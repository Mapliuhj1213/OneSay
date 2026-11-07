package com.onesay.assistant.v2.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * 5 条式流体音频波形动态指示器（WaveVisualizerView）
 *
 * 1:1 对标小爱水光动画规范中的头部状态波形条：
 * - 5 条垂直光柱，底端对齐；
 * - 宽度 2.5dp，柱间距 2.5dp，圆角 1.2dp；
 * - 颜色为 Apple/HyperOS 荧光蓝 (#0A84FF)，透明度 85%；
 * - 依正弦波谐波呼吸摆动（周期 ~1.1s），帧率限制在 30fps，兼顾细腻动感与超低功耗。
 */
public class WaveVisualizerView extends View {

    private final Paint mPaint;
    private final RectF mBarRect;
    private final float[] mBaseHeightsDp = {5f, 11f, 7f, 13f, 6f};
    private boolean mIsActive = true;
    private float mDensity = 1.0f;

    public WaveVisualizerView(Context context) {
        this(context, null);
    }

    public WaveVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(Color.parseColor("#0A84FF"));
        mPaint.setAlpha((int) (255 * 0.85f));
        mPaint.setStyle(Paint.Style.FILL);

        mBarRect = new RectF();
    }

    public void setActive(boolean active) {
        this.mIsActive = active;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (dp(2.5f) * 5 + dp(2.5f) * 4);
        int h = (int) dp(18f);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float barWidth = dp(2.5f);
        float barGap = dp(2.5f);
        float cornerRadius = dp(1.2f);
        float totalWidth = barWidth * 5 + barGap * 4;
        float startX = (width - totalWidth) / 2f;

        long now = SystemClock.uptimeMillis();
        float t = (now % 100000L);

        for (int k = 0; k < 5; k++) {
            float baseH = dp(mBaseHeightsDp[k]);
            float currentH;
            if (mIsActive) {
                float waveFactor = 0.7f + 0.3f * (float) Math.sin(t / 180.0 + k * 1.1);
                currentH = Math.max(dp(3f), baseH * waveFactor);
            } else {
                currentH = dp(3f);
            }

            float left = startX + k * (barWidth + barGap);
            float top = height - currentH;
            float right = left + barWidth;
            float bottom = height;

            mBarRect.set(left, top, right, bottom);
            canvas.drawRoundRect(mBarRect, cornerRadius, cornerRadius, mPaint);
        }

        if (mIsActive && isShown()) {
            postInvalidateDelayed(33); // 30fps
        }
    }

    private float dp(float v) {
        return v * mDensity;
    }
}
