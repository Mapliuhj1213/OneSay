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
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.data.Settings;
import com.onesay.plan.remind.StrongRingService;

import java.util.List;
import java.util.TimeZone;

/**
 * [borrow-1112] 待办强提醒的整屏页（锁屏上也显示、点亮屏幕、常亮）。列出所有正在响的待办（并表同时提醒），每条有自己的「完成」；
 * 底部「稍后 N 分钟」/「停止」对全部在响的生效。音量键 = 全部稍后，电源键 = 全部停止（服务收屏幕熄灭广播处理）。
 * 某条完成后其余继续响、页面刷新；全部完成 / 稍后 / 停止后页面自己关。返回键不关（防误触），只能选按钮。
 * 思路来源：涛声 TaoSheng（MIT），代码自写。
 */
public final class StrongAlertActivity extends Activity {
    /** 服务通知整屏页「在响的变了」：还有就刷新列表，没了就关。 */
    public static final String A_CLOSE = "com.onesay.plan.strong.CLOSE";
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { render(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        registerReceiver(close, new IntentFilter(A_CLOSE));
        render();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        render();
    }

    void render() {
        List<StrongAlert.Entry> l = StrongRingService.ringingEntries(this);
        if (l.isEmpty()) { finish(); return; }   // 过期的整屏意图（已经停了）
        boolean many = l.size() > 1;
        Ui ui = new Ui(this);
        LinearLayout c = ui.col();
        c.setBackgroundColor(Color.BLACK);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(ui.dp(28), ui.dp(many ? 64 : 96), ui.dp(28), ui.dp(40));
        TextView tag = ui.text(many ? "待办 · 强提醒 · " + l.size() + " 条同时到点" : "待办 · 强提醒", 15, 0xB3FFFFFF, Ui.MEDIUM);
        tag.setGravity(Gravity.CENTER);
        c.addView(tag);
        if (!many) {
            StrongAlert.Entry e = l.get(0);
            TextView title = ui.text(e.title, 30, Color.WHITE, Ui.MEDIUM);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, ui.dp(18), 0, ui.dp(8));
            c.addView(title);
            String due = StrongAlert.dueText(e.dueAt, TimeZone.getDefault());
            TextView sub = ui.text((due.length() > 0 ? due + " · " : "") + "音量键稍后，电源键停止", 15, 0x99FFFFFF, null);
            sub.setGravity(Gravity.CENTER);
            c.addView(sub);
            View spacer = new View(this);
            c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
            c.addView(button(ui, "完成", 0xFF1FA463, StrongRingService.A_DONE, e.todoId), ui.match(8, 8));
        } else {
            TextView sub = ui.text("每条点「完成」勾掉，其余继续响；音量键全部稍后，电源键全部停止", 14, 0x99FFFFFF, null);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, ui.dp(8), 0, ui.dp(16));
            c.addView(sub);
            LinearLayout list = ui.col();
            for (StrongAlert.Entry e : l) list.addView(row(ui, e), ui.match(6, 6));
            ScrollView sv = new ScrollView(this);
            sv.addView(list);
            c.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        c.addView(button(ui, (many ? "全部稍后 " : "稍后 ") + StrongAlert.snoozeMin(Settings.strongSnoozeMin(this)) + " 分钟", 0xFF3A3A3C, StrongRingService.A_SNOOZE, 0), ui.match(8, 8));
        c.addView(button(ui, many ? "全部停止" : "停止", 0xFF3A3A3C, StrongRingService.A_STOP, 0), ui.match(8, 8));
        setContentView(c);
    }

    /** 多条时的一行：标题 + 截止时间 + 这条自己的「完成」。 */
    View row(Ui ui, StrongAlert.Entry e) {
        LinearLayout r = ui.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12));
        r.setBackground(ui.round(0xFF1C1C1E, 18));
        LinearLayout txt = ui.col();
        txt.addView(ui.text(e.title, 20, Color.WHITE, Ui.MEDIUM));
        String due = StrongAlert.dueText(e.dueAt, TimeZone.getDefault());
        if (due.length() > 0) txt.addView(ui.text(due, 13, 0x99FFFFFF, null));
        r.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = button(ui, "完成", 0xFF1FA463, StrongRingService.A_DONE, e.todoId);
        done.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10));
        done.setTextSize(16);
        r.addView(done);
        return r;
    }

    TextView button(Ui ui, String s, int color, final String action, final long todoId) {
        TextView t = ui.text(s, 18, Color.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(16), 0, ui.dp(16));
        t.setBackground(ui.round(color, 28));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(action, todoId); }
        });
        return t;
    }

    /** 发给响铃服务。单条完成不关页（其余还在响，服务会发广播刷新 / 关页）；全部稍后 / 停止直接关。 */
    void send(String action, long todoId) {
        startService(StrongRingService.action(this, action, todoId));
        if (!StrongRingService.A_DONE.equals(action)) finish();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        StrongAlert.Key k = StrongAlert.key(keyCode);
        if (k == StrongAlert.Key.SNOOZE) { send(StrongRingService.A_SNOOZE, 0); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;   // 返回键不关，免得误触后以为处理过了
        return super.onKeyDown(keyCode, e);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(close); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
