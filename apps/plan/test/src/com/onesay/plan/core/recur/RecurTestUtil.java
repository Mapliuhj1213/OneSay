package com.onesay.plan.core.recur;

import java.util.Calendar;
import java.util.TimeZone;

final class RecurTestUtil {
    private RecurTestUtil() {}

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    static Calendar cal(int y, int m, int d) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, m - 1, d, 12, 0, 0);
        return c;
    }
}
