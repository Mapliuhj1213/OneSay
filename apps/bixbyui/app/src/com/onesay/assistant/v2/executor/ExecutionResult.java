package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import org.json.JSONObject;

/**
 * 统一执行结果契约。
 *
 * 规范：
 * 1. ok：业务与校验是否最终成功；
 * 2. code：标准结构化错误码；
 * 3. verification：真实物理回读（check / expect）的三态结论（P0-4）；
 *    verified（boolean）保留为兼容字段 = (verification == VERIFIED)；
 * 4. userMessage：面向用户的友好中文文案（直接用于 TTS / 前端显示）；
 * 5. detail：底层技术诊断输出（用于日志与调试）；
 * 6. hint：下一步提示（"去配置 / 换路 / 别重试"），默认取 {@link ErrorCode#defaultHint}。
 *
 * <p>旧版 {@code verified} 初值为 true，没有回读命令的能力（大量 am start）也报
 * "已验证"，模型与 UI 误以为已确认生效（"商店假成功"的根源）。现在默认 {@link Verification#UNVERIFIED}，
 * 只有真的跑过回读且命中期望才是 {@link Verification#VERIFIED}。
 */
public class ExecutionResult {

    /** 回读核对三态。 */
    public enum Verification {
        /** 跑过回读命令且输出命中期望值：物理生效已确认 */
        VERIFIED,
        /** 没有回读手段（或未执行到回读）：命令执行了，但是否生效未知 —— 默认值 */
        UNVERIFIED,
        /** 跑过回读命令，输出与期望不符：物理未生效 */
        FAILED
    }

    public final boolean ok;
    public final ErrorCode code;
    /** 兼容字段：仅当 {@link #verification} == VERIFIED 时为 true。 */
    public final boolean verified;
    public final Verification verification;
    public final String userMessage;
    public final String detail;
    /** 下一步提示，可能为空串。 */
    public final String hint;
    public final Capability capability;
    public final JSONObject args;

    public ExecutionResult(boolean ok, ErrorCode code, Verification verification, String userMessage,
                           String detail, String hint, Capability capability, JSONObject args) {
        this.ok = ok;
        this.code = code != null ? code : (ok ? ErrorCode.OK : ErrorCode.EXEC_FAILED);
        this.verification = verification != null ? verification : Verification.UNVERIFIED;
        this.verified = this.verification == Verification.VERIFIED;
        this.userMessage = userMessage;
        this.detail = detail != null ? detail : "";
        this.hint = hint != null ? hint : this.code.defaultHint;
        this.capability = capability;
        this.args = args;
    }

    /** 兼容旧构造：boolean verified → VERIFIED / UNVERIFIED（code==VERIFY_FAILED 时为 FAILED）。 */
    public ExecutionResult(boolean ok, ErrorCode code, boolean verified, String userMessage, String detail, Capability capability, JSONObject args) {
        this(ok, code, legacy(code, verified), userMessage, detail, null, capability, args);
    }

    private static Verification legacy(ErrorCode code, boolean verified) {
        if (code == ErrorCode.VERIFY_FAILED) return Verification.FAILED;
        return verified ? Verification.VERIFIED : Verification.UNVERIFIED;
    }

    // ------------------------------------------------------------------ 工厂

    public static ExecutionResult success(Capability cap, JSONObject args, String reply, Verification v, String detail) {
        return new ExecutionResult(true, ErrorCode.OK, v, reply, detail, "", cap, args);
    }

    /** 兼容旧签名：verified=true → VERIFIED，false → UNVERIFIED。 */
    public static ExecutionResult success(Capability cap, JSONObject args, String reply, boolean verified, String detail) {
        return success(cap, args, reply, verified ? Verification.VERIFIED : Verification.UNVERIFIED, detail);
    }

    public static ExecutionResult failed(ErrorCode code, String userMessage, String detail, Capability cap, JSONObject args) {
        return new ExecutionResult(false, code, legacy(code, false), userMessage, detail, null, cap, args);
    }

    /** 带具体提示的失败（覆盖 {@link ErrorCode#defaultHint}）。 */
    public static ExecutionResult failed(ErrorCode code, String userMessage, String detail, String hint,
                                         Capability cap, JSONObject args) {
        return new ExecutionResult(false, code, legacy(code, false), userMessage, detail, hint, cap, args);
    }

    public static ExecutionResult gated(Capability cap, JSONObject args) {
        String msg = "⚠️ 正在请求执行高危操作：【" + cap.summary + "】，请确认是否继续执行？";
        return new ExecutionResult(false, ErrorCode.RISK_GATED, Verification.UNVERIFIED, msg,
                "risk=2 confirm=true", null, cap, args);
    }

    /** 复制并替换提示。 */
    public ExecutionResult withHint(String newHint) {
        return new ExecutionResult(ok, code, verification, userMessage, detail, newHint, capability, args);
    }

    // ------------------------------------------------------------------ 给模型的观察值

    /** 观察值里 detail 的最大长度（shell 输出可能很长）。 */
    public static final int OBS_DETAIL_MAX = 300;

    /**
     * 稳定的模型观察值（JSON 文本），供 AgentLoop 作为 tool 消息回灌。
     *
     * <p>字段（协议稳定，新增只追加）：
     * <pre>
     *   ok        boolean
     *   code      ErrorCode 枚举名，如 "OK" / "NOT_CONFIGURED" / "AUTH_FAILED"
     *   verified  "VERIFIED" | "UNVERIFIED" | "FAILED"
     *   result    面向用户的文案
     *   retryable boolean，仅失败时出现：原样重试是否可能成功
     *   hint      仅非空时出现：下一步怎么办（去配置 / 换路 / 别重试）
     *   detail    仅失败时出现，截断到 300 字
     * </pre>
     * 只给事实与可操作提示，不夹带诱导性措辞。
     */
    public String toModelObservation() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("code", code.name());
            o.put("verified", verification.name());
            o.put("result", userMessage == null ? "" : userMessage);
            if (!ok) o.put("retryable", code.retryable);
            if (hint != null && hint.length() > 0) o.put("hint", hint);
            if (!ok && detail.length() > 0) {
                o.put("detail", detail.length() > OBS_DETAIL_MAX ? detail.substring(0, OBS_DETAIL_MAX) : detail);
            }
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":" + ok + ",\"code\":\"" + code.name() + "\"}";
        }
    }

    @Override
    public String toString() {
        return "Result{ok=" + ok + ", code=" + code + ", verification=" + verification
                + ", msg='" + userMessage + "', hint='" + hint + "', detail='" + detail + "'}";
    }
}
