package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 打电话 / 发短信的号码分级（CommsRisk.assess + ToolGate）：拨号指令（* # , ; p w）、MMI / 呼叫转移码、
 * 全角数字、零宽、阿拉伯数字、字母、tel: 前缀、过短 / 过长 → 直接拒绝（不弹卡）；正常号码 → 打电话红档、短信黄档，都要确认；
 * 紧急号码红档且理由里点名。分隔符（空格、短横、括号、点、全角空格、不换行空格）会被去掉。每个号码 × 两种能力。
 */
@RunWith(Parameterized.class)
public class AdvCommsNumberTest {

    static final String[] DENY = {
            "", " ", "*#06#", "*21*13800138000#", "##002#", "#31#10086", "10086,1", "10086;1", "10086p123", "10086P1", "10086w1", "10086W9",
            "１３８００１３８０００", "138\u200b0013 8000", "١٣٨٠٠١٣٨٠٠٠", "abc", "tel:10086", "+", "+1", "12", "1234567890123456",
            "+12345678901234567", "10086\n", "10086\u0000", "10086#", "*10086", "138-0013-800０", "10O86", "1оо86",
            "javascript:10086", "10086/1", "+86+13800138000", "13800138000ext1", "\u202e10086"
    };
    static final String[] OK = {
            "10086", "110", "119", "120", "122", "112", "999", "911", "13800138000", "138 0013 8000", "138-0013-8000",
            "(010) 6552 9988", "+86 138 0013 8000", "+8613800138000", "010.6552.9988", "138\u30000013\u30008000", "138\u00a00013\u00a08000",
            "123", "123456789012345", "+123456789012345", "400-820-8820", "95588"
    };

    @Parameterized.Parameters(name = "{index}: {0} [{1}]")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String sign : new String[]{"phone.call", "sms.send"}) {
            for (String n : DENY) { if (!seen.add(sign + n)) throw new IllegalStateException(n); out.add(new Object[]{sign, n, false}); }
            for (String n : OK) { if (!seen.add(sign + n)) throw new IllegalStateException(n); out.add(new Object[]{sign, n, true}); }
        }
        return out;
    }

    private final String sign, number;
    private final boolean ok;

    public AdvCommsNumberTest(String sign, String number, boolean ok) {
        this.sign = sign;
        this.number = number;
        this.ok = ok;
    }

    @Test public void grade() throws Exception {
        JSONObject args = new JSONObject().put("number", number).put("text", "hi");
        CommsRisk.Assessment a = CommsRisk.assess(sign, args);
        Capability cap = new Capability.Builder(sign, "adv").domain("phone").risk(2, true).sideEffect(SideEffect.PUBLIC_COMMS).cmd("am start").build();
        ToolGate.GateResult g = ToolGate.check(cap, args, false);
        ToolGate.GateResult gt = ToolGate.check(cap, args, true);
        if (!ok) {
            assertEquals(CommsRisk.Level.DENY, a.level);
            assertEquals(ToolGate.Decision.DENY, g.decision);
            assertEquals("污点下同样拒绝", ToolGate.Decision.DENY, gt.decision);
            return;
        }
        assertEquals(sign.equals("sms.send") ? CommsRisk.Level.YELLOW : CommsRisk.Level.RED, a.level);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, g.decision);
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, gt.decision);
        String digits = a.normalizedNumber.startsWith("+") ? a.normalizedNumber.substring(1) : a.normalizedNumber;
        assertTrue(a.normalizedNumber, digits.matches("\\d{3,15}"));
        if (sign.equals("phone.call") && CommsRisk.EMERGENCY.contains(digits)) assertTrue(a.reason, a.reason.contains("紧急号码"));
        assertFalse("通信类永远不接受「始终允许」", ConsentPolicy.acceptsAlwaysAllow(cap));
    }
}
