package com.onesay.assistant.v2.router;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B03：「晚上 12 点」= 0 点，不是中午 12 点（t20k router.params alarm-*）。 */
public class B03MidnightAlarmTest {

    private static void at(String q, int h, int m) {
        Invocation i = FastIntents.route(q);
        assertNotNull(q, i);
        assertEquals(q, "clock.alarm.set", i.sign);
        assertEquals(q, h, i.args.optInt("hour"));
        assertEquals(q, m, i.args.optInt("minute"));
    }

    @Test public void eveningTwelveIsMidnight() {
        at("定晚上12点闹钟", 0, 0);
        at("晚上12点30分叫我", 0, 30);
        at("晚上12点一刻叫我", 0, 15);
        at("夜里十二点叫我", 0, 0);
        at("中午12点叫我", 12, 0);
        at("下午12点叫我", 12, 0);
        at("晚上11点叫我", 23, 0);
        at("凌晨12点叫我", 0, 0);
    }
}
