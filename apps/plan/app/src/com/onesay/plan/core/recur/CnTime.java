package com.onesay.plan.core.recur;

import java.util.Calendar;
import java.util.TimeZone;

/** 从 OneSay sched/CnTime 摘出的三个日期小工具（SchedRecur 用）；口语时间解析留在 OneSay。 */
public final class CnTime {
    private CnTime() {}

    public static long dayStart(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    public static long addDays(long day, int n, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        k.add(Calendar.DAY_OF_MONTH, n);
        return k.getTimeInMillis();
    }

    public static long ymd(int y, int m, int d, TimeZone tz) {
        if (m < 1 || m > 12 || d < 1) return -1L;
        Calendar k = Calendar.getInstance(tz);
        k.clear();
        k.set(Calendar.YEAR, y);
        k.set(Calendar.MONTH, m - 1);
        if (d > k.getActualMaximum(Calendar.DAY_OF_MONTH)) return -1L;
        k.set(y, m - 1, d, 0, 0, 0);
        return k.getTimeInMillis();
    }
}
