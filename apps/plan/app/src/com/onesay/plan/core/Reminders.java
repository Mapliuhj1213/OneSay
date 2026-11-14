package com.onesay.plan.core;

import com.onesay.plan.core.recur.CnTime;
import com.onesay.plan.core.recur.SchedEvent;
import com.onesay.plan.core.recur.SchedRecur;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * 计划 App 自己的本地提醒排程（纯 Java）：从四类数据算出「哪些时刻要弹通知」。只排一个系统闹钟（最早的那个），
 * 到点后把 (上次检查, 现在] 之间到期的全部发出，再排下一个——与 OneSay S3 的单闹钟做法一致。
 *
 * <ul>
 *   <li>日程：每次发生的开始时刻 − 提前量（lead_min；-1 = 默认 {@link #DEFAULT_EVENT_LEAD_MIN} 分钟）；全天日程在当天 8:00；</li>
 *   <li>待办：remind=1、未完成、有截止 → 截止时刻；</li>
 *   <li>票：有效（status=0）→ 开场/出发前按「票提醒档位」各提醒一次（默认提前 120 + 30 分钟，设置可改），
 *       票自己的 remind_lead_min 若不是默认 120 也加一档、0 = 这张票不提醒；文案带取票码/订单号；</li>
 *   <li>取件码：remind=1、未取、有截止 → 截止前 2 小时（截止不足 2 小时则在截止时刻）；</li>
 *   <li>自动任务不在这里：到点执行归 OneSay（见 README「谁来排程」）。</li>
 * </ul>
 * 「准时」容忍：错过 {@link #CATCHUP_MS} 以内的照样补发，更早的丢掉（手机关机一夜不在早上刷一屏旧提醒）。
 */
public final class Reminders {
    private Reminders() {}

    public static final int DEFAULT_EVENT_LEAD_MIN = 10;
    public static final long PICKUP_LEAD_MS = 2L * 3600000L;
    public static final long CATCHUP_MS = 60L * 60000L;
    /** 票默认提醒档位（分钟，开场/出发前）。 */
    public static final int[] DEFAULT_TICKET_LEADS = {120, 30};
    /** 票过期多久自动归档（status 0 → 1 已用）：到达时刻（没有就用出发时刻）之后 3 小时。 */
    public static final long TICKET_ARCHIVE_AFTER_MS = 3L * 3600000L;

    /** 一条到点提醒。 */
    public static final class Item {
        public final long at;
        public final String table;
        public final long id;
        public final String title;
        public final String text;
        /** 这次提醒对应的那次发生的时刻（日程重复用；去重键的一部分）。 */
        public final long occ;
        /** [borrow-1112] 强提醒的待办：到点响铃 + 整屏，而不是只发通知。 */
        public final boolean strong;

        Item(long at, String table, long id, String title, String text, long occ) {
            this(at, table, id, title, text, occ, false);
        }

        Item(long at, String table, long id, String title, String text, long occ, boolean strong) {
            this.at = at; this.table = table; this.id = id; this.title = title; this.text = text; this.occ = occ; this.strong = strong;
        }

        /** 去重键：表:id:发生时刻:提醒时刻。 */
        public String key() { return table + ":" + id + ":" + occ + ":" + at; }

        /** 通知 id（同一条数据同一次发生只占一个通知位）。 */
        public int notifyId() { return Reminders.notifyId(table, id); }

        @Override public String toString() { return key() + "@" + at + " " + title; }
    }

    /** 提醒计算的输入（Android 层从库和设置里读好再传进来）。 */
    public static final class Input {
        public List<Row> events = new ArrayList<Row>(), todos = new ArrayList<Row>(), tickets = new ArrayList<Row>(), pickups = new ArrayList<Row>();
        public int defaultEventLeadMin = DEFAULT_EVENT_LEAD_MIN;
        public int[] ticketLeadsMin = DEFAULT_TICKET_LEADS;
    }

    /** 一张票要提醒的档位（分钟，去重、降序）：设置档位 + 票自己的非默认提前量；票 remind_lead_min=0 → 空。 */
    public static List<Integer> ticketLeads(Row r, int[] settingLeads) {
        List<Integer> l = new ArrayList<Integer>();
        long own = r.l("remind_lead_min", 120);
        if (own == 0) return l;
        if (settingLeads != null) for (int m : settingLeads) if (m > 0 && !l.contains(m)) l.add(m);
        if (own > 0 && own != 120 && !l.contains((int) own)) l.add((int) own);
        Collections.sort(l, Collections.reverseOrder());
        return l;
    }

    /** 通知 id：同一条数据只占一个通知位（撤销已弹出的通知也按它算）。 */
    public static int notifyId(String table, long id) { return (table + ":" + id).hashCode() & 0x7fffffff; }

    /** 删除这张表的一行时要不要撤销它已弹出的通知（四类有提醒的表都要）。 */
    public static boolean dismissOnDelete(String table) {
        return Contract.T_EVENTS.equals(table) || Contract.T_TODOS.equals(table)
                || Contract.T_TICKETS.equals(table) || Contract.T_PICKUPS.equals(table);
    }

    /**
     * 一次更新后这条数据已弹出的通知是否该撤销：日程软删（deleted_at 0→非 0）、待办完成（done_at 0→非 0）、
     * 票失效/归档（status 0→非 0）、取件码已取（taken_at 0→非 0）；另外关掉提醒（remind→0、票 remind_lead_min→0）也撤。
     * before 为更新前整行，after 为更新后整行（或 before+改动）。
     */
    public static boolean dismissOnUpdate(String table, Row before, Row after) {
        if (before == null || after == null) return false;
        if (Contract.T_EVENTS.equals(table)) return turnedOn(before, after, "deleted_at");
        if (Contract.T_TODOS.equals(table)) return turnedOn(before, after, "done_at") || turnedOff(before, after, "remind");
        if (Contract.T_TICKETS.equals(table)) return turnedOn(before, after, "status") || (before.l("remind_lead_min", 120) != 0 && after.l("remind_lead_min", 120) == 0);
        if (Contract.T_PICKUPS.equals(table)) return turnedOn(before, after, "taken_at") || turnedOff(before, after, "remind");
        return false;
    }

    static boolean turnedOn(Row b, Row a, String k) { return b.l(k) == 0 && a.l(k) != 0; }

    static boolean turnedOff(Row b, Row a, String k) { return b.l(k, 1) != 0 && a.l(k, 1) == 0; }

    /** 「2,30」「120, 30」→ {120,30}；坏值丢掉，全坏回默认；空串 = 不提醒票。 */
    public static int[] parseLeads(String s) {
        if (s == null) return DEFAULT_TICKET_LEADS;
        List<Integer> l = new ArrayList<Integer>();
        for (String p : s.split("[,，\\s]+")) {
            if (p.length() == 0) continue;
            try {
                int m = Integer.parseInt(p);
                if (m > 0 && m <= 7 * 1440 && !l.contains(m)) l.add(m);
            } catch (NumberFormatException e) { /* 跳过 */ }
        }
        if (l.isEmpty()) return s.trim().length() == 0 ? new int[0] : DEFAULT_TICKET_LEADS;
        Collections.sort(l, Collections.reverseOrder());
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** 「还有 2 小时」「还有 30 分钟」。 */
    public static String leadText(int min) {
        if (min >= 1440 && min % 1440 == 0) return "还有 " + (min / 1440) + " 天";
        if (min >= 60 && min % 60 == 0) return "还有 " + (min / 60) + " 小时";
        if (min > 60) return "还有 " + (min / 60) + " 小时 " + (min % 60) + " 分钟";
        return "还有 " + min + " 分钟";
    }

    /** 该自动归档的票：有效、到达（没有就出发）+ {@link #TICKET_ARCHIVE_AFTER_MS} 已过。 */
    public static List<Long> ticketsToArchive(List<Row> tickets, long now) {
        List<Long> l = new ArrayList<Long>();
        for (Row r : tickets) {
            if (r.l("status") != 0) continue;
            long end = r.l("arrive_at") > r.l("depart_at") ? r.l("arrive_at") : r.l("depart_at");
            if (end > 0 && end + TICKET_ARCHIVE_AFTER_MS < now) l.add(r.id());
        }
        return l;
    }

    /** 在 (from, to] 内要提醒的全部条目，按时刻排序。 */
    public static List<Item> between(Input in, long from, long to, TimeZone tz) {
        List<Item> out = new ArrayList<Item>();
        for (Row r : in.events) {
            SchedEvent e = r.toEvent();
            long lead = e.leadMin < 0 ? in.defaultEventLeadMin : e.leadMin;
            // 往后多看一天以容纳提前量
            for (SchedRecur.Occ o : SchedRecur.expand(e, from, to + (lead + 1) * 60000L + 24L * 3600000L, tz)) {
                long at = e.allDay ? CnTime.dayStart(o.start, tz) + 8L * 3600000L : o.start - lead * 60000L;
                if (at > from && at <= to) out.add(new Item(at, Contract.T_EVENTS, e.id, e.title, Fmt.eventLine(e, o.start, tz), o.start));
            }
        }
        for (Row r : in.todos) {
            if (r.l("remind", 1) == 0 || r.l("done_at") != 0 || r.l("due_at") == 0) continue;
            long at = r.l("due_at");
            if (at > from && at <= to) out.add(new Item(at, Contract.T_TODOS, r.id(), r.s("title"), "待办到点了", at, StrongAlert.isStrong(r)));
        }
        for (Row r : in.tickets) {
            if (r.l("status") != 0) continue;
            long dep = r.l("depart_at");
            for (int lead : ticketLeads(r, in.ticketLeadsMin)) {
                long at = dep - lead * 60000L;
                if (at > from && at <= to) out.add(new Item(at, Contract.T_TICKETS, r.id(), Fmt.ticketTitle(r), Fmt.ticketRemind(r, lead, tz), dep));
            }
        }
        for (Row r : in.pickups) {
            if (r.l("remind", 1) == 0 || r.l("taken_at") != 0 || r.l("deadline_at") == 0) continue;
            long dl = r.l("deadline_at");
            long at = Math.max(dl - PICKUP_LEAD_MS, r.l("created_at") + 60000L);
            if (at > dl) at = dl;
            if (at > from && at <= to) out.add(new Item(at, Contract.T_PICKUPS, r.id(), r.s("kind") + " " + r.s("code"),
                    (r.s("station").length() > 0 ? r.s("station") + " · " : "") + "快到期了，记得去取", dl));
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.at < b.at ? -1 : a.at > b.at ? 1 : a.key().compareTo(b.key());
            }
        });
        return out;
    }

    /** 下一个要排的闹钟时刻（&gt; now；最多看 400 天），没有返回 -1。 */
    public static long nextAt(Input in, long now, TimeZone tz) {
        long[] spans = {2L * 86400000L, 40L * 86400000L, 400L * 86400000L};
        for (long span : spans) {
            List<Item> l = between(in, now, now + span, tz);
            if (!l.isEmpty()) return l.get(0).at;
        }
        return -1L;
    }

    /** 到点检查：上次检查到现在之间该发的（过期太久的丢掉）。 */
    public static List<Item> due(Input in, long lastCheck, long now, TimeZone tz) {
        long from = Math.max(lastCheck, now - CATCHUP_MS);
        List<Item> all = between(in, from, now, tz);
        // 同一条数据一次到点只发最新那档（票的「2 小时」补发档和「30 分钟」档同时到期时只留后者；通知位本来也只有一个）
        java.util.Map<String, Item> last = new java.util.LinkedHashMap<String, Item>();
        for (Item it : all) last.put(it.table + ":" + it.id, it);
        return new ArrayList<Item>(last.values());
    }
}
