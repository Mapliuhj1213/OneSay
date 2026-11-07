package com.onesay.assistant.v2.sched;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [sched] 中文时间解析。基准「现在」= 2026-10-24（周六）10:00 Asia/Shanghai。
 * 本周一 = 10-19，下周一 = 10-26，下周三 = 10-28；今天 10:00 时上午 3 点已过、下午 3 点未到。
 */
public class CnTimeTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long NOW = at(2026, 10, 24, 10, 0);
    private TimeZone saved;

    @Before public void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
    }

    @After public void tearDown() {
        TimeZone.setDefault(saved);
    }

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar k = Calendar.getInstance(TZ);
        k.clear();
        k.set(y, mo - 1, d, h, mi, 0);
        return k.getTimeInMillis();
    }

    static long day(int y, int mo, int d) {
        return at(y, mo, d, 0, 0);
    }

    static CnTime.Result p(String s) {
        return CnTime.parse(s, NOW, TZ);
    }

    static void timed(String s, long want) {
        CnTime.Result r = p(s);
        assertTrue(s + " found " + r, r.found);
        assertNull(s + " 不该追问: " + r.question, r.question);
        assertNull(s + " error " + r.error, r.error);
        assertFalse(s + " 不是全天 " + r, r.allDay);
        assertEquals(s + " " + r + " got " + new java.util.Date(r.start), want, r.start);
    }

    static void allDay(String s, long want) {
        CnTime.Result r = p(s);
        assertTrue(s + " found", r.found);
        assertNull(s + " 不该追问: " + r.question, r.question);
        assertTrue(s + " 全天 " + r, r.allDay);
        assertEquals(s + " " + r + " got " + new java.util.Date(r.start), want, r.start);
    }

    // ------------------------------------------------------------------ 日子 + 钟点

    @Test public void dayWordsWithClock() {
        timed("明天下午三点开会", at(2026, 10, 25, 15, 0));
        timed("后天上午9点半面试", at(2026, 10, 26, 9, 30));
        timed("大后天晚上8点吃饭", at(2026, 10, 27, 20, 0));
        timed("明天上午10点", at(2026, 10, 25, 10, 0));
        timed("明天15:30", at(2026, 10, 25, 15, 30));
        timed("明天早上7点", at(2026, 10, 25, 7, 0));
        timed("明天凌晨2点", at(2026, 10, 25, 2, 0));
        timed("明天中午12点", at(2026, 10, 25, 12, 0));
        timed("明天中午1点", at(2026, 10, 25, 13, 0));
        timed("明天八点", at(2026, 10, 25, 8, 0));
        timed("明天十一点", at(2026, 10, 25, 11, 0));
        timed("明天晚上十点", at(2026, 10, 25, 22, 0));
        timed("明日下午两点", at(2026, 10, 25, 14, 0));
        timed("明儿下午两点", at(2026, 10, 25, 14, 0));
    }

    @Test public void clockForms() {
        timed("下午3点45", at(2026, 10, 24, 15, 45));
        timed("下午两点半", at(2026, 10, 24, 14, 30));
        timed("下午三点一刻", at(2026, 10, 24, 15, 15));
        timed("下午三点三刻", at(2026, 10, 24, 15, 45));
        timed("晚上7点45分", at(2026, 10, 24, 19, 45));
        timed("晚上7点05分", at(2026, 10, 24, 19, 5));
        timed("晚上十一点半", at(2026, 10, 24, 23, 30));
        timed("15:30开会", at(2026, 10, 24, 15, 30));
        timed("１５：３０开会", at(2026, 10, 24, 15, 30));
        timed("15时30分", at(2026, 10, 24, 15, 30));
        timed("十二点", at(2026, 10, 24, 12, 0));
        timed("下午三点整", at(2026, 10, 24, 15, 0));
        timed("傍晚六点", at(2026, 10, 24, 18, 0));
        timed("下午 3 点 开会", at(2026, 10, 24, 15, 0));
    }

    @Test public void tonightMidnightAndSmallHours() {
        timed("今晚12点", at(2026, 10, 25, 0, 0));
        timed("今晚八点", at(2026, 10, 24, 20, 0));
        timed("晚上12点", at(2026, 10, 25, 0, 0));
        timed("晚上一点", at(2026, 10, 25, 1, 0));
        timed("半夜12点", at(2026, 10, 25, 0, 0));
        timed("明晚七点半", at(2026, 10, 26 - 1, 19, 30));
    }

    // ------------------------------------------------------------------ 歧义 / 小爱式推断

    @Test public void bareHourTodayPicksFuture() {
        timed("三点开会", at(2026, 10, 24, 15, 0));        // 上午 3 点已过 → 下午
        timed("1点", at(2026, 10, 24, 13, 0));
        timed("八点", at(2026, 10, 24, 20, 0));            // 上午 8 点已过 → 晚上
        timed("九点", at(2026, 10, 24, 21, 0));
        timed("十一点", at(2026, 10, 24, 11, 0));          // 上午 11 点还没到
        timed("十点整", at(2026, 10, 24, 10, 0));          // 正好现在
    }

    @Test public void bareHourOtherDayAsks() {
        CnTime.Result r = p("明天三点开会");
        assertNotNull(r.question);
        assertTrue(r.question, r.question.contains("下午3点") && r.question.contains("凌晨3点"));
        assertEquals(2, r.options.size());
        assertEquals(at(2026, 10, 25, 15, 0), r.options.get(0).start);
        assertEquals(at(2026, 10, 25, 3, 0), r.options.get(1).start);
        CnTime.Result six = p("明天6点");
        assertTrue(six.question, six.question.contains("晚上6点") && six.question.contains("早上6点"));
        assertEquals(at(2026, 10, 25, 18, 0), six.options.get(0).start);
        assertEquals(at(2026, 10, 25, 6, 0), six.options.get(1).start);
    }

    @Test public void passedClockRollsToTomorrowWithoutAsking() {
        CnTime.Result r = p("早上7点");
        assertTrue(r.rolled);
        assertNull(r.question);
        assertEquals(at(2026, 10, 25, 7, 0), r.start);
        CnTime.Result z = p("零点");
        assertTrue(z.rolled);
        assertEquals(at(2026, 10, 25, 0, 0), z.start);
        CnTime.Result late = CnTime.parse("八点", at(2026, 10, 24, 23, 0), TZ);
        assertTrue(late.rolled);
        assertEquals(at(2026, 10, 25, 8, 0), late.start);
        CnTime.Result lateAmb = CnTime.parse("三点", at(2026, 10, 24, 23, 0), TZ);
        assertNotNull("深夜说「三点」：明天凌晨还是下午？", lateAmb.question);
        assertEquals(at(2026, 10, 25, 15, 0), lateAmb.options.get(0).start);
        CnTime.Result early = CnTime.parse("三点", at(2026, 10, 24, 0, 30), TZ);
        assertNotNull("凌晨 0:30 说「三点」：两个都没过 → 追问", early.question);
    }

    @Test public void explicitTodayPassedAsks() {
        CnTime.Result r = p("今天上午9点");
        assertNotNull(r.question);
        assertTrue(r.question.contains("已经过了"));
        assertEquals(at(2026, 10, 25, 9, 0), r.options.get(0).start);
        assertEquals("就今天", r.options.get(1).label);
    }

    @Test public void weekdayToday() {
        CnTime.Result r = p("周六下午三点聚餐");   // 今天就是周六
        assertNotNull(r.question);
        assertTrue(r.question.contains("今天") && r.question.contains("下周六"));
        assertEquals(at(2026, 10, 24, 15, 0), r.options.get(0).start);
        assertEquals(at(2026, 10, 31, 15, 0), r.options.get(1).start);
        timed("周六上午9点", at(2026, 10, 31, 9, 0));   // 今天这个点已过 → 下周六，不追问
    }

    @Test public void thisWeekPastAsks() {
        CnTime.Result r = p("这周三下午三点");
        assertNotNull(r.question);
        assertTrue(r.question, r.question.contains("这周三已经过了"));
        assertEquals(at(2026, 10, 28, 15, 0), r.options.get(0).start);
    }

    // ------------------------------------------------------------------ 星期

    @Test public void weekdays() {
        timed("下周一上午十点例会", at(2026, 10, 26, 10, 0));
        timed("下周三下午两点", at(2026, 10, 28, 14, 0));
        timed("周三下午两点", at(2026, 10, 28, 14, 0));
        timed("这周日晚上七点", at(2026, 10, 25, 19, 0));
        timed("星期五上午9点", at(2026, 10, 30, 9, 0));
        timed("礼拜五上午9点", at(2026, 10, 30, 9, 0));
        allDay("周日", day(2026, 10, 25));
        allDay("星期天", day(2026, 10, 25));
        allDay("下周日", day(2026, 11, 1));
        allDay("下下周五", day(2026, 11, 6));
        allDay("下个星期二", day(2026, 10, 27));
    }

    @Test public void weekOnly() {
        CnTime.Result r = p("下周开会");
        assertTrue(r.found);
        assertEquals(day(2026, 10, 26), r.weekStart);
        assertNull("没说钟点：查询用，不追问", r.question);
        CnTime.Result q = p("下周下午三点开会");
        assertEquals("下周哪一天？", q.question);
        assertEquals(day(2026, 10, 19), p("这周").weekStart);
    }

    @Test public void weekend() {
        CnTime.Result r = p("周末去爬山");
        assertTrue(r.weekend);
        assertEquals(day(2026, 10, 24), r.start);
        CnTime.Result n = p("下周末");
        assertEquals(day(2026, 10, 31), n.start);
    }

    // ------------------------------------------------------------------ 相对

    @Test public void relative() {
        timed("半小时后开会", at(2026, 10, 24, 10, 30));
        timed("一个半小时后", at(2026, 10, 24, 11, 30));
        timed("10分钟后", at(2026, 10, 24, 10, 10));
        timed("两个小时后", at(2026, 10, 24, 12, 0));
        timed("两个钟头以后", at(2026, 10, 24, 12, 0));
        timed("1小时20分钟后", at(2026, 10, 24, 11, 20));
        timed("过半小时", at(2026, 10, 24, 10, 30));
        timed("过20分钟", at(2026, 10, 24, 10, 20));
        timed("三个小时之后", at(2026, 10, 24, 13, 0));
        allDay("3天后", day(2026, 10, 27));
        allDay("十日后", day(2026, 11, 3));
        timed("两周后上午九点", at(2026, 11, 7, 9, 0));
        timed("三天后下午两点", at(2026, 10, 27, 14, 0));
        allDay("一个月后", day(2026, 11, 24));
        allDay("半个月后", day(2026, 11, 8));
    }

    // ------------------------------------------------------------------ 月日 / 月底

    @Test public void monthDays() {
        allDay("25号", day(2026, 10, 25));
        allDay("20号", day(2026, 11, 20));         // 已过 → 下个月
        allDay("31号", day(2026, 10, 31));
        allDay("下个月3号", day(2026, 11, 3));
        allDay("下个月的3号", day(2026, 11, 3));
        timed("下个月15号上午10点", at(2026, 11, 15, 10, 0));
        timed("11月11号晚上8点", at(2026, 11, 11, 20, 0));
        allDay("3月5日", day(2027, 3, 5));          // 已过 → 明年
        allDay("2027年1月1日", day(2027, 1, 1));
        allDay("明年5月1号", day(2027, 5, 1));
        timed("12月31号下午6点", at(2026, 12, 31, 18, 0));
        allDay("十一月十一日", day(2026, 11, 11));
        allDay("11月11", day(2026, 11, 11));
    }

    @Test public void monthEdges() {
        allDay("月底交报告", day(2026, 10, 31));
        allDay("这个月底", day(2026, 10, 31));
        allDay("下个月底", day(2026, 11, 30));
        allDay("月初", day(2026, 11, 1));
        allDay("下个月初", day(2026, 11, 1));
        allDay("12月底", day(2026, 12, 31));
        allDay("年底", day(2026, 12, 31));
        allDay("2月底", day(2027, 2, 28));
    }

    @Test public void invalidDate() {
        CnTime.Result r = p("2月30号开会");
        assertNotNull(r.error);
        CnTime.Result r2 = p("2027年2月29日");
        assertNotNull(r2.error);
    }

    // ------------------------------------------------------------------ 只说时段

    @Test public void periodOnlyGuesses() {
        CnTime.Result r = p("明天上午");
        assertTrue(r.timeGuessed);
        assertEquals(at(2026, 10, 25, 9, 0), r.start);
        assertEquals(at(2026, 10, 25, 15, 0), p("明天下午").start);
        assertEquals(at(2026, 10, 24, 20, 0), p("今晚").start);
        assertEquals(at(2026, 10, 25, 8, 0), p("明早").start);
        assertEquals(at(2026, 10, 25, 12, 0), p("明天中午").start);
    }

    // ------------------------------------------------------------------ 区间

    @Test public void ranges() {
        CnTime.Result r = p("下午三点到五点开会");
        assertEquals(at(2026, 10, 24, 15, 0), r.start);
        assertEquals(at(2026, 10, 24, 17, 0), r.end);
        CnTime.Result r2 = p("明天上午10点到11点半");
        assertEquals(at(2026, 10, 25, 10, 0), r2.start);
        assertEquals(at(2026, 10, 25, 11, 30), r2.end);
        CnTime.Result r3 = p("明天14:00-15:30评审");
        assertEquals(at(2026, 10, 25, 14, 0), r3.start);
        assertEquals(at(2026, 10, 25, 15, 30), r3.end);
        CnTime.Result r4 = p("明天上午11点到下午1点");
        assertEquals(at(2026, 10, 25, 13, 0), r4.end);
        assertTrue(r4.rest, !r4.rest.contains("到"));
    }

    // ------------------------------------------------------------------ 重复

    @Test public void repeats() {
        CnTime.Result w = p("每周三下午三点开例会");
        assertEquals(SchedEvent.REPEAT_WEEKLY, w.repeat);
        assertEquals(1 << (Calendar.WEDNESDAY - 1), w.weekMask);
        assertEquals(at(2026, 10, 28, 15, 0), w.start);
        CnTime.Result d = p("每天早上八点");
        assertEquals(SchedEvent.REPEAT_DAILY, d.repeat);
        assertEquals(at(2026, 10, 25, 8, 0), d.start);
        CnTime.Result d2 = p("每天晚上9点跑步");
        assertEquals(at(2026, 10, 24, 21, 0), d2.start);
        CnTime.Result wn = p("每晚十点");
        assertEquals(SchedEvent.REPEAT_DAILY, wn.repeat);
        assertEquals(at(2026, 10, 24, 22, 0), wn.start);
        CnTime.Result wd = p("每个工作日上午9点站会");
        assertEquals(SchedEvent.REPEAT_WORKDAY, wd.repeat);
        assertEquals(at(2026, 10, 26, 9, 0), wd.start);
        CnTime.Result m = p("每月15号还信用卡");
        assertEquals(SchedEvent.REPEAT_MONTHLY, m.repeat);
        assertEquals(15, m.monthDay);
        assertEquals(day(2026, 11, 15), m.start);
        CnTime.Result me = p("每月底交房租");
        assertEquals(-1, me.monthDay);
        assertEquals(day(2026, 10, 31), me.start);
        CnTime.Result y = p("每年3月8日");
        assertEquals(SchedEvent.REPEAT_YEARLY, y.repeat);
        assertEquals(day(2027, 3, 8), y.start);
        CnTime.Result multi = p("每周一三五");
        int want = (1 << (Calendar.MONDAY - 1)) | (1 << (Calendar.WEDNESDAY - 1)) | (1 << (Calendar.FRIDAY - 1));
        assertEquals(want, multi.weekMask);
        assertEquals(day(2026, 10, 26), multi.start);
        CnTime.Result multi2 = p("每周二和周四晚上7点");
        assertEquals((1 << (Calendar.TUESDAY - 1)) | (1 << (Calendar.THURSDAY - 1)), multi2.weekMask);
        assertEquals(at(2026, 10, 27, 19, 0), multi2.start);
        CnTime.Result we = p("每个周末");
        assertEquals((1 << (Calendar.SATURDAY - 1)) | (1 << (Calendar.SUNDAY - 1)), we.weekMask);
        assertEquals(day(2026, 10, 24), we.start);
        CnTime.Result wsun = p("每周日上午10点");
        assertEquals(at(2026, 10, 25, 10, 0), wsun.start);
        CnTime.Result w3 = p("每周三三点");
        assertNull("重复日程的 1-6 点不追问，按下午", w3.question);
        assertEquals(at(2026, 10, 28, 15, 0), w3.start);
    }

    // ------------------------------------------------------------------ 提前量

    @Test public void leads() {
        assertEquals(30, p("明天下午三点开会提前半小时提醒我").leadMin);
        assertEquals(1440, p("提前一天提醒").leadMin);
        assertEquals(10, p("明天开会提前10分钟提醒").leadMin);
        assertEquals(60, p("提前1小时").leadMin);
        assertEquals(90, p("提前一个半小时提醒").leadMin);
        assertEquals(CnTime.LEAD_NONE, p("明天开会不用提醒").leadMin);
        assertEquals(SchedEvent.LEAD_DEFAULT, p("明天开会").leadMin);
        CnTime.Result r = p("明天下午三点开会提前半小时提醒我");
        assertEquals(at(2026, 10, 25, 15, 0), r.start);
        assertFalse("提前量不当成相对时间", r.rest.contains("半小时"));
    }

    // ------------------------------------------------------------------ 农历（ICU）

    @Test public void lunarAvailableInJvm() {
        assertTrue("测试垫片应转给 icu4j", Lunar.available());
        int[] s = Lunar.toSolar(2026, 8, false, 15, TZ);
        assertEquals(2026, s[0]);
        assertEquals(9, s[1]);
        assertEquals(25, s[2]);
        int[] cny = Lunar.toSolar(2027, 1, false, 1, TZ);
        assertEquals(2027, cny[0]);
        assertEquals(2, cny[1]);
        assertEquals(6, cny[2]);
        int[] back = Lunar.fromSolar(2026, 9, 25);
        assertEquals(8, back[1]);
        assertEquals(15, back[2]);
        assertNull("不存在的日子", Lunar.toSolar(2026, 1, false, 31, TZ));
        // ICU 与官方的已知差异（Lunar.FIX）：2027 春节官方 2-6（ICU 2-7），2030 春节官方 2-3（ICU 2-2）
        int[] cny30 = Lunar.toSolar(2030, 1, false, 1, TZ);
        assertEquals(2, cny30[1]);
        assertEquals(3, cny30[2]);
        assertNull("2026 腊月只有 29 天（官方）", Lunar.toSolar(2026, 12, false, 30, TZ));
        int[] eve = Lunar.toSolar(2026, 12, false, 29, TZ);
        assertEquals(5, eve[2]);
        int[] zm30 = Lunar.toSolar(2027, 1, false, 30, TZ);
        assertEquals("2027 正月 30 天：三十 = 3-7", 7, zm30[2]);
        int[] b2 = Lunar.fromSolar(2027, 2, 6);
        assertEquals(2027, b2[0]);
        assertEquals(1, b2[1]);
        assertEquals(1, b2[2]);
        int[] b3 = Lunar.fromSolar(2030, 2, 2);
        assertEquals("2030-02-02 官方还是腊月三十", 12, b3[1]);
        assertEquals(30, b3[2]);
        int[] lx = Lunar.toSolar(2029, 12, false, 30, TZ);
        assertEquals(2, lx[1]);
        assertEquals(2, lx[2]);
        assertEquals("八月十五", Lunar.label(8, 15));
        assertEquals("正月初一", Lunar.label(1, 1));
        assertEquals("腊月廿三", Lunar.label(12, 23));
    }

    @Test public void lunarDates() {
        allDay("农历八月十五", day(2027, 9, 15));   // 2026 中秋（9-25）已过 → 2027
        allDay("中秋节", day(2027, 9, 15));
        allDay("春节", day(2027, 2, 6));
        allDay("正月初一", day(2027, 2, 6));
        allDay("大年初一", day(2027, 2, 6));
        allDay("元宵节", day(2027, 2, 20));
        allDay("除夕", Lunar.nextOccurrence(12, 30, day(2026, 10, 24), TZ));
        assertEquals(day(2027, 2, 5), Lunar.nextOccurrence(12, 30, day(2026, 10, 24), TZ));
        CnTime.Result r = p("中秋节聚餐");
        assertTrue(r.rest, r.rest.contains("聚餐"));
        timed("农历八月十五晚上8点", at(2027, 9, 15, 20, 0));
        CnTime.Result y = p("每年农历八月十五");
        assertEquals(SchedEvent.REPEAT_YEARLY, y.repeat);
        assertTrue(y.lunar);
        assertEquals(8, y.lunarMonth);
        assertEquals(15, y.lunarDay);
        assertEquals(day(2027, 9, 15), y.start);
        assertFalse("公历十月二十五号不是农历", p("十月二十五号").lunar);
        allDay("十月二十五号", day(2026, 10, 25));
    }

    // ------------------------------------------------------------------ 不是时间 / 标题

    @Test public void notTime() {
        assertFalse(p("早一点出发").hasTime);
        assertFalse(p("快一点").found);
        assertFalse(p("有点累").found);
        assertFalse(p("打开微信").found);
        assertFalse(p("第一点").found);
        assertFalse(p("这个小时").found);
    }

    @Test public void restKeepsTitle() {
        CnTime.Result r = p("明天下午三点和王总在公司开会");
        assertTrue(r.rest, r.rest.contains("和王总在公司开会"));
        CnTime.Result r2 = p("下周三上午九点半去医院复诊");
        assertTrue(r2.rest, r2.rest.contains("医院复诊"));
        CnTime.Result r3 = p("月底交报告");
        assertEquals("交报告", r3.rest.trim());
    }

    @Test public void soonAndNumbers() {
        assertTrue(p("最近有什么安排").soon);
        assertTrue(p("接下来").soon);
        assertEquals(23, CnNum.parse("二十三"));
        assertEquals(23, CnNum.parse("廿三"));
        assertEquals(10, CnNum.parse("十"));
        assertEquals(15, CnNum.parse("十五"));
        assertEquals(2027, CnNum.parse("二〇二七"));
        assertEquals(2, CnNum.parse("两"));
        assertEquals(45, CnNum.parse("45"));
        assertEquals(-1, CnNum.parse("abc"));
    }
}
