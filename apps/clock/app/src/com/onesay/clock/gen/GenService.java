package com.onesay.clock.gen;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.onesay.clock.App;
import com.onesay.clock.L;
import com.onesay.clock.core.BriefMeta;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 生成服务：前台（Doze 下 alarm 唤醒后能联网）、单线程执行器串行（P2-8），每个任务持 70 秒唤醒锁。 */
public class GenService extends Service {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private int running;

    public static void start(Context c, long forAlarmAt, String why) {
        Intent i = new Intent(c, GenService.class).putExtra("at", forAlarmAt).putExtra("why", why);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public int onStartCommand(Intent in, int flags, final int startId) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, App.CH_WORK) : new Notification.Builder(this);
        startForeground(9302, b.setSmallIcon(com.onesay.clock.R.drawable.ic_stat_alarm).setContentTitle("正在准备智能播报")
                .setOngoing(true).build());
        final long at = in == null ? 0 : in.getLongExtra("at", 0);
        final String why = in == null ? "?" : String.valueOf(in.getStringExtra("why"));
        running++;
        final PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bc:gen");
        wl.acquire(70_000L);
        EX.submit(new Runnable() {
            public void run() {
                try {
                    BriefMeta m = Briefer.load(GenService.this);
                    if (m != null && m.forAlarmAt == at && at > 0 && System.currentTimeMillis() - m.generatedAt < 5 * 60_000L
                            && "retry".equals(why) && m.degraded.isEmpty()) {
                        L.i(GenService.this, "GEN skip：这次闹钟已有完整成品");
                    } else {
                        Briefer.generate(GenService.this, at > 0 ? at : System.currentTimeMillis(), why);
                    }
                } finally {
                    if (wl.isHeld()) wl.release();
                    done(startId);
                }
            }
        });
        return START_NOT_STICKY;
    }

    private void done(final int startId) {
        new android.os.Handler(getMainLooper()).post(new Runnable() {
            public void run() { if (--running <= 0) { stopForeground(true); stopSelf(); } }
        });
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
