package com.onesay.plan.core.recur;

import static com.onesay.plan.core.recur.RecurTestUtil.TZ;
import static com.onesay.plan.core.recur.RecurTestUtil.at;
import static org.junit.Assert.*;

import org.junit.Test;

/** 农历换算（JVM 下经 shims 走 icu4j）与 {@link Lunar#FIX} 修正表。 */
public class LunarTest {
    @Test public void available() {
        assertTrue(Lunar.available());
    }

    @Test public void springFestival2026() {
        assertArrayEquals(new int[]{2026, 2, 17}, Lunar.toSolar(2026, 1, false, 1, TZ));
    }

    @Test public void fixTableOverridesIcu2027() {
        assertArrayEquals("官方 2027 春节 2 月 6 日", new int[]{2027, 2, 6}, Lunar.toSolar(2027, 1, false, 1, TZ));
    }

    @Test public void midAutumn() {
        assertArrayEquals(new int[]{2026, 9, 25}, Lunar.toSolar(2026, 8, false, 15, TZ));
        assertEquals(at(2027, 9, 15, 0, 0), Lunar.nextOccurrence(8, 15, at(2026, 11, 6, 0, 0), TZ));
    }

    @Test public void fromSolarRoundTrip() {
        int[] r = Lunar.fromSolar(2026, 9, 25);
        assertNotNull(r);
        assertEquals(8, r[1]);
        assertEquals(15, r[2]);
        assertEquals(0, r[3]);
    }

    @Test public void labels() {
        assertEquals("八月十五", Lunar.label(8, 15));
        assertEquals("正月初一", Lunar.label(1, 1));
        assertEquals("腊月廿三", Lunar.label(12, 23));
        assertEquals("冬月二十", Lunar.label(11, 20));
    }

    @Test public void invalidDays() {
        assertNull(Lunar.toSolar(2026, 13, false, 1, TZ));
        assertNull(Lunar.toSolar(2026, 1, false, 31, TZ));
    }
}
