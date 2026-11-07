package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.TimeZone;

/** 0.1.4 的「打开进今天」「0 点刷新」规则（从 MainActivity 摘出后锁定）。 */
public class TabNavTest {
    static final TimeZone TZ = RemindersTest.TZ;

    @Test public void openWithoutTabGoesToday() {
        assertEquals("today", TabNav.resolve("tickets", null));
    }

    @Test public void openWithValidTab() {
        for (String t : new String[]{"today", "events", "todos", "tickets", "pickups", "automations"})
            assertEquals(t, TabNav.resolve("today", t));
    }

    @Test public void unknownTabKeepsCurrent() {
        assertEquals("todos", TabNav.resolve("todos", "nope"));
        assertEquals("todos", TabNav.resolve("todos", ""));
    }

    @Test public void restartResetsUnlessInternal() {
        assertEquals("today", TabNav.afterRestart("tickets", false));
        assertEquals("tickets", TabNav.afterRestart("tickets", true));
    }

    @Test public void dayKeyChangesAtLocalMidnight() {
        long before = RemindersTest.at(2026, 11, 6, 23, 59);
        long after = RemindersTest.at(2026, 11, 7, 0, 0);
        assertNotEquals(TabNav.dayKey(before, TZ), TabNav.dayKey(after, TZ));
        assertEquals(TabNav.dayKey(after, TZ), TabNav.dayKey(RemindersTest.at(2026, 11, 7, 23, 59), TZ));
    }

    @Test public void midnightDelay() {
        assertEquals(60000L + 1000L, TabNav.delayToNextMidnight(RemindersTest.at(2026, 11, 6, 23, 59), TZ));
        assertEquals(86400000L + 1000L, TabNav.delayToNextMidnight(RemindersTest.at(2026, 11, 7, 0, 0), TZ));
    }
}
