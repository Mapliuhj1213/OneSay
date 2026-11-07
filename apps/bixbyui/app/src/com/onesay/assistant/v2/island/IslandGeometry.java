package com.onesay.assistant.v2.island;

/**
 * [island v3] 岛的几何与弹簧（纯 Java，JVM 可测）。输入是屏幕像素 + density，输出像素；常量用 dp（island-v3-plan.md §3）。
 *
 * <p>v3：不读 DisplayCutout，挖孔屏和无挖孔屏走同一条路 —— 上沿 = 状态栏下沿 + 2dp、水平居中（Note 8：y = 63 + 5 = 68px、cx = 540px）；
 * 全屏（状态栏隐藏）时只有关键事件显示，上沿 = 8dp。
 */
public final class IslandGeometry {

    private IslandGeometry() {}

    public static final float GAP_BELOW_STATUS_DP = 2f;
    public static final float FULLSCREEN_TOP_DP = 8f;
    public static final float DEFAULT_STATUS_DP = 24f;

    public static final float COMPACT_H_DP = 28f;
    public static final float COMPACT_MIN_W_DP = 98f;
    public static final float COMPACT_MAX_W_DP = 120f;
    public static final float COMPACT_PAD_L_DP = 8f;
    public static final float COMPACT_PAD_R_DP = 5f;
    public static final float COMPACT_GROUP_GAP_DP = 10f;

    public static final float PULSE_W_DP = 232f;
    public static final float PULSE_H_DP = 40f;
    public static final float PULSE_R_DP = 20f;

    public static final float EXP_W_DP = 380f;
    public static final float EXP_R_DP = 30f;
    public static final float EXP_PAD_DP = 16f;
    public static final float EXP_INNER_R_DP = 14f;
    public static final float EXP_SIDE_MIN_DP = 12f;
    public static final float EXP_MAX_H_FRAC = 0.40f;

    public static final float MIN_TASK_DP = 28f;
    public static final float MIN_CODE_W_DP = 44f;
    public static final float DUAL_GAP_DP = 6f;

    public static final float SWIPE_TUCK_DP = 24f;

    /** 出现（隐藏 → 紧凑；面板收成岛走的就是这条）：R11 用户点名表扬「面板切灵动岛很流畅」→ 参数一点不动。 */
    public static final float SPRING_OMEGA = 22f;
    public static final float SPRING_ZETA = 0.86f;
    public static final float CONTENT_GATE = 0.8f;

    // ★ R11 对齐 Apple 灵动岛 / Live Activities 的动效（弹簧 response 0.35–0.5 s、轻微回弹、内容交叉淡化、不硬切）。
    //   换算：ω = 2π / response，ζ = dampingFraction（SwiftUI bounce = 1 − ζ）。
    /** 展开（紧凑 → 展开 / 变大）：response 0.45 s、ζ 0.78（约 2% 回弹，肉眼可见的「弹一下」但不晃）。 */
    public static final float EXPAND_RESPONSE_S = 0.45f, EXPAND_ZETA = 0.78f;
    /** 收起（展开 → 紧凑 / 变小）：response 0.38 s、ζ 0.90（更干脆，几乎不回弹）。 */
    public static final float COLLAPSE_RESPONSE_S = 0.38f, COLLAPSE_ZETA = 0.90f;
    /** 消失（可见 → 隐藏）：缩回一颗小药丸再淡出，response 0.32 s、临界阻尼。 */
    public static final float HIDE_RESPONSE_S = 0.32f, HIDE_ZETA = 1.0f;
    /** 新内容淡入的门槛（R11 从 0.8 降到 0.6：形状长到 60% 就开始交叉淡入，更接近 Apple 的「边长边显」）。 */
    public static final float CONTENT_GATE_R11 = 0.6f;
    /** 新内容淡入时从 0.94 放大到 1（只在换内容时）；旧内容 ~120 ms 淡出。 */
    public static final float CONTENT_SCALE_FROM = 0.94f;
    /** 隐藏时缩到的小药丸直径（dp）——不再缩成一个点。 */
    public static final float HIDE_PILL_DP = 20f;

    public static float omegaOf(float responseS) {
        return (float) (2.0 * Math.PI / Math.max(0.05f, responseS));
    }

    /** 一次形变用哪组弹簧（纯函数，JVM 测）。 */
    public enum Motion { APPEAR, EXPAND, COLLAPSE, HIDE, NONE }

    /**
     * @param fromW,fromH 当前形状（像素；≈0 = 隐藏）
     * @param toW,toH     目标形状（像素；0 = 隐藏）
     */
    public static Motion motionFor(float fromW, float fromH, float toW, float toH) {
        boolean fromHidden = fromW < 1f || fromH < 1f;
        boolean toHidden = toW < 1f || toH < 1f;
        if (fromHidden && toHidden) return Motion.NONE;
        if (fromHidden) return Motion.APPEAR;
        if (toHidden) return Motion.HIDE;
        float a0 = fromW * fromH, a1 = toW * toH;
        if (Math.abs(a1 - a0) < 1f) return Motion.NONE;
        return a1 > a0 ? Motion.EXPAND : Motion.COLLAPSE;
    }

    /** {ω, ζ} for a motion. */
    public static float[] springFor(Motion m) {
        switch (m) {
            case EXPAND: return new float[] {omegaOf(EXPAND_RESPONSE_S), EXPAND_ZETA};
            case COLLAPSE: return new float[] {omegaOf(COLLAPSE_RESPONSE_S), COLLAPSE_ZETA};
            case HIDE: return new float[] {omegaOf(HIDE_RESPONSE_S), HIDE_ZETA};
            default: return new float[] {SPRING_OMEGA, SPRING_ZETA};
        }
    }

    /** 岛的锚点（像素）：水平中心 + 上沿。 */
    public static final class Anchor {
        public final int cx;
        public final int top;
        public final boolean fullscreen;
        public final int statusBarPx;

        Anchor(int cx, int top, boolean fullscreen, int statusBarPx) {
            this.cx = cx;
            this.top = top;
            this.fullscreen = fullscreen;
            this.statusBarPx = statusBarPx;
        }

        @Override
        public String toString() {
            return "cx=" + cx + " top=" + top + " statusBar=" + statusBarPx + (fullscreen ? " fullscreen" : "");
        }
    }

    /**
     * @param screenW 当前方向的屏幕宽（像素）
     * @param statusBarPx 状态栏高（像素；≤0 = 未知 → 24dp）
     * @param fullscreen 状态栏隐藏
     */
    public static Anchor anchor(int screenW, float density, int statusBarPx, boolean fullscreen) {
        float d = density <= 0 ? 1f : density;
        int sb = statusBarPx > 0 ? statusBarPx : Math.round(DEFAULT_STATUS_DP * d);
        int top = fullscreen ? Math.round(FULLSCREEN_TOP_DP * d) : sb + Math.round(GAP_BELOW_STATUS_DP * d);
        return new Anchor(screenW / 2, top, fullscreen, sb);
    }

    /** 紧凑态宽：内容宽 + 左右内边距，夹在 [98, 120]dp（像素）。 */
    public static float compactWidth(float contentPx, float density) {
        float d = density <= 0 ? 1f : density;
        float w = contentPx + (COMPACT_PAD_L_DP + COMPACT_PAD_R_DP) * d;
        return Math.max(COMPACT_MIN_W_DP * d, Math.min(COMPACT_MAX_W_DP * d, w));
    }

    /** 展开卡宽：min(380dp, 屏宽 − 2×12dp)。 */
    public static float expandedWidth(int screenW, float density) {
        float d = density <= 0 ? 1f : density;
        return Math.max(1f, Math.min(EXP_W_DP * d, screenW - 2f * EXP_SIDE_MIN_DP * d));
    }

    /** 展开卡最高：0.40 × 屏高 − 上沿（像素）。 */
    public static float expandedMaxHeight(int screenH, int topPx) {
        return Math.max(0f, EXP_MAX_H_FRAC * screenH - topPx);
    }

    /** 双活动最小态成组：返回 {主块左, 主块宽, 副块左, 副块宽}（像素），组中心 = cx。 */
    public static float[] dualLayout(int cx, float density) {
        float d = density <= 0 ? 1f : density;
        float a = MIN_TASK_DP * d, b = MIN_CODE_W_DP * d, g = DUAL_GAP_DP * d;
        float total = a + g + b;
        float left = cx - total / 2f;
        return new float[] {left, a, left + a + g, b};
    }

    // =====================================================================
    // 弹簧（与设计稿同一积分：半隐式欧拉，每帧 4 个子步）
    // =====================================================================

    public static final class Spring {
        public float v;
        public float t;
        public float vel;
        public float omega;
        public float zeta;

        public Spring(float v, float omega, float zeta) {
            this.v = v;
            this.t = v;
            this.omega = omega;
            this.zeta = zeta;
        }

        public void step(float dt) {
            float k = omega * omega;
            float c = 2f * zeta * omega;
            int n = 4;
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -k * (v - t) - c * vel;
                vel += a * h;
                v += vel * h;
            }
        }

        public boolean settled(float eps) {
            return Math.abs(v - t) < eps && Math.abs(vel) < eps * 4f;
        }

        /** ★ R11：每次形变按方向换一组弹簧参数（速度保留，不打断进行中的运动 → 不硬切）。 */
        public void setParams(float omega, float zeta) {
            this.omega = omega;
            this.zeta = zeta;
        }

        public void snap() {
            v = t;
            vel = 0f;
        }
    }

    public static boolean contentGateOpen(float w, float tw, float h, float th) {
        return contentGateOpen(w, tw, h, th, CONTENT_GATE);
    }

    public static boolean contentGateOpen(float w, float tw, float h, float th, float gate) {
        boolean growing = tw > w + 0.5f || th > h + 0.5f;
        if (!growing) return true;
        return w >= tw * gate && h >= th * gate;
    }

    /**
     * ★ R11 固定窗口：岛有活动期间窗口一次定好，不再随形变挪位置（挪窗口 = 内容先按新原点画、窗口一帧后才到 → 抖）。
     * 全宽；上沿 = 锚点上沿 − 8dp；下沿 = 锚点上沿 + 展开卡最高 + 16dp。触摸只认岛的形状（touchable region），其余透传给下面的 App。
     */
    public static int[] fixedWindow(int screenW, int screenH, int anchorTop, float density) {
        float d = density <= 0 ? 1f : density;
        int top = Math.max(0, anchorTop - Math.round(8 * d));
        int bottom = anchorTop + Math.round(expandedMaxHeight(screenH, anchorTop)) + Math.round(16 * d);
        if (screenH > 0) bottom = Math.min(screenH, bottom);
        return new int[] {0, top, Math.max(1, screenW), Math.max(top + 1, bottom)};
    }
}
