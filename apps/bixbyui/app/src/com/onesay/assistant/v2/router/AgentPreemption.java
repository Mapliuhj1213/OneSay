package com.onesay.assistant.v2.router;

/**
 * r2/fixes 14：新请求到达时，正在跑的 AgentLoop 复合任务要不要取消（纯函数，JVM 可测）。
 *
 * <p>以前 FloatingSheetService.onSubmitCommand 第一句 {@code cancelActiveStream()} 不分青红皂白把在途请求全作废 ——
 * AgentLoop 的取消令牌就是 mActiveStream，于是用户在复合任务跑着时随口问一句「几点了」，后台任务就被杀掉了。
 *
 * <ul>
 *   <li>新请求<b>也进 AgentLoop</b> → 取消旧的复合任务（同一时间只跑一个循环：共用确认卡、胶囊、会话记忆）。</li>
 *   <li>新请求是简单问答 / L0 / L1 / 插件 / 识屏等 → <b>不取消</b>旧任务；只作废旧的流式问答。旧任务失去卡片：
 *       步骤进度、收工结论不再写到卡片上（不盖住新请求的答案），结论照旧交给岛 / 胶囊和会话记忆；
 *       它若需要确认，卡片已不属于它 → 按未授权处理（绝不把确认卡弹到别的请求上面）。</li>
 *   <li>面板收起 / 看门狗超时等<b>非新请求</b>的收尾：任务还占着卡片时照旧一起取消（「✕ 取消」语义不变）；
 *       已经被新请求接管、转到后台的任务不受影响。</li>
 * </ul>
 */
public final class AgentPreemption {

    private AgentPreemption() {}

    /** 新请求是否取消正在跑的复合任务：只有新请求自己也进 AgentLoop 时才取消。 */
    public static boolean newRequestCancelsAgent(boolean newRequestUsesAgentLoop) {
        return newRequestUsesAgentLoop;
    }

    /** 复合任务是否还占着卡片（派发时的请求号 == 当前请求号）。 */
    public static boolean agentOwnsCard(int dispatchSeq, int currentSeq) {
        return com.onesay.assistant.v2.ui.wake.TaskResultRouting.writeToCard(dispatchSeq, currentSeq);
    }

    /** 面板收起 / 超时这类收尾是否连带取消复合任务：只取消还占着卡片的那个。 */
    public static boolean teardownCancelsAgent(int dispatchSeq, int currentSeq) {
        return agentOwnsCard(dispatchSeq, currentSeq);
    }

    /** ★ 11-04 B：执行模式让位（面板收起给 App 操作腾地方）不是用户取消 → 不连带取消。 */
    public static boolean teardownCancelsAgent(int dispatchSeq, int currentSeq, boolean yieldForExec) {
        return !yieldForExec && agentOwnsCard(dispatchSeq, currentSeq);
    }
}
