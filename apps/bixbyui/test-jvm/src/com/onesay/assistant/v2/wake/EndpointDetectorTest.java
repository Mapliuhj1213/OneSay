package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** P1-9：自适应断句 + 短提示音合成。 */
public class EndpointDetectorTest {

    @Test public void fixedModeEqualsOldBehaviour() {
        EndpointDetector d = EndpointDetector.fixed(1600);
        assertFalse(d.isAdaptive());
        assertEquals(1600, d.trailingSilenceMs(1000, 1500));
        assertEquals(1600, d.trailingSilenceMs(1000, 9000));
        assertTrue("旧逻辑不做最短语句保护", d.utteranceLongEnough(1000, 1000, 1, 4));
        // 旧判据：说过话 + 人声帧够 + 静音 > 1600
        assertFalse(d.shouldEndpoint(3100, 1000, 1500, 4, 4));
        assertTrue(d.shouldEndpoint(3101, 1000, 1500, 4, 4));
        assertFalse("人声帧不足不断句", d.shouldEndpoint(9000, 1000, 1500, 3, 4));
        assertFalse("没听到过人声不断句", d.shouldEndpoint(9000, 0, 0, 10, 4));
    }

    static EndpointDetector adaptive() {
        return new EndpointDetector(true, 1600, 700, 900, 1500, 300);
    }

    @Test public void shortUtteranceEndsAfter700ms() {
        EndpointDetector d = adaptive();
        assertEquals(700, d.trailingSilenceMs(1000, 2000));     // 说了 1.0s
        assertFalse(d.shouldEndpoint(2700, 1000, 2000, 8, 4));
        assertTrue(d.shouldEndpoint(2701, 1000, 2000, 8, 4));
    }

    @Test public void longUtteranceKeepsMorePatience() {
        EndpointDetector d = adaptive();
        assertEquals(900, d.trailingSilenceMs(1000, 4000));     // 说了 3.0s
        assertFalse(d.shouldEndpoint(4800, 1000, 4000, 20, 4));
        assertTrue(d.shouldEndpoint(4901, 1000, 4000, 20, 4));
    }

    @Test public void minUtteranceGuardBlocksBlips() {
        EndpointDetector d = adaptive();
        // 跨度 100ms、只有 4 帧（一声咳嗽）→ 不断句
        assertFalse(d.utteranceLongEnough(1000, 1100, 4, 4));
        assertFalse(d.shouldEndpoint(5000, 1000, 1100, 4, 4));
        // 跨度虽短但人声帧充足（短促真实指令）→ 放行
        assertTrue(d.utteranceLongEnough(1000, 1100, 8, 4));
        assertTrue(d.utteranceLongEnough(1000, 1400, 4, 4));
    }

    @Test public void thresholdsClampedToSafeRange() {
        EndpointDetector tooFast = new EndpointDetector(true, 1600, 200, 300, 1500, 300);
        assertEquals("下限 600ms", EndpointDetector.FLOOR_MS, tooFast.trailingSilenceMs(1000, 1500));
        assertEquals(EndpointDetector.FLOOR_MS, tooFast.trailingSilenceMs(1000, 9000));
        EndpointDetector tooSlow = new EndpointDetector(true, 1200, 2000, 3000, 1500, 300);
        assertEquals("自适应不会比旧固定值更慢", 1200, tooSlow.trailingSilenceMs(1000, 1500));
        assertEquals(1200, tooSlow.trailingSilenceMs(1000, 9000));
        EndpointDetector inverted = new EndpointDetector(true, 1600, 900, 700, 1500, 300);
        assertTrue("长句阈值不小于短句", inverted.trailingSilenceMs(1000, 9000) >= inverted.trailingSilenceMs(1000, 1200));
    }

    @Test public void earconIsShortClickFreeAndBounded() {
        short[] pcm = Earcon.wakeChime();
        int ms = Earcon.durationMs(pcm, Earcon.SAMPLE_RATE);
        assertTrue("150–250ms 短提示音: " + ms, ms >= 150 && ms <= 250);
        int peak = 0;
        for (short v : pcm) peak = Math.max(peak, Math.abs(v));
        assertTrue("有声音", peak > 3000);
        assertTrue("不削波", peak <= (int) (0.36 * Short.MAX_VALUE));
        assertTrue("首尾淡入淡出（无爆音）", Math.abs(pcm[0]) < 200 && Math.abs(pcm[pcm.length - 1]) < 200);
        int split = (int) Math.round(pcm.length * Earcon.FIRST_SHARE);   // ★ device-1102 方案 A：交界在 40% 处
        assertTrue("两段交界也淡化", Math.abs(pcm[split]) < 400 && Math.abs(pcm[split - 1]) < 400);
    }
}
