package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.onesay.assistant.v2.ui.shape.Squircle;

/** [R13] RMS 映射 / 包络；连续圆角参数。 */
public class VoiceLevelAndSquircleTest {

    @Test public void rmsNormalisation() {
        float floor = 200f;
        assertEquals("底噪 → 0", 0f, VoiceLevel.norm(200f, floor), 1e-4);
        double loud = 32768.0 * Math.pow(10, -18.0 / 20.0);
        assertEquals("−18 dBFS → 1", 1f, VoiceLevel.norm((float) loud, floor), 1e-3);
        assertEquals("更响也封顶 1", 1f, VoiceLevel.norm(30000f, floor), 0f);
        float mid = VoiceLevel.norm(1500f, floor);
        assertTrue(mid > 0.2f && mid < 0.8f);
        assertEquals("极端底噪不除零", 0f, VoiceLevel.norm(1f, 30000f), 0f);
    }

    @Test public void envelopeAttackFastReleaseSlow() {
        VoiceLevel.Envelope e = new VoiceLevel.Envelope();
        e.step(1f, 0.030f);
        assertTrue("30 ms 起头到 ~63%", e.value > 0.6f && e.value < 0.66f);
        e.value = 1f;
        e.step(0f, 0.030f);
        assertTrue("停顿慢慢落", e.value > 0.85f);
    }

    @Test public void freshness() {
        VoiceLevel.publish(800f, 200f, 10_000L);
        assertTrue(VoiceLevel.fresh(10_200L));
        assertTrue("超过 300 ms 没读麦 = 不伪造音量", !VoiceLevel.fresh(10_400L));
    }

    @Test public void squircleExponentAndContinuity() {
        assertEquals("n 由 k=1.53 解出", 3.26, Squircle.solveN(1.53), 0.005);
        float r = 34f * 2.625f;
        float[] p = Squircle.points(0f, 0f, 1000f, 600f, r);
        assertEquals(4 * (Squircle.SEG + 1) * 2, p.length);
        // 45° 处与同半径普通圆角重合（≤ 1px）
        float e = Squircle.cornerExtent(1000f, 600f, r);
        float th = (float) (Math.PI / 4);
        float dx = (float) (e * Math.pow(Math.cos(th), 2.0 / Squircle.N));
        float sqX = e - dx;   // 左上角 45° 点到左边的距离
        float circX = r - (float) (r * Math.cos(th));
        assertEquals("45° 重合", circX, sqX, 1.0f);
        // 起止点落在直边上（曲率连续的前提）
        assertEquals(0f, p[0], 0.01f);
        assertEquals(e, p[1], 0.01f);
        assertEquals("小卡片时角区钳到半高", 40f, Squircle.cornerExtent(300f, 80f, r), 0.001f);
    }
}
