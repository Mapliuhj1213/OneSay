package com.onesay.clock;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** 建通知渠道；首启后台预热件库（ogg → pcm 永久缓存，之后现拼开场不用解码）。 */
public class App extends Application {
    public static final String CH_ALARM = "bc_alarm_quiet", CH_WORK = "bc_work_quiet";
    static final String[] OLD_CHANNELS = {"bc_alarm", "bc_work"};

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            // 0.1.4：响铃与准备播报都静默（不弹横幅、不响不振，只在下拉栏留一条）。渠道重要性建后改不了，所以换新 id、删旧渠道。
            for (String old : OLD_CHANNELS) nm.deleteNotificationChannel(old);
            NotificationChannel a = new NotificationChannel(CH_ALARM, "闹钟响铃（静默）", NotificationManager.IMPORTANCE_LOW);
            a.setSound(null, null);
            a.enableVibration(false);
            a.setShowBadge(false);
            a.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(a);
            NotificationChannel w = new NotificationChannel(CH_WORK, "准备播报（静默）", NotificationManager.IMPORTANCE_LOW);
            w.setSound(null, null);
            w.enableVibration(false);
            w.setShowBadge(false);
            nm.createNotificationChannel(w);
        }
        migrate();
        try {
            // 朗读表（assets/phrases/zh-CN.json）：所有固定句与读法的唯一来源
            com.onesay.clock.core.Bank.install(new String(com.onesay.clock.content.Http.readAll(getAssets().open(com.onesay.clock.core.Bank.ASSET)), "UTF-8"));
        } catch (Exception e) {
            L.w(this, "PHRASE 朗读表加载失败 " + e.getClass().getSimpleName());
        }
    }

    /** 设置迁移（一次性）：清掉作废/改了默认值的键。 */
    void migrate() {
        android.content.SharedPreferences sp = getSharedPreferences("bc", MODE_PRIVATE);
        int v = sp.getInt(com.onesay.clock.core.Flags.CFG_VER, 1);
        if (v >= com.onesay.clock.core.Flags.CFG_VER_NOW) return;
        android.content.SharedPreferences.Editor e = sp.edit();
        for (String k : com.onesay.clock.core.Flags.dropKeys(v)) e.remove(k);
        e.putInt(com.onesay.clock.core.Flags.CFG_VER, com.onesay.clock.core.Flags.CFG_VER_NOW).commit();
        L.i(this, "CFG migrate " + v + "→" + com.onesay.clock.core.Flags.CFG_VER_NOW);
    }
}
