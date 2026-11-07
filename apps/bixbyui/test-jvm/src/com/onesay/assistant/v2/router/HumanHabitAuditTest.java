package com.onesay.assistant.v2.router;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** ★ 11-04 F：外部白盒审计里核实成立的两条——「N 分钟后叫我」走闹钟、否定句不能被当成动作执行。 */
public class HumanHabitAuditTest {
    @After public void tearDown() { FastIntents.sNowForTest = 0L; }

    @Test public void jiaoWoIsAlarm() throws Exception {
        FastIntents.sNowForTest = AlarmRelTest.at(2, 41);
        AlarmRelTest.expect("40分钟后叫我", 3, 21);
        AlarmRelTest.expect("四十分钟后喊我", 3, 21);
        AlarmRelTest.expect("帮我定一个40分钟后的闹钟", 3, 21);
        AlarmRelTest.expect("定一个半小时后的闹钟", 4, 11);
        AlarmRelTest.expect("设个半小时后的闹钟", 3, 11);
    }

    @Test public void jiaoWoWithErrandStaysTimer() {
        Invocation f = FastIntents.route("10分钟后叫我吃饭");
        assertNotNull(f);
        assertEquals("clock.timer.set", f.sign);
    }

    @Test public void negatedCommandsNeverL0() {
        com.onesay.assistant.v2.capability.CapabilityRegistry reg = com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance();
        L0RuleRouter l0 = L0RuleRouter.forRegistry(reg);
        String[] qs = {"别打开蓝牙", "不要打开蓝牙", "不用开手电筒", "别关wifi", "千万别静音", "不要提醒我了", "别把音量调大", "先别打开相机"};
        StringBuilder bad = new StringBuilder();
        for (String q : qs) {
            Invocation f = l0.route(q);
            TriageDecider.Decision d = TriageDecider.decide(q, new TriageDecider.Signals(f == null ? null : f.sign, false, false));
            System.out.println("NEG " + q + " l0=" + (f == null ? "-" : f.sign) + " label=" + d.label + " loop=" + d.useAgentLoop + " rule=" + d.rule);
            if (f != null) bad.append(q).append("->").append(f.sign).append("; ");
        }
        assertEquals("", bad.toString());
        for (String q : new String[]{"别打开蓝牙", "不要打开蓝牙", "不用开手电筒", "别关wifi", "千万别静音", "别把音量调大", "先别打开相机"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, null);
            assertEquals(q, TriageDecider.Label.ASK, d.label);
            assertFalse(q, d.useAgentLoop);
        }
        // 取消类 / 非否定照旧
        assertNotEquals(TriageDecider.Label.ASK, TriageDecider.decide("不要提醒我了", null).label);
        assertNotEquals(TriageDecider.Label.ASK, TriageDecider.decide("打开蓝牙", null).label);
        assertFalse(TriageDecider.isNegatedCommand("别忘了明天交房租"));
        assertFalse(TriageDecider.isNegatedCommand("不要钱的耳机推荐一下"));
    }

    @Test public void negatedCommandsNeverFastPath() {
        String[] qs = {"别打开蓝牙", "不要打开蓝牙", "不用开手电筒", "别关wifi", "千万别静音", "不要提醒我了", "别把音量调大", "先别打开相机"};
        for (String q : qs) {
            Invocation f = FastIntents.route(q);
            assertNull(q + " -> " + (f == null ? "" : f.sign), f);
        }
    }
}
