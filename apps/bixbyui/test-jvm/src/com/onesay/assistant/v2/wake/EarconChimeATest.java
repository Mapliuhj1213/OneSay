package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ device-1102 唤醒音方案 A：C6 → E6、240 ms、渐弱；旧音 160 ms 880 → 1320 保留可对比。 */
public class EarconChimeATest {

    static double goertzel(short[] x, int from, int to, int sr, double f) {
        double w = 2 * Math.PI * f / sr, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = from; i < to; i++) { double s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0; }
        return Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (to - from);
    }

    static double rms(short[] x, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) e += (double) x[i] * x[i];
        return Math.sqrt(e / Math.max(1, to - from));
    }

    @Test public void defaultIsPlanA() {
        short[] a = Earcon.wakeChime();
        assertEquals(240, Earcon.durationMs(a, Earcon.SAMPLE_RATE));
        assertEquals(1046.50, Earcon.C6, 0.01);
        assertEquals(1318.51, Earcon.E6, 0.01);
        assertArrayEquals(Earcon.chimeA(Earcon.SAMPLE_RATE), a);
    }

    @Test public void firstNoteIsC6SecondIsE6() {
        int sr = Earcon.SAMPLE_RATE;
        short[] a = Earcon.wakeChime();
        int split = (int) Math.round(a.length * Earcon.FIRST_SHARE);
        assertTrue(goertzel(a, 0, split, sr, Earcon.C6) > 5 * goertzel(a, 0, split, sr, Earcon.E6));
        assertTrue(goertzel(a, split, a.length, sr, Earcon.E6) > 5 * goertzel(a, split, a.length, sr, Earcon.C6));
        assertTrue("不再有 880 Hz", goertzel(a, 0, a.length, sr, 880.0) < 0.2 * goertzel(a, 0, split, sr, Earcon.C6));
    }

    @Test public void decaysAndEndsSilent() {
        short[] a = Earcon.wakeChime();
        int sr = Earcon.SAMPLE_RATE, split = (int) Math.round(a.length * Earcon.FIRST_SHARE);
        int ms10 = sr / 100;
        double e2Head = rms(a, split + ms10, split + 3 * ms10);
        double e2Tail = rms(a, a.length - 4 * ms10, a.length - 2 * ms10);
        assertTrue("第二音渐弱: " + e2Head + " → " + e2Tail, e2Tail < 0.35 * e2Head);
        assertTrue("第二音起音不低于第一音尾", e2Head > 0.5 * rms(a, split - 3 * ms10, split - ms10));
        assertEquals(0, a[a.length - 1], 30);
        assertEquals(0, a[0], 30);
        int peak = 0;
        for (short v : a) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak > 3000 && peak <= (int) (0.36 * Short.MAX_VALUE));
    }

    @Test public void legacyKeptForComparison() {
        short[] l = Earcon.legacyChime(Earcon.SAMPLE_RATE);
        assertEquals(160, Earcon.durationMs(l, Earcon.SAMPLE_RATE));
        assertArrayEquals(Earcon.twoTone(Earcon.SAMPLE_RATE, 160, 880.0, 1320.0, 0.35), l);
    }

    @Test public void echoMaskFollowsPlanA() {
        boolean[] m = ChimeEchoGate.shortChimeMask();
        assertTrue(m[(int) Math.round(Earcon.C6 * ChimeEchoGate.N / 16000)]);
        assertTrue(m[(int) Math.round(Earcon.E6 * ChimeEchoGate.N / 16000)]);
    }
}
