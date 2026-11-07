package com.onesay.plan.core.recur;

import static com.onesay.plan.core.recur.RecurTestUtil.TZ;
import static com.onesay.plan.core.recur.RecurTestUtil.at;
import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/** 重复日程展开（「今天」页和提醒都靠它）。 */
public class SchedRecurTest {
    static SchedEvent ev(int repeat, long start) {
        SchedEvent e = new SchedEvent();
        e.title = "t";
        e.repeat = repeat;
        e.startAt = start;
        return e;
    }

    @Test public void dailyOneWeek() {
        SchedEvent e = ev(SchedEvent.REPEAT_DAILY, at(2026, 11, 1, 8, 0));
        List<SchedRecur.Occ> l = SchedRecur.expand(e, at(2026, 11, 2, 0, 0), at(2026, 11, 9, 0, 0), TZ);
        assertEquals(7, l.size());
        assertEquals(at(2026, 11, 2, 8, 0), l.get(0).start);
    }

    @Test public void workdaysAroundNationalDay() {
        SchedEvent e = ev(SchedEvent.REPEAT_WORKDAY, at(2026, 9, 1, 9, 0));
        // 9/28(一)–10/11(日)：上班 9/28 9/29 9/30、10/8 10/9 10/10(调休) = 6 天
        assertEquals(6, SchedRecur.expand(e, at(2026, 9, 28, 0, 0), at(2026, 10, 12, 0, 0), TZ).size());
    }

    @Test public void monthlyLastDay() {
        SchedEvent e = ev(SchedEvent.REPEAT_MONTHLY, at(2026, 1, 31, 20, 0));
        e.monthDay = -1;
        List<SchedRecur.Occ> l = SchedRecur.expand(e, at(2026, 2, 1, 0, 0), at(2026, 3, 1, 0, 0), TZ);
        assertEquals(1, l.size());
        assertEquals(at(2026, 2, 28, 20, 0), l.get(0).start);
        assertEquals("每月最后一天", SchedRecur.describe(e));
    }

    @Test public void yearlyLunar() {
        SchedEvent e = ev(SchedEvent.REPEAT_YEARLY, at(2026, 1, 1, 0, 0));
        e.allDay = true;
        e.lunar = true;
        e.lunarMonth = 8;
        e.lunarDay = 15;
        assertEquals(at(2026, 9, 25, 0, 0), SchedRecur.nextStart(e, at(2026, 9, 1, 0, 0), TZ));
        assertEquals("每年农历八月十五", SchedRecur.describe(e));
    }

    @Test public void deletedNotExpanded() {
        SchedEvent e = ev(SchedEvent.REPEAT_DAILY, at(2026, 11, 1, 8, 0));
        e.deleted = true;
        assertTrue(SchedRecur.expand(e, at(2026, 11, 2, 0, 0), at(2026, 11, 9, 0, 0), TZ).isEmpty());
    }
}
