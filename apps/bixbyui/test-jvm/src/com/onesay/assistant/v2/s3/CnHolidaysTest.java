package com.onesay.assistant.v2.s3;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** [s3] 2026 法定节假日与调休表（国办发明电〔2025〕7 号）+ 「工作日」排期跳过放假、补上调休上班日。 */
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
        assertEquals("放假 3+9+3+5+3+3+7", 33, CnHolidays.OFF.length);
        assertEquals("调休上班 1+2+1+2", 6, CnHolidays.ON.length);
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
        assertFalse(CnHolidays.covers(2027));
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
        // 2027 未收录：1/1（周五）按周一到周五算 = 工作日（法定节假日要等国务院通知后录入）
        assertTrue(CnHolidays.isWorkday(day(2027, 1, 1)));
        assertFalse(CnHolidays.isWorkday(day(2027, 1, 2)));
        // 跨年：2026-12-31 周四晚 → 2027-01-01
        assertEquals(at(2027, 1, 1, 9), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 12, 31, 20), SH));
    }
}
