package com.onesay.clock.ring;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.AlertUi;
import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.WeatherCode;
import com.onesay.clock.content.Weather;
import com.onesay.clock.gen.Briefer;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;

/**
 * 全屏响铃页 —— 智能播报闹钟的全屏响铃页，以参考视频为准：
 *   背景：RadialGradientView 三层径向光晕（中心/剖面/呼吸按原版；只有光团颜色与蓝层范围参考视频调，见 RingViews.Bg）+ 入场/4 s 呼吸，底色黑；
 *   控件颜色一律取原版 res/values 原值（对照表见 <内部路径>），不再凭视频取色；
 *   顶部：FullAlertTopIcon —— 45dip 高、左边距 36dip、14sp #8CFFFFFF、小时钟图标 + 4dip、「闹钟」；
 *   上半区（6/13，内容整体居中）：闹钟名 25dip #FAFAFA → 时间 64dip RobotoNum-3L（margin −1dip）→ 日期 20dip #73FAFAFA（margin −4dip）
 *     → 天气线条图标 50dip、margin-top 20dip、白色（拿不到天气 GONE，整块重新居中）；左右各留 7.5%；
 *   下半区（7/13）按 2/7、4/7、1/7：
 *     贪睡行（区顶部、左右 24dip）：38dip 圆形 −/+（图标 17dip）、胶囊 minWidth 156dip、minHeight 31dip + 上下 8dip、文字 17sp、左右 16dip、
 *       间距 14dip、圆角 23.5dip、底色 #268587FE、按压波纹 #38FAFAFA；
 *     停止圆环：105dip 黑底 + 1dip #99999BFF 描边、「停止」17sp、175dip 呼吸光晕、按住滑出 0.95×直径 解除；
 *     CP 行（区底部 30dip）：1px #4CFFFFFF 分隔线、「提供者：」11dip #7FFFFFFF、3dip、标志 + 来源名（#80FFFFFF）。
 * 移植自 v2 ac0e72b + 9bb14f0（WIP 的解除崩溃修复已核对：回调叫 onStopFired，不覆盖 Activity.onStop）；停止/贪睡改发给 RingService。
 * 非播报响铃（贪睡、当天非第一个、非播报闹钟）不显示天气和提供者行，与三星一致。
 */
public class RingActivity extends Activity implements RingViews.StopHost {
    static final int TEXT = 0xFFFAFAFA, DATE = 0x73FAFAFA, TOP_ICON = 0x8CFFFFFF, CP_TEXT = 0x7FFFFFFF, CP_LOGO = 0x80FFFFFF,
            DIVIDER = 0x4CFFFFFF, RIPPLE = 0x38FAFAFA;
    /**
     * 贪睡胶囊与 −/+ 底色 = 原版 clock_alert_optional_button_color #268587FE（约 15% 白偏蓝的半透明「毛玻璃」）。
     * 视频里按钮发蓝是背后光团透出来的，不是按钮本色；原版没有真模糊，这里同样只叠半透明。
     */
    static final int BTN_BG = 0x268587FE;

    private int id, volumeKey;
    private boolean done, preview;
    private final BroadcastReceiver doneRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { done = true; finish(); }
    };
    private int snoozeMin = AlertUi.SNOOZE_MIN;
    private final Handler h = new Handler();
    private TextView timeV, dateV, snzText;
    private View minusV, plusV, snzRow;
    private RingViews.PlusMinus minusG, plusG;
    private RingViews.Bg bg;

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private static Typeface numFont;

    /** 数字字体（可选）：res/raw 里的 ttf 复制到 files/ 后加载（minSdk 21 用不了 res/font）。 */
    static synchronized Typeface numFont(Context c) {
        if (numFont != null) return numFont;
        try {
            File f = new File(c.getFilesDir(), "RobotoNum-3L.ttf");
            // 开源版：三星 RobotoNum-3L 字体不随仓库分发（第三方字体）。自备可许可的字体放 res/raw/robotonum_3l.ttf 即用；没有就用系统细体。
            int rid = c.getResources().getIdentifier("robotonum_3l", "raw", c.getPackageName());
            if (rid == 0) throw new java.io.FileNotFoundException("res/raw/robotonum_3l");
            InputStream in = c.getResources().openRawResource(rid);
            try {
                byte[] buf = new byte[16384];
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] all = bo.toByteArray();
                if (!f.isFile() || f.length() != all.length) {
                    OutputStream out = new FileOutputStream(f);
                    try { out.write(all); } finally { out.close(); }
                }
            } finally { in.close(); }
            numFont = Typeface.createFromFile(f);
        } catch (Throwable t) {
            numFont = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        return numFont;
    }

    private TextView text(String s, float size, int unit, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(unit, size);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        id = getIntent().getIntExtra("id", 0);
        preview = getIntent().getBooleanExtra("preview", false);
        String mode = String.valueOf(getIntent().getStringExtra("mode"));
        boolean briefing = "BRIEF".equals(mode) || "LIVE".equals(mode);
        Prefs prefs = new Prefs(this);
        Alarm al = prefs.alarm(id);
        volumeKey = prefs.i(Flags.VOLUME_KEY, Flags.VOLUME_KEY_DEF);
        snoozeMin = AlertUi.snoozeStart(al != null ? al.snoozeMin : AlertUi.SNOOZE_MIN);
        BriefMeta m = "BRIEF".equals(mode) ? Briefer.load(this) : null;
        String wxKey = null;
        if (briefing) {
            if (m != null && m.wxKey.length() > 0) wxKey = m.wxKey;
            else { Weather.Facts f = Weather.cached(this, Weather.CACHE_OK_MS); if (f != null) wxKey = f.key; }
        }
        int sw = getResources().getDisplayMetrics().widthPixels;

        FrameLayout root = new FrameLayout(this);
        bg = new RingViews.Bg(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(24 + 3), 0, 0);                  // 状态栏 24dip（全屏时空出）+ 3dip，同 mock_layout_px
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        Typeface sysReg = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface sysLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface sysMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        // ---- 顶部小图标 + 「闹钟」
        TextView top = text("闹钟", 14, TypedValue.COMPLEX_UNIT_SP, TOP_ICON, sysReg);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setCompoundDrawablesRelativeWithIntrinsicBounds(new RingViews.ClockGlyph(TOP_ICON, dp(16)), null, null, null);
        top.setCompoundDrawablePadding(dp(4));
        top.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dp(45));
        tlp.setMarginStart(dp(36));
        tlp.setMarginEnd(dp(36));
        col.addView(top, tlp);

        // ---- 上半区 6/13
        FrameLayout upper = new FrameLayout(this);
        LinearLayout blk = new LinearLayout(this);
        blk.setOrientation(LinearLayout.VERTICAL);
        blk.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Math.round(sw * 0.075f);
        TextView name = text(AlertUi.alarmName(al != null ? al.label : null), 25, TypedValue.COMPLEX_UNIT_DIP, TEXT, sysLight);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        blk.addView(name, new LinearLayout.LayoutParams(-1, -2));
        timeV = text("", 64, TypedValue.COMPLEX_UNIT_DIP, TEXT, numFont(this));
        timeV.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = -dp(1);
        blk.addView(timeV, tp);
        dateV = text("", 20, TypedValue.COMPLEX_UNIT_DIP, DATE, sysLight);
        dateV.setGravity(Gravity.CENTER_HORIZONTAL);
        dateV.setMaxLines(1);
        dateV.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = -dp(4);
        blk.addView(dateV, dlp);
        Calendar now = Calendar.getInstance();
        String wx = WeatherCode.icon(wxKey, now.get(Calendar.HOUR_OF_DAY));
        if (wx != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new RingViews.WxGlyph(wx, 0xFFFFFFFF, dp(50)));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setContentDescription(wx);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(50), dp(50));
            ilp.topMargin = dp(20);
            blk.addView(icon, ilp);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        blp.leftMargin = blp.rightMargin = side;
        upper.addView(blk, blp);
        col.addView(upper, new LinearLayout.LayoutParams(-1, 0, 6f));

        // ---- 下半区 7/13
        LinearLayout lower = new LinearLayout(this);
        lower.setOrientation(LinearLayout.VERTICAL);
        col.addView(lower, new LinearLayout.LayoutParams(-1, 0, 7f));

        // 贪睡 2/7
        FrameLayout snzArea = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        snzRow = row;
        minusG = new RingViews.PlusMinus(false, dp(17));
        plusG = new RingViews.PlusMinus(true, dp(17));
        minusV = roundBtn(minusG, "减 5 分钟");
        plusV = roundBtn(plusG, "加 5 分钟");
        minusV.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { com.onesay.clock.ui.Haptics.fire(v, com.onesay.clock.core.HapticMap.Ev.SNOOZE_STEP); step(false); } });
        plusV.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { com.onesay.clock.ui.Haptics.fire(v, com.onesay.clock.core.HapticMap.Ev.SNOOZE_STEP); step(true); } });
        row.addView(minusV, new LinearLayout.LayoutParams(dp(38), dp(38)));
        snzText = text("", 17, TypedValue.COMPLEX_UNIT_SP, TEXT, sysMed);
        snzText.setGravity(Gravity.CENTER);
        snzText.setMaxLines(2);
        snzText.setEllipsize(TextUtils.TruncateAt.END);
        snzText.setPadding(dp(16), dp(8), dp(16), dp(8));
        snzText.setMinWidth(dp(156));
        snzText.setMinimumWidth(dp(156));
        snzText.setMinHeight(dp(31 + 16));
        snzText.setMinimumHeight(dp(31 + 16));
        snzText.setBackground(ripple(false));
        snzText.setClickable(true);
        snzText.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { com.onesay.clock.ui.Haptics.fire(v, com.onesay.clock.core.HapticMap.Ev.SNOOZE_TAP); snooze(); } });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = plp.rightMargin = dp(14);
        row.addView(snzText, plp);
        row.addView(plusV, new LinearLayout.LayoutParams(dp(38), dp(38)));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rlp.leftMargin = rlp.rightMargin = dp(24);
        snzArea.addView(row, rlp);
        lower.addView(snzArea, new LinearLayout.LayoutParams(-1, 0, 2f));
        if (al != null && !al.snoozeOn) row.setVisibility(View.INVISIBLE);
        refreshSnooze();

        // 停止 4/7
        lower.addView(new RingViews.Stop(this, this, "停止"), new LinearLayout.LayoutParams(-1, 0, 4f));

        // CP 1/7（底部 30dip）
        FrameLayout cpArea = new FrameLayout(this);
        FrameLayout cpRow = new FrameLayout(this);
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        cpRow.addView(line, new FrameLayout.LayoutParams(-1, 1, Gravity.TOP));
        LinearLayout cp = new LinearLayout(this);
        cp.setOrientation(LinearLayout.HORIZONTAL);
        cp.setGravity(Gravity.CENTER_VERTICAL);
        TextView by = text("提供者：", 11, TypedValue.COMPLEX_UNIT_DIP, CP_TEXT, sysLight);
        LinearLayout.LayoutParams bylp = new LinearLayout.LayoutParams(-2, -2);
        bylp.setMarginEnd(dp(3));
        cp.addView(by, bylp);
        if (!briefing || m == null || m.newsSource.length() == 0) cpRow.setVisibility(View.INVISIBLE);
        TextView src = text(AlertUi.cpName(m != null ? m.newsSource : null), 11, TypedValue.COMPLEX_UNIT_DIP, CP_LOGO,
                Typeface.create("sans-serif", Typeface.BOLD));
        src.setCompoundDrawablesRelativeWithIntrinsicBounds(new RingViews.CpMark(CP_LOGO, dp(13)), null, null, null);
        src.setCompoundDrawablePadding(dp(3));
        cp.addView(src, new LinearLayout.LayoutParams(-2, -2));
        cpRow.addView(cp, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        cpArea.addView(cpRow, new FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM));
        lower.addView(cpArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        tick();
        registerReceiver(doneRx, new IntentFilter(RingService.DONE));
    }

    private android.graphics.drawable.Drawable ripple(boolean oval) {
        GradientDrawable fill = new GradientDrawable();
        GradientDrawable mask = new GradientDrawable();
        fill.setColor(BTN_BG);
        mask.setColor(Color.WHITE);
        if (oval) { fill.setShape(GradientDrawable.OVAL); mask.setShape(GradientDrawable.OVAL); }
        else { fill.setCornerRadius(dp(23.5f)); mask.setCornerRadius(dp(23.5f)); }
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), fill, mask);
    }

    private View roundBtn(RingViews.PlusMinus g, String desc) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(ripple(true));
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(g);
        f.addView(iv, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        return f;
    }

    private void step(boolean inc) {
        snoozeMin = AlertUi.snoozeStep(snoozeMin, inc);
        refreshSnooze();
    }

    private void refreshSnooze() {
        boolean mi = AlertUi.minusEnabled(snoozeMin), pl = AlertUi.plusEnabled(snoozeMin);
        minusV.setEnabled(mi);
        plusV.setEnabled(pl);
        minusG.setAlpha(Math.round(255 * 0.75f * (mi ? 1f : 0.2f)));
        plusG.setAlpha(Math.round(255 * 0.75f * (pl ? 1f : 0.2f)));
        String s = AlertUi.snoozeLabel(snoozeMin);
        snzText.setText(s);
        snzText.setContentDescription(s + " 按钮");
    }

    private final Runnable ticker = new Runnable() { public void run() { tick(); } };

    private void tick() {
        Calendar c = Calendar.getInstance();
        timeV.setText(AlertUi.timeLabel(c));
        dateV.setText(AlertUi.dateLabel(c));
        h.postDelayed(ticker, 60_000L - System.currentTimeMillis() % 60_000L + 50);
    }

    @Override public void onGrab(boolean grabbed) {           // 按住停止圆环时贪睡行淡出（fade_out 500 ms），松手淡入
        snzRow.animate().cancel();
        snzRow.animate().alpha(grabbed ? 0f : 1f).setDuration(500).start();
        snzRow.setEnabled(!grabbed);
    }

    /** 停止圆环滑到阈值。注意不能叫 onStop：那会覆盖 Activity.onStop()（不调 super → SuperNotCalledException，且一息屏就被解除）。 */
    @Override public void onStopFired() { dismiss(); }

    @Override public void onHapticDone() { if (!done) RingService.action(this, RingService.A_REVIB, 0); }

    void dismiss() {
        if (done) return;
        done = true;
        RingService.action(this, RingService.A_STOP, 0);
        finish();
    }

    void snooze() {
        if (done) return;
        done = true;
        RingService.action(this, RingService.A_SNOOZE, snoozeMin);
        finish();
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) return true;          // 与三星一致：返回键不解除
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {   // 电源键永不处理（P2-5）
            if (volumeKey == 1) { snooze(); return true; }
            if (volumeKey == 2) { dismiss(); return true; }
        }
        return super.onKeyDown(k, e);
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(doneRx); } catch (Throwable ignored) { }
        h.removeCallbacks(ticker);
        if (bg != null) bg.running = false;
        super.onDestroy();
    }
}
