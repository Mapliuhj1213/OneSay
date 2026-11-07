package com.onesay.plan.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One UI 风格的小工具箱：浅灰底、白色大圆角卡片、大标题、蓝色强调色、文字层级 87% / 55% / 38%。界面全部用代码搭，不用 XML 布局。 */
public final class Ui {

    public static final int BG = 0xFFF4F4F6, CARD = 0xFFFFFFFF, TEXT = 0xDE000000, TEXT2 = 0x8C000000, TEXT3 = 0x61000000,
            LINE = 0x14000000, ACCENT = 0xFF0381FE, RED = 0xFFE5484D, GREEN = 0xFF1FA463;
    /** 每类一个颜色（左侧色条 / 图标底）。 */
    public static final int C_EVENT = 0xFF3E7BFA, C_TODO = 0xFFFF9500, C_TICKET = 0xFF8E5CF7, C_PICKUP = 0xFF1FA463, C_AUTO = 0xFF00A3B4;
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    public final Activity a;
    final float d;

    public Ui(Activity a) {
        this.a = a;
        this.d = a.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) { return Math.round(v * d); }

    public TextView text(CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(true);
        return t;
    }

    public TextView single(CharSequence s, float sp, int color, Typeface tf) {
        TextView t = text(s, sp, color, tf);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public LinearLayout col() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout row() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    public Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(0x1F000000), content, mask);
    }

    /** 白色大圆角卡片（One UI 26dp）。 */
    public LinearLayout card() {
        LinearLayout c = col();
        c.setBackground(round(CARD, 26));
        c.setPadding(0, dp(6), 0, dp(6));
        return c;
    }

    public LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public LinearLayout.LayoutParams match(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    public LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 大标题区（One UI 的展开式标题：大字靠下、留白多）。 */
    public View largeTitle(String title, String sub, View action) {
        FrameLayout f = new FrameLayout(a);
        LinearLayout c = col();
        c.setGravity(Gravity.BOTTOM);
        c.setPadding(dp(24), dp(56), dp(24), dp(18));
        c.addView(text(title, 34, TEXT, LIGHT));
        if (sub != null && sub.length() > 0) {
            TextView s = text(sub, 14, TEXT2, null);
            s.setPadding(0, dp(4), 0, 0);
            c.addView(s);
        }
        f.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (action != null) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.RIGHT);
            p.topMargin = dp(10);
            p.rightMargin = dp(8);
            f.addView(action, p);
        }
        return f;
    }

    /** 分组标题：彩色小圆点 + 文字 + 右侧数字。 */
    public View section(String title, int color, String right) {
        LinearLayout r = row();
        r.setPadding(dp(28), dp(18), dp(28), dp(8));
        View dot = new View(a);
        dot.setBackground(round(color, 4));
        r.addView(dot, lp(dp(8), dp(8)));
        TextView t = text(title, 14, TEXT2, MEDIUM);
        t.setPadding(dp(8), 0, 0, 0);
        r.addView(t, weight());
        if (right != null) r.addView(text(right, 13, TEXT3, null));
        return r;
    }

    /** 列表行：左侧色条 + 标题 / 副标题 + 右侧控件。 */
    public LinearLayout item(int color, CharSequence title, CharSequence sub, View right, boolean dim) {
        LinearLayout r = row();
        r.setPadding(dp(20), dp(12), dp(16), dp(12));
        r.setMinimumHeight(dp(60));
        View bar = new View(a);
        bar.setBackground(round(dim ? 0x33000000 : color, 2));
        LinearLayout.LayoutParams bp = lp(dp(4), dp(34));
        bp.rightMargin = dp(14);
        r.addView(bar, bp);
        LinearLayout tc = col();
        TextView t = single(title, 16.5f, dim ? TEXT3 : TEXT, null);
        tc.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = single(sub, 13.5f, dim ? TEXT3 : TEXT2, null);
            s.setPadding(0, dp(2), 0, 0);
            tc.addView(s);
        }
        r.addView(tc, weight());
        if (right != null) {
            ViewGroup.LayoutParams own = right.getLayoutParams();
            LinearLayout.LayoutParams rp = own != null && own.width > 0 ? lp(own.width, own.height) : lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.leftMargin = dp(10);
            r.addView(right, rp);
        }
        r.setBackground(ripple(null, round(Color.WHITE, 0)));
        return r;
    }

    public View divider() {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        p.leftMargin = dp(38);
        p.rightMargin = dp(20);
        v.setLayoutParams(p);
        return v;
    }

    /** 胶囊按钮（浅底强调色字）。 */
    public TextView pill(String s, int color, View.OnClickListener l) {
        TextView t = text(s, 13.5f, color, MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setBackground(ripple(round((color & 0x00FFFFFF) | 0x1A000000, 18), round(Color.WHITE, 18)));
        t.setOnClickListener(l);
        return t;
    }

    /** 小标签（如「逾期」「已过」）。 */
    public TextView tag(String s, int color) {
        TextView t = text(s, 11.5f, color, MEDIUM);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        t.setBackground(round((color & 0x00FFFFFF) | 0x1F000000, 10));
        return t;
    }

    /** 空状态：一句大白话 + 一句怎么加。 */
    public View empty(String line, String hint) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(24), dp(48), dp(24), dp(48));
        TextView t = text(line, 16, TEXT2, null);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        if (hint != null) {
            TextView h = text(hint, 13.5f, TEXT3, null);
            h.setGravity(Gravity.CENTER);
            h.setPadding(0, dp(6), 0, 0);
            c.addView(h);
        }
        return c;
    }

    /** 圆形图标按钮（文字图标，如「＋」「⚙」）。 */
    public TextView iconButton(String glyph, View.OnClickListener l) {
        TextView t = text(glyph, 22, TEXT, null);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(null, round(Color.WHITE, 24)));
        t.setOnClickListener(l);
        t.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48)));
        t.setMinWidth(dp(48));
        t.setMinHeight(dp(48));
        return t;
    }

    /** 右下角圆形「＋」悬浮按钮。 */
    public TextView fab(View.OnClickListener l) {
        TextView t = text("＋", 26, Color.WHITE, null);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ACCENT);
        GradientDrawable m = new GradientDrawable();
        m.setShape(GradientDrawable.OVAL);
        m.setColor(Color.WHITE);
        t.setBackground(ripple(g, m));
        t.setElevation(dp(6));
        t.setOnClickListener(l);
        t.setContentDescription("新建");
        return t;
    }
}
