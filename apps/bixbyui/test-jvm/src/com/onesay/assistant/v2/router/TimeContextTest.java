package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;

import org.junit.Test;

/** TimeContext：相对时间 → 绝对时刻（2026-10-06「40分钟后叫醒我」事故回归）。 */
public class TimeContextTest {

    private static long delta(String q) {
        TimeContext.Relative r = TimeContext.parseRelative(q);
        assertNotNull("应识别出相对时间: " + q, r);
        return r.deltaSeconds;
    }

    @Test public void relativeAmounts() {
        assertEquals(40 * 60, delta("40分钟后叫醒我"));
        assertEquals(40 * 60, delta("四十分钟后提醒我"));
        assertEquals(2 * 3600, delta("两小时后"));
        assertEquals(90, delta("90秒以后"));
        assertEquals(30 * 60, delta("半个小时之后叫我"));
    }

    /** 「一个半小时」必须是 90 分钟，不能被「半小时后」抢先匹配成 30 分钟。 */
    @Test public void oneAndAHalfHours() {
        assertEquals(90 * 60, delta("一个半小时后叫醒我"));
    }

    @Test public void noRelativeTime() {
        assertNull(TimeContext.parseRelative("打开蓝牙"));
        assertNull(TimeContext.parseRelative(null));
        assertNull(TimeContext.hint("明天早上八点叫我"));
        assertEquals("打开蓝牙", TimeContext.withHint("打开蓝牙"));
    }

    @Test public void targetIsNowPlusDelta() {
        long before = System.currentTimeMillis();
        TimeContext.Relative r = TimeContext.parseRelative("40分钟后叫醒我");
        long diff = r.target.getTimeInMillis() - before;
        assertTrue("target - now ≈ 40min, got " + diff, diff >= 2400_000L - 1000 && diff <= 2400_000L + 5000);
        assertEquals("40 分钟", r.deltaText());
    }

    @Test public void stripRelativeRemovesTemporalHou() {
        String s = TimeContext.stripRelative("40分钟后叫醒我");
        assertEquals("叫醒我", s);
        assertFalse(s.contains("后"));
    }

    @Test public void describeFormat() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 6, 0, 12);   // 2026-10-06 是星期二
        assertEquals("2026年10月6日 星期二 00:12", TimeContext.describe(c));
        assertEquals("00:12", TimeContext.hhmm(c));
    }
}
