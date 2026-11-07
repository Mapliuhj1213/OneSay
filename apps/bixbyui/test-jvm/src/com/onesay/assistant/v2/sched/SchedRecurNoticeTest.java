package com.onesay.assistant.v2.sched;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** [sched] 重复展开、提醒生成、晨间播报、迁移框架、展示文字。现在 = 2026-10-24（周六）10:00。 */
public class SchedRecurNoticeTest {

    static final TimeZone TZ = CnTimeTest.TZ;
    static final long NOW = CnTimeTest.NOW;
    private TimeZone saved;

    @Before public void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
    }

    @After public void tearDown() {
        TimeZone.setDefault(saved);
    }

    static long at(int y, int mo, int d, int h, int mi) {
        return CnTimeTest.at(y, mo, d, h, mi);
    }

    static SchedEvent ev(String title, long start) {
        SchedEvent e = new SchedEvent();
        e.title = title;
        e.startAt = start;
        return e;
    }

    // ------------------------------------------------------------------ 重复

    @Test public void dailyAndWeekly() {
        SchedEvent d = ev("跑步", at(2026, 10, 24, 7, 0));
        d.repeat = SchedEvent.REPEAT_DAILY;
        assertEquals(at(2026, 10, 25, 7, 0), SchedRecur.nextStart(d, NOW, TZ));
        assertEquals(7, SchedRecur.expand(d, CnTimeTest.day(2026, 10, 24), CnTimeTest.day(2026, 10, 31), TZ).size());
        SchedEvent w = ev("例会", at(2026, 10, 26, 9, 0));
        w.repeat = SchedEvent.REPEAT_WEEKLY;
        w.weekMask = 1 << (Calendar.MONDAY - 1);
        List<SchedRecur.Occ> l = SchedRecur.expand(w, CnTimeTest.day(2026, 10, 1), CnTimeTest.day(2026, 11, 30), TZ);
        assertEquals("不早于起点：10-26、11-2、9、16、23", 5, l.size());
        assertEquals(at(2026, 10, 26, 9, 0), l.get(0).start);
        SchedEvent wm = ev("例会", at(2026, 10, 26, 9, 0));
        wm.repeat = SchedEvent.REPEAT_WEEKLY;   // mask 0 → 用起点那天的星期
        assertEquals(at(2026, 11, 2, 9, 0), SchedRecur.nextStart(wm, at(2026, 10, 27, 0, 0), TZ));
    }

    @Test public void workdayUsesHolidayTable() {
        SchedEvent w = ev("站会", at(2026, 9, 28, 9, 0));
        w.repeat = SchedEvent.REPEAT_WORKDAY;
        List<SchedRecur.Occ> l = SchedRecur.expand(w, CnTimeTest.day(2026, 9, 28), CnTimeTest.day(2026, 10, 12), TZ);
        List<Integer> days = new ArrayList<Integer>();
        for (SchedRecur.Occ o : l) {
            Calendar k = Calendar.getInstance(TZ);
            k.setTimeInMillis(o.start);
            days.add((k.get(Calendar.MONTH) + 1) * 100 + k.get(Calendar.DAY_OF_MONTH));
        }
        // 国庆 10/1–10/7 放假；10/10（周六）调休上班
        assertEquals(Arrays.asList(928, 929, 930, 1008, 1009, 1010), days);
    }

    @Test public void monthlyAndYearly() {
        SchedEvent m = ev("还信用卡", CnTimeTest.day(2026, 10, 31));
        m.allDay = true;
        m.repeat = SchedEvent.REPEAT_MONTHLY;
        m.monthDay = 31;
        assertEquals("11 月没有 31 号：跳到 12 月", CnTimeTest.day(2026, 12, 31), SchedRecur.nextStart(m, CnTimeTest.day(2026, 11, 1), TZ));
        m.monthDay = -1;
        assertEquals(CnTimeTest.day(2026, 11, 30), SchedRecur.nextStart(m, CnTimeTest.day(2026, 11, 1), TZ));
        assertEquals(CnTimeTest.day(2027, 2, 28), SchedRecur.nextStart(m, CnTimeTest.day(2027, 2, 1), TZ));
        SchedEvent y = ev("纪念日", CnTimeTest.day(2024, 2, 29));
        y.allDay = true;
        y.repeat = SchedEvent.REPEAT_YEARLY;
        assertEquals("2 月 29 日只在闰年", CnTimeTest.day(2028, 2, 29), SchedRecur.nextStart(y, NOW, TZ));
        SchedEvent lunar = ev("妈妈生日", CnTimeTest.day(2026, 1, 1));
        lunar.allDay = true;
        lunar.repeat = SchedEvent.REPEAT_YEARLY;
        lunar.lunar = true;
        lunar.lunarMonth = 8;
        lunar.lunarDay = 15;
        assertEquals(CnTimeTest.day(2027, 9, 15), SchedRecur.nextStart(lunar, NOW, TZ));
        assertEquals("每年农历八月十五", SchedRecur.describe(lunar));
    }

    @Test public void allDayStillTodayAndOneShotPast() {
        SchedEvent a = ev("交报告", CnTimeTest.day(2026, 10, 24));
        a.allDay = true;
        assertEquals("全天日程当天还没过完", CnTimeTest.day(2026, 10, 24), SchedRecur.nextStart(a, NOW, TZ));
        SchedEvent p = ev("早会", at(2026, 10, 24, 9, 0));
        assertEquals(-1L, SchedRecur.nextStart(p, NOW, TZ));
        assertTrue(SchedRecur.expand(p, NOW, NOW + 86400000L, TZ).isEmpty());
        p.deleted = true;
        assertTrue(SchedRecur.expand(p, 0, Long.MAX_VALUE / 2, TZ).isEmpty());
    }

    @Test public void describe() {
        SchedEvent w = ev("x", NOW);
        w.repeat = SchedEvent.REPEAT_WEEKLY;
        w.weekMask = (1 << (Calendar.MONDAY - 1)) | (1 << (Calendar.WEDNESDAY - 1)) | (1 << (Calendar.SUNDAY - 1));
        assertEquals("每周一三日", SchedRecur.describe(w));
        w.weekMask = (1 << 6) | 1;
        assertEquals("每个周末", SchedRecur.describe(w));
        w.repeat = SchedEvent.REPEAT_MONTHLY;
        w.monthDay = -1;
        assertEquals("每月最后一天", SchedRecur.describe(w));
    }

    // ------------------------------------------------------------------ 提醒

    SchedEngine engineWith(SchedEvent... evs) {
        SchedStore.Memory s = new SchedStore.Memory();
        for (SchedEvent x : evs) s.insert(x);
        return new SchedEngine(s, TZ);
    }

    @Test public void noticeBasics() {
        SchedEvent a = ev("开会", at(2026, 10, 24, 11, 0));
        a.endAt = at(2026, 10, 24, 12, 0);
        SchedEngine e = engineWith(a);
        List<IslandNotice> l = SchedNotices.plan(e, NOW);
        assertEquals(1, l.size());
        IslandNotice n = l.get(0);
        assertEquals(IslandNotice.Source.CALENDAR, n.source);
        assertEquals("sched", n.sourceTag);
        assertEquals("sched:" + a.id + ":" + a.startAt, n.dedupKey);
        assertEquals(a.startAt - 10 * 60000L, n.showAt);
        assertEquals(a.startAt + 15 * 60000L, n.expireAt);
        assertEquals("开会", n.title);
        assertTrue(n.summary, n.summary.startsWith("11:00–12:00"));
        assertEquals(IslandNotice.Priority.NORMAL, n.priority);
        assertEquals(NoticeAction.Kind.OPEN_PAGE, n.actions.get(0).kind);
        assertEquals(SchedNotices.PAGE_LIST, n.actions.get(0).arg);
        for (NoticeAction x : n.actions) assertFalse("提醒不能发邮件", x.kind == NoticeAction.Kind.OPEN_MAIL_CARD);
    }

    @Test public void noticeLeadPriorityAllDayAndSkips() {
        SchedEvent lead = ev("面试", at(2026, 10, 24, 14, 0));
        lead.leadMin = 60;
        SchedEvent none = ev("看电影", at(2026, 10, 24, 15, 0));
        none.leadMin = CnTime.LEAD_NONE;
        SchedEvent started = ev("早会", at(2026, 10, 24, 9, 50));
        SchedEvent allDay = ev("交报告", CnTimeTest.day(2026, 10, 25));
        allDay.allDay = true;
        SchedEvent far = ev("出差", at(2026, 10, 27, 9, 0));
        SchedEngine e = engineWith(lead, none, started, allDay, far);
        e.settings.defaultLead = 5;
        List<IslandNotice> l = SchedNotices.plan(e, NOW);
        assertEquals("面试 + 交报告（明天全天）；看电影不提醒、早会已开始、出差超出窗口：" + l, 2, l.size());
        IslandNotice m = l.get(0).title.equals("面试") ? l.get(0) : l.get(1);
        IslandNotice d = l.get(0).title.equals("面试") ? l.get(1) : l.get(0);
        assertEquals(IslandNotice.Priority.HIGH, m.priority);
        assertEquals(lead.startAt - 3600000L, m.showAt);
        assertEquals(CnTimeTest.day(2026, 10, 25) + SchedNotices.ALLDAY_HOUR * 3600000L, d.showAt);
    }

    @Test public void noticeRepeatingKeyPerOccurrence() {
        SchedEvent w = ev("吃药", at(2026, 10, 1, 21, 0));
        w.repeat = SchedEvent.REPEAT_DAILY;
        List<IslandNotice> l = SchedNotices.plan(engineWith(w), NOW);
        assertEquals("今晚 21:00（明晚 21:00 超出 26 小时窗口外？不：明晚 = 35 小时后）", 1, l.size());
        assertEquals("sched:" + w.id + ":" + at(2026, 10, 24, 21, 0), l.get(0).dedupKey);
    }

    // ------------------------------------------------------------------ 晨间播报

    @Test public void briefLines() {
        SchedEvent a = ev("晨会", at(2026, 10, 24, 9, 0));
        SchedEvent b = ev("开会", at(2026, 10, 24, 15, 0));
        SchedEvent c = ev("交报告", CnTimeTest.day(2026, 10, 24));
        c.allDay = true;
        SchedEvent tm = ev("明天的", at(2026, 10, 25, 9, 0));
        SchedEngine e = engineWith(a, b, c, tm);
        assertEquals(Arrays.asList("全天 交报告", "上午9点 晨会", "下午3点 开会"), SchedBrief.todayLines(e, at(2026, 10, 24, 7, 25)));
        assertEquals("已结束的不念", Arrays.asList("全天 交报告", "下午3点 开会"), SchedBrief.todayLines(e, at(2026, 10, 24, 10, 30)));
    }

    @Test public void dailyDigestWithEvents() {
        com.onesay.assistant.v2.jobs.DailyDigest.Part todos = com.onesay.assistant.v2.jobs.DailyDigest.Part.of(new ArrayList<String>());
        com.onesay.assistant.v2.jobs.DailyDigest.Part mails = com.onesay.assistant.v2.jobs.DailyDigest.Part.of(new ArrayList<String>());
        String old = com.onesay.assistant.v2.jobs.DailyDigest.compose(todos, mails, "晴");
        assertEquals("不带日程 = 旧稿", old, com.onesay.assistant.v2.jobs.DailyDigest.compose(todos, mails, "晴", null));
        assertFalse(old.contains("日程"));
        String s = com.onesay.assistant.v2.jobs.DailyDigest.compose(todos, mails, "晴",
                com.onesay.assistant.v2.jobs.DailyDigest.Part.of(Arrays.asList("上午9点 晨会", "下午3点 开会")));
        assertTrue(s, s.startsWith("早上好。今天有 2 个日程：上午9点 晨会；下午3点 开会。"));
        String empty = com.onesay.assistant.v2.jobs.DailyDigest.compose(todos, mails, "晴", com.onesay.assistant.v2.jobs.DailyDigest.Part.of(new ArrayList<String>()));
        assertTrue(empty, empty.contains("今天没有日程。"));
    }

    @Test public void myThingsEvents() throws Exception {
        com.onesay.assistant.v2.jobs.DailyDigest.Part todos = com.onesay.assistant.v2.jobs.DailyDigest.Part.of(Arrays.asList("交周报"));
        com.onesay.assistant.v2.jobs.DailyDigest.Part mails = com.onesay.assistant.v2.jobs.DailyDigest.Part.note("没授权读取");
        String old = com.onesay.assistant.v2.jobs.MyThingsExport.payload(todos, mails, NOW, TZ);
        assertFalse(old.contains("events"));
        assertEquals(old, com.onesay.assistant.v2.jobs.MyThingsExport.payload(todos, mails, null, NOW, TZ));
        String p = com.onesay.assistant.v2.jobs.MyThingsExport.payload(todos, mails, com.onesay.assistant.v2.jobs.DailyDigest.Part.of(Arrays.asList("下午3点 开会")), NOW, TZ);
        org.json.JSONObject o = new org.json.JSONObject(p);
        assertEquals(1, o.getInt("v"));
        assertEquals("下午3点 开会", o.getJSONArray("events").getString(0));
        assertEquals("", o.getString("eventNote"));
        assertTrue(com.onesay.assistant.v2.jobs.MyThingsExport.amCommand(p).matches("^am broadcast -n \\S+ -a \\S+ --ei v 1 --es payload [A-Za-z0-9+/=]+$"));
    }

    // ------------------------------------------------------------------ 迁移

    @Test public void migrations() {
        final List<String> ran = new ArrayList<String>();
        SchedMigrations.Exec rec = new SchedMigrations.Exec() {
            @Override public void exec(String sql) {
                ran.add(sql);
            }
        };
        int v = 0;
        for (SchedMigrations.Step s : SchedMigrations.steps()) {
            assertEquals("版本号从 1 起连续", v + 1, s.version);
            v = s.version;
            assertTrue(s.sql.length > 0);
        }
        assertEquals(SchedMigrations.LATEST, v);
        assertEquals(SchedMigrations.LATEST, SchedMigrations.migrate(rec, 0, SchedMigrations.LATEST));
        assertTrue(ran.get(0).startsWith("CREATE TABLE IF NOT EXISTS sched_event"));
        ran.clear();
        assertEquals("v1 → 最新：只跑后面的步骤", SchedMigrations.LATEST - 1, SchedMigrations.migrate(rec, 1, SchedMigrations.LATEST));
        assertTrue(ran.get(0).startsWith("ALTER TABLE sched_event ADD COLUMN lunar"));
        ran.clear();
        assertEquals(0, SchedMigrations.migrate(rec, SchedMigrations.LATEST, SchedMigrations.LATEST));
        assertEquals("降级不动", 0, SchedMigrations.migrate(rec, SchedMigrations.LATEST + 1, SchedMigrations.LATEST));
        assertTrue(ran.isEmpty());
    }

    @Test public void sqliteStoreSelectsEveryColumnTheMigrationsCreate() {
        StringBuilder all = new StringBuilder();
        for (SchedMigrations.Step s : SchedMigrations.steps()) for (String q : s.sql) all.append(q).append('\n');
        String cols = SqliteSchedStore.COLS;
        for (String c : cols.split(",")) {
            if (c.equals("id")) continue;
            assertTrue("列 " + c + " 由某一步迁移建出来", java.util.regex.Pattern.compile("[(,\\s]" + c + " (INTEGER|TEXT)").matcher(all).find());
        }
    }

    // ------------------------------------------------------------------ 文字

    @Test public void format() {
        assertEquals("明天下午3点", SchedFormat.when(at(2026, 10, 25, 15, 0), false, NOW, TZ));
        assertEquals("今天上午9点半", SchedFormat.when(at(2026, 10, 24, 9, 30), false, NOW, TZ));
        assertEquals("后天晚上7点05分", SchedFormat.when(at(2026, 10, 26, 19, 5), false, NOW, TZ));
        assertEquals("下周三（10月28日）全天", SchedFormat.when(CnTimeTest.day(2026, 10, 28), true, NOW, TZ));
        assertEquals("11月11日 周三中午12点", SchedFormat.when(at(2026, 11, 11, 12, 0), false, NOW, TZ));
        assertEquals("2027年1月1日 周五 全天", SchedFormat.when(CnTimeTest.day(2027, 1, 1), true, NOW, TZ));
        assertEquals("明天下午3点到5点", SchedFormat.span(at(2026, 10, 25, 15, 0), at(2026, 10, 25, 17, 0), false, NOW, TZ));
        assertEquals("15:00–16:30", SchedFormat.hm(at(2026, 10, 25, 15, 0), at(2026, 10, 25, 16, 30), false, TZ));
        assertEquals("提前半小时提醒", SchedFormat.lead(30));
        assertEquals("提前1天提醒", SchedFormat.lead(1440));
        assertEquals("提前1个半小时提醒", SchedFormat.lead(90));
        assertEquals("准点提醒", SchedFormat.lead(0));
        assertEquals("不提醒", SchedFormat.lead(CnTime.LEAD_NONE));
        assertNull(null);
    }
}
