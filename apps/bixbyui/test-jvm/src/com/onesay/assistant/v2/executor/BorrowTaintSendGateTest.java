package com.onesay.assistant.v2.executor;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.Test;

/** ★ borrow-1112 ②：读过外部内容（tainted）后，发送 / 分享 / 提交类工具一律要确认；没污染照旧；只加严不放松。 */
public class BorrowTaintSendGateTest {

    static Capability cap(String sign, SideEffect se, int risk, boolean confirm) {
        return new Capability.Builder(sign, sign).schema(new ParamsSchema()).sideEffect(se).risk(risk, confirm).executor("custom").build();
    }

    @Test public void taintedShareNeedsConfirmEvenIfSideEffectLooksHarmless() {
        Capability share = cap("note.share", SideEffect.NONE, 0, false);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(share, new JSONObject(), false).decision);
        ToolGate.GateResult g = ToolGate.check(share, new JSONObject(), true);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, g.decision);
        assertTrue(g.reason, g.reason.contains("外部内容"));
    }

    @Test public void taintedAlwaysAllowIsIgnoredForSend() {
        Capability send = cap("mail.reply", SideEffect.PERSISTENT_WRITE, 2, true);
        try {
            ConsentPolicy.allowAlways("mail.reply");   // 「记住」过
            assertEquals(ToolGate.Decision.ALLOW, ConsentPolicy.gate(send, new JSONObject(), false).decision);
            assertEquals("污点下「记住」不生效", ToolGate.Decision.REQUIRE_CONFIRM, ConsentPolicy.gate(send, new JSONObject(), true).decision);
        } finally {
            ConsentPolicy.revoke("mail.reply");
        }
    }

    @Test public void benignAndReadOnlyStillAllowedWhenTainted() {
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(cap("screen.read", SideEffect.NONE, 0, false), new JSONObject(), true).decision);
        assertEquals(ToolGate.Decision.DENY, ToolGate.check(cap("sys.shell.send", SideEffect.ROOT, 3, true), new JSONObject(), true).decision);
    }
}
