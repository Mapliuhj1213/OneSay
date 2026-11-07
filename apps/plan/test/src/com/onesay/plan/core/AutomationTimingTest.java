package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** automations.next_at 预估（原 Store.fillNextAt）。 */
public class AutomationTimingTest {
    static final long NOW = RemindersTest.NOW;

    static Row daily(int h) {
        return new Row().with("mode", "time").with("repeat", 1).with("hour", h).with("minute", 0);
    }

    @Test public void newTimeAutomationGetsNextAt() {
        Row w = daily(8);
        AutomationTiming.fillNextAt(w, null, NOW, RemindersTest.TZ);
        assertEquals(com.onesay.plan.core.recur.S3Schedule.next(1, 8, 0, 0, 0, NOW, RemindersTest.TZ), w.l("next_at"));
        assertTrue(w.l("next_at") > NOW);
    }

    @Test public void disabledGetsMinusOne() {
        Row w = daily(8).with("enabled", 0);
        AutomationTiming.fillNextAt(w, null, NOW, RemindersTest.TZ);
        assertEquals(-1L, w.l("next_at"));
    }

    @Test public void callerNextAtWins() {
        Row w = daily(8).with("next_at", 123L);
        AutomationTiming.fillNextAt(w, null, NOW, RemindersTest.TZ);
        assertEquals(123L, w.l("next_at"));
    }

    @Test public void triggerModeUntouched() {
        Row w = new Row().with("mode", "trigger").with("trigger_type", "wifi");
        AutomationTiming.fillNextAt(w, null, NOW, RemindersTest.TZ);
        assertFalse(w.containsKey("next_at"));
    }

    @Test public void updateWithoutTimingFieldsUntouched() {
        Row w = new Row().with("title", "改个名");
        AutomationTiming.fillNextAt(w, daily(8), NOW, RemindersTest.TZ);
        assertFalse(w.containsKey("next_at"));
        Row w2 = new Row().with("hour", 9);
        AutomationTiming.fillNextAt(w2, daily(8), NOW, RemindersTest.TZ);
        assertEquals(com.onesay.plan.core.recur.S3Schedule.next(1, 9, 0, 0, 0, NOW, RemindersTest.TZ), w2.l("next_at"));
    }
}
