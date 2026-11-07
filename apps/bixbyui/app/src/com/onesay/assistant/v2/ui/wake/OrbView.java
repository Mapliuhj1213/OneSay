package com.onesay.assistant.v2.ui.wake;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.ui.motion.MotionSpec;

/**
 * [R13 唤醒界面 v2] 卡片右侧的 30dp 柔光小球（替代「正在听」字样 + 7 柱声纹 + 「打字」胶囊）：随 RMS 呼吸、缓慢自转。
 * 小球预烘焙一张位图（苔绿 / 琥珀 / 青的扫掠渐变 + 左上高光 + 暗边），每帧只旋转 + 缩放 + 改 alpha，不新建 Shader。
 * 点按小球 = 打字（D11=A）。
 */
public final class OrbView extends View {

    private final float d;
    private Bitmap mOrb;
    private final Paint mP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** ★ r2/input：「快要发送」倒计时环（尾静音 0.4 s 后出现，满一圈 = 判定说完）。 */
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF mRingRect = new android.graphics.RectF();
    private final IslandGeometry.Spring mRingV = MotionSpec.GLOW_LEVEL.spring(0f);
    private final IslandGeometry.Spring mLevel = MotionSpec.GLOW_LEVEL.spring(0f);
    private final IslandGeometry.Spring mShow = MotionSpec.CHIP_IN.spring(0f);
    private final VoiceLevel.Envelope mEnv = new VoiceLevel.Envelope();
    private long mLast;
    private float mAngle;
    private boolean mActive;
    private final Runnable mNext = new Runnable() {
        @Override
        public void run() {
            postInvalidateOnAnimation();
        }
    };

    public OrbView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setContentDescription("打字");
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeCap(Paint.Cap.ROUND);
        mRing.setStrokeWidth(2f * d);
        mRing.setColor(0xFF84AE64);   // 苔绿（岛 / 控制台主色）
        mRingTrack.setStyle(Paint.Style.STROKE);
        mRingTrack.setStrokeWidth(2f * d);
        mRingTrack.setColor(0x33FFFFFF);
    }

    public void setActive(boolean on, boolean instant) {
        mActive = on;
        mShow.t = on ? 1f : 0f;
        if (instant) mShow.snap();
        mLast = 0L;
        postInvalidateOnAnimation();
    }

    private void bake(int size) {
        if (mOrb != null && mOrb.getWidth() == size) return;
        if (mOrb != null) mOrb.recycle();
        mOrb = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mOrb);
        float r = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new SweepGradient(r, r, WakeGlowView.PALETTE, null));
        c.drawCircle(r, r, r * 0.92f, p);
        p.setShader(new RadialGradient(r * 0.72f, r * 0.62f, r * 0.9f, new int[] {0xCCFFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r * 0.92f, p);
        p.setShader(new RadialGradient(r, r, r * 0.92f, new int[] {0x00000000, 0x00000000, 0x66000000},
                new float[] {0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r * 0.92f, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        drawOrb(c);
    }

    private void drawOrb(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int size = Math.min(w, h);
        bake(size);
        long now = SystemClock.uptimeMillis();
        float dt = mLast == 0L ? 0.016f : Math.min(0.033f, (now - mLast) / 1000f);
        mLast = now;
        float norm = mActive && VoiceLevel.fresh(SystemClock.elapsedRealtime()) ? VoiceLevel.norm(VoiceLevel.rms(), VoiceLevel.floor()) : 0f;
        mLevel.t = mEnv.step(norm, dt);
        mLevel.step(dt);
        mShow.step(dt);
        float e = Math.max(0f, Math.min(1.2f, mLevel.v));
        mAngle += dt * (40f + 120f * e);
        float breath = mActive && e < 0.05f ? 0.04f * (float) Math.sin(2 * Math.PI * 0.25 * now / 1000.0) : 0f;
        float sc = (0.86f + 0.14f * Math.min(1.1f, Math.max(0f, mShow.v))) * (1f + 0.10f * e + breath);
        mP.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, mShow.v))));
        c.save();
        c.translate(w / 2f, h / 2f);
        c.rotate(mAngle);
        c.scale(sc, sc);
        c.drawBitmap(mOrb, -size / 2f, -size / 2f, mP);
        c.restore();
        // ★ r2/input：倒计时环 —— 说话 / 正常换气时不画；尾静音超过 0.4 s 才出现，一圈走满 = 马上发送
        mRingV.t = mActive ? ListenProgress.get(SystemClock.elapsedRealtime()) : 0f;
        mRingV.step(dt);
        float rp = Math.max(0f, Math.min(1f, mRingV.v));
        if (rp > 0.01f) {
            float inset = mRing.getStrokeWidth() / 2f + 0.5f;
            mRingRect.set(inset, inset, w - inset, h - inset);
            int a = Math.round(255 * Math.min(1f, rp * 4f) * Math.max(0f, Math.min(1f, mShow.v)));
            mRingTrack.setAlpha(Math.round(a * 0.2f));
            mRing.setAlpha(a);
            c.drawOval(mRingRect, mRingTrack);
            c.drawArc(mRingRect, -90f, 360f * rp, false, mRing);
        }
        boolean live = isAttachedToWindow() && getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE;
        if (live && (mActive || !mShow.settled(0.004f))) postOnAnimation(mNext);   // 30 fps：小球慢转 + 呼吸，不需要每个 vsync
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mNext);   // [R13] 摘窗即停帧
        if (mOrb != null) mOrb.recycle();
        mOrb = null;
    }
}
