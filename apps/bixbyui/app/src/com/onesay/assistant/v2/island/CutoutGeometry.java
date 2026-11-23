package com.onesay.assistant.v2.island;

import java.util.ArrayList;
import java.util.List;

/**
 * [island-cutout-1118] 岛从挖孔摄像头长出来：DisplayCutout 的 boundingRects → 岛的「源点」与紧凑态位置（纯 Java，JVM 可测）。
 *
 * <p>输入全部是<b>当前方向</b>的屏幕像素（{@code Display.getCutout()} / {@code WindowInsets.getDisplayCutout()} 本来就按当前旋转给）；
 * 尺寸常量全是 dp，乘 density 后用，不写死像素。
 *
 * <h3>判定</h3>
 * <ul>
 *   <li><b>圆孔</b>（ROUND）：在上沿区域、近似正方形；有的系统把孔的外接框一直延到屏幕上沿（t = 0、高 ≥ 宽），那就按宽当直径、孔心 = 下沿 − 半径。</li>
 *   <li><b>药丸孔</b>（PILL）：双摄挖孔（宽 / 高 1.67–3.2、高 ≤ 48dp、不贴上沿）；两个靠得很近（间隙 &lt; 16dp）的圆孔也合并成药丸孔。</li>
 *   <li><b>刘海 / 水滴</b>（NOTCH）：贴着上沿且比高还宽，或宽 &gt; 110dp → 不包孔，回落到上方居中。</li>
 *   <li><b>侧边 / 底边</b>（SIDE）：横屏时孔在左右短边 → 不算，回落到上方居中。</li>
 * </ul>
 * 多个候选取离屏幕水平中线最近的那个。没有孔 / 只有刘海 → 源点是上方居中的一颗 28dp 小胶囊（与 v3 一样）。
 *
 * <h3>包孔（wrap）</h3>
 * 有孔且设置 {@code cutout} 开着：紧凑态竖直方向以孔心为中心，高 = max(28dp, 孔径 + 2×5dp)，横向以孔心为中心（贴边时夹到屏内 8dp）；
 * 内容在孔的左右两侧排（孔两边各让 6dp）。展开卡上沿与紧凑态同一条线，内容从孔下沿往下排。
 * 设置关掉 = 岛仍从孔里长出来，但停在状态栏下 2dp（v3 位置）。
 */
public final class CutoutGeometry {

    private CutoutGeometry() {}

    public enum Kind { NONE, ROUND, PILL, NOTCH, SIDE }

    /** 孔最大边长（dp）：比这大的不当挖孔。 */
    public static final float HOLE_MAX_DP = 64f;
    public static final float PILL_MAX_W_DP = 110f;
    public static final float PILL_MAX_H_DP = 48f;
    /** 两个圆孔间隙小于这个就合成一个药丸孔（双摄）。 */
    public static final float MERGE_GAP_DP = 16f;
    /** 包孔时孔外留的黑边（每边）。 */
    public static final float RING_DP = 5f;
    /** 紧凑态内容在孔两侧各让开的距离。 */
    public static final float LANE_PAD_DP = 6f;
    /** 包孔时岛离屏幕左右边至少多远。 */
    public static final float EDGE_MARGIN_DP = 8f;
    /** 「中间孔」：孔心离屏幕中线不超过 max(16dp, 4% 屏宽)。 */
    public static final float CENTER_TOL_DP = 16f;
    public static final float CENTER_TOL_FRAC = 0.04f;

    /** 一个挖孔（像素，当前方向）。 */
    public static final class Hole {
        public final Kind kind;
        public final float cx, cy, w, h;

        Hole(Kind kind, float cx, float cy, float w, float h) {
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }

        public float left() { return cx - w / 2f; }
        public float right() { return cx + w / 2f; }
        public float top() { return cy - h / 2f; }
        public float bottom() { return cy + h / 2f; }

        @Override
        public String toString() {
            return kind + "@" + Math.round(cx) + "," + Math.round(cy) + " " + Math.round(w) + "x" + Math.round(h);
        }
    }

    static float dens(float d) {
        return d <= 0 ? 1f : d;
    }

    /** 判定一个外接框（l, t, r, b）。 */
    public static Hole classify(int l, int t, int r, int b, int screenW, int screenH, float density, int statusBarPx) {
        float d = dens(density);
        float w = r - l, h = b - t;
        if (w <= 0 || h <= 0) return null;
        int sb = statusBarPx > 0 ? statusBarPx : Math.round(IslandGeometry.DEFAULT_STATUS_DP * d);
        boolean topArea = t <= Math.max(sb, 48f * d) && b <= Math.max(2f * sb, 96f * d) && (screenH <= 0 || t < screenH / 4f);
        float cx = (l + r) / 2f;
        if (!topArea) return new Hole(Kind.SIDE, cx, (t + b) / 2f, w, h);
        boolean touchesTop = t <= Math.round(1f * d);
        if (w > PILL_MAX_W_DP * d) return new Hole(Kind.NOTCH, cx, (t + b) / 2f, w, h);
        if (touchesTop) {
            // 外接框延到上沿：高 ≥ 0.85×宽 → 圆孔（直径 = 宽，孔心在下沿往上半个直径）；否则是刘海 / 水滴
            if (h >= 0.85f * w && w <= HOLE_MAX_DP * d) return new Hole(Kind.ROUND, cx, b - w / 2f, w, w);
            return new Hole(Kind.NOTCH, cx, (t + b) / 2f, w, h);
        }
        float ar = w / h;
        if (ar >= 0.6f && ar <= 1.67f && Math.max(w, h) <= HOLE_MAX_DP * d) {
            float dia = (w + h) / 2f;
            return new Hole(Kind.ROUND, cx, (t + b) / 2f, dia, dia);
        }
        if (ar > 1.67f && ar <= 3.2f && h <= PILL_MAX_H_DP * d) return new Hole(Kind.PILL, cx, (t + b) / 2f, w, h);
        return new Hole(Kind.NOTCH, cx, (t + b) / 2f, w, h);
    }

    /** 从所有外接框里挑岛的源孔（可能为 null；只有刘海时返回 NOTCH）。rects = {l, t, r, b} 列表。 */
    public static Hole pick(List<int[]> rects, int screenW, int screenH, float density, int statusBarPx) {
        float d = dens(density);
        List<Hole> holes = new ArrayList<Hole>();
        Hole notch = null;
        if (rects != null) {
            for (int[] q : rects) {
                if (q == null || q.length < 4) continue;
                Hole h = classify(q[0], q[1], q[2], q[3], screenW, screenH, density, statusBarPx);
                if (h == null) continue;
                if (h.kind == Kind.ROUND || h.kind == Kind.PILL) holes.add(h);
                else if (h.kind == Kind.NOTCH && notch == null) notch = h;
            }
        }
        // 双摄：两个圆孔靠得很近 → 合成一个药丸孔
        boolean merged = true;
        while (merged && holes.size() > 1) {
            merged = false;
            outer:
            for (int i = 0; i < holes.size(); i++) {
                for (int j = i + 1; j < holes.size(); j++) {
                    Hole a = holes.get(i), b = holes.get(j);
                    float gap = Math.max(a.left(), b.left()) - Math.min(a.right(), b.right());
                    boolean vOverlap = Math.min(a.bottom(), b.bottom()) > Math.max(a.top(), b.top());
                    if (vOverlap && gap < MERGE_GAP_DP * d) {
                        float l = Math.min(a.left(), b.left()), r = Math.max(a.right(), b.right());
                        float t = Math.min(a.top(), b.top()), bb = Math.max(a.bottom(), b.bottom());
                        holes.remove(j);
                        holes.set(i, new Hole(Kind.PILL, (l + r) / 2f, (t + bb) / 2f, r - l, bb - t));
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        Hole best = null;
        float mid = screenW / 2f;
        for (Hole h : holes) {
            if (best == null || Math.abs(h.cx - mid) < Math.abs(best.cx - mid)) best = h;
        }
        return best != null ? best : notch;
    }

    public static boolean isHole(Hole h) {
        return h != null && (h.kind == Kind.ROUND || h.kind == Kind.PILL);
    }

    public static boolean isCentered(Hole h, int screenW, float density) {
        if (h == null) return false;
        float tol = Math.max(CENTER_TOL_DP * dens(density), CENTER_TOL_FRAC * screenW);
        return Math.abs(h.cx - screenW / 2f) <= tol;
    }

    /**
     * 完整锚点：源点（孔 / 上方居中的种子）+ 紧凑态位置。
     *
     * @param rects 当前方向的 boundingRects（null / 空 = 没有挖孔）
     * @param wrap  设置 {@code cutout}：有孔时包住孔
     */
    public static IslandGeometry.Anchor anchor(int screenW, int screenH, float density, int statusBarPx, boolean fullscreen,
            List<int[]> rects, boolean wrap) {
        float d = dens(density);
        IslandGeometry.Anchor base = IslandGeometry.anchor(screenW, d, statusBarPx, fullscreen);
        Hole hole = pick(rects, screenW, screenH, d, base.statusBarPx);
        float seed = IslandGeometry.COMPACT_H_DP * d;
        if (!isHole(hole)) {
            // 没有孔 / 刘海 / 横屏孔在侧边：上方居中，源点 = 锚点处一颗 28dp 小胶囊（v3 原样）
            return new IslandGeometry.Anchor(base.cx, base.top, fullscreen, base.statusBarPx,
                    hole == null ? Kind.NONE : hole.kind, base.cx, base.top + seed / 2f, seed, seed,
                    Math.round(seed), false, 0f, 0f);
        }
        if (!wrap) {
            // 不包孔：停在 v3 位置，但从孔里长出来（源点 = 孔）
            return new IslandGeometry.Anchor(base.cx, base.top, fullscreen, base.statusBarPx, hole.kind,
                    hole.cx, hole.cy, hole.w, hole.h, Math.round(seed), false, 0f, 0f);
        }
        int ch = Math.round(Math.max(seed, hole.h + 2f * RING_DP * d));
        int top = Math.max(0, Math.round(hole.cy - ch / 2f));
        float lane = hole.w + 2f * LANE_PAD_DP * d;
        return new IslandGeometry.Anchor(Math.round(hole.cx), top, fullscreen, base.statusBarPx, hole.kind,
                hole.cx, hole.cy, hole.w, hole.h, ch, true, hole.cx - lane / 2f, hole.cx + lane / 2f);
    }

    /**
     * 宽 w 的岛的水平中心：包孔时以孔为中心，但整块必须在屏内（左右各留 8dp）；放不下就屏幕居中。
     * 不包孔 → 锚点 cx。
     */
    public static float cxFor(IslandGeometry.Anchor a, float w, int screenW, float density) {
        if (a == null) return screenW / 2f;
        if (!a.wrap) return a.cx;
        float m = EDGE_MARGIN_DP * dens(density);
        float lo = m + w / 2f, hi = screenW - m - w / 2f;
        if (lo > hi) return screenW / 2f;
        return Math.max(lo, Math.min(hi, a.originCx));
    }

    /** 孔这条「过道」相对岛左沿的位置：0 = 不需要让（不包孔）；返回 {laneL, laneR}（岛内坐标）。 */
    public static float[] laneIn(IslandGeometry.Anchor a, float islandLeft) {
        if (a == null || !a.wrap) return null;
        return new float[] {a.laneL - islandLeft, a.laneR - islandLeft};
    }

    /** 紧凑态内容怎么绕开孔：不包孔 / 孔在中间（左右两组分居孔两侧）/ 孔偏左（内容全在孔右边）/ 孔偏右。 */
    public static final int LANE_NONE = 0, LANE_SPLIT = 1, LANE_LEFT = 2, LANE_RIGHT = 3;

    /** 孔心离屏幕中线 ≤ 20% 屏宽 → 两侧分居；否则内容整体排到孔的另一侧。 */
    public static int laneMode(IslandGeometry.Anchor a, int screenW) {
        if (a == null || !a.wrap) return LANE_NONE;
        if (Math.abs(a.originCx - screenW / 2f) <= 0.2f * screenW) return LANE_SPLIT;
        return a.originCx < screenW / 2f ? LANE_LEFT : LANE_RIGHT;
    }

    /**
     * 包孔时紧凑态宽。孔在中间：孔两边对称各放 max(左组, 右组)，且不比「原宽 + 过道」窄；
     * 孔偏一侧：岛贴到那一侧的屏边（8dp），内容整体排在孔的另一侧 → 宽 = 原宽 + 孔外沿到屏边的距离。
     */
    public static float wrapCompactWidth(IslandGeometry.Anchor a, float leftGroup, float rightGroup, float plainWidth,
            int screenW, float density) {
        int mode = laneMode(a, screenW);
        if (mode == LANE_NONE) return plainWidth;
        float d = dens(density);
        float m = EDGE_MARGIN_DP * d;
        float lane = a.laneR - a.laneL;
        float pad = (IslandGeometry.COMPACT_PAD_L_DP + IslandGeometry.COMPACT_PAD_R_DP) * d;
        float max = Math.max(1f, screenW - 2f * m);
        float w;
        if (mode == LANE_SPLIT) {
            w = Math.max(plainWidth + lane, 2f * Math.max(leftGroup, rightGroup) + lane + pad);
        } else if (mode == LANE_LEFT) {
            w = plainWidth + Math.max(lane, a.laneR - m);
        } else {
            w = plainWidth + Math.max(lane, screenW - m - a.laneL);
        }
        return Math.min(max, w);
    }

    /**
     * 包孔时双活动（主泡 + 副泡）：孔落在主泡里（孔在中间 / 偏左），偏右时落在副泡里。
     * 返回屏幕 x：{岛左, 主泡左, 主泡右, 主泡内容中心, 副泡左, 副泡右, 副泡内容左, 副泡内容右, 总宽}。
     *
     * @param mainW 主泡内容宽（任务环），subW 副泡宽（取件码 / 提醒），gap 两泡间隙
     */
    public static float[] dualWrap(IslandGeometry.Anchor a, float mainW, float subW, float gap, int screenW, float density) {
        float d = dens(density);
        float m = EDGE_MARGIN_DP * d;
        int mode = laneMode(a, screenW);
        float mainL, mainR, mainC, subL, subR, subCL, subCR;
        if (mode == LANE_LEFT) {
            mainL = m;
            mainR = a.laneR + mainW;
            mainC = a.laneR + mainW / 2f;
            subL = mainR + gap;
            subR = subL + subW;
            subCL = subL;
            subCR = subR;
        } else if (mode == LANE_RIGHT) {
            subR = screenW - m;
            subL = a.laneL - subW;
            subCL = subL;
            subCR = a.laneL;
            mainR = subL - gap;
            mainL = mainR - mainW;
            mainC = mainL + mainW / 2f;
        } else {
            // 中间孔（或不包孔时按中间算）：任务环在孔左边，副泡在主泡右边
            float laneL = mode == LANE_NONE ? screenW / 2f : a.laneL, laneR = mode == LANE_NONE ? screenW / 2f : a.laneR;
            mainL = laneL - mainW;
            mainR = laneR + 4f * d;
            mainC = laneL - mainW / 2f;
            subL = mainR + gap;
            subR = subL + subW;
            subCL = subL;
            subCR = subR;
        }
        float shift = 0f;   // 整组别出屏
        if (mainL < m) shift = m - mainL;
        if (subR + shift > screenW - m) shift = (screenW - m) - subR;
        return new float[] {mainL + shift, mainL + shift, mainR + shift, mainC + shift, subL + shift, subR + shift, subCL + shift,
                subCR + shift, subR - mainL};
    }

    /** 展开卡内容往下让多少（孔下沿 + 2dp 以下才排第一行；EXP_PAD 已有 16dp）。 */
    public static float expandedInsetTop(IslandGeometry.Anchor a, float density) {
        if (a == null || !a.wrap) return 0f;
        float d = dens(density);
        float holeBottomRel = (a.originCy + a.originH / 2f) - a.top;
        return Math.max(0f, holeBottomRel + 2f * d - IslandGeometry.EXP_PAD_DP * d);
    }

    // ================================================================== 随屏宽缩放的尺寸（dp + 上下限）

    /** 紧凑态封顶宽：0.29 × 屏宽，夹在 [120, 168]dp（手机 411dp 宽仍是 120dp，平板放宽）。 */
    public static float compactMaxWidth(int screenW, float density) {
        float d = dens(density);
        float wdp = screenW / d;
        return Math.max(IslandGeometry.COMPACT_MAX_W_DP, Math.min(168f, 0.29f * wdp)) * d;
    }

    /** 紧凑宽（随屏宽的封顶）。 */
    public static float compactWidth(float contentPx, int screenW, float density) {
        float d = dens(density);
        float w = contentPx + (IslandGeometry.COMPACT_PAD_L_DP + IslandGeometry.COMPACT_PAD_R_DP) * d;
        return Math.max(IslandGeometry.COMPACT_MIN_W_DP * d, Math.min(compactMaxWidth(screenW, density), w));
    }

    /** 展开卡宽：min(屏宽 − 24dp, clamp(0.6 × 屏宽, 380, 460)dp)。手机上与 v3 相同（380 / 屏宽 − 24），平板 / 横屏放宽到 460dp。 */
    public static float expandedWidth(int screenW, float density) {
        float d = dens(density);
        float wdp = screenW / d;
        float want = Math.max(IslandGeometry.EXP_W_DP, Math.min(460f, 0.6f * wdp)) * d;
        return Math.max(1f, Math.min(want, screenW - 2f * IslandGeometry.EXP_SIDE_MIN_DP * d));
    }
}
