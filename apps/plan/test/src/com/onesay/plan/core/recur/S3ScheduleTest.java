package com.onesay.plan.core.recur;

import static com.onesay.plan.core.recur.RecurTestUtil.TZ;
import static com.onesay.plan.core.recur.RecurTestUtil.at;
import static org.junit.Assert.*;

import org.junit.Test;

/** 自动任务（定时）排期：Store 用它给 automations.next_at 填值。 */
public class S3ScheduleTest {
    static final long FRI_10 = at(2026, 11, 6, 10, 0);

    @Test public void once() {
        assertEquals(FRI_10 + 1, S3Schedule.next(S3Schedule.ONCE, 0, 0, 0, FRI_10 + 1, FRI_10, TZ));
        assertEquals(-1L, S3Schedule.next(S3Schedule.ONCE, 0, 0, 0, FRI_10, FRI_10, TZ));
    }

    @Test public void dailyStrictlyAfter() {
        assertEquals(at(2026, 11, 7, 10, 0), S3Schedule.next(S3Schedule.DAILY, 10, 0, 0, 0, FRI_10, TZ));
        assertEquals(at(2026, 11, 6, 21, 30), S3Schedule.next(S3Schedule.DAILY, 21, 30, 0, 0, FRI_10, TZ));
    }

    @Test public void weeklyMonWed() {
        int mask = 1 | 4;   // 周一 = bit0，周三 = bit2
        assertEquals(at(2026, 11, 9, 9, 0), S3Schedule.next(S3Schedule.WEEKLY, 9, 0, mask, 0, FRI_10, TZ));
        assertEquals(-1L, S3Schedule.next(S3Schedule.WEEKLY, 9, 0, 0, 0, FRI_10, TZ));
        assertEquals("每周一、三 09:00", S3Schedule.describe(S3Schedule.WEEKLY, 9, 0, mask, 0, FRI_10, TZ));
    }

    @Test public void workdayUsesMakeUpDays() {
        assertEquals("10/9 周五之后是调休上班的 10/10 周六", at(2026, 10, 10, 9, 0),
                S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 10, 9, 10, 0), TZ));
        assertEquals("国庆假期跳过", at(2026, 10, 8, 9, 0),
                S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(2026, 9, 30, 10, 0), TZ));
    }

    @Test public void missDecision() {
        assertEquals(S3Schedule.Miss.NOT_DUE, S3Schedule.missDecision(0, FRI_10));
        assertEquals(S3Schedule.Miss.NOT_DUE, S3Schedule.missDecision(FRI_10 + 1, FRI_10));
        assertEquals(S3Schedule.Miss.FIRE_NOW, S3Schedule.missDecision(FRI_10 - 10 * 60000L, FRI_10));
        assertEquals(S3Schedule.Miss.FIRE_LATE, S3Schedule.missDecision(FRI_10 - 30 * 60000L, FRI_10));
        assertEquals(S3Schedule.Miss.MISSED, S3Schedule.missDecision(FRI_10 - 3600000L, FRI_10));
    }
}
