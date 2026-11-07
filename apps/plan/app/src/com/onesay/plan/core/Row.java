package com.onesay.plan.core;

import com.onesay.plan.core.recur.SchedEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一行数据（列名 → 值）。core 层不碰 Cursor / ContentValues，Android 层负责转换。 */
public final class Row extends LinkedHashMap<String, Object> {

    public Row() {}

    public Row(Map<String, Object> m) {
        if (m != null) putAll(m);
    }

    /** put 后返回自己，便于链式构造。 */
    public Row with(String k, Object v) {
        put(k, v);
        return this;
    }

    /** 取整数列；缺失 / null / 非整数按 0。 */
    public long l(String k) {
        Long v = Validator.toLong(get(k));
        return v == null ? 0L : v;
    }

    /** 取整数列；缺失或 null 时返回 dflt。 */
    public long l(String k, long dflt) {
        if (!containsKey(k) || get(k) == null) return dflt;
        return l(k);
    }

    /** 取文本列；null 返回空串。 */
    public String s(String k) {
        Object v = get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /** {@code _id} 列。 */
    public long id() { return l(Contract.C_ID); }

    /** 日程行 → SchedEvent（给重复展开用）。 */
    public SchedEvent toEvent() {
        SchedEvent e = new SchedEvent();
        e.id = id();
        e.title = s("title");
        e.startAt = l("start_at");
        e.endAt = l("end_at");
        e.allDay = l("all_day") != 0;
        e.location = s("location");
        e.note = s("note");
        e.repeat = (int) l("repeat");
        e.weekMask = (int) l("week_mask");
        e.monthDay = (int) l("month_day");
        e.lunar = l("lunar") != 0;
        e.lunarMonth = (int) l("lunar_month");
        e.lunarDay = (int) l("lunar_day");
        e.leadMin = (int) l("lead_min", -1);
        e.source = s("source");
        e.extId = s("ext_id");
        e.createdAt = l("created_at");
        e.updatedAt = l("updated_at");
        return e;
    }
}
