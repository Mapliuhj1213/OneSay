package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onesay.assistant.v2.kernel.ConsoleHost;

/**
 * [console R12] 控件工厂（方案 §6）：Hero、状态胶囊、提醒条、大卡、分组、各种行、主开关条、页脚、顶栏。
 * 全部 GradientDrawable + 代码，不加 XML 资源、不碰 Manifest。颜色只从 {@link ConsoleTheme} 取。
 */
public final class ConsoleKit {

    public static final int W_REGULAR = 0, W_MEDIUM = 1, W_BOLD = 2;

    public final Activity act;
    public final ConsoleTheme t;
    private final float mD;
    private static Typeface sMedium;

    public ConsoleKit(Activity act, ConsoleTheme t) {
        this.act = act;
        this.t = t;
        this.mD = act.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) {
        return Math.round(v * mD);
    }

    // ------------------------------------------------------------------ 基础

    public GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    /** 点按水波（遮罩 = 自身范围；分组容器 clipToOutline 保证圆角不被画出去）。 */
    public Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(ConsoleTheme.alpha(t.text1, t.dark ? 0.10f : 0.08f)), content,
                content != null ? null : new ColorDrawable(0xFFFFFFFF));
    }

    static Typeface medium() {
        if (sMedium == null) sMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return sMedium;
    }

    public TextView text(String s, int sp, int color, int weight) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(true);
        // 中文行高约加 7%（M3：中文属于 medium 语言高度）
        tv.setLineSpacing(0f, 1.12f);
        if (weight == W_BOLD) tv.setTypeface(Typeface.DEFAULT_BOLD);
        else if (weight == W_MEDIUM) tv.setTypeface(medium());
        return tv;
    }

    public LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public LinearLayout.LayoutParams lpMatch(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    public LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout rowLayout() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 页面骨架：ScrollView（页面底色）> 竖直列（左右 16dp，曲面屏下文字距屏幕边 ≥ 32dp）。 */
    public ScrollView scroll(LinearLayout col) {
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(t.bg);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        col.setPadding(dp(ConsoleTheme.PAD_SCREEN), dp(4), dp(ConsoleTheme.PAD_SCREEN), dp(32));
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 按下缩到 0.98、松开回 1（方案 §7 点按反馈）。 */
    public void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (!ConsoleNav.animationsOn(act)) return false;
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ 顶栏

    /** 首页大标题：左「OneSay」、右版本小字。 */
    public View largeTitle(String title, String right) {
        LinearLayout bar = rowLayout();
        bar.setPadding(dp(16), dp(28), dp(8), dp(16));
        TextView tv = text(title, 30, t.text1, W_BOLD);
        bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) bar.addView(text(right, ConsoleTheme.SP_BODY, t.text3, W_REGULAR));
        return bar;
    }

    /** 二级页顶栏：‹ + 标题（20 Bold）+ 可选右侧文字动作。 */
    public View topBar(String title, final Runnable back, String right, final Runnable onRight) {
        LinearLayout bar = rowLayout();
        bar.setPadding(0, dp(12), 0, dp(8));
        ConsoleIcon b = new ConsoleIcon(act, ConsoleIcon.BACK, t.text1, 0);
        b.setContentDescription("返回");
        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        b.setBackground(ripple(null));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (back != null) back.run();
            }
        });
        bar.addView(b, lp(dp(48), dp(48)));
        TextView tv = text(title, ConsoleTheme.SP_TITLE + 2, t.text1, W_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(4), 0, dp(8), 0);
        bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            TextView r = text(right, ConsoleTheme.SP_BODY, t.accent, W_MEDIUM);
            r.setPadding(dp(14), dp(12), dp(14), dp(12));
            r.setBackground(ripple(null));
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (onRight != null) onRight.run();
                }
            });
            bar.addView(r);
        }
        return bar;
    }

    // ------------------------------------------------------------------ 分组与行

    /**
     * 组标题 + 分组容器。★ console-align：与电脑端设置页同一写法——标题 13 中粗、次级字色，左边一条 4×14 的主色短条；卡片圆角 22
     * （{@link ConsoleStyle#R_CARD}）。原来是 12 Medium、t3、圆角 24。
     */
    public LinearLayout group(LinearLayout root, String title) {
        if (title != null) {
            LinearLayout hr = rowLayout();
            hr.setPadding(dp(8), dp(ConsoleTheme.GAP_GROUP + 2), dp(16), dp(8));
            View bar = new View(act);
            bar.setBackground(round(t.accent, 2));
            LinearLayout.LayoutParams blp = lp(dp(4), dp(14));
            blp.rightMargin = dp(8);
            hr.addView(bar, blp);
            TextView h = text(title, ConsoleTheme.SP_SUB, t.text2, W_MEDIUM);
            hr.addView(h);
            root.addView(hr);
        }
        LinearLayout g = column();
        g.setBackground(round(t.surface, ConsoleStyle.R_CARD));
        g.setClipToOutline(true);
        LinearLayout.LayoutParams lp = lpMatch(title == null ? ConsoleTheme.GAP_CARD : 0, 0);
        root.addView(g, lp);
        return g;
    }

    private void addToGroup(LinearLayout group, View row) {
        if (group.getChildCount() > 0) {
            View div = new View(act);
            div.setBackgroundColor(t.line);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
            lp.leftMargin = dp(16);
            lp.rightMargin = dp(16);
            Object tag = row.getTag();
            if ("icon".equals(tag)) lp.leftMargin = dp(80);
            group.addView(div, lp);
        }
        group.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 行的实现：标题 + 副标题 + 尾部（开关 / 状态字 / ›/ 胶囊按钮）。 */
    public final class RowImpl implements ConsoleHost.Row {
        final LinearLayout root;
        final LinearLayout textCol;
        final TextView title;
        final TextView sub;
        TextView value;
        ToggleView toggle;
        View tapArea;
        TextView button;
        ConsoleHost.Toggle listener;
        boolean enabled = true;

        RowImpl(String titleText, String subText, int iconKind) {
            root = rowLayout();
            root.setMinimumHeight(dp(subText != null ? 64 : 56));
            root.setPadding(dp(16), dp(10), dp(12), dp(10));
            if (iconKind > 0) {
                ConsoleIcon ic = new ConsoleIcon(act, iconKind, t.accent, t.surface2);
                LinearLayout.LayoutParams ilp = lp(dp(40), dp(40));
                ilp.rightMargin = dp(16);
                root.addView(ic, ilp);
                root.setTag("icon");
            }
            textCol = column();
            // ★ console-align：行标题 16 常规、说明 13 次级字色（电脑端 15 / 12.5 px 同比例）；原来 17 Medium / 14
            title = text(titleText, ConsoleTheme.SP_ITEM, t.text1, W_REGULAR);
            textCol.addView(title);
            sub = text(subText == null ? "" : subText, ConsoleTheme.SP_SUB, t.text2, W_REGULAR);
            sub.setPadding(0, dp(2), 0, 0);
            sub.setVisibility(subText == null ? View.GONE : View.VISIBLE);
            textCol.addView(sub);
            root.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        @Override public View view() {
            return root;
        }

        @Override public void setSub(String s) {
            sub.setText(s == null ? "" : s);
            sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        }

        @Override public void setSubTone(int tone) {
            sub.setTextColor(toneColor(tone, t.text2));
        }

        @Override public void setValue(String v, int tone) {
            if (value == null) return;
            value.setText(v == null ? "" : v);
            value.setTextColor(toneColor(tone, t.text2));
        }

        @Override public void setOn(boolean on) {
            if (toggle != null) toggle.setOn(on, false);
        }

        @Override public boolean isOn() {
            return toggle != null && toggle.isOn();
        }

        @Override public void setEnabled(boolean en) {
            enabled = en;
            root.setAlpha(en ? 1f : 0.42f);
            if (tapArea != null) tapArea.setEnabled(en);
            if (button != null) button.setEnabled(en);
            root.setEnabled(en);
        }

        void flip() {
            if (!enabled || toggle == null) return;
            boolean on = !toggle.isOn();
            toggle.setOn(on, true);
            if (listener != null) listener.onToggle(on);
        }
    }

    public int toneColor(int tone, int normal) {
        switch (tone) {
            case ConsoleHost.TONE_OK: return t.accent;
            case ConsoleHost.TONE_WARN: return t.warn;
            case ConsoleHost.TONE_BAD: return t.danger;
            case ConsoleHost.TONE_MUTED: return t.text3;
            default: return normal;
        }
    }

    public int levelColor(ConsoleStatus.Level l) {
        switch (l) {
            case OK: return t.accent;
            case WARN: return t.warn;
            case BAD: return t.danger;
            default: return t.text3;
        }
    }

    public RowImpl switchRow(LinearLayout group, String title, String sub, boolean on, ConsoleHost.Toggle l) {
        final RowImpl r = new RowImpl(title, sub, 0);
        r.listener = l;
        r.toggle = new ToggleView(act, t, on);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        r.root.addView(r.toggle, tlp);
        r.root.setBackground(ripple(null));
        r.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.flip();
            }
        });
        r.tapArea = r.root;
        r.root.setContentDescription(title);
        addToGroup(group, r.root);
        return r;
    }

    public RowImpl switchLinkRow(LinearLayout group, String title, String sub, boolean on, ConsoleHost.Toggle l, final Runnable open) {
        final RowImpl r = new RowImpl(title, sub, 0);
        r.listener = l;
        // 左边：点进细项页
        r.textCol.setBackground(ripple(null));
        r.textCol.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (open != null) open.run();
            }
        });
        View div = new View(act);
        div.setBackgroundColor(t.line);
        LinearLayout.LayoutParams dlp = lp(Math.max(1, dp(0.7f)), dp(28));
        dlp.leftMargin = dp(8);
        r.root.addView(div, dlp);
        r.toggle = new ToggleView(act, t, on);
        r.toggle.setPadding(dp(8), 0, 0, 0);
        r.toggle.setBackground(ripple(null));
        r.toggle.setClickable(true);
        r.toggle.setContentDescription(title + " 开关");
        r.toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        r.toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.flip();
            }
        });
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(4);
        r.root.addView(r.toggle, tlp);
        r.tapArea = r.toggle;
        addToGroup(group, r.root);
        return r;
    }

    public RowImpl linkRow(LinearLayout group, String title, String sub, int icon, final Runnable open) {
        RowImpl r = new RowImpl(title, sub, icon);
        r.value = text("", ConsoleTheme.SP_SUB + 1, t.text2, W_REGULAR);
        r.root.addView(r.value);
        ConsoleIcon ch = new ConsoleIcon(act, ConsoleIcon.CHEVRON, t.text3, 0);
        LinearLayout.LayoutParams clp = lp(dp(24), dp(24));
        clp.leftMargin = dp(4);
        r.root.addView(ch, clp);
        r.root.setBackground(ripple(null));
        r.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (open != null) open.run();
            }
        });
        r.tapArea = r.root;
        if (icon > 0) r.root.setMinimumHeight(dp(68));
        addToGroup(group, r.root);
        return r;
    }

    public RowImpl valueRow(LinearLayout group, String title, String sub, String value, int tone) {
        RowImpl r = new RowImpl(title, sub, 0);
        r.value = text(value == null ? "" : value, ConsoleTheme.SP_BODY + 1, toneColor(tone, t.text2), W_MEDIUM);
        r.value.setPadding(dp(12), 0, dp(4), 0);
        r.value.setMaxWidth(dp(150));
        r.value.setGravity(Gravity.END);
        r.root.addView(r.value);
        addToGroup(group, r.root);
        return r;
    }

    public RowImpl actionRow(LinearLayout group, String title, String sub, String action, final Runnable onAction) {
        RowImpl r = new RowImpl(title, sub, 0);
        r.button = pillButton(action, onAction);
        if (ConsoleStyle.isDanger(action)) {   // ★ console-align：危险动作红字红底（两端同一张动词表）
            r.button.setTextColor(t.danger);
            r.button.setBackground(ripple(round(t.dangerContainer, 20)));
        }
        LinearLayout.LayoutParams blp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        blp.leftMargin = dp(12);
        r.root.addView(r.button, blp);
        addToGroup(group, r.root);
        return r;
    }

    /** [计划区] 小胶囊按钮（首页计划直达页签）：浅底、正文色、32dp 高，单行不截断。 */
    public TextView chip(String label, final Runnable onClick) {
        TextView b = text(label, ConsoleTheme.SP_SUB + 1, t.text1, W_MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(32));
        b.setBackground(ripple(round(t.surface2, 16)));
        b.setContentDescription("计划 · " + label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onClick != null) onClick.run();
            }
        });
        return b;
    }

    /** 苔绿容器色的胶囊按钮（正向动作）。 */
    public TextView pillButton(String label, final Runnable onClick) {
        TextView b = text(label, ConsoleTheme.SP_BODY + 1, t.accent, W_MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinHeight(dp(40));
        b.setBackground(ripple(round(t.accentContainer, 20)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onClick != null) onClick.run();
            }
        });
        return b;
    }

    public RowImpl mainSwitch(LinearLayout root, String title, boolean on, ConsoleHost.Toggle l) {
        final RowImpl r = new RowImpl(title, null, 0);
        r.title.setTypeface(Typeface.DEFAULT_BOLD);
        r.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, ConsoleTheme.SP_ITEM + 2);
        r.root.setMinimumHeight(dp(64));
        r.root.setPadding(dp(20), dp(12), dp(14), dp(12));
        r.listener = l;
        r.toggle = new ToggleView(act, t, on);
        r.root.addView(r.toggle);
        r.root.setBackground(ripple(round(t.accentContainer, ConsoleTheme.R_CARD)));
        r.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.flip();
            }
        });
        r.tapArea = r.root;
        root.addView(r.root, lpMatch(ConsoleTheme.GAP_CARD, 0));
        return r;
    }

    public TextView footer(LinearLayout root, String s) {
        TextView f = text(s, ConsoleTheme.SP_SUB, t.text3, W_REGULAR);
        f.setLineSpacing(0f, 1.25f);
        f.setPadding(dp(16), dp(14), dp(16), dp(4));
        root.addView(f);
        return f;
    }

    // ------------------------------------------------------------------ 首页零件

    public View dot(int color, int sizeDp) {
        View v = new View(act);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** 状态胶囊：高 30、全圆角（和岛紧凑态 28/14 同一比例），点颜色 = 真实状态。 */
    public View pill(ConsoleStatus.Pill p) {
        LinearLayout l = rowLayout();
        l.setPadding(dp(10), 0, dp(12), 0);
        l.setMinimumHeight(dp(30));
        l.setBackground(round(t.surface2, 16));
        View d = dot(levelColor(p.level), 8);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) d.getLayoutParams();
        dlp.rightMargin = dp(6);
        l.addView(d, dlp);
        TextView tv = text(p.label, ConsoleTheme.SP_SUB, p.level == ConsoleStatus.Level.UNKNOWN ? t.text2 : t.text1, W_MEDIUM);
        tv.setSingleLine(true);
        l.addView(tv);
        l.setContentDescription(p.label);
        return l;
    }

    public View banner(ConsoleStatus.Banner b, final Runnable onAction) {
        LinearLayout l = rowLayout();
        l.setPadding(dp(18), dp(8), dp(8), dp(8));
        l.setMinimumHeight(dp(56));
        boolean bad = b.level == ConsoleStatus.Level.BAD;
        l.setBackground(round(bad ? t.dangerContainer : t.warnContainer, ConsoleTheme.R_BANNER));
        View d = dot(bad ? t.danger : t.warn, 8);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) d.getLayoutParams();
        dlp.rightMargin = dp(12);
        l.addView(d, dlp);
        TextView tv = text(b.text, ConsoleTheme.SP_BODY + 1, t.text1, W_REGULAR);
        l.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView a = text(b.action, ConsoleTheme.SP_BODY + 1, bad ? t.danger : t.warn, W_BOLD);
        a.setPadding(dp(12), dp(10), dp(12), dp(10));
        a.setSingleLine(true);
        a.setBackground(ripple(null));
        a.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onAction != null) onAction.run();
            }
        });
        l.addView(a);
        return l;
    }

    /** 首页大卡（记一记 / 记忆）：图标 + 标题 + ›；大数字 + 单位；副标题。 */
    public final class Tile {
        public final LinearLayout root;
        public final TextView number;
        public final TextView sub;

        Tile(int icon, String title, String unit, final Runnable open) {
            root = column();
            root.setPadding(dp(16), dp(14), dp(12), dp(14));
            root.setBackground(ripple(round(t.surface, ConsoleTheme.R_CARD)));
            LinearLayout head = rowLayout();
            ConsoleIcon ic = new ConsoleIcon(act, icon, t.accent, t.accentContainer);
            LinearLayout.LayoutParams ilp = lp(dp(34), dp(34));
            ilp.rightMargin = dp(10);
            head.addView(ic, ilp);
            TextView tt = text(title, ConsoleTheme.SP_ITEM + 1, t.text1, W_MEDIUM);
            tt.setSingleLine(true);
            head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(new ConsoleIcon(act, ConsoleIcon.CHEVRON, t.text3, 0), lp(dp(20), dp(20)));
            root.addView(head);
            LinearLayout num = rowLayout();
            num.setGravity(Gravity.BOTTOM);
            number = text("—", ConsoleTheme.SP_DISPLAY + 4, t.text1, W_BOLD);
            number.setIncludeFontPadding(false);
            number.setLineSpacing(0f, 1f);
            num.addView(number);
            TextView u = text(" " + unit, ConsoleTheme.SP_SUB + 1, t.text2, W_REGULAR);
            u.setPadding(dp(2), 0, 0, dp(3));
            num.addView(u);
            num.setPadding(0, dp(12), 0, dp(6));
            root.addView(num);
            sub = text(" ", ConsoleTheme.SP_SUB + 1, t.text2, W_REGULAR);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(sub);
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (open != null) open.run();
                }
            });
            pressable(root);
        }
    }

    public Tile tile(int icon, String title, String unit, Runnable open) {
        return new Tile(icon, title, unit, open);
    }

    // ------------------------------------------------------------------ 对话框

    public AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(act, t.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    /** 确认框：标题「X？」+ 一句后果 + 动词按钮 +「取消」。★ console-align：动词是危险动作时按钮写红色（与电脑端一致）。 */
    public void confirm(String title, String msg, String ok, final Runnable yes) {
        AlertDialog d = dialog().setTitle(title).setMessage(msg)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (yes != null) yes.run();
                    }
                }).setNegativeButton("取消", null).show();
        if (ConsoleStyle.isDanger(ok)) {
            try {
                d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(t.danger);
            } catch (Throwable ignored) {
            }
        }
    }

    /** ★ console-align：状态徽标行（标题 + 说明 + 右侧彩色小胶囊，颜色 = 控屏颜色）。 */
    public RowImpl badgeRow(LinearLayout group, String title, String sub, String label, int rgb) {
        RowImpl r = new RowImpl(title, sub, 0);
        TextView b = text(label, ConsoleTheme.SP_SUB, 0xFF000000 | rgb, W_MEDIUM);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(3), dp(10), dp(3));
        b.setBackground(round(ConsoleTheme.alpha(0xFF000000 | rgb, 0.16f), 12));
        LinearLayout.LayoutParams blp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(12);
        r.root.addView(b, blp);
        r.value = b;
        addToGroup(group, r.root);
        return r;
    }
}
