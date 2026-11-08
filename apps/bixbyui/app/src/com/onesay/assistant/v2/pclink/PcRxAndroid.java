package com.onesay.assistant.v2.pclink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * [pclink v2] 电脑 → 手机的 Android 一侧：常连循环的环境（开关、Wi-Fi、组播锁、配对信息）和每条指令的真正动作。
 *
 * <ul>
 *   <li>找手机：闹钟音频流（USAGE_ALARM）+ 临时把闹钟音量调到最大，静音 / 振动模式也响（「完全勿扰」会挡住，系统限制）；
 *       同时振动、点亮屏幕、发一条带「停止」的通知；到时 / 电脑发 stop / 点通知都会停，并把闹钟音量还原。
 *       （用闹钟流最大音量找手机的做法参考 Home Assistant 安卓伴侣 App 的 alarm_stream_max，代码自己写的。）</li>
 *   <li>打开网址 / 打开 App / 点亮屏幕 / 媒体音量 / 写剪贴板 / 弹通知 / 电量状态。</li>
 * </ul>
 * 日志只打指令名、结果、长度，不打网址、剪贴板、通知内容。
 */
public final class PcRxAndroid implements PcRx.Env, PcRx.Handler {

    static final String TAG = "PcLink";
    public static final String K_RX = "rx_enabled", K_RX_LOG = "rx_log";
    static final String CH = "pclink_from_pc", ACTION_STOP = "com.onesay.assistant.v2.pclink.RING_STOP";
    static final int NID_RING = 47821, NID_MSG = 47822;

    private static PcRx.Loop loop;
    private static Thread thread;
    private static volatile String state = PcRx.S_OFF, detail = "";

    private final Context app;
    private WifiManager.MulticastLock mlock;

    private PcRxAndroid(Context app) {
        this.app = app;
    }

    // ================================================================== 生命周期

    /** 进程起来 / 配对 / 开关变了时调用：该跑就起（已在跑就叫它马上重试）。 */
    public static synchronized void ensure(Context c) {
        Context app = c.getApplicationContext();
        if (loop != null && thread != null && thread.isAlive()) {
            loop.wake();
            return;
        }
        PcRxAndroid env = new PcRxAndroid(app);
        if (!env.wanted()) return; // 不需要就不起线程
        loop = new PcRx.Loop(env);
        thread = com.onesay.assistant.v2.util.Threads.start("pclink-rx", loop); // 非守护；loop.stop() 后自己退出
        Log.i(TAG, "[PCLINK] 常连通道启动");
    }

    public static synchronized void stopAll() {
        if (loop != null) loop.stop();
        loop = null;
        thread = null;
    }

    public static boolean rxOn(Context c) {
        return PcLink.sp(c).getBoolean(K_RX, PcRxPolicy.MASTER_DEFAULT);
    }

    public static void setRx(Context c, boolean on) {
        PcLink.sp(c).edit().putBoolean(K_RX, on).apply();
        if (on) ensure(c);
        else if (loop != null) loop.wake();
    }

    public static boolean cmdOn(Context c, String cmd) {
        return PcLink.sp(c).getBoolean("rx_" + cmd, PcRxPolicy.defaultOn(cmd));
    }

    public static void setCmd(Context c, String cmd, boolean on) {
        PcLink.sp(c).edit().putBoolean("rx_" + cmd, on).apply();
    }

    /** 控制台显示用：常连状态。 */
    public static String stateText() {
        String s = state;
        if (PcRx.S_UP.equals(s)) return "已连上 " + detail;
        if (PcRx.S_SEARCH.equals(s)) return "正在找电脑…";
        if (PcRx.S_WAIT.equals(s)) return "电脑不在，等它上线（电脑一开 OneSay 就会连上）";
        if (PcRx.S_NO_WIFI.equals(s)) return "没连 Wi-Fi，暂停";
        if (PcRx.S_ERROR.equals(s)) return detail;
        return "没在运行";
    }

    public static boolean up() {
        return PcRx.S_UP.equals(state);
    }

    // ================================================================== Env

    @Override public boolean wanted() {
        return PcLink.active(app) && rxOn(app) && !PcLink.keyLost(app);
    }

    @Override public boolean onLan() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && (ni.getType() == ConnectivityManager.TYPE_WIFI || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override public PcLanClient.Peer peer() {
        return PcLink.peer(app);
    }

    @Override public List<InetAddress> extraBroadcasts() {
        return PcLink.wifiBroadcast(app);
    }

    @Override public synchronized void multicast(boolean hold) {
        try {
            if (hold) {
                if (mlock == null) {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm == null) return;
                    mlock = wm.createMulticastLock("onesay-pclink");
                    mlock.setReferenceCounted(false);
                }
                if (!mlock.isHeld()) mlock.acquire();
            } else if (mlock != null && mlock.isHeld()) {
                mlock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "[PCLINK] 组播锁: " + t.getClass().getSimpleName());
        }
    }

    @Override public void state(String s, String d) {
        if (!s.equals(state) || !d.equals(detail)) Log.i(TAG, "[PCLINK] 常连 " + s + (PcRx.S_ERROR.equals(s) ? " " + d : ""));
        state = s;
        detail = d == null ? "" : d;
    }

    @Override public void remember(InetSocketAddress a) {
        String last = a.getAddress().getHostAddress() + ":" + a.getPort();
        SharedPreferences sp = PcLink.sp(app);
        if (!last.equals(sp.getString(PcLink.K_LAST, ""))) sp.edit().putString(PcLink.K_LAST, last).apply();
    }

    @Override public PcRx.Handler handler() {
        return this;
    }

    // ================================================================== Handler

    @Override public boolean enabled(String cmd) {
        return cmdOn(app, cmd);
    }

    @Override public void record(String cmd, boolean ok, String text) {
        String line = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date()) + " " + PcRxPolicy.label(cmd) + (ok ? " ✓" : " ✗ " + clip(text, 40));
        SharedPreferences sp = PcLink.sp(app);
        String old = sp.getString(K_RX_LOG, "");
        StringBuilder b = new StringBuilder(line);
        String[] lines = old.split("\n");
        for (int i = 0; i < lines.length && i < 9; i++) if (lines[i].length() > 0) b.append('\n').append(lines[i]);
        sp.edit().putString(K_RX_LOG, b.toString()).apply();
        Log.i(TAG, "[PCLINK] 电脑指令 " + cmd + " → " + (ok ? "ok" : "fail"));
    }

    static String clip(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    @Override public PcLanClient.Result run(String cmd, Map<String, Object> a) {
        if (PcRxPolicy.FIND.equals(cmd)) {
            if ("stop".equals(PcRxPolicy.str(a, "action"))) return ok(Ring.stop(app) ? "已停止响铃" : "本来就没在响", null);
            int s = PcRxPolicy.ringSeconds(a);
            return Ring.start(app, s) ? ok("手机正在响（" + s + " 秒）", null) : fail("响不了：没有可用的铃声");
        }
        if (PcRxPolicy.OPEN_URL.equals(cmd)) {
            String u = PcVoice.normalizeUrl(PcRxPolicy.str(a, "url"));
            if (u == null) return fail("只能打开 http / https 网址");
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ok("已在手机上打开", null);
            } catch (Exception e) {
                return fail("手机上没有能打开网址的应用");
            }
        }
        if (PcRxPolicy.OPEN_APP.equals(cmd)) return openApp(PcRxPolicy.str(a, "package"), PcRxPolicy.str(a, "name"));
        if (PcRxPolicy.SCREEN_ON.equals(cmd)) {
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "onesay:pclink-screen");
                wl.acquire(3000);
                return ok("屏幕已点亮", null);
            } catch (Exception e) {
                return fail("点不亮：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.VOLUME.equals(cmd)) {
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int p = ((Number) a.get("percent")).intValue();
                int idx = Math.round(p * max / 100f);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
                return ok("媒体音量 " + p + "%", null);
            } catch (Exception e) {
                return fail("调不了音量：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.CLIPBOARD.equals(cmd)) {
            final String t = String.valueOf(a.get("text"));
            final boolean[] done = {false};
            final CountDownLatch l = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("OneSay", t));
                        done[0] = true;
                    } catch (Throwable ignored) {
                    }
                    l.countDown();
                }
            });
            try {
                l.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return done[0] ? ok("已放进手机剪贴板（" + t.length() + " 字）", null) : fail("写不了剪贴板");
        }
        if (PcRxPolicy.NOTIFY.equals(cmd)) {
            String title = PcRxPolicy.str(a, "title"), text = PcRxPolicy.str(a, "text");
            try {
                NotificationManager nm = channel(app);
                Notification n = builder(app).setSmallIcon(app.getApplicationInfo().icon)
                        .setContentTitle(title.length() > 0 ? title : "电脑提醒").setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build();
                nm.notify(NID_MSG + (int) (System.currentTimeMillis() % 1000), n);
                return ok("已在手机上提醒", null);
            } catch (Exception e) {
                return fail("通知发不出：" + e.getClass().getSimpleName());
            }
        }
        if (PcRxPolicy.STATUS.equals(cmd)) return status();
        return fail("手机不支持「" + cmd + "」");
    }

    PcLanClient.Result openApp(String pkg, String name) {
        PackageManager pm = app.getPackageManager();
        Intent launch = null;
        String label = pkg;
        if (pkg.length() > 0) {
            launch = pm.getLaunchIntentForPackage(pkg);
        } else {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> all = pm.queryIntentActivities(q, 0);
            ResolveInfo exact = null, part = null;
            String want = name.toLowerCase(Locale.ROOT).replace(" ", "");
            for (ResolveInfo ri : all) {
                CharSequence l = ri.loadLabel(pm);
                String s = l == null ? "" : l.toString().toLowerCase(Locale.ROOT).replace(" ", "");
                if (s.equals(want)) { exact = ri; break; }
                if (part == null && s.length() > 0 && (s.contains(want) || want.contains(s))) part = ri;
            }
            ResolveInfo hit = exact != null ? exact : part;
            if (hit != null) {
                launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName);
                label = String.valueOf(hit.loadLabel(pm));
            }
        }
        if (launch == null) return fail("手机上没找到这个 App");
        try {
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return ok("已打开 " + label, null);
        } catch (Exception e) {
            return fail("打不开：" + e.getClass().getSimpleName());
        }
    }

    PcLanClient.Result status() {
        Map<String, Object> d = new TreeMap<String, Object>();
        try {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) d.put("battery", Long.valueOf(Math.round(level * 100f / scale)));
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                d.put("charging", Boolean.valueOf(st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL));
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            d.put("screen_on", Boolean.valueOf(pm.isInteractive()));
        } catch (Exception ignored) {
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int rm = am.getRingerMode();
            d.put("ringer", rm == AudioManager.RINGER_MODE_SILENT ? "静音" : rm == AudioManager.RINGER_MODE_VIBRATE ? "振动" : "响铃");
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max > 0) d.put("music_volume", Long.valueOf(Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)));
        } catch (Exception ignored) {
        }
        d.put("model", Build.MODEL);
        d.put("android", Build.VERSION.RELEASE);
        return ok("手机状态", d);
    }

    static PcLanClient.Result ok(String text, Map<String, Object> data) {
        return new PcLanClient.Result(true, text, data, "");
    }

    static PcLanClient.Result fail(String text) {
        return new PcLanClient.Result(false, text, null, "");
    }

    static NotificationManager channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "电脑发来的", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // 找手机自己放声音；普通提醒不额外响
            nm.createNotificationChannel(ch);
        }
        return nm;
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
    }

    // ================================================================== 找手机

    static final class Ring {
        private static MediaPlayer mp;
        private static int savedAlarm = -1;
        private static final Handler H = new Handler(Looper.getMainLooper());
        private static BroadcastReceiver stopRx;
        private static final Runnable AUTO_STOP = new Runnable() {
            @Override public void run() {
                if (appRef != null) stop(appRef);
            }
        };
        private static Context appRef;

        static synchronized boolean start(final Context app, int seconds) {
            stop(app);
            appRef = app;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (u == null) return false;
            try {
                savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (Exception e) {
                savedAlarm = -1; // 勿扰等原因改不了音量：照样按当前闹钟音量响
            }
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mp.setDataSource(app, u);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                Log.w(TAG, "[PCLINK] 找手机放不了铃声: " + e.getClass().getSimpleName());
                release(app);
                return false;
            }
            try {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 800, 600}, 0);
            } catch (Exception ignored) {
            }
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "onesay:pclink-ring");
                wl.acquire(Math.min(seconds, 60) * 1000L);
            } catch (Exception ignored) {
            }
            try {
                if (stopRx == null) {
                    stopRx = new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent i) { stop(c.getApplicationContext()); }
                    };
                    app.registerReceiver(stopRx, new IntentFilter(ACTION_STOP));
                }
                PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(ACTION_STOP).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = builder(app).setSmallIcon(app.getApplicationInfo().icon).setContentTitle("电脑在找这台手机")
                        .setContentText("点这里停止响铃").setContentIntent(pi).setDeleteIntent(pi).setOngoing(true)
                        .setCategory(Notification.CATEGORY_ALARM).build();
                channel(app).notify(NID_RING, n);
            } catch (Exception ignored) {
            }
            H.removeCallbacks(AUTO_STOP);
            H.postDelayed(AUTO_STOP, seconds * 1000L);
            Log.i(TAG, "[PCLINK] 找手机：响铃 " + seconds + " 秒");
            return true;
        }

        static synchronized boolean stop(Context app) {
            boolean was = mp != null;
            H.removeCallbacks(AUTO_STOP);
            release(app);
            return was;
        }

        private static void release(Context app) {
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) { }
                try { mp.release(); } catch (Exception ignored) { }
                mp = null;
            }
            try {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.cancel();
            } catch (Exception ignored) {
            }
            if (savedAlarm >= 0) {
                try {
                    ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0);
                } catch (Exception ignored) {
                }
                savedAlarm = -1;
            }
            try {
                ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID_RING);
            } catch (Exception ignored) {
            }
        }
    }
}
