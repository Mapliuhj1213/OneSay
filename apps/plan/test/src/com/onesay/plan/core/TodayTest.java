package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** 首页「今天」聚合按常识取舍。 */
public class TodayTest {

    static final TimeZone TZ = RemindersTest.TZ;
    static final long NOW = RemindersTest.NOW;

    static long at(int d, int h, int m) { return RemindersTest.at(2026, 11, d, h, m); }

    static List<Row> l(Row... r) {
        List<Row> x = new ArrayList<Row>();
        for (Row a : r) x.add(a);
        return x;
    }

    @Test public void eventsTodayOnlyPastFlagged() {
        Today.Data d = Today.build(l(new Row().with("_id", 1L).with("title", "早会").with("start_at", at(5, 9, 0)),
                new Row().with("_id", 2L).with("title", "下午会").with("start_at", at(5, 15, 0)),
                new Row().with("_id", 3L).with("title", "明天").with("start_at", at(6, 9, 0))), l(), l(), l(), l(), NOW, TZ);
        assertEquals(2, d.events.size());
        assertEquals("past", d.events.get(0).flag);
        assertEquals("", d.events.get(1).flag);
        assertEquals("今天 1 个日程", Today.summary(d));
    }

    @Test public void ticketsWithinThreeDays() {
        Today.Data d = Today.build(l(), l(), l(new Row().with("_id", 1L).with("kind", "train").with("title", "近").with("depart_at", at(6, 9, 0)),
                new Row().with("_id", 2L).with("kind", "train").with("title", "远").with("depart_at", at(20, 9, 0)),
                new Row().with("_id", 3L).with("kind", "train").with("title", "刚开走").with("depart_at", NOW - 3600000L),
                new Row().with("_id", 4L).with("kind", "train").with("title", "早过了").with("depart_at", NOW - 5 * 3600000L)), l(), l(), NOW, TZ);
        assertEquals(2, d.tickets.size());
        assertEquals("刚开走", d.tickets.get(0).title);
        assertEquals("soon", d.tickets.get(0).flag);
    }

    @Test public void todosOverdueFirstFutureHidden() {
        Today.Data d = Today.build(l(), l(new Row().with("_id", 1L).with("title", "无期").with("created_at", NOW - 1000),
                new Row().with("_id", 2L).with("title", "今晚").with("due_at", at(5, 20, 0)),
                new Row().with("_id", 3L).with("title", "昨天的").with("due_at", at(4, 20, 0)),
                new Row().with("_id", 4L).with("title", "下周").with("due_at", at(12, 20, 0)),
                new Row().with("_id", 5L).with("title", "做完").with("done_at", NOW)), l(), l(), l(), NOW, TZ);
        assertEquals(3, d.todos.size());
        assertEquals("昨天的", d.todos.get(0).title);
        assertEquals("overdue", d.todos.get(0).flag);
        assertEquals("今晚", d.todos.get(1).title);
        assertEquals("无期", d.todos.get(2).title);
    }

    @Test public void undatedTodosCapped() {
        List<Row> t = new ArrayList<Row>();
        for (int i = 0; i < 9; i++) t.add(new Row().with("_id", (long) i + 1).with("title", "t" + i).with("created_at", NOW - i));
        assertEquals(Today.MAX_UNDATED_TODOS, Today.build(l(), t, l(), l(), l(), NOW, TZ).todos.size());
    }

    @Test public void pickupsUntakenOnly() {
        Today.Data d = Today.build(l(), l(), l(), l(new Row().with("_id", 1L).with("code", "1234").with("kind", "取件码").with("station", "菜鸟驿站"),
                new Row().with("_id", 2L).with("code", "5678").with("kind", "取件码").with("taken_at", NOW)), l(), NOW, TZ);
        assertEquals(1, d.pickups.size());
        assertEquals("取件码 1234", d.pickups.get(0).title);
        assertTrue(Today.summary(d).contains("1 个待取件"));
    }

    @Test public void timeAutomationsLaterToday() {
        Today.Data d = Today.build(l(), l(), l(), l(), l(
                new Row().with("_id", 1L).with("title", "晚上吃药").with("mode", "time").with("repeat", 1).with("hour", 21).with("minute", 0).with("action_type", "remind"),
                new Row().with("_id", 2L).with("title", "早上").with("mode", "time").with("repeat", 1).with("hour", 8).with("minute", 0).with("action_type", "remind"),
                new Row().with("_id", 3L).with("title", "关了").with("mode", "time").with("repeat", 1).with("hour", 22).with("enabled", 0),
                new Row().with("_id", 4L).with("title", "触发").with("mode", "trigger").with("trigger_type", "wifi")), NOW, TZ);
        assertEquals(1, d.autos.size());
        assertEquals("晚上吃药", d.autos.get(0).title);
    }

    @Test public void emptyDay() {
        Today.Data d = Today.build(l(), l(), l(), l(), l(), NOW, TZ);
        assertTrue(d.empty());
        assertEquals("今天没什么安排", Today.summary(d));
    }
}
