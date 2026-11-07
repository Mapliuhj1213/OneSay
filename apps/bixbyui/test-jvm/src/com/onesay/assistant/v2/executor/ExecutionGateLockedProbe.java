package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import org.json.JSONObject;

/** ★ r2/screenoff-wake 测试辅助：从 wake 包的测试里够到 ExecutionGate 的包内入口（authorize / authorizeLegacy）。 */
public final class ExecutionGateLockedProbe {
    private ExecutionGateLockedProbe() {}

    public static boolean evaluateDenied(Capability cap) {
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, new JSONObject(), false);
        return d.denied() && com.onesay.assistant.v2.wake.ScreenOffPolicy.UNLOCK_REASON.equals(d.reason);
    }

    /** authorize 被锁屏拒绝时返回 detail（"KeyguardLocked"），否则 null（放行或其它原因）。 */
    public static String authorizeLockedDetail(Capability cap) {
        ExecutionResult r = ExecutionGate.authorize(cap, new JSONObject(), false, null);
        return (r != null && r.code == ErrorCode.GATE_DENIED && "KeyguardLocked".equals(r.detail)) ? r.detail : null;
    }

    public static String legacyLockedDetail(Capability cap) {
        ExecutionResult r = ExecutionGate.authorizeLegacy(cap, new JSONObject(), false, false);
        return (r != null && r.code == ErrorCode.GATE_DENIED && "KeyguardLocked".equals(r.detail)) ? r.detail : null;
    }
}
