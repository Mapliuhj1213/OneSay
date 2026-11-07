package com.onesay.assistant.v2.sched;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** [sched] 给人看 / 给 TTS 念的时间说法（纯 Java）。口语：「明天下午3点」「周三上午9点半」「11月3日 周二 全天」。 */
public final class SchedFormat {

    private SchedFormat() {}

    static final String WEEK = "日一二三四五六";

    /** 「今天 / 明天 / 后天 / 周三 / 10月28日」（7 天内说星期，跨年带年份）。 */
    public static String dayWord(long day, long now, TimeZone tz) {
        long d0 = CnTime.dayStart(now, tz), d = CnTime.dayStart(day, tz);
        int diff = Math.round((d - d0) / 86400000f);
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(d);
        String wk = "周" + WEEK.charAt(k.get(Calendar.DAY_OF_WEEK) - 1);
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        if (diff == -1) return "昨天";
        Calendar n = Calendar.getInstance(tz);
        n.setTimeInMillis(now);
        String md = (k.get(Calendar.MONTH) + 1) + "月" + k.get(Calendar.DAY_OF_MONTH) + "日";
        if (k.get(Calendar.YEAR) != n.get(Calendar.YEAR)) md = k.get(Calendar.YEAR) + "年" + md;
        if (diff > 2 && diff < 7) {
            // 本周内说「周X」，跨到下周说「下周X」
            int todayIdx = (n.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            int idx = (k.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            return (idx > todayIdx ? wk : "下" + wk) + "（" + md + "）";
        }
        return md + " " + wk;
    }

    /** 「下午3点」「上午9点半」「晚上7点45分」「中午12点」「凌晨0点」。 */
    public static String clockWord(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        int h = k.get(Calendar.HOUR_OF_DAY), m = k.get(Calendar.MINUTE);
        String p;
        int hh = h;
        if (h < 6) p = "凌晨";
        else if (h < 9) p = "早上";
        else if (h < 12) p = "上午";
        else if (h == 12) p = "中午";
        else if (h < 18) { p = "下午"; hh = h - 12; }
        else { p = "晚上"; hh = h - 12; }
        String mm = m == 0 ? "" : m == 30 ? "半" : (m < 10 ? "0" + m : String.valueOf(m)) + "分";
        return p + hh + "点" + mm;
    }

    /** 「明天下午3点」「周三（10月28日）全天」。 */
    public static String when(long start, boolean allDay, long now, TimeZone tz) {
        String d = dayWord(start, now, tz);
        return allDay ? d + (d.endsWith("）") ? "" : " ") + "全天" : d + clockWord(start, tz);
    }

    /** 「明天下午3点到5点」。 */
    public static String span(long start, long end, boolean allDay, long now, TimeZone tz) {
        String s = when(start, allDay, now, tz);
        if (allDay || end <= start) return s;
        if (CnTime.dayStart(end, tz) != CnTime.dayStart(start, tz)) return s + "到" + when(end, false, now, tz);
        String a = clockWord(start, tz), b = clockWord(end, tz);
        // 同一时段不重复：「下午3点到5点」
        return s + "到" + (a.substring(0, 2).equals(b.substring(0, 2)) ? b.substring(2) : b);
    }

    /** 控制台列表用的 24 小时制「15:00」「15:00–16:30」「全天」。 */
    public static String hm(long start, long end, boolean allDay, TimeZone tz) {
        if (allDay) return "全天";
        String a = fmt(start, tz);
        return end > start ? a + "–" + fmt(end, tz) : a;
    }

    static String fmt(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }

    /** 「提前10分钟提醒」「提前1小时提醒」「准点提醒」「不提醒」。 */
    public static String lead(int min) {
        if (min == CnTime.LEAD_NONE) return "不提醒";
        if (min <= 0) return "准点提醒";
        if (min % 1440 == 0) return "提前" + (min / 1440) + "天提醒";
        if (min % 60 == 0) return "提前" + (min / 60) + "小时提醒";
        if (min > 60 && min % 30 == 0) return "提前" + (min / 60) + "个半小时提醒";
        if (min == 30) return "提前半小时提醒";
        return "提前" + min + "分钟提醒";
    }
}
