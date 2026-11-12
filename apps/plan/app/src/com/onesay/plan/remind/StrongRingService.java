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

import com.onesay.plan.R;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.data.Settings;
import com.onesay.plan.data.Store;
import com.onesay.plan.ui.StrongAlertActivity;

/**
 * [borrow-1112] 待办强提醒的响铃服务（前台服务，不导出）：闹钟音频流循环响铃 + 振动 + 整屏页面。
 * 稍后 / 停止 / 完成都发 Intent 给本服务；屏幕被按灭（电源键）= 停止；响 {@link StrongAlert#RING_TIMEOUT_MS} 没人管 → 自动稍后，
 * 最多 {@link StrongAlert#MAX_AUTO_SNOOZE} 次后停止并留一条通知。思路来源：涛声 TaoSheng（MIT），代码自写。
 * 同一时刻只响一条：新的到点时，正在响的那条先按「稍后」处理。
 */
public final class StrongRingService extends Service {
    static final String TAG = "PlanStrong";
    public static final String CH = "plan_strong";
    public static final String A_RING = "com.onesay.plan.strong.RING", A_SNOOZE = "com.onesay.plan.strong.SNOOZE", A_STOP = "com.onesay.plan.strong.STOP",
            A_DONE = "com.onesay.plan.strong.DONE", A_CANCEL = "com.onesay.plan.strong.CANCEL";
    public static final String X_ID = "id", X_TITLE = "title", X_DUE = "due";
    static final int NOTI_RING = 0x5170, NOTI_MISSED = 0x5171;

    /** 正在响的待办 id（0 = 没在响）；整屏页用它判断自己是不是过期的。 */
    public static volatile long ringingId;

    private MediaPlayer player;
    private PowerManager.WakeLock wl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = new Runnable() { public void run() { onTimeout(); } };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ringingId != 0) { Log.i(TAG, "屏幕被按灭 → 停止"); finish(ringingId, StrongAlert.STOPPED, false); }
        }
    };
    private boolean screenRx;

    /** 开始响某条待办（Remind.fire / 稍后到点调用）。 */
    public static void ring(Context c, long id, String title, long due) {
        Intent i = new Intent(c, StrongRingService.class).setAction(A_RING).putExtra(X_ID, id).putExtra(X_TITLE, title).putExtra(X_DUE, due);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** 待办被勾掉 / 删除 / 关了提醒：在响就停（不改状态以外的东西）。 */
    public static void cancelIfRinging(Context c, long id) {
        if (ringingId == id && id != 0) c.startService(new Intent(c, StrongRingService.class).setAction(A_CANCEL).putExtra(X_ID, id));
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
            snooze(id == 0 ? ringingId : id, false, true);
        } else if (A_STOP.equals(a)) {
            finish(id == 0 ? ringingId : id, StrongAlert.STOPPED, false);
        } else if (A_DONE.equals(a)) {
            final long tid = id == 0 ? ringingId : id;
            finish(tid, StrongAlert.DONE, false);
            final Context c = getApplicationContext();
            new Thread(new Runnable() { public void run() { Remind.markDone(c, tid); } }, "plan-strong-done").start();
        } else if (A_CANCEL.equals(a)) {
            if (id == ringingId) finish(id, StrongAlert.STOPPED, false);
        } else if (ringingId == 0) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void start(long id, String title, long due) {
        if (id <= 0) { stopSelfIfIdle(); return; }
        if (ringingId != 0 && ringingId != id) snooze(ringingId, false, false);   // 同一时刻只响一条（不停服务，马上响新的）
        final String t = title == null ? "" : title;
        final long d = due;
        final long tid = id;
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) { return b.ring(tid, t, d, System.currentTimeMillis()); }
        });
        ringingId = id;
        channels(this);
        Intent full = new Intent(this, StrongAlertActivity.class).putExtra(X_ID, id).putExtra(X_TITLE, t).putExtra(X_DUE, d)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(this, (int) id, full, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(t)
                .setContentText("强提醒 · 音量键稍后，电源键停止").setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setFullScreenIntent(fullPi, true).setContentIntent(fullPi)
                .addAction(new Notification.Action.Builder(null, "完成", svc(A_DONE, id, 1)).build())
                .addAction(new Notification.Action.Builder(null, "稍后 " + snoozeMin() + " 分钟", svc(A_SNOOZE, id, 2)).build())
                .addAction(new Notification.Action.Builder(null, "停止", svc(A_STOP, id, 3)).build())
                .build();
        startForeground(NOTI_RING, n);
        try { startActivity(full); } catch (Throwable e) { Log.w(TAG, "整屏页拉不起来（通知里的整屏意图兜底）: " + e); }
        play();
        if (!screenRx) { registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF)); screenRx = true; }
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, StrongAlert.RING_TIMEOUT_MS);
        Log.i(TAG, "响铃 todo#" + id);
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

    void onTimeout() {
        final long id = ringingId;
        if (id == 0) return;
        StrongAlert.Entry e = AlertLog.load(this).get(id);
        if (StrongAlert.onTimeout(e) == StrongAlert.Timeout.SNOOZE) snooze(id, true, true);
        else {
            finish(id, StrongAlert.MISSED, true);
        }
    }

    void snooze(final long id, final boolean auto, boolean stopIfIdle) {
        if (id == 0) return;
        final int m = snoozeMin();
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) { return b.snooze(id, System.currentTimeMillis(), m, auto); }
        });
        Log.i(TAG, "稍后 todo#" + id + " " + m + " 分钟" + (auto ? "（没人管，自动）" : ""));
        endRing(id, stopIfIdle);
        Remind.schedule(this);   // 稍后到点由同一个精确闹钟排
    }

    void finish(final long id, final String state, boolean postMissed) {
        if (id == 0) { stopSelfIfIdle(); return; }
        AlertLog.edit(this, new AlertLog.Edit() {
            public StrongAlert.Entry apply(StrongAlert.Book b) { return b.stop(id, System.currentTimeMillis(), state); }
        });
        Log.i(TAG, "结束 todo#" + id + " " + state);
        if (postMissed) postMissed(id);
        endRing(id, true);
        Remind.schedule(this);
    }

    void endRing(long id, boolean stopIfIdle) {
        if (ringingId == id) {
            ringingId = 0;
            h.removeCallbacks(timeout);
            stopSound();
            if (screenRx) { try { unregisterReceiver(screenOff); } catch (Throwable ignored) { } screenRx = false; }
            sendBroadcast(new Intent(StrongAlertActivity.A_CLOSE).setPackage(getPackageName()));
        }
        if (stopIfIdle) stopSelfIfIdle();
    }

    void stopSelfIfIdle() {
        if (ringingId == 0) { stopForeground(true); stopSelf(); }
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
        stopSound();
        if (screenRx) { try { unregisterReceiver(screenOff); } catch (Throwable ignored) { } screenRx = false; }
        super.onDestroy();
    }
}
