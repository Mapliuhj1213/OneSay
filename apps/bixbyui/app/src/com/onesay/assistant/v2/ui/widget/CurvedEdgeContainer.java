package com.onesay.assistant.v2.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 曲面屏安全流体抽屉容器（CurvedEdgeContainer）
 *
 * 针对三星 Note8 物理曲面屏优化：
 * 1. 设立 24dp 左右双侧安全区内边距（Curved Safe Insets），彻底将卡片、按钮、输入框拉回平坦屏幕区域，
 *    彻底根除用户在曲面弧度侧边误触、边缘触控失灵、手指无着力点的痛点；
 * 2. 95% 亚光磨砂深色背景（#F2181820），28dp 连续超感圆角，1.5dp 微光内描边；
 * 3. 顶部集成 36dp 药丸手柄条（Grabber Handle），支持 1:1 跟随手指下拖直接操纵与点击瞬退。
 */
public class CurvedEdgeContainer extends LinearLayout {

    public interface OnDismissListener {
        void onDismiss();
    }

    private final View mHandleView;
    private OnDismissListener mDismissListener;

    public CurvedEdgeContainer(Context context) {
        this(context, null);
    }

    public CurvedEdgeContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setFitsSystemWindows(true);

        applyClassicFrame();

        // 顶部 iOS Grabber 手柄指示条（支持直接手势下拖收起 + 点击瞬退）
        mHandleView = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#4DFFFFFF"));
        handleBg.setCornerRadius(dp(2.5f));
        mHandleView.setBackground(handleBg);
        LayoutParams handleLp = new LayoutParams(dp(36), dp(5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = dp(2);
        handleLp.bottomMargin = dp(14); // 宽松留白
        addView(mHandleView, handleLp);

        setupHandleTouch();
    }

    /** 旧面板外观：曲面屏双侧 24dp 安全内边距（顶 10dp、底 18dp）+ 磨砂深色背景 + 1.5dp 微光描边。 */
    protected void applyClassicFrame() {
        setPadding(dp(24), dp(10), dp(24), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2181820"));
        bg.setCornerRadii(new float[]{ dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0 });
        bg.setStroke(dp(1.5f), Color.parseColor("#26FFFFFF"));
        setBackground(bg);
    }

    /** [R13] 新唤醒界面不用手柄条（关闭走 ✕ / 点外部 / 返回键）。 */
    protected void setHandleVisible(boolean visible) {
        if (mHandleView != null) mHandleView.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setOnDismissListener(OnDismissListener listener) {
        this.mDismissListener = listener;
    }

    private void setupHandleTouch() {
        mHandleView.setOnTouchListener(new OnTouchListener() {
            private float startY = 0;
            private long startTime = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        startTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - startY;
                        if (dy > 0) {
                            setTranslationY(dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float totalDy = event.getRawY() - startY;
                        long elapsed = System.currentTimeMillis() - startTime;
                        // 若下滑超过 35dp，或者点击手柄（移动极小且耗时短），立即关闭
                        if (totalDy > dp(35) || (Math.abs(totalDy) < dp(15) && elapsed < 350)) {
                            if (mDismissListener != null) {
                                mDismissListener.onDismiss();
                            }
                        } else {
                            animate().translationY(0).setDuration(160).start();
                        }
                        return true;
                }
                return false;
            }
        });

        mHandleView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mDismissListener != null) {
                    mDismissListener.onDismiss();
                }
            }
        });
    }

    protected int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
