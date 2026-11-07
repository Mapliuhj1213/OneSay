package com.onesay.assistant.v2.agent;

/**
 * ★ arch/agent-loop（B 路）：AgentLoop 的结构化结局（替换笼统的 finished=true/false）。
 *
 * <ul>
 *   <li>{@link #DONE}：模型给出最终答复（没有工具调用）；</li>
 *   <li>{@link #NOT_DONE}：没做成 / 只做了一部分，带原因（{@link Reason}）—— 预算用完、换路都失败、模型主动 end_not_done(route_failed…)；</li>
 *   <li>{@link #NEEDS_USER}：要用户亲手来（登录 / 验证码 / 付款 / 关键信息问了没回答）；</li>
 *   <li>{@link #BLOCKED}：被外部条件挡住（模型无响应、没 Key、设备不可用、安全策略拦截且无替代）；</li>
 *   <li>{@link #CANCELLED}：用户停止 / 新指令接管。</li>
 * </ul>
 * end_not_done 的 subtype 借鉴 Mika core/general/OutcomeTools.kt（Apache-2.0，wangzan101/mika）的 FAILURE_SUBTYPES，按本项目改名归并。
 */
public enum Outcome {
    DONE, NOT_DONE, NEEDS_USER, BLOCKED, CANCELLED;

    /** 细分原因（线上字符串小写，给日志 / 岛 / 负缓存用）。 */
    public static final class Reason {
        private Reason() {}

        public static final String BUDGET = "budget";
        public static final String ROUTE_FAILED = "route_failed";
        public static final String PARTIAL = "partial";
        public static final String NO_ANSWER = "no_answer";
        public static final String LOGIN = "login_required";
        public static final String CAPTCHA = "captcha";
        public static final String PAYMENT = "payment";
        public static final String NEEDS_INFO = "needs_info";
        public static final String MODEL_UNAVAILABLE = "model_unavailable";
        public static final String NO_KEY = "no_key";
        public static final String DEVICE_UNAVAILABLE = "device_unavailable";
        public static final String POLICY = "policy_blocked";
        public static final String NO_TOOLS = "no_tools";
        public static final String LOOP_GUARD = "loop_guard";
        public static final String ERROR = "error";
        public static final String USER_STOPPED = "user_stopped";
    }

    /** end_not_done 允许的 subtype（模型可选）。 */
    public static final String[] END_SUBTYPES = {
            Reason.ROUTE_FAILED, Reason.PARTIAL, Reason.NO_ANSWER, Reason.LOGIN, Reason.CAPTCHA, Reason.PAYMENT,
            Reason.NEEDS_INFO, Reason.DEVICE_UNAVAILABLE, Reason.POLICY, "blocked",
    };

    /** end_not_done 的 subtype → 结局。认不出 → NOT_DONE + route_failed。 */
    public static Outcome ofEndSubtype(String subtype) {
        String s = subtype == null ? "" : subtype.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals(Reason.LOGIN) || s.equals(Reason.CAPTCHA) || s.equals(Reason.PAYMENT) || s.equals(Reason.NEEDS_INFO)
                || s.equals(Reason.NO_ANSWER) || s.equals("needs_user") || s.equals("login_incomplete") || s.equals("captcha_incomplete")) {
            return NEEDS_USER;
        }
        if (s.equals("blocked") || s.equals(Reason.DEVICE_UNAVAILABLE) || s.equals(Reason.POLICY)) return BLOCKED;
        return NOT_DONE;
    }

    /** 规整 subtype（未知 → route_failed）。 */
    public static String normalizeSubtype(String subtype) {
        String s = subtype == null ? "" : subtype.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("login_incomplete")) return Reason.LOGIN;
        if (s.equals("captcha_incomplete")) return Reason.CAPTCHA;
        if (s.equals("needs_user")) return Reason.NEEDS_INFO;
        for (String x : END_SUBTYPES) if (x.equals(s)) return s;
        return Reason.ROUTE_FAILED;
    }

    /** 线上字符串（小写）。 */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
