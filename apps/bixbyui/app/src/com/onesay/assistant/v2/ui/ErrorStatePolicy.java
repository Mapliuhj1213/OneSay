package com.onesay.assistant.v2.ui;

/**
 * 面板「没听清」错误态的收敛策略（纯 Java，JVM 可测；Android 侧只按 {@link Plan} 执行）。
 *
 * <h3>问题</h3>
 * <p>真机验收截图：深链测试连续两张截图（间隔 3 s+）里面板始终悬浮，标题「没听清」、副标题还停在
 * 「正在听你说…说完稍停即可」、正文「没听清指令，请重新唤醒...」，底部挂着上一轮的推荐芯片
 * （现在几点 / 今天天气 / 打开相机…），遮住被测应用下半屏。原因是 2026-10-03 起「没听清」终态改成了
 * 常驻、只能手动关；而这一态既不清芯片、也不更新副标题。
 *
 * <h3>现在的规则</h3>
 * <ol>
 *   <li>面板不在屏幕上 → 什么都不做（错误态从不主动把面板弹出来）；</li>
 *   <li><b>确认卡未决 → 不清、不复位、不收起</b>（S1 不变量：确认卡永远不会被自动藏起来）；</li>
 *   <li>长任务在跑（AgentLoop / 胶囊）→ 只更新状态行，不收起（面板/胶囊归任务所有）；</li>
 *   <li>用户正在用「⌨ 文字」输入 → 只显示错误、{@link #ERROR_VISIBLE_MS} 后回中性态，不清芯片、不收起；</li>
 *   <li>新一次唤醒没听清 / 云端识别失败 / 注入的 panel_query 无效 → 清掉推荐芯片，错误显示
 *       {@link #ERROR_VISIBLE_MS} 后回中性态并自动收起；</li>
 *   <li>继续聆听会话里这一轮没听到 → 清芯片，{@link #ERROR_VISIBLE_MS} 后回中性态（上一轮问答保留），
 *       {@link #CONTINUE_SESSION_HIDE_MS} 后收起（给用户留时间看完上一轮内容）。</li>
 * </ol>
 * <p>延时回调到点时由 {@link #stillHide} 复核：期间出现确认卡 / 任务开始 / 用户转文字输入，收起作废。
 * 任何新一轮输入（新唤醒、提交指令）都会先取消这两个回调（宿主的 cancelAutoHide）。
 */
public final class ErrorStatePolicy {

    private ErrorStatePolicy() {}

    /** 错误态在屏幕上停留多久再回中性态（真机报告要求 2~3 s）。 */
    public static final long ERROR_VISIBLE_MS = 2500L;
    /** 继续聆听会话里没听到：多久后收起（屏幕上还有上一轮问答，给足阅读时间）。 */
    public static final long CONTINUE_SESSION_HIDE_MS = 6000L;

    /** 错误来源。 */
    public enum Source {
        /** 新一次唤醒，捕获窗内没听到有效指令。 */
        NO_SPEECH_NEW_WAKE,
        /** 继续聆听会话（面板上有上一轮问答），这一轮没听到。 */
        NO_SPEECH_CONTINUE,
        /** 云端识别失败 / 凭据失效等 ASR 终态。 */
        ASR_FAILED,
        /** panel_query（ACTION_SHOW + query）注入的指令无效（为空/被引号吃掉）。 */
        INJECTED_QUERY_FAILED,
        /** r2/p0 P0-2：语音识别没配置 / Key 失效 —— 卡片带「去配置」，<b>不复位、不自动收起</b>（用户手动关）。 */
        ASR_NEEDS_SETUP,
        /** r2/p0 P0-2：超时 / 限流 / 服务端 / 网络失败且带「重试」芯片 —— 不复位，{@link #RETRY_CARD_HIDE_MS} 后才收起。 */
        ASR_RETRYABLE
    }

    /** 带「重试」芯片的识别失败卡多久后收起（2.5 s 来不及点）。 */
    public static final long RETRY_CARD_HIDE_MS = 10000L;

    /** 宿主要执行的动作。时间为 -1 表示不安排。 */
    public static final class Plan {
        /** 是否更新面板（false = 面板不在屏上，整件事忽略）。 */
        public final boolean apply;
        /** 进入错误态时隐藏推荐芯片（防止上一轮的残留芯片跟着错误态常驻）。 */
        public final boolean clearSuggestions;
        /** 多久后把状态行复位为中性态。 */
        public final long neutralAfterMs;
        /** 多久后自动收起面板。 */
        public final long hideAfterMs;
        /** 中性态时是否同时清掉转录区（新唤醒：清；继续会话：保留上一轮问答）。 */
        public final boolean clearTranscriptOnNeutral;
        /** 日志用。 */
        public final String reason;

        Plan(boolean apply, boolean clearSuggestions, long neutralAfterMs, long hideAfterMs,
             boolean clearTranscriptOnNeutral, String reason) {
            this.apply = apply;
            this.clearSuggestions = clearSuggestions;
            this.neutralAfterMs = neutralAfterMs;
            this.hideAfterMs = hideAfterMs;
            this.clearTranscriptOnNeutral = clearTranscriptOnNeutral;
            this.reason = reason;
        }

        @Override public String toString() {
            return "apply=" + apply + " clearChips=" + clearSuggestions + " neutral@" + neutralAfterMs
                    + " hide@" + hideAfterMs + " (" + reason + ")";
        }
    }

    /**
     * @param panelOnScreen  面板此刻是否挂在屏幕上
     * @param confirmPending 确认卡未决（单步确认或 AgentLoop 确认）
     * @param taskRunning    长任务（AgentLoop / S1 胶囊）在跑
     * @param textMode       用户正在「⌨ 文字」模式输入
     */
    public static Plan plan(Source source, boolean panelOnScreen, boolean confirmPending,
                            boolean taskRunning, boolean textMode) {
        if (source == null) source = Source.NO_SPEECH_NEW_WAKE;
        if (!panelOnScreen) return new Plan(false, false, -1, -1, false, "面板不在屏幕上，不弹出");
        if (confirmPending) return new Plan(true, false, -1, -1, false, "确认卡未决：不清、不复位、不收起");
        if (taskRunning) return new Plan(true, false, -1, -1, false, "任务在跑：只更新状态行");
        if (textMode) return new Plan(true, false, ERROR_VISIBLE_MS, -1, false, "文字输入中：只回中性态");
        if (source == Source.ASR_NEEDS_SETUP) return new Plan(true, true, -1, -1, false, "识别未配置 / Key 失效：常驻等用户处理");
        if (source == Source.ASR_RETRYABLE) return new Plan(true, true, -1, RETRY_CARD_HIDE_MS, false, "识别失败可重试：留时间点「重试」");
        if (source == Source.NO_SPEECH_CONTINUE) {
            return new Plan(true, true, ERROR_VISIBLE_MS, CONTINUE_SESSION_HIDE_MS, false, "继续会话没听到");
        }
        return new Plan(true, true, ERROR_VISIBLE_MS, ERROR_VISIBLE_MS, true, source.name());
    }

    /** 延时收起到点时复核：这期间出现确认卡 / 任务开始 / 用户转文字输入 / 面板已不在屏上 → 不收。 */
    public static boolean stillHide(boolean panelOnScreen, boolean confirmPending, boolean taskRunning, boolean textMode) {
        return panelOnScreen && !confirmPending && !taskRunning && !textMode;
    }

    /** 中性态复位到点时复核（确认卡 / 任务出现后不能把它们的状态行改掉）。 */
    public static boolean stillNeutral(boolean panelOnScreen, boolean confirmPending, boolean taskRunning) {
        return panelOnScreen && !confirmPending && !taskRunning;
    }
}
