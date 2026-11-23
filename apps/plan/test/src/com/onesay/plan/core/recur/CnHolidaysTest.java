package com.onesay.plan.core.recur;

import static com.onesay.plan.core.recur.RecurTestUtil.cal;
import static org.junit.Assert.*;

import org.junit.Test;

/** 法定节假日 / 调休表（2026、2027）与表外年份的退路。特征测试：锁定现有行为。 */
public class CnHolidaysTest {
    @Test public void covers2026And2027() {
        assertTrue(CnHolidays.covers(2026));
        assertTrue(CnHolidays.covers(2027));   // 国办发明电〔2026〕10 号，2026-11-05 发布
        assertFalse(CnHolidays.covers(2028));
    }

    @Test public void holidays2027() {
        assertFalse("2027 元旦 1/1 周五放假", CnHolidays.isWorkday(cal(2027, 1, 1)));
        assertFalse("2027 春节 2/8 周一放假", CnHolidays.isWorkday(cal(2027, 2, 8)));
        assertTrue("2027 春节调休 1/31 周日上班", CnHolidays.isWorkday(cal(2027, 1, 31)));
        assertTrue("2027 春节调休 2/14 周日上班", CnHolidays.isWorkday(cal(2027, 2, 14)));
        assertTrue("2027 劳动节调休 5/8 周六上班", CnHolidays.isWorkday(cal(2027, 5, 8)));
        assertFalse("2027 端午 6/9 周三放假", CnHolidays.isWorkday(cal(2027, 6, 9)));
        assertFalse("2027 中秋 9/15 周三放假", CnHolidays.isWorkday(cal(2027, 9, 15)));
        assertTrue("2027 国庆调休 9/26 周日上班", CnHolidays.isWorkday(cal(2027, 9, 26)));
        assertTrue("2027 国庆调休 10/9 周六上班", CnHolidays.isWorkday(cal(2027, 10, 9)));
        assertTrue("2027 国庆后 10/8 周五上班", CnHolidays.isWorkday(cal(2027, 10, 8)));
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
        assertTrue(CnHolidays.isWorkday(cal(2028, 1, 3)));     // 周一（2028 还没收录）
        assertFalse(CnHolidays.isWorkday(cal(2028, 1, 1)));    // 周六
    }
}
