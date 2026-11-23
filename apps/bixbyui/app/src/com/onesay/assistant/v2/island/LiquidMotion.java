package com.onesay.assistant.v2.island;

/**
 * [island-cutout-1118] 灵动岛的「液态」动效：弹簧解析解、每个轴各自的弹簧参数、底边下垂（水滴感）、
 * 孔 ↔ 岛之间的 metaball 颈（像从摄像头里挤出来）、轮廓路径。纯 Java（JVM 测 + JVM 出演示帧）。
 *
 * <p>Android 端只用这里的结果画<b>一个 View 的一条 Path</b>：窗口固定、不 relayout、每帧不分配对象（轮廓写进复用的 Path）。
 *
 * <h3>弹簧</h3>
 * 二阶阻尼振子 x'' = −ω²(x − T) − 2ζω x'。{@link #step} 用解析解推进（欠阻尼 / 临界 / 过阻尼三种分支），任意 dt 都精确、无发散；
 * 岛上实际推进用的仍是 {@link IslandGeometry.Spring}（半隐式欧拉、每帧 4 子步，R10 起真机调过手感）；它与解析解在 60fps 下差 ≤ 3% 行程、
 * 终点一致（单测核对）。JVM 演示帧也走同一个 Spring.step，所以演示与真机同一套数；解析解用于测试与过冲 / 落定时间的推算。
 * response（秒）→ ω = 2π / response，与 SwiftUI 的 spring(response:dampingFraction:) 同口径。
 */
public final class LiquidMotion {

    private LiquidMotion() {}

    // ================================================================== 弹簧数学

    /** 解析推进一步：xv = {位置, 速度}，原地更新。 */
    public static void step(double[] xv, double target, double omega, double zeta, double dt) {
        double x0 = xv[0] - target, v0 = xv[1];
        if (dt <= 0) return;
        if (omega <= 0) {
            xv[0] = target;
            xv[1] = 0;
            return;
        }
        double x, v;
        if (zeta < 1.0 - 1e-6) {
            double wd = omega * Math.sqrt(1 - zeta * zeta);
            double e = Math.exp(-zeta * omega * dt);
            double c = Math.cos(wd * dt), s = Math.sin(wd * dt);
            double b = (v0 + zeta * omega * x0) / wd;
            x = e * (x0 * c + b * s);
            // x(t) = e^{−ζωt}(x0·cos ωd t + b·sin ωd t) 求导化简
            v = e * (v0 * c - (x0 * omega * omega + zeta * omega * v0) / wd * s);
        } else if (zeta <= 1.0 + 1e-6) {
            double e = Math.exp(-omega * dt);
            double b = v0 + omega * x0;
            x = e * (x0 + b * dt);
            v = e * (b - omega * (x0 + b * dt));
        } else {
            double sq = Math.sqrt(zeta * zeta - 1);
            double r1 = -omega * (zeta - sq), r2 = -omega * (zeta + sq);
            double c2 = (v0 - r1 * x0) / (r2 - r1), c1 = x0 - c2;
            double e1 = Math.exp(r1 * dt), e2 = Math.exp(r2 * dt);
            x = c1 * e1 + c2 * e2;
            v = c1 * r1 * e1 + c2 * r2 * e2;
        }
        xv[0] = target + x;
        xv[1] = v;
    }

    /** 从静止阶跃时的最大过冲比例（ζ ≥ 1 为 0）：exp(−ζπ / √(1−ζ²))。 */
    public static double overshoot(double zeta) {
        if (zeta >= 1) return 0;
        return Math.exp(-zeta * Math.PI / Math.sqrt(1 - zeta * zeta));
    }

    /** 从静止单位阶跃后，|误差| 最后一次超过 eps（比例）的时刻（秒）；用解析解按 1 ms 扫，扫到 10 s 为止。 */
    public static double settleTime(double omega, double zeta, double eps) {
        double[] xv = {0, 0};
        double last = 0;
        for (int i = 1; i <= 10000; i++) {
            step(xv, 1.0, omega, zeta, 0.001);
            if (Math.abs(xv[0] - 1.0) > eps) last = i * 0.001;
        }
        return last;
    }

    public static float omegaOf(float responseS) {
        return IslandGeometry.omegaOf(responseS);
    }

    // ================================================================== 各阶段 · 各轴的弹簧

    /** 形变阶段。APPEAR = 面板收成岛（R11/R13 表扬过，ω22 ζ0.86 原样锁死）；EMERGE = 从孔里长出来。 */
    public enum Phase { EMERGE, EXPAND, COLLAPSE, HIDE, APPEAR }

    /** 轴：宽、高、圆角、水平中心、上沿、颈。 */
    public enum Axis { W, H, R, X, Y, NECK }

    /**
     * {response 秒, ζ}。液态的关键是三件事一起动但<b>不同步</b>：
     * 展开时宽先到（ζ 0.76，约 2.5% 过冲）、高稍晚且更弹（ζ 0.74，约 3.2%），像液体先摊开再坠下来；
     * 收起时反过来，高先收（0.34 s）、宽后收，最后缩回孔里。
     */
    public static float[] params(Phase p, Axis a) {
        switch (p) {
            case APPEAR:
                if (a == Axis.X || a == Axis.Y) return new float[] {0.32f, 1.0f};
                if (a == Axis.NECK) return new float[] {0.45f, 1.0f};
                return new float[] {(float) (2 * Math.PI / IslandGeometry.SPRING_OMEGA), IslandGeometry.SPRING_ZETA};
            case EMERGE:
                switch (a) {
                    case W: return new float[] {0.42f, 0.70f};
                    case H: return new float[] {0.38f, 0.78f};
                    case R: return new float[] {0.38f, 0.85f};
                    case NECK: return new float[] {0.50f, 1.0f};
                    default: return new float[] {0.38f, 0.88f};
                }
            case EXPAND:
                switch (a) {
                    case W: return new float[] {0.44f, 0.76f};
                    case H: return new float[] {0.50f, 0.74f};
                    case R: return new float[] {0.46f, 0.85f};
                    case NECK: return new float[] {0.40f, 1.0f};
                    default: return new float[] {0.44f, 0.88f};
                }
            case COLLAPSE:
                switch (a) {
                    case W: return new float[] {0.42f, 0.84f};
                    case H: return new float[] {0.34f, 0.88f};
                    case R: return new float[] {0.36f, 0.92f};
                    case NECK: return new float[] {0.40f, 1.0f};
                    default: return new float[] {0.38f, 0.92f};
                }
            case HIDE:
            default:
                switch (a) {
                    case W: return new float[] {0.36f, 0.90f};
                    case H: return new float[] {0.30f, 0.95f};
                    case R: return new float[] {0.30f, 1.0f};
                    case NECK: return new float[] {0.22f, 1.0f};
                    default: return new float[] {0.32f, 1.0f};
                }
        }
    }

    /** {ω, ζ}。 */
    public static float[] spring(Phase p, Axis a) {
        float[] q = params(p, a);
        if (p == Phase.APPEAR && a != Axis.X && a != Axis.Y && a != Axis.NECK) {
            return new float[] {IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA};
        }
        return new float[] {omegaOf(q[0]), q[1]};
    }

    /** IslandGeometry 的方向判定 → 阶段（从隐藏出现：fromOrigin = 不是面板接力 → EMERGE）。 */
    public static Phase phaseOf(IslandGeometry.Motion m, boolean appearLock) {
        if (appearLock) return Phase.APPEAR;
        switch (m) {
            case APPEAR: return Phase.EMERGE;
            case EXPAND: return Phase.EXPAND;
            case COLLAPSE: return Phase.COLLAPSE;
            case HIDE: return Phase.HIDE;
            default: return Phase.COLLAPSE;
        }
    }

    // ================================================================== 「减少动画」

    /**
     * Settings.Global.animator_duration_scale → 每帧 dt 的倍率：0 = 关动画（返回 0，调用方直接 snap 到终点），
     * 0.5 = 两倍快，2 = 两倍慢；异常值按 1。
     */
    public static float timeScale(float animatorDurationScale) {
        if (Float.isNaN(animatorDurationScale) || animatorDurationScale < 0f) return 1f;
        if (animatorDurationScale == 0f) return 0f;
        return 1f / Math.min(10f, animatorDurationScale);
    }

    // ================================================================== 水滴：底边随竖直速度下垂

    /** 底边中点下垂量（像素）：往下长时鼓出（≤ min(10dp, 12% 高)），往上收时内凹一半。 */
    public static float sag(float heightVel, float h, float density) {
        float d = density <= 0 ? 1f : density;
        float max = Math.min(10f * d, 0.12f * Math.max(0f, h));
        float s = heightVel * 0.010f;
        if (s > max) s = max;
        if (s < -0.5f * max) s = -0.5f * max;
        return s;
    }

    // ================================================================== 路径输出（Android Path / AWT Path2D 各接一个）

    public interface PathSink {
        void moveTo(float x, float y);

        void lineTo(float x, float y);

        void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3);

        void close();
    }

    /** 圆弧 → 三次贝塞尔的系数。 */
    static final float K = 0.5522848f;

    /**
     * 圆角矩形轮廓，底边按 sag 下垂（顺时针）。圆角 r 自动夹到 min(w, h)/2；
     * 底边没有直线段（药丸 / 圆）时，下垂由两个下圆角的末端一起承担 → 水滴。
     */
    public static void outline(PathSink p, float l, float t, float rr, float b, float r, float sag) {
        float w = rr - l, h = b - t;
        if (w <= 0f || h <= 0f) return;
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        float straight = w - 2f * r;
        float cornerSag = r <= 0.01f ? 0f : sag * Math.max(0f, Math.min(1f, 1f - straight / (2f * r)));
        float k = K * r;
        p.moveTo(l + r, t);
        p.lineTo(rr - r, t);
        p.cubicTo(rr - r + k, t, rr, t + r - k, rr, t + r);
        p.lineTo(rr, b - r);
        p.cubicTo(rr, b - r + k, rr - r + k, b + cornerSag, rr - r, b + cornerSag);
        if (straight > 0.01f) {
            float c = sag / 0.75f;   // 两个控制点同偏 c → 中点偏 0.75c
            p.cubicTo(rr - r - straight / 3f, b + c, l + r + straight / 3f, b + c, l + r, b + cornerSag);
        }
        p.cubicTo(l + r - k, b + cornerSag, l, b - r + k, l, b - r);
        p.lineTo(l, t + r);
        p.cubicTo(l, t + r - k, l + r - k, t, l + r, t);
        p.close();
    }

    /** 圆（四段贝塞尔）。 */
    public static void circle(PathSink p, float cx, float cy, float r) {
        if (r <= 0f) return;
        float k = K * r;
        p.moveTo(cx, cy - r);
        p.cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        p.cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        p.cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        p.cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        p.close();
    }

    /**
     * metaball 颈：两圆 (c1, r1)（孔）与 (c2, r2)（岛上沿的虚拟圆）之间的「粘连」轮廓。
     * spread ∈ [0, 1]：0 = 不画，越大颈越粗（随形变进度衰减）；距离太远（&gt; r1 + 2.5·r2 + r1·2）就断开。
     * 写进 out[16] = p1, h1, h3, p3, p4, h4, h2, p2（x, y 交替）；不画返回 false。
     */
    public static boolean neck(float c1x, float c1y, float r1, float c2x, float c2y, float r2, float spread, float[] out) {
        if (spread <= 0.02f || r1 <= 0.5f || r2 <= 0.5f) return false;
        double dx = c2x - c1x, dy = c2y - c1y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double maxDist = r1 + r2 * 2.5 + r1 * 2;
        if (dist > maxDist || dist <= Math.abs(r1 - r2) + 0.01) return false;
        double v = 0.55 * Math.min(1f, spread);
        double handle = 2.4;
        double u1 = 0, u2 = 0;
        if (dist < r1 + r2) {
            u1 = Math.acos(clampCos((r1 * r1 + dist * dist - r2 * r2) / (2 * r1 * dist)));
            u2 = Math.acos(clampCos((r2 * r2 + dist * dist - r1 * r1) / (2 * r2 * dist)));
        }
        double ang = Math.atan2(dy, dx);
        double maxSpread = Math.acos(clampCos((r1 - r2) / dist));
        double a1 = ang + u1 + (maxSpread - u1) * v;
        double a2 = ang - u1 - (maxSpread - u1) * v;
        double a3 = ang + Math.PI - u2 - (Math.PI - u2 - maxSpread) * v;
        double a4 = ang - Math.PI + u2 + (Math.PI - u2 - maxSpread) * v;
        double p1x = c1x + r1 * Math.cos(a1), p1y = c1y + r1 * Math.sin(a1);
        double p2x = c1x + r1 * Math.cos(a2), p2y = c1y + r1 * Math.sin(a2);
        double p3x = c2x + r2 * Math.cos(a3), p3y = c2y + r2 * Math.sin(a3);
        double p4x = c2x + r2 * Math.cos(a4), p4y = c2y + r2 * Math.sin(a4);
        double tot = r1 + r2;
        double d13 = Math.hypot(p1x - p3x, p1y - p3y);
        double d2 = Math.min(v * handle, d13 / tot) * Math.min(1, dist * 2 / tot);
        double hr1 = r1 * d2, hr2 = r2 * d2;
        double h1x = p1x + hr1 * Math.cos(a1 - Math.PI / 2), h1y = p1y + hr1 * Math.sin(a1 - Math.PI / 2);
        double h2x = p2x + hr1 * Math.cos(a2 + Math.PI / 2), h2y = p2y + hr1 * Math.sin(a2 + Math.PI / 2);
        double h3x = p3x + hr2 * Math.cos(a3 + Math.PI / 2), h3y = p3y + hr2 * Math.sin(a3 + Math.PI / 2);
        double h4x = p4x + hr2 * Math.cos(a4 - Math.PI / 2), h4y = p4y + hr2 * Math.sin(a4 - Math.PI / 2);
        double[] q = {p1x, p1y, h1x, h1y, h3x, h3y, p3x, p3y, p4x, p4y, h4x, h4y, h2x, h2y, p2x, p2y};
        for (int i = 0; i < 16; i++) {
            if (Double.isNaN(q[i]) || Double.isInfinite(q[i])) return false;
            out[i] = (float) q[i];
        }
        return true;
    }

    private static double clampCos(double c) {
        return Math.max(-1.0, Math.min(1.0, c));
    }

    /** 把 {@link #neck} 的结果写成闭合路径。 */
    public static void neckPath(PathSink p, float[] q) {
        p.moveTo(q[0], q[1]);
        p.cubicTo(q[2], q[3], q[4], q[5], q[6], q[7]);
        p.lineTo(q[8], q[9]);
        p.cubicTo(q[10], q[11], q[12], q[13], q[14], q[15]);
        p.close();
    }
}
