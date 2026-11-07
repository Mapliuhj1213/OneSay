package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * [screenref] 面板上的"下一步动作芯片"横条（识屏指代 + 动作芯片功能专用，新文件，不改 FloatingSheetView）。
 *
 * <p>由 {@code ScreenRefHooks} 在运行时插入 FloatingSheetView（模式胶囊行之下），默认 GONE。
 * 每个芯片是一行短文案（如「导航到 深圳市南山区…」「复制取件码 5-2-1234」），点一下回调 {@link Listener}。
 */
public class ActionChipsView extends HorizontalScrollView {

    public interface Listener {
        void onChipClicked(int index);
    }

    public static final String TAG_IN_SHEET = "screenref_chips";

    /** ★ R13：新唤醒界面开着时芯片用 v2 样式（42dp 胶囊、主芯片苔绿 30% 底、错峰 CHIP_IN 弹入）。由 FloatingSheetView.setV2 置位。 */
    public static volatile boolean sV2Style;

    private final LinearLayout mRow;
    private Listener mListener;

    /** ★ ui-K1 对话窗：芯片变化观察者（对话窗把面板芯片镜像成追问建议胶囊）。只在主线程回调。 */
    public interface Observer {
        void onChipsChanged(ActionChipsView v, List<String> labels);
    }

    public static volatile Observer sObserver;

    private final java.util.ArrayList<String> mLabels = new java.util.ArrayList<String>();

    /** 当前这排芯片的文案（只读副本）。 */
    public List<String> labels() {
        return new java.util.ArrayList<String>(mLabels);
    }

    /** 等同用户点第 i 个芯片（对话窗里的建议胶囊复用芯片原有回调）。 */
    public boolean clickAt(int i) {
        if (mListener == null || i < 0 || i >= mLabels.size()) return false;
        mListener.onChipClicked(i);
        return true;
    }

    private void notifyObserver() {
        Observer o = sObserver;
        if (o != null) {
            try {
                o.onChipsChanged(this, labels());
            } catch (Throwable ignore) {
            }
        }
    }

    /** r2/p0：当前这排芯片是谁的（谁 showChips 谁负责设；任何 showChips / hideChips 都先清掉）。只用来判断「收起时别收了别人的芯片」。 */
    public Object owner;

    public ActionChipsView(Context context) {
        super(context);
        setTag(TAG_IN_SHEET);
        setHorizontalScrollBarEnabled(false);
        setVisibility(GONE);
        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setPadding(0, dp(2), 0, dp(6));
        addView(mRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /** 显示芯片（labels 为空则隐藏）。只在主线程调用。 */
    public void showChips(List<String> labels) {
        owner = null;
        mRow.removeAllViews();
        mLabels.clear();
        if (labels == null || labels.isEmpty()) {
            setVisibility(GONE);
            notifyObserver();
            return;
        }
        mLabels.addAll(labels);
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            TextView chip = new TextView(getContext());
            chip.setText(labels.get(i));
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(220));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            chip.setTextColor(Color.parseColor("#F2F2F7"));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#332C6BED"));
            bg.setStroke(dp(1), Color.parseColor("#665E9EFF"));
            bg.setCornerRadius(dp(16));
            chip.setBackground(bg);
            chip.setContentDescription("动作芯片：" + labels.get(i));
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onChipClicked(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            if (sV2Style) {
                // v2.5：42dp 高胶囊、文字 15sp Medium、左右各 22dp；主芯片苔绿 30% 底 + #D6EAC6 字，次芯片白 8.5% 底；间距 10dp
                boolean primary = i == 0;
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                chip.setTextColor(primary ? 0xFFD6EAC6 : 0xFFF0EEEA);
                chip.setPadding(dp(22), 0, dp(22), 0);
                chip.setMaxWidth(dp(240));
                GradientDrawable v2 = new GradientDrawable();
                v2.setColor(primary ? 0x4D8CD27A : 0x16FFFFFF);
                v2.setCornerRadius(dp(21));
                chip.setBackground(v2);
                lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
                lp.rightMargin = dp(10);
                chip.setAlpha(0f);
            }
            mRow.addView(chip, lp);
        }
        setVisibility(VISIBLE);
        scrollTo(0, 0);
        if (sV2Style) {
            final float dens = getResources().getDisplayMetrics().density;
            final boolean instant = !com.onesay.assistant.v2.ui.wake.WakeUiSettings.animatorsEnabled(getContext());
            post(new Runnable() {   // 量过尺寸再弹（缩放轴心 = 芯片中心）
                @Override
                public void run() {
                    for (int k = 0; k < mRow.getChildCount(); k++) {
                        com.onesay.assistant.v2.ui.motion.ViewSpring.chipIn(mRow.getChildAt(k), dens, 50L * k, instant);
                    }
                }
            });
        }
        notifyObserver();
    }

    public void hideChips() {
        owner = null;
        mRow.removeAllViews();
        mLabels.clear();
        setVisibility(GONE);
        notifyObserver();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
