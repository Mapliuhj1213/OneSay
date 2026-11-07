package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 本地提醒排程：什么时候弹、弹几次、过期多久就不补了。 */
public class RemindersTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar k = Calendar.getInstance(TZ);
        k.clear();
        k.set(y, mo - 1, d, h, mi, 0);
        return k.getTimeInMillis();
    }

    static final long NOW = at(2026, 11, 5, 11, 30);   // 周四

    static Row ev(long id, String t, long start) {
        return new Row().with("_id", id).with("title", t).with("start_at", start);
    }

    @Test public void eventDefaultLead() {
        Reminders.Input in = new Reminders.Input();
        in.events.add(ev(1, "开会", at(2026, 11, 5, 15, 0)));
        assertEquals(at(2026, 11, 5, 14, 50), Reminders.nextAt(in, NOW, TZ));
        in.defaultEventLeadMin = 30;
        assertEquals(at(2026, 11, 5, 14, 30), Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void eventOwnLeadAndOnTime() {
        Reminders.Input in = new Reminders.Input();
        in.events.add(ev(1, "开会", at(2026, 11, 5, 15, 0)).with("lead_min", 0));
        assertEquals(at(2026, 11, 5, 15, 0), Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void allDayAtEight() {
        Reminders.Input in = new Reminders.Input();
        in.events.add(ev(1, "妈妈生日", at(2026, 11, 6, 0, 0)).with("all_day", 1));
        assertEquals(at(2026, 11, 6, 8, 0), Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void weeklyRepeatNextOccurrence() {
        Reminders.Input in = new Reminders.Input();
        // 每周一 9 点例会（bit1 = 周一），从 10 月起
        in.events.add(ev(1, "例会", at(2026, 10, 5, 9, 0)).with("repeat", 2).with("week_mask", 2).with("lead_min", 0));
        assertEquals(at(2026, 11, 9, 9, 0), Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void pastEventNotScheduled() {
        Reminders.Input in = new Reminders.Input();
        in.events.add(ev(1, "早会", at(2026, 11, 5, 9, 0)));
        assertEquals(-1L, Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void todoOnlyWithDueRemindAndOpen() {
        Reminders.Input in = new Reminders.Input();
        in.todos.add(new Row().with("_id", 1L).with("title", "交水费").with("due_at", at(2026, 11, 5, 18, 0)));
        in.todos.add(new Row().with("_id", 2L).with("title", "没截止").with("due_at", 0));
        in.todos.add(new Row().with("_id", 3L).with("title", "做完了").with("due_at", at(2026, 11, 5, 12, 0)).with("done_at", NOW));
        in.todos.add(new Row().with("_id", 4L).with("title", "不提醒").with("due_at", at(2026, 11, 5, 12, 0)).with("remind", 0));
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 86400000L, TZ);
        assertEquals(1, l.size());
        assertEquals("交水费", l.get(0).title);
    }

    @Test public void ticketTwoHoursBefore() {
        Reminders.Input in = new Reminders.Input();
        in.tickets.add(new Row().with("_id", 1L).with("kind", "train").with("title", "G1").with("number", "G1").with("from_place", "上海")
                .with("to_place", "北京").with("depart_at", at(2026, 11, 6, 9, 0)));
        in.tickets.add(new Row().with("_id", 2L).with("kind", "train").with("title", "用过").with("depart_at", at(2026, 11, 6, 10, 0)).with("status", 1));
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 2 * 86400000L, TZ);
        assertEquals(2, l.size());   // 默认两档：提前 2 小时 + 30 分钟；已用的票不提醒
        assertEquals(at(2026, 11, 6, 7, 0), l.get(0).at);
        assertEquals(at(2026, 11, 6, 8, 30), l.get(1).at);
        assertTrue(l.get(0).title.contains("上海 → 北京"));
        assertTrue(l.get(0).text.startsWith("还有 2 小时出发"));
        assertTrue(l.get(1).text.startsWith("还有 30 分钟出发"));
        assertNotEquals(l.get(0).key(), l.get(1).key());
    }

    static Row movie(long id, long dep) {
        return new Row().with("_id", id).with("kind", "movie").with("title", "测试影片").with("venue", "测试影城 3 号厅")
                .with("seat", "7排8座").with("order_no", "TEST1234").with("depart_at", dep);
    }

    @Test public void movieTicketTextHasCodeAndOpening() {
        Reminders.Input in = new Reminders.Input();
        in.tickets.add(movie(1, at(2026, 11, 5, 19, 30)));
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 86400000L, TZ);
        assertEquals(2, l.size());
        assertEquals(at(2026, 11, 5, 17, 30), l.get(0).at);
        assertEquals("还有 30 分钟开场 · 19:30 · 测试影城 3 号厅 · 7排8座 · 取票码 TEST1234", l.get(1).text);
    }

    @Test public void ticketLeadsFromSettingsAndOwnAndOff() {
        Reminders.Input in = new Reminders.Input();
        in.ticketLeadsMin = Reminders.parseLeads("1440, 60，x");
        in.tickets.add(movie(1, at(2026, 11, 7, 19, 30)).with("remind_lead_min", 15));
        in.tickets.add(movie(2, at(2026, 11, 7, 20, 0)).with("remind_lead_min", 0));   // 这张不提醒
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 5 * 86400000L, TZ);
        assertEquals(3, l.size());
        assertEquals(at(2026, 11, 6, 19, 30), l.get(0).at);
        assertEquals(at(2026, 11, 7, 18, 30), l.get(1).at);
        assertEquals(at(2026, 11, 7, 19, 15), l.get(2).at);
        assertTrue(l.get(0).text.startsWith("还有 1 天开场"));
    }

    @Test public void parseLeadsEdge() {
        assertArrayEquals(new int[]{120, 30}, Reminders.parseLeads(null));
        assertArrayEquals(new int[]{120, 30}, Reminders.parseLeads("30,120,30"));
        assertArrayEquals(new int[0], Reminders.parseLeads(""));
        assertArrayEquals(new int[]{120, 30}, Reminders.parseLeads("abc"));
        assertEquals("还有 1 小时 30 分钟", Reminders.leadText(90));
    }

    @Test public void expiredTicketNotRemindedAndArchived() {
        Reminders.Input in = new Reminders.Input();
        in.tickets.add(movie(1, NOW - 3600000L));        // 一小时前开场
        in.tickets.add(movie(2, NOW + 10 * 60000L));      // 10 分钟后开场：2 小时档已过，只剩 30 分钟档也已过 → 不补发旧档
        assertEquals(-1L, Reminders.nextAt(in, NOW, TZ));
        // 30 分钟档在 20 分钟前，due() 在追补窗口内会补发一次
        assertEquals(1, Reminders.due(in, NOW - 25 * 60000L, NOW, TZ).size());
        // 两档同时落在追补窗口里（2h 档 110 分钟前不在 60 分钟窗口；换成自定义 15 分钟档）→ 只发最新一档
        Reminders.Input in2 = new Reminders.Input();
        in2.tickets.add(movie(5, NOW + 5 * 60000L).with("remind_lead_min", 15));
        List<Reminders.Item> d = Reminders.due(in2, NOW - 50 * 60000L, NOW, TZ);
        assertEquals(1, d.size());
        assertEquals(NOW - 10 * 60000L, d.get(0).at);
        java.util.List<Row> t = new java.util.ArrayList<Row>();
        t.add(movie(1, NOW - 4 * 3600000L));
        t.add(movie(2, NOW - 2 * 3600000L));
        t.add(movie(3, NOW - 5 * 3600000L).with("arrive_at", NOW - 1 * 3600000L));
        t.add(movie(4, NOW - 9 * 3600000L).with("status", 2));
        assertEquals(java.util.Arrays.asList(1L), Reminders.ticketsToArchive(t, NOW));
    }

    @Test public void pickupBeforeDeadlineNotBeforeCreated() {
        Reminders.Input in = new Reminders.Input();
        in.pickups.add(new Row().with("_id", 1L).with("code", "5-2-1234").with("kind", "取件码").with("deadline_at", at(2026, 11, 5, 20, 0))
                .with("created_at", NOW));
        // 截止前 2 小时
        assertEquals(at(2026, 11, 5, 18, 0), Reminders.nextAt(in, NOW, TZ));
        in.pickups.clear();
        // 截止只剩 1 小时：创建 1 分钟后就提醒（不会排到过去）
        in.pickups.add(new Row().with("_id", 2L).with("code", "8888").with("kind", "取件码").with("deadline_at", NOW + 3600000L).with("created_at", NOW));
        assertEquals(NOW + 60000L, Reminders.nextAt(in, NOW, TZ));
    }

    @Test public void dueWindowCatchupOneHourOnly() {
        Reminders.Input in = new Reminders.Input();
        in.todos.add(new Row().with("_id", 1L).with("title", "很久以前").with("due_at", NOW - 3 * 3600000L));
        in.todos.add(new Row().with("_id", 2L).with("title", "刚才").with("due_at", NOW - 20 * 60000L));
        List<Reminders.Item> d = Reminders.due(in, NOW - 24 * 3600000L, NOW, TZ);
        assertEquals(1, d.size());
        assertEquals("刚才", d.get(0).title);
        // 已经检查过的不再发
        assertTrue(Reminders.due(in, NOW - 10 * 60000L, NOW, TZ).isEmpty());
    }

    @Test public void notifyIdStablePerRow() {
        Reminders.Input in = new Reminders.Input();
        in.events.add(ev(7, "每天", at(2026, 11, 1, 20, 0)).with("repeat", 1).with("lead_min", 0));
        List<Reminders.Item> l = Reminders.between(in, NOW, NOW + 3 * 86400000L, TZ);
        assertEquals(3, l.size());
        assertEquals(l.get(0).notifyId(), l.get(1).notifyId());
        assertNotEquals(l.get(0).key(), l.get(1).key());
    }

    @Test public void nothingToSchedule() {
        assertEquals(-1L, Reminders.nextAt(new Reminders.Input(), NOW, TZ));
    }
}
