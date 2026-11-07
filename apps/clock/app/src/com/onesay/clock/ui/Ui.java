package com.onesay.clock.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * One UI 观感的小组件库（全代码，无 XML 布局）：浅灰底、26dp 圆角白卡片、大标题头（标题 + 副标题）、
 * 行（标题 / 副标题 / 右侧开关或值）、细分隔线、One UI 蓝强调色。控件本身用 DeviceDefault 主题，在三星机上就是三星原生样式。
 */
public final class Ui {
    private Ui() { }

    public static final int BG = 0xFFF6F6F6, CARD = 0xFFFFFFFF, T1 = 0xFF111111, T2 = 0xFF8A8A8E, T_OFF = 0xFFB9B9BD,
            ACCENT = 0xFF3E7BF2, DIV = 0x14000000, RIPPLE = 0x1F000000, WARN = 0xFFE5533D, OK = 0xFF2E9E5B;

    /** versionName（设置页标题、关于）。 */
    public static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static Typeface light() { return Typeface.create("sans-serif-light", Typeface.NORMAL); }

    public static Typeface regular() { return Typeface.create("sans-serif", Typeface.NORMAL); }

    public static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    public static void window(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    public static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static Drawable ripple(Drawable content, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, round(Color.WHITE, radiusPx));
    }

    /** 卡片容器（垂直）。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, dp(c, 26)));
        l.setClipToOutline(true);
        return l;
    }

    public static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = lp.rightMargin = dp(c, 12);
        lp.bottomMargin = dp(c, 12);
        return lp;
    }

    /** 卡片外的小节标题。 */
    public static TextView section(Context c, String s) {
        TextView t = text(c, s, 13, T2, medium());
        t.setPadding(dp(c, 28), dp(c, 14), dp(c, 28), dp(c, 8));
        return t;
    }

    /** One UI 大标题头：标题居中偏下 + 副标题，高 ~ 屏高 28%。 */
    public static LinearLayout header(Context c, String title, String sub) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setGravity(Gravity.CENTER);
        int ht = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.26f);
        h.setMinimumHeight(ht);
        TextView t = text(c, title, 34, T1, regular());
        t.setGravity(Gravity.CENTER);
        h.addView(t, new LinearLayout.LayoutParams(-2, -2));
        if (sub != null) {
            TextView s = text(c, sub, 14, T2, regular());
            s.setGravity(Gravity.CENTER);
            s.setTag("sub");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(c, 10);
            h.addView(s, lp);
        }
        return h;
    }

    /** 顶栏（返回 / 标题 / 右侧按钮），高 56dp。 */
    public static LinearLayout toolbar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        b.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        b.setMinimumHeight(dp(c, 56));
        return b;
    }

    public static ImageView iconBtn(Context c, Drawable d, String desc, View.OnClickListener l) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(desc);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, oval()));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return v;
    }

    static GradientDrawable oval() { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Color.WHITE); return g; }

    /** 行：标题 + 可选副标题 + 右侧控件。返回行 View；副标题 TextView 挂在 tag 上方便更新。 */
    public static LinearLayout row(Context c, String title, String sub, View right, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 24), dp(c, 15), dp(c, 20), dp(c, 15));
        r.setMinimumHeight(dp(c, 64));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, title, 17, T1, regular());
        col.addView(t);
        TextView s = text(c, sub == null ? "" : sub, 13.5f, ACCENT, regular());
        s.setVisibility(sub == null ? View.GONE : View.VISIBLE);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(c, 4);
        col.addView(s, slp);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.leftMargin = dp(c, 12);
            r.addView(right, rlp);
        }
        r.setTag(s);
        if (click != null) {
            r.setOnClickListener(click);
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(Color.WHITE, 0)));
        }
        return r;
    }

    public static void setSub(View row, String s) {
        TextView t = (TextView) row.getTag();
        t.setText(s);
        t.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    public static void setSubColor(View row, int color) { ((TextView) row.getTag()).setTextColor(color); }

    public static Switch sw(Context c, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(c);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, 0xFFFAFAFA}));
        s.setTrackTintList(new ColorStateList(st, new int[]{ACCENT & 0x80FFFFFF | 0x80000000, 0x42000000}));
        s.setChecked(on);
        final CompoundButton.OnCheckedChangeListener inner = l;
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Haptics.fire(b, com.onesay.clock.core.HapticMap.Ev.SWITCH_TOGGLE);   // 旧版：无（表里切换即生效）
                if (inner != null) inner.onCheckedChanged(b, v);
            }
        });
        return s;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f)));
        lp.leftMargin = dp(c, 24);
        lp.rightMargin = dp(c, 24);
        v.setLayoutParams(lp);
        return v;
    }

    /** 往卡片里加行（自动在行之间加分隔线）。 */
    public static <T extends View> T add(LinearLayout card, T row) {
        if (card.getChildCount() > 0) card.addView(divider(card.getContext()));
        card.addView(row);
        return row;
    }

    /** 底部双按钮（取消 / 保存），One UI 文字按钮。 */
    public static LinearLayout bottomBar(Context c, String left, View.OnClickListener l, String right, View.OnClickListener r) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setBackgroundColor(BG);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 10));
        b.addView(textBtn(c, left, l), new LinearLayout.LayoutParams(0, dp(c, 52), 1f));
        b.addView(textBtn(c, right, r), new LinearLayout.LayoutParams(0, dp(c, 52), 1f));
        return b;
    }

    static TextView textBtn(Context c, String s, View.OnClickListener l) {
        TextView t = text(c, s, 17, T1, medium());
        t.setGravity(Gravity.CENTER);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(Color.WHITE, dp(c, 26))));
        final View.OnClickListener inner = l;
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Haptics.fire(v, com.onesay.clock.core.HapticMap.Ev.BUTTON_TAP); if (inner != null) inner.onClick(v); }
        });
        return t;
    }

    /** 小图形（代码绘制）：+、⋮、返回、齿轮。 */
    public static final class Glyph extends Drawable {
        final String kind;
        final int size;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Glyph(Context c, String kind, int color) {
            this.kind = kind;
            size = dp(c, 24);
            p.setColor(color);
            p.setStrokeWidth(dp(c, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
        }

        @Override public int getIntrinsicWidth() { return size; }

        @Override public int getIntrinsicHeight() { return size; }

        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float k = b.width() / 24f, x = b.left, y = b.top;
            if ("plus".equals(kind)) {
                c.drawLine(x + 5 * k, y + 12 * k, x + 19 * k, y + 12 * k, p);
                c.drawLine(x + 12 * k, y + 5 * k, x + 12 * k, y + 19 * k, p);
            } else if ("more".equals(kind)) {
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) c.drawCircle(x + 12 * k, y + (6 + 6 * i) * k, 1.7f * k, p);
                p.setStyle(Paint.Style.STROKE);
            } else if ("back".equals(kind)) {
                c.drawLine(x + 5 * k, y + 12 * k, x + 19 * k, y + 12 * k, p);
                c.drawLine(x + 5 * k, y + 12 * k, x + 11 * k, y + 6 * k, p);
                c.drawLine(x + 5 * k, y + 12 * k, x + 11 * k, y + 18 * k, p);
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }

        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }

    public static ViewGroup.LayoutParams wrap() { return new ViewGroup.LayoutParams(-2, -2); }
}
