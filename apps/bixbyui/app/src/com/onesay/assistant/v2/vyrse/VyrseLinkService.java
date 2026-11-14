package com.onesay.assistant.v2.vyrse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.onesay.assistant.plug.vyrse.core.VyrseDispatcher;
import com.onesay.assistant.plug.vyrse.core.VyrseHttp;
import com.onesay.assistant.plug.vyrse.core.VyrseLink;

/**
 * [vyrse 适配层] 「让 VYRSE 电脑联系这台手机」前台服务：跑 {@link VyrseLink} 长轮询线程。
 * <b>只在 总开关开 + 已配对 + 连接开关开 时启动</b>（{@link #sync}）；不监听任何端口，只主动连配对时填的局域网地址。
 * 常驻通知写明「局域网明文」并带「停止」按钮（发给本服务，不导出、不注册接收器）。
 * 电脑移除这台手机（401）→ 清掉 key、停服务、发一条提醒。日志只打动作名和成败。
 */
public final class VyrseLinkService extends Service {

    static final String TAG = "VyrseLink";
    static final String CH = "onesay_vyrse_link";
    static final int NID = 9400, NID_REMOVED = 9401;
    static final String ACT_STOP = "com.onesay.assistant.vyrse.STOP";

    private static volatile VyrseLinkService sRunning;
    private static volatile VyrseLink.State sState = VyrseLink.State.STOPPED;
    private static volatile String sLastCommand = "";
    private VyrseLink mLink;
    private VyrseDispatcher mDispatcher;
    private Thread mThread;

    public static boolean running() {
        return sRunning != null;
    }

    public static VyrseLink.State state() {
        return sState;
    }

    public static String lastCommand() {
        return sLastCommand;
    }

    static boolean shouldRun(Context c) {
        return VyrseSettings.enabled(c) && VyrseSettings.paired(c) && VyrseSettings.linkOn(c);
    }

    /** 按开关 / 配对状态起停。任意线程。 */
    public static void sync(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent it = new Intent(app, VyrseLinkService.class);
        try {
            if (shouldRun(app)) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(it);
                else app.startService(it);
            } else if (sRunning != null) {
                app.stopService(it);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[VYRSE] 起停服务失败: " + t.getClass().getSimpleName());
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "VYRSE 电脑连接", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, com.onesay.assistant.v2.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent stop = PendingIntent.getService(this, 9403, new Intent(this, VyrseLinkService.class).setAction(ACT_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NID, b.setSmallIcon(getApplicationInfo().icon).setOngoing(true)
                .setContentTitle("VYRSE 电脑可以联系这台手机")
                .setContentText("响铃 / 手电筒 / 读通知 / 打开 App；局域网明文传输")
                .setContentIntent(PendingIntent.getActivity(this, 9404, open, PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(0, "停止", stop).build()).build());
        sRunning = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            VyrseSettings.setLinkOn(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!shouldRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mThread == null || !mThread.isAlive()) startLoop();
        return START_STICKY;
    }

    private void startLoop() {
        final Context app = getApplicationContext();
        mDispatcher = new VyrseDispatcher(new VyrseAbilities(app));
        try {
            mLink = new VyrseLink(new VyrseHttp.LanSocket(), VyrseSettings.base(app), VyrseSettings.token(app), mDispatcher,
                    new VyrseLink.Sleeper() {
                        @Override public boolean sleep(long ms) {
                            try {
                                Thread.sleep(ms);
                                return true;
                            } catch (InterruptedException e) {
                                return false;
                            }
                        }
                    }, new VyrseLink.Listener() {
                        @Override public void onState(VyrseLink.State s) {
                            sState = s;
                            Log.i(TAG, "[VYRSE] 连接状态 " + s);
                        }

                        @Override public void onUnauthorized() {
                            VyrseSettings.markRemoved(app);
                            notifyRemoved(app);
                            stopSelf();
                        }

                        @Override public void onCommand(String action, boolean ok) {
                            sLastCommand = action + (ok ? " ✓" : " ✗") + " · " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
                            Log.i(TAG, "[VYRSE] 执行 " + action + " ok=" + ok);
                        }
                    });
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "[VYRSE] 配对信息无效，停服务");
            stopSelf();
            return;
        }
        mThread = com.onesay.assistant.v2.util.Threads.start("vyrse-link", mLink);
    }

    @SuppressWarnings("deprecation")
    static void notifyRemoved(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
            nm.notify(NID_REMOVED, b.setSmallIcon(c.getApplicationInfo().icon).setAutoCancel(true)
                    .setContentTitle("VYRSE 电脑已移除这台手机")
                    .setContentText("要继续用，在电脑上重新点「Pair a phone」，再到 OneSay 设置里输入新配对码").build());
        } catch (Throwable ignored) {
        }
    }

    @Override public void onDestroy() {
        VyrseLink l = mLink;
        mLink = null;
        if (l != null) l.stop();
        Thread t = mThread;
        mThread = null;
        if (t != null) t.interrupt();
        VyrseDispatcher d = mDispatcher;
        if (d != null) d.ring().stop();
        sRunning = null;
        sState = VyrseLink.State.STOPPED;
        super.onDestroy();
    }
}
