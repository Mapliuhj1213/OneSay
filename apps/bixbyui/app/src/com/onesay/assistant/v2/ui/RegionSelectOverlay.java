package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * [s1] #8 圈选即问的全屏遮罩（薄视图；几何换算在 {@code s1/RegionSelection}）。
 *
 * <p>半透明暗层 + 顶部提示 + 「取消」按钮；手指按下拖动画出一个虚线框，松手回调 {@link Listener#onSelected}，
 * 坐标用 {@code getRawX/Y}（屏幕像素，与 uiautomator bounds、screencap 同一坐标系）。
 */
public class RegionSelectOverlay extends FrameLayout {

    public interface Listener {
        void onSelected(float x0, float y0, float x1, float y1);

        void onCancel();
    }

    private final Paint mDim = new Paint();
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClear = new Paint();
    private final TextView mHint;
    private Listener mListener;
    private boolean mDragging;
    private float mX0, mY0, mX1, mY1;
    private final int[] mLoc = new int[2];

    public RegionSelectOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        mDim.setColor(Color.parseColor("#66000000"));
        mClear.setColor(Color.parseColor("#1A5E9EFF"));
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(2));
        mStroke.setColor(Color.parseColor("#5E9EFF"));
        mStroke.setPathEffect(new DashPathEffect(new float[] {dp(8), dp(5)}, 0));

        mHint = new TextView(context);
        mHint.setText("拖动框选要问的区域 · 松手确认");
        mHint.setTextColor(Color.WHITE);
        mHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        mHint.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.parseColor("#CC1C1C1E"));
        hb.setCornerRadius(dp(18));
        mHint.setBackground(hb);
        LayoutParams hp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = dp(64);
        addView(mHint, hp);

        TextView cancel = new TextView(context);
        cancel.setText("✕ 取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Color.parseColor("#CCFF453A"));
        cb.setCornerRadius(dp(20));
        cancel.setBackground(cb);
        cancel.setContentDescription("取消圈选");
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onCancel();
            }
        });
        LayoutParams cp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = dp(72);
        addView(cancel, cp);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setHint(String text) {
        mHint.setText(text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                mX0 = mX1 = ev.getRawX();
                mY0 = mY1 = ev.getRawY();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) return true;
                mX1 = ev.getRawX();
                mY1 = ev.getRawY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!mDragging) return true;
                mDragging = false;
                mX1 = ev.getRawX();
                mY1 = ev.getRawY();
                invalidate();
                if (mListener != null) mListener.onSelected(mX0, mY0, mX1, mY1);
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawRect(0, 0, getWidth(), getHeight(), mDim);
        if (mX0 == mX1 && mY0 == mY1) return;
        // 原始坐标 → 视图坐标（遮罩铺满整屏时偏移为 0）
        getLocationOnScreen(mLoc);
        RectF r = new RectF(Math.min(mX0, mX1) - mLoc[0], Math.min(mY0, mY1) - mLoc[1],
                Math.max(mX0, mX1) - mLoc[0], Math.max(mY0, mY1) - mLoc[1]);
        c.drawRect(r, mClear);
        c.drawRect(r, mStroke);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
