package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** [R13] 卡 ↔ 岛 黏连形变几何（Note 8：density 2.625，卡片 383×92dp、顶 706dp → 岛顶 26dp）。 */
public class MorphGeometryTest {

    static final float D = 2.625f;

    static float[] card() {
        float w = 1080f, side = 14f * D;
        float top = 706f * D, h = 92f * D;
        return new float[] {side, top, w - side, top + h};
    }

    static MorphGeometry toIsland() {
        return MorphGeometry.toIsland(D, card(), 34f * D, 540f, 26f * D, 28f * D, 110f * D);
    }

    @Test public void squashAndStretchDropletThenContact() {
        MorphGeometry m = toIsland();
        float startH = m.startHeight();
        float peak = 0f, peakT = 0f, contactT = -1f;
        float[] r = new float[4];
        float wAtContact = 0f;
        for (int i = 0; i < 120 && contactT < 0f; i++) {
            m.step(1f / 60f);
            m.rect(r);
            float h = r[3] - r[1];
            if (h > peak) {
                peak = h;
                peakT = m.elapsed();
            }
            assertTrue("高度钳在起始 2.2× 以内", h <= startH * 2.2f + 0.5f);
            assertTrue("圆角不超过半高 / 半宽", m.cornerRadius() <= Math.min(r[2] - r[0], r[3] - r[1]) / 2f + 0.01f);
            if (m.contact()) {
                contactT = m.elapsed();
                wAtContact = r[2] - r[0];
            }
        }
        assertTrue("飞行途中拉长成水滴（≈2.1×）: " + peak / startH, peak / startH > 1.9f && peak / startH <= 2.2f);
        assertTrue("峰值在 ~120 ms: " + peakT, peakT > 0.08f && peakT < 0.16f);
        assertTrue("约 0.25 s 接触岛: " + contactT, contactT > 0.2f && contactT < 0.32f);
        assertTrue("接触时已收窄但还比紧凑岛宽: " + wAtContact / D, wAtContact < 383f * D * 0.45f && wAtContact > 110f * D);
    }

    @Test public void frameAt120msMatchesStoryboard() {
        MorphGeometry m = toIsland();
        for (int i = 0; i < 7; i++) m.step(1f / 60f);   // ≈117 ms（03 胶片条第 2 帧）
        float[] r = new float[4];
        m.rect(r);
        assertEquals("顶 ≈ 273dp", 273f, r[1] / D, 12f);
        assertEquals("底 ≈ 470dp", 470f, r[3] / D, 12f);
        assertEquals("宽 ≈ 236–246dp", 241f, (r[2] - r[0]) / D, 10f);
        assertEquals("水平居中", 540f, (r[0] + r[2]) / 2f, 0.5f);
    }

    @Test public void snapshotFadesInFirst40Percent() {
        MorphGeometry m = toIsland();
        assertEquals(1f, m.snapshotAlpha(), 1e-4);
        boolean faded = false;
        for (int i = 0; i < 40; i++) {
            m.step(1f / 60f);
            if (m.progress() >= 0.4f) {
                assertEquals("40% 进度时快照已淡完", 0f, m.snapshotAlpha(), 1e-4);
                faded = true;
                break;
            }
        }
        assertTrue(faded);
    }

    @Test public void neckOnlyWithin14dp() {
        assertEquals(0f, MorphGeometry.neckWaist(20f * D, 28f * D, D), 0f);
        assertEquals(0f, MorphGeometry.neckWaist(0f, 28f * D, D), 0f);
        assertEquals(0f, MorphGeometry.neckWaist(-3f, 28f * D, D), 0f);
        assertEquals("贴上那一刻腰宽 → 岛高 × 0.6", 28f * D * 0.6f, MorphGeometry.neckWaist(0.01f, 28f * D, D), 0.5f);
        assertEquals("一半距离一半腰宽", 28f * D * 0.3f, MorphGeometry.neckWaist(7f * D, 28f * D, D), 0.5f);
    }

    @Test public void mergedShapeStartsAtIslandAndCarriesVelocity() {
        MorphGeometry m = toIsland();
        while (!m.contact() && m.elapsed() < 1f) m.step(1f / 60f);
        assertTrue(m.contact());
        float[] mr = new float[4];
        m.mergedRect(mr, 400f * D);
        assertEquals("合并形状上沿 = 岛上沿", 26f * D, mr[1], 0.01f);
        assertEquals("水平中心 = 岛中心", 540f, (mr[0] + mr[2]) / 2f, 0.5f);
        assertTrue("合并形状至少有岛那么高", mr[3] - mr[1] >= 28f * D - 0.01f);
        m.mergedRect(mr, 60f * D);
        assertTrue("不超出岛窗口下沿", mr[3] <= 60f * D + 0.01f);
        float[] v = m.handoffVelocity();
        assertTrue("接力时还在收窄（宽度速度 < 0）", v[0] < 0f);
        assertTrue("底边还在往上走（高度速度 < 0）", v[1] < 0f);
        assertEquals("接触后不再画颈", 0f, m.neckWaist(), 0f);
    }

    @Test public void unmorphBottomLeadsAndSettlesOnCard() {
        float[] island = {540f - 60f * D, 26f * D, 540f + 60f * D, 26f * D + 120f * D};
        float[] c = card();
        MorphGeometry m = MorphGeometry.fromIsland(D, island, 30f * D, c, 34f * D);
        assertEquals("回程开头卡片内容不显示", 0f, m.snapshotAlpha(), 0f);
        float[] r = new float[4];
        m.step(0.1f);
        m.rect(r);
        float topTravel = (r[1] - island[1]) / (c[1] - island[1]);
        float botTravel = (r[3] - island[3]) / (c[3] - island[3]);
        assertTrue("底边领跑: top " + topTravel + " bottom " + botTravel, botTravel > topTravel);
        for (int i = 0; i < 120 && !m.settled(); i++) m.step(1f / 60f);
        assertTrue("1.2 s 内落定: " + m.elapsed(), m.settled());
        m.rect(r);
        assertEquals(c[1], r[1], 1.5f * D);
        assertEquals(c[3], r[3], 1.5f * D);
        assertEquals(1f, m.snapshotAlpha(), 1e-3);
        assertEquals("回程不判接触", Float.MAX_VALUE, m.gap(), 0f);
    }
}
