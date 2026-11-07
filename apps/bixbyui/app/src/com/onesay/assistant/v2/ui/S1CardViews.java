package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * [s1] 小浮卡（薄视图，逻辑在 s1 包）：
 * <ul>
 *   <li>{@link ClipChipCard}：剪贴板芯片，「📋 复制了地址：深圳市南山区…」+「导航」「打车」… +「✕」。</li>
 * </ul>
 * （旧的取件码浮卡 PickupStack 只给已删除的悬浮胶囊回落用，2026-10-10 第 6 项随胶囊一起删除；取件码只在灵动岛上显示。）
 */
public final class S1CardViews {

    private S1CardViews() {}

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable cardBg(Context c, String fill, String stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(fill));
        bg.setStroke(dp(c, 1), Color.parseColor(stroke));
        bg.setCornerRadius(dp(c, 14));
        return bg;
    }

    static TextView pill(Context c, String text, String fill, String fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Color.parseColor(fg));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(fill));
        bg.setCornerRadius(dp(c, 14));
        t.setBackground(bg);
        return t;
    }

    // =====================================================================

    /** 剪贴板芯片卡。 */
    public static class ClipChipCard extends LinearLayout {

        public interface Listener {
            void onChip(int index);

            void onClose();

            /** 用户碰到了卡片（取消自动消失）。 */
            void onTouched();
        }

        private Listener mListener;

        public ClipChipCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            Context c = context;
            setPadding(dp(c, 14), dp(c, 10), dp(c, 10), dp(c, 10));
            setBackground(cardBg(c, "#F01C1C1E", "#805E9EFF"));
            setElevation(dp(c, 8));
        }

        public void setListener(Listener l) {
            mListener = l;
        }

        public void bind(String title, List<String> chipLabels) {
            bind("📋", title, chipLabels);
        }

        /** ★ fix/shot-1104：非岛模式下主动建议也借这张卡（截图用 🖼），icon 换掉剪贴板图标。 */
        public void bind(String icon, String title, List<String> chipLabels) {
            removeAllViews();
            Context c = getContext();
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(c);
            t.setText((icon == null || icon.length() == 0 ? "" : icon + " ") + title);
            t.setTextColor(Color.parseColor("#F2F2F7"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(c, 260));
            head.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView close = pill(c, "✕", "#00000000", "#8E8E93");
            close.setContentDescription("关闭剪贴板建议");
            close.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onClose();
                }
            });
            head.addView(close);
            addView(head);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setPadding(0, dp(c, 6), 0, 0);
            for (int i = 0; i < chipLabels.size(); i++) {
                final int idx = i;
                TextView chip = pill(c, chipLabels.get(i), "#332C6BED", "#F2F2F7");
                chip.setContentDescription("剪贴板芯片：" + chipLabels.get(i));
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mListener != null) mListener.onChip(idx);
                    }
                });
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(c, 8);
                row.addView(chip, lp);
            }
            addView(row);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && mListener != null) mListener.onTouched();
            return false;
        }
    }
}
