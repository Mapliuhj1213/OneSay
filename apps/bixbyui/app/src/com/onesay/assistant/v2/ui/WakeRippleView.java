package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 小爱水光实机取色唤醒动效视图（WakeRippleView）
 *
 * 1:1 复刻 _probe/wake_animation_preview.html 规格书：
 * 1. 【屏幕压暗层】黑底 (#0A0A14)，透明度 0 -> 0.22；
 * 2. 【磨砂面纱】自底部 H*0.22 升起的线性 wash 渐变，避免 8-bit banding 阶梯硬边；
 * 3. 【底部白核辉光】中心 (0.5W, 0.88H)，半径 0.95W，白核 (#FFFFFF) -> 淡紫 (#CCC1EC) -> 薰衣草灰 (#B9A8D8)；
 * 4. 【软涟漪 ×3】高斯软环亮度分布 a·exp(-((r-r0)/σ)²)，σ≈0.045W，无生硬描边线，轻柔律动；
 * 5. 【「我在听」上浮文本】居中悬浮于面板上方，白光阴影，淡入上浮 8dp；
 * 6. 【性能保障】完全基于硬件加速 Canvas 自绘，严格限制在 30fps，低功耗平滑运行。
 */
public class WakeRippleView extends View {

    private final Paint mDimPaint;
    private final Paint mVeilPaint;
    private final Paint mGlowPaint;
    private final Paint mRipplePaint;
    private final Paint mTextPaint;

    private boolean mIsRunning = false;
    private long mStartTime = 0;
    private float mDensity = 1.0f;
    private float mFadeAlpha = 1.0f;

    // 配色（实机取色：白核、淡紫、灰紫、隔纱青）
    private final int[] mColorCore = {255, 255, 255};
    private final int[] mColorMid1 = {204, 193, 236};
    private final int[] mColorMid2 = {185, 168, 216};
    private final int[] mColorVeil = {230, 236, 248};

    public WakeRippleView(Context context) {
        this(context, null);
    }

    public WakeRippleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;

        mDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDimPaint.setStyle(Paint.Style.FILL);

        mVeilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mVeilPaint.setStyle(Paint.Style.FILL);

        mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGlowPaint.setStyle(Paint.Style.FILL);

        mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRipplePaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(dp(13.5f));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);
        mTextPaint.setShadowLayer(dp(8f), 0, dp(1f), Color.parseColor("#7864DC"));
    }

    public void startRipple() {
        mStartTime = SystemClock.uptimeMillis();
        mIsRunning = true;
        mFadeAlpha = 1.0f;
        setVisibility(VISIBLE);
        postInvalidate();
    }

    public void stopRipple() {
        mIsRunning = false;
        postInvalidate();
    }

    public void fadeOutRipple(final Runnable onComplete) {
        animate().cancel();
        animate().alpha(0.0f).setDuration(160).withEndAction(new Runnable() {
            @Override
            public void run() {
                mIsRunning = false;
                setVisibility(GONE);
                setAlpha(1.0f);
                if (onComplete != null) onComplete.run();
            }
        }).start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || !mIsRunning) return;

        long elapsed = SystemClock.uptimeMillis() - mStartTime;
        float t = (float) elapsed;

        // 时间轴包络
        float veilE = easeOutCubic(clamp01(t / 500f));
        float bloomE = easeOutCubic(clamp01((t - 100f) / 900f));
        float breathe = (t > 1400f) ? 1.0f + 0.05f * (float) Math.sin(2.0 * Math.PI * (t - 1400f) / 2600.0) : 1.0f;
        float G = 1.0f * bloomE * breathe * mFadeAlpha;

        float cx = w / 2f;
        float cy = h * 0.88f;

        // 1. 屏幕压暗层 (#0A0A14, 最大 0.22f)
        float dimAlpha = 0.22f * veilE * mFadeAlpha;
        if (dimAlpha > 0.002f) {
            mDimPaint.setColor(Color.parseColor("#0A0A14"));
            mDimPaint.setAlpha((int) (255 * dimAlpha));
            canvas.drawRect(0, 0, w, h, mDimPaint);
        }

        // 2. 磨砂面纱 (底部线性 wash 渐变，多 stop 缓升)
        float va = 0.30f * veilE * mFadeAlpha;
        if (va > 0.002f) {
            int c0 = argb(0f, mColorVeil);
            int c1 = argb(va * 0.06f, mColorVeil);
            int c2 = argb(va * 0.18f, mColorVeil);
            int c3 = argb(va * 0.42f, mColorVeil);
            int c4 = argb(va * 0.75f, mColorVeil);
            int c5 = argb(va, mColorVeil);
            LinearGradient lg = new LinearGradient(
                    0, h * 0.22f, 0, h,
                    new int[]{c0, c1, c2, c3, c4, c5},
                    new float[]{0.0f, 0.28f, 0.52f, 0.74f, 0.90f, 1.0f},
                    Shader.TileMode.CLAMP
            );
            mVeilPaint.setShader(lg);
            canvas.drawRect(0, 0, w, h, mVeilPaint);
        }

        // 3. 底部白核辉光 (主角：白核 -> 淡紫 -> 薰衣草灰 -> 透明)
        if (G > 0.003f) {
            float R = Math.max(2f, w * 0.95f * G);
            int gc0 = argb(0.92f * Math.min(1f, G), mColorCore);
            int gc1 = argb(0.72f * Math.min(1f, G), mColorCore);
            int gc2 = argb(0.42f * Math.min(1f, G), mColorMid1);
            int gc3 = argb(0.15f * Math.min(1f, G), mColorMid2);
            int gc4 = argb(0f, mColorMid2);

            RadialGradient rg = new RadialGradient(
                    cx, cy, R,
                    new int[]{gc0, gc1, gc2, gc3, gc4},
                    new float[]{0.0f, 0.18f, 0.42f, 0.70f, 1.0f},
                    Shader.TileMode.CLAMP
            );
            mGlowPaint.setShader(rg);
            canvas.drawRect(cx - R, cy - R, cx + R, cy + R, mGlowPaint);
        }

        // 4. 软涟漪 ×3 (高斯软环，无硬边描边)
        if (bloomE > 0.15f && mFadeAlpha > 0.01f) {
            for (int i = 0; i < 3; i++) {
                float ph = (((t - 250f) / 2100f) + (i / 3f)) % 1.0f;
                if (ph < 0f) continue;
                float rr = (0.30f + 1.15f * easeOutCubic(ph)) * w * 0.62f * Math.min(1f, G);
                float aa = 0.15f * bell(ph) * 0.9f * Math.min(1f, G);
                drawSoftRing(canvas, cx, cy, rr, Math.max(dp(3f), w * 0.045f), aa);
            }
        }

        // 5. 「我在听」上浮文本 (浮现于面板之上)
        float lE = clamp01((t - 800f) / 320f);
        if (lE > 0.01f) {
            float textAlpha = easeOutCubic(lE) * mFadeAlpha;
            float textY = (cy - dp(65f)) + ((1f - easeOutCubic(lE)) * dp(8f));
            mTextPaint.setAlpha((int) (255 * textAlpha));
            canvas.drawText("我  在  听", cx, textY, mTextPaint);
        }

        if (mIsRunning && isShown()) {
            postInvalidateDelayed(33); // 30fps 定频节拍
        }
    }

    private void drawSoftRing(Canvas canvas, float cx, float cy, float r0, float sig, float amp) {
        float rIn = Math.max(0f, r0 - 3f * sig);
        float rOut = r0 + 3f * sig;
        if (rOut <= 1f || amp <= 0.003f) return;

        float span = rOut - rIn;
        if (span <= 0.001f) return;

        float n1 = clamp01((r0 - sig - rIn) / span);
        float n2 = clamp01((r0 - rIn) / span);
        float n3 = clamp01((r0 + sig - rIn) / span);

        int c0 = argb(0f, mColorMid1);
        int c1 = argb(amp * 0.42f, mColorMid1);
        int c2 = argb(amp, mColorMid1);
        int c3 = argb(amp * 0.42f, mColorMid1);
        int c4 = argb(0f, mColorMid1);

        RadialGradient g = new RadialGradient(
                cx, cy, rOut,
                new int[]{c0, c1, c2, c3, c4},
                new float[]{0f, n1, n2, n3, 1f},
                Shader.TileMode.CLAMP
        );
        mRipplePaint.setShader(g);
        canvas.drawCircle(cx, cy, rOut, mRipplePaint);
    }

    private static int argb(float a, int[] rgb) {
        int alpha = (int) (255f * clamp01(a));
        return Color.argb(alpha, rgb[0], rgb[1], rgb[2]);
    }

    private static float easeOutCubic(float x) {
        float inv = 1.0f - x;
        return 1.0f - inv * inv * inv;
    }

    private static float clamp01(float x) {
        if (x < 0f) return 0f;
        if (x > 1f) return 1f;
        return x;
    }

    private static float bell(float x) {
        return (float) Math.pow(Math.sin(Math.PI * clamp01(x)), 1.4);
    }

    private float dp(float v) {
        return v * mDensity;
    }
}
