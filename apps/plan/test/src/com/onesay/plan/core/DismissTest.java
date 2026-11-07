package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** 删票 / 软删日程 / 完成待办 / 取件码已取 / 过期归档 → 撤销已弹出的临期通知（按表+id 的通知位）。 */
public class DismissTest {

    static Row r(Object... kv) {
        Row r = new Row();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static Row merged(Row before, Row change) {
        Row a = new Row(before);
        a.putAll(change);
        return a;
    }

    @Test public void notifyIdMatchesItem() {
        Reminders.Input in = new Reminders.Input();
        long dep = RemindersTest.NOW + 3L * 3600000L;
        in.tickets.add(r("_id", 7L, "kind", "movie", "title", "电影", "depart_at", dep, "status", 0L));
        java.util.List<Reminders.Item> l = Reminders.between(in, RemindersTest.NOW, dep, RemindersTest.TZ);
        assertFalse(l.isEmpty());
        for (Reminders.Item it : l) assertEquals(Reminders.notifyId(Contract.T_TICKETS, 7L), it.notifyId());
        assertNotEquals(Reminders.notifyId(Contract.T_TICKETS, 7L), Reminders.notifyId(Contract.T_TODOS, 7L));
        assertTrue(Reminders.notifyId(Contract.T_TICKETS, 7L) >= 0);
    }

    @Test public void deleteDismissesReminderTables() {
        assertTrue(Reminders.dismissOnDelete(Contract.T_TICKETS));
        assertTrue(Reminders.dismissOnDelete(Contract.T_EVENTS));
        assertTrue(Reminders.dismissOnDelete(Contract.T_TODOS));
        assertTrue(Reminders.dismissOnDelete(Contract.T_PICKUPS));
        assertFalse(Reminders.dismissOnDelete(Contract.T_AUTOMATIONS));
        assertFalse(Reminders.dismissOnDelete(Contract.T_RUNS));
    }

    @Test public void ticketArchivedOrVoided() {
        Row b = r("_id", 1L, "status", 0L, "remind_lead_min", 120L);
        assertTrue(Reminders.dismissOnUpdate(Contract.T_TICKETS, b, merged(b, r("status", 1L))));
        assertTrue(Reminders.dismissOnUpdate(Contract.T_TICKETS, b, merged(b, r("remind_lead_min", 0L))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_TICKETS, b, merged(b, r("seat", "5排6座"))));
        Row used = r("_id", 1L, "status", 1L);
        assertFalse("已归档的再改不重复撤", Reminders.dismissOnUpdate(Contract.T_TICKETS, used, merged(used, r("status", 2L))));
    }

    @Test public void eventSoftDelete() {
        Row b = r("_id", 2L, "deleted_at", 0L);
        assertTrue(Reminders.dismissOnUpdate(Contract.T_EVENTS, b, merged(b, r("deleted_at", 123L))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_EVENTS, b, merged(b, r("title", "改名"))));
        Row del = r("_id", 2L, "deleted_at", 123L);
        assertFalse("撤销软删不撤通知", Reminders.dismissOnUpdate(Contract.T_EVENTS, del, merged(del, r("deleted_at", 0L))));
    }

    @Test public void todoDoneOrRemindOff() {
        Row b = r("_id", 3L, "done_at", 0L, "remind", 1L);
        assertTrue(Reminders.dismissOnUpdate(Contract.T_TODOS, b, merged(b, r("done_at", 9L))));
        assertTrue(Reminders.dismissOnUpdate(Contract.T_TODOS, b, merged(b, r("remind", 0L))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_TODOS, b, merged(b, r("title", "x"))));
        Row done = r("_id", 3L, "done_at", 9L, "remind", 1L);
        assertFalse("取消完成不撤", Reminders.dismissOnUpdate(Contract.T_TODOS, done, merged(done, r("done_at", 0L))));
    }

    @Test public void pickupTaken() {
        Row b = r("_id", 4L, "taken_at", 0L, "remind", 1L);
        assertTrue(Reminders.dismissOnUpdate(Contract.T_PICKUPS, b, merged(b, r("taken_at", 5L))));
        assertTrue(Reminders.dismissOnUpdate(Contract.T_PICKUPS, b, merged(b, r("remind", 0L))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_PICKUPS, b, merged(b, r("station", "菜鸟驿站"))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_AUTOMATIONS, b, merged(b, r("enabled", 0L))));
        assertFalse(Reminders.dismissOnUpdate(Contract.T_PICKUPS, null, b));
    }

    @Test public void archivedTicketDropsOutOfSchedule() {
        long dep = RemindersTest.NOW + 3L * 3600000L;
        Reminders.Input in = new Reminders.Input();
        in.tickets.add(r("_id", 8L, "kind", "movie", "title", "电影", "depart_at", dep, "status", 1L));
        assertEquals("归档票不再排闹钟", -1L, Reminders.nextAt(in, RemindersTest.NOW, RemindersTest.TZ));
    }
}
