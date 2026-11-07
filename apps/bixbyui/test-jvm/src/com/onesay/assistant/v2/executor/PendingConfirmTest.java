package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * F2：FloatingSheetService 单步确认卡 / 多步计划 / 循环三条路径迁移到 ExecutionGate 后的流程
 * （按 FSS 实际调用顺序复现：evaluate → hold → allow/reject → execute(cap,args,token,tainted)），严格模式下。
 */
public class PendingConfirmTest {

    static Capability risky() {
        return new Capability.Builder("t.fss.risky", "高危测试").risk(2, true)
                .sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo risky").build();
    }

    static Capability safe() {
        return new Capability.Builder("t.fss.safe", "安全测试").risk(0, false).cmd("echo safe").build();
    }

    static UnifiedExecutor exec(UnifiedExecutorTest.FakeShell sh) {
        return new UnifiedExecutor(null, sh, new UnifiedExecutorTest.FakeApps(), null);
    }

    static final class Clock implements ExecutionGate.Clock {
        long t = 1000000L;
        @Override public long now() { return t; }
    }

    @After public void reset() {
        ExecutionGate.setClockForTest(null);
        ExecutionGate.setStrictConfirm(true);   // r2/fixes 11：恢复生产默认（严格）
    }

    @Test public void singleStepCardAllowExecutesExactlyOnce() throws Exception {
        ExecutionGate.setStrictConfirm(true);
        UnifiedExecutorTest.FakeShell sh = new UnifiedExecutorTest.FakeShell();
        Capability cap = risky();
        JSONObject args = new JSONObject();
        PendingConfirm pc = new PendingConfirm();

        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, false);
        assertTrue(d.needsConfirm());
        pc.hold(d, "重启一下");
        assertTrue(pc.isHolding());

        PendingConfirm.Approved ap = pc.allow();
        assertTrue(ap.ok());
        assertEquals("重启一下", ap.query);
        assertFalse("令牌只能取一次", pc.allow().ok());

        ExecutionResult r = exec(sh).execute(cap, args, ap.token, false);
        assertTrue(r.ok);
        assertEquals(1, sh.cmds.size());
        ExecutionResult replay = exec(sh).execute(cap, args, ap.token, false);
        assertFalse("重放被拒", replay.ok);
        assertEquals(1, sh.cmds.size());
    }

    @Test public void rejectAndExpiryNeverExecute() throws Exception {
        ExecutionGate.setStrictConfirm(true);
        Clock clock = new Clock();
        ExecutionGate.setClockForTest(clock);
        UnifiedExecutorTest.FakeShell sh = new UnifiedExecutorTest.FakeShell();
        Capability cap = risky();
        PendingConfirm pc = new PendingConfirm();

        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, new JSONObject(), false);
        pc.hold(d, "q1");
        assertEquals("q1", pc.reject());
        assertEquals(ExecutionGate.TokenState.REJECTED, d.token.state());
        ExecutionResult r = exec(sh).execute(cap, new JSONObject(), d.token, false);
        assertEquals(ErrorCode.USER_REJECTED, r.code);

        ExecutionGate.Decision d2 = ExecutionGate.evaluate(cap, new JSONObject(), false);
        pc.hold(d2, "q2");
        clock.t += ExecutionGate.TOKEN_TTL_MS + 1;
        PendingConfirm.Approved ap = pc.allow();
        assertFalse("过期令牌不能批准", ap.ok());
        assertEquals("q2", ap.query);
        assertEquals(0, sh.cmds.size());
    }

    @Test public void newCardInvalidatesPreviousToken() throws Exception {
        Capability cap = risky();
        PendingConfirm pc = new PendingConfirm();
        ExecutionGate.Decision d1 = ExecutionGate.evaluate(cap, new JSONObject(), false);
        pc.hold(d1, "a");
        ExecutionGate.Decision d2 = ExecutionGate.evaluate(cap, new JSONObject(), false);
        pc.hold(d2, "b");
        assertEquals(ExecutionGate.TokenState.REJECTED, d1.token.state());
        assertSame(d2.token, pc.allow().token);
    }

    @Test public void cardArgsTamperedAfterApprovalAreGated() throws Exception {
        ExecutionGate.setStrictConfirm(true);
        UnifiedExecutorTest.FakeShell sh = new UnifiedExecutorTest.FakeShell();
        Capability cap = risky();
        PendingConfirm pc = new PendingConfirm();
        pc.hold(ExecutionGate.evaluate(cap, new JSONObject().put("x", 1), false), null);
        PendingConfirm.Approved ap = pc.allow();
        ExecutionResult r = exec(sh).execute(cap, new JSONObject().put("x", 2), ap.token, false);
        assertEquals(ErrorCode.RISK_GATED, r.code);
        assertEquals(0, sh.cmds.size());
    }

    @Test public void strictModeBlocksLegacyBooleanButNotSafeOrNullTokenAllow() throws Exception {
        ExecutionGate.setStrictConfirm(true);
        UnifiedExecutorTest.FakeShell sh = new UnifiedExecutorTest.FakeShell();
        @SuppressWarnings("deprecation")
        ExecutionResult legacy = exec(sh).executeInternal(risky(), new JSONObject(), true, false);
        assertEquals("严格模式：旧布尔不能绕过确认", ErrorCode.RISK_GATED, legacy.code);
        // FSS 放行路径（含「始终允许」与反悔开关）：token=null
        ExecutionResult ok = exec(sh).execute(safe(), new JSONObject(), null, false);
        assertTrue(ok.ok);
        assertEquals(1, sh.cmds.size());
    }

    @Test public void planAbortRejectsIssuedToken() throws Exception {
        // executePlan：非 ALLOW 即中止，并作废随结论签发的令牌（避免令牌表堆积）
        int before = ExecutionGate.pendingTokenCountForTest();
        ExecutionGate.Decision d = ExecutionGate.evaluate(risky(), new JSONObject(), false);
        assertFalse(d.allowed());
        ExecutionGate.reject(d.token);
        assertEquals(before, ExecutionGate.pendingTokenCountForTest());
        ExecutionGate.reject(null);   // ALLOW 时 token 为 null，必须安全
    }
}
