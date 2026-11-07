package com.onesay.plan.core;

import com.onesay.plan.core.recur.CnTime;
import com.onesay.plan.core.recur.S3Schedule;
import com.onesay.plan.core.recur.SchedRecur;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * 「今天」聚合（首页 + 后门 /today，纯 Java）。每条是一行：kind（event/pickup/ticket/todo/auto）、ref_id、at、title、sub。
 * 规则（按常识）：
 * <ul>
 *   <li>日程：今天的全部发生（含已过去的，标 past），全天的排最前；</li>
 *   <li>待取件：所有没取的（码不过期就一直挂着，过截止的也挂着、标逾期）；</li>
 *   <li>临近的票：出发在 [现在 − 2 小时, 现在 + 3 天) 的有效票；</li>
 *   <li>待办：没完成的，今天到期 / 已逾期的排前面，再是没日期的，以后到期的不上首页（只显示最近 5 条无日期）；</li>
 *   <li>自动任务：今天还会跑的定时任务（next_at 在今天）。</li>
 * </ul>
 */
public final class Today {
    private Today() {}

    public static final String[] COLUMNS = {"kind", "ref_id", "at", "title", "sub", "flag"};
    public static final long TICKET_AHEAD_MS = 3L * 86400000L, TICKET_BEHIND_MS = 2L * 3600000L;
    public static final int MAX_UNDATED_TODOS = 5;

    /** 「今天」的一行，列顺序同 {@link #COLUMNS}。 */
    public static final class Line {
        public final String kind;
        public final long refId, at;
        public final String title, sub;
        /** past / overdue / soon / ""。 */
        public final String flag;

        Line(String kind, long refId, long at, String title, String sub, String flag) {
            this.kind = kind; this.refId = refId; this.at = at; this.title = title; this.sub = sub; this.flag = flag;
        }

        /** 按 {@link #COLUMNS} 顺序给 MatrixCursor 用。 */
        public Object[] toArray() { return new Object[]{kind, refId, at, title, sub, flag}; }
    }

    /** 按类别分好的「今天」内容。 */
    public static final class Data {
        public final List<Line> events = new ArrayList<Line>(), pickups = new ArrayList<Line>(), tickets = new ArrayList<Line>(),
                todos = new ArrayList<Line>(), autos = new ArrayList<Line>();

        /** 全部行：日程、取件、票、待办、自动任务。 */
        public List<Line> all() {
            List<Line> l = new ArrayList<Line>();
            l.addAll(events); l.addAll(pickups); l.addAll(tickets); l.addAll(todos); l.addAll(autos);
            return l;
        }

        /** 今天什么都没有。 */
        public boolean empty() { return all().isEmpty(); }
    }

    /** 从各表行（已按「有效」过滤）聚合出今天的内容。 */
    public static Data build(List<Row> events, List<Row> todos, List<Row> tickets, List<Row> pickups, List<Row> autos, long now, TimeZone tz) {
        Data d = new Data();
        long d0 = CnTime.dayStart(now, tz), d1 = CnTime.addDays(d0, 1, tz);
        List<com.onesay.plan.core.recur.SchedEvent> evs = new ArrayList<com.onesay.plan.core.recur.SchedEvent>();
        for (Row r : events) evs.add(r.toEvent());
        for (SchedRecur.Occ o : SchedRecur.expandAll(evs, d0, d1, tz)) {
            boolean past = o.end <= now;
            d.events.add(new Line("event", o.event.id, o.start, o.event.title, Fmt.eventLine(o.event, o.start, tz), past ? "past" : ""));
        }
        for (Row r : pickups) {
            if (r.l("taken_at") != 0) continue;
            long dl = r.l("deadline_at");
            String sub = (r.s("station").length() > 0 ? r.s("station") : "") + (dl > 0 ? (r.s("station").length() > 0 ? " · " : "") + Fmt.when(dl, now, tz) + " 前" : "");
            d.pickups.add(new Line("pickup", r.id(), dl > 0 ? dl : r.l("created_at"), r.s("kind") + " " + r.s("code"), sub,
                    dl > 0 && dl < now ? "overdue" : ""));
        }
        sortByAt(d.pickups);
        for (Row r : tickets) {
            if (r.l("status") != 0) continue;
            long dep = r.l("depart_at");
            if (dep < now - TICKET_BEHIND_MS || dep >= now + TICKET_AHEAD_MS) continue;
            d.tickets.add(new Line("ticket", r.id(), dep, Fmt.ticketTitle(r), Fmt.day(dep, now, tz) + " " + Fmt.ticketLine(r, tz),
                    dep - now < 3L * 3600000L ? "soon" : ""));
        }
        sortByAt(d.tickets);
        List<Line> dated = new ArrayList<Line>(), undated = new ArrayList<Line>();
        for (Row r : todos) {
            if (r.l("done_at") != 0) continue;
            long due = r.l("due_at");
            if (due == 0) undated.add(new Line("todo", r.id(), r.l("created_at"), r.s("title"), "", ""));
            else if (due < d1) dated.add(new Line("todo", r.id(), due, r.s("title"), Fmt.when(due, now, tz), due < now ? "overdue" : ""));
        }
        sortByAt(dated);
        d.todos.addAll(dated);
        for (int i = 0; i < undated.size() && i < MAX_UNDATED_TODOS; i++) d.todos.add(undated.get(i));
        for (Row r : autos) {
            if (!Contract.MODE_TIME.equals(r.s("mode")) || r.l("enabled", 1) == 0) continue;
            long next = r.l("next_at", -1);
            if (next <= 0) next = S3Schedule.next((int) r.l("repeat"), (int) r.l("hour"), (int) r.l("minute"), (int) r.l("week_mask"), r.l("once_at"), now, tz);
            if (next >= now && next < d1) d.autos.add(new Line("auto", r.id(), next, r.s("title"), Fmt.hm(next, tz) + " · " + Fmt.autoAction(r), ""));
        }
        sortByAt(d.autos);
        return d;
    }

    static void sortByAt(List<Line> l) {
        Collections.sort(l, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) {
                return a.at < b.at ? -1 : a.at > b.at ? 1 : 0;
            }
        });
    }

    /** 一句话总结（通知 / 后门 ping 用）：「今天 2 个日程 · 1 个待取件 · 3 件待办」。 */
    public static String summary(Data d) {
        List<String> p = new ArrayList<String>();
        int ev = 0;
        for (Line l : d.events) if (!"past".equals(l.flag)) ev++;
        if (ev > 0) p.add(ev + " 个日程");
        if (!d.pickups.isEmpty()) p.add(d.pickups.size() + " 个待取件");
        if (!d.tickets.isEmpty()) p.add(d.tickets.size() + " 张临近的票");
        if (!d.todos.isEmpty()) p.add(d.todos.size() + " 件待办");
        if (p.isEmpty()) return "今天没什么安排";
        StringBuilder sb = new StringBuilder("今天 ");
        for (int i = 0; i < p.size(); i++) sb.append(i > 0 ? " · " : "").append(p.get(i));
        return sb.toString();
    }
}
