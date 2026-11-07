package com.onesay.assistant.v2.ui.motion;

import com.onesay.assistant.v2.island.IslandGeometry;

/**
 * [R13 唤醒界面 v2] 弹簧规格表（纯 Java，JVM 可测）。
 *
 * <p>记号同 WWDC23「Animate with springs」：duration（感知时长）+ bounce。mass = 1 时
 * {@code ω = 2π / duration}、{@code ζ = 1 − bounce}、{@code stiffness = ω²}、{@code damping = 2ζω}。
 * 数值来自 stage-20261011/wake-mockups-v2/springs_v2.py（方案 v2.6 表），JVM 测试逐条核对。
 *
 * <p>岛的 APPEAR（ω22 ζ0.86 门槛 0.8）不在这里定义 —— 它仍然只住在 {@link IslandGeometry}，一个字都不改。
 * 积分器复用 {@link IslandGeometry.Spring}（半隐式欧拉，每帧 4 子步）。
 */
public final class MotionSpec {

    private MotionSpec() {}

    public static final class Spec {
        public final String name;
        public final float duration;
        public final float bounce;

        Spec(String name, float duration, float bounce) {
            this.name = name;
            this.duration = duration;
            this.bounce = bounce;
        }

        public float omega() {
            return omegaOf(duration);
        }

        public float zeta() {
            return zetaOf(bounce);
        }

        public float stiffness() {
            float w = omega();
            return w * w;
        }

        public float damping() {
            return 2f * zeta() * omega();
        }

        /** 新弹簧，静止于 v（目标 = v）。 */
        public IslandGeometry.Spring spring(float v) {
            return new IslandGeometry.Spring(v, omega(), zeta());
        }

        /** 减弱动态效果：bounce 归零（ζ = 1），时长不变。 */
        public Spec reduced() {
            return bounce <= 0f ? this : new Spec(name + "~", duration, 0f);
        }

        /** 把参数套到已有弹簧上（保留速度：被打断时带着速度改目标，不跳变）。 */
        public void applyTo(IslandGeometry.Spring s) {
            s.setParams(omega(), zeta());
        }

        @Override
        public String toString() {
            return name + "(" + duration + "s/" + bounce + " → ω" + String.format(java.util.Locale.US, "%.2f", omega())
                    + " ζ" + String.format(java.util.Locale.US, "%.2f", zeta()) + ")";
        }
    }

    public static float omegaOf(float durationS) {
        return (float) (2.0 * Math.PI / Math.max(0.05f, durationS));
    }

    public static float zetaOf(float bounce) {
        return Math.max(0.05f, 1f - bounce);
    }

    // ---- v2.6 表（duration s / bounce）----
    public static final Spec CARD_IN = new Spec("CARD_IN", 0.50f, 0.18f);
    public static final Spec CARD_RESIZE = new Spec("CARD_RESIZE", 0.45f, 0.10f);
    public static final Spec TEXT_IN = new Spec("TEXT_IN", 0.40f, 0.00f);
    public static final Spec CHIP_IN = new Spec("CHIP_IN", 0.42f, 0.20f);
    public static final Spec GLOW_INHALE = new Spec("GLOW_INHALE", 0.70f, 0.00f);
    public static final Spec GLOW_LEVEL = new Spec("GLOW_LEVEL", 0.24f, 0.25f);
    public static final Spec MORPH_TOP = new Spec("MORPH_TOP", 0.38f, 0.10f);
    public static final Spec MORPH_BOTTOM = new Spec("MORPH_BOTTOM", 0.54f, 0.12f);
    public static final Spec MORPH_WIDTH = new Spec("MORPH_WIDTH", 0.44f, 0.08f);
    public static final Spec MORPH_RADIUS = new Spec("MORPH_RADIUS", 0.36f, 0.00f);
    public static final Spec ISLAND_BULGE = new Spec("ISLAND_BULGE", 0.32f, 0.15f);
    public static final Spec UNMORPH = new Spec("UNMORPH", 0.52f, 0.16f);
    public static final Spec DISMISS = new Spec("DISMISS", 0.40f, 0.00f);
    public static final Spec PRESS = new Spec("PRESS", 0.30f, 0.28f);

    /** 光的整体亮灭（GLOW_IN / GLOW_OUT 在 v2 里不再单列，用临界阻尼的 TEXT_IN 节奏即可）。 */
    public static final Spec GLOW_FADE = new Spec("GLOW_FADE", 0.35f, 0.00f);

    public static Spec[] all() {
        return new Spec[] {CARD_IN, CARD_RESIZE, TEXT_IN, CHIP_IN, GLOW_INHALE, GLOW_LEVEL, MORPH_TOP, MORPH_BOTTOM,
                MORPH_WIDTH, MORPH_RADIUS, ISLAND_BULGE, UNMORPH, DISMISS, PRESS};
    }

    /** ISLAND_BULGE 脉冲初速度（静止于 0 时给的速度，单位 /s）：宽 +6%、高 −8%，峰值约 53 ms（方案 v2.6）。 */
    public static final float BULGE_VX = 2.97f;
    public static final float BULGE_VY = -3.97f;

    /** 形变时高度上限：起始高度的 2.2 倍（防「拉面」）。 */
    public static final float MORPH_MAX_STRETCH = 2.2f;

    /**
     * 数值仿真（1 ms 步长、从 x0/v0 起步、目标 1）：返回 {超调百分比, 进入 ±eps 后不再离开的时刻 s}。
     * 与 springs_v2.py 的 sim() 同一算法（显式半隐式欧拉），用于 JVM 测试核对表格。
     */
    public static float[] simulate(float omega, float zeta, float x0, float v0, float eps) {
        double x = x0, v = v0, t = 0, peak = x0, last = 0, dt = 1.0 / 1000;
        while (t < 3) {
            double a = -omega * omega * (x - 1) - 2 * zeta * omega * v;
            v += a * dt;
            x += v * dt;
            t += dt;
            peak = Math.max(peak, x);
            if (Math.abs(x - 1) > eps) last = t;
        }
        return new float[] {(float) ((peak - 1) * 100), (float) last};
    }

    /** 脉冲弹簧（静止于 0、初速度 v0）：返回 {峰值, 峰值时刻 s, 最低值}。 */
    public static float[] kick(float omega, float zeta, float v0) {
        double x = 0, v = v0, t = 0, pk = 0, tp = 0, mn = 0, dt = 1.0 / 1000;
        while (t < 2) {
            double a = -omega * omega * x - 2 * zeta * omega * v;
            v += a * dt;
            x += v * dt;
            t += dt;
            if (x > pk) {
                pk = x;
                tp = t;
            }
            mn = Math.min(mn, x);
        }
        return new float[] {(float) pk, (float) tp, (float) mn};
    }
}
