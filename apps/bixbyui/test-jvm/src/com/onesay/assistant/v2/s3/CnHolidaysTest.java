package com.onesay.assistant.v2.s3;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** [s3] 2026 / 2027 法定节假日与调休表（国办发明电〔2025〕7 号、〔2026〕10 号）+ 「工作日」排期跳过放假、补上调休上班日。 */
public class CnHolidaysTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static Calendar day(int y, int m, int d) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(y, m - 1, d, 9, 0, 0);
        return c;
    }

    static long at(int y, int m, int d, int h) {
        Calendar c = day(y, m, d);
        c.set(Calendar.HOUR_OF_DAY, h);
        return c.getTimeInMillis();
    }

    @Test public void tableShapeMatchesNotice() {
        assertEquals("放假 2026：3+9+3+5+3+3+7；2027：3+9+3+5+1+1+7", 33 + 29, CnHolidays.OFF.length);
        assertEquals("调休上班 2026：1+2+1+2；2027：2+1+2", 6 + 5, CnHolidays.ON.length);
        for (int ymd : CnHolidays.ON) {
            Calendar c = day(ymd / 10000, ymd / 100 % 100, ymd % 100);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            assertTrue("调休上班日必须是周末: " + ymd, dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
            assertTrue(CnHolidays.isWorkday(c));
        }
        for (int ymd : CnHolidays.OFF) {
            assertFalse("放假日不是工作日: " + ymd, CnHolidays.isWorkday(day(ymd / 10000, ymd / 100 % 100, ymd % 100)));
        }
        assertTrue(CnHolidays.covers(2026));
        assertTrue(CnHolidays.covers(2027));
        assertFalse("2028 还没有通知", CnHolidays.covers(2028));
    }

    /** [rel-1123-b] 2027 表逐条对国办发明电〔2026〕10 号：每段放假的首尾星期、调休上班日的星期都和通知写的一样。 */
    @Test public void table2027MatchesNoticeWeekdays() {
        int[][] off = {{20270101, Calendar.FRIDAY}, {20270103, Calendar.SUNDAY}, {20270205, Calendar.FRIDAY}, {20270213, Calendar.SATURDAY},
                {20270403, Calendar.SATURDAY}, {20270405, Calendar.MONDAY}, {20270501, Calendar.SATURDAY}, {20270505, Calendar.WEDNESDAY},
                {20270609, Calendar.WEDNESDAY}, {20270915, Calendar.WEDNESDAY}, {20271001, Calendar.FRIDAY}, {20271007, Calendar.THURSDAY}};
        for (int[] x : off) {
            Calendar c = day(x[0] / 10000, x[0] / 100 % 100, x[0] % 100);
            assertEquals("星期对不上通知：" + x[0], x[1], c.get(Calendar.DAY_OF_WEEK));
            assertFalse("放假：" + x[0], CnHolidays.isWorkday(c));
        }
        int[][] on = {{20270131, Calendar.SUNDAY}, {20270214, Calendar.SUNDAY}, {20270508, Calendar.SATURDAY}, {20270926, Calendar.SUNDAY}, {20271009, Calendar.SATURDAY}};
        for (int[] x : on) {
            Calendar c = day(x[0] / 10000, x[0] / 100 % 100, x[0] % 100);
            assertEquals("星期对不上通知：" + x[0], x[1], c.get(Calendar.DAY_OF_WEEK));
            assertTrue("调休上班：" + x[0], CnHolidays.isWorkday(c));
        }
        // 端午 / 中秋只放 1 天：前后一天照常上班
        assertTrue(CnHolidays.isWorkday(day(2027, 6, 8)));
        assertTrue(CnHolidays.isWorkday(day(2027, 6, 10)));
        assertTrue(CnHolidays.isWorkday(day(2027, 9, 16)));
        // 2027 全年天数自洽：365 天里放假 29 天（含周末）、调休上班 5 天
        int workdays = 0;
        Calendar c = day(2027, 1, 1);
        for (int i = 0; i < 365; i++) {
            if (CnHolidays.isWorkday(c)) workdays++;
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        assertEquals("2027 年工作日数（261 个周一到周五 − 18 个落在周一到周五的放假日 + 5 个调休上班）", 261 - 18 + 5, workdays);
    }

    @Test public void workday2027ScheduleFollowsNotice() {
        // 春节：1/30 周六晚 → 1/31 周日调休上班；2/4 周四晚 → 2/14 周日上班（2/5–13 放假）
        assertEquals(at(2027, 1, 31, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2027, 1, 30, 20), SH));
        assertEquals(at(2027, 2, 14, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2027, 2, 4, 20), SH));
        // 国庆：9/30 晚 → 10/8 周五；10/8 晚 → 10/9 周六调休上班
        assertEquals(at(2027, 10, 8, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2027, 9, 30, 20), SH));
        assertEquals(at(2027, 10, 9, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2027, 10, 8, 20), SH));
    }

    @Test public void ordinaryDaysUnchanged() {
        assertTrue(CnHolidays.isWorkday(day(2026, 10, 9)));    // 周五
        assertFalse(CnHolidays.isWorkday(day(2026, 10, 11)));  // 周日
        assertTrue(CnHolidays.isWorkday(day(2026, 3, 10)));
        assertFalse(CnHolidays.isWorkday(day(2026, 3, 14)));   // 周六
    }

    @Test public void workdayScheduleSkipsHolidaysAndHitsMakeupDays() {
        // 国庆：9/30 晚 → 10/8（10/1–7 放假）
        assertEquals(at(2026, 10, 8, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 9, 30, 20), SH));
        // 国庆前调休：9/19 周六晚 → 9/20 周日上班
        assertEquals(at(2026, 9, 20, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 9, 19, 20), SH));
        // 春节：2/13 周五晚 → 2/14 周六上班；2/14 晚 → 2/24（2/15–23 放假 9 天）
        assertEquals(at(2026, 2, 14, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 2, 13, 20), SH));
        assertEquals(at(2026, 2, 24, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 2, 14, 20), SH));
        // 中秋 9/25 周五放假：9/24 晚 → 9/28 周一
        assertEquals(at(2026, 9, 28, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 9, 24, 20), SH));
        // 每天 / 每周不受影响
        assertEquals(at(2026, 10, 1, 9), S3Schedule.next(S3Schedule.DAILY, 9, 0, 0, 0, at(2026, 9, 30, 20), SH));
    }

    @Test public void uncoveredYearFallsBackToMonFri() {
        // 2028 未收录：按周一到周五算（法定节假日要等国务院通知后录入）；2028-01-03 周一、2028-01-01 周六
        assertTrue(CnHolidays.isWorkday(day(2028, 1, 3)));
        assertFalse(CnHolidays.isWorkday(day(2028, 1, 1)));
        // 跨年：2026-12-31 周四晚 → 2027-01-04 周一（2027 元旦 1/1–1/3 放假，已收录）
        assertEquals(at(2027, 1, 4, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 12, 31, 20), SH));
    }
}
