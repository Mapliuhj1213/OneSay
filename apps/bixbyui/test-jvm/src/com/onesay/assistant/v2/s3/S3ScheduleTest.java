package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

/** [s3] 排期：Asia/Shanghai、每天 / 每周指定日 / 工作日 / 一次、漏触发补一次或标错过。 */
public class S3ScheduleTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    /** 2026-10-09（周五）hh:mm 上海时间。 */
    static long at(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(SH);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    @Test public void dailyNextIsTodayOrTomorrow() {
        assertEquals(at(10, 9, 8, 0), S3Schedule.next(S3Schedule.DAILY, 8, 0, 0, 0, at(10, 9, 7, 59), SH));
        assertEquals("严格晚于：正好 8:00 → 明天", at(10, 10, 8, 0), S3Schedule.next(S3Schedule.DAILY, 8, 0, 0, 0, at(10, 9, 8, 0), SH));
        assertEquals(at(10, 10, 8, 0), S3Schedule.next(S3Schedule.DAILY, 8, 0, 0, 0, at(10, 9, 21, 0), SH));
    }

    @Test public void weeklyMaskMondayIsBit0() {
        int mon = 1;
        assertEquals("周五 → 下周一", at(10, 12, 9, 0), S3Schedule.next(S3Schedule.WEEKLY, 9, 0, mon, 0, at(10, 9, 10, 0), SH));
        int monWedFri = 1 | 4 | 16;
        assertEquals("周五 8 点 → 当天 9 点", at(10, 9, 9, 0), S3Schedule.next(S3Schedule.WEEKLY, 9, 0, monWedFri, 0, at(10, 9, 8, 0), SH));
        assertEquals("周五 10 点 → 下周一", at(10, 12, 9, 0), S3Schedule.next(S3Schedule.WEEKLY, 9, 0, monWedFri, 0, at(10, 9, 10, 0), SH));
        int sun = 1 << 6;
        assertEquals(at(10, 11, 9, 0), S3Schedule.next(S3Schedule.WEEKLY, 9, 0, sun, 0, at(10, 9, 10, 0), SH));
        assertEquals("掩码为空 → 没有下一次", -1L, S3Schedule.next(S3Schedule.WEEKLY, 9, 0, 0, 0, at(10, 9, 10, 0), SH));
    }

    @Test public void workdaySkipsWeekend() {
        // 2026-10-09 起按节假日 / 调休表（CnHolidays）：10/10 周六是国庆调休上班日
        assertEquals("周五晚 → 周六（10/10 调休上班）", at(10, 10, 9, 0), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(10, 9, 20, 0), SH));
        assertEquals("周六晚 → 下周一", at(10, 12, 9, 0), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(10, 10, 20, 0), SH));
        assertEquals(at(10, 9, 9, 0), S3Schedule.next(S3Schedule.WORKDAY, 9, 0, 0, 0, at(10, 9, 6, 0), SH));
    }

    @Test public void onceOnlyInFuture() {
        long t = at(10, 10, 15, 0);
        assertEquals(t, S3Schedule.next(S3Schedule.ONCE, 15, 0, 0, t, at(10, 9, 10, 0), SH));
        assertEquals(-1L, S3Schedule.next(S3Schedule.ONCE, 15, 0, 0, t, t, SH));
    }

    @Test public void missedUnderOneHourCatchesUpElseMissed() {
        long p = at(10, 9, 8, 0);
        assertEquals(S3Schedule.Miss.NOT_DUE, S3Schedule.missDecision(p, p - 1));
        assertEquals(S3Schedule.Miss.FIRE_NOW, S3Schedule.missDecision(p, p + 9L * 60000L));
        assertEquals("晚 10 分钟以上：补触发，岛上显示晚了 N 分钟", S3Schedule.Miss.FIRE_LATE, S3Schedule.missDecision(p, p + 40L * 60000L));
        assertEquals(S3Schedule.Miss.MISSED, S3Schedule.missDecision(p, p + 61L * 60000L));
    }

    @Test public void describeIsReadable() {
        assertEquals("每天 08:00", S3Schedule.describe(S3Schedule.DAILY, 8, 0, 0, 0, 0, SH));
        assertEquals("每周一、三 09:30", S3Schedule.describe(S3Schedule.WEEKLY, 9, 30, 1 | 4, 0, 0, SH));
        assertEquals("工作日 09:00", S3Schedule.describe(S3Schedule.WORKDAY, 9, 0, 0, 0, 0, SH));
        assertEquals("明天 15:00", S3Schedule.describe(S3Schedule.ONCE, 15, 0, 0, at(10, 10, 15, 0), at(10, 9, 10, 0), SH));
        assertEquals("10月20日 15:00", S3Schedule.describe(S3Schedule.ONCE, 15, 0, 0, at(10, 20, 15, 0), at(10, 9, 10, 0), SH));
    }
}
