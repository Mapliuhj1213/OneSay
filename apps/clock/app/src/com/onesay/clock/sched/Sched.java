package com.onesay.clock.sched;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlarmMath;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.Missed;
import com.onesay.clock.core.Pause;
import com.onesay.clock.core.SnoozeBook;
import com.onesay.clock.gen.GenService;
import com.onesay.clock.ui.MainActivity;

import java.util.List;
import java.util.TimeZone;

/**
 * 排程（P0-2 / P1-8 / P2-2）：永远只排「最早的一次」——闹钟或贪睡，用 setAlarmClock（精确、Doze 下也响、状态栏有闹钟图标）；
 * 这次若要播报，再用 setExactAndAllowWhileIdle 排「提前 N 分钟生成」和「T-2 分钟重试」。
 * 没有全局 nextAt：贪睡有自己的记录（SnoozeBook），到点按 id+时刻核对。所有入口去抖 500 ms、主线程串行。
 */
public final class Sched {
    private Sched() { }

    public static final String A_FIRE = "com.onesay.clock.FIRE", A_PREGEN = "com.onesay.clock.PREGEN", A_RETRY = "com.onesay.clock.RETRY";
    static final int RC_FIRE = 1, RC_PREGEN = 2, RC_RETRY = 3;
    public static final long RETRY_BEFORE_MS = 2 * 60_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context appCtx;
    private static String why = "";
    private static final Runnable RUN = new Runnable() { public void run() { rescheduleNow(appCtx, why); } };

    /** 去抖重排（同一 500 ms 内多次调用只排一次）。 */
    public static void reschedule(Context c, String reason) {
        appCtx = c.getApplicationContext();
        why = reason;
        H.removeCallbacks(RUN);
        H.postDelayed(RUN, 500);
    }

    static PendingIntent pi(Context c, String act, int rc, int id, long at, boolean snooze) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(act).putExtra("id", id).putExtra("at", at).putExtra("snooze", snooze);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent show(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 立即重排（同步）。返回排上的事件（null = 没有闹钟）。 */
    public static synchronized AlarmMath.Event rescheduleNow(Context c, String reason) {
        Prefs p = new Prefs(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        SnoozeBook b = p.snoozes();
        if (b.purge(now) > 0) p.saveSnoozes(b);
        prunePauses(c, p, now, tz);
        List<Alarm> as = p.alarms();
        AlarmMath.Event e = AlarmMath.earliest(as, b, now, tz);
        am.cancel(pi(c, A_FIRE, RC_FIRE, 0, 0, false));
        am.cancel(pi(c, A_PREGEN, RC_PREGEN, 0, 0, false));
        am.cancel(pi(c, A_RETRY, RC_RETRY, 0, 0, false));
        p.setSchedMark(e == null ? null : new Missed.Mark(e.alarmId, e.at, e.snooze));   // [borrow-1112] 开机后对账用
        if (e == null) { L.i(c, "SCHED none (" + reason + ")"); return null; }
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(e.at, show(c)), pi(c, A_FIRE, RC_FIRE, e.alarmId, e.at, e.snooze));
        boolean brief = false;
        if (!e.snooze) {
            Alarm a = p.alarm(e.alarmId);
            brief = AlarmMath.shouldBrief(a, e.at, p.lastFiredDay(), p.flag(Flags.FIRST_ONLY), tz);
            if (brief) {
                int dbg = com.onesay.clock.BuildMode.RELEASE ? 0 : p.i(Flags.DBG_PREGEN_SEC, 0);   // release 不认调试覆盖
                long lead = dbg > 0 ? dbg * 1000L : p.i(Flags.PREGEN_MIN, Flags.PREGEN_MIN_DEF) * 60_000L;
                long pre = e.at - lead, retry = e.at - RETRY_BEFORE_MS;
                if (pre > now + 5000) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pre, pi(c, A_PREGEN, RC_PREGEN, e.alarmId, e.at, false));
                else GenService.start(c, e.at, "late-sched");
                if (retry > now + 5000) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retry, pi(c, A_RETRY, RC_RETRY, e.alarmId, e.at, false));
            }
        }
        String why = e.snooze ? "snooze" : AlarmMath.briefReason(p.alarm(e.alarmId), e.at, p.lastFiredDay(), p.flag(Flags.FIRST_ONLY), tz);
        L.i(c, "SCHED " + e + " brief=" + brief + " why=" + why + " (" + reason + ")");
        return e;
    }

    /** 贪睡：写独立记录再重排。 */
    public static void snooze(Context c, int id, int minutes) {
        Prefs p = new Prefs(c);
        int dbg = com.onesay.clock.BuildMode.RELEASE ? 0 : p.i(Flags.DBG_SNOOZE_SEC, 0);   // release 不认调试覆盖
        long at = System.currentTimeMillis() + (dbg > 0 ? dbg * 1000L : minutes * 60_000L);
        at -= at % 1000;
        SnoozeBook b = p.snoozes();
        b.put(id, at);
        p.saveSnoozes(b);
        L.i(c, "SNOOZE id=" + id + " min=" + minutes + (dbg > 0 ? " (调试覆盖 " + dbg + " 秒)" : "") + " at=" + at);
        rescheduleNow(c, "snooze");
    }

    /** 用户停止：清掉这个闹钟的贪睡。 */
    public static void dismissed(Context c, int id) {
        Prefs p = new Prefs(c);
        SnoozeBook b = p.snoozes();
        if (b.remove(id)) p.saveSnoozes(b);
        rescheduleNow(c, "dismiss");
    }

    /** 到点后的记账：贪睡移除记录；非贪睡记「当天已响」、单次闹钟关掉。返回这次是否该播报。 */
    public static boolean onFired(Context c, int id, long at, boolean snooze) {
        Prefs p = new Prefs(c);
        p.setFiredMark(new Missed.Mark(id, at, snooze));   // [borrow-1112] 记下响过的那一次（错过对账用）
        boolean brief = false;
        if (snooze) {
            SnoozeBook b = p.snoozes();
            b.remove(id);
            p.saveSnoozes(b);
        } else {
            Alarm a = p.alarm(id);
            TimeZone tz = TimeZone.getDefault();
            brief = AlarmMath.shouldBrief(a, at, p.lastFiredDay(), p.flag(Flags.FIRST_ONLY), tz);
            L.i(c, "FIRED id=" + id + " brief=" + brief + " why=" + AlarmMath.briefReason(a, at, p.lastFiredDay(), p.flag(Flags.FIRST_ONLY), tz));
            if (AlarmMath.recordsFiredDay(id, false)) p.setLastFiredDay(AlarmMath.dayKey(at, tz));
            if (a != null && !a.repeats() && a.on) { a.on = false; p.put(a); }
        }
        rescheduleNow(c, "fired");
        return brief;
    }

    /** [borrow-1112] 过期的暂停记录清掉（到期本来就自动恢复，这里只是不让旧日期越积越多）。 */
    static void prunePauses(Context c, Prefs p, long now, TimeZone tz) {
        int today = AlarmMath.dayKey(now, tz);
        for (Alarm a : p.alarms()) {
            if (a.hasPause() && Pause.prune(a, today)) { p.put(a); L.i(c, "PAUSE 到期清理 id=" + a.id); }
        }
    }

    public static final int NOTI_MISSED = 7301;

    /**
     * [borrow-1112] 开机 / 改时间 / 改时区 / 升级后、重排之前调用：上次排上的那一次到点了却没响 → 发一条「错过了 X 点的闹钟」通知。
     * 只发通知，不补响；发完清掉记录（同一次不重复报）。没有记录（刚从旧版本升上来）→ 什么都不做。
     */
    public static synchronized void checkMissed(Context c, String action) {
        try {
            Prefs p = new Prefs(c);
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            List<Missed.Item> l = Missed.find(p.schedMark(), p.firedMark(), p.alarms(), now, tz);
            if (l.isEmpty()) return;
            p.setSchedMark(null);
            String title = Missed.title(l, now, tz), text = Missed.text(l, Missed.reason(action));
            L.i(c, "MISSED " + l + " (" + action + ")");
            android.app.Notification.Builder nb = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, com.onesay.clock.App.CH_MISSED) : new android.app.Notification.Builder(c);
            nb.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setShowWhen(true)
                    .setWhen(l.get(0).at).setCategory(android.app.Notification.CATEGORY_REMINDER).setContentIntent(show(c));
            ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTI_MISSED, nb.build());
        } catch (Throwable t) {
            L.i(c, "MISSED 检查失败 " + t);
        }
    }
}
