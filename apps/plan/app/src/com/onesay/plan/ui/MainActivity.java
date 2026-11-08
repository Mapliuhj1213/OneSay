package com.onesay.plan.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.TabNav;
import com.onesay.plan.remind.Remind;

/**
 * 主界面：底部 6 个标签（今天 / 日程 / 待办 / 票夹 / 取件 / 自动），内容区是可滚动的大标题页。
 * 数据变化（自己改或 OneSay 经后门改）→ ContentObserver → 当前页重画。
 */
public final class MainActivity extends Activity {

    static final String[] TABS = TabNav.TABS;
    static final String[] LABELS = {"今天", "日程", "待办", "票夹", "取件", "自动"};

    Ui ui;
    Pages pages;
    String tab = TabNav.TODAY;
    FrameLayout content;
    LinearLayout tabBar;
    ScrollView scroll;
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable redraw = new Runnable() {
        @Override public void run() {
            render(false);
        }
    };
    ContentObserver obs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 伴侣连接：后台反查一次助手状态（只记日志 [LINK]，不阻塞界面；没装助手 = 什么都不做）
        final android.content.Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { com.onesay.plan.platform.AssistantLink.query(appCtx, com.onesay.plan.core.LinkContract.F_GET_ASSISTANT_STATUS); }
        }, "assistant-link").start();
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10 /* LIGHT_NAVIGATION_BAR (API 26) */);
        ui = new Ui(this);
        pages = new Pages(this);
        LinearLayout root = ui.col();
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabBar = ui.row();
        tabBar.setBackgroundColor(Color.WHITE);
        tabBar.setElevation(ui.dp(2));
        root.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(58)));
        setContentView(root);
        if (b != null) tab = b.getString("tab", tab);
        handleIntent(getIntent());
        Remind.channels(this);
        com.onesay.plan.platform.CapabilityProbe.probe(this);   // 启动时探测系统能力（只给设置页置灰 / 提示用，不影响提醒）
        Remind.schedule(this);
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean self, Uri uri) {
                h.removeCallbacks(redraw);
                h.postDelayed(redraw, 120);
                checkWatchedRun();
            }
        };
        getContentResolver().registerContentObserver(Uri.parse(Contract.BASE_URI), true, obs);
        render(true);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
        render(true);
    }

    // 计划 App 沉浸全屏时 OneSay 的岛被隐藏，点「运行」的结果只剩通知 → 盯住这次请求，结束时 App 内提示
    long watchRunId;
    String watchRunName;
    long watchRunUntil;

    void watchShortcutRun(long runId, String name) {
        if (runId <= 0) return;
        watchRunId = runId;
        watchRunName = name;
        watchRunUntil = System.currentTimeMillis() + com.onesay.plan.core.Shortcuts.WATCH_MS;
    }

    void checkWatchedRun() {
        if (watchRunId <= 0) return;
        if (System.currentTimeMillis() > watchRunUntil) {
            watchRunId = 0;
            return;
        }
        com.onesay.plan.core.Row run = com.onesay.plan.data.Store.get(this, Contract.T_SHORTCUT_RUNS, watchRunId);
        if (run == null) {
            watchRunId = 0;
            return;
        }
        String t = com.onesay.plan.core.Shortcuts.finishedText(watchRunName, run);
        if (t == null) return;
        watchRunId = 0;
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show();
    }

    /** 0.1.4：没指定页签的打开（桌面图标、OneSay「打开计划」）一律回到「今天」；指定了就去指定页。 */
    void handleIntent(Intent i) {
        if (i == null) return;
        tab = TabNav.resolve(tab, i.getStringExtra(TabNav.EXTRA_TAB));
    }

    /** 0.1.4：跨天自动刷新。前台时排到下一个 0 点（+1 秒）重画；改时间/时区/日期变化广播也重画；回前台发现跨天也重画。 */
    long shownDay = -1;
    final Runnable midnight = new Runnable() {
        @Override public void run() {
            render(false);
            armMidnight();
        }
    };
    final android.content.BroadcastReceiver dayRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent in) {
            render(false);
            armMidnight();
        }
    };

    static long dayKey(long now) {
        return TabNav.dayKey(now, java.util.TimeZone.getDefault());
    }

    void armMidnight() {
        h.removeCallbacks(midnight);
        h.postDelayed(midnight, TabNav.delayToNextMidnight(System.currentTimeMillis(), java.util.TimeZone.getDefault()));
    }

    /** 自己开的页（票详情、选图等）回来不算「重新打开」。 */
    boolean internalNav;

    @Override public void startActivityForResult(Intent i, int req, Bundle opts) {
        internalNav = true;
        super.startActivityForResult(i, req, opts);
    }

    /**
     * 0.1.4：从后台回来（桌面图标、OneSay「打开计划」、最近任务）一律回到「今天」。
     * 桌面图标把已有任务拉到前台时系统不发 onNewIntent，所以在 onRestart 里重置；
     * 带页签的意图随后在 onNewIntent 里覆盖（顺序 onRestart → onStart → onNewIntent → onResume）。
     */
    @Override protected void onRestart() {
        super.onRestart();
        tab = TabNav.afterRestart(tab, internalNav);
        internalNav = false;
    }

    @Override protected void onResume() {
        super.onResume();
        render(false);
        armMidnight();
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(dayRx, f);
    }

    @Override protected void onPause() {
        h.removeCallbacks(midnight);
        try { unregisterReceiver(dayRx); } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tab", tab);
    }

    @Override protected void onDestroy() {
        if (obs != null) getContentResolver().unregisterContentObserver(obs);
        super.onDestroy();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Editors.REQ_QR && res == RESULT_OK && data != null) pages.ed.onQrPicked(data.getData());
    }

    @Override public void onBackPressed() {
        if (!TabNav.TODAY.equals(tab)) {
            tab = TabNav.TODAY;
            render(true);
            return;
        }
        super.onBackPressed();
    }

    void go(String t) {
        tab = t;
        render(true);
    }

    void render(boolean resetScroll) {
        long d = dayKey(System.currentTimeMillis());
        if (shownDay != -1 && d != shownDay) resetScroll = true;   // 跨天：内容整页换了，回到顶
        shownDay = d;
        int y = !resetScroll && scroll != null ? scroll.getScrollY() : 0;
        content.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = ui.col();
        col.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(96));
        scroll.addView(col);
        content.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View.OnClickListener add = pages.build(tab, col);
        if (add != null) {
            TextView fab = ui.fab(add);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.BOTTOM | Gravity.RIGHT);
            p.rightMargin = ui.dp(22);
            p.bottomMargin = ui.dp(22);
            content.addView(fab, p);
        }
        final int fy = y;
        if (fy > 0) scroll.post(new Runnable() {
            @Override public void run() {
                scroll.scrollTo(0, fy);
            }
        });
        drawTabs();
    }

    void drawTabs() {
        tabBar.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final String t = TABS[i];
            boolean on = t.equals(tab);
            LinearLayout cell = ui.col();
            cell.setGravity(Gravity.CENTER);
            TextView tv = ui.text(LABELS[i], 14.5f, on ? Ui.TEXT : Ui.TEXT3, on ? Ui.MEDIUM : null);
            tv.setGravity(Gravity.CENTER);
            cell.addView(tv);
            View bar = new View(this);
            bar.setBackground(ui.round(on ? Ui.ACCENT : Color.TRANSPARENT, 2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(3));
            bp.topMargin = ui.dp(5);
            cell.addView(bar, bp);
            cell.setBackground(ui.ripple(null, ui.round(Color.WHITE, 0)));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    go(t);
                }
            });
            cell.setContentDescription(LABELS[i]);
            tabBar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
    }
}
