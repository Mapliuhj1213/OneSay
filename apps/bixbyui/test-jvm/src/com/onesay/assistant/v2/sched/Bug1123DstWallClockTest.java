package com.onesay.assistant.v2.sched;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ fix/1123-bug1 夏令时建日程钟点漂移（adv-1114 P2-9 ①）：在有夏令时的时区，重复日程的第一次正好落在「跳过的那一小时」里
 * （例：洛杉矶 2026-03-08 凌晨 2:30 不存在，当天只能是 3:30），以前以后每天都变成 3:30。
 * 修法：日程另记「用户说的钟点」（{@link SchedEvent#wallMin}），展开时按它算；它和 startAt 对不上（后来改过时间）就照旧按 startAt 的钟点。
 * 中国时区没有夏令时，结果不变。
 */
public class Bug1123DstWallClockTest {

    static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    static final TimeZone TZ_LA = TimeZone.getTimeZone(LA);
    private TimeZone saved;

    @Before public void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ_LA);
    }

    @After public void tearDown() {
        TimeZone.setDefault(saved);
    }

    static long at(ZoneId z, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi), z).toInstant().toEpochMilli();
    }

    static LocalTime clock(long t, ZoneId z) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(t), z).toLocalTime();
    }

    static LocalTime occOn(SchedEvent e, ZoneId z, int y, int mo, int d) {
        long from = at(z, y, mo, d, 0, 0);
        long to = ZonedDateTime.of(LocalDate.of(y, mo, d).plusDays(1), LocalTime.MIDNIGHT, z).toInstant().toEpochMilli();
        List<SchedRecur.Occ> l = SchedRecur.expand(e, from, to, TimeZone.getTimeZone(z));
        assertEquals(y + "-" + mo + "-" + d + " 应该有 1 次", 1, l.size());
        return clock(l.get(0).start, z);
    }

    /** 复现原题：startAt 是那天被挪后的 3:30，带上用户说的 2:30 以后每天回到 2:30。 */
    @Test public void recurKeepsSaidClockAfterGapDay() {
        SchedEvent e = new SchedEvent();
        e.title = "x";
        e.repeat = SchedEvent.REPEAT_DAILY;
        e.startAt = ZonedDateTime.of(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), LA).toInstant().toEpochMilli();
        assertEquals("那天 2:30 不存在，存进去就是 3:30", LocalTime.of(3, 30), clock(e.startAt, LA));
        e.wallMin = 2 * 60 + 30;
        assertEquals("跳过那天本身只能是 3:30", LocalTime.of(3, 30), occOn(e, LA, 2026, 3, 8));
        assertEquals(LocalTime.of(2, 30), occOn(e, LA, 2026, 3, 9));
        assertEquals(LocalTime.of(2, 30), occOn(e, LA, 2026, 3, 20));
        assertEquals("秋天切回来以后也是 2:30", LocalTime.of(2, 30), occOn(e, LA, 2026, 11, 10));
        assertEquals("nextStart 同口径", LocalTime.of(2, 30), clock(SchedRecur.nextStart(e, at(LA, 2026, 3, 9, 0, 0), TZ_LA), LA));
    }

    /** 没记钟点的老数据、或后来改过时间（钟点和 startAt 对不上）：照旧按 startAt 的钟点，不乱跳。 */
    @Test public void staleOrMissingWallClockFallsBack() {
        SchedEvent e = new SchedEvent();
        e.title = "x";
        e.repeat = SchedEvent.REPEAT_DAILY;
        e.startAt = at(LA, 2026, 3, 10, 9, 0);
        assertEquals(-1, e.wallMin);
        assertEquals(LocalTime.of(9, 0), occOn(e, LA, 2026, 3, 12));
        e.wallMin = 2 * 60 + 30;   // 记的是 2:30，可 startAt 后来改成了 9:00
        assertEquals(LocalTime.of(9, 0), occOn(e, LA, 2026, 3, 12));
        assertEquals("copy 带上钟点", e.wallMin, e.copy().wallMin);
    }

    /** 全链路：说「每天凌晨2点半吃药」，第一次落在跳过的那天。 */
    @Test public void engineCreateOnGapDay() {
        SchedStore.Memory store = new SchedStore.Memory();
        SchedEngine eng = new SchedEngine(store, TZ_LA);
        long now = at(LA, 2026, 3, 8, 0, 30);   // 3 月 8 日凌晨 0:30（还没切）
        SchedEngine.Reply r = eng.handle("每天凌晨2点半吃药", now);
        assertNotNull(r);
        assertEquals(r.text, SchedEngine.Kind.CREATED, r.kind);
        List<SchedEvent> l = store.all(false);
        assertEquals(1, l.size());
        SchedEvent ev = l.get(0);
        assertEquals(SchedEvent.REPEAT_DAILY, ev.repeat);
        assertEquals(LocalTime.of(3, 30), occOn(ev, LA, 2026, 3, 8));
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 3, 9));
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 4, 1));
    }

    /** 前一天建、当天 2:30 已过 → 第一次顺延到跳过的那天，以后仍是 2:30。 */
    @Test public void engineCreateDayBeforeRollsOntoGapDay() {
        SchedStore.Memory store = new SchedStore.Memory();
        SchedEngine eng = new SchedEngine(store, TZ_LA);
        long now = at(LA, 2026, 3, 7, 10, 0);
        assertEquals(SchedEngine.Kind.CREATED, eng.handle("每天凌晨2点半吃药", now).kind);
        SchedEvent ev = store.all(false).get(0);
        assertEquals(LocalTime.of(3, 30), occOn(ev, LA, 2026, 3, 8));
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 3, 9));
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 3, 15));
    }

    /** 每周 / 工作日同理（周日 3 月 8 日就是切换日）。 */
    @Test public void weeklyOnGapDay() {
        SchedStore.Memory store = new SchedStore.Memory();
        SchedEngine eng = new SchedEngine(store, TZ_LA);
        long now = at(LA, 2026, 3, 8, 0, 30);
        assertEquals(SchedEngine.Kind.CREATED, eng.handle("每周日凌晨2点半开例会", now).kind);
        SchedEvent ev = store.all(false).get(0);
        assertEquals(SchedEvent.REPEAT_WEEKLY, ev.repeat);
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 3, 15));
        assertEquals(LocalTime.of(2, 30), occOn(ev, LA, 2026, 3, 22));
    }

    /** 中国时区：没有夏令时，结果和以前一样。 */
    @Test public void chinaUnchanged() {
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        TimeZone.setDefault(TimeZone.getTimeZone(sh));
        SchedStore.Memory store = new SchedStore.Memory();
        SchedEngine eng = new SchedEngine(store, TimeZone.getTimeZone(sh));
        long now = at(sh, 2026, 3, 8, 0, 30);
        assertEquals(SchedEngine.Kind.CREATED, eng.handle("每天凌晨2点半吃药", now).kind);
        SchedEvent ev = store.all(false).get(0);
        assertEquals(at(sh, 2026, 3, 8, 2, 30), ev.startAt);
        assertEquals(LocalTime.of(2, 30), occOn(ev, sh, 2026, 3, 9));
        assertEquals(LocalTime.of(2, 30), occOn(ev, sh, 2026, 11, 10));
    }

    /** 本地库：新列由迁移建出、读写都带上（老库升级后默认 -1 = 没记）。 */
    @Test public void storeColumnPresent() {
        assertTrue(SqliteSchedStore.COLS.contains("wall_min"));
        boolean found = false;
        for (SchedMigrations.Step s : SchedMigrations.steps()) for (String q : s.sql) if (q.contains("wall_min") && q.contains("DEFAULT -1")) found = true;
        assertTrue(found);
    }
}
