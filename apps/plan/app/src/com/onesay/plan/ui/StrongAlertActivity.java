package com.onesay.plan.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.data.Settings;
import com.onesay.plan.remind.StrongRingService;

import java.util.TimeZone;

/**
 * [borrow-1112] 待办强提醒的整屏页（锁屏上也显示、点亮屏幕、常亮）。按钮：完成 / 稍后 N 分钟 / 停止；
 * 音量键 = 稍后，电源键 = 停止（服务收屏幕熄灭广播处理）。返回键不关（防误触），只能选一个按钮。
 * 思路来源：涛声 TaoSheng（MIT），代码自写。
 */
public final class StrongAlertActivity extends Activity {
    public static final String A_CLOSE = "com.onesay.plan.strong.CLOSE";
    private long id;
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (StrongRingService.ringingId != id) finish(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        registerReceiver(close, new IntentFilter(A_CLOSE));
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        show(i);
    }

    void show(Intent in) {
        id = in.getLongExtra(StrongRingService.X_ID, 0);
        if (id == 0 || StrongRingService.ringingId != id) { finish(); return; }   // 过期的整屏意图（已经停了）
        Ui ui = new Ui(this);
        LinearLayout c = ui.col();
        c.setBackgroundColor(Color.BLACK);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(ui.dp(28), ui.dp(96), ui.dp(28), ui.dp(40));
        TextView tag = ui.text("待办 · 强提醒", 15, 0xB3FFFFFF, Ui.MEDIUM);
        tag.setGravity(Gravity.CENTER);
        c.addView(tag);
        TextView title = ui.text(in.getStringExtra(StrongRingService.X_TITLE) == null ? "" : in.getStringExtra(StrongRingService.X_TITLE), 30, Color.WHITE, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ui.dp(18), 0, ui.dp(8));
        c.addView(title);
        String due = StrongAlert.dueText(in.getLongExtra(StrongRingService.X_DUE, 0), TimeZone.getDefault());
        TextView sub = ui.text((due.length() > 0 ? due + " · " : "") + "音量键稍后，电源键停止", 15, 0x99FFFFFF, null);
        sub.setGravity(Gravity.CENTER);
        c.addView(sub);
        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        c.addView(button(ui, "完成", 0xFF1FA463, StrongRingService.A_DONE), ui.match(8, 8));
        c.addView(button(ui, "稍后 " + StrongAlert.snoozeMin(Settings.strongSnoozeMin(this)) + " 分钟", 0xFF3A3A3C, StrongRingService.A_SNOOZE), ui.match(8, 8));
        c.addView(button(ui, "停止", 0xFF3A3A3C, StrongRingService.A_STOP), ui.match(8, 8));
        setContentView(c);
    }

    TextView button(Ui ui, String s, int color, final String action) {
        TextView t = ui.text(s, 18, Color.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(16), 0, ui.dp(16));
        t.setBackground(ui.round(color, 28));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(action); }
        });
        return t;
    }

    void send(String action) {
        startService(StrongRingService.action(this, action, id));
        finish();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        StrongAlert.Key k = StrongAlert.key(keyCode);
        if (k == StrongAlert.Key.SNOOZE) { send(StrongRingService.A_SNOOZE); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;   // 返回键不关，免得误触后以为处理过了
        return super.onKeyDown(keyCode, e);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(close); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
