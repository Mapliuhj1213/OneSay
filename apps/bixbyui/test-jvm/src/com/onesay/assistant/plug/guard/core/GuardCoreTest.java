package com.onesay.assistant.plug.guard.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** mika.guard 核心：阶梯、兜圈、无进展叫停、读循环、连续失败、StuckAdvice。 */
public class GuardCoreTest {

    @Test public void climbLadder() {
        assertNull(GuardRules.climb(2, GuardRules.NO_EFFECT_LADDER, 0, true));
        GuardRules.Rung r = GuardRules.climb(3, GuardRules.NO_EFFECT_LADDER, 0, true);
        assertEquals(3, r.threshold);
        assertEquals(GuardRules.Severity.WARN, r.severity);
        assertNull("同一级不重复提醒", GuardRules.climb(4, GuardRules.NO_EFFECT_LADDER, 3, true));
        assertEquals(GuardRules.Severity.STOP, GuardRules.climb(12, GuardRules.NO_EFFECT_LADDER, 9, true).severity);
        assertEquals(GuardRules.Severity.WARN, GuardRules.climb(12, GuardRules.NO_EFFECT_LADDER, 9, false).severity);
        assertEquals(15, GuardRules.lateStop(60, 4, 10));
        assertEquals(10, GuardRules.lateStop(20, 4, 10));
    }

    @Test public void cycles() {
        assertNull(GuardRules.cycle(Arrays.asList(1L, 2L, 3L)));
        GuardRules.Cycle c = GuardRules.cycle(Arrays.asList(1L, 2L, 1L, 2L, 1L));
        assertEquals(2, c.period);
        assertEquals(2, c.rounds());
        GuardRules.Cycle c3 = GuardRules.cycle(Arrays.asList(1L, 2L, 3L, 1L, 2L, 3L));
        assertEquals(3, c3.period);
    }

    @Test public void noEffectLadderStopsAt12() {
        OperationGuards g = new OperationGuards(null);
        List<OperationGuards.Verdict> all = new ArrayList<OperationGuards.Verdict>();
        for (int i = 0; i < 12; i++) {
            g.noteAction();
            all.addAll(g.effect("no_effect"));
        }
        assertEquals(4, all.size());
        assertEquals("no_progress", all.get(0).code);
        assertNotNull(all.get(0).nudge);
        assertTrue(all.get(0).nudge.contains("出路"));
        assertTrue(all.get(3).stop());
        assertNotNull(g.stop());
        assertTrue(StuckAdvice.stopReason(g.stop()).contains("已安全停止"));
        g.noteAction();
        g.effect("done");
        assertEquals(0, g.unchangedFrames());
    }

    @Test public void frameProgressClearsNoProgressStop() {
        OperationGuards g = new OperationGuards(null);
        g.frame("s1", 100L);
        for (int i = 0; i < 12; i++) {
            g.noteAction();
            g.frame("s" + (i + 2), 100L);
        }
        assertNotNull(g.stop());
        g.noteAction();
        g.frame("x", 200L);
        assertNull("新状态 = 进展，清掉无进展叫停", g.stop());
    }

    @Test public void cycleOnlyWarns() {
        OperationGuards g = new OperationGuards(null);
        List<OperationGuards.Verdict> vs = new ArrayList<OperationGuards.Verdict>();
        long[] seq = {1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2};
        int k = 0;
        for (long h : seq) {
            g.noteAction();
            vs.addAll(g.frame("f" + (k++), h));
        }
        boolean cycleWarn = false;
        for (OperationGuards.Verdict v : vs) {
            if (v.code.equals("state_cycle")) cycleWarn = true;
            if (v.code.equals("state_cycle") || v.code.equals("same_state")) assertFalse(v.stop());
        }
        assertTrue(cycleWarn);
    }

    @Test public void readLoopAndFailures() {
        OperationGuards g = new OperationGuards(null);
        List<OperationGuards.Verdict> r = new ArrayList<OperationGuards.Verdict>();
        for (int i = 0; i < 12; i++) r.addAll(g.read("observe", false, null));
        assertEquals(3, r.size());
        assertTrue(r.get(2).stop());
        g.pageChanged();
        assertTrue(g.read("observe", false, null).isEmpty());
        OperationGuards f = new OperationGuards(null);
        assertTrue(f.result("tap", false, true, "x").isEmpty());
        assertEquals(GuardRules.Severity.WARN, f.result("tap", false, true, "x").get(0).severity);
        assertTrue(f.result("tap", true, true, null).isEmpty());
        assertEquals(GuardRules.Severity.WARN, f.result("launch", false, false, "no app").get(0).severity);
        f.result("launch", false, false, "no app");
        assertEquals(GuardRules.Severity.STOP, f.result("launch", false, false, "no app").get(0).severity);
    }

    @Test public void stuckAdvice() {
        StuckAdvice a = new StuckAdvice();
        OperationGuards g = new OperationGuards(null);
        List<OperationGuards.Verdict> v = new ArrayList<OperationGuards.Verdict>();
        for (int i = 0; i < 3; i++) {
            g.noteAction();
            v = g.effect("no_effect");
        }
        assertNull(a.next(v, null, "com.x", false));
        for (int i = 0; i < 3; i++) {
            g.noteAction();
            v = g.effect("no_effect");
        }
        String adv = a.next(v, null, "com.x", false);
        assertNotNull(adv);
        assertTrue(adv.contains("没有进展"));
        StuckAdvice b = new StuckAdvice();
        assertNull(b.next(v, "天气", "com.x", false));
        String again = b.next(v, "天气", "com.x", false);
        assertNotNull(again);
        assertNull("进展清零", new StuckAdvice().next(new ArrayList<OperationGuards.Verdict>(), "天气", "com.x", true));
    }
}
