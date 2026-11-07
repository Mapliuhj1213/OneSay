package com.onesay.clock.core;

/**
 * 响铃时放什么、出事怎么回落（P0-1 永不静音 / P1-1 不播旧稿 / P1-6 贪睡只响铃）。纯状态机。
 *
 *   开始：贪睡 → RINGTONE；不播报（非播报闹钟或当天非第一个）→ RINGTONE；成品有效 → BRIEF；否则 → LIVE（铃声先响，开场拼好再说）。
 *   BRIEF（人声 + 天气配乐垫底）播完 → MUSIC：同一首天气配乐继续循环（0.1.1 起，用户定：播完不切铃声）；
 *   MUSIC 出错/配乐根本没起来 → RINGTONE；BRIEF 人声出错 → RINGTONE；LIVE（成品缺失）播完/失败 → RINGTONE；
 *   RINGTONE 打不开 → BEEP（代码合成提示音，不依赖任何文件）；BEEP 是终点，不会再落到静音。
 *   超时（默认 10 分钟无人操作）→ AUTO_STOP：0.1.5 起（用户 11-06 定）等同用户点停止——停声、清贪睡、排下一次，不再自动贪睡；
 *   用户停止 / 贪睡 → DONE（手动贪睡行为不变）。AUTO_STOP 与 DONE 一样是终点，只多一个「是超时停的」标记，用来发静默通知。
 */
public final class RingPlan {
    private RingPlan() { }

    public enum Mode { BRIEF, LIVE, MUSIC, RINGTONE, BEEP, AUTO_STOP, DONE }

    public enum Ev { VOICE_DONE, VOICE_ERROR, RINGTONE_ERROR, TIMEOUT, USER_STOP, USER_SNOOZE, LIVE_FAILED, MUSIC_ERROR }

    public static Mode start(boolean snooze, boolean briefThisTime, Validity.Verdict v) {
        if (snooze || !briefThisTime) return Mode.RINGTONE;
        return v == Validity.Verdict.OK ? Mode.BRIEF : Mode.LIVE;
    }

    public static Mode next(Mode cur, Ev e) {
        if (e == Ev.USER_STOP || e == Ev.USER_SNOOZE) return Mode.DONE;
        if (cur == Mode.DONE || cur == Mode.AUTO_STOP) return cur;
        if (e == Ev.TIMEOUT) return Mode.AUTO_STOP;
        switch (cur) {
            case BRIEF:
                if (e == Ev.VOICE_DONE) return Mode.MUSIC;
                if (e == Ev.VOICE_ERROR || e == Ev.LIVE_FAILED) return Mode.RINGTONE;
                if (e == Ev.RINGTONE_ERROR) return Mode.BEEP;
                return cur;                                      // 配乐中途出错：人声照播，播完进 MUSIC 再判
            case MUSIC:
                if (e == Ev.MUSIC_ERROR) return Mode.RINGTONE;
                return Mode.MUSIC;                               // 人声事件迟到：保持配乐
            case LIVE:
                if (e == Ev.VOICE_DONE || e == Ev.VOICE_ERROR || e == Ev.LIVE_FAILED) return Mode.RINGTONE;
                if (e == Ev.RINGTONE_ERROR) return Mode.BEEP;      // LIVE 期间铃声垫底失败
                return cur;
            case RINGTONE:
                if (e == Ev.RINGTONE_ERROR) return Mode.BEEP;
                return Mode.RINGTONE;                            // 人声事件迟到：保持铃声
            case BEEP:
            default:
                return Mode.BEEP;
        }
    }

    /** 这个模式下有没有声音在响（DONE/AUTO_STOP 以外都必须有声）。 */
    public static boolean audible(Mode m) { return m != Mode.DONE && m != Mode.AUTO_STOP; }

    /** 响铃怎么收尾：STOP = 清贪睡、排下一次（Sched.dismissed）；SNOOZE = 写贪睡记录。 */
    public enum End { STOP, SNOOZE }

    /** 只有用户手动贪睡才贪睡；超时（0.1.5 起）和用户停止都按停止收尾。 */
    public static End endFor(Ev e) { return e == Ev.USER_SNOOZE ? End.SNOOZE : End.STOP; }

    /** 超时停止后的静默通知标题：「闹钟 7:05 已自动停止」（时不补零、分补零）。 */
    public static String autoStopTitle(int hour, int minute) {
        return "闹钟 " + hour + ":" + (minute < 10 ? "0" : "") + minute + " 已自动停止";
    }

    /** 实际超时毫秒：调试覆盖秒数 > 0 时用它（只有 TEST_ALARM 会写），否则用设置的分钟数（夹到 ≥1）。 */
    public static long timeoutMs(int settingMin, int dbgSec) {
        if (dbgSec > 0) return dbgSec * 1000L;
        return Math.max(1, settingMin) * 60_000L;
    }
}
