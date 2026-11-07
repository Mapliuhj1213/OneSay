package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ★ fix/triage-fast：一句多意图的零 LLM 拆分（「把亮度调低、开勿扰、10 分钟后提醒我」）。
 *
 * <p>按标点（、，,；;。）和连接词（然后 / 接着 / 之后 / 并且 / 同时 / 顺便 / 还有 / 以及 / 和 / 跟 / 再帮我 / 再把 …）切成子句，
 * <b>每一个</b>子句都必须能被快路径（{@link FastIntents}）或调用方给的单句解析器（L0 模板）接住，才产出计划；
 * 任何一个接不住就整句放弃（返回 null），交回原来的分诊（多步 → AgentLoop）。
 * 共享动词补全：「打开 wifi 和蓝牙」的第二个子句「蓝牙」继承前一个子句的开 / 关动词。
 *
 * <p>产出的计划由面板的 executePlan 串行执行（每步照样过 ExecutionGate，高危步骤中止整条计划），
 * 灵动岛按子句显示子目标（{@link #titleOf}），不再显示抽象步数。纯函数，JVM 可测。
 */
public final class CompoundSplitter {

    private CompoundSplitter() {}

    /** 单句解析器（JVM 测试 / 线上都传 L0：快路径之外的 L0 模板子句也算接得住）。 */
    public interface Resolver {
        Invocation resolve(String clause);
    }

    /** 最多拆几个子句（再多就交给 AgentLoop：说明是真复杂）。 */
    public static final int MAX_CLAUSES = 5;

    private static final Pattern SEP = Pattern.compile(
            "[、，,；;。！!？?]+|然后再|然后|接着|之后再|之后|并且|而且|同时|顺便|顺带|还有|以及|完了再|再帮我|再给我|再把|另外|最后");
    /** 「和 / 跟」只在两边都像动作 / 开关对象时才切（「张三和李四」不切：那种句子本来也接不住）。 */
    private static final Pattern AND = Pattern.compile("和|跟|与");

    private static final String[] ON_VERBS = {"打开", "开启", "开"};
    private static final String[] OFF_VERBS = {"关闭", "关掉", "关"};

    /**
     * @return 子句计划（≥2 步）；null = 不是可零 LLM 拆分的多意图句
     */
    public static Invocation split(String utterance, Resolver l0) {
        if (!FastIntents.enabled() || utterance == null) return null;
        String q = utterance.trim();
        if (q.length() < 4 || q.length() > 60) return null;
        List<String> clauses = new ArrayList<String>();
        for (String part : SEP.split(q)) {
            if (part == null) continue;
            String p = part.trim();
            if (p.length() == 0) continue;
            String[] sub = AND.split(p);
            if (sub.length > 1) {
                for (String s : sub) if (s.trim().length() > 0) clauses.add(s.trim());
            } else {
                clauses.add(p);
            }
        }
        if (clauses.size() < 2 || clauses.size() > MAX_CLAUSES) return null;
        List<Invocation> steps = new ArrayList<Invocation>();
        String lastVerb = null;
        for (String c : clauses) {
            Invocation inv = resolve(c, l0);
            if (inv == null && lastVerb != null) inv = resolve(lastVerb + c, l0);   // 共享动词
            if (inv == null) return null;
            if (inv.isPlan()) return null;
            steps.add(new Invocation(inv.sign, inv.args, inv.confidence, Invocation.RouteSource.L0_RULE, c));
            String v = leadingVerb(TriageDecider.normalize(c));
            if (v != null) lastVerb = v;
        }
        return Invocation.plan(steps, Invocation.RouteSource.L0_RULE, utterance);
    }

    private static Invocation resolve(String clause, Resolver l0) {
        Invocation f = FastIntents.route(clause);
        if (f != null) return f;
        if (l0 == null) return null;
        try {
            Invocation inv = l0.resolve(clause);
            // app.launch 之类「打开 X」在复合句里也算；但缺参追问 / 问答不算
            if (inv == null || inv.sign == null || inv.sign.startsWith("ask.") || SlotFiller.SIGN_CLARIFY.equals(inv.sign)) return null;
            return inv;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String leadingVerb(String c) {
        for (String v : ON_VERBS) if (c.startsWith(v)) return v;
        for (String v : OFF_VERBS) if (c.startsWith(v)) return v;
        return null;
    }

    /**
     * AgentLoop 候选钉选用：整句和每个子句里能被快路径认出的系统动作签名（「截个图然后发给张三」→ {ui.screenshot}）。
     * 让多步任务里的每一步都先看到直达工具，而不是因为字面打分低被截掉、退到点界面。
     */
    public static java.util.Set<String> fastSigns(String utterance) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (!FastIntents.enabled() || utterance == null || utterance.length() > 120) return out;
        FastIntents.Hit whole = FastIntents.match(utterance);
        if (whole != null) out.add(whole.sign);
        for (String part : SEP.split(utterance)) {
            if (part == null || part.trim().length() == 0) continue;
            for (String c : AND.split(part)) {
                FastIntents.Hit h = FastIntents.match(c);
                if (h != null) out.add(h.sign);
            }
        }
        return out;
    }

    /**
     * AgentLoop 开局的子目标种子：一句多意图（≥2 个子句）先按子句给岛显示「第 i/n 项 + 子目标」，
     * 模型调 task.todo 后由账本全量替换。不是多意图返回空表（岛照旧懒上岛）。
     */
    public static java.util.List<com.onesay.assistant.v2.island.Subgoal> seedSubgoals(String utterance) {
        java.util.List<com.onesay.assistant.v2.island.Subgoal> out = new java.util.ArrayList<com.onesay.assistant.v2.island.Subgoal>();
        if (utterance == null || utterance.length() > 120) return out;
        java.util.List<String> cl = new java.util.ArrayList<String>();
        for (String part : SEP.split(utterance.trim())) {
            if (part == null) continue;
            String p = FastIntents.clean(part);
            if (p.length() >= 2) cl.add(p);
        }
        if (cl.size() < 2 || cl.size() > MAX_CLAUSES) return out;
        for (int i = 0; i < cl.size(); i++) {
            String t = cl.get(i);
            if (t.length() > 10) t = t.substring(0, 10) + "…";
            out.add(new com.onesay.assistant.v2.island.Subgoal("c" + (i + 1), t,
                    i == 0 ? com.onesay.assistant.v2.island.Subgoal.State.DOING : com.onesay.assistant.v2.island.Subgoal.State.PENDING));
        }
        return out;
    }

    /** 零 LLM 计划的面板标题：「调低亮度 · 开勿扰 · 10分钟后提醒我」（代替「这是一条多步任务，共 N 步」）。 */
    public static String planHeadline(Invocation plan) {
        if (plan == null || plan.steps == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Invocation st : plan.steps) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(titleOf(st));
        }
        return sb.toString();
    }

    /** 灵动岛 / 面板上的子目标短名（子句原话去掉礼貌框架，≤10 字）。 */
    public static String titleOf(Invocation step) {
        if (step == null) return "";
        String t = step.originalQuery != null ? FastIntents.clean(step.originalQuery) : step.sign;
        if (t.length() == 0) t = step.sign;
        return t.length() > 10 ? t.substring(0, 10) + "…" : t;
    }
}
