package com.onesay.plan.core;

import com.onesay.plan.core.recur.S3Schedule;

import java.util.TimeZone;

/**
 * 自动任务（定时）的 next_at 预估（纯 Java，从 data/Store 摘出，行为不变）。
 * 只给界面显示「下次」用；真正到点执行由 OneSay 排程。
 */
public final class AutomationTiming {
    private AutomationTiming() {}

    /**
     * 写入前补 next_at：调用方没给 next_at、且这次写入动到了时间规则（或是新建）时，按规则算一个；停用的写 -1。
     *
     * @param w        这次要写入的列（会被就地加上 next_at）
     * @param existing 更新时的原行；新建为 null
     */
    public static void fillNextAt(Row w, Row existing, long now, TimeZone tz) {
        Row m = new Row();
        if (existing != null) m.putAll(existing);
        m.putAll(w);
        if (!Contract.MODE_TIME.equals(m.s("mode"))) return;
        boolean timing = w.containsKey("repeat") || w.containsKey("hour") || w.containsKey("minute") || w.containsKey("week_mask")
                || w.containsKey("once_at") || w.containsKey("enabled") || existing == null;
        if (w.containsKey("next_at") || !timing) return;
        long next = m.l("enabled", 1) == 0 ? -1L
                : S3Schedule.next((int) m.l("repeat"), (int) m.l("hour"), (int) m.l("minute"), (int) m.l("week_mask"), m.l("once_at"), now, tz);
        w.put("next_at", next);
    }
}
