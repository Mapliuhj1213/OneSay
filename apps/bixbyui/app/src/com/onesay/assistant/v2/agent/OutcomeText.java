package com.onesay.assistant.v2.agent;

/**
 * ★ arch/integrate：AgentLoop 收尾时面板 / 岛上的说法按结构化结局换（以前没做成一律「部分完成 · reason」）。
 *
 * <ul>
 *   <li>{@link Outcome#NEEDS_USER}：「需要你来」+ 要用户做什么（登录 / 验证 / 付款 / 补信息 / 没等到回答）；</li>
 *   <li>{@link Outcome#BLOCKED}：「暂时做不了」+ 被什么挡住（模型无响应 / 没 Key / 设备不可用 / 安全策略）；</li>
 *   <li>{@link Outcome#NOT_DONE}：照旧「部分完成」+ 循环给的原因；</li>
 *   <li>{@link Outcome#CANCELLED}：「已停止」。</li>
 * </ul>
 * 纯函数，JVM 可测。
 */
public final class OutcomeText {

    private OutcomeText() {}

    /** 面板状态行：标题。 */
    public static String title(Outcome o) {
        if (o == null) return "部分完成";
        switch (o) {
            case DONE: return "执行完毕";
            case NEEDS_USER: return "需要你来";
            case BLOCKED: return "暂时做不了";
            case CANCELLED: return "已停止";
            default: return "部分完成";
        }
    }

    /** 面板状态行：颜色（#RRGGBB）。 */
    public static String color(Outcome o) {
        if (o == null) return "#FF9F0A";
        switch (o) {
            case DONE: return "#30D158";
            case BLOCKED: return "#FF453A";
            case CANCELLED: return "#8E8E93";
            default: return "#FF9F0A";
        }
    }

    /**
     * 面板状态行的副文字 / 岛失败卡的原因：needs_user / blocked 按细分原因换成给用户看的一句话；
     * 认不出的原因、not_done、cancelled → 循环原来的 reason。
     */
    public static String line(Outcome o, String outcomeReason, String loopReason) {
        String fallback = loopReason == null || loopReason.trim().length() == 0 ? "任务没做完" : loopReason;
        String r = outcomeReason == null ? "" : outcomeReason;
        if (o == Outcome.NEEDS_USER) {
            if (Outcome.Reason.LOGIN.equals(r)) return "要你先登录，登录好再叫我";
            if (Outcome.Reason.CAPTCHA.equals(r)) return "需要你亲自完成验证";
            if (Outcome.Reason.PAYMENT.equals(r)) return "付款要你亲自确认";
            if (Outcome.Reason.NO_ANSWER.equals(r)) return "没等到你的回答，先停在这里";
            if (Outcome.Reason.NEEDS_INFO.equals(r)) return "还缺一点信息，需要你补充";
            return "需要你接手一下：" + fallback;
        }
        if (o == Outcome.BLOCKED) {
            if (Outcome.Reason.MODEL_UNAVAILABLE.equals(r)) return "模型服务没有响应，稍后再试";
            if (Outcome.Reason.NO_KEY.equals(r)) return "还没配置大模型 Key";
            if (Outcome.Reason.DEVICE_UNAVAILABLE.equals(r)) return "这台手机现在没法完成这一步";
            if (Outcome.Reason.POLICY.equals(r)) return "被安全策略拦下了";
            if (Outcome.Reason.NO_TOOLS.equals(r)) return "没有能用的工具";
            return "被外部条件挡住了：" + fallback;
        }
        return fallback;
    }

    /** 没有答复文字时卡片正文的兜底。 */
    public static String fallbackAnswer(boolean finished, Outcome o, String outcomeReason, String loopReason) {
        if (finished) return "任务已完成。";
        if (o == Outcome.NEEDS_USER || o == Outcome.BLOCKED) return title(o) + "：" + line(o, outcomeReason, loopReason);
        return "任务未完成：" + loopReason;
    }
}
