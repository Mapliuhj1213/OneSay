package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.v2.roles.AppGridModel;

/**
 * [r2/bike-chooser] 回答下方的应用选择网格（用户 2026-10-30 定的排版）：
 * <pre>
 *  ┌──────────────────┐ ┌──────────────────┐
 *  │ [图标] 哈啰        │ │ [图标] 美团        │
 *  │        ↓ 下载      │ │        ↗ 打开      │
 *  └──────────────────┘ └──────────────────┘
 *  ┌──────────────────┐ ┌──────────────────┐
 *  │ …                │ │ …                │
 *  └──────────────────┘ └──────────────────┘
 *  打开应用商店查看更多 ↗          ← 蓝色超链接样式，与格子左对齐
 * </pre>
 * 每行 2 格、最多 4 格（{@link AppGridModel}）；图标 40dp 比文字大；名称单行省略；动作行 = 线条图标 + 小字；
 * 整格可点（≥64dp 高，水波纹 + 按下微缩），链接行 44dp 高、按下变深并淡一下。深 / 浅两套色见 {@link ChoiceGridPalette}。
 *
 * <p>由 {@code RoleHooks} 插进 FloatingSheetView（紧跟在动作芯片后面 / 内容区后面），默认 GONE；不改 FloatingSheetView。
 */
public class ChoiceGridView extends LinearLayout {

    public static final String TAG_IN_SHEET = "choice_grid";

    public interface Listener {
        void onCell(AppGridModel.Cell cell);

        void onMore(String storeQuery);
    }

    /** ★ install-wait（PLAN-install-wait-resume）：缺应用时网格下方「不用了」。实现了它的 Listener 才画这一行。 */
    public interface DismissListener {
        void onDismiss();
    }

    /** debug 强制主题：null = 跟控制台外观；"light" / "dark"（TestRunReceiver --es choicegrid）。 */
    public static volatile String sForceTheme;

    /** 图标来源（Android 层用 PackageManager；拿不到返回 null → 占位图标）。 */
    public interface IconSource {
        Drawable icon(String pkg);
    }

    private Listener mListener;
    private AppGridModel mModel;
    private ChoiceGridPalette mPal = ChoiceGridPalette.DARK;

    public ChoiceGridView(Context context) {
        super(context);
        setTag(TAG_IN_SHEET);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public AppGridModel model() {
        return mModel;
    }

    public ChoiceGridPalette palette() {
        return mPal;
    }

    /** 只在主线程调用。model 为空 / 没有格子 → 隐藏。 */
    public void show(AppGridModel model, boolean light, IconSource icons, boolean animate) {
        removeAllViews();
        mModel = model;
        if (model == null || model.isEmpty()) {
            setVisibility(GONE);
            com.onesay.assistant.v2.roles.InstallCardState.gone(com.onesay.assistant.v2.roles.InstallCardState.KIND_GRID);
            return;
        }
        mPal = ChoiceGridPalette.of(light);
        if (mPal.panel != 0) {
            GradientDrawable p = new GradientDrawable();
            p.setColor(mPal.panel);
            p.setCornerRadius(dp(ChoiceGridPalette.PANEL_RADIUS));
            setBackground(p);
            int pad = dp(ChoiceGridPalette.PANEL_PAD);
            setPadding(pad, pad, pad, dp(2));
        } else {
            setBackground(null);
            setPadding(0, dp(2), 0, 0);
        }
        int gap = dp(ChoiceGridPalette.CELL_GAP);
        final java.util.List<View> cellViews = new java.util.ArrayList<View>();
        for (int r = 0; r < model.rows(); r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setClipChildren(false);
            for (int c = 0; c < 2; c++) {
                AppGridModel.Cell cell = model.at(r, c);
                LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c == 1) lp.leftMargin = gap;
                if (cell == null) {
                    View spacer = new View(getContext());
                    row.addView(spacer, lp);
                    continue;
                }
                View v = buildCell(cell, icons);
                if (animate) v.setAlpha(0f);
                cellViews.add(v);
                row.addView(v, lp);
            }
            LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = gap;
            addView(row, rlp);
        }
        if (model.storeQuery != null) {
            View link = buildMoreLink(model.storeQuery);
            LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(2);
            addView(link, llp);
        }
        boolean missing = false;
        for (AppGridModel.Cell c : model.cells) if (!c.installed) missing = true;
        if (missing) {   // ★ backlog #3：缺应用的网格 = 安装卡（「不用了」口令可关）
            java.util.List<String> ps = new java.util.ArrayList<String>();
            for (AppGridModel.Cell c : model.cells) if (!c.installed && c.pkg != null) ps.add(c.pkg);
            com.onesay.assistant.v2.roles.InstallCardState.shown(com.onesay.assistant.v2.roles.InstallCardState.KIND_GRID, ps);
        } else {
            com.onesay.assistant.v2.roles.InstallCardState.gone(com.onesay.assistant.v2.roles.InstallCardState.KIND_GRID);
        }
        if (missing && mListener instanceof DismissListener) {
            final TextView no = new TextView(getContext());
            no.setText("不用了");
            no.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChoiceGridPalette.SP_LINK);
            no.setTextColor(mPal.link);
            no.setMinHeight(dp(ChoiceGridPalette.LINK_MIN_H));
            no.setGravity(Gravity.CENTER_VERTICAL);
            no.setPadding(dp(4), 0, dp(12), 0);
            no.setContentDescription("不用了，按钮");
            no.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener instanceof DismissListener) ((DismissListener) mListener).onDismiss();
                }
            });
            addView(no, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        setVisibility(VISIBLE);
        if (animate) {
            final float dens = getResources().getDisplayMetrics().density;
            post(new Runnable() {   // 量过尺寸再弹（缩放轴心 = 格子中心），和动作芯片同一条 CHIP_IN 弹簧、错峰 50ms
                @Override
                public void run() {
                    for (int k = 0; k < cellViews.size(); k++) {
                        com.onesay.assistant.v2.ui.motion.ViewSpring.chipIn(cellViews.get(k), dens, 50L * k, false);
                    }
                }
            });
        }
    }

    public void hide() {
        removeAllViews();
        mModel = null;
        setVisibility(GONE);
        com.onesay.assistant.v2.roles.InstallCardState.gone(com.onesay.assistant.v2.roles.InstallCardState.KIND_GRID);
    }

    // ------------------------------------------------------------------ 格子

    private View buildCell(final AppGridModel.Cell cell, IconSource icons) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinimumHeight(dp(ChoiceGridPalette.CELL_MIN_H));
        box.setPadding(dp(12), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mPal.cell);
        bg.setCornerRadius(dp(ChoiceGridPalette.CELL_RADIUS));
        if ((mPal.cellStroke >>> 24) != 0) bg.setStroke(dp(1), mPal.cellStroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(ChoiceGridPalette.CELL_RADIUS));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(mPal.cellPressed), bg, mask));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(cell.describe());
        pressFeedback(box, 0.97f);

        ImageView icon = new ImageView(getContext());
        Drawable d = null;
        if (cell.installed && icons != null) {
            try {
                d = icons.icon(cell.pkg);
            } catch (Throwable ignored) {
                d = null;
            }
        }
        if (d == null) d = new PlaceholderIcon(cell.label, mPal.placeholderBg, mPal.placeholderText, dp(ChoiceGridPalette.ICON_RADIUS));
        icon.setImageDrawable(d);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, new LayoutParams(dp(ChoiceGridPalette.ICON), dp(ChoiceGridPalette.ICON)));

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        LayoutParams clp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(10);
        box.addView(col, clp);

        TextView name = new TextView(getContext());
        name.setText(cell.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChoiceGridPalette.SP_NAME);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(mPal.name);
        name.setIncludeFontPadding(false);
        col.addView(name, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout act = new LinearLayout(getContext());
        act.setOrientation(HORIZONTAL);
        act.setGravity(Gravity.CENTER_VERTICAL);
        int ac = cell.installed ? mPal.action : mPal.actionDownload;
        LineIcon li = new LineIcon(getContext(), cell.installed ? LineIcon.OPEN : LineIcon.DOWNLOAD, ac);
        act.addView(li, new LayoutParams(dp(ChoiceGridPalette.ACTION_ICON), dp(ChoiceGridPalette.ACTION_ICON)));
        TextView at = new TextView(getContext());
        at.setText(cell.action.text);
        at.setSingleLine(true);
        at.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChoiceGridPalette.SP_ACTION);
        at.setTextColor(ac);
        at.setIncludeFontPadding(false);
        LayoutParams alp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(4);
        act.addView(at, alp);
        LayoutParams aclp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aclp.topMargin = dp(6);
        col.addView(act, aclp);

        box.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onCell(cell);
            }
        });
        return box;
    }

    // ------------------------------------------------------------------ 「打开应用商店查看更多」

    private View buildMoreLink(final String query) {
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ChoiceGridPalette.LINK_MIN_H));
        row.setPadding(dp(4), 0, dp(12), 0);   // 左边与格子的圆角内沿对齐（不是按钮框，只是一行可点的字）
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("打开应用商店查看更多，搜索" + query + "，链接");
        final TextView t = new TextView(getContext());
        t.setText("打开应用商店查看更多");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChoiceGridPalette.SP_LINK);
        t.setTextColor(mPal.link);
        t.getPaint().setUnderlineText(true);
        t.setSingleLine(true);
        row.addView(t, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LineIcon ext = new LineIcon(getContext(), LineIcon.EXTERNAL, mPal.link);
        LayoutParams elp = new LayoutParams(dp(13), dp(13));
        elp.leftMargin = dp(4);
        row.addView(ext, elp);
        row.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                boolean down = a == MotionEvent.ACTION_DOWN;
                boolean up = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
                if (down || up) {
                    int c = down ? mPal.linkPressed : mPal.link;
                    t.setTextColor(c);
                    ext.setColor(c);
                    row.animate().alpha(down ? 0.72f : 1f).setDuration(down ? 60 : 140).start();
                }
                return false;
            }
        });
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onMore(query);
            }
        });
        return row;
    }

    private static void pressFeedback(final View v, final float scale) {
        v.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(scale).scaleY(scale).setDuration(70).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                }
                return false;
            }
        });
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ 线条图标（1.5dp 描边、圆头）

    static final class LineIcon extends View {
        static final int OPEN = 0, DOWNLOAD = 1, EXTERNAL = 2;
        private final int mKind;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);

        LineIcon(Context c, int kind, int color) {
            super(c);
            mKind = kind;
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setStrokeJoin(Paint.Join.ROUND);
            mP.setStrokeWidth(1.5f * c.getResources().getDisplayMetrics().density);
            mP.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setColor(int c) {
            mP.setColor(c);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h), k = mP.getStrokeWidth();
            float l = k, t = k, r = s - k, b = s - k;
            if (mKind == DOWNLOAD) {
                float cx = s / 2f;
                cv.drawLine(cx, t, cx, b * 0.70f, mP);                       // 竖线
                cv.drawLine(cx - s * 0.26f, b * 0.70f - s * 0.26f, cx, b * 0.70f, mP);
                cv.drawLine(cx + s * 0.26f, b * 0.70f - s * 0.26f, cx, b * 0.70f, mP);
                cv.drawLine(l, b, r, b, mP);                                  // 托盘
            } else {
                // 右上箭头 ↗（OPEN）；EXTERNAL 再加一个缺角方框
                cv.drawLine(l + s * 0.18f, b - s * 0.18f, r, t, mP);
                cv.drawLine(r - s * 0.46f, t, r, t, mP);
                cv.drawLine(r, t, r, t + s * 0.46f, mP);
                if (mKind == EXTERNAL) {
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(s * 0.42f, t + s * 0.08f);
                    p.lineTo(l, t + s * 0.08f);
                    p.lineTo(l, b);
                    p.lineTo(r - s * 0.08f, b);
                    p.lineTo(r - s * 0.08f, s * 0.58f);
                    cv.drawPath(p, mP);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 未安装 / 拿不到图标时的占位：苔绿圆角方块 + 首字

    static final class PlaceholderIcon extends Drawable {
        private final String mChar;
        private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mR;
        private final RectF mRect = new RectF();

        PlaceholderIcon(String label, int bg, int fg, float radius) {
            String s = label == null ? "" : label.trim();
            mChar = s.length() == 0 ? "?" : new String(Character.toChars(s.codePointAt(0)));
            mBg.setColor(bg);
            mTx.setColor(fg);
            mTx.setTextAlign(Paint.Align.CENTER);
            mTx.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mR = radius;
        }

        @Override
        public void draw(Canvas c) {
            mRect.set(getBounds());
            c.drawRoundRect(mRect, mR, mR, mBg);
            mTx.setTextSize(mRect.height() * 0.46f);
            Paint.FontMetrics fm = mTx.getFontMetrics();
            c.drawText(mChar, mRect.centerX(), mRect.centerY() - (fm.ascent + fm.descent) / 2f, mTx);
        }

        @Override public void setAlpha(int a) {
            mBg.setAlpha(a);
            mTx.setAlpha(a);
        }

        @Override public void setColorFilter(ColorFilter cf) {
            mTx.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override public int getIntrinsicWidth() {
            return -1;
        }

        @Override public int getIntrinsicHeight() {
            return -1;
        }
    }
}
