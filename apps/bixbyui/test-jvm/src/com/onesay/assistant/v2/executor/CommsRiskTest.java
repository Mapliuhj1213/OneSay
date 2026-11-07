package com.onesay.assistant.v2.executor;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/** sms.send / phone.call 风险分级。 */
public class CommsRiskTest {

    static Capability cap(String sign) {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        for (Capability c : reg.listCapabilities()) if (sign.equals(c.sign)) return c;
        throw new AssertionError("no cap " + sign);
    }

    static JSONObject num(String n) throws Exception { return new JSONObject().put("number", n).put("text", "hi"); }

    @After public void clean() { ConsentPolicy.revoke("phone.call"); ConsentPolicy.revoke("sms.send"); }

    @Test public void mmiAndDtmfCodesAreDenied() throws Exception {
        for (String bad : new String[]{"*#06#", "**21*13800138000#", "10086,1", "10086;2", "13800138000p123", "#31#"}) {
            CommsRisk.Assessment a = CommsRisk.assess("phone.call", num(bad));
            assertEquals(bad, CommsRisk.Level.DENY, a.level);
            assertEquals(bad, ToolGate.Decision.DENY, ToolGate.check(cap("phone.call"), num(bad), false).decision);
        }
    }

    @Test public void namesAndGarbageAreDenied() throws Exception {
        for (String bad : new String[]{"", "妈妈", "12", "1234567890123456", "abc10086"}) {
            assertEquals(bad, CommsRisk.Level.DENY, CommsRisk.assess("phone.call", num(bad)).level);
            assertEquals(bad, CommsRisk.Level.DENY, CommsRisk.assess("sms.send", num(bad)).level);
        }
    }

    @Test public void normalNumbersGraded() throws Exception {
        assertEquals(CommsRisk.Level.RED, CommsRisk.assess("phone.call", num("138-0013 8000")).level);
        assertEquals("13800138000", CommsRisk.assess("phone.call", num("138-0013 8000")).normalizedNumber);
        assertEquals(CommsRisk.Level.RED, CommsRisk.assess("phone.call", num("+86 138 0013 8000")).level);
        CommsRisk.Assessment e = CommsRisk.assess("phone.call", num("120"));
        assertEquals(CommsRisk.Level.RED, e.level);
        assertTrue(e.reason.contains("紧急号码"));
        assertEquals(CommsRisk.Level.YELLOW, CommsRisk.assess("sms.send", num("10086")).level);
        assertEquals(CommsRisk.Level.NOT_COMMS, CommsRisk.assess("wifi.on", num("10086")).level);
    }

    @Test public void tiersAndAlwaysAllow() throws Exception {
        Capability call = cap("phone.call"), sms = cap("sms.send");
        assertEquals(SideEffect.PUBLIC_COMMS, call.sideEffect);
        assertEquals(SideEffect.PUBLIC_COMMS, sms.sideEffect);
        assertEquals(ConsentPolicy.TIER_RED, ConsentPolicy.tierOf(call, false));
        assertEquals(ConsentPolicy.TIER_YELLOW, ConsentPolicy.tierOf(sms, false));
        assertEquals(ConsentPolicy.TIER_RED, ConsentPolicy.tierOf(sms, true));
        // 旧清单里残留 phone.call 的「始终允许」也不放行；2026-10-11 晚起 sms.send 也不接受「始终允许」（逐次黄卡）
        ConsentPolicy.allowAlways("phone.call");
        ConsentPolicy.allowAlways("sms.send");
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ConsentPolicy.gate(call, num("10086"), false).decision);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ConsentPolicy.gate(sms, num("10086"), false).decision);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ConsentPolicy.gate(sms, num("10086"), true).decision);
        ExecutionGate.Decision d = ExecutionGate.evaluate(call, num("10086"), false);
        assertTrue(d.reason, d.reason.contains("立即拨出"));
    }

    @Test public void retrievalKeywordsNoLongerSettings() {
        assertFalse(cap("phone.call").verbs.contains("设置"));
        assertFalse(cap("sms.send").examples.contains("调整设置"));
    }
}
