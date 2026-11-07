package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.onesay.assistant.v2.island.IslandGeometry;

/**
 * [console R12] 自绘开关（方案 §6.4：40×24 轨、18 圆钮；系统 Switch 在 Android 8 上是 Material 1 样式，配不上新配色）。
 * 圆钮位移 + 轨道颜色用岛的弹簧（ω22 ζ0.86）；系统动画缩放为 0 时瞬间到位。
 * 本身不处理点击（整行点 = 切换，由行负责），只负责画和动画。
 */
public final class ToggleView extends View {

    private final ConsoleTheme mT;
    private final float mD;
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private final IslandGeometry.Spring mSpring;
    private boolean mOn;
    private long mLastFrame;

    public ToggleView(Context ctx, ConsoleTheme t, boolean on) {
        super(ctx);
        mT = t;
        mD = ctx.getResources().getDisplayMetrics().density;
        mOn = on;
        mSpring = new IslandGeometry.Spring(on ? 1f : 0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public boolean isOn() {
        return mOn;
    }

    public void setOn(boolean on, boolean animate) {
        if (mOn == on && mSpring.t == (on ? 1f : 0f)) return;
        mOn = on;
        mSpring.t = on ? 1f : 0f;
        if (!animate || !ConsoleNav.animationsOn(getContext()) || !isAttachedToWindow()) {
            mSpring.snap();
            invalidate();
            return;
        }
        mLastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(48 * mD), Math.round(32 * mD));
    }

    static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        if (!mSpring.settled(0.002f)) {
            float dt = mLastFrame == 0 ? 1f / 60f : Math.min(0.05f, (now - mLastFrame) / 1e9f);
            mSpring.step(dt);
            mLastFrame = now;
            postInvalidateOnAnimation();
        } else {
            mSpring.snap();
            mLastFrame = 0;
        }
        float p = Math.max(0f, Math.min(1f, mSpring.v));
        float tw = 40 * mD, th = 24 * mD;
        float left = getWidth() - tw - 2 * mD, top = (getHeight() - th) / 2f;
        mR.set(left, top, left + tw, top + th);
        mTrack.setColor(blend(mT.switchOffTrack, mT.accent, p));
        c.drawRoundRect(mR, th / 2f, th / 2f, mTrack);
        float r = 9 * mD;
        float x0 = left + 3 * mD + r, x1 = left + tw - 3 * mD - r;
        mThumb.setColor(blend(mT.switchOffThumb, mT.onAccent, p));
        c.drawCircle(x0 + (x1 - x0) * mSpring.v, top + th / 2f, r, mThumb);
    }
}
