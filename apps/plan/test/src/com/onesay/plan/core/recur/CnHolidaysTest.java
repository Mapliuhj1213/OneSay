package com.onesay.plan.core.recur;

import static com.onesay.plan.core.recur.RecurTestUtil.cal;
import static org.junit.Assert.*;

import org.junit.Test;

/** 法定节假日 / 调休表（2026）与表外年份的退路。特征测试：锁定现有行为。 */
public class CnHolidaysTest {
    @Test public void coversOnly2026() {
        assertTrue(CnHolidays.covers(2026));
        assertFalse(CnHolidays.covers(2027));
    }

    @Test public void makeUpWorkdaysOnWeekend() {
        assertTrue("春节调休 2/14 周六上班", CnHolidays.isWorkday(cal(2026, 2, 14)));
        assertTrue("国庆调休 10/10 周六上班", CnHolidays.isWorkday(cal(2026, 10, 10)));
    }

    @Test public void holidaysOnWeekdays() {
        assertFalse("国庆 10/1 周四放假", CnHolidays.isWorkday(cal(2026, 10, 1)));
        assertFalse("春节 2/16 周一放假", CnHolidays.isWorkday(cal(2026, 2, 16)));
    }

    @Test public void ordinaryDays() {
        assertTrue(CnHolidays.isWorkday(cal(2026, 11, 6)));    // 周五
        assertFalse(CnHolidays.isWorkday(cal(2026, 11, 7)));   // 周六
    }

    @Test public void outsideTableFallsBackToMonFri() {
        assertTrue(CnHolidays.isWorkday(cal(2027, 1, 4)));     // 周一
        assertFalse(CnHolidays.isWorkday(cal(2027, 1, 2)));    // 周六
    }
}
