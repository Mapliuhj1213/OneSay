package com.onesay.plan.core.recur;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [s3] 排期计算（纯 Java，JVM 可测）。所有时间都是 epoch 毫秒 + 显式时区（生产用 {@code TimeZone.getDefault()}，设备是 Asia/Shanghai）。
 *
 * <h2>重复方式</h2>
 * <ul>
 *   <li>{@link #ONCE}：只一次，{@code onceAt} 就是触发时刻；</li>
 *   <li>{@link #DAILY}：每天 HH:mm；</li>
 *   <li>{@link #WEEKLY}：每周指定日 HH:mm，{@code weekMask} 第 0 位 = 周一 … 第 6 位 = 周日；</li>
 *   <li>{@link #WORKDAY}：工作日 HH:mm —— 2026-10-09 起按 {@link CnHolidays} 的法定节假日 / 调休表（调休上班的周末触发、放假的工作日不触发）；
 *       表外年份退回周一到周五并打日志。</li>
 * </ul>
 *
 * <h2>漏触发（重启 / 关机 / Doze 期间错过）</h2>
 * {@link #missDecision}：错过不到 {@link #CATCHUP_MS}（1 小时）→ 补触发一次（岛上显示「晚了 N 分钟」）；超过 → 标记为错过，不补。
 * 重复任务不管补不补，都接着排下一次。
 */
public final class S3Schedule {

    private S3Schedule() {}

    public static final int ONCE = 0, DAILY = 1, WEEKLY = 2, WORKDAY = 3;
    public static final long CATCHUP_MS = 3600L * 1000L;

    public enum Miss { NOT_DUE, FIRE_NOW, FIRE_LATE, MISSED }

    /** Calendar.DAY_OF_WEEK → 掩码位（周一 = 0 … 周日 = 6）。 */
    public static int bitOf(int calendarDow) {
        return calendarDow == Calendar.SUNDAY ? 6 : calendarDow - Calendar.MONDAY;
    }

    /**
     * 严格晚于 {@code afterMs} 的下一次触发时刻；没有下一次（一次性任务已过）返回 -1。
     */
    public static long next(int repeat, int hour, int minute, int weekMask, long onceAt, long afterMs, TimeZone tz) {
        if (repeat == ONCE) return onceAt > afterMs ? onceAt : -1L;
        if (repeat == WORKDAY) return nextWorkday(hour, minute, afterMs, tz);
        int mask = repeat == DAILY ? 0x7F : (weekMask & 0x7F);
        if (mask == 0) return -1L;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(afterMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > afterMs && (mask & (1 << bitOf(c.get(Calendar.DAY_OF_WEEK)))) != 0) {
                return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
        }
        return -1L;
    }

    /** 工作日：按节假日 / 调休表找下一个上班日（最长春节 9 天连休，查 40 天足够）。 */
    static long nextWorkday(int hour, int minute, long afterMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(afterMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        for (int i = 0; i < 40; i++) {
            if (c.getTimeInMillis() > afterMs && CnHolidays.isWorkday(c)) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
        }
        return -1L;
    }

    /** 计划时刻 {@code planned} 在 {@code now} 看来该怎么办（闹钟回调、开机、改时间后都用它）。 */
    public static Miss missDecision(long planned, long now) {
        if (planned <= 0) return Miss.NOT_DUE;
        if (now < planned) return Miss.NOT_DUE;
        long late = now - planned;
        if (late <= 10L * 60L * 1000L) return Miss.FIRE_NOW;
        if (late < CATCHUP_MS) return Miss.FIRE_LATE;
        return Miss.MISSED;
    }

    /** 「每天 08:00」「每周一、三 09:00」「工作日 09:00」「10月9日 15:00」这样的人读描述。 */
    public static String describe(int repeat, int hour, int minute, int weekMask, long onceAt, long now, TimeZone tz) {
        String hm = (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
        switch (repeat) {
            case DAILY: return "每天 " + hm;
            case WORKDAY: return "工作日 " + hm;
            case WEEKLY: {
                StringBuilder sb = new StringBuilder("每周");
                String[] n = {"一", "二", "三", "四", "五", "六", "日"};
                boolean first = true;
                for (int i = 0; i < 7; i++) {
                    if ((weekMask & (1 << i)) != 0) {
                        if (!first) sb.append("、");
                        sb.append(n[i]);
                        first = false;
                    }
                }
                return sb.append(" ").append(hm).toString();
            }
            default: {
                Calendar c = Calendar.getInstance(tz);
                c.setTimeInMillis(onceAt);
                Calendar t = Calendar.getInstance(tz);
                t.setTimeInMillis(now);
                int dd = dayDiff(now, onceAt, tz);
                String day = dd == 0 ? "今天" : dd == 1 ? "明天" : dd == 2 ? "后天"
                        : (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
                return day + " " + hm;
            }
        }
    }

    public static int dayDiff(long a, long b, TimeZone tz) {
        return (int) Math.round((dayStart(b, tz) - dayStart(a, tz)) / 86400000.0);
    }

    public static long dayStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long dayEnd(long ms, TimeZone tz) {
        return dayStart(ms, tz) + 86400000L - 1L;
    }
}
