package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.ui.motion.MotionSpec;

/** [R13] 弹簧表逐条核对方案 v2.6（springs_v2.py 的输出），APPEAR 锁死。 */
public class MotionSpecTest {

    /** {duration, bounce, ω, ζ, k, c, 超调%, 0.5% 收敛 s}（方案 v2.6 表原样抄录）。 */
    static final Object[][] TABLE = {
            {MotionSpec.CARD_IN, 12.57, 0.82, 158, 20.61, 1.0, 0.562},
            {MotionSpec.CARD_RESIZE, 13.96, 0.90, 195, 25.13, 0.1, 0.396},
            {MotionSpec.TEXT_IN, 15.71, 1.00, 247, 31.42, 0.0, 0.477},
            {MotionSpec.CHIP_IN, 14.96, 0.80, 224, 23.94, 1.4, 0.478},
            {MotionSpec.GLOW_INHALE, 8.98, 1.00, 81, 17.95, 0.0, 0.832},
            {MotionSpec.GLOW_LEVEL, 26.18, 0.75, 685, 39.27, 2.6, 0.271},
            {MotionSpec.MORPH_TOP, 16.53, 0.90, 273, 29.76, 0.1, 0.335},
            {MotionSpec.MORPH_BOTTOM, 11.64, 0.88, 135, 20.48, 0.3, 0.443},
            {MotionSpec.MORPH_WIDTH, 14.28, 0.92, 204, 26.28, 0.0, 0.415},
            {MotionSpec.MORPH_RADIUS, 17.45, 1.00, 305, 34.91, 0.0, 0.430},
            {MotionSpec.ISLAND_BULGE, 19.63, 0.85, 386, 33.38, null, 0.328},
            {MotionSpec.UNMORPH, 12.08, 0.84, 146, 20.30, 0.7, 0.566},
            {MotionSpec.DISMISS, 15.71, 1.00, 247, 31.42, 0.0, 0.477},
            {MotionSpec.PRESS, 20.94, 0.72, 439, 30.16, 3.6, 0.335},
    };

    @Test public void tableMatchesPlanV26() {
        for (Object[] r : TABLE) {
            MotionSpec.Spec s = (MotionSpec.Spec) r[0];
            assertEquals(s.name + " ω", ((Number) r[1]).doubleValue(), s.omega(), 0.01);
            assertEquals(s.name + " ζ", ((Number) r[2]).doubleValue(), s.zeta(), 0.001);
            assertEquals(s.name + " k", ((Number) r[3]).doubleValue(), s.stiffness(), 0.6);
            assertEquals(s.name + " c", ((Number) r[4]).doubleValue(), s.damping(), 0.01);
            float[] sim = MotionSpec.simulate(s.omega(), s.zeta(), 0f, 0f, 0.005f);
            if (r[5] != null) assertEquals(s.name + " 超调%", ((Number) r[5]).doubleValue(), sim[0], 0.06);
            assertEquals(s.name + " 收敛", ((Number) r[6]).doubleValue(), sim[1], 0.003);
        }
        assertEquals("表里 14 条弹簧", 14, MotionSpec.all().length);
    }

    @Test public void appearIsLockedAndNotRedefined() {
        assertEquals(22f, IslandGeometry.SPRING_OMEGA, 0f);
        assertEquals(0.86f, IslandGeometry.SPRING_ZETA, 0f);
        assertEquals(0.8f, IslandGeometry.CONTENT_GATE, 0f);
        float[] ap = IslandGeometry.springFor(IslandGeometry.Motion.APPEAR);
        assertEquals(22f, ap[0], 0f);
        assertEquals(0.86f, ap[1], 0f);
        float[] sim = MotionSpec.simulate(22f, 0.86f, 0f, 0f, 0.005f);
        assertEquals("APPEAR 超调 0.4%（v2 表）", 0.4, sim[0], 0.06);
        assertEquals(0.221, sim[1], 0.003);
        for (MotionSpec.Spec s : MotionSpec.all()) {
            assertTrue("没有一条新弹簧与 APPEAR 重合（APPEAR 只住在 IslandGeometry）: " + s,
                    Math.abs(s.omega() - 22f) > 0.5f || Math.abs(s.zeta() - 0.86f) > 0.01f);
        }
    }

    @Test public void bulgeKickPeaks() {
        MotionSpec.Spec b = MotionSpec.ISLAND_BULGE;
        float[] kx = MotionSpec.kick(b.omega(), b.zeta(), MotionSpec.BULGE_VX);
        assertEquals("宽 +6%", 0.06, kx[0], 0.002);
        assertEquals("峰值约 53 ms", 0.053, kx[1], 0.004);
        assertTrue("回弹几乎为 0", kx[2] > -0.001f);
        float[] ky = MotionSpec.kick(b.omega(), b.zeta(), -MotionSpec.BULGE_VY);
        assertEquals("高 −8%（对称）", 0.08, ky[0], 0.002);
    }

    @Test public void reducedMotionHasNoBounce() {
        for (MotionSpec.Spec s : MotionSpec.all()) {
            assertEquals(1f, s.reduced().zeta(), 0f);
            assertEquals(s.omega(), s.reduced().omega(), 1e-4);
        }
    }

    @Test public void springIntegratorKeepsVelocityOnRetarget() {
        IslandGeometry.Spring sp = MotionSpec.MORPH_TOP.spring(0f);
        sp.t = 100f;
        for (int i = 0; i < 6; i++) sp.step(0.016f);
        float vel = sp.vel;
        MotionSpec.UNMORPH.applyTo(sp);
        assertEquals("换参保速度（被打断时不跳变）", vel, sp.vel, 0f);
        assertEquals(MotionSpec.UNMORPH.omega(), sp.omega, 1e-5);
    }
}
