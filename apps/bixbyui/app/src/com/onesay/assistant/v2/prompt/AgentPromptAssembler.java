package com.onesay.assistant.v2.prompt;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.agenttools.ToolTier;
import com.onesay.assistant.v2.agenttools.ToolTiers;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ arch/tool-tiers（A 路）：AgentLoop 系统提示词的分段拼装。
 *
 * <p>文字全部在 {@code assets/agent_prompt.md}（格式见文件头注释）；这里只做：解析段 → 按当轮实际挂载的工具
 * （tools 数组里的函数名）挑段、挑行 → 替换占位符 → 每段按预算截断 → 总长超预算时丢 optional 段。
 * 人设 / 记忆前缀（SoulHooks.withPrefix）不经过这里，仍由调用方加在最前。
 */
public final class AgentPromptAssembler {

    private static final String TAG = "AgentPrompt";

    private AgentPromptAssembler() {}

    /** 系统提示词（不含人设 / 记忆前缀）总预算（字）。 */
    public static final int TOTAL_BUDGET = 6000;   // 合并后（含 B 路 loop_tools 段）约 4.3 千字

    /** 资源文件读不到时的最小兜底（只为不让循环裸奔；正常不会走到）。 */
    static final String FALLBACK = "你是智能手机语音助手的执行体。需要真实数据或操作时调用工具；做法的先后：接口 > 查信息 > 深链 > 点界面。"
            + "绝不编造数据，用户没说的必填参数先追问，只使用提供的工具。\n";

    // ------------------------------------------------------------------ 解析

    static final Pattern SECTION = Pattern.compile("^<!-- section: ([A-Za-z0-9_.]+)(.*?)-->\\s*$");
    static final Pattern LINE_NEEDS = Pattern.compile("^<!-- (needs|needs-not): ([^>]*?) -->\\s?");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)\\}\\}");

    public static final class Line {
        final String text;
        final List<String> needs;      // 任一满足才保留（空 = 不限）
        final List<String> needsNot;   // 任一满足就去掉

        Line(String text, List<String> needs, List<String> needsNot) {
            this.text = text;
            this.needs = needs;
            this.needsNot = needsNot;
        }
    }

    public static final class Section {
        public final String id;
        public final List<String> needs;
        public final int budget;
        public final boolean optional;
        final List<Line> lines = new ArrayList<Line>();

        Section(String id, List<String> needs, int budget, boolean optional) {
            this.id = id;
            this.needs = needs;
            this.budget = budget;
            this.optional = optional;
        }

        public boolean isNudge() {
            return id.startsWith("nudge.");
        }
    }

    public static List<Section> parse(String text) {
        List<Section> out = new ArrayList<Section>();
        if (text == null) return out;
        Section cur = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher m = SECTION.matcher(line);
            if (m.matches()) {
                List<String> needs = new ArrayList<String>();
                int budget = 0;
                boolean optional = false;
                for (String attr : m.group(2).split("\\|")) {
                    String a = attr.trim();
                    if (a.startsWith("needs:")) needs = splitList(a.substring(6));
                    else if (a.startsWith("budget:")) budget = Integer.parseInt(a.substring(7).trim());
                    else if (a.equals("optional")) optional = true;
                }
                cur = new Section(m.group(1), needs, budget, optional);
                out.add(cur);
                continue;
            }
            if (cur == null) continue;   // 文件头注释
            Matcher n = LINE_NEEDS.matcher(line);
            if (n.find()) {
                List<String> list = splitList(n.group(2));
                String rest = line.substring(n.end());
                if ("needs".equals(n.group(1))) cur.lines.add(new Line(rest, list, Collections.<String>emptyList()));
                else cur.lines.add(new Line(rest, Collections.<String>emptyList(), list));
            } else {
                cur.lines.add(new Line(line, Collections.<String>emptyList(), Collections.<String>emptyList()));
            }
        }
        return out;
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<String>();
        for (String x : s.split(",")) {
            String t = x.trim();
            if (t.length() > 0) out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ------------------------------------------------------------------ 条件

    /** 当轮挂载的工具：签名（小写）+ 各档是否有。 */
    public static final class Mounted {
        final Set<String> signs = new LinkedHashSet<String>();
        final Map<ToolTier, List<String>> byTier = new LinkedHashMap<ToolTier, List<String>>();
        /** ★ arch/integrate：常驻控制类（task.todo / ask_user / end_not_done）—— 不归任何档。 */
        final List<String> resident = new ArrayList<String>();

        public Mounted(Iterable<String> signList) {
            for (ToolTier t : ToolTier.values()) byTier.put(t, new ArrayList<String>());
            for (String s : signList) {
                if (s == null) continue;
                String sign = s.toLowerCase(Locale.ROOT);
                if (!signs.add(sign)) continue;
                if (ToolTiers.isResident(sign)) { resident.add(s); continue; }
                byTier.get(tierOf(s)).add(s);
            }
        }

        public List<String> resident() {
            return resident;
        }

        public static Mounted of(JSONArray tools) {
            return new Mounted(ToolTiers.mountedSigns(tools));
        }

        boolean has(String cond) {
            if (cond.startsWith("plug:")) return plugOn(cond.substring(5));   // [richout] 插件开关当条件（不是工具）
            if (cond.startsWith("tier:")) {
                try {
                    return !byTier.get(ToolTier.valueOf(cond.substring(5).toUpperCase(Locale.ROOT))).isEmpty();
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
            return signs.contains(cond);
        }

        boolean any(List<String> conds) {
            for (String c : conds) if (has(c)) return true;
            return false;
        }

        public List<String> inTier(ToolTier t) {
            return byTier.get(t);
        }
    }

    /** [richout] 行 / 段条件 {@code plug:<id>}：目前只认 richout（宿主按变体 + 开关裁决；release 变体 JVM 单测里为关 = 原文）。 */
    static boolean plugOn(String id) {
        if ("richout".equals(id)) return com.onesay.assistant.plug.richout.host.RichOutHost.on();
        return false;
    }

    static ToolTier tierOf(String sign) {
        try {
            Capability c = CapabilityRegistry.getInstance().describe(sign);
            if (c != null) return ToolTiers.of(c);
        } catch (Throwable ignored) {
        }
        return ToolTiers.ofSign(sign);
    }

    // ------------------------------------------------------------------ 拼装

    public static final class Result {
        public final String text;
        /** 每段拼好后的字数（按拼装顺序）。 */
        public final Map<String, Integer> sizes;
        public final List<String> overBudget;
        public final List<String> dropped;

        Result(String text, Map<String, Integer> sizes, List<String> overBudget, List<String> dropped) {
            this.text = text;
            this.sizes = sizes;
            this.overBudget = overBudget;
            this.dropped = dropped;
        }
    }

    /**
     * @param strict true（单测）= 占位符漏填直接抛 {@link IllegalStateException}；false = 去掉占位符并打日志
     */
    public static Result assemble(List<Section> sections, Mounted mounted, Map<String, String> vars, boolean strict) {
        List<String> ids = new ArrayList<String>();
        List<String> texts = new ArrayList<String>();
        List<Boolean> optional = new ArrayList<Boolean>();
        List<String> over = new ArrayList<String>();
        for (Section s : sections) {
            if (s.isNudge()) continue;
            if (!s.needs.isEmpty() && !mounted.any(s.needs)) continue;
            String body = render(s, mounted, vars, strict);
            if (body.trim().length() == 0) continue;
            if (s.budget > 0 && body.length() > s.budget) {
                over.add(s.id + ":" + body.length() + ">" + s.budget);
                Log.w(TAG, "段 " + s.id + " 超预算 " + body.length() + " > " + s.budget + "，截断");
                body = cut(body, s.budget);
            }
            ids.add(s.id);
            texts.add(body);
            optional.add(s.optional);
        }
        List<String> dropped = new ArrayList<String>();
        while (total(texts) > TOTAL_BUDGET) {
            int victim = -1;
            for (int i = texts.size() - 1; i >= 0; i--) if (optional.get(i)) { victim = i; break; }
            if (victim < 0) break;
            dropped.add(ids.get(victim));
            ids.remove(victim);
            texts.remove(victim);
            optional.remove(victim);
        }
        if (!dropped.isEmpty()) Log.w(TAG, "总长超预算 " + TOTAL_BUDGET + "，丢弃段 " + dropped);
        Map<String, Integer> sizes = new LinkedHashMap<String, Integer>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(texts.get(i));
            sizes.put(ids.get(i), texts.get(i).length());
        }
        return new Result(sb.toString(), sizes, over, dropped);
    }

    static int total(List<String> texts) {
        int n = 0;
        for (String t : texts) n += t.length() + 2;
        return n;
    }

    static String render(Section s, Mounted mounted, Map<String, String> vars, boolean strict) {
        StringBuilder sb = new StringBuilder();
        for (Line l : s.lines) {
            if (!l.needs.isEmpty() && !mounted.any(l.needs)) continue;
            if (!l.needsNot.isEmpty() && mounted.any(l.needsNot)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.text);
        }
        String tpl = sb.toString().trim();
        return substitute(s.id, tpl, vars, strict);
    }

    static String substitute(String id, String tpl, Map<String, String> vars, boolean strict) {
        Matcher m = PLACEHOLDER.matcher(tpl);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = vars == null ? null : vars.get(m.group(1));
            if (v == null) {
                if (strict) throw new IllegalStateException("提示词段 " + id + " 的占位符 {{" + m.group(1) + "}} 没填");
                Log.w(TAG, "段 " + id + " 占位符 {{" + m.group(1) + "}} 没填，去掉");
                v = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String cut(String body, int budget) {
        int nl = body.lastIndexOf('\n', budget);
        return nl > budget / 2 ? body.substring(0, nl) : body.substring(0, budget);
    }

    // ------------------------------------------------------------------ 生产入口

    private static volatile List<Section> sParsed;
    private static volatile String sParsedFrom;

    static List<Section> sections(Context ctx) {
        String text = PromptSource.agentPrompt(ctx);
        if (text == null) return null;
        List<Section> p = sParsed;
        if (p != null && text.equals(sParsedFrom)) return p;
        p = parse(text);
        sParsed = p;
        sParsedFrom = text;
        return p;
    }

    /** 占位符取值（时间、工具数、各档清单、探针预算、应用选择规则）。 */
    public static Map<String, String> vars(Mounted mounted, int toolCount) {
        Map<String, String> v = new HashMap<String, String>();
        v.put("PROBE_BUDGET_MAX", String.valueOf(com.onesay.assistant.v2.router.AgentLoop.PROBE_BUDGET_MAX));
        v.put("TIME_BLOCK", com.onesay.assistant.v2.router.TimeContext.block().trim());
        v.put("TOOL_COUNT", String.valueOf(toolCount));
        v.put("TIER_INVENTORY", tierInventory(mounted));
        v.put("APP_CHOICE_RULE", com.onesay.assistant.v2.roles.AppChoiceSpec.PROMPT_RULE);
        v.put("OWNER_CHARTER", OwnerCharter.BLOCK);   // ★ 11-03：主人守则（人设之后、工具说明之前）
        return v;
    }

    /** 「接口 N 个；查信息：a、b；深链：c、d…（共 M 个）；点界面：收起（入口 gui.unfold）」。 */
    public static String tierInventory(Mounted m) {
        StringBuilder sb = new StringBuilder();
        sb.append(ToolTier.API.label).append(' ').append(m.inTier(ToolTier.API).size()).append(" 个");
        appendNames(sb, ToolTier.INFO, m.inTier(ToolTier.INFO));
        appendNames(sb, ToolTier.DEEPLINK, m.inTier(ToolTier.DEEPLINK));
        sb.append("；").append(ToolTier.GUI.label).append('：');
        if (m.has(ToolTiers.SIGN_GUI_OPERATE)) sb.append("phone.operate（只在前三档都不适用时用）");
        else if (m.has(ToolTiers.SIGN_GUI_ENTRY)) sb.append("已收起（需要时先调 gui.unfold）");
        else sb.append("本轮不可用");
        if (!m.resident().isEmpty()) {   // ★ arch/integrate
            sb.append("；常驻：");
            for (int i = 0; i < m.resident().size(); i++) {
                if (i > 0) sb.append('、');
                sb.append(m.resident().get(i));
            }
        }
        sb.append('。');
        return sb.toString();
    }

    private static void appendNames(StringBuilder sb, ToolTier t, List<String> names) {
        sb.append("；").append(t.label).append('：');
        if (names.isEmpty()) { sb.append("无"); return; }
        int show = Math.min(6, names.size());
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append('、');
            sb.append(names.get(i));
        }
        if (names.size() > show) sb.append("…（共 ").append(names.size()).append(" 个）");
    }

    /** 生产：按 tools 数组（当轮实际挂载）拼系统提示词（不含人设前缀）。 */
    public static String build(Context ctx, JSONArray tools) {
        return build(ctx, Mounted.of(tools), tools == null ? 0 : tools.length());
    }

    public static String build(Context ctx, Mounted mounted, int toolCount) {
        List<Section> secs = sections(ctx);
        if (secs == null || secs.isEmpty()) {
            Log.e(TAG, "提示词资源 " + PromptSource.AGENT_PROMPT_ASSET + " 读不到，用最小兜底");
            return FALLBACK + com.onesay.assistant.v2.roles.AppChoiceSpec.PROMPT_RULE;
        }
        Result r = assemble(secs, mounted, vars(mounted, toolCount), false);
        Log.i(TAG, "[PROMPT] 段 " + r.sizes + " 共 " + r.text.length() + " 字"
                + (r.overBudget.isEmpty() ? "" : " 超预算 " + r.overBudget)
                + (r.dropped.isEmpty() ? "" : " 丢弃 " + r.dropped));
        return r.text;
    }

    /**
     * 旧签名（只知道工具条数）：按「常见挂载」拼 —— 探针 + app.launch + 读屏 + GUI 入口（折叠态）。
     */
    public static final List<String> DEFAULT_MOUNT = Collections.unmodifiableList(Arrays.asList(
            "probe.apps", "probe.app_intents", "probe.shell_read", "app.launch", "vision.screen.understand",
            "mail.inbox.latest", ToolTiers.SIGN_GUI_ENTRY));

    public static String buildLegacy(int toolCount) {
        return build(null, new Mounted(DEFAULT_MOUNT), toolCount);
    }

    /** 循环中途的提醒段（nudge.*），占位符同系统提示词。读不到返回 fallback。 */
    public static String text(String sectionId, String fallback) {
        List<Section> secs = sections(null);
        if (secs != null) {
            for (Section s : secs) {
                if (!s.id.equals(sectionId)) continue;
                Mounted none = new Mounted(Collections.<String>emptyList());
                return render(s, none, vars(none, 0), false);
            }
        }
        return fallback;
    }
}
