package com.onesay.plan.core.recur;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [sched] 农历 ↔ 公历（可选功能）。实现用系统自带的 ICU {@code android.icu.util.ChineseCalendar}（API 24+，本机 API 26 可用）；
 * JVM 单测里由 test/shims 的同名类转给 icu4j。ICU 不可用时 {@link #available()} = false，解析器遇到「农历」就如实说「农历暂时算不了」，不会猜。
 *
 * <h2>ICU 与官方农历的已知差异（{@link #FIX}）</h2>
 * ICU 的天文近似在「朔」落在北京时间半夜前后时会差一天。2024–2035 年逐月对过春节日期（维基百科「正月初一」表 / 香港天文台对照表）：
 * <ul>
 *   <li>2027 正月初一：官方 2 月 6 日，ICU 2 月 7 日（朔在 2 月 6 日 23:56 北京时间）；</li>
 *   <li>2030 正月初一：官方 2 月 3 日，ICU 2 月 2 日。</li>
 * </ul>
 * 这里按官方日期修正这两个月的起点（以及前一个月的长度）；新发现的差异往 FIX 里加一行即可（单测 LunarTest 锁定）。
 *
 * <p>ICU 的 EXTENDED_YEAR 是从公元前 2637 年起算的连续年数：公历某年开始的那个农历年 = 公历年 + 2637。
 */
public final class Lunar {

    private Lunar() {}

    static final int EPOCH_OFFSET = 2637;

    /** {农历年, 月（非闰）, 官方初一 yyyyMMdd}。 */
    static final int[][] FIX = {
            {2027, 1, 20270206},
            {2030, 1, 20300203},
    };

    private static volatile Boolean sOk;

    public static boolean available() {
        Boolean b = sOk;
        if (b != null) return b;
        boolean ok;
        try {
            int[] r = toSolar(2026, 1, false, 1, TimeZone.getTimeZone("Asia/Shanghai"));
            ok = r != null && r[0] == 2026 && r[1] == 2 && r[2] == 17;   // 2026 春节 = 2 月 17 日
        } catch (Throwable t) {
            ok = false;
        }
        sOk = ok;
        return ok;
    }

    // ------------------------------------------------------------------ 日子编号（公历日序号，和时区无关）

    static long epochDay(int y, int m, int d) {
        Calendar g = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        g.clear();
        g.set(y, m - 1, d, 12, 0, 0);
        return g.getTimeInMillis() / 86400000L;
    }

    static int[] ymdOf(long epochDay) {
        Calendar g = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        g.setTimeInMillis(epochDay * 86400000L + 12L * 3600000L);
        return new int[]{g.get(Calendar.YEAR), g.get(Calendar.MONTH) + 1, g.get(Calendar.DAY_OF_MONTH)};
    }

    static long fixStart(int ly, int month, boolean leap) {
        if (leap) return Long.MIN_VALUE;
        for (int[] f : FIX) {
            if (f[0] == ly && f[1] == month) return epochDay(f[2] / 10000, f[2] / 100 % 100, f[2] % 100);
        }
        return Long.MIN_VALUE;
    }

    /** ICU 原样算的某月初一（公历日序号）；这个月不存在（例如不是闰月却要闰）返回 MIN_VALUE。 */
    static long icuStart(int ly, int month, boolean leap) {
        try {
            android.icu.util.ChineseCalendar cc = new android.icu.util.ChineseCalendar();
            cc.clear();
            cc.set(android.icu.util.Calendar.EXTENDED_YEAR, ly + EPOCH_OFFSET);
            cc.set(android.icu.util.Calendar.MONTH, month - 1);
            cc.set(android.icu.util.Calendar.IS_LEAP_MONTH, leap ? 1 : 0);
            cc.set(android.icu.util.Calendar.DAY_OF_MONTH, 1);
            cc.set(android.icu.util.Calendar.HOUR_OF_DAY, 12);
            long ms = cc.getTimeInMillis();
            android.icu.util.ChineseCalendar chk = new android.icu.util.ChineseCalendar();
            chk.setTimeInMillis(ms);
            if (chk.get(android.icu.util.Calendar.MONTH) != month - 1 || chk.get(android.icu.util.Calendar.DAY_OF_MONTH) != 1
                    || (chk.get(android.icu.util.Calendar.IS_LEAP_MONTH) != 0) != leap) return Long.MIN_VALUE;
            Calendar g = Calendar.getInstance();
            g.setTimeInMillis(ms);
            return epochDay(g.get(Calendar.YEAR), g.get(Calendar.MONTH) + 1, g.get(Calendar.DAY_OF_MONTH));
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    /** 官方的某月初一（先查修正表，再用 ICU）。 */
    static long monthStart(int ly, int month, boolean leap) {
        long f = fixStart(ly, month, leap);
        return f != Long.MIN_VALUE ? f : icuStart(ly, month, leap);
    }

    /** 这个月之后那个月的初一（闰月紧跟同号月；腊月之后是下一年正月）。 */
    static long nextMonthStart(int ly, int month, boolean leap) {
        if (!leap) {
            long l = icuStart(ly, month, true);
            if (l != Long.MIN_VALUE) return l;
        }
        if (month == 12) return monthStart(ly + 1, 1, false);
        return monthStart(ly, month + 1, false);
    }

    /**
     * 农历 lunarYear 年（以公历年份称呼，即这一年春节所在的公历年）month 月 day 日 → 公历 {年, 月(1-12), 日}。
     * 日子不存在（例如那年腊月只有 29 天却要三十）返回 null。
     */
    public static int[] toSolar(int lunarYear, int month, boolean leap, int day, TimeZone tz) {
        if (month < 1 || month > 12 || day < 1 || day > 30) return null;
        long s = monthStart(lunarYear, month, leap);
        if (s == Long.MIN_VALUE) return null;
        long n = nextMonthStart(lunarYear, month, leap);
        if (n == Long.MIN_VALUE || n <= s) return null;
        if (s + day - 1 >= n) return null;
        return ymdOf(s + day - 1);
    }

    /** 公历 y-m-d → 农历 {农历年(公历称呼), 月, 日, 是否闰月(0/1)}。 */
    public static int[] fromSolar(int y, int m, int d) {
        try {
            Calendar g = Calendar.getInstance();
            g.clear();
            g.set(y, m - 1, d, 12, 0, 0);
            android.icu.util.ChineseCalendar cc = new android.icu.util.ChineseCalendar();
            cc.setTimeInMillis(g.getTimeInMillis());
            int ly = cc.get(android.icu.util.Calendar.EXTENDED_YEAR) - EPOCH_OFFSET;
            int mo = cc.get(android.icu.util.Calendar.MONTH) + 1;
            boolean leap = cc.get(android.icu.util.Calendar.IS_LEAP_MONTH) != 0;
            long day = epochDay(y, m, d);
            // ICU 的月份附近找官方意义上包含这天的月（修正表只改起点，前后一个月就够）
            int[][] cands = {{ly, mo, leap ? 1 : 0}, {mo == 12 ? ly + 1 : ly, mo == 12 ? 1 : mo + 1, 0}, {mo == 1 ? ly - 1 : ly, mo == 1 ? 12 : mo - 1, 0}};
            for (int[] c : cands) {
                long s = monthStart(c[0], c[1], c[2] == 1);
                long n = nextMonthStart(c[0], c[1], c[2] == 1);
                if (s != Long.MIN_VALUE && n != Long.MIN_VALUE && day >= s && day < n) return new int[]{c[0], c[1], (int) (day - s + 1), c[2]};
            }
            return new int[]{ly, mo, cc.get(android.icu.util.Calendar.DAY_OF_MONTH), leap ? 1 : 0};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 fromDay（那天 0 点，tz）起（含当天）下一个农历 month 月 day 日的公历 0 点；找不到返回 -1。
     * 那年没有三十的按二十九（民间过生日 / 除夕的习惯）。
     */
    public static long nextOccurrence(int month, int day, long fromDay, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(fromDay);
        int y = c.get(Calendar.YEAR);
        for (int ly = y - 1; ly <= y + 2; ly++) {
            int[] s = toSolar(ly, month, false, day, tz);
            if (s == null && day == 30) s = toSolar(ly, month, false, 29, tz);
            if (s == null) continue;
            Calendar r = Calendar.getInstance(tz);
            r.clear();
            r.set(s[0], s[1] - 1, s[2], 0, 0, 0);
            if (r.getTimeInMillis() >= fromDay) return r.getTimeInMillis();
        }
        return -1L;
    }

    static final String[] MONTH_CN = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};

    /** 「八月十五」「正月初一」「腊月廿三」。 */
    public static String label(int month, int day) {
        String m = month >= 1 && month <= 12 ? MONTH_CN[month - 1] + "月" : month + "月";
        return m + dayLabel(day);
    }

    static String dayLabel(int d) {
        String[] n = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (d <= 0 || d > 30) return String.valueOf(d);
        if (d <= 10) return "初" + n[d];
        if (d < 20) return "十" + n[d - 10];
        if (d == 20) return "二十";
        if (d < 30) return "廿" + n[d - 20];
        return "三十";
    }
}
