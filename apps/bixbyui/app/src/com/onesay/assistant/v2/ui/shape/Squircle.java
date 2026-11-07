package com.onesay.assistant.v2.ui.shape;

/**
 * [R13 唤醒界面 v2] 连续圆角（超椭圆角）几何（方案 v2.3）。点生成是纯函数（JVM 可测）；{@link #build} 把点连成 Path。
 *
 * <p>每个角取 |dx/e|ⁿ + |dy/e|ⁿ = 1 的四分之一：角区 e = min(k·r, w/2, h/2)，k = 1.53，n = 3.26
 * （由「45° 处与半径 r 的普通圆角重合」解出：1 − 2^(−1/n) = (1 − √2/2) / k）。n &gt; 2 时与直边衔接处曲率为 0（G2）。
 */
public final class Squircle {

    private Squircle() {}

    public static final float K = 1.53f;
    public static final float N = 3.26f;
    public static final int SEG = 12;

    /** 由 k 解 n（JVM 测试核对 3.26）。 */
    public static double solveN(double k) {
        double rhs = (1.0 - Math.sqrt(2.0) / 2.0) / k;   // = 1 − 2^(−1/n)
        return -1.0 / (Math.log(1.0 - rhs) / Math.log(2.0));
    }

    /** 角区长度。 */
    public static float cornerExtent(float w, float h, float r) {
        return Math.min(K * r, Math.min(w, h) / 2f);
    }

    /**
     * 轮廓点（顺时针，左上角起），每角 SEG+1 个点：返回 {x0, y0, x1, y1, …}。
     */
    public static float[] points(float l, float t, float r, float b, float radius) {
        float w = r - l, h = b - t;
        float e = cornerExtent(w, h, radius);
        float p = 2f / N;
        float[][] c = {{l + e, t + e, -1, -1}, {r - e, t + e, 1, -1}, {r - e, b - e, 1, 1}, {l + e, b - e, -1, 1}};
        float[] out = new float[4 * (SEG + 1) * 2];
        int k = 0;
        for (int i = 0; i < 4; i++) {
            for (int s = 0; s <= SEG; s++) {
                float tt = (i % 2 == 0) ? s / (float) SEG : 1f - s / (float) SEG;
                double th = tt * Math.PI / 2;
                float dx = (float) (e * Math.pow(Math.cos(th), p));
                float dy = (float) (e * Math.pow(Math.sin(th), p));
                out[k++] = c[i][0] + c[i][2] * dx;
                out[k++] = c[i][1] + c[i][3] * dy;
            }
        }
        return out;
    }

    /** 把轮廓点连成 Path（建一次缓存；尺寸变了才重建）。 */
    public static android.graphics.Path build(float l, float t, float r, float b, float radius, android.graphics.Path out) {
        float[] pts = points(l, t, r, b, radius);
        out.reset();
        out.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) out.lineTo(pts[i], pts[i + 1]);
        out.close();
        return out;
    }
}
