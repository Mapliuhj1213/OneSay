package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * [island-cutout-1118] 挖孔 → 岛的源点 / 紧凑态位置：魅族 21 Pro（中间圆孔）、Note 8（无孔）、角落孔、双摄药丸孔、
 * 水滴 / 刘海、平板、横屏、多个挖孔、不同 density。
 */
public class CutoutGeometryTest {

    static List<int[]> rects(int[]... r) {
        return new ArrayList<int[]>(Arrays.asList(r));
    }

    static final List<int[]> NONE = Collections.<int[]>emptyList();

    // ---------------------------------------------------------------- 魅族 21 Pro：1440×3200，560dpi（3.5），中间圆孔

    static final float MZ_D = 3.5f;
    static final int MZ_W = 1440, MZ_H = 3200, MZ_SB = 136;
    /** 孔 64px（≈18dp）居中，孔心 y = 74。 */
    static final int[] MZ_HOLE = {688, 42, 752, 106};

    @Test public void meizu21ProCenterHoleWrapsCamera() {
        CutoutGeometry.Hole h = CutoutGeometry.pick(rects(MZ_HOLE), MZ_W, MZ_H, MZ_D, MZ_SB);
        assertNotNull(h);
        assertEquals(CutoutGeometry.Kind.ROUND, h.kind);
        assertEquals(720f, h.cx, 0.01f);
        assertEquals(74f, h.cy, 0.01f);
        assertEquals(64f, h.w, 0.01f);
        assertTrue(CutoutGeometry.isCentered(h, MZ_W, MZ_D));

        IslandGeometry.Anchor a = CutoutGeometry.anchor(MZ_W, MZ_H, MZ_D, MZ_SB, false, rects(MZ_HOLE), true);
        assertTrue(a.wrap);
        assertEquals(720, a.cx);
        // 紧凑高 = max(28dp = 98px, 64 + 2×5dp = 99px) = 99 → 上沿 = 74 − 49.5 → 25（孔竖直居中）
        assertEquals(99, a.compactH);
        assertEquals(25, a.top);
        assertTrue("孔在紧凑态里：上", a.top <= h.top() - 1);
        assertTrue("孔在紧凑态里：下", a.top + a.compactH >= h.bottom() + 1);
        assertEquals(720f, a.originCx, 0.01f);
        assertEquals(42f, a.originTop(), 0.01f);
        // 过道 = 孔 + 两侧各 6dp
        assertEquals(720f - (64f + 42f) / 2f, a.laneL, 0.01f);
        assertEquals(720f + (64f + 42f) / 2f, a.laneR, 0.01f);
        assertEquals(CutoutGeometry.LANE_SPLIT, CutoutGeometry.laneMode(a, MZ_W));

        // 紧凑态与展开卡都以孔为中心
        float compact = CutoutGeometry.wrapCompactWidth(a, 70f * MZ_D, 30f * MZ_D, CutoutGeometry.compactWidth(110f * MZ_D, MZ_W, MZ_D), MZ_W, MZ_D);
        assertEquals(720f, CutoutGeometry.cxFor(a, compact, MZ_W, MZ_D), 0.01f);
        float exp = CutoutGeometry.expandedWidth(MZ_W, MZ_D);
        assertEquals("411dp 宽的手机：展开卡仍是 380dp", 380f, exp / MZ_D, 0.5f);
        assertEquals(720f, CutoutGeometry.cxFor(a, exp, MZ_W, MZ_D), 0.01f);
        // 对称：孔两侧各放 max(左, 右) = 70dp，加 13dp 内边距 + 过道
        assertEquals(2f * 70f * MZ_D + (13f * MZ_D) + (a.laneR - a.laneL), compact, 0.5f);
        // 展开内容从孔下沿往下排：孔底相对上沿 = 106 − 25 = 81px → 81 + 2dp − 16dp
        assertEquals(81f + 2f * MZ_D - 16f * MZ_D, CutoutGeometry.expandedInsetTop(a, MZ_D), 0.01f);
    }

    @Test public void meizuBoundingRectExtendedToTopEdgeIsStillARoundHole() {
        // 有的系统把孔的外接框一路延到屏幕上沿（t = 0、高 ≥ 宽）
        CutoutGeometry.Hole h = CutoutGeometry.pick(rects(new int[] {688, 0, 752, 106}), MZ_W, MZ_H, MZ_D, MZ_SB);
        assertEquals(CutoutGeometry.Kind.ROUND, h.kind);
        assertEquals(64f, h.w, 0.01f);
        assertEquals("孔心 = 下沿 − 半径", 74f, h.cy, 0.01f);
    }

    @Test public void meizuWithWrapOffStaysBelowStatusBarButGrowsFromHole() {
        IslandGeometry.Anchor a = CutoutGeometry.anchor(MZ_W, MZ_H, MZ_D, MZ_SB, false, rects(MZ_HOLE), false);
        assertFalse(a.wrap);
        assertEquals("状态栏下 2dp", MZ_SB + 7, a.top);
        assertEquals(720f, a.originCx, 0.01f);
        assertEquals(74f, a.originCy, 0.01f);
        assertEquals(Math.round(28 * MZ_D), a.compactH);
        assertEquals(CutoutGeometry.LANE_NONE, CutoutGeometry.laneMode(a, MZ_W));
    }

    // ---------------------------------------------------------------- Note 8：无挖孔（上边框），v3 位置不变

    @Test public void note8NoCutoutFallsBackToV3Anchor() {
        float d = 2.625f;
        IslandGeometry.Anchor a = CutoutGeometry.anchor(1080, 2220, d, 63, false, NONE, true);
        IslandGeometry.Anchor v3 = IslandGeometry.anchor(1080, d, 63, false);
        assertEquals(v3.cx, a.cx);
        assertEquals(v3.top, a.top);
        assertEquals(68, a.top);
        assertFalse(a.wrap);
        assertEquals(CutoutGeometry.Kind.NONE, a.cutout);
        // 源点 = 锚点处的 28dp 小胶囊（与 v3「从小胶囊长出来」一致）
        assertEquals(540f, a.originCx, 0.01f);
        assertEquals(68f, a.originTop(), 0.01f);
        assertEquals(28f * d, a.originW, 0.01f);
        assertEquals(540f, CutoutGeometry.cxFor(a, 300f, 1080, d), 0.01f);
        assertEquals(0f, CutoutGeometry.expandedInsetTop(a, d), 0f);
        // null 列表同样处理
        assertEquals(68, CutoutGeometry.anchor(1080, 2220, d, 63, false, null, true).top);
        // 全屏 8dp
        assertEquals(21, CutoutGeometry.anchor(1080, 2220, d, 63, true, NONE, true).top);
    }

    // ---------------------------------------------------------------- 角落孔（左上 / 右上）

    @Test public void cornerHoleLeftKeepsIslandOnScreenAndContentRightOfHole() {
        float d = 2.625f;   // 1080×2400 420dpi
        int w = 1080;
        int[] hole = {50, 40, 110, 100};   // 孔心 (80, 70)，60px
        IslandGeometry.Anchor a = CutoutGeometry.anchor(w, 2400, d, 110, false, rects(hole), true);
        assertTrue(a.wrap);
        assertEquals(CutoutGeometry.Kind.ROUND, a.cutout);
        assertEquals(CutoutGeometry.LANE_LEFT, CutoutGeometry.laneMode(a, w));
        float plain = CutoutGeometry.compactWidth(100f * d, w, d);
        float cw = CutoutGeometry.wrapCompactWidth(a, 60f * d, 30f * d, plain, w, d);
        float cx = CutoutGeometry.cxFor(a, cw, w, d);
        float left = cx - cw / 2f, right = cx + cw / 2f;
        float m = CutoutGeometry.EDGE_MARGIN_DP * d;
        assertEquals("贴左屏边 8dp", m, left, 0.5f);
        assertTrue("孔在岛里", left < 50 && right > 110);
        assertEquals("孔右侧放得下原内容", plain, right - a.laneR, 0.5f);
        // 展开卡：放不下以孔为中心 → 夹到屏内（左右各 ≥ 8dp）
        float exp = CutoutGeometry.expandedWidth(w, d);
        float ecx = CutoutGeometry.cxFor(a, exp, w, d);
        assertTrue(ecx - exp / 2f >= m - 0.01f && ecx + exp / 2f <= w - m + 0.01f);
        assertTrue("孔仍在展开卡里", ecx - exp / 2f < 50);
    }

    @Test public void cornerHoleRightMirrors() {
        float d = 2.75f;   // 1080×2340 440dpi
        int w = 1080;
        int[] hole = {960, 36, 1020, 96};
        IslandGeometry.Anchor a = CutoutGeometry.anchor(w, 2340, d, 104, false, rects(hole), true);
        assertEquals(CutoutGeometry.LANE_RIGHT, CutoutGeometry.laneMode(a, w));
        float plain = CutoutGeometry.compactWidth(90f * d, w, d);
        float cw = CutoutGeometry.wrapCompactWidth(a, 50f * d, 30f * d, plain, w, d);
        float cx = CutoutGeometry.cxFor(a, cw, w, d);
        assertEquals("贴右屏边", w - CutoutGeometry.EDGE_MARGIN_DP * d, cx + cw / 2f, 0.5f);
        assertEquals("孔左侧放得下原内容", plain, a.laneL - (cx - cw / 2f), 0.5f);
        float[] g = CutoutGeometry.dualWrap(a, 28f * d, 44f * d, 6f * d, w, d);
        assertTrue("偏右：孔落在副泡里", g[4] < hole[0] && g[5] > hole[2]);
        assertTrue("主泡在副泡左边且不重叠", g[2] + 6f * d <= g[4] + 0.01f);
    }

    @Test public void centerHoleDualPutsHoleInsideMainBubble() {
        IslandGeometry.Anchor a = CutoutGeometry.anchor(MZ_W, MZ_H, MZ_D, MZ_SB, false, rects(MZ_HOLE), true);
        float[] g = CutoutGeometry.dualWrap(a, 28f * MZ_D, 44f * MZ_D, 6f * MZ_D, MZ_W, MZ_D);
        assertTrue("孔在主泡里", g[1] < MZ_HOLE[0] && g[2] > MZ_HOLE[2]);
        assertTrue("任务环在孔左边", g[3] < a.laneL);
        assertEquals("总宽 = 副泡右 − 主泡左", g[5] - g[1], g[8], 0.01f);
    }

    // ---------------------------------------------------------------- 双摄药丸孔 / 两个挨着的孔 / 多个挖孔

    @Test public void dualCameraPillHole() {
        float d = 3f;
        CutoutGeometry.Hole h = CutoutGeometry.pick(rects(new int[] {1180, 40, 1380, 110}), 1440, 3040, d, 120);
        assertEquals(CutoutGeometry.Kind.PILL, h.kind);
        // 两个圆孔挨着（间隙 15px = 5dp < 16dp）→ 合成一个药丸孔
        CutoutGeometry.Hole m = CutoutGeometry.pick(rects(new int[] {640, 40, 700, 100}, new int[] {715, 40, 775, 100}), 1440, 3040, d, 120);
        assertEquals(CutoutGeometry.Kind.PILL, m.kind);
        assertEquals(135f, m.w, 0.01f);
        assertEquals(707.5f, m.cx, 0.01f);
        IslandGeometry.Anchor a = CutoutGeometry.anchor(1440, 3040, d, 120, false, rects(new int[] {640, 40, 700, 100}, new int[] {715, 40, 775, 100}), true);
        assertTrue(a.wrap);
        assertTrue("过道盖住两个孔", a.laneL < 640 && a.laneR > 775);
    }

    @Test public void multipleCutoutsPickTheTopHoleNearestCentre() {
        float d = 3f;
        int w = 1440, h = 3200;
        // 底部一个（不算）、左上角一个、正中一个 → 选正中
        List<int[]> rs = rects(new int[] {690, 3100, 750, 3160}, new int[] {40, 40, 100, 100}, new int[] {690, 40, 750, 100});
        CutoutGeometry.Hole p = CutoutGeometry.pick(rs, w, h, d, 120);
        assertEquals(720f, p.cx, 0.01f);
        // 刘海 + 圆孔 → 选圆孔
        CutoutGeometry.Hole q = CutoutGeometry.pick(rects(new int[] {420, 0, 1020, 150}, new int[] {1300, 40, 1360, 100}), w, h, d, 120);
        assertEquals(CutoutGeometry.Kind.ROUND, q.kind);
        assertEquals(1330f, q.cx, 0.01f);
        // 空 / 退化的框
        assertNull(CutoutGeometry.pick(rects(new int[] {10, 10, 10, 20}, null, new int[] {1, 2}), w, h, d, 120));
    }

    // ---------------------------------------------------------------- 刘海 / 水滴 → 上方居中

    @Test public void notchAndWaterdropFallBackToTopCentre() {
        float d = 2.75f;
        int w = 1080;
        int[] waterdrop = {440, 0, 640, 80};
        int[] wideNotch = {300, 0, 780, 160};
        for (int[] n : new int[][] {waterdrop, wideNotch}) {
            IslandGeometry.Anchor a = CutoutGeometry.anchor(w, 2280, d, 90, false, rects(n), true);
            assertEquals(CutoutGeometry.Kind.NOTCH, a.cutout);
            assertFalse(a.wrap);
            assertEquals(540, a.cx);
            assertEquals(90 + Math.round(2 * d), a.top);
            assertEquals("源点是锚点处的种子，不是刘海", 540f, a.originCx, 0.01f);
            assertEquals(a.top, a.originTop(), 0.01f);
        }
    }

    // ---------------------------------------------------------------- 横屏：孔在左右短边 → 上方居中

    @Test public void landscapeHoleOnShortEdgeIsIgnored() {
        // 魅族横屏：3200×1440，孔在左边
        int[] side = {42, 688, 106, 752};
        CutoutGeometry.Hole h = CutoutGeometry.classify(side[0], side[1], side[2], side[3], 3200, 1440, MZ_D, MZ_SB);
        assertEquals(CutoutGeometry.Kind.SIDE, h.kind);
        IslandGeometry.Anchor a = CutoutGeometry.anchor(3200, 1440, MZ_D, MZ_SB, false, rects(side), true);
        assertFalse(a.wrap);
        assertEquals(1600, a.cx);
        assertEquals(MZ_SB + 7, a.top);
        // 横屏宽 914dp：展开卡放宽到 460dp，紧凑态封顶 168dp
        assertEquals(460f, CutoutGeometry.expandedWidth(3200, MZ_D) / MZ_D, 0.5f);
        assertEquals(168f, CutoutGeometry.compactMaxWidth(3200, MZ_D) / MZ_D, 0.5f);
        // 反方向（270°）同样
        assertFalse(CutoutGeometry.anchor(3200, 1440, MZ_D, MZ_SB, false, rects(new int[] {3094, 688, 3158, 752}), true).wrap);
    }

    // ---------------------------------------------------------------- 平板

    @Test public void tabletScalesWidthsWithinLimits() {
        float d = 2f;   // 2560×1600 320dpi = 1280×800dp
        IslandGeometry.Anchor a = CutoutGeometry.anchor(2560, 1600, d, 48, false, NONE, true);
        assertEquals(1280, a.cx);
        assertEquals(52, a.top);
        assertEquals(460f, CutoutGeometry.expandedWidth(2560, d) / d, 0.01f);
        assertEquals(168f, CutoutGeometry.compactMaxWidth(2560, d) / d, 0.01f);
        assertEquals(168f, CutoutGeometry.compactWidth(400f * d, 2560, d) / d, 0.01f);
        // 竖屏平板 800dp：0.6×800 = 480 → 封顶 460；紧凑 0.29×800 = 232 → 封顶 168
        assertEquals(460f, CutoutGeometry.expandedWidth(1600, d) / d, 0.01f);
        // 小屏手机：屏宽 − 24dp 优先（与 v3 一致）
        assertEquals(336f, CutoutGeometry.expandedWidth(Math.round(360 * 3f), 3f) / 3f, 0.5f);
        assertEquals(276f, CutoutGeometry.expandedWidth(Math.round(300 * 3f), 3f) / 3f, 0.5f);
        assertEquals("手机上紧凑封顶仍 120dp", 120f, CutoutGeometry.compactMaxWidth(Math.round(411 * 3f), 3f) / 3f, 0.01f);
    }

    // ---------------------------------------------------------------- 不同 density：同一块物理屏，dp 结果一致

    @Test public void densityIndependent() {
        // 同一台魅族改「显示大小」：3.5 / 3.0 / 4.0 —— 孔的像素不变，紧凑高按 dp 下限 28dp 变化，孔始终在紧凑态里、居中
        for (float d : new float[] {3.0f, 3.5f, 4.0f}) {
            IslandGeometry.Anchor a = CutoutGeometry.anchor(MZ_W, MZ_H, d, Math.round(39 * d), false, rects(MZ_HOLE), true);
            assertTrue(a.wrap);
            assertEquals(720, a.cx);
            assertTrue(a.compactH >= Math.round(28 * d));
            assertTrue(a.top <= 42 && a.top + a.compactH >= 106);
            assertEquals("孔竖直居中", 74f, a.top + a.compactH / 2f, 1f);
        }
        // 低密度（1.5）下 64px 孔 = 42.7dp：仍是孔（≤ 64dp）
        assertEquals(CutoutGeometry.Kind.ROUND, CutoutGeometry.classify(688, 42, 752, 106, 1440, 3200, 1.5f, 60).kind);
        // 超大圆（> 64dp）不当孔
        assertEquals(CutoutGeometry.Kind.NOTCH, CutoutGeometry.classify(600, 20, 840, 260, 1440, 3200, 3.5f, 300).kind);
    }

    @Test public void holeTooHighClampsTopToScreen() {
        float d = 3f;
        IslandGeometry.Anchor a = CutoutGeometry.anchor(1080, 2400, d, 80, false, rects(new int[] {510, 4, 570, 64}), true);
        assertTrue(a.wrap);
        assertTrue(a.top >= 0);
        assertTrue(a.top + a.compactH >= 64);
    }

    @Test public void legacyAnchorHasNoOrigin() {
        IslandGeometry.Anchor a = IslandGeometry.anchor(1080, 2.625f, 63, false);
        assertFalse(a.hasOrigin());
        assertFalse(a.wrap);
        assertEquals(CutoutGeometry.Kind.NONE, a.cutout);
        assertTrue(a.toString().startsWith("cx=540 top=68"));
    }
}
