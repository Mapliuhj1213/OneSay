package com.onesay.plan.core.recur;


import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * [sched] 重复日程展开（纯 Java）。一次性日程原样；重复日程按 {@link SchedEvent#startAt} 的钟点、从它那天起按规则逐天展开。
 * 「工作日」用 S3 同一张法定节假日 / 调休表（{@link CnHolidays}）。每年农历走 {@link Lunar}（ICU 不可用就不展开）。
 */
public final class SchedRecur {

    private SchedRecur() {}

    /** 一次具体的发生。 */
    public static final class Occ {
        public final SchedEvent event;
        public final long start;
        public final long end;

        Occ(SchedEvent e, long start, long end) {
            this.event = e;
            this.start = start;
            this.end = end;
        }

        @Override public String toString() {
            return event.title + "@" + start;
        }
    }

    static final int MAX_DAYS = 800;
    static final int MAX_OCC = 500;

    /** 第一次「还没结束」的发生的开始时刻（&gt;= after；全天日程按那天还没过完算）。没有返回 -1。 */
    public static long nextStart(SchedEvent e, long after, TimeZone tz) {
        if (e == null) return -1L;
        if (!e.repeating()) {
            return e.startAt + (e.allDay ? 24L * 3600000L - 1 : 0) >= after ? e.startAt : -1L;
        }
        long from = Math.max(CnTime.dayStart(e.startAt, tz), CnTime.dayStart(after, tz));
        if (e.repeat == SchedEvent.REPEAT_YEARLY) {
            for (int i = 0; i < 4; i++) {
                long d = yearlyOnOrAfter(e, from, tz);
                if (d < 0) return -1L;
                long s = withClock(d, e, tz);
                if (s + (e.allDay ? 24L * 3600000L - 1 : 0) >= after) return s;
                from = CnTime.addDays(d, 1, tz);
            }
            return -1L;
        }
        long d = from;
        for (int i = 0; i < MAX_DAYS; i++, d = CnTime.addDays(d, 1, tz)) {
            if (!matches(e, d, tz)) continue;
            long s = withClock(d, e, tz);
            if (s + (e.allDay ? 24L * 3600000L - 1 : 0) >= after) return s;
        }
        return -1L;
    }

    /** [from, to) 里所有发生（和区间有交集就算；按开始时刻排序）。 */
    public static List<Occ> expand(SchedEvent e, long from, long to, TimeZone tz) {
        List<Occ> out = new ArrayList<Occ>();
        if (e == null || e.deleted || to <= from) return out;
        long dur = e.duration();
        if (!e.repeating()) {
            if (e.startAt < to && e.startAt + dur > from) out.add(new Occ(e, e.startAt, e.startAt + dur));
            return out;
        }
        long first = Math.max(CnTime.dayStart(e.startAt, tz), CnTime.dayStart(from - dur, tz));
        if (e.repeat == SchedEvent.REPEAT_YEARLY) {
            long d = first;
            for (int i = 0; i < 30 && d < to; i++) {
                long y = yearlyOnOrAfter(e, d, tz);
                if (y < 0 || y >= to) break;
                long s = withClock(y, e, tz);
                if (s < to && s + dur > from) out.add(new Occ(e, s, s + dur));
                d = CnTime.addDays(y, 1, tz);
            }
            return out;
        }
        long d = first;
        for (int i = 0; i < MAX_DAYS && d < to && out.size() < MAX_OCC; i++, d = CnTime.addDays(d, 1, tz)) {
            if (!matches(e, d, tz)) continue;
            long s = withClock(d, e, tz);
            if (s < to && s + dur > from) out.add(new Occ(e, s, s + dur));
        }
        return out;
    }

    /** 多条日程一起展开、排序（同一时刻按标题）。 */
    public static List<Occ> expandAll(List<SchedEvent> events, long from, long to, TimeZone tz) {
        List<Occ> all = new ArrayList<Occ>();
        if (events != null) for (SchedEvent e : events) all.addAll(expand(e, from, to, tz));
        Collections.sort(all, new Comparator<Occ>() {
            @Override public int compare(Occ a, Occ b) {
                if (a.event.allDay != b.event.allDay && CnTime.dayStart(a.start, TimeZone.getDefault()) == CnTime.dayStart(b.start, TimeZone.getDefault())) {
                    return a.event.allDay ? -1 : 1;   // 同一天：全天的排前面
                }
                if (a.start != b.start) return a.start < b.start ? -1 : 1;
                return a.event.title.compareTo(b.event.title);
            }
        });
        return all;
    }

    /** day（0 点）这天是否有一次发生（不看 startAt 之前）。 */
    static boolean matches(SchedEvent e, long day, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        switch (e.repeat) {
            case SchedEvent.REPEAT_DAILY:
                return true;
            case SchedEvent.REPEAT_WEEKLY: {
                int mask = e.weekMask;
                if (mask == 0) {
                    Calendar s = Calendar.getInstance(tz);
                    s.setTimeInMillis(e.startAt);
                    mask = 1 << (s.get(Calendar.DAY_OF_WEEK) - 1);
                }
                return (mask & (1 << (k.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
            }
            case SchedEvent.REPEAT_WORKDAY:
                return CnHolidays.isWorkday(k);
            case SchedEvent.REPEAT_MONTHLY: {
                int dom = k.get(Calendar.DAY_OF_MONTH);
                int want = e.monthDay;
                if (want == 0) {
                    Calendar s = Calendar.getInstance(tz);
                    s.setTimeInMillis(e.startAt);
                    want = s.get(Calendar.DAY_OF_MONTH);
                }
                if (want < 0) return dom == k.getActualMaximum(Calendar.DAY_OF_MONTH);
                return dom == want;   // 没有 31 号的月份跳过（和系统日历一致）
            }
            default:
                return false;
        }
    }

    /** 每年：from（0 点）当天或之后的下一次（0 点）；-1 = 算不出。 */
    static long yearlyOnOrAfter(SchedEvent e, long from, TimeZone tz) {
        if (e.lunar) {
            if (!Lunar.available()) return -1L;
            return Lunar.nextOccurrence(e.lunarMonth, e.lunarDay, from, tz);
        }
        Calendar s = Calendar.getInstance(tz);
        s.setTimeInMillis(e.startAt);
        int mo = s.get(Calendar.MONTH) + 1, d = s.get(Calendar.DAY_OF_MONTH);
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(from);
        int y = k.get(Calendar.YEAR);
        for (int i = 0; i < 9; i++) {
            long t = CnTime.ymd(y + i, mo, d, tz);   // 2 月 29 日只在闰年
            if (t >= 0 && t >= from) return t;
        }
        return -1L;
    }

    static long withClock(long day, SchedEvent e, TimeZone tz) {
        if (e.allDay) return day;
        Calendar s = Calendar.getInstance(tz);
        s.setTimeInMillis(e.startAt);
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(day);
        k.set(Calendar.HOUR_OF_DAY, s.get(Calendar.HOUR_OF_DAY));
        k.set(Calendar.MINUTE, s.get(Calendar.MINUTE));
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /** 「每周三」「每个工作日」「每月 15 号」「每年农历八月十五」。 */
    public static String describe(SchedEvent e) {
        switch (e.repeat) {
            case SchedEvent.REPEAT_DAILY: return "每天";
            case SchedEvent.REPEAT_WORKDAY: return "每个工作日";
            case SchedEvent.REPEAT_WEEKLY: {
                int mask = e.weekMask;
                if (mask == ((1 << 6) | 1)) return "每个周末";
                if (mask == 0x3e) return "每周一到周五";
                StringBuilder b = new StringBuilder("每周");
                String names = "日一二三四五六";
                int[] order = {1, 2, 3, 4, 5, 6, 0};
                for (int i : order) if ((mask & (1 << i)) != 0) b.append(names.charAt(i));
                return b.length() == 2 ? "每周" : b.toString();
            }
            case SchedEvent.REPEAT_MONTHLY: return e.monthDay < 0 ? "每月最后一天" : e.monthDay == 0 ? "每月" : "每月" + e.monthDay + "号";
            case SchedEvent.REPEAT_YEARLY: return e.lunar ? "每年农历" + Lunar.label(e.lunarMonth, e.lunarDay) : "每年";
            default: return "";
        }
    }
}
