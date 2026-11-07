package com.onesay.assistant.v2.ui.wake;

import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.ui.motion.MotionSpec;

/**
 * [R13 唤醒界面 v2] 卡片 ↔ 岛 的黏连形变几何（纯 Java，JVM 可测；单位全是屏幕像素）。
 *
 * <p>去程（卡 → 岛，方案 v2.6「卡 → 岛 黏连形变时间线」）：顶边（MORPH_TOP，快）与底边（MORPH_BOTTOM，慢）
 * 两根独立弹簧同时起步 → 飞行途中自然拉长成「水滴」（高度钳在起始 2.2× 以内），宽度（MORPH_WIDTH）收窄、
 * 圆角（MORPH_RADIUS）从连续圆角 34dp 走向胶囊；顶边距岛底 &lt; 14dp 时画一段「颈」；接触（gap ≤ 0）时把
 * 合并形状（岛顶 → 水滴底）连同速度交给岛的 APPEAR（ω22 ζ0.86，锁死）收尾。
 *
 * <p>回程（岛 → 卡，UNMORPH）：镜像 —— 底边领跑（MORPH_TOP 参数），顶边以 UNMORPH（0.52 s / 0.16）落位。
 */
public final class MorphGeometry {

    /** [R13] 舞台最长时长（去程没接触 / 回程没落定都按此收尾，舞台绝不无限重绘）。 */
    public static final float MAX_STAGE_S = 1.4f;

    /** 颈出现的距离（dp）。 */
    public static final float NECK_RANGE_DP = 14f;
    /** 颈腰宽上限 = 岛高 × 0.6。 */
    public static final float NECK_WAIST_FRAC = 0.6f;
    /** 内容快照在前 40% 进度内淡出。 */
    public static final float SNAPSHOT_FADE_END = 0.40f;
    /** 回程：卡片内容在 60% 进度后淡入。 */
    public static final float UNMORPH_CONTENT_FROM = 0.60f;

    private final float d;
    private final boolean reverse;
    public final IslandGeometry.Spring top, bottom, width, cx, radius;
    private final float startH, maxH;
    private final float islandTop, islandH, islandCx;
    private final float startTop, endTop;
    private float elapsed;
    private boolean contact;
    private float mCardR;

    private MorphGeometry(float density, boolean reverse, float[] from, float[] to, float fromR, float toR,
                          float islandCx, float islandTop, float islandH, float maxStretchBase) {
        this.d = density <= 0 ? 1f : density;
        this.reverse = reverse;
        this.islandCx = islandCx;
        this.islandTop = islandTop;
        this.islandH = islandH;
        MotionSpec.Spec topSpec = reverse ? MotionSpec.UNMORPH : MotionSpec.MORPH_TOP;
        MotionSpec.Spec botSpec = reverse ? MotionSpec.MORPH_TOP : MotionSpec.MORPH_BOTTOM;
        MotionSpec.Spec widSpec = reverse ? MotionSpec.UNMORPH : MotionSpec.MORPH_WIDTH;
        top = topSpec.spring(from[1]);
        top.t = to[1];
        bottom = botSpec.spring(from[3]);
        bottom.t = to[3];
        width = widSpec.spring(from[2] - from[0]);
        width.t = to[2] - to[0];
        cx = topSpec.spring((from[0] + from[2]) / 2f);
        cx.t = (to[0] + to[2]) / 2f;
        radius = MotionSpec.MORPH_RADIUS.spring(fromR);
        radius.t = toR;
        mCardR = reverse ? toR : fromR;
        startH = from[3] - from[1];
        maxH = Math.max(1f, maxStretchBase) * MotionSpec.MORPH_MAX_STRETCH;
        startTop = from[1];
        endTop = to[1];
    }

    /**
     * 去程。
     *
     * @param card      卡片当前屏幕矩形 {l, t, r, b}
     * @param cardR     卡片圆角（px）
     * @param islandCx  岛水平中心
     * @param islandTop 岛上沿
     * @param seedH     岛「种子」（APPEAR 原生起点，28dp 小胶囊）高 = 宽
     * @param compactW  岛目标紧凑宽（不知道就传 0 → 110dp）
     */
    public static MorphGeometry toIsland(float density, float[] card, float cardR, float islandCx, float islandTop,
                                         float seedH, float compactW) {
        float dd = density <= 0 ? 1f : density;
        float cw = compactW > 1f ? compactW : 110f * dd;
        float[] to = {islandCx - cw / 2f, islandTop, islandCx + cw / 2f, islandTop + seedH};
        return new MorphGeometry(dd, false, card, to, cardR, seedH / 2f, islandCx, islandTop, seedH, card[3] - card[1]);
    }

    /** 回程：从岛当前形状流回卡片目标矩形。 */
    public static MorphGeometry fromIsland(float density, float[] island, float islandR, float[] card, float cardR) {
        float dd = density <= 0 ? 1f : density;
        float base = Math.max(island[3] - island[1], card[3] - card[1]);
        return new MorphGeometry(dd, true, island, card, islandR, cardR, (island[0] + island[2]) / 2f, island[1],
                island[3] - island[1], base);
    }

    public void step(float dt) {
        float h = Math.max(0f, Math.min(0.05f, dt));
        top.step(h);
        bottom.step(h);
        width.step(h);
        cx.step(h);
        // 圆角始终追「胶囊」（半个短边）：连续圆角 34dp → 水滴 / 种子都是药丸形（真机首版追 14dp，飞行中是个方块）
        radius.t = Math.max(1f, Math.min(width.v, bottom.v - top.v) / 2f);
        if (reverse) radius.t = Math.min(radius.t, mCardR);
        radius.step(h);
        elapsed += h;
        if (!reverse && gap() <= 0f) contact = true;
    }

    public float elapsed() {
        return elapsed;
    }

    public boolean reverse() {
        return reverse;
    }

    /** 当前形状 {l, t, r, b}（高度已钳到 2.2×）。 */
    public void rect(float[] out) {
        float t = top.v, b = bottom.v;
        if (b - t > maxH) {
            if (reverse) t = b - maxH;   // 回程底边领跑：钳住时顶边被「拖」下来
            else b = t + maxH;           // 去程顶边领跑：钳住时底边被拉上去
        }
        if (b < t + 1f) b = t + 1f;
        float w = Math.max(1f, width.v);
        out[0] = cx.v - w / 2f;
        out[1] = t;
        out[2] = cx.v + w / 2f;
        out[3] = b;
    }

    /** 当前圆角（不超过半高 / 半宽 → 胶囊）。 */
    public float cornerRadius() {
        float[] r = new float[4];
        rect(r);
        float half = Math.min(r[2] - r[0], r[3] - r[1]) / 2f;
        return Math.max(0f, Math.min(radius.v, half));
    }

    /** 去程：水滴顶距岛底的距离（px，≤0 = 已接触）。 */
    public float gap() {
        if (reverse) return Float.MAX_VALUE;
        float[] r = new float[4];
        rect(r);
        return r[1] - (islandTop + islandH);
    }

    public boolean contact() {
        return contact;
    }

    /** 颈腰宽（px）；0 = 不画。只在去程、接触前 14dp 内。 */
    public float neckWaist() {
        if (reverse || contact) return 0f;
        float g = gap();
        return neckWaist(g, islandH, d);
    }

    public static float neckWaist(float gapPx, float islandH, float density) {
        float range = NECK_RANGE_DP * (density <= 0 ? 1f : density);
        if (gapPx <= 0f || gapPx >= range) return 0f;
        return islandH * NECK_WAIST_FRAC * (1f - gapPx / range);
    }

    /** 进度 0→1（按顶边行程）。 */
    public float progress() {
        float span = endTop - startTop;
        if (Math.abs(span) < 1f) return 1f;
        float p = (top.v - startTop) / span;
        return Math.max(0f, Math.min(1f, p));
    }

    /** 快照（卡片内容）透明度：去程前 40% 淡出；回程 60% 后淡入。 */
    public float snapshotAlpha() {
        float p = progress();
        if (!reverse) return Math.max(0f, 1f - p / SNAPSHOT_FADE_END);
        return Math.max(0f, Math.min(1f, (p - UNMORPH_CONTENT_FROM) / (1f - UNMORPH_CONTENT_FROM)));
    }

    /**
     * 接触时交给岛的合并形状 {l, t, r, b}：岛上沿 → 水滴底，宽 = 水滴当前宽，水平中心 = 岛中心。
     * bottomLimit = 岛窗口下沿（合并形状不能超出岛窗口）。
     */
    public void mergedRect(float[] out, float bottomLimit) {
        float[] r = new float[4];
        rect(r);
        float w = r[2] - r[0];
        out[0] = islandCx - w / 2f;
        out[1] = islandTop;
        out[2] = islandCx + w / 2f;
        out[3] = Math.min(Math.max(islandTop + islandH, r[3]), bottomLimit > 0 ? bottomLimit : Float.MAX_VALUE);
    }

    /** 接力速度 {vw, vh}（px/s）：宽度速度 + 高度（底边）速度。 */
    public float[] handoffVelocity() {
        return new float[] {width.vel, bottom.vel};
    }

    /** 回程是否落定。 */
    public boolean settled() {
        float eps = 0.5f * d;
        return top.settled(eps) && bottom.settled(eps) && width.settled(eps) && cx.settled(eps) && radius.settled(eps);
    }

    public float startHeight() {
        return startH;
    }

}
