/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式识别：纯规则打分（关键词 + 正则 + 意图标签 + 前台 App + 屏幕特征 + 例句字二元组模糊匹配），零模型调用、零 IO。
 * 阈值 + Top-K + 总字数预算 + 冲突裁决；可选 {@link SkillSession} 做多轮保持 / 话题切换卸载。
 * 关键词 / 排除词 / 正则都对「小写、去空白和标点」后的原话匹配（正则里别写空格和标点）。
 * 每个命中 / 卸载都出一行 {@code [SKILL] id score reason}。
 */
public final class SkillMatcher {

    /** 一次识别的输入。除 query 外都可空（空 = 不参与打分 / 不做门槛）。 */
    public static final class Input {
        public String query = "";
        /** "agent" / "ask"。 */
        public String path = "agent";
        /** 意图标签（宿主给：候选工具前几名的 domain / category 等），小写比较。 */
        public Collection<String> intents;
        public String foregroundPkg;
        /** 唤醒时屏幕上的文字（不额外截屏，宿主有就给）。 */
        public Collection<String> screenTexts;
        /** 本轮工具表（签名）；null = 不检查 requires.tools。 */
        public Collection<String> tools;
        /** null = 不知道（不按 root 过滤）。 */
        public Boolean root;

        public Input(String query) {
            this.query = query == null ? "" : query;
        }
    }

    public static final class Config {
        public int threshold = 5, keepThreshold = 2, topK = 2, totalBudget = 1500, minSection = 120;
        public double fuzzyMin = 0.45;
    }

    /** 一个技能这次的得分与原因。 */
    public static final class Scored {
        public final Skill skill;
        public final int score;
        public final String reason;

        Scored(Skill s, int score, String reason) {
            this.skill = s;
            this.score = score;
            this.reason = reason;
        }

        @Override public String toString() {
            return skill.id + " " + score + " " + reason;
        }
    }

    /** 选中的技能 + 渲染好的提示词块 + 日志。 */
    public static final class Selection {
        public final List<Scored> picked;
        public final String block;
        public final List<String> log;
        public final int chars;

        Selection(List<Scored> picked, String block, List<String> log) {
            this.picked = Collections.unmodifiableList(picked);
            this.block = block;
            this.log = Collections.unmodifiableList(log);
            this.chars = block.length();
        }

        public List<String> ids() {
            List<String> l = new ArrayList<String>();
            for (Scored s : picked) l.add(s.skill.id);
            return l;
        }

        public boolean isEmpty() {
            return picked.isEmpty();
        }
    }

    public static final String HEADER = "【技能（按本条指令自动加载，只是做法参考：工具以工具列表为准，和铁律冲突时听铁律）】";

    private final SkillIndex index;
    private final Config cfg;

    public SkillMatcher(SkillIndex index, Config cfg) {
        this.index = index;
        this.cfg = cfg == null ? new Config() : cfg;
    }

    public Config config() {
        return cfg;
    }

    // ------------------------------------------------------------------ 打分

    public Scored score(Skill s, Input in) {
        String q = norm(in.query);
        StringBuilder why = new StringBuilder();
        for (String ex0 : s.exclude) {
            String ex = norm(ex0);
            if (ex.length() > 0 && q.contains(ex)) return new Scored(s, 0, "exclude:" + ex);
        }
        int kw = 0;
        for (String k0 : s.keywords) {
            String k = norm(k0);
            if (k.length() == 0 || !q.contains(k)) continue;
            int add = k.length() >= 4 ? 4 : 3;
            kw += add;
            why.append("kw:").append(k).append('+').append(add).append(',');
        }
        kw = Math.min(kw, 8);
        int rx = 0;
        for (Pattern p : s.regex) {
            Matcher m = p.matcher(q);
            if (m.find()) {
                rx += 5;
                why.append("re:").append(clip(m.group(), 12)).append("+5,");
            }
        }
        rx = Math.min(rx, 8);
        int it = 0;
        if (in.intents != null) for (String i : in.intents) {
            String li = i == null ? "" : i.toLowerCase(Locale.ROOT);
            if (li.length() > 0 && s.intents.contains(li)) {
                it += 2;
                why.append("intent:").append(li).append("+2,");
            }
        }
        it = Math.min(it, 4);
        int pk = 0;
        if (in.foregroundPkg != null && in.foregroundPkg.length() > 0) for (String p : s.packages)
            if (p.equals(in.foregroundPkg)) {
                pk = 3;
                why.append("pkg:").append(p).append("+3,");
                break;
            }
        int sc = 0;
        if (in.screenTexts != null && !s.screen.isEmpty()) for (String f : s.screen) {
            for (String t : in.screenTexts) if (t != null && t.contains(f)) {
                sc += 2;
                why.append("screen:").append(f).append("+2,");
                break;
            }
        }
        sc = Math.min(sc, 4);
        int fz = 0;
        if (!s.examples.isEmpty() && q.length() >= 2) {
            double best = 0;
            for (String ex : s.examples) best = Math.max(best, cosine(q, norm(ex)));
            if (best >= cfg.fuzzyMin) {
                fz = (int) Math.round(best * 6);
                why.append("fuzzy:").append(String.format(Locale.ROOT, "%.2f", best)).append('+').append(fz).append(',');
            }
        }
        int total = kw + rx + it + pk + sc + fz;
        if (why.length() > 0) why.setLength(why.length() - 1);
        return new Scored(s, total, why.length() == 0 ? "-" : why.toString());
    }

    /** 全部技能打分（只含本路径适用的），按分数降序、priority 降序、id 升序。 */
    public List<Scored> scoreAll(Input in) {
        List<Scored> out = new ArrayList<Scored>();
        for (Skill s : index.skills()) if (s.forPath(in.path)) out.add(score(s, in));
        Collections.sort(out, ORDER);
        return out;
    }

    static final Comparator<Scored> ORDER = new Comparator<Scored>() {
        @Override public int compare(Scored a, Scored b) {
            if (a.score != b.score) return b.score - a.score;
            if (a.skill.priority != b.skill.priority) return b.skill.priority - a.skill.priority;
            return a.skill.id.compareTo(b.skill.id);
        }
    };

    /** 能不能用：requires.tools 至少一个在本轮工具表里；要 root 而宿主说没有 → 不能。返回 null = 能用，否则原因。 */
    static String unusable(Skill s, Input in) {
        if (in.tools != null && !s.tools.isEmpty()) {
            boolean any = false;
            for (String t : s.tools) if (in.tools.contains(t)) any = true;
            if (!any) return "no_tools";
        }
        if (s.root && in.root != null && !in.root.booleanValue()) return "no_root";
        return null;
    }

    // ------------------------------------------------------------------ 选择

    /** 无会话（单次 / 无人值守）。 */
    public Selection select(Input in) {
        return select(in, null, 0L);
    }

    /**
     * 选技能：本轮达阈值的 → 冲突裁决 → 会话保持（上一轮加载的，本轮仍有一点分或是追问 → 保留；否则卸载）→ Top-K → 预算渲染。
     * session 非空时会更新它。
     */
    public Selection select(Input in, SkillSession session, long nowMs) {
        List<String> log = new ArrayList<String>();
        List<Scored> all = scoreAll(in);
        Map<String, Scored> byId = new HashMap<String, Scored>();
        for (Scored s : all) byId.put(s.skill.id, s);
        List<Scored> fresh = new ArrayList<Scored>();
        for (Scored s : all) {
            if (s.score < cfg.threshold) continue;
            String why = unusable(s.skill, in);
            if (why != null) {
                log.add("[SKILL] " + s.skill.id + " " + s.score + " skip:" + why);
                continue;
            }
            fresh.add(s);
        }
        // 会话保持
        List<Scored> held = new ArrayList<Scored>();
        Set<String> freshIds = new HashSet<String>();
        for (Scored s : fresh) freshIds.add(s.skill.id);
        if (session != null) {
            boolean followUp = session.isFollowUp(in.query, nowMs, fresh.isEmpty());
            for (String id : session.loaded()) {
                if (freshIds.contains(id)) continue;
                Scored s = byId.get(id);
                if (s == null) {
                    log.add("[SKILL] " + id + " 0 unload:not_this_path");
                    continue;
                }
                String bad = unusable(s.skill, in);
                if (bad != null) {
                    log.add("[SKILL] " + id + " " + s.score + " unload:" + bad);
                    continue;
                }
                if (session.stale(id, nowMs)) {
                    log.add("[SKILL] " + id + " " + s.score + " unload:stale");
                } else if (s.score >= cfg.keepThreshold) {
                    held.add(new Scored(s.skill, s.score, "hold:" + s.reason));
                } else if (followUp) {
                    held.add(new Scored(s.skill, s.score, "hold:follow_up"));
                } else {
                    log.add("[SKILL] " + id + " " + s.score + " unload:topic_switch");
                }
            }
        }
        // 冲突裁决（本轮新命中优先于保持的；同为新命中按分数顺序）
        List<Scored> cand = new ArrayList<Scored>(fresh);
        cand.addAll(held);
        List<Scored> kept = new ArrayList<Scored>();
        for (Scored s : cand) {
            Scored clash = null;
            for (Scored k : kept) if (k.skill.conflicts.contains(s.skill.id) || s.skill.conflicts.contains(k.skill.id)) clash = k;
            if (clash != null) {
                log.add("[SKILL] " + s.skill.id + " " + s.score + " drop:conflict:" + clash.skill.id);
                continue;
            }
            kept.add(s);
        }
        // Top-K
        if (kept.size() > cfg.topK) {
            for (Scored s : kept.subList(cfg.topK, kept.size())) log.add("[SKILL] " + s.skill.id + " " + s.score + " drop:topk");
            kept = new ArrayList<Scored>(kept.subList(0, cfg.topK));
        }
        // 预算渲染
        List<Scored> picked = new ArrayList<Scored>();
        StringBuilder block = new StringBuilder();
        int left = cfg.totalBudget - HEADER.length() - 1;
        for (Scored s : kept) {
            String sec = section(s.skill, Math.min(s.skill.budget, left));
            if (sec == null) {
                log.add("[SKILL] " + s.skill.id + " " + s.score + " drop:budget");
                continue;
            }
            if (block.length() == 0) block.append(HEADER);
            block.append('\n').append(sec);
            left -= sec.length() + 1;
            picked.add(s);
            log.add("[SKILL] " + s.skill.id + " " + s.score + " " + s.reason + " chars=" + sec.length());
        }
        if (session != null) {
            List<String> hitIds = new ArrayList<String>();
            List<String> ids = new ArrayList<String>();
            for (Scored s : picked) {
                ids.add(s.skill.id);
                if (freshIds.contains(s.skill.id)) hitIds.add(s.skill.id);
            }
            session.update(ids, hitIds, nowMs);
        }
        return new Selection(picked, block.toString(), log);
    }

    /** 单独渲染一个技能（LLM 兜底用）：表头 + 一段，同样受总预算约束；放不下返回 null。 */
    public String render(Skill s) {
        String sec = section(s, Math.min(s.budget, cfg.totalBudget - HEADER.length() - 1));
        return sec == null ? null : HEADER + "\n" + sec;
    }

    /** 渲染一段：标题（含来源文件）+ 正文，按行截到 limit 字；放不下标题 + 一点正文返回 null。 */
    String section(Skill s, int limit) {
        String title = "### " + s.name + "（来源 " + (s.source.length() > 0 ? s.source : "skill_" + s.id + ".md") + "）";
        if (limit < cfg.minSection || limit <= title.length() + 20) return null;
        int room = limit - title.length() - 1;
        String body = s.body;
        if (body.length() > room) {
            String cut = body.substring(0, room - 1);
            int nl = cut.lastIndexOf('\n');
            if (nl > room / 2) cut = cut.substring(0, nl);
            body = cut + "…";
        }
        return title + "\n" + body;
    }

    // ------------------------------------------------------------------ 工具

    /** 小写、去空白与常见标点（中英文）。 */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            if (Character.isWhitespace(c) || "，。！？、,.!?；;：:\"'“”‘’（）()【】[]《》<>~～…".indexOf(c) >= 0) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** 字二元组（含首尾单字）向量的余弦相似度：轻量模糊匹配，无词典无依赖。 */
    static double cosine(String a, String b) {
        Map<String, Integer> va = grams(a), vb = grams(b);
        if (va.isEmpty() || vb.isEmpty()) return 0;
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Integer> e : va.entrySet()) {
            na += e.getValue() * e.getValue();
            Integer o = vb.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (Integer v : vb.values()) nb += v * v;
        return dot / Math.sqrt(na * nb);
    }

    static Map<String, Integer> grams(String s) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        if (s.length() == 1) m.put(s, 1);
        for (int i = 0; i + 1 < s.length(); i++) {
            String g = s.substring(i, i + 2);
            Integer c = m.get(g);
            m.put(g, c == null ? 1 : c + 1);
        }
        return m;
    }

    static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }
}
