package com.onesay.clock.ui;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.clock.Prefs;

/** 首次启动引导：把会影响「准时响、能播报」的系统设置逐项列出来，显示状态，一键去设置。 */
public class OnboardActivity extends Activity {
    private LinearLayout card;
    private Prefs p;

    int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.window(this);
        p = new Prefs(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.header(this, "欢迎使用 OneSay Clock", "几项设置能让闹钟准时、播报完整"));
        card = Ui.card(this);
        col.addView(card, Ui.cardLp(this));
        TextView note = Ui.text(this, "闹钟本身不需要 root。三星天气读取是可选功能，在设置里打开。", 13, Ui.T2, Ui.regular());
        note.setPadding(dp(28), dp(4), dp(28), dp(16));
        col.addView(note);
        sv.addView(col);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView go = Ui.text(this, "开始使用", 17, 0xFFFFFFFF, Ui.medium());
        go.setGravity(Gravity.CENTER);
        go.setBackground(Ui.ripple(Ui.round(Ui.ACCENT, dp(26)), dp(26)));
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { p.setOnboarded(); finish(); } });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(52));
        glp.setMargins(dp(24), dp(8), dp(24), dp(20));
        outer.addView(go, glp);
        setContentView(outer);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    void render() {
        card.removeAllViews();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm.isIgnoringBatteryOptimizations(getPackageName());
        item("电池优化白名单", battery, battery ? "已加入：后台准备播报不会被系统推迟" : "建议加入：否则夜里可能来不及准备播报", new Runnable() {
            public void run() {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Throwable t) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            }
        });
        boolean noti = ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled();
        item("通知", noti, noti ? "已允许：锁屏上能弹出响铃页" : "需要允许：响铃页靠全屏通知弹出", new Runnable() {
            public void run() { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        });
        boolean loc = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        item("大致位置", loc, loc ? "已允许：天气跟随你所在的城市" : "可选：不允许也行，在设置里选一个城市", new Runnable() {
            public void run() { requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1); }
        });
        boolean exact = Build.VERSION.SDK_INT < 31;
        item("精确闹钟", exact, exact ? "本机 Android " + Build.VERSION.RELEASE + " 无需设置" : "需要在系统设置里允许", exact ? null : new Runnable() {
            public void run() { startActivity(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", Uri.parse("package:" + getPackageName()))); }
        });
        boolean full = Build.VERSION.SDK_INT < 34;
        item("全屏响铃页", full, full ? "本机无需单独授权" : "需要在系统设置里允许全屏通知", null);
        boolean key = p.bailianKey().length() > 0;
        item("云端语音（新闻朗读）", key, key ? "已配置" : "必填 · 未配置则不朗读（只响铃 / 配乐）；点此打开「API 密钥」", new Runnable() {
            public void run() { startActivity(new Intent(OnboardActivity.this, KeysActivity.class)); }
        });
    }

    void item(String title, boolean ok, String sub, final Runnable fix) {
        TextView st = Ui.text(this, ok ? "已完成" : (fix == null ? "—" : "去设置"), 13, ok ? Ui.OK : Ui.ACCENT, Ui.medium());
        st.setPadding(dp(10), dp(5), dp(10), dp(5));
        st.setBackground(Ui.round((ok ? Ui.OK : Ui.ACCENT) & 0x1AFFFFFF, dp(12)));
        View r = Ui.add(card, Ui.row(this, title, sub, st, ok || fix == null ? null : new View.OnClickListener() { public void onClick(View v) { fix.run(); } }));
        Ui.setSubColor(r, ok ? Ui.T2 : Ui.T2);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p2, int[] g) { render(); }
}
