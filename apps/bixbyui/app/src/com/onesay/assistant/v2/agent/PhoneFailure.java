package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.executor.ErrorCode;

/**
 * ★ arch/integrate：phone.operate（点界面档）没做成时的<b>细分</b>（替换笼统的 EXEC_FAILED）。
 *
 * <p>输入是 {@code PhoneAgent.Result} 的状态和原因文字（{@code PhoneAgent.finish / PhoneHooks.runBlocking / TaskBudget} 写的那些句子），
 * 输出 = 执行层错误码 + B 路 {@link Outcome} 结局与 {@link Outcome.Reason} 原因字符串。观察值里带上 outcome / reason，
 * 模型收工时可以原样用作 {@code end_not_done(subtype)}；AgentLoop 侧不需要再改。纯函数，JVM 可测。
 */
public final class PhoneFailure {

    public final ErrorCode code;
    public final Outcome outcome;
    /** {@link Outcome.Reason} 里的线上字符串。 */
    public final String reason;
    /** 给模型的下一步提示。 */
    public final String hint;

    private PhoneFailure(ErrorCode code, Outcome outcome, String reason, String hint) {
        this.code = code;
        this.outcome = outcome;
        this.reason = reason;
        this.hint = hint;
    }

    /**
     * @param stopped PhoneAgent.Status == STOPPED
     * @param why     PhoneAgent.Result.reason
     * @param answer  PhoneAgent.Result.answer（失败时通常为空，偶尔带模型的说明）
     */
    public static PhoneFailure classify(boolean stopped, String why, String answer) {
        String r = why == null ? "" : why;
        String all = r + " " + (answer == null ? "" : answer);
        if (stopped) {
            if (r.startsWith("用户没有同意")) {
                return new PhoneFailure(ErrorCode.USER_REJECTED, Outcome.CANCELLED, Outcome.Reason.USER_STOPPED,
                        "用户在界面操作中途拒绝了这一步，不要重试，直接说明");
            }
            if (containsAny(all, LOGIN)) return needsUser(Outcome.Reason.LOGIN, "要用户先登录");
            if (containsAny(all, CAPTCHA)) return needsUser(Outcome.Reason.CAPTCHA, "要用户亲自完成验证");
            if (containsAny(all, PAYMENT)) return needsUser(Outcome.Reason.PAYMENT, "付款要用户亲自确认");
            // 不用 ErrorCode.CANCELLED：AgentLoop 见到 CANCELLED 会直接收掉整个任务（那是面板停止键的语义）；
            // 这里只是界面那一步被用户叫停，让模型照常写收尾答复。
            return new PhoneFailure(ErrorCode.USER_REJECTED, Outcome.CANCELLED, Outcome.Reason.USER_STOPPED,
                    "用户停止了界面操作，不要重试，直接说明做到哪一步");
        }
        if (r.contains("没有配置大模型 Key")) {
            return new PhoneFailure(ErrorCode.NOT_CONFIGURED, Outcome.BLOCKED, Outcome.Reason.NO_KEY,
                    "没有配置大模型 Key，点界面做不了；如实告诉用户");
        }
        if (r.contains("读屏助手不可用") || r.contains("已有一个操作任务在进行")) {
            return new PhoneFailure(ErrorCode.PERMISSION_DENIED, Outcome.BLOCKED, Outcome.Reason.DEVICE_UNAVAILABLE,
                    "这台手机现在没法替你点界面（读屏助手不可用或正忙），换别的做法或如实说明");
        }
        if (r.contains("模型没有返回")) {
            return new PhoneFailure(ErrorCode.TIMEOUT, Outcome.BLOCKED, Outcome.Reason.MODEL_UNAVAILABLE,
                    "界面操作的模型没响应；可以稍后再试，或如实说明");
        }
        if (containsAny(all, LOGIN)) return needsUser(Outcome.Reason.LOGIN, "要用户先登录");
        if (containsAny(all, CAPTCHA)) return needsUser(Outcome.Reason.CAPTCHA, "要用户亲自完成验证");
        if (containsAny(all, PAYMENT)) return needsUser(Outcome.Reason.PAYMENT, "付款要用户亲自确认");
        if (containsAny(r, STUCK)) {
            return new PhoneFailure(ErrorCode.EXEC_FAILED, Outcome.NOT_DONE, Outcome.Reason.LOOP_GUARD,
                    "界面卡住没有进展；换一个入口或如实说明卡在哪");
        }
        if (containsAny(r, BUDGET)) {
            return new PhoneFailure(ErrorCode.EXEC_FAILED, Outcome.NOT_DONE, Outcome.Reason.BUDGET,
                    "界面操作用完了预算还没做完；说明做到哪一步、还差什么");
        }
        if (containsAny(all, NOT_FOUND)) {
            return new PhoneFailure(ErrorCode.NOT_FOUND_APP, Outcome.NOT_DONE, Outcome.Reason.ROUTE_FAILED,
                    "界面里没找到目标；换个说法、换个入口，或如实说明");
        }
        if (r.contains("Exception") || r.contains("Error")) {
            return new PhoneFailure(ErrorCode.EXEC_FAILED, Outcome.NOT_DONE, Outcome.Reason.ERROR,
                    "界面操作出错；换一种方式或如实说明");
        }
        return new PhoneFailure(ErrorCode.EXEC_FAILED, Outcome.NOT_DONE, Outcome.Reason.ROUTE_FAILED,
                ErrorCode.EXEC_FAILED.defaultHint);
    }

    private static PhoneFailure needsUser(String reason, String what) {
        return new PhoneFailure(ErrorCode.EXEC_FAILED, Outcome.NEEDS_USER, reason,
                what + "：别再替用户点，收工时用 end_not_done(subtype=" + reason + ") 说明要用户做什么");
    }

    /** 观察值里追加的结构化尾巴（{@code outcome=… reason=…}）。 */
    public String tag() {
        return "outcome=" + outcome.wire() + " reason=" + reason;
    }

    static final String[] LOGIN = {"登录", "登陆", "未登录", "请先登录", "sign in", "log in"};
    static final String[] CAPTCHA = {"验证码", "人机验证", "滑块", "安全验证", "captcha"};
    static final String[] PAYMENT = {"付款", "支付", "输入密码", "支付密码"};
    static final String[] STUCK = {"界面没有任何变化", "界面都没变化", "卡住"};
    static final String[] BUDGET = {"安全上限", "已经问了模型", "已经做了", "已走满", "未完成"};
    static final String[] NOT_FOUND = {"没找到", "找不到", "未找到", "未安装", "没有安装"};

    static boolean containsAny(String s, String[] keys) {
        if (s == null) return false;
        String l = s.toLowerCase(java.util.Locale.ROOT);
        for (String k : keys) if (l.contains(k)) return true;
        return false;
    }
}
