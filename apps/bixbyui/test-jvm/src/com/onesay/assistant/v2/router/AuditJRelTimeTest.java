package com.onesay.assistant.v2.router;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ audit-J 4（Gemini 真机 TIME-02）：「40分钟后是几点」本地算、秒答，不进 AgentLoop。 */
public class AuditJRelTimeTest {

    @After public void tearDown() { FastIntents.sNowForTest = 0L; }

    @Test public void answersLocally() {
        FastIntents.sNowForTest = AlarmRelTest.at(18, 44);
        assertEquals("40分钟后是19:24。", AskEngine.tryAnswer("40分钟后是几点"));
        assertEquals("40分钟后是19:24。", AskEngine.tryAnswer("40分钟后是几点？"));
        assertEquals("两小时后是20:44。", AskEngine.tryAnswer("两小时后是几点"));
        assertEquals("一个半小时后是20:14。", AskEngine.tryAnswer("一个半小时后几点"));
        assertEquals("半小时后是19:14。", AskEngine.tryAnswer("半小时以后是几点"));
        FastIntents.sNowForTest = AlarmRelTest.at(23, 30);
        assertEquals("40分钟后是明天 0:10。", AskEngine.tryAnswer("40分钟后是几点"));
    }

    @Test public void notHijacked() {
        assertNull(AskEngine.tryAnswer("40分钟后叫我"));
        assertNull(AskEngine.tryAnswer("40分钟后提醒我开会"));
        assertNull(AskEngine.tryAnswer("三十小时后是几点"));   // > 24 小时
        assertNotNull("现在几点照旧", AskEngine.tryAnswer("现在几点"));
    }

    @Test public void alarmRelStillWorks() throws Exception {
        FastIntents.sNowForTest = AlarmRelTest.at(18, 38);
        Invocation f = FastIntents.route("40分钟后叫我");
        assertEquals("clock.alarm.set", f.sign);
        assertEquals(19, f.args.getInt("hour"));
        assertEquals(18, f.args.getInt("minute"));
    }

    @Test public void triageKeepsItOutOfAgentLoop() {
        FastIntents.sNowForTest = AlarmRelTest.at(18, 44);
        TriageDecider.Decision d = TriageDecider.decide("40分钟后是几点", new TriageDecider.Signals(null, false, AskEngine.tryAnswer("40分钟后是几点") != null));
        assertFalse(d.toString(), d.useAgentLoop);
    }
}
