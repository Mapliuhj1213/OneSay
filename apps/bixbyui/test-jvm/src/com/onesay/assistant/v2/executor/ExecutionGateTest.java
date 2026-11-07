package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** P1-7：门禁单一入口 + 确认令牌不可绕过。 */
public class ExecutionGateTest {

    static Capability risky(String sign) {
        return new Capability.Builder(sign, "高危测试").risk(2, true).sideEffect(SideEffect.DEVICE_CONTROL)
                .cmd("echo risky").build();
    }

    static Capability safe(String sign) {
        return new Capability.Builder(sign, "安全测试").risk(0, false).cmd("echo safe").build();
    }

    static Capability root(String sign) {
        return new Capability.Builder(sign, "root").risk(0, false).sideEffect(SideEffect.ROOT).cmd("echo r").build();
    }

    @After public void reset() {
        ExecutionGate.setClockForTest(null);
        ExecutionGate.setStrictConfirm(true);   // r2/fixes 11：恢复生产默认（严格）
    }

    @Test public void allowDenyConfirmDecisions() throws Exception {
        ExecutionGate.Decision a = ExecutionGate.evaluate(safe("t.safe"), new JSONObject(), false);
        assertTrue(a.allowed());
        assertNull(a.token);
        ExecutionGate.Decision d = ExecutionGate.evaluate(root("t.root"), new JSONObject(), true);
        assertTrue("不可信来源触发 ROOT 必须拒绝", d.denied());
        ExecutionGate.Decision c = ExecutionGate.evaluate(risky("t.risky"), new JSONObject(), false);
        assertTrue(c.needsConfirm());
        assertNotNull(c.token);
        assertEquals(ConsentPolicy.TIER_YELLOW, c.tier);
        assertEquals(ExecutionGate.TokenState.PENDING, c.token.state());
        ExecutionGate.Decision cr = ExecutionGate.evaluate(risky("t.risky"), new JSONObject(), true);
        assertEquals("不可信来源一律红卡", ConsentPolicy.TIER_RED, cr.tier);
    }

    @Test public void tokenMustBeApprovedAndIsSingleUse() throws Exception {
        Capability cap = risky("t.reboot");
        JSONObject args = new JSONObject().put("x", 1);
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, false);
        // 未批准：拒绝（退回需确认）
        ExecutionResult notYet = ExecutionGate.authorize(cap, args, false, d.token);
        assertNotNull(notYet);
        assertEquals(ErrorCode.RISK_GATED, notYet.code);
        assertTrue(notYet.detail.contains("not approved"));
        // 批准后放行一次
        assertTrue(ExecutionGate.approve(d.token));
        assertNull(ExecutionGate.authorize(cap, args, false, d.token));
        assertEquals(ExecutionGate.TokenState.CONSUMED, d.token.state());
        // 再用一次：拒绝
        ExecutionResult again = ExecutionGate.authorize(cap, args, false, d.token);
        assertNotNull(again);
        assertEquals(ErrorCode.RISK_GATED, again.code);
        assertFalse(ExecutionGate.approve(d.token));
    }

    @Test public void tokenIsBoundToSignArgsAndTaint() throws Exception {
        Capability cap = risky("t.sms");
        JSONObject args = new JSONObject().put("number", "10086").put("text", "hi");
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, false);
        assertTrue(ExecutionGate.approve(d.token));
        // 参数被改
        JSONObject changed = new JSONObject().put("number", "999").put("text", "hi");
        ExecutionResult r1 = ExecutionGate.authorize(cap, changed, false, d.token);
        assertNotNull(r1);
        assertTrue(r1.detail.contains("args changed"));
        // 挪用到别的能力
        ExecutionResult r2 = ExecutionGate.authorize(risky("t.other"), args, false, d.token);
        assertNotNull(r2);
        assertTrue(r2.detail.contains("sign mismatch"));
        // 污点升级
        ExecutionResult r3 = ExecutionGate.authorize(cap, args, true, d.token);
        assertNotNull(r3);
        // 键顺序不同 / 30 与 30.0：视为同一参数，仍可用
        JSONObject reordered = new JSONObject().put("text", "hi").put("number", "10086");
        assertNull(ExecutionGate.authorize(cap, reordered, false, d.token));
    }

    @Test public void tokenExpiresAndRejectIsReported() throws Exception {
        final long[] now = {1000L};
        ExecutionGate.setClockForTest(new ExecutionGate.Clock() {
            @Override public long now() { return now[0]; }
        });
        Capability cap = risky("t.exp");
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, new JSONObject(), false);
        assertTrue(ExecutionGate.approve(d.token));
        now[0] += ExecutionGate.TOKEN_TTL_MS + 1;
        ExecutionResult r = ExecutionGate.authorize(cap, new JSONObject(), false, d.token);
        assertNotNull(r);
        assertEquals(ErrorCode.RISK_GATED, r.code);

        ExecutionGate.Decision d2 = ExecutionGate.evaluate(cap, new JSONObject(), false);
        ExecutionGate.reject(d2.token);
        ExecutionResult rr = ExecutionGate.authorize(cap, new JSONObject(), false, d2.token);
        assertNotNull(rr);
        assertFalse(ExecutionGate.approve(d2.token));
    }

    @Test public void missingTokenIsGatedAndDenyCannotBeBypassed() throws Exception {
        assertEquals(ErrorCode.RISK_GATED,
                ExecutionGate.authorize(risky("t.r"), new JSONObject(), false, null).code);
        // 即使拿着别处批准过的令牌，DENY 也不放行
        ExecutionGate.Decision d = ExecutionGate.evaluate(risky("t.r2"), new JSONObject(), false);
        ExecutionGate.approve(d.token);
        assertEquals(ErrorCode.GATE_DENIED,
                ExecutionGate.authorize(root("t.root2"), new JSONObject(), true, d.token).code);
        assertEquals(ErrorCode.GATE_DENIED,
                ExecutionGate.authorizeLegacy(root("t.root3"), new JSONObject(), true, true).code);
    }

    @Test public void legacyBooleanHonouredUntilStrict() throws Exception {
        Capability cap = risky("t.legacy");
        ExecutionGate.setStrictConfirm(false);   // r2/fixes 11：默认已是严格，这里显式切回兼容模式测旧路径
        assertNull("非严格模式：旧布尔仍放行（兼容）", ExecutionGate.authorizeLegacy(cap, new JSONObject(), false, true));
        assertEquals(ErrorCode.RISK_GATED, ExecutionGate.authorizeLegacy(cap, new JSONObject(), false, false).code);
        ExecutionGate.setStrictConfirm(true);
        assertEquals("严格模式：旧布尔不能绕过确认", ErrorCode.RISK_GATED,
                ExecutionGate.authorizeLegacy(cap, new JSONObject(), false, true).code);
    }

    @Test public void alwaysAllowSkipsConfirmation() throws Exception {
        Capability cap = risky("t.always");
        ConsentPolicy.revoke(cap.sign);
        assertTrue(ExecutionGate.evaluate(cap, new JSONObject(), false).needsConfirm());
        ConsentPolicy.allowAlways(cap.sign);
        try {
            ExecutionGate.Decision d = ExecutionGate.evaluate(cap, new JSONObject(), false);
            assertTrue(d.allowed());
            assertTrue(d.viaAlwaysAllow);
            assertNull(ExecutionGate.authorize(cap, new JSONObject(), false, null));
            // 「始终允许」不作用于不可信来源
            assertTrue(ExecutionGate.evaluate(cap, new JSONObject(), true).needsConfirm());
        } finally {
            ConsentPolicy.revoke(cap.sign);
        }
    }

    @Test public void canonicalDigest() throws Exception {
        assertEquals(ExecutionGate.digest(new JSONObject().put("a", 30).put("b", "x")),
                ExecutionGate.digest(new JSONObject().put("b", "x").put("a", 30.0)));
        assertNotEquals(ExecutionGate.digest(new JSONObject().put("a", 1)),
                ExecutionGate.digest(new JSONObject().put("a", 2)));
        assertEquals(ExecutionGate.digest(null), ExecutionGate.digest(new JSONObject()));
    }
}
