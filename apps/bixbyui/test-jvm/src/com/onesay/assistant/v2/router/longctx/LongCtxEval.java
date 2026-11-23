package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.plug.skills.core.SkillSession;
import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.router.L2LLMCompilerAccess;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Kind;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Q;
import com.onesay.assistant.v2.router.longctx.LongCtxEvalData.Scenario;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ★ fix/1123-longctx-phone：长会话评测器。用真的 ConversationMemory / L2LLMCompiler.buildBody（改后再加真的长对话组件）
 * 组请求体，喂给 {@link FakeLongCtxModel}，按 {@link LongCtxEvalData} 的标签判分：复读率、偏题率、遗忘率、幻觉率、
 * 人设违规率、技能准则遵守率、重活外溢（重活过程原文留在后续请求里的字数）、平均请求 token。
 */
public final class LongCtxEval {

    /** 一种「问答怎么组请求」的做法：旧做法 = {@link Legacy}；新做法在 LongCtxEvalNewDriver（改后才有）。 */
    public interface Driver {
        String name();

        /** 开一个新会话。 */
        void begin(int budgetTokens, final long[] clock, SkillIndex skills);

        /** 一轮自由问答，返回终稿。 */
        String ask(String query, String system, FakeLongCtxModel model) throws Exception;

        /** 重活（识屏 / 代操作）把回执记进会话（不走问答模型）。 */
        void heavy(String query, String receipt) throws Exception;

        /** 自检重答次数（旧做法 0）。 */
        int retries();

        /** 本场自动接力次数（旧做法 0）。 */
        int relays();
    }

    /** 评测用的系统提示词：人设口吻放在系统提示词里（等同 soul 前缀），两种做法一样。 */
    public static final String SYSTEM = "你是用户手机上的语音助手。回答说简单的大白话，务实，先给结论；可以留一点憨，但"
            + FakeLongCtxModel.PERSONA_MARK + "。拿不准的事如实说不知道，不要编。\n"
            + "（以下模拟其余系统提示词：工具说明、富内容规则、十条铁律……）\n" + rep("规则正文。", 120);

    /** 评测用技能：出行（只在 ask 路径），正文要求答复末尾附出行清单。 */
    public static final String TRIP_SKILL = "---\nid: trip_eval\nname: 出行规划\ndescription: 出差 / 旅行的行程、住宿、预算\npriority: 60\nbudget: 400\n"
            + "paths: [ask]\ntriggers:\n  keywords: [出差, 行程, 旅行]\n  examples: [帮我规划去成都出差的行程, 下周旅行怎么安排]\nrequires:\n  tools: []\n---\n"
            + "1. 先给行程结论，再说住宿和交通。\n2. 每次答复最后附一行" + FakeLongCtxModel.SKILL_MARK + "：证件 / 充电器 / 换洗衣物。\n";

    static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    public static SkillIndex skills() {
        SkillIndex i = new SkillIndex();
        i.add("skill_trip_eval.md", TRIP_SKILL);
        return i;
    }

    /** 旧做法（= 1ee84af 的 L2LLMCompiler.ask：系统提示词 + 技能块，历史快照，本轮原话；记终稿）。 */
    public static final class Legacy implements Driver {
        ConversationMemory mem;
        SkillMatcher matcher;
        final SkillSession session = new SkillSession();
        long[] clock;

        @Override public String name() {
            return "改前（旧做法）";
        }

        @Override public void begin(int budget, final long[] clock, SkillIndex skills) {
            this.clock = clock;
            mem = ConversationMemory.newForTest(new ConversationMemory.Clock() {
                @Override public long now() {
                    return clock[0];
                }
            });
            mem.setBudgetTokens(budget);
            matcher = new SkillMatcher(skills, new SkillMatcher.Config());
            session.reset();
        }

        @Override public String ask(String query, String system, FakeLongCtxModel model) throws Exception {
            if (mem.snapshot().isEmpty()) session.reset();
            SkillMatcher.Input in = new SkillMatcher.Input(query);
            in.path = "ask";
            SkillMatcher.Selection sel = matcher.select(in, session, clock[0]);
            String sys = sel.isEmpty() ? system : system + "\n\n" + sel.block;
            String body = L2LLMCompilerAccess.buildBody(sys, mem.snapshot(), query, 800, false);
            String a = polish(model.answer(body));
            if (a == null) return null;
            mem.record(query, a, false);
            return a;
        }

        @Override public void heavy(String query, String receipt) {
            mem.record(query, receipt, true);
        }

        @Override public int retries() {
            return 0;
        }

        @Override public int relays() {
            return 0;
        }
    }

    /** 与 1ee84af 的 L2LLMCompiler.ask 一样：extractAnswerText → polishKeepChoice（私有方法，反射调；改前改后都能编）。 */
    static String polish(String content) throws Exception {
        if (content == null) return null;
        java.lang.reflect.Method ex = com.onesay.assistant.v2.router.L2LLMCompiler.class.getDeclaredMethod("extractAnswerText", String.class);
        java.lang.reflect.Method po = com.onesay.assistant.v2.router.L2LLMCompiler.class.getDeclaredMethod("polishKeepChoice", String.class);
        ex.setAccessible(true);
        po.setAccessible(true);
        String a = (String) ex.invoke(null, content);
        if (a == null || a.length() == 0) return null;
        a = (String) po.invoke(null, a);
        return a == null || a.length() == 0 ? null : a;
    }

    /** 一场的计数。 */
    public static final class Score {
        public String name;
        public int turns, judged, offTopic, echo, repeat, recall, forgot, halluc, persona, trip, tripOk, requests, retries, relays;
        public long reqTokens, heavyChars, heavyReqs;

        void add(Score o) {
            turns += o.turns;
            judged += o.judged;
            offTopic += o.offTopic;
            echo += o.echo;
            repeat += o.repeat;
            recall += o.recall;
            forgot += o.forgot;
            halluc += o.halluc;
            persona += o.persona;
            trip += o.trip;
            tripOk += o.tripOk;
            requests += o.requests;
            retries += o.retries;
            relays += o.relays;
            reqTokens += o.reqTokens;
            heavyChars += o.heavyChars;
            heavyReqs += o.heavyReqs;
        }

        public double rate(int n, int d) {
            return d == 0 ? 0 : 100.0 * n / d;
        }

        public double echoRate() {
            return rate(echo + repeat, judged + recall);
        }

        public double offTopicRate() {
            return rate(offTopic, judged);
        }

        public double forgetRate() {
            return rate(forgot + halluc, recall);
        }

        public double hallucRate() {
            return rate(halluc, recall);
        }

        public double personaRate() {
            return rate(persona, judged + recall);
        }

        public double skillRate() {
            return rate(tripOk, trip);
        }

        public long avgTokens() {
            return requests == 0 ? 0 : reqTokens / requests;
        }

        public long heavyPerReq() {
            return heavyReqs == 0 ? 0 : heavyChars / heavyReqs;
        }

        public String row() {
            return String.format(Locale.US, "| %s | %d | %.1f%% | %.1f%% | %s | %s | %.1f%% | %s | %d | %d | %d | %d |", name, turns, echoRate(), offTopicRate(),
                    recall == 0 ? "—" : String.format(Locale.US, "%.1f%%", forgetRate()), recall == 0 ? "—" : String.format(Locale.US, "%.1f%%", hallucRate()),
                    personaRate(), trip == 0 ? "—" : String.format(Locale.US, "%.1f%%", skillRate()), heavyPerReq(), avgTokens(), retries, relays);
        }
    }

    public static final String HEADER = "| 场景 | 轮数 | 复读率 | 偏题率 | 遗忘率（含幻觉） | 幻觉率 | 人设违规率 | 技能准则遵守率 | 重活外溢（字/请求） | 平均请求 token | 自检重答 | 自动接力 |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|";

    /** 跑全部场景；返回 [每场..., 合计]。 */
    public static List<Score> run(Driver d, int budgetTokens) throws Exception {
        return run(d, budgetTokens, 0);
    }

    /** ignorePct：假模型加压（见 FakeLongCtxModel.ignoreMarksPct）。 */
    public static List<Score> run(Driver d, int budgetTokens, int ignorePct) throws Exception {
        List<Score> out = new ArrayList<Score>();
        Score total = new Score();
        total.name = "合计";
        for (Scenario s : LongCtxEvalData.all()) {
            Score sc = runOne(d, s, budgetTokens, ignorePct);
            out.add(sc);
            total.add(sc);
        }
        out.add(total);
        return out;
    }

    static Score runOne(Driver d, Scenario s, int budget, int ignorePct) throws Exception {
        Score sc = new Score();
        sc.name = s.name;
        long[] clock = {1000L};
        d.begin(budget, clock, skills());
        FakeLongCtxModel model = new FakeLongCtxModel();
        model.ignoreMarksPct = ignorePct;
        List<String> prevAnswers = new ArrayList<String>();
        List<Q> prevTargets = new ArrayList<Q>();
        boolean heavySeen = false;
        int before = d.retries();
        for (int i = 0; i < s.turns.size(); i++) {
            Q q = s.turns.get(i);
            clock[0] += 10_000L;
            model.at(s.seed, i);
            sc.turns++;
            if (q.kind == Kind.HEAVY) {
                d.heavy(q.text, q.receipt);
                heavySeen = true;
                prevAnswers.add(q.answer);
                prevTargets.add(q);
                continue;
            }
            int nb = model.bodies.size();
            String a = d.ask(q.text, SYSTEM, model);
            for (int k = nb; k < model.bodies.size(); k++) {
                String body = model.bodies.get(k);
                sc.requests++;
                sc.reqTokens += tokensOf(body);
                if (heavySeen) {
                    sc.heavyChars += heavyChars(body);
                    sc.heavyReqs++;
                }
            }
            if (a == null) a = "";
            if (a.contains("你问的是「")) sc.echo++;
            if (a.contains("呗") || a.contains("啥") || a.contains("咋")) sc.persona++;
            if (q.kind == Kind.RECALL) {
                sc.recall++;
                if (a.contains(q.wrongValue)) sc.halluc++;
                else if (!a.contains(q.value)) sc.forgot++;
                if (!a.contains(q.value) && Boolean.getBoolean("longctx.eval.debug")) System.out.println("[遗忘] " + d.name() + " " + s.name + " #" + i + " 问「" + q.text + "」答「" + a + "」 请求末尾：" + tailOf(model.bodies.get(model.bodies.size() - 1)));
            } else {
                sc.judged++;
                Q got = null;
                for (Q p : LongCtxEvalData.pool()) if (p.answer != null && a.contains(p.answer)) got = p;
                if (got != q) {
                    if (Boolean.getBoolean("longctx.eval.debug")) System.out.println("[偏题] " + d.name() + " " + s.name + " #" + i + " 问「" + q.text + "」答「" + a + "」");
                    sc.offTopic++;
                    // 答成了之前答过的那条 = 复读旧答案
                    if (got != null && prevTargets.contains(got)) sc.repeat++;
                }
                if (q.kind == Kind.TRIP) {
                    sc.trip++;
                    if (a.contains(FakeLongCtxModel.SKILL_MARK)) sc.tripOk++;
                }
            }
            prevAnswers.add(a);
            prevTargets.add(q);
        }
        sc.retries = d.retries() - before;
        sc.relays = d.relays();
        return sc;
    }

    static String tailOf(String body) {
        try {
            JSONArray ms = new JSONObject(body).getJSONArray("messages");
            String c = ms.getJSONObject(ms.length() - 1).optString("content", "");
            return c.length() > 400 ? c.substring(c.length() - 400) : c;
        } catch (Exception e) {
            return "?";
        }
    }

    static int tokensOf(String body) throws Exception {
        JSONArray ms = new JSONObject(body).getJSONArray("messages");
        int n = 0;
        for (int i = 0; i < ms.length(); i++) n += com.onesay.assistant.v2.router.ctx.TokenEstimator.message(ms.getJSONObject(i).optString("content", ""));
        return n;
    }

    /** 请求里还留着多少「重活过程原文」：代操作每步的「gui.unfold{」行、识屏全文的「群消息：」行。 */
    static int heavyChars(String body) throws Exception {
        JSONArray ms = new JSONObject(body).getJSONArray("messages");
        int n = 0;
        for (int i = 0; i < ms.length(); i++) {
            for (String line : ms.getJSONObject(i).optString("content", "").split("\n")) {
                if (line.contains("gui.unfold{") || line.contains("群消息：")) n += line.length();
            }
        }
        return n;
    }

    public static String table(List<Score> rows) {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        for (Score s : rows) b.append(s.row()).append('\n');
        return b.toString();
    }
}
