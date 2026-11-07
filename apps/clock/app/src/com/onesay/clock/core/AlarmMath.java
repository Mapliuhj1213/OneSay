package com.onesay.clock.core;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 下次响铃时刻、最早事件、「当天第一个闹钟」——纯函数。 */
public final class AlarmMath {
    private AlarmMath() { }

    /** 严格晚于 now 的下次响铃（毫秒，秒归零）；关着的闹钟返回 0。 */
    public static long next(Alarm a, long now, TimeZone tz) {
        if (a == null || !a.on) return 0;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        for (int i = 0; i < 9; i++) {
            if (c.getTimeInMillis() > now) {
                if (!a.repeats() || a.onDay(c.get(Calendar.DAY_OF_WEEK) - 1)) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, a.hour);       // 跨夏令时也落在同一墙钟时刻
            c.set(Calendar.MINUTE, a.minute);
        }
        return 0;
    }

    /** 接下来要排的那一次。 */
    public static final class Event {
        public final int alarmId;
        public final long at;
        public final boolean snooze;

        public Event(int alarmId, long at, boolean snooze) { this.alarmId = alarmId; this.at = at; this.snooze = snooze; }

        public String toString() { return (snooze ? "snooze#" : "alarm#") + alarmId + "@" + at; }
    }

    /** 闹钟与贪睡里最早的一次；同一时刻贪睡优先（它是正在进行的那一次）。都没有 → null。 */
    public static Event earliest(List<Alarm> alarms, SnoozeBook snz, long now, TimeZone tz) {
        Event best = null;
        if (snz != null) {
            for (SnoozeBook.Entry e : snz.entries()) {
                if (e.at <= now - SnoozeBook.LATE_MS) continue;
                if (best == null || e.at < best.at) best = new Event(e.alarmId, e.at, true);
            }
        }
        if (alarms != null) {
            for (Alarm a : alarms) {
                long t = next(a, now, tz);
                if (t <= 0) continue;
                if (best == null || t < best.at) best = new Event(a.id, t, false);
            }
        }
        return best;
    }

    /** yyyyMMdd（本地日期），用来判「当天」。 */
    public static int dayKey(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 这次（非贪睡）响铃要不要播报：闹钟是播报闹钟（对齐三星：每个播报闹钟正点首响都播）；
     * 只有开了新增功能「每天只播报一次」时，才要求当天还没有别的（非调试）闹钟响过。
     * @param lastFiredDay 最近一次「非贪睡闹钟响铃」所在的 dayKey（0 = 从没响过）
     */
    public static boolean shouldBrief(Alarm a, long fireAt, int lastFiredDay, boolean firstOnly, TimeZone tz) {
        if (a == null || !a.brief) return false;
        if (!firstOnly || isDebugId(a.id)) return true;     // 调试/示例闹钟不受「每天一次」限制
        return dayKey(fireAt, tz) != lastFiredDay;
    }

    /** 900 以上是调试（990 TEST_ALARM）和截图示例（901–903）用的 id：不参与「当天已响」记账。 */
    public static final int DEBUG_ID_MIN = 900;

    public static boolean isDebugId(int id) { return id >= DEBUG_ID_MIN; }

    /** 这次（非贪睡）响铃要不要记成「当天已响」：调试闹钟不记（0.1.2 的 990 测试把用户当天的播报闹钟挡掉了）。 */
    public static boolean recordsFiredDay(int id, boolean snooze) { return !snooze && !isDebugId(id); }

    /** 不播报的原因（日志用）：brief=播报 / not-brief-alarm / first-only。 */
    public static String briefReason(Alarm a, long fireAt, int lastFiredDay, boolean firstOnly, TimeZone tz) {
        if (a == null) return "no-alarm";
        if (!a.brief) return "not-brief-alarm";
        return shouldBrief(a, fireAt, lastFiredDay, firstOnly, tz) ? "brief" : "first-only(lastFiredDay=" + lastFiredDay + ")";
    }

    /** 「X 小时 Y 分钟后响铃」（列表顶部）。 */
    public static String untilLabel(long at, long now) {
        if (at <= 0) return "所有闹钟均已关闭";
        long min = Math.max(0, (at - now + 59_999L) / 60_000L);
        long d = min / 1440, h = (min % 1440) / 60, m = min % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" 天 ");
        if (h > 0) sb.append(h).append(" 小时 ");
        if (m > 0 || sb.length() == 0) sb.append(m).append(" 分钟");
        return sb.toString().trim() + "后响铃";
    }
}
