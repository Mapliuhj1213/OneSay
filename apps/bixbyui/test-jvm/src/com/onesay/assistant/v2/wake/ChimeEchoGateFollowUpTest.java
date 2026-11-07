package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ bug LB-1（10-23）回归：「窗内够帧」放行还要求窗外（第一块 R14 帧）有人声续段。
 *
 * <p>真机现象：原版提示音 + 只按键不说话 + 安静环境，12 次里 1 次「接上预录 290ms：保留 人声帧 4/19（最长连续 4）」——
 * start_tone 起音的宽带瞬态在窗内后段连出 4 帧，没有跨界、窗外 0 帧。上一站另有 3 次「窗末 3 帧 + 窗外 0 帧」。
 * 门限 4 只差一帧，门限 5 又会让 joke@140–150ms 首字回归，所以改从特征上区分：瞬态在窗外是 0 帧，真人抢话会接着说。
 *
 * <p>帧对齐同 {@link ChimeEchoGateBoundaryTest}：预录 PRE = 4800 样本 → 判定帧 0–18（帧 k 中心 = k·256），上下文帧 19–25。
 * 一段 [from, to) 的爆音只要 to ≤ 4608，就碰不到帧 19（覆盖 [4608, 5120)），窗外是 0 帧。
 */
public class ChimeEchoGateFollowUpTest {

    private static final int PRE = 4800;
    private static final int CTX = 1600;

    private static ChimeEchoGate defGate() {
        return ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask());
    }

    private static ChimeEchoGate oldGate() {   // D-1 的判定（不要求窗外续段）
        return new ChimeEchoGate(ChimeEchoGate.startToneMask(), 1.8f, 220f, 0.35f, 4, 2, 0f, 0);
    }

    /** 底噪 60 + 若干段 3000 rms 宽带爆音（{from, to} 成对），整段 PRE + CTX。 */
    private static short[] bursts(int... fromTo) {
        double[] x = LaneDAudio.noise(PRE + CTX, 60, 21);
        for (int i = 0; i + 1 < fromTo.length; i += 2) {
            LaneDAudio.mixInto(x, fromTo[i], LaneDAudio.noise(fromTo[i + 1] - fromTo[i], 3000, 22 + i), 1);
        }
        return LaneDAudio.toPcm(x);
    }

    /** LB-1 原样：窗内 4 帧、不贴窗末、窗外 0 帧。D-1 判定放行（误接），现在丢弃，日志标「无续段」。 */
    @Test public void lb1Shape_fourFramesInWindow_noFollowUp_dropped() {
        short[] pcm = bursts(3584, 4352);
        ChimeEchoGate.Result old = oldGate().process(pcm, pcm.length, PRE, 75);
        assertEquals(4, old.longestRun);
        assertTrue("D-1 判定下这就是 LB-1 的误接：" + old, old.kept());
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertEquals(4, r.longestRun);
        assertEquals(0, r.crossWin);
        assertEquals(0, r.follow);
        assertTrue(r.noFollowUp);
        assertFalse(r.toString(), r.kept());
        assertTrue("日志要能看出窗外没有续段：" + r, r.toString().contains("窗外续 0 无续段"));
        assertTrue("逐帧串要带上下文帧（| 分隔）：" + r.trace(), r.trace().contains(" | "));
    }

    /**
     * 验收合成组：窗末 3–5 帧宽带瞬态、窗外 0 帧。段长 3/4/5 帧 × 段尾离窗末 0/1/2 帧 × 电平 1500/3000/6000 ×
     * 有无提示音回声垫底 × 平顶 / 起音衰减两种包络 × 2 个随机种子 = 216 例。窗外 0 帧的（瞬态窗外 0 帧、垫底回声也没在窗外越线）
     * 一律丢弃，其中窗内 ≥ 4 帧的在 D-1 判定下会误接。垫底回声自己在窗外越线 1 帧的那几例（不再是「窗外 0 帧」）会走响度那条放行：
     * 这里门值只有 220（底噪 60），瞬态峰值是门的 3–18 倍；真机提示音瞬态只有门的 1.3–1.9 倍，见 deviceLevel… 两个用例。
     */
    @Test public void windowEndBroadbandTransient3to5_noContext_allDropped() throws Exception {
        ChimeEchoGate g = defGate(), old = oldGate();
        int n = 0, oldPass = 0, maxRun = 0, strict = 0, stray = 0, strayKept = 0;
        int[] runHist = new int[8];
        for (int frames = 3; frames <= 5; frames++)
            for (int endGap = 0; endGap <= 2; endGap++)
                for (double amp : new double[]{1500, 3000, 6000})
                    for (int withChime = 0; withChime < 2; withChime++)
                        for (int decay = 0; decay < 2; decay++)
                            for (int seed = 0; seed < 2; seed++) {
                                int end = 4608 - endGap * 256;
                                int from = end - (frames - 1) * 256;
                                double[] x = LaneDAudio.noise(PRE + CTX, 60, 31 + n);
                                if (withChime == 1) LaneDAudio.mixInto(x, 150 * 16, LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, n), 1);
                                double[] b = LaneDAudio.noise(end - from, amp, 77 + n);
                                if (decay == 1) for (int i = 0; i < b.length; i++) b[i] *= Math.exp(-1.5 * i / b.length);
                                LaneDAudio.mixInto(x, from, b, 1);
                                short[] pcm = LaneDAudio.toPcm(x);
                                ChimeEchoGate.Result r = g.process(pcm, pcm.length, PRE, 75);
                                String tag = "frames=" + frames + " gap=" + endGap + " amp=" + amp + " chime=" + withChime + " decay=" + decay + " → " + r + "\n" + r.trace();
                                // 瞬态本身窗外 0 帧（段尾 ≤ 4608，碰不到帧 19）；垫底的提示音回声偶尔自己在窗外越线 1 帧，那是回声不是瞬态
                                if (withChime == 0) assertEquals("合成组要求窗外 0 帧：" + tag, 0, r.ctxRun);
                                if (r.ctxRun == 0) { assertFalse(tag, r.kept()); strict++; }
                                else { assertTrue("窗外有越线帧的只会是回声垫底那组：" + tag, withChime == 1); stray++; if (r.kept()) strayKept++; }
                                if (old.process(pcm, pcm.length, PRE, 75).kept()) oldPass++;
                                runHist[Math.min(7, r.longestRun)]++;
                                maxRun = Math.max(maxRun, r.longestRun);
                                n++;
                            }
        assertEquals(216, n);
        System.out.println("[LB-1 合成组] 窗外 0 帧 " + strict + " 例全部丢弃；回声垫底在窗外越线的 " + stray + " 例里放行 " + strayKept + " 例");
        assertTrue("严格组（窗外 0 帧）要占绝大多数，实际 " + strict, strict >= 200);
        assertTrue(stray <= 8);
        assertTrue("合成组里要有窗内 5 帧的例子，实际最长 " + maxRun + " 分布 " + java.util.Arrays.toString(runHist), maxRun >= 5);
        assertTrue("D-1 判定下这一组有误接（证明用例有效），实际 " + oldPass, oldPass > 0);
    }

    /** 贴窗末的短段溢出到窗外 1 帧（窗内 4 + 窗外 1 = 5 ≤ BURST_MAX_FRAMES）：溢出帧不算续段 → 丢弃。 */
    @Test public void shortBurstSpillDoesNotCountAsFollowUp() {
        short[] pcm = bursts(3840, 4864);
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertEquals(r.toString(), 4, r.longestRun);
        assertEquals(1, r.crossCtx);
        assertEquals(0, r.follow);
        assertFalse(r.toString(), r.kept());
    }

    /** 真机电平：底噪 812 → 门 1461（与 10-23 上机一致）。 */
    private static final float DEV_FLOOR = 812f;

    /** 窗内 4 帧 + 窗外 [ctxFrom, ctxFrom+128) 一下短促的响声。 */
    private static ChimeEchoGate.Result deviceLevel(double winRms, int ctxFrom, double ctxAmp) {
        double[] x = LaneDAudio.noise(PRE + CTX, 60, 21);
        LaneDAudio.mixInto(x, 3584, LaneDAudio.noise(768, winRms, 22), 1);
        if (ctxFrom > 0) LaneDAudio.mixInto(x, ctxFrom, LaneDAudio.noise(128, ctxAmp, 23), 1);
        short[] pcm = LaneDAudio.toPcm(x);
        return defGate().process(pcm, pcm.length, PRE, DEV_FLOOR);
    }

    /**
     * 真机电平的瞬态（峰值约 1.4 倍门，真机提示音瞬态 1.3–1.9 倍）+ 窗外孤立 1 帧（比如提示音别的音符）：续段 1 帧、又不够响 → 丢弃。
     * 同样形状但字头够响（≥ 2.2 倍门，真机抢话 2.5–9 倍）→ 放行：这就是「讲 / 个」那种字间有停顿的抢话。
     */
    @Test public void deviceLevel_quietTransientPlusIsolatedContextFrame_dropped_loudSpeechKept() {
        ChimeEchoGate.Result q = deviceLevel(3600, 5568, 6000);   // 居中在帧 22（中心 5632），只让这一帧越线
        assertEquals(q.toString(), 4, q.longestRun);
        assertEquals(q.toString(), 1, q.follow);
        assertTrue(q.toString(), q.loud > 1.0f && q.loud < ChimeEchoGate.LOUD_RATIO);
        assertFalse(q.toString(), q.kept());
        assertTrue(q.noFollowUp);
        ChimeEchoGate.Result l = deviceLevel(7000, 5568, 6000);
        assertEquals(l.toString(), 1, l.follow);
        assertTrue(l.toString(), l.loud >= ChimeEchoGate.LOUD_RATIO);
        assertTrue(l.toString(), l.kept());
        // 同一个响的字头、窗外一帧都没有 → 仍然丢弃（LB-1 那条不靠响度单独放行）
        ChimeEchoGate.Result l0 = deviceLevel(7000, 0, 0);
        assertEquals(0, l0.follow);
        assertFalse(l0.toString(), l0.kept());
    }

    /** 短字头：窗内只有 3 帧（字刚开头窗就结束），窗外接着说 ≥ 3 帧 → 放行；窗外只有 2 帧 → 丢弃（真机只按键时窗内 3 帧的几次窗外都是 0 帧）。 */
    @Test public void shortHeadWithStrongFollowUpKept() {
        short[] pcm = bursts(3584, 4096, 5376, 6400);
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertEquals(r.toString(), 3, r.longestRun);
        assertEquals(0, r.crossWin);
        assertTrue(r.toString(), r.follow >= 3);
        assertTrue(r.toString(), r.kept());
        short[] pcm2 = bursts(3584, 4096, 5632, 5888);
        ChimeEchoGate.Result r2 = defGate().process(pcm2, pcm2.length, PRE, 75);
        assertEquals(3, r2.longestRun);
        assertTrue(r2.toString(), r2.follow >= 1 && r2.follow <= 2);
        assertFalse(r2.toString(), r2.kept());
    }

    /** 真人抢话的样子：窗内一段、窗外隔一会儿又有人声（下一个字）→ 放行。 */
    @Test public void separateSpeechInContextCountsAsFollowUp() {
        short[] pcm = bursts(3584, 4352, 5376, 6144);
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertEquals(4, r.longestRun);
        assertEquals(0, r.crossWin);
        assertTrue(r.toString(), r.follow >= 2);
        assertTrue(r.toString(), r.kept());
        assertFalse(r.viaCross);
    }

    /** 一个字从窗内一直说到窗外（窗内 6 帧贴窗末 + 窗外连续）→ 放行，输出就是整段预录。 */
    @Test public void sustainedSpeechAcrossBoundaryKept() {
        short[] pcm = bursts(3328, 6400);
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertTrue(r.longestRun >= 5);
        assertTrue(r.crossWin + r.crossCtx > ChimeEchoGate.BURST_MAX_FRAMES);
        assertTrue(r.toString(), r.kept());
        assertEquals(PRE, r.pcm.length);
    }

    /** 跨界放行（D1-5：窗末 1 帧 + 窗外连续 4 帧）不受 LB-1 影响。 */
    @Test public void crossRuleUnaffected() {
        short[] pcm = bursts(4608, 5632);
        ChimeEchoGate.Result r = defGate().process(pcm, pcm.length, PRE, 75);
        assertTrue(r.toString(), r.kept());
        assertTrue(r.viaCross);
    }

    /** 没有上下文（窗关时 flush 传 null）：这条不适用，按 D-1 判定。 */
    @Test public void noContext_fallsBackToWindowRule() {
        short[] pcm = bursts(3584, 4352);
        ChimeEchoGate.Result r = defGate().process(LaneDAudio.slice(pcm, 0, PRE), PRE, 75);
        assertEquals(0, r.ctxFrames);
        assertTrue(r.toString(), r.kept());
    }

    /** 默认值与调参：ctx 续段默认 2，preroll_ctx_frames=0 关掉，范围 0–8。 */
    @Test public void defaultsAndTuning() {
        assertEquals(2, ChimeEchoGate.DEF_CTX_MIN_FRAMES);
        assertEquals(5, ChimeEchoGate.BURST_MAX_FRAMES);
        assertEquals(2.2, ChimeEchoGate.LOUD_RATIO, 1e-9);
        assertEquals("门限仍是 4，没有再调高", 4, ChimeEchoGate.DEF_MIN_FRAMES);
        assertEquals("preroll_ctx_frames", WakeTuning.K_PREROLL_CTX_FRAMES);
        assertEquals(ChimeEchoGate.DEF_CTX_MIN_FRAMES, WakeTuning.DEF_PREROLL_CTX_FRAMES);
        assertEquals(2, defGate().ctxMinFrames());
        assertEquals(2, ChimeEchoGate.fromParams(false, 35, 4, 2, 220, 180, 0).ctxMinFrames());
        assertEquals(0, ChimeEchoGate.fromParams(false, 35, 4, 2, 220, 180, 0, -3).ctxMinFrames());
        assertEquals(8, ChimeEchoGate.fromParams(false, 35, 4, 2, 220, 180, 0, 99).ctxMinFrames());
        String wt = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeTuning.java");
        assertTrue("chimeEchoGate() 要把 preroll_ctx_frames 传进去", wt.contains("getInt(K_PREROLL_CTX_FRAMES, DEF_PREROLL_CTX_FRAMES))"));
        String ws = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue("接上预录时也要打逐帧（上机看余量）", ws.contains("\"ms）：\" + f.gate + \" | 逐帧 \" + f.gate.trace()"));
    }
}
