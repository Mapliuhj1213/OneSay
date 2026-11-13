package com.onesay.clock.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.clock.L;
import com.onesay.clock.core.Health;

import java.util.List;

/**
 * [borrow-1112] 闹钟可靠性自检页：精确闹钟权限、电池优化白名单、闹钟音量、勿扰、通知权限。每项有问题就给一键跳转（或直接调好音量）。
 * 判定在 core/Health（JVM 测试）；这里只读系统状态、跳设置。思路来源：Wake Me（WeckMich，GPL-3.0）——只读思路，代码自写。
 */
public class CheckActivity extends Activity {
    private LinearLayout card;
    private TextView summary;

    int dp(float v) { return Ui.dp(this, v); }

    /** 读系统状态（读不到的留 null，Health 不报）。 */
    public static Health.Input probe(Context c) {
        Health.Input in = new Health.Input();
        in.sdk = Build.VERSION.SDK_INT;
        try {
            if (Build.VERSION.SDK_INT >= 31) {   // compileSdk 28：用反射调 canScheduleExactAlarms
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                in.exactAllowed = (Boolean) AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(am);
            }
        } catch (Throwable ignored) { }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            in.ignoringBattery = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable ignored) { }
        try {
            AudioManager au = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            in.alarmVolume = au.getStreamVolume(AudioManager.STREAM_ALARM);
            in.alarmVolumeMax = au.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            int f = nm.getCurrentInterruptionFilter();
            in.zen = f == NotificationManager.INTERRUPTION_FILTER_ALL ? Health.ZEN_OFF
                    : f == NotificationManager.INTERRUPTION_FILTER_PRIORITY ? Health.ZEN_PRIORITY
                    : f == NotificationManager.INTERRUPTION_FILTER_NONE ? Health.ZEN_NONE
                    : f == NotificationManager.INTERRUPTION_FILTER_ALARMS ? Health.ZEN_ALARMS : Health.ZEN_UNKNOWN;
            if (in.zen == Health.ZEN_PRIORITY) {
                if (Build.VERSION.SDK_INT >= 28) {
                    NotificationManager.Policy p = nm.getNotificationPolicy();
                    in.zenAllowsAlarms = p == null ? null : (p.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0;
                } else {
                    in.zenAllowsAlarms = true;   // Android 9 以前的优先模式总是放行闹钟
                }
            }
        } catch (Throwable ignored) { }
        try {
            in.notificationsEnabled = ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
        } catch (Throwable ignored) { }
        return in;
    }

    public static List<Health.Issue> issues(Context c) { return Health.evaluate(probe(c)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        LinearLayout header = Ui.header(this, "可靠性检查", "");
        summary = (TextView) header.findViewWithTag("sub");
        col.addView(header);
        card = Ui.card(this);
        col.addView(card, Ui.cardLp(this));
        TextView note = Ui.text(this, "检查项：闹钟和提醒权限、电池优化、闹钟音量、勿扰、通知。手机关机或时间、时区变化后，如果有闹钟没响，会补一条通知（不补响）。",
                13, Ui.T2, Ui.regular());
        note.setPadding(dp(28), dp(12), dp(28), 0);
        col.addView(note);
        sv.addView(col);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        outer.addView(Ui.bottomBar(this, "关闭", new View.OnClickListener() { public void onClick(View v) { finish(); } },
                "重新检查", new View.OnClickListener() { public void onClick(View v) { refresh(); } }));
        setContentView(outer);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();   // 从设置页回来自动重查
    }

    void refresh() {
        card.removeAllViews();
        Health.Input in = probe(this);
        List<Health.Issue> l = Health.evaluate(in);
        L.i(this, "CHECK " + l);
        summary.setText(l.isEmpty() ? "没有发现问题" : Health.banner(l).replace("，点这里检查", ""));
        if (l.isEmpty()) {
            Ui.add(card, Ui.row(this, "都正常", okDetail(in), null, null));
            return;
        }
        for (final Health.Issue i : l) {
            View r = Ui.add(card, Ui.row(this, i.title, i.detail + " · " + i.fix, null, new View.OnClickListener() {
                public void onClick(View v) { fix(i.code); }
            }));
            if (i.level >= Health.SEVERE) Ui.setSubColor(r, Ui.WARN);
        }
    }

    String okDetail(Health.Input in) {
        StringBuilder sb = new StringBuilder();
        if (in.alarmVolume != null && in.alarmVolumeMax != null) sb.append("闹钟音量 ").append(in.alarmVolume).append('/').append(in.alarmVolumeMax);
        if (in.ignoringBattery != null) sb.append(sb.length() > 0 ? " · " : "").append(in.ignoringBattery ? "已加入电池白名单" : "");
        return sb.toString();
    }

    void fix(String code) {
        String pkg = getPackageName();
        if (Health.VOLUME.equals(code)) {
            try {
                AudioManager au = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                au.setStreamVolume(AudioManager.STREAM_ALARM, Health.halfVolume(au.getStreamMaxVolume(AudioManager.STREAM_ALARM)), AudioManager.FLAG_SHOW_UI);
                refresh();
                return;
            } catch (Throwable t) {
                open(new Intent(Settings.ACTION_SOUND_SETTINGS), null);
                return;
            }
        }
        if (Health.EXACT.equals(code)) {
            open(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").setData(Uri.parse("package:" + pkg)), appDetails(pkg));
        } else if (Health.BATTERY.equals(code)) {
            open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + pkg)),
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } else if (Health.DND.equals(code)) {
            open(new Intent("android.settings.ZEN_MODE_SETTINGS"), new Intent(Settings.ACTION_SOUND_SETTINGS));
        } else if (Health.NOTIF.equals(code)) {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg), appDetails(pkg));
        }
    }

    static Intent appDetails(String pkg) { return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + pkg)); }

    void open(Intent first, Intent fallback) {
        try { startActivity(first); return; } catch (Throwable ignored) { }
        try { if (fallback != null) { startActivity(fallback); return; } } catch (Throwable ignored) { }
        Toast.makeText(this, "这台手机打不开对应的设置页，请到系统设置里手动调整", Toast.LENGTH_LONG).show();
    }
}
