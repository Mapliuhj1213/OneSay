package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * [borrow-1112] 重复规则与预览（纯 Java）。思路来源：Wake Me（WeckMich，GPL-3.0）——只读了它的功能说明，代码自写。
 *
 * <ul>
 *   <li>WEEKLY：原来的「每周几」（days 位图；0 = 只响一次）。旧闹钟都是它，算法走 {@link AlarmMath#next} 的原路径。</li>
 *   <li>BIWEEKLY 大小周：起始日 anchor 所在那一周（周一开始）响 days，下一周响 daysB，此后两周交替。</li>
 *   <li>MONTHLY_NTH：每月第 nth 个周 nthDow（nth = -1 表示最后一个，如「最后一个周五」）。</li>
 *   <li>EVERY_N：从 anchor 那天起每隔 everyN 天（含 anchor 当天）。</li>
 * </ul>
 * 暂停（{@link Alarm#resume} / {@link Alarm#skips}）只影响重复闹钟：被跳过的那天不响，到期自动恢复，不改规则本身。
 * 日期一律按墙钟日（yyyyMMdd）判，与时区无关；时刻按传入时区落在 hour:minute（跨夏令时与原实现一样落在同一墙钟时刻）。
 */
public final class RepeatRule {
    private RepeatRule() { }

    public static final int WEEKLY = 0, BIWEEKLY = 1, MONTHLY_NTH = 2, EVERY_N = 3;
    /** 规则找下一次最多往后看多少天（暂停最长 366 天 + 每月一次的间隔都够）。 */
    public static final int SCAN_DAYS = 800;

    public static boolean known(int r) { return r == WEEKLY || r == BIWEEKLY || r == MONTHLY_NTH || r == EVERY_N; }

    /** yyyyMMdd 是否是真实日期（2000–2100 年）。 */
    public static boolean validDay(int k) {
        if (k <= 0) return false;
        int y = k / 10000, m = k / 100 % 100, d = k % 100;
        return y >= 2000 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= daysInMonth(y, m);
    }

    static boolean leap(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }

    static int daysInMonth(int y, int m) {
        switch (m) {
            case 2: return leap(y) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    /** 公历日 → 自 1970-01-01 起的天数（与时区无关）。 */
    public static long epochDay(int y, int m, int d) {
        long yy = m <= 2 ? y - 1 : y;
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    public static long epochDay(int dayKey) { return epochDay(dayKey / 10000, dayKey / 100 % 100, dayKey % 100); }

    /** 周几（0=周日 … 6=周六）。 */
    public static int dow(int dayKey) {
        long e = epochDay(dayKey);           // 1970-01-01 是周四（4）
        return (int) (((e + 4) % 7 + 7) % 7);
    }

    static long floorDiv(long a, long b) { long q = a / b; if ((a % b != 0) && ((a < 0) != (b < 0))) q--; return q; }

    /** 规则本身在这一天响不响（不看暂停、不看开关）。 */
    public static boolean matchesDay(Alarm a, int dayKey) {
        int y = dayKey / 10000, m = dayKey / 100 % 100, d = dayKey % 100;
        int w = dow(dayKey);
        switch (a.rule) {
            case BIWEEKLY: {
                if (a.anchor == 0) return false;
                long anchorMon = epochDay(a.anchor) - ((dow(a.anchor) + 6) % 7);   // 起始日所在周的周一
                long thisMon = epochDay(dayKey) - ((w + 6) % 7);
                long weeks = floorDiv(thisMon - anchorMon, 7);
                int mask = (weeks % 2 == 0) ? a.days : a.daysB;
                return (mask & (1 << w)) != 0;
            }
            case MONTHLY_NTH: {
                if (w != a.nthDow) return false;
                if (a.nth == -1) return d + 7 > daysInMonth(y, m);
                return (d - 1) / 7 + 1 == a.nth;
            }
            case EVERY_N: {
                if (a.anchor == 0 || a.everyN < 1) return false;
                long diff = epochDay(dayKey) - epochDay(a.anchor);
                return diff >= 0 && diff % a.everyN == 0;
            }
            default:
                return a.days == 0 || (a.days & (1 << w)) != 0;
        }
    }

    /** 这一天被暂停挡掉吗（单次闹钟不受暂停影响）。 */
    public static boolean pausedOn(Alarm a, int dayKey) {
        if (!a.repeats()) return false;
        if (a.resume > 0 && dayKey < a.resume) return true;
        return a.skips.contains(dayKey);
    }

    /** 一次响铃（预览 / 跳过列表用）。 */
    public static final class Occ {
        public final long at;
        public final int day;
        public final boolean skipped;

        Occ(long at, int day, boolean skipped) { this.at = at; this.day = day; this.skipped = skipped; }

        public String toString() { return day + (skipped ? "(跳过)" : ""); }
    }

    /**
     * 从 now 之后按规则列出响铃（严格晚于 now），最多 max 条、最多看 {@link #SCAN_DAYS} 天。
     * withSkipped = true 时把被暂停挡掉的也列出来（标 skipped）；false 时只列真正会响的。
     * 单次闹钟（WEEKLY 且 days=0）只有一条。
     */
    public static List<Occ> list(Alarm a, long now, int max, boolean withSkipped, TimeZone tz) {
        List<Occ> out = new ArrayList<Occ>();
        if (a == null || max <= 0) return out;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        for (int i = 0; i < SCAN_DAYS && out.size() < max; i++) {
            if (c.getTimeInMillis() > now) {
                int k = AlarmMath.dayKey(c.getTimeInMillis(), tz);
                if (matchesDay(a, k)) {
                    boolean skip = pausedOn(a, k);
                    if (!skip || withSkipped) out.add(new Occ(c.getTimeInMillis(), k, skip));
                    if (!a.repeats()) break;
                }
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, a.hour);
            c.set(Calendar.MINUTE, a.minute);
        }
        return out;
    }

    /** 下次真正会响的时刻（不看开关）；没有返回 0。 */
    public static long next(Alarm a, long now, TimeZone tz) {
        List<Occ> l = list(a, now, 1, false, tz);
        return l.isEmpty() ? 0 : l.get(0).at;
    }

    /** 编辑页预览：接下来 n 次真正会响的。 */
    public static List<Occ> preview(Alarm a, long now, int n, TimeZone tz) { return list(a, now, n, false, tz); }

    /** 暂停期间会被跳过的那些次（最多 max 条）：从 now 起到暂停结束为止，规则本来会响、但被挡掉的日子。 */
    public static List<Occ> skipped(Alarm a, long now, int max, TimeZone tz) {
        List<Occ> out = new ArrayList<Occ>();
        if (a == null || !a.repeats() || !a.hasPause()) return out;
        int last = a.resume > 0 ? a.resume : 0;
        for (int s : a.skips) if (s >= last) last = s + 1;   // 最后一个需要看到的日子之后
        for (Occ o : list(a, now, 1000, true, tz)) {
            if (o.day >= last) break;
            if (o.skipped) out.add(o);
            if (out.size() >= max) break;
        }
        return out;
    }

    static final String[] WD = {"日", "一", "二", "三", "四", "五", "六"};

    static String maskText(int mask) {
        if (mask == 0) return "不响";
        if (mask == 0x7F) return "每天";
        if (mask == 0x3E) return "周一至五";
        if (mask == 0x7E) return "周一至六";
        StringBuilder sb = new StringBuilder("周");
        for (int i = 1; i <= 7; i++) { int d = i % 7; if ((mask & (1 << d)) != 0) sb.append(WD[d]); }
        return sb.toString();
    }

    /** 列表 / 编辑页上的规则描述。WEEKLY 原样用 {@link Alarm#daysLabel}（旧闹钟显示不变）。 */
    public static String label(Alarm a) {
        switch (a.rule) {
            case BIWEEKLY: return "大小周 · 本周" + maskText(a.days) + "，下周" + maskText(a.daysB);
            case MONTHLY_NTH: return "每月" + (a.nth == -1 ? "最后一个" : "第 " + a.nth + " 个") + "周" + WD[a.nthDow];
            case EVERY_N: return "每隔 " + a.everyN + " 天";
            default: return Alarm.daysLabel(a.days);
        }
    }

    /** 列表右侧的短描述（WEEKLY 不用它，列表照旧画七个字）。 */
    public static String shortLabel(Alarm a) {
        switch (a.rule) {
            case BIWEEKLY: return "大小周";
            case MONTHLY_NTH: return (a.nth == -1 ? "每月最后一个" : "每月第 " + a.nth + " 个") + "周" + WD[a.nthDow];
            case EVERY_N: return "每隔 " + a.everyN + " 天";
            default: return Alarm.daysLabel(a.days);
        }
    }

    /** 大小周的「本周」：起始日描述用。anchor 所在周相对 today 所在周是不是同一奇偶（today 这周是不是响 days 的那周）。 */
    public static boolean anchorWeekIsCurrent(int anchor, int today) {
        long a = epochDay(anchor) - ((dow(anchor) + 6) % 7), t = epochDay(today) - ((dow(today) + 6) % 7);
        return floorDiv(t - a, 7) % 2 == 0;
    }

    /** 「11月14日 周五 07:00」。 */
    public static String occText(Occ o, int hour, int minute) {
        int m = o.day / 100 % 100, d = o.day % 100;
        return m + "月" + d + "日 周" + WD[dow(o.day)] + " " + (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    /** 「11月14日」。 */
    public static String dayText(int day) { return (day / 100 % 100) + "月" + (day % 100) + "日"; }

    /** dayKey + n 天。 */
    public static int addDays(int dayKey, int n) {
        long e = epochDay(dayKey) + n;
        // epochDay 反算（civil from days）
        long z = e + 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        if (m <= 2) y++;
        return (int) (y * 10000 + m * 100 + d);
    }
}
