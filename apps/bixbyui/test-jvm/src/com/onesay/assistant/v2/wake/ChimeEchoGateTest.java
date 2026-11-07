package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** D1：提示音回声剔除（已知频带陷除 + 帧级能量门）。 */
public class ChimeEchoGateTest {

    private static final int PRE = 4800;   // 300 ms 预录

    @Test public void fftRoundTrip() {
        double[] re = new double[ChimeEchoGate.N], im = new double[ChimeEchoGate.N];
        java.util.Random r = new java.util.Random(1);
        double[] orig = new double[re.length];
        for (int i = 0; i < re.length; i++) orig[i] = re[i] = r.nextGaussian() * 1000;
        ChimeEchoGate.fft(re, im, false);
        ChimeEchoGate.fft(re, im, true);
        for (int i = 0; i < re.length; i++) assertEquals(orig[i], re[i], 1e-6);
    }

    @Test public void emptyMaskAndAllSpeechReconstructsInput() {
        // 掩码为空、门槛极低（每帧都算人声）→ STFT 分析 + 合成应完美重建
        ChimeEchoGate g = new ChimeEchoGate(new boolean[ChimeEchoGate.BINS], 1f, 1e-3f, 0f, 1, 0, 0f);
        short[] x = LaneDAudio.toPcm(LaneDAudio.noise(PRE, 2000, 3));
        ChimeEchoGate.Result res = g.process(x, x.length, 0);
        assertTrue(res.kept());
        // 左端 8 ms 渐强、右端（无上下文时）16 ms 渐弱，中间逐样本重建
        for (int i = 128; i < x.length - 256; i++) assertEquals("i=" + i, x[i], res.pcm[i], 2);
        // 给了右侧上下文：右端不渐弱，输出只到 outLen
        short[] y = LaneDAudio.toPcm(LaneDAudio.noise(PRE + 1600, 2000, 4));
        ChimeEchoGate.Result r2 = g.process(y, y.length, PRE, 0);
        assertEquals(PRE, r2.pcm.length);
        for (int i = 128; i < PRE; i++) assertEquals("ctx i=" + i, y[i], r2.pcm[i], 2);
    }

    @Test public void startToneMaskMatchesFixture() throws Exception {
        boolean[] fromWave = ChimeEchoGate.maskFromChime(LaneDAudio.startTone(), 0.97, 2);
        assertEquals("start_tone.ogg 换了？请同步 START_TONE_LO_HZ/HI_HZ",
                ChimeEchoGate.describe(ChimeEchoGate.startToneMask()), ChimeEchoGate.describe(fromWave));
        assertEquals("500–1063Hz", ChimeEchoGate.describe(ChimeEchoGate.startToneMask()));
    }

    @Test public void shortChimeMaskCoversBothTones() {
        boolean[] m = ChimeEchoGate.shortChimeMask();
        // ★ device-1102 方案 A：C6(1046.5) → E6(1318.5)
        assertTrue(m[(int) Math.round(Earcon.C6 * ChimeEchoGate.N / 16000)]);
        assertTrue(m[(int) Math.round(Earcon.E6 * ChimeEchoGate.N / 16000)]);
        assertFalse("不挖人声基频区", m[(int) Math.round(700.0 * ChimeEchoGate.N / 16000)]);
        assertFalse(m[(int) Math.round(2000.0 * ChimeEchoGate.N / 16000)]);
    }

    @Test public void silenceAndNoiseAreDropped() {
        ChimeEchoGate g = ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask());
        assertFalse(g.process(new short[PRE], PRE, 0).kept());
        assertFalse(g.process(new short[0], 0, 0).kept());
        assertFalse(g.process(null, 100, 0).kept());
        short[] quiet = LaneDAudio.toPcm(LaneDAudio.noise(PRE, 60, 5));
        assertFalse(g.process(quiet, quiet.length, 75).kept());
        // 白天底噪 rms≈700（R14.1 实测），底噪估计跟上时不误判
        short[] day = LaneDAudio.toPcm(LaneDAudio.noise(PRE, 700, 6));
        ChimeEchoGate.Result r = g.process(day, day.length, 700 * 1.25f);
        assertFalse(r.toString(), r.kept());
    }

    /** 只有提示音（各种电平 / 失真 / 混响 / 起播延迟 / 底噪）→ 一律整段丢弃，人声帧 ≤ 2。 */
    @Test public void chimeOnlyNeverPasses() throws Exception {
        short[][] chimes = {LaneDAudio.startTone(), LaneDAudio.shortChime16k()};
        boolean[][] masks = {ChimeEchoGate.startToneMask(), ChimeEchoGate.shortChimeMask()};
        int cases = 0, maxFrames = 0;
        for (int c = 0; c < 2; c++) {
            ChimeEchoGate g = ChimeEchoGate.withDefaults(masks[c]);
            for (double lvl : new double[]{1400, 2800, 5000}) {
                for (double drive : new double[]{0.5, 3}) {
                    for (double rt : new double[]{0.1, 0.3}) {
                        for (double nz : new double[]{60, 700}) {
                            for (int delayMs : new int[]{0, 60, 150}) {
                                double[] x = LaneDAudio.noise(PRE, nz, 11 + cases);
                                LaneDAudio.mixInto(x, delayMs * 16, LaneDAudio.echo(chimes[c], lvl, drive, rt, cases), 1);
                                short[] pcm = LaneDAudio.toPcm(x);
                                ChimeEchoGate.Result r = g.process(pcm, pcm.length, (float) (nz * 1.25));
                                assertFalse("c=" + c + " lvl=" + lvl + " drive=" + drive + " rt=" + rt + " nz=" + nz + " d=" + delayMs + " → " + r + "\n" + r.trace(g.leak()), r.kept());
                                maxFrames = Math.max(maxFrames, r.longestRun);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(144, cases);
        assertTrue("提示音单独出现时最长连续人声帧应 ≤ 2，实际 " + maxFrames, maxFrames <= 2);
    }

    /** 提示音 + 抢话：保留下来，而且掩码外的语音能量基本都在（首字保住）。 */
    @Test public void chimePlusSpeechKeepsSpeech() throws Exception {
        boolean[] mask = ChimeEchoGate.startToneMask();
        ChimeEchoGate g = ChimeEchoGate.withDefaults(mask);
        double[] echo = LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, 7);
        for (String w : new String[]{"joke.wav", "weather.wav", "mail.wav"}) {
            short[] sp = LaneDAudio.wav(w);
            int lead = 250 * 16;   // 素材开头约 250 ms 静音
            for (int onsetMs : new int[]{50, 150}) {
                double[] clean = new double[PRE];
                LaneDAudio.mixInto(clean, onsetMs * 16, sp, lead, 0.5);
                double[] x = LaneDAudio.noise(PRE, 60, 2);
                LaneDAudio.mixInto(x, 60 * 16, echo, 1);
                for (int i = 0; i < PRE; i++) x[i] += clean[i];
                short[] pcm = LaneDAudio.toPcm(x);
                ChimeEchoGate.Result r = g.process(pcm, pcm.length, 75);
                assertTrue(w + " onset=" + onsetMs + " " + r, r.kept());
                assertTrue(r.longestRun >= ChimeEchoGate.DEF_MIN_FRAMES);
                double kept = LaneDAudio.outOfMaskRms(r.pcm, mask);
                double ref = LaneDAudio.outOfMaskRms(LaneDAudio.toPcm(clean), mask);
                assertTrue(w + " onset=" + onsetMs + " 掩码外语音能量保留 " + kept + " / " + ref, kept > 0.8 * ref && kept < 1.3 * ref);
            }
        }
    }

    @Test public void keptOutputHasChimeBandRemoved() throws Exception {
        boolean[] mask = ChimeEchoGate.startToneMask();
        ChimeEchoGate g = ChimeEchoGate.withDefaults(mask);
        double[] x = LaneDAudio.noise(PRE, 60, 2);
        LaneDAudio.mixInto(x, 0, LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, 7), 1);
        LaneDAudio.mixInto(x, 50 * 16, LaneDAudio.wav("joke.wav"), 250 * 16, 0.5);
        short[] pcm = LaneDAudio.toPcm(x);
        ChimeEchoGate.Result r = g.process(pcm, pcm.length, 75);
        assertTrue(r.kept());
        boolean[] inv = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) inv[i] = !mask[i];
        // outOfMaskRms(x, inv) = 掩码「内」的能量
        double before = LaneDAudio.outOfMaskRms(pcm, inv), after = LaneDAudio.outOfMaskRms(r.pcm, inv);
        assertTrue("提示音频带应被压下去 " + before + " → " + after, after < 0.1 * before);
    }

    @Test public void tooFewFramesAreDropped() {
        ChimeEchoGate g = new ChimeEchoGate(ChimeEchoGate.startToneMask(), 1.8f, 220f, 0.35f, 3, 2, 0f);
        // 一段 2 帧长（~40 ms）的宽带爆音：不足 3 帧，整段丢
        double[] x = new double[PRE];
        LaneDAudio.mixInto(x, 2000, LaneDAudio.noise(120, 3000, 9), 1);
        short[] pcm = LaneDAudio.toPcm(x);
        ChimeEchoGate.Result r = g.process(pcm, pcm.length, 0);
        assertFalse(r.toString(), r.kept());
        assertTrue(r.toString(), r.speechFrames > 0 && r.speechFrames < 3);
        // 两次孤立的爆音（各 ≤ 2 帧，中间隔开）：总数够 3 但不连续，也丢
        double[] y = new double[PRE];
        LaneDAudio.mixInto(y, 1000, LaneDAudio.noise(120, 3000, 9), 1);
        LaneDAudio.mixInto(y, 3500, LaneDAudio.noise(120, 3000, 10), 1);
        short[] pcm2 = LaneDAudio.toPcm(y);
        ChimeEchoGate.Result r2 = g.process(pcm2, pcm2.length, 0);
        assertFalse(r2.toString() + " " + r2.trace(0.35f), r2.kept());
        assertTrue(r2.toString(), r2.speechFrames >= 3);
    }

    /** 预录里只有提示音、人在预录结束后才开口（话只在右侧上下文里）→ 预录不接：那段话本来就在 R14 的帧里。 */
    @Test public void speechOnlyInContextDoesNotKeepPreRoll() throws Exception {
        ChimeEchoGate g = ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask());
        for (int startAfterMs : new int[]{10, 40, 80}) {
            double[] x = LaneDAudio.noise(PRE + 1600, 60, 8);
            LaneDAudio.mixInto(x, 60 * 16, LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, 3), 1);
            LaneDAudio.mixInto(x, PRE + startAfterMs * 16, LaneDAudio.wav("joke.wav"), 250 * 16, 0.5);
            short[] pcm = LaneDAudio.toPcm(x);
            ChimeEchoGate.Result r = g.process(pcm, pcm.length, PRE, 75);
            assertFalse("开口在预录后 " + startAfterMs + "ms：" + r + " " + r.trace(), r.kept());
        }
    }

    /** 抢话的字跨过预录边界：预录里只有开头 ~60 ms，靠右侧上下文把这段连续人声接起来。 */
    @Test public void speechCrossingBoundaryIsKeptWithContext() throws Exception {
        ChimeEchoGate g = ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask());
        double[] x = LaneDAudio.noise(PRE + 1600, 60, 8);
        LaneDAudio.mixInto(x, 60 * 16, LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, 3), 1);
        LaneDAudio.mixInto(x, PRE - 60 * 16, LaneDAudio.wav("weather.wav"), 290 * 16, 0.5);
        short[] pcm = LaneDAudio.toPcm(x);
        ChimeEchoGate.Result withCtx = g.process(pcm, pcm.length, PRE, 75);
        assertTrue(withCtx + " " + withCtx.trace(), withCtx.kept());
        assertEquals(PRE, withCtx.pcm.length);
    }
}
