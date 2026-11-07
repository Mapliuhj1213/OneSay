package com.onesay.assistant.v2.ui;

import android.view.animation.Interpolator;

/**
 * 物理弹簧阻尼求解器（SpringSolver / Interpolator）
 *
 * 遵循 Apple 规范与小爱水光动画规范（damping ratio ζ=0.8, response T=0.32s）：
 * 1% 收敛准则：
 *   ωn = 4.605 / (ζ·T) = 17.988 rad/s (≈ 18.0)
 *   ωd = ωn · √(1 - ζ²) = 18.0 · 0.6 = 10.8 rad/s
 *   k = ωn² = 324.0
 *   c = 2·ζ·ωn = 28.8
 *
 * 解析解（单位阶跃零初速响应）：
 *   x(t) = 1 - e^(-14.4·t) · [cos(10.8·t) + 1.3333 · sin(10.8·t)]
 *
 * 在 t=0 时 x(0)=0，在 t=0.32s 时已进入 1% 稳态范围，最大超调仅 ~1.5%，手感温润克制。
 */
public class SpringSolver implements Interpolator {

    private final float mZeta;
    private final float mPeriod;
    private final float mOmegaN;
    private final float mOmegaD;
    private final float mDecay;
    private final float mSinFactor;

    public SpringSolver() {
        this(0.8f, 0.32f);
    }

    public SpringSolver(float zeta, float period) {
        this.mZeta = zeta;
        this.mPeriod = period;
        this.mOmegaN = 4.605f / (zeta * period);
        float underSqrt = 1.0f - zeta * zeta;
        this.mOmegaD = mOmegaN * (float) Math.sqrt(underSqrt > 0 ? underSqrt : 0.0001f);
        this.mDecay = zeta * mOmegaN;
        this.mSinFactor = mDecay / mOmegaD;
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1.0f) return 1.0f;
        float t = input * mPeriod * 1.4f; // 归一化时间映射到物理时间
        float exp = (float) Math.exp(-mDecay * t);
        float cos = (float) Math.cos(mOmegaD * t);
        float sin = (float) Math.sin(mOmegaD * t);
        float value = 1.0f - exp * (cos + mSinFactor * sin);
        if (value > 1.02f) value = 1.02f; // 物理超调限幅
        return value;
    }

    /** 物理半隐式欧拉单步步进（供模拟器或离线数值验证） */
    public static float step(float x, float v, float dt, float target, float k, float c) {
        float a = -k * (x - target) - c * v;
        v += a * dt;
        x += v * dt;
        return x;
    }
}
