package com.onesay.assistant.v2.router;

/**
 * ★ fix/triage-fast：分诊第 0 层（纯函数，UnifiedRouter.triage 与 JVM 覆盖矩阵共用同一份逻辑）。
 *
 * <p>严格的优先级（用户 2026-11-02 定）：模板 / 规则直达 → 工具 / API → 单步 LLM 选工具 → 多步 Agent → GUI 兜底。
 * 这里负责第一档：
 * <ol>
 *   <li>{@link FastIntents} 整句命中 → 单步 DO（DO_READ / DO_ACT），<b>永不进 AgentLoop、不调 LLM 分类</b>；</li>
 *   <li>{@link CompoundSplitter} 每个子句都能被快路径 / L0 接住 → 零 LLM 计划（面板串行执行，岛显示子目标）。</li>
 * </ol>
 * 都不中返回 null，照旧走 TriageDecider 决策表。
 *
 * <p>另修一个老漏洞（{@link #l0WithCore}）：分诊只拿<b>原句</b>去匹配 L0，「能不能帮我锁屏呀」这类剥掉礼貌框架后
 * 才命中的句子在分诊时 l0=null → 单步 DO 被判进 AgentLoop；而级联 ③d 明明会用核心短语再试一次 L0。现在分诊阶段就试。
 */
public final class FastPathTriage {

    private FastPathTriage() {}

    /** @return null = 第 0 层不接，交回决策表 */
    static UnifiedRouter.Triage triage(String query, CompoundSplitter.Resolver l0) {
        if (query == null || query.trim().length() == 0) return null;
        if (com.onesay.assistant.v2.trip.TripIntent.isTrigger(query)) return null;   // ★ trip-decompose：交决策表进规划模式
        Invocation f = FastIntents.route(query);
        if (f != null) {
            return new UnifiedRouter.Triage(TriageDecider.fastPath(query, f.sign, false), f, null);
        }
        // ★ int/1102 审计：上一轮感知的改值追问（「改成八点」「再大一点」）—— 引用上一轮能力与参数，零 LLM
        Invocation fe = FollowupEdit.resolve(query, FollowupEdit.last(System.currentTimeMillis()));
        if (fe != null) {
            return fe.isPlan() ? new UnifiedRouter.Triage(TriageDecider.fastPath(query, Invocation.SIGN_PLAN, true), null, fe)
                    : new UnifiedRouter.Triage(TriageDecider.fastPath(query, fe.sign, false), fe, null);
        }
        Invocation plan = CompoundSplitter.split(query, l0);
        if (plan != null) {
            return new UnifiedRouter.Triage(TriageDecider.fastPath(query, Invocation.SIGN_PLAN, true), null, plan);
        }
        return null;
    }

    /** 原句匹配 L0；没中且剥礼貌框架后的核心短语不同 → 用核心短语再试（结果保留原句作 originalQuery）。 */
    static Invocation l0WithCore(String query, CompoundSplitter.Resolver l0) {
        if (query == null || l0 == null) return null;
        Invocation hit = l0.resolve(query);
        if (hit != null) return hit;
        String n = TriageDecider.normalize(query);
        String core = TriageDecider.stripFrames(n);
        if (core == null || core.length() < 2 || core.equals(n)) return null;
        Invocation c = l0.resolve(core);
        if (c == null) return null;
        return new Invocation(c.sign, c.args, c.confidence, c.source, query);
    }
}
