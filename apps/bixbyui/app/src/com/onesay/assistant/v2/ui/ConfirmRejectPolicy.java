package com.onesay.assistant.v2.ui;

/**
 * 确认卡被<b>拒绝</b>后的面板收敛策略（纯 Java，JVM 可测；Android 侧只按 {@link Plan} 执行）。
 *
 * <h3>问题</h3>
 * <p>真机：红卡上点「取消」（用户口中的「拒绝」）面板<b>立刻收起</b>——红卡的取消键走的是 {@code onCancelAction}
 * = 「✕ 关闭」同一条路，用户看不到任何「已拒绝」的反馈，也分不清是「拒绝成功」还是「面板崩了」。
 * 黄卡的「拒绝」则相反：状态行写了「已拒绝」，但确认卡直接消失、之后<b>永远不收起</b>。
 *
 * <h3>现在的规则（所有确认卡一致：红卡 / 黄卡 / S2「清空记忆」/ #9 诊断修复黄卡 / ExecutionGate 单步卡 / AgentLoop 步骤卡）</h3>
 * <ol>
 *   <li>面板不在屏幕上 → 什么都不做；</li>
 *   <li>否则<b>一律先显示终态「已拒绝」</b>：确认卡原地换成中性灰的「已拒绝 · 该操作不会执行」卡（按钮全部隐藏），
 *       状态行标题「已拒绝」；</li>
 *   <li><b>另一张确认卡仍未决 → 不复位、不收起</b>（S1 不变量：确认卡永远不会被自动藏起来）；</li>
 *   <li>AgentLoop 步骤卡被拒 / 长任务在跑 → 只显示「已拒绝」，不收起（循环会拿到 DENY 继续收尾，面板归任务所有）；</li>
 *   <li>用户正在「⌨ 文字」输入 → {@link ErrorStatePolicy#ERROR_VISIBLE_MS} 后回中性态，不收起；</li>
 *   <li>其余（单步卡被拒）→ 与「没听清」错误态同一时序：{@link ErrorStatePolicy#ERROR_VISIBLE_MS}（2.5 s）后回中性态并自动收起。</li>
 * </ol>
 * <p>到点复核沿用 {@link ErrorStatePolicy#stillHide} / {@link ErrorStatePolicy#stillNeutral}：
 * 期间出现新的确认卡 / 任务开始 / 用户转文字输入 / 新一轮输入（宿主 cancelAutoHide），收起作废。
 *
 * <p>「✕ 关闭」/ 点面板外 / 下拉手势仍然是<b>立即</b>收起（那是「关面板」，不是「拒绝」）。
 */
public final class ConfirmRejectPolicy {

    private ConfirmRejectPolicy() {}

    /** 终态标题（状态行 + 卡片标题）。 */
    public static final String TITLE = "已拒绝";
    /** 终态副标题。 */
    public static final String SUBTITLE = "该操作不会执行";

    /** 被拒绝的确认卡来自哪条路径。 */
    public enum Path {
        /** 单步确认卡（ExecutionGate 令牌：dispatch / 识屏芯片 / 诊断修复 / S2 清空…）。 */
        SINGLE_STEP,
        /** AgentLoop 步骤确认卡（循环线程在等裁决，拒绝后循环继续收尾）。 */
        AGENT_LOOP
    }

    /** 宿主要执行的动作。时间为 -1 表示不安排。 */
    public static final class Plan {
        /** 是否更新面板（false = 面板不在屏上，整件事忽略）。 */
        public final boolean apply;
        /** 是否把确认卡换成「已拒绝」终态卡。 */
        public final boolean showRejected;
        /** 多久后把状态行复位为中性态（同时收掉「已拒绝」卡）。 */
        public final long neutralAfterMs;
        /** 多久后自动收起面板。 */
        public final long hideAfterMs;
        /** 日志用。 */
        public final String reason;

        Plan(boolean apply, boolean showRejected, long neutralAfterMs, long hideAfterMs, String reason) {
            this.apply = apply;
            this.showRejected = showRejected;
            this.neutralAfterMs = neutralAfterMs;
            this.hideAfterMs = hideAfterMs;
            this.reason = reason;
        }

        @Override public String toString() {
            return "apply=" + apply + " rejectedCard=" + showRejected + " neutral@" + neutralAfterMs
                    + " hide@" + hideAfterMs + " (" + reason + ")";
        }
    }

    /**
     * @param path                被拒绝的卡来自哪条路径
     * @param panelOnScreen       面板此刻是否挂在屏幕上
     * @param otherConfirmPending 拒绝这张之后，是否还有<b>另一张</b>确认卡未决（单步令牌 / 面板确认卡 / S1 胶囊记录）
     * @param taskRunning         长任务（AgentLoop / S1 胶囊）在跑
     * @param textMode            用户正在「⌨ 文字」模式输入
     */
    public static Plan plan(Path path, boolean panelOnScreen, boolean otherConfirmPending,
                            boolean taskRunning, boolean textMode) {
        if (path == null) path = Path.SINGLE_STEP;
        if (!panelOnScreen) return new Plan(false, false, -1, -1, "面板不在屏幕上");
        if (otherConfirmPending) return new Plan(true, true, -1, -1, "另一张确认卡未决：只显示已拒绝，不复位、不收起");
        if (path == Path.AGENT_LOOP) return new Plan(true, true, -1, -1, "循环步骤被拒：循环继续收尾，面板归任务");
        if (taskRunning) return new Plan(true, true, -1, -1, "任务在跑：只显示已拒绝");
        if (textMode) return new Plan(true, true, ErrorStatePolicy.ERROR_VISIBLE_MS, -1, "文字输入中：只回中性态");
        return new Plan(true, true, ErrorStatePolicy.ERROR_VISIBLE_MS, ErrorStatePolicy.ERROR_VISIBLE_MS,
                "单步确认卡被拒：已拒绝 → 中性态 → 收起");
    }

    /** 「已拒绝」卡的说明文字（能力摘要可空）。 */
    public static String cardText(String summary) {
        if (summary == null || summary.trim().length() == 0) return SUBTITLE + "。";
        return "【" + summary.trim() + "】" + SUBTITLE + "。";
    }
}
