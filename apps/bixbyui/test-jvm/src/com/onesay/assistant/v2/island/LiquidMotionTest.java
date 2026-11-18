package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * [island-cutout-1118] 弹簧数学（解析解 vs 欧拉、过冲公式、稳定性）、各阶段参数、「减少动画」、水滴轮廓、metaball 颈，
 * 以及 IslandMorph 整段「从孔里长出 → 展开 → 收起 → 缩回孔里」的性质。
 */
public class LiquidMotionTest {

    // ================================================================== 弹簧数学

    static double exactUnder(double t, double x0, double v0, double w, double z) {
        double wd = w * Math.sqrt(1 - z * z);
        return Math.exp(-z * w * t) * (x0 * Math.cos(wd * t) + (v0 + z * w * x0) / wd * Math.sin(wd * t));
    }

    @Test public void analyticStepMatchesClosedFormAndIsDtIndependent() {
        double w = 2 * Math.PI / 0.45, z = 0.76;
        double[] a = {0, 0};
        for (int i = 0; i < 60; i++) LiquidMotion.step(a, 100, w, z, 1.0 / 60);
        double[] b = {0, 0};
        LiquidMotion.step(b, 100, w, z, 1.0);
        assertEquals("60 小步 = 1 大步", b[0], a[0], 1e-6);
        assertEquals(b[1], a[1], 1e-5);
        double expect = 100 + exactUnder(1.0, -100, 0, w, z);
        assertEquals(expect, a[0], 1e-6);
        // 速度 = 位置的数值导数
        double[] c = {0, 0}, e = {0, 0};
        LiquidMotion.step(c, 100, w, z, 0.2);
        LiquidMotion.step(e, 100, w, z, 0.2 + 1e-6);
        assertEquals((e[0] - c[0]) / 1e-6, c[1], 1e-2);
    }

    @Test public void criticalAndOverdampedBranchesAreContinuousWithUnderdamped() {
        double w = 20;
        for (double t : new double[] {0.05, 0.1, 0.3}) {
            double[] under = {0, 30}, crit = {0, 30}, over = {0, 30};
            LiquidMotion.step(under, 50, w, 0.999, t);
            LiquidMotion.step(crit, 50, w, 1.0, t);
            LiquidMotion.step(over, 50, w, 1.001, t);
            assertEquals(crit[0], under[0], 0.05);
            assertEquals(crit[0], over[0], 0.05);
            assertEquals(crit[1], over[1], 0.5);
        }
        double[] o = {0, 0};
        for (int i = 0; i < 600; i++) {
            LiquidMotion.step(o, 1, w, 1.6, 1.0 / 60);
            assertTrue("过阻尼不过冲", o[0] <= 1 + 1e-9);
        }
    }

    @Test public void overshootFormulaMatchesSimulation() {
        for (double z : new double[] {0.6, 0.7, 0.74, 0.76, 0.86}) {
            double[] xv = {0, 0};
            double peak = 0;
            for (int i = 0; i < 4000; i++) {
                LiquidMotion.step(xv, 1, 14, z, 0.001);
                peak = Math.max(peak, xv[0]);
            }
            assertEquals("ζ=" + z, LiquidMotion.overshoot(z), peak - 1, 2e-4);
        }
        assertEquals(0, LiquidMotion.overshoot(1.0), 0);
        assertEquals(0.0254, LiquidMotion.overshoot(0.76), 5e-4);
    }

    @Test public void eulerSpringAgreesWithAnalyticAt60fps() {
        float w = IslandGeometry.omegaOf(0.44f), z = 0.76f;
        IslandGeometry.Spring s = new IslandGeometry.Spring(98f, w, z);
        s.t = 380f;
        double[] xv = {98, 0};
        double worst = 0;
        for (int i = 0; i < 90; i++) {
            s.step(1f / 60f);
            LiquidMotion.step(xv, 380, w, z, 1.0 / 60);
            worst = Math.max(worst, Math.abs(s.v - xv[0]));
        }
        // 半隐式欧拉（每帧 4 子步）有一点相位误差：与解析解差 ≤ 3% 行程；岛上和 JVM 演示用的都是这同一个积分，所以演示 = 真机
        assertTrue("欧拉 4 子步与解析解差 < 3% 行程，实际 " + worst, worst < 0.03 * (380 - 98));
        assertTrue("终点一致", Math.abs(s.v - 380f) < 0.5f && Math.abs(xv[0] - 380) < 0.5);
    }

    @Test public void stableForHugeDtAndNoNaN() {
        double[] xv = {0, 5000};
        LiquidMotion.step(xv, 10, 60, 0.3, 5.0);
        assertFalse(Double.isNaN(xv[0]) || Double.isInfinite(xv[0]));
        assertEquals(10, xv[0], 1e-3);
        double[] z = {3, 1};
        LiquidMotion.step(z, 10, 0, 0.8, 0.1);
        assertEquals("ω=0 → 直接到终点", 10, z[0], 0);
        double[] n = {3, 1};
        LiquidMotion.step(n, 10, 20, 0.8, 0);
        assertEquals("dt=0 不动", 3, n[0], 0);
    }

    @Test public void settleTimesAreSnappy() {
        for (LiquidMotion.Phase p : LiquidMotion.Phase.values()) {
            for (LiquidMotion.Axis a : new LiquidMotion.Axis[] {LiquidMotion.Axis.W, LiquidMotion.Axis.H, LiquidMotion.Axis.R}) {
                float[] q = LiquidMotion.spring(p, a);
                double ts = LiquidMotion.settleTime(q[0], q[1], 0.01);
                assertTrue(p + "/" + a + " 1% 内落定 " + ts + " s", ts > 0.1 && ts < 0.75);
            }
        }
    }

    // ================================================================== 阶段参数

    @Test public void phaseParamsGiveSlightOvershootOnlyWhenGrowing() {
        for (LiquidMotion.Axis a : LiquidMotion.Axis.values()) {
            for (LiquidMotion.Phase p : LiquidMotion.Phase.values()) {
                float[] q = LiquidMotion.spring(p, a);
                double os = LiquidMotion.overshoot(q[1]);
                assertTrue(p + "/" + a + " 过冲 ≤ 5%（实际 " + os + "）", os <= 0.05);
            }
        }
        assertTrue("展开有可见回弹", LiquidMotion.overshoot(LiquidMotion.spring(LiquidMotion.Phase.EXPAND, LiquidMotion.Axis.H)[1]) > 0.02);
        assertTrue("从孔里长出有回弹", LiquidMotion.overshoot(LiquidMotion.spring(LiquidMotion.Phase.EMERGE, LiquidMotion.Axis.W)[1]) > 0.03);
        assertTrue("收起几乎不弹", LiquidMotion.overshoot(LiquidMotion.spring(LiquidMotion.Phase.COLLAPSE, LiquidMotion.Axis.H)[1]) < 0.01);
        assertEquals("缩回孔里不过冲（不会冲出孔再回来）", 0.0, LiquidMotion.overshoot(LiquidMotion.spring(LiquidMotion.Phase.HIDE, LiquidMotion.Axis.R)[1]), 0);
        // 液态：展开时高比宽慢、收起时高比宽快
        assertTrue(LiquidMotion.params(LiquidMotion.Phase.EXPAND, LiquidMotion.Axis.H)[0] > LiquidMotion.params(LiquidMotion.Phase.EXPAND, LiquidMotion.Axis.W)[0]);
        assertTrue(LiquidMotion.params(LiquidMotion.Phase.COLLAPSE, LiquidMotion.Axis.H)[0] < LiquidMotion.params(LiquidMotion.Phase.COLLAPSE, LiquidMotion.Axis.W)[0]);
    }

    @Test public void panelHandoffSpringStaysLocked() {
        // R11/R13：面板收成岛（APPEAR）ω22 ζ0.86 一个不动
        for (LiquidMotion.Axis a : new LiquidMotion.Axis[] {LiquidMotion.Axis.W, LiquidMotion.Axis.H, LiquidMotion.Axis.R}) {
            float[] q = LiquidMotion.spring(LiquidMotion.Phase.APPEAR, a);
            assertEquals(22f, q[0], 0f);
            assertEquals(0.86f, q[1], 0f);
        }
        assertEquals(LiquidMotion.Phase.APPEAR, LiquidMotion.phaseOf(IslandGeometry.Motion.EXPAND, true));
        assertEquals(LiquidMotion.Phase.EMERGE, LiquidMotion.phaseOf(IslandGeometry.Motion.APPEAR, false));
        assertEquals(LiquidMotion.Phase.HIDE, LiquidMotion.phaseOf(IslandGeometry.Motion.HIDE, false));
        assertEquals(LiquidMotion.Phase.COLLAPSE, LiquidMotion.phaseOf(IslandGeometry.Motion.NONE, false));
    }

    @Test public void reduceAnimationsTimeScale() {
        assertEquals("animator_duration_scale = 0 → 关动画", 0f, LiquidMotion.timeScale(0f), 0f);
        assertEquals(1f, LiquidMotion.timeScale(1f), 0f);
        assertEquals(2f, LiquidMotion.timeScale(0.5f), 1e-6f);
        assertEquals(0.5f, LiquidMotion.timeScale(2f), 1e-6f);
        assertEquals("异常值按 1", 1f, LiquidMotion.timeScale(Float.NaN), 0f);
        assertEquals(1f, LiquidMotion.timeScale(-1f), 0f);
        assertEquals("最慢 10 倍", 0.1f, LiquidMotion.timeScale(50f), 1e-6f);
    }

    // ================================================================== 路径

    /** 记录路径命令 + 包围盒。 */
    static final class Rec implements LiquidMotion.PathSink {
        final List<float[]> pts = new ArrayList<float[]>();
        int moves, closes;
        /** 颈的路径闭合时是一条跨过孔圆的直线，不要求终点 = 起点。 */
        boolean strict = true;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sx, sy, lx, ly;

        void add(float x, float y) {
            assertFalse(Float.isNaN(x) || Float.isNaN(y));
            pts.add(new float[] {x, y});
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            lx = x;
            ly = y;
        }

        @Override public void moveTo(float x, float y) {
            moves++;
            sx = x;
            sy = y;
            add(x, y);
        }

        @Override public void lineTo(float x, float y) {
            add(x, y);
        }

        @Override public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            add(x1, y1);
            add(x2, y2);
            add(x3, y3);
        }

        @Override public void close() {
            closes++;
            if (!strict) return;
            assertEquals("闭合：终点回到起点", sx, lx, 0.01f);
            assertEquals(sy, ly, 0.01f);
        }
    }

    static Rec lax() {
        Rec r = new Rec();
        r.strict = false;
        return r;
    }

    @Test public void outlineIsClosedAndBoundedWithSag() {
        Rec r = new Rec();
        LiquidMotion.outline(r, 10, 20, 410, 220, 30, 0);
        assertEquals(1, r.moves);
        assertEquals(1, r.closes);
        assertEquals(10f, r.minX, 0.01f);
        assertEquals(410f, r.maxX, 0.01f);
        assertEquals(20f, r.minY, 0.01f);
        assertEquals("不下垂 = 平底", 220f, r.maxY, 0.01f);
        Rec s = new Rec();
        LiquidMotion.outline(s, 10, 20, 410, 220, 30, 12);
        assertEquals("控制点 = 下垂 / 0.75", 220f + 16f, s.maxY, 0.01f);
        // 药丸（无直底）：下垂由两个下圆角承担 → 水滴
        Rec p = new Rec();
        LiquidMotion.outline(p, 0, 0, 98, 98, 49, 6);
        assertEquals(98f + 6f, p.maxY, 0.01f);
        assertEquals(1, p.closes);
        // 退化
        Rec e = new Rec();
        LiquidMotion.outline(e, 5, 5, 5, 50, 10, 0);
        assertEquals(0, e.moves);
        // 圆角夹到短边一半
        Rec k = new Rec();
        LiquidMotion.outline(k, 0, 0, 200, 40, 999, 0);
        assertEquals(40f, k.maxY, 0.01f);
    }

    @Test public void sagFollowsVerticalVelocityAndIsCapped() {
        float d = 3.5f;
        assertEquals(0f, LiquidMotion.sag(0f, 300f, d), 0f);
        assertTrue(LiquidMotion.sag(800f, 300f, d) > 0f);
        assertEquals("封顶 10dp", 10f * d, LiquidMotion.sag(1e6f, 1000f, d), 1e-3f);
        assertEquals("封顶 12% 高", 0.12f * 100f, LiquidMotion.sag(1e6f, 100f, d), 1e-3f);
        assertEquals("往上收时内凹一半", -0.5f * 10f * d, LiquidMotion.sag(-1e6f, 1000f, d), 1e-3f);
    }

    @Test public void metaballNeckExistsOnlyWhenCloseAndNotNested() {
        float[] q = new float[16];
        assertTrue("相近 → 有颈", LiquidMotion.neck(100, 50, 30, 100, 130, 40, 1f, q));
        for (float v : q) assertFalse(Float.isNaN(v));
        Rec r = lax();
        LiquidMotion.neckPath(r, q);
        assertEquals(1, r.closes);
        assertTrue("颈在两圆之间", r.minY >= 50 - 30 - 1 && r.maxY <= 130 + 40 + 1);
        assertFalse("太远 → 断开", LiquidMotion.neck(100, 50, 30, 100, 500, 40, 1f, q));
        assertFalse("一个包着另一个 → 不需要", LiquidMotion.neck(100, 50, 10, 100, 55, 40, 1f, q));
        assertFalse("spread 0 → 不画", LiquidMotion.neck(100, 50, 30, 100, 130, 40, 0f, q));
        // spread 越大颈越粗
        float[] thin = new float[16], fat = new float[16];
        LiquidMotion.neck(100, 50, 30, 100, 150, 40, 0.2f, thin);
        LiquidMotion.neck(100, 50, 30, 100, 150, 40, 1f, fat);
        assertTrue(Math.abs(fat[0] - fat[14]) > Math.abs(thin[0] - thin[14]));
    }

    // ================================================================== IslandMorph 整段

    static IslandGeometry.Anchor meizu(boolean wrap) {
        return CutoutGeometry.anchor(1440, 3200, 3.5f, 136, false,
                new ArrayList<int[]>(Arrays.asList(new int[] {688, 42, 752, 106})), wrap);
    }

    static float run(IslandMorph m, float seconds, float[] maxW, float[] maxH) {
        int n = Math.round(seconds * 60);
        for (int i = 0; i < n; i++) {
            m.step(1f / 60f);
            if (maxW != null) maxW[0] = Math.max(maxW[0], m.w.v);
            if (maxH != null) maxH[0] = Math.max(maxH[0], m.h.v);
        }
        return n;
    }

    @Test public void emergesFromHoleExpandsCollapsesAndReturnsIntoHole() {
        float d = 3.5f;
        IslandGeometry.Anchor a = meizu(true);
        IslandMorph m = new IslandMorph();
        m.setAnchor(a, d);
        // 1) 从孔里长出来：第一帧 = 孔
        m.setPhase(LiquidMotion.Phase.EMERGE);
        float cw = 160f * d, ch = a.compactH;
        m.targetShape(cw, ch, ch / 2f, CutoutGeometry.cxFor(a, cw, 1440, d), a.top);
        m.seedAtOrigin();
        assertEquals(64f, m.w.v, 0.01f);
        assertEquals(64f, m.h.v, 0.01f);
        assertEquals(720f, m.left() + m.w.v / 2f, 0.01f);
        assertEquals(42f, m.top(), 0.01f);
        assertTrue(m.originIsHole());
        float[] mw = {0}, mh = {0};
        run(m, 1.2f, mw, mh);
        assertTrue(m.settled(0.5f));
        assertEquals(cw, m.w.v, 0.5f);
        assertEquals(a.top, m.top(), 0.5f);
        assertTrue("有回弹（液态）", mw[0] > cw * 1.01f);
        assertTrue("回弹 ≤ 5%", mw[0] < cw * 1.05f);
        assertTrue("落定后孔在形状里", m.left() < 688 && m.right() > 752 && m.top() < 42 && m.bottom() > 106);

        // 2) 展开成卡：上沿不动，往下长；中途底边下垂
        m.setPhase(LiquidMotion.Phase.EXPAND);
        float ew = CutoutGeometry.expandedWidth(1440, d), eh = 180f * d;
        m.targetShape(ew, eh, 30f * d, CutoutGeometry.cxFor(a, ew, 1440, d), a.top);
        float maxSag = 0f, topDrift = 0f;
        float[] eW = {0}, eH = {0};
        for (int i = 0; i < 72; i++) {
            m.step(1f / 60f);
            maxSag = Math.max(maxSag, m.sag());
            topDrift = Math.max(topDrift, Math.abs(m.top() - a.top));
            eW[0] = Math.max(eW[0], m.w.v);
            eH[0] = Math.max(eH[0], m.h.v);
        }
        assertTrue("展开中底边下垂（水滴）" + maxSag, maxSag > 2f * d);
        assertTrue("上沿钉在孔上", topDrift < 1f);
        assertTrue("高有回弹", eH[0] > eh * 1.01f && eH[0] < eh * 1.05f);
        assertEquals(ew, m.w.v, 1f);

        // 3) 收起回紧凑
        m.setPhase(LiquidMotion.Phase.COLLAPSE);
        m.targetShape(cw, ch, ch / 2f, CutoutGeometry.cxFor(a, cw, 1440, d), a.top);
        run(m, 1.0f, null, null);
        assertEquals(cw, m.w.v, 1f);
        assertEquals(ch, m.h.v, 1f);

        // 4) 缩回孔里
        m.setPhase(LiquidMotion.Phase.HIDE);
        m.targetOrigin();
        assertTrue(m.toOrigin());
        boolean near = false;
        for (int i = 0; i < 60 && !near; i++) {
            m.step(1f / 60f);
            near = m.nearOrigin();
        }
        assertTrue("1 s 内缩到孔附近（可以淡出）", near);
        run(m, 1.0f, null, null);
        assertEquals(64f, m.w.v, 0.5f);
        assertEquals(42f, m.top(), 0.5f);
        assertTrue("从不缩到比孔还小（ζ=0.9/0.95，宽高下冲 < 1px）", m.w.v > 63f);
    }

    @Test public void belowModeDrawsGooeyNeckFromHoleThenReleases() {
        float d = 3.5f;
        IslandGeometry.Anchor a = meizu(false);
        IslandMorph m = new IslandMorph();
        m.setAnchor(a, d);
        m.setPhase(LiquidMotion.Phase.EMERGE);
        float cw = 120f * d, ch = 28f * d;
        m.targetShape(cw, ch, ch / 2f, a.cx, a.top);
        m.seedAtOrigin();
        Rec r = lax();
        int necks = 0;
        for (int i = 0; i < 6; i++) {
            m.step(1f / 60f);
            r = lax();
            if (m.buildNeck(r, 0, 0)) necks++;
        }
        assertTrue("刚离开孔时有颈", necks > 0);
        run(m, 1.5f, null, null);
        assertFalse("落定后颈松开", m.buildNeck(lax(), 0, 0));
        // 收起：颈重新出现
        m.setPhase(LiquidMotion.Phase.HIDE);
        m.targetOrigin();
        boolean sawNeck = false;
        for (int i = 0; i < 30; i++) {
            m.step(1f / 60f);
            sawNeck |= m.buildNeck(lax(), 0, 0);
        }
        assertTrue(sawNeck);
    }

    @Test public void noCutoutGrowsFromSeedAtTopCentreWithoutNeck() {
        float d = 2.625f;
        IslandGeometry.Anchor a = CutoutGeometry.anchor(1080, 2220, d, 63, false, null, true);
        IslandMorph m = new IslandMorph();
        m.setAnchor(a, d);
        m.setPhase(LiquidMotion.Phase.EMERGE);
        m.targetShape(110f * d, 28f * d, 14f * d, a.cx, a.top);
        m.seedAtOrigin();
        assertEquals(28f * d, m.w.v, 0.01f);
        assertEquals(a.top, m.top(), 0.01f);
        assertFalse(m.originIsHole());
        for (int i = 0; i < 60; i++) {
            m.step(1f / 60f);
            assertFalse("无孔不画颈", m.buildNeck(lax(), 0, 0));
            assertEquals("上沿不动", a.top, m.top(), 0.01f);
        }
    }

    @Test public void rotationJumpsPositionInsteadOfSlidingAcrossTheScreen() {
        IslandMorph m = new IslandMorph();
        m.setAnchor(meizu(true), 3.5f);
        assertEquals(720f, m.x.v, 0f);
        IslandGeometry.Anchor land = CutoutGeometry.anchor(3200, 1440, 3.5f, 136, false,
                new ArrayList<int[]>(Arrays.asList(new int[] {42, 688, 106, 752})), true);
        m.setAnchor(land, 3.5f);
        assertEquals(1600f, m.x.v, 0f);
        assertEquals(0f, m.x.vel, 0f);
        assertEquals(land.top, m.y.v, 0f);
    }

    @Test public void snapAllIsInstant() {
        IslandMorph m = new IslandMorph();
        m.setAnchor(meizu(true), 3.5f);
        m.setPhase(LiquidMotion.Phase.EXPAND);
        m.seedAtOrigin();
        m.targetShape(1330f, 600f, 105f, 720f, 25f);
        m.snapAll();
        assertTrue(m.settled(0.01f));
        assertEquals(1330f, m.w.v, 0f);
        assertEquals(0f, m.sag(), 0f);
    }

    @Test public void buildIsCheapEnoughFor60fps() {
        IslandMorph m = new IslandMorph();
        m.setAnchor(meizu(false), 3.5f);
        m.setPhase(LiquidMotion.Phase.EMERGE);
        m.targetShape(420f, 98f, 49f, 720f, 143f);
        m.seedAtOrigin();
        m.step(0.03f);
        LiquidMotion.PathSink nop = new LiquidMotion.PathSink() {
            @Override public void moveTo(float x, float y) {}
            @Override public void lineTo(float x, float y) {}
            @Override public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
            @Override public void close() {}
        };
        for (int i = 0; i < 20000; i++) m.build(nop, 0, 0);   // 预热
        long t0 = System.nanoTime();
        int n = 20000;
        for (int i = 0; i < n; i++) {
            m.step(1f / 60f);
            m.build(nop, 0, 0);
        }
        double us = (System.nanoTime() - t0) / 1000.0 / n;
        assertTrue("每帧形状计算 " + us + " µs（预算 16.7 ms 的零头）", us < 200);
    }
}
