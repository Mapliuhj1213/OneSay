package com.onesay.plan.remind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.onesay.plan.R;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.data.Settings;
import com.onesay.plan.data.Store;
import com.onesay.plan.ui.StrongAlertActivity;

/**
 * [borrow-1112] 待办强提醒的响铃服务（前台服务，不导出）：闹钟音频流循环响铃 + 振动 + 整屏页面。
 * 稍后 / 停止 / 完成都发 Intent 给本服务；屏幕被按灭（电源键）= 停止；每条响 {@link StrongAlert#RING_TIMEOUT_MS} 没人管 → 这条自动稍后，
 * 每条各自最多 {@link StrongAlert#MAX_AUTO_SNOOZE} 次后停止并留一条通知。思路来源：涛声 TaoSheng（MIT），代码自写。
 * 并表同时提醒（2026-11-12 用户决定）：同一时刻多条到点、或一条在响时另一条到点，合并成一次响铃、一条通知、一个整屏页；
 * 「完成」只勾掉那一条（其余继续响，全部完成才停铃），音量键 / 稍后 = 全部稍后，电源键 / 停止 = 全部停止。
 */
public final class StrongRingService extends Service {
    static final String TAG = "PlanStrong";
    public static final String CH = "plan_strong";
    public static final String A_RING = "com.onesay.plan.strong.RING", A_SNOOZE = "com.onesay.plan.strong.SNOOZE", A_STOP = "com.onesay.plan.strong.STOP",
            A_DONE = "com.onesay.plan.strong.DONE", A_CANCEL = "com.onesay.plan.strong.CANCEL";
    public static final String X_ID = "id", X_TITLE = "title", X_DUE = "due";
    static final int NOTI_RING = 0x5170, NOTI_MISSED = 0x5171;

    /** 正在响的待办 id（空 = 没在响）；整屏页按它列出各条、判断自己是不是过期的。 */
    static final Set<Long> LIVE = Collections.synchronizedSet(new LinkedHashSet<Long>());

    public static boolean isRinging(long id) { return id != 0 && LIVE.contains(id); }

    public static boolean anyRinging() { return !LIVE.isEmpty(); }

    /** 整屏页用：现在正在响的各条（按到点先后）。 */
    public static List<StrongAlert.Entry> ringingEntries(Context c) {
        List<StrongAlert.Entry> l = new ArrayList<StrongAlert.Entry>();
        for (StrongAlert.Entry e : AlertLog.load(c).ringingAll()) if (LIVE.contains(e.todoId)) l.add(e);
        return l;
    }

    private MediaPlayer player;
    private PowerManager.WakeLock wl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = new Runnable() { public void run() { onTimeout(); } };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (anyRinging()) { Log.i(TAG, "屏幕被按灭 → 全部停止"); stopAll(StrongAlert.STOPPED); }
        }
    };
    private boolean screenRx;

    /** 开始响某条待办（Remind.fire / 稍后到点调用）；已有在响的就并进同一次响铃。 */
    public static void ring(Context c, long id, String title, long due) {
        Intent i = new Intent(c, StrongRingService.class).setAction(A_RING).putExtra(X_ID, id).putExtra(X_TITLE, title).putExtra(X_DUE, due);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** 待办被勾掉 / 删除 / 关了提醒：这条在响就停这一条（其余继续响）。 */
    public static void cancelIfRinging(Context c, long id) {
        if (isRinging(id)) c.startService(new Intent(c, StrongRingService.class).setAction(A_CANCEL).putExtra(X_ID, id));
    }

    public static Intent action(Context c, String a, long id) { return new Intent(c, StrongRingService.class).setAction(a).putExtra(X_ID, id); }

    public static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "待办强提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("设为强提醒的待办到点时，像闹钟一样响铃并整屏提醒");
        ch.setSound(null, null);   // 声音由本服务按闹钟音量放
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        String a = in == null ? null : in.getAction();
        long id = in == null ? 0 : in.getLongExtra(X_ID, 0);
        if (A_RING.equals(a)) {
            start(id, in.getStringExtra(X_TITLE), in.getLongExtra(X_DUE, 0));
        } else if (A_SNOOZE.equals(a)) {
            snoozeAll(false);                       // 音量键 / 稍后 = 全部稍后
        } else if (A_STOP.equals(a)) {
            stopAll(StrongAlert.STOPPED);           // 电源键 / 停止 = 全部停止
        } else if (A_DONE.equals(a)) {
            final long tid = id;
            if (tid != 0) {
                complete(tid);                      // 只勾掉这一条，其余继续响
                final Context c = getApplicationContext();
                new Thread(new Runnable() { public void run() { Remind.markDone(c, tid); } }, "plan-strong-done").start();
            } else stopSelfIfIdle();
        } else if (A_CANCEL.equals(a)) {
            if (isRinging(id)) finishOne(id, StrongAlert.STOPPED, false);
            else stopSelfIfIdle();
        } else {
            stopSelfIfIdle();
        }
        return START_NOT_STICKY;
    }

    void start(long id, String title, long due) {
        if (id <= 0) { stopSelfIfIdle(); return; }
        final boolean fresh = LIVE.isEmpty();
        final String t = title == null ? "" : title;
        final long d = due;
        final long tid = id;
        final Set<Long> live = new LinkedHashSet<Long>(LIVE);
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) {
                long now = System.currentTimeMillis();
                if (fresh) b.clearStaleRinging(live, now);   // 进程被杀后残留的「正在响」
                return b.ring(tid, t, d, now);
            }
        });
        LIVE.add(id);   // 并表：已经在响的不动，这一条加进同一次响铃
        refresh(true);
        if (fresh || player == null) play();
        if (!screenRx) { registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF)); screenRx = true; }
        armTimeout();
        Log.i(TAG, "响铃 todo#" + id + "（同时在响 " + LIVE.size() + " 条）");
    }

    /** 按现在在响的各条重发合并通知、刷新整屏页（openPage = 把整屏页拉到前台）。 */
    void refresh(boolean openPage) {
        List<StrongAlert.Entry> l = ringingEntries(this);
        if (l.isEmpty()) return;
        channels(this);
        StrongAlert.Entry first = l.get(0);
        Intent full = new Intent(this, StrongAlertActivity.class).putExtra(X_ID, first.todoId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(this, 0, full, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(StrongAlert.ringTitle(l))
                .setContentText(StrongAlert.ringText(l)).setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setFullScreenIntent(fullPi, true).setContentIntent(fullPi);
        if (l.size() == 1) {
            nb.addAction(new Notification.Action.Builder(null, "完成", svc(A_DONE, first.todoId, 1)).build())
              .addAction(new Notification.Action.Builder(null, "稍后 " + snoozeMin() + " 分钟", svc(A_SNOOZE, 0, 2)).build())
              .addAction(new Notification.Action.Builder(null, "停止", svc(A_STOP, 0, 3)).build());
        } else {
            Notification.InboxStyle st = new Notification.InboxStyle().setSummaryText("点开逐条完成 · 音量键全部稍后");
            for (StrongAlert.Entry e : l) st.addLine(e.title);
            nb.setStyle(st)
              .addAction(new Notification.Action.Builder(null, "全部稍后 " + snoozeMin() + " 分钟", svc(A_SNOOZE, 0, 2)).build())
              .addAction(new Notification.Action.Builder(null, "全部停止", svc(A_STOP, 0, 3)).build());
        }
        startForeground(NOTI_RING, nb.build());
        if (openPage) { try { startActivity(full); } catch (Throwable e) { Log.w(TAG, "整屏页拉不起来（通知里的整屏意图兜底）: " + e); } }
        else sendBroadcast(new Intent(StrongAlertActivity.A_CLOSE).setPackage(getPackageName()));
    }

    /** 每条各自的「没人管」计时：排到最早到时的那一条。 */
    void armTimeout() {
        h.removeCallbacks(timeout);
        long at = -1;
        for (StrongAlert.Entry e : ringingEntries(this)) {
            long x = e.remindedAt + StrongAlert.RING_TIMEOUT_MS;
            if (at < 0 || x < at) at = x;
        }
        if (at > 0) h.postDelayed(timeout, Math.max(1000L, at - System.currentTimeMillis()));
    }

    PendingIntent svc(String a, long id, int k) {
        return PendingIntent.getService(this, (int) (id * 8 + k), action(this, a, id), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    int snoozeMin() { return StrongAlert.snoozeMin(Settings.strongSnoozeMin(this)); }

    void play() {
        stopSound();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plan:strong");
            wl.acquire(StrongAlert.RING_TIMEOUT_MS + 60000L);
        } catch (Throwable ignored) { }
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Throwable e) {
            Log.w(TAG, "铃声放不出来: " + e);
        }
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 800, 600}, 0, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Throwable ignored) { }
    }

    void stopSound() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Throwable ignored) { }
        player = null;
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.cancel(); } catch (Throwable ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
        wl = null;
    }

    /** 没人管：到时的那几条各自处理（还没满 3 次 → 这条自动稍后；满了 → 这条停止并留通知），其余继续响。 */
    void onTimeout() {
        long now = System.currentTimeMillis();
        StrongAlert.Book b = AlertLog.load(this);
        for (StrongAlert.Entry e : b.timedOut(now)) {
            if (!LIVE.contains(e.todoId)) continue;
            if (StrongAlert.onTimeout(e) == StrongAlert.Timeout.SNOOZE) snoozeOne(e.todoId);
            else finishOne(e.todoId, StrongAlert.MISSED, true);
        }
        if (anyRinging()) armTimeout();
    }

    /** 没人管时这一条自动稍后（计次）。 */
    void snoozeOne(final long id) {
        final int m = snoozeMin();
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) { return b.snooze(id, System.currentTimeMillis(), m, true); }
        });
        Log.i(TAG, "稍后 todo#" + id + " " + m + " 分钟（没人管，自动）");
        drop(id);
        Remind.schedule(this);   // 稍后到点由同一个精确闹钟排
    }

    /** 音量键 / 稍后：正在响的全部稍后（手动，不计次）。 */
    void snoozeAll(final boolean auto) {
        if (!anyRinging()) { stopSelfIfIdle(); return; }
        final int m = snoozeMin();
        List<StrongAlert.Entry> l = AlertLog.editAll(this, new AlertLog.EditAll() {
            public List<StrongAlert.Entry> apply(StrongAlert.Book b) { return b.snoozeAll(System.currentTimeMillis(), m, auto); }
        });
        Log.i(TAG, "全部稍后 " + l.size() + " 条 " + m + " 分钟");
        dropAll();
        Remind.schedule(this);
    }

    /** 电源键 / 停止：正在响的全部停止。 */
    void stopAll(final String state) {
        if (!anyRinging()) { stopSelfIfIdle(); return; }
        List<StrongAlert.Entry> l = AlertLog.editAll(this, new AlertLog.EditAll() {
            public List<StrongAlert.Entry> apply(StrongAlert.Book b) { return b.stopAll(System.currentTimeMillis(), state); }
        });
        Log.i(TAG, "全部停止 " + l.size() + " 条");
        dropAll();
        Remind.schedule(this);
    }

    /** 整屏页 / 通知上某一条的「完成」：只停这一条；全部完成才停铃。 */
    void complete(final long id) {
        List<StrongAlert.Entry> left = AlertLog.editAll(this, new AlertLog.EditAll() {
            public List<StrongAlert.Entry> apply(StrongAlert.Book b) { return b.complete(id, System.currentTimeMillis()); }
        });
        Log.i(TAG, "完成 todo#" + id + "，还在响 " + left.size() + " 条");
        drop(id);
    }

    /** 单条结束（被勾掉 / 删除 / 没人管满 3 次）：只停这一条，其余继续响。 */
    void finishOne(final long id, final String state, boolean postMissed) {
        if (id == 0) { stopSelfIfIdle(); return; }
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) { return b.stop(id, System.currentTimeMillis(), state); }
        });
        Log.i(TAG, "结束 todo#" + id + " " + state);
        if (postMissed) postMissed(id);
        drop(id);
        Remind.schedule(this);
    }

    /** 把一条从这次响铃里拿掉：还有别的在响 → 继续响、刷新通知和整屏页；都没了 → 停铃、关页、停服务。 */
    void drop(long id) {
        LIVE.remove(id);
        if (anyRinging()) { refresh(false); armTimeout(); }
        else endRing();
    }

    void dropAll() {
        LIVE.clear();
        endRing();
    }

    void endRing() {
        h.removeCallbacks(timeout);
        stopSound();
        if (screenRx) { try { unregisterReceiver(screenOff); } catch (Throwable ignored) { } screenRx = false; }
        sendBroadcast(new Intent(StrongAlertActivity.A_CLOSE).setPackage(getPackageName()));
        stopSelfIfIdle();
    }

    void stopSelfIfIdle() {
        if (!anyRinging()) { stopForeground(true); stopSelf(); }
    }

    void postMissed(long id) {
        Row r = Store.get(this, Contract.T_TODOS, id);
        if (r == null) return;
        Remind.channels(this);
        Notification n = new Notification.Builder(this, Remind.CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(r.s("title"))
                .setContentText("强提醒响了几次都没人处理，已经停了").setAutoCancel(true).setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(this, (int) id, new Intent(this, com.onesay.plan.ui.MainActivity.class)
                        .putExtra(com.onesay.plan.core.TabNav.EXTRA_TAB, Contract.T_TODOS).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_MISSED + (int) (id & 0xFFF), n);
    }

    @Override public void onDestroy() {
        h.removeCallbacks(timeout);
        LIVE.clear();
        stopSound();
        if (screenRx) { try { unregisterReceiver(screenOff); } catch (Throwable ignored) { } screenRx = false; }
        super.onDestroy();
    }
}
