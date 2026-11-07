package com.onesay.clock.core;

import java.util.TimeZone;

/** 成品能不能播（P1-1 不播旧稿）。不过关 → 普通铃声 + 现拼开场。 */
public final class Validity {
    private Validity() { }

    public enum Verdict { OK, NO_BRIEF, NO_AUDIO, WRONG_ALARM, WRONG_DAY, TOO_OLD, FROM_FUTURE }

    public static final long MAX_AGE_MS = 6L * 3600_000L;
    public static final long ALARM_TOLERANCE_MS = 90_000L;
    public static final long CLOCK_SKEW_MS = 5L * 60_000L;

    /**
     * @param wantAlarmAt 本次响铃对应的闹钟时刻（0 = 手动试听，只看新旧）
     * @param audioBytes  brief.wav 大小（< 1 KB 视为没有）
     */
    public static Verdict check(BriefMeta m, long audioBytes, long wantAlarmAt, long now, TimeZone tz) {
        if (m == null) return Verdict.NO_BRIEF;
        if (audioBytes < 1024) return Verdict.NO_AUDIO;
        if (m.generatedAt > now + CLOCK_SKEW_MS) return Verdict.FROM_FUTURE;
        if (wantAlarmAt > 0) {
            if (m.forAlarmAt <= 0 || Math.abs(m.forAlarmAt - wantAlarmAt) > ALARM_TOLERANCE_MS) return Verdict.WRONG_ALARM;
            if (m.day != AlarmMath.dayKey(wantAlarmAt, tz)) return Verdict.WRONG_DAY;
        }
        if (now - m.generatedAt > MAX_AGE_MS) return Verdict.TOO_OLD;
        return Verdict.OK;
    }
}
