package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ bug D-1（10-23）回归：「最长连续人声帧」只在预录窗内数；跨界放行要求窗外续段自己够 minFrames 帧。
 *
 * <p>真机现象：短提示音 + 只按键不说话，10 次里 2 次「接上预录 …（最长连续 3）」—— 窗内末尾 2 帧瞬态越线，
 * 旧逻辑把右侧上下文（第一块 R14 帧）里紧接的 1 帧也算进同一段，凑成 3 帧放行。
 *
 * <p>帧对齐：预录 PRE = 4800 样本 → 判定帧 0–18（帧 k 中心 = k·256），上下文帧从 19 开始。
 * 用宽带噪声爆音（掩码外能量远大于泄漏比）精确控制哪些帧越线。
 */
public class ChimeEchoGateBoundaryTest {

    private static final int PRE = 4800;
    private static final int CTX = 1600;

    /** ctxMinFrames = 0：只测 D-1 的窗内 / 跨界计数本身（LB-1 的「窗外续段」由 ChimeEchoGateFollowUpTest 单独测）。 */
    private static ChimeEchoGate gate(int minFrames) {
        return new ChimeEchoGate(ChimeEchoGate.startToneMask(), 1.8f, 220f, 0.35f, minFrames, 2, 0f, 0);
    }

    /** 底噪 + [from, to) 一段 3000 rms 宽带爆音，整段 PRE + CTX，按 outLen = PRE 处理。 */
    private static ChimeEchoGate.Result burst(ChimeEchoGate g, int from, int to) {
        double[] x = LaneDAudio.noise(PRE + CTX, 60, 21);
        LaneDAudio.mixInto(x, from, LaneDAudio.noise(to - from, 3000, 22), 1);
        short[] pcm = LaneDAudio.toPcm(x);
        return g.process(pcm, pcm.length, PRE, 75);
    }

    /** D-1 原样：窗内末尾 2 帧 + 窗外 1 帧。旧逻辑数成 3 帧放行；现在窗内只有 2 帧、窗外续段 1 帧 → 丢弃（3、4 帧门限都丢）。 */
    @Test public void windowTwoPlusContextOneIsDropped() {
        for (int mf : new int[]{3, 4}) {
            ChimeEchoGate.Result r = burst(gate(mf), 4352, 4864);
            assertEquals(r.toString(), 2, r.longestRun);
            assertEquals(r.toString(), 2, r.crossWin);
            assertEquals(r.toString(), 1, r.crossCtx);
            assertFalse("mf=" + mf + " " + r, r.kept());
            assertFalse(r.viaCross);
            assertTrue("日志要能看出跨界几帧：" + r, r.toString().contains("跨界 2+1"));
        }
    }

    /** 窗内 3 帧 + 窗外 1 帧：门限 3 时靠窗内 3 帧放行（不是跨界）；默认门限 4 时丢弃 —— 窗外那 1 帧不再帮忙凑数。 */
    @Test public void contextFramesNeverCountTowardsWindowRun() {
        ChimeEchoGate.Result r3 = burst(gate(3), 4096, 4864);
        assertEquals(3, r3.longestRun);
        assertEquals(1, r3.crossCtx);
        assertTrue(r3.toString(), r3.kept());
        assertFalse("窗内已够，不是靠跨界", r3.viaCross);
        ChimeEchoGate.Result r4 = burst(gate(4), 4096, 4864);
        assertFalse(r4.toString(), r4.kept());
        // 同样 4 帧整段在窗内（不碰边界）：门限 4 放行
        ChimeEchoGate.Result in4 = burst(gate(4), 3584, 4352);
        assertEquals(4, in4.longestRun);
        assertEquals(0, in4.crossWin);
        assertTrue(in4.toString(), in4.kept());
        // ★ LB-1：同样的形状（窗内 4 帧、窗外 0 帧）在默认回声门（要求窗外续段）下丢弃 —— 这正是 LB-1 的真机误接
        ChimeEchoGate def = new ChimeEchoGate(ChimeEchoGate.startToneMask(), 1.8f, 220f, 0.35f, 4, 2, 0f);
        assertFalse(burst(def, 3584, 4352).kept());
    }

    /** D1-5 跨界保留：窗末 1 帧 + 窗外连续 4 帧（字跨过边界继续说）→ 放行，且输出里保住了窗末那段语音。 */
    @Test public void crossingWordWithSustainedContextIsKept() {
        for (int mf : new int[]{3, 4}) {
            ChimeEchoGate.Result r = burst(gate(mf), 4608, 5632);
            assertEquals(1, r.crossWin);
            assertEquals(4, r.crossCtx);
            assertTrue("mf=" + mf + " " + r, r.kept());
            assertTrue(r.viaCross);
            assertEquals(PRE, r.pcm.length);
            double tail = 0;
            for (int i = PRE - 192; i < PRE; i++) tail += (double) r.pcm[i] * r.pcm[i];
            assertTrue("窗末的字头要保留，rms=" + Math.sqrt(tail / 192), Math.sqrt(tail / 192) > 500);
        }
        // 窗外续段 3 帧：门限 3 放行，默认门限 4 不放行（窗外续段自己也要够 minFrames）
        assertTrue(burst(gate(3), 4608, 5376).kept());
        ChimeEchoGate.Result r4 = burst(gate(4), 4608, 5376);
        assertEquals(3, r4.crossCtx);
        assertFalse(r4.toString(), r4.kept());
    }

    /** 默认门限 = 4（D-1 顺带加余量），WakeTuning 默认值跟着走。 */
    @Test public void defaultMinFramesIsFour() {
        assertEquals(4, ChimeEchoGate.DEF_MIN_FRAMES);
        assertEquals(4, ChimeEchoGate.withDefaults(ChimeEchoGate.shortChimeMask()).minFrames());
        assertEquals(ChimeEchoGate.DEF_MIN_FRAMES, WakeTuning.DEF_PREROLL_MIN_FRAMES);
    }

    /**
     * 只有提示音、带右侧上下文（真机的调用方式：KeyPreRoll.flush 总会把第一块 R14 帧当上下文传进来），
     * 起播延迟让提示音尾巴跨过预录边界：144 种组合全部丢弃。旧逻辑 + 门限 3 时原版提示音 5000 电平有 1 例误接。
     */
    @Test public void chimeOnlyWithContextAcrossBoundaryNeverPasses() throws Exception {
        short[][] chimes = {LaneDAudio.startTone(), LaneDAudio.shortChime16k()};
        boolean[][] masks = {ChimeEchoGate.startToneMask(), ChimeEchoGate.shortChimeMask()};
        int cases = 0, maxWin = 0;
        for (int c = 0; c < 2; c++) {
            ChimeEchoGate g = ChimeEchoGate.withDefaults(masks[c]);
            for (double lvl : new double[]{1400, 2800, 5000}) {
                for (double drive : new double[]{0.5, 3}) {
                    for (double rt : new double[]{0.1, 0.3}) {
                        for (double nz : new double[]{60, 700}) {
                            for (int delayMs : new int[]{60, 150, 250}) {
                                double[] x = LaneDAudio.noise(PRE + CTX, nz, 11 + cases);
                                LaneDAudio.mixInto(x, delayMs * 16, LaneDAudio.echo(chimes[c], lvl, drive, rt, cases), 1);
                                short[] pcm = LaneDAudio.toPcm(x);
                                ChimeEchoGate.Result r = g.process(pcm, pcm.length, PRE, (float) (nz * 1.25));
                                assertFalse("c=" + c + " lvl=" + lvl + " drive=" + drive + " rt=" + rt + " nz=" + nz + " d=" + delayMs + " → " + r + "\n" + r.trace(), r.kept());
                                maxWin = Math.max(maxWin, r.longestRun);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(144, cases);
        assertTrue("窗内最长连续应 < 默认门限 4，实际 " + maxWin, maxWin < ChimeEchoGate.DEF_MIN_FRAMES);
    }
}
