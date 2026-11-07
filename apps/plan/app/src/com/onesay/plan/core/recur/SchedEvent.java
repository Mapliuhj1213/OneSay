package com.onesay.plan.core.recur;

/**
 * [sched] 本地日程的一条记录（纯 Java，JVM 可测）。计划 App 里由 events 表行转换而来，重复展开见 {@link SchedRecur}。
 *
 * <p>时间全部是毫秒（epoch）。全天日程 {@link #allDay} = true 时 {@link #startAt} 是那天 0 点（本机时区）。
 * 重复规则：{@link #repeat} + {@link #weekMask}（每周几，bit0 = 周日 … bit6 = 周六，与 Calendar.DAY_OF_WEEK-1 对齐）
 * / {@link #monthDay}（每月几号，-1 = 月底）/ {@link #lunar}（每年按农历 {@link #lunarMonth} 月 {@link #lunarDay} 日）。
 */
public final class SchedEvent {

    public static final int REPEAT_NONE = 0, REPEAT_DAILY = 1, REPEAT_WEEKLY = 2, REPEAT_WORKDAY = 3, REPEAT_MONTHLY = 4, REPEAT_YEARLY = 5;

    /** 提前量 = 跟随默认设置。 */
    public static final int LEAD_DEFAULT = -1;

    public long id;
    public String title = "";
    public long startAt;
    /** 0 = 没有结束时间（按开始 + 1 小时显示 / 过期）。 */
    public long endAt;
    public boolean allDay;
    public String location = "";
    public String note = "";
    public int repeat = REPEAT_NONE;
    public int weekMask;
    public int monthDay;
    public boolean lunar;
    public int lunarMonth, lunarDay;
    /** 提前多少分钟提醒；{@link #LEAD_DEFAULT} = 用设置里的默认值；0 = 准点。 */
    public int leadMin = LEAD_DEFAULT;
    /** voice / text / console / import。 */
    public String source = "voice";
    /** 导入来源的外部 id（系统日历 Instances 的 event id）；本地新建为空。 */
    public String extId = "";
    public long createdAt, updatedAt;
    public boolean deleted;
    public long deletedAt;

    public SchedEvent() {}

    public SchedEvent copy() {
        SchedEvent e = new SchedEvent();
        e.id = id; e.title = title; e.startAt = startAt; e.endAt = endAt; e.allDay = allDay; e.location = location; e.note = note;
        e.repeat = repeat; e.weekMask = weekMask; e.monthDay = monthDay; e.lunar = lunar; e.lunarMonth = lunarMonth; e.lunarDay = lunarDay;
        e.leadMin = leadMin; e.source = source; e.extId = extId; e.createdAt = createdAt; e.updatedAt = updatedAt;
        e.deleted = deleted; e.deletedAt = deletedAt;
        return e;
    }

    public boolean repeating() {
        return repeat != REPEAT_NONE;
    }

    /** 只读导入的（系统日历）：不能在 OneSay 里改，只能删掉这份副本。 */
    public boolean imported() {
        return "import".equals(source);
    }

    /** 时长（ms）：没有结束时间的按 1 小时，全天按 1 天。 */
    public long duration() {
        if (endAt > startAt) return endAt - startAt;
        return allDay ? 24L * 3600000L : 3600000L;
    }

    @Override
    public String toString() {
        return "#" + id + " " + title + " @" + startAt + (allDay ? " allDay" : "") + " rep=" + repeat + " mask=" + weekMask
                + " md=" + monthDay + (lunar ? " lunar " + lunarMonth + "/" + lunarDay : "") + " lead=" + leadMin + (deleted ? " DELETED" : "");
    }
}
