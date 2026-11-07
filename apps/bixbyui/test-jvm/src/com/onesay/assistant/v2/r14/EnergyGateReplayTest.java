package com.onesay.assistant.v2.r14;

import com.onesay.assistant.v2.wake.EndpointDetector;
import com.onesay.assistant.v2.wake.EnergyGate;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * R14 #1：能量门回放测试（100 ms / 帧的 RMS 序列）。
 *
 * <p><b>数据来源说明</b>：R13b 不打逐帧 RMS（R14 才加 [CAPTURE-SUM] 汇总行），所以 10-15 01:10 的三次尝试只能按日志里的
 * 可观测量<b>重建</b>：开窗前底噪 ≈ 253（[ENV] 01:06:08）、说话期间底噪被抬到 330 / 484（[ENV] 01:10:51 / 01:11:33）、
 * 旧门限下人声帧 0→1→3 / 1 / 5（[VAD] 行）。下面的序列在<b>旧算法</b>上复现了这些量（底噪上涨、帧数 &lt; 4、断不了句），
 * 再用<b>新算法</b>跑同一序列。拿到 R14 真机的 [CAPTURE-SUM] rms=[…] 之后，可以把真序列直接追加成用例。
 */
public class EnergyGateReplayTest {

    // ---------------------------------------------------------------- 序列

    static final int[] QUIET = {250, 262, 241, 258, 270, 236, 255, 248, 266, 244};
    /** 轻声说一句（约 2 s）：音节 460–620，词间回落到 280–340 —— 全都低于旧阈值 max(350, 253×2.5=632)。 */
    static final int[] SOFT = {300, 460, 540, 610, 580, 470, 320, 280, 500, 600, 560, 520, 340, 290, 480, 590, 620, 540, 380, 270};
    static final int[] SOFT_B = {310, 500, 650, 700, 590, 480, 330, 300, 520, 680, 610, 540, 350, 300, 470, 600, 720, 560, 380, 280};
    /** 第 1 次后半段用户提高了音量：偶尔几个音节到 980–1320（旧门限也只能零星数到）。 */
    static final int[] LOUDER = {320, 520, 700, 980, 620, 500, 340, 310, 560, 760, 1150, 580, 360, 310, 500, 640, 1320, 600, 400, 290};
    /** 第 3 次（正常音量，旧门限勉强数到 5–6 帧）。 */
    static final int[] NORMAL = {300, 640, 760, 820, 700, 560, 380, 330, 690, 880, 780, 650, 400, 320, 600, 790, 850, 700, 420, 280};

    static int[] cat(Object... parts) {
        List<Integer> out = new ArrayList<Integer>();
        for (Object p : parts) {
            if (p instanceof int[]) for (int v : (int[]) p) out.add(v);
            else if (p instanceof Integer) for (int k = 0; k < (Integer) p; k++) for (int v : QUIET) out.add(v);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static int[] head(int[] a, int n) {
        return Arrays.copyOf(a, n);
    }

    /** 开窗前 15 s 安静（底噪收敛到 ≈253）。 */
    static final int PRE = 15;

    // ---------------------------------------------------------------- 回放器

    static final class Run {
        int frames;            // 本窗人声帧
        long endpointAtMs = -1; // 相对开窗（arm）的断句时刻；-1 = 没断句
        float maxFloor;        // 捕获窗内底噪最大值（旧算法会被抬高）
        float floorAtArm;
    }

    /** 新算法：EnergyGate + EndpointDetector（自适应 700/900），与 WakeService 读循环同一判据。 */
    static Run runNew(int[] seq, int armIdx, boolean adaptive) {
        EnergyGate g = new EnergyGate();
        EndpointDetector ep = adaptive ? new EndpointDetector(true, 1600, 700, 900, 1500, 300) : EndpointDetector.fixed(1600);
        Run r = new Run();
        long base = 1000000L;
        for (int i = 0; i < seq.length; i++) {
            long t = base + i * 100L;
            if (i == armIdx) {
                g.armCapture(t);
                r.floorAtArm = g.getNoiseFloor();
            }
            g.processRms(seq[i], t);
            if (i < armIdx) continue;
            r.maxFloor = Math.max(r.maxFloor, g.getNoiseFloor());
            long first = g.getFirstSpeechTimestamp();
            long last = g.getLastSpeechTimestamp();
            int frames = g.getSpeechFrameCount();
            if (r.endpointAtMs < 0 && ep.shouldEndpoint(t, first, last, frames, 4)) {
                r.endpointAtMs = t - (base + armIdx * 100L);
            }
        }
        r.frames = g.getSpeechFrameCount();
        return r;
    }

    /** R13b 及以前的算法（逐行照抄 EnergyGate 旧 processFrame + 固定 1600 ms 断句），用来证明重建序列确实复现了真机症状。 */
    static Run runOld(int[] seq, int armIdx) {
        float floor = 200f;
        int frames = 0;
        long last = 0, first = 0;
        Run r = new Run();
        for (int i = 0; i < seq.length; i++) {
            long t = i * 100L;
            if (i == armIdx) {
                frames = 0;
                last = t;
                first = 0;
                r.floorAtArm = floor;
            }
            float rms = seq[i];
            boolean sp = rms > floor * 2.5f && rms > 350f;
            if (sp) {
                last = t;
                frames++;
                if (i >= armIdx && first == 0) first = t;
            } else {
                floor = floor * 0.95f + rms * 0.05f;
                if (floor < 100f) floor = 100f;
            }
            if (i < armIdx) continue;
            r.maxFloor = Math.max(r.maxFloor, floor);
            if (r.endpointAtMs < 0 && first > 0 && t - last > 1600 && frames >= 4) r.endpointAtMs = t - armIdx * 100L;
        }
        r.frames = frames;
        return r;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    public void attempt1_userFailingCase_oldNeverEndpoints_newDoes() {
        // 第 1 次（01:10:29 按键，16 s 后用户放弃）：轻声说、重复了几遍，后两遍提高音量
        int[] seq = cat(PRE, SOFT, head(QUIET, 6), SOFT, head(QUIET, 8), SOFT_B, head(QUIET, 5), LOUDER, head(QUIET, 4), LOUDER, 3);
        int arm = PRE * QUIET.length;
        Run old = runOld(seq, arm);
        assertTrue("旧算法：16 s 内人声帧 < 4（日志 0→1→3），实际 " + old.frames, old.frames < 4);
        assertEquals("旧算法：一直断不了句", -1, old.endpointAtMs);
        assertTrue("旧算法：底噪被人声抬高 > 30%（日志 253→330/484），实际 " + old.maxFloor, old.maxFloor > old.floorAtArm * 1.3f);

        Run neu = runNew(seq, arm, true);
        assertTrue("新算法：人声帧够数，实际 " + neu.frames, neu.frames >= 20);
        assertTrue("新算法：6 s 内断句（用户第二遍说完），实际 " + neu.endpointAtMs, neu.endpointAtMs > 0 && neu.endpointAtMs <= 6000);
        assertEquals("新算法：捕获窗内底噪冻结", neu.floorAtArm, neu.maxFloor, 0.01f);
    }

    @Test
    public void attempt1_firstSentenceAlone_endpointsWithin1sOfSpeechEnd() {
        int[] seq = cat(PRE, SOFT, 3);
        int arm = PRE * QUIET.length;
        assertEquals("旧：一句轻声话 0 帧", 0, runOld(seq, arm).frames);
        Run neu = runNew(seq, arm, true);
        // 最后一帧人声在 1.7 s（590/620/540 那几帧），自适应长句尾静音 900 ms
        assertTrue("新：说完 ≤ 1 s 内断句，实际 " + neu.endpointAtMs, neu.endpointAtMs >= 2000 && neu.endpointAtMs <= 2800);
    }

    @Test
    public void attempt2_voiceWake_oneFrameOld_manyFramesNew() {
        // 第 2 次（语音唤醒，旧门限整句只数到 1 帧，靠 8 s 窗到点兜底）
        int[] seq = cat(PRE, SOFT_B, 6);
        int arm = PRE * QUIET.length;
        Run old = runOld(seq, arm);
        assertTrue("旧：≤1 帧（日志 1/4），实际 " + old.frames, old.frames <= 1);
        assertEquals(-1, old.endpointAtMs);
        Run neu = runNew(seq, arm, true);
        assertTrue(neu.frames >= 10);
        assertTrue("新：不用等 8 s 窗到点，实际 " + neu.endpointAtMs, neu.endpointAtMs > 0 && neu.endpointAtMs < 3000);
    }

    @Test
    public void attempt3_normalVolume_bothEndpoint_newFasterWithAdaptive() {
        int[] seq = cat(PRE, NORMAL, 4);
        int arm = PRE * QUIET.length;
        Run old = runOld(seq, arm);
        assertTrue("旧：勉强过线（日志 5 帧），实际 " + old.frames, old.frames >= 4 && old.frames <= 7);
        Run neuFixed = runNew(seq, arm, false);
        Run neuAdaptive = runNew(seq, arm, true);
        assertTrue(neuFixed.frames > old.frames);
        assertTrue("自适应尾静音比固定 1600 ms 快 ≥ 600 ms", neuFixed.endpointAtMs - neuAdaptive.endpointAtMs >= 600);
    }

    @Test
    public void quietRoom_noiseNeverCountsAsSpeech() {
        // 安静卧室：底噪 ~110，偶尔 180–210 的小起伏（翻身、空调）—— 阈值 max(220, 110×1.8=198)=220，不该算人声
        int[] room = {105, 118, 99, 122, 110, 181, 112, 104, 209, 115};
        int[] seq = new int[room.length * 30];
        for (int i = 0; i < seq.length; i++) seq[i] = room[i % room.length];
        Run neu = runNew(seq, 150, true);
        assertEquals("安静房间不出人声帧", 0, neu.frames);
        assertEquals(-1, neu.endpointAtMs);
    }

    @Test
    public void noisyRoom_floorAdaptsOutsideCapture_thenSpeechStillDetected() {
        // 环境从 250 变吵到 ~420（电视），40 s 后开窗；电视声 380–460 不算人声，人声 900+ 算
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 0; i < 400; i++) l.add(i % 2 == 0 ? 400 : 440);
        int arm = l.size();
        int[] tv = {380, 460, 420, 400, 450, 410};
        for (int k = 0; k < 3; k++) for (int v : tv) l.add(v);
        int[] loud = {950, 1100, 1020, 980, 1200, 1050, 990, 1010};
        for (int v : loud) l.add(v);
        for (int k = 0; k < 15; k++) l.add(420);
        int[] seq = new int[l.size()];
        for (int i = 0; i < seq.length; i++) seq[i] = l.get(i);
        Run neu = runNew(seq, arm, true);
        assertTrue("开窗时底噪已跟上环境（≥ 380），实际 " + neu.floorAtArm, neu.floorAtArm >= 380f);
        assertEquals("只数到 8 帧真人声（电视声不算）", 8, neu.frames);
        assertTrue(neu.endpointAtMs > 0);
    }

    @Test
    public void floorFrozenDuringCapture_thawedAfter_andAutoThawAfter21s() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(250, t += 100);
        float f0 = g.getNoiseFloor();
        g.armCapture(t);
        assertTrue(g.isCapturing());
        for (int i = 0; i < 100; i++) g.processRms(400, t += 100);   // 低于阈值的「轻声」帧：旧算法会拿它抬底噪
        assertEquals("窗内冻结", f0, g.getNoiseFloor(), 0.001f);
        g.endCapture();
        for (int i = 0; i < 50; i++) g.processRms(120, t += 100);
        assertTrue("解冻后安静帧继续追踪", g.getNoiseFloor() < f0);
        // 调用方漏了 endCapture：21 s 后自动解冻
        g.armCapture(t);
        float f1 = g.getNoiseFloor();
        g.processRms(130, t += 22000);
        assertFalse("超时自动解冻", g.isCapturing());
        assertTrue(g.getNoiseFloor() < f1);
    }

    @Test
    public void speechFramesOutsideCapture_doNotRaiseFloorQuickly() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(250, t += 100);
        float f0 = g.getNoiseFloor();
        for (int i = 0; i < 20; i++) g.processRms(560, t += 100);   // 2 s 没过硬门的说话声（窗外，比如唤醒词前后）
        assertTrue("窗外 2 s 说话最多把底噪抬 10%（旧算法 ≈ +60%），实际 " + g.getNoiseFloor(), g.getNoiseFloor() < f0 * 1.10f);
    }

    @Test
    public void kwsHardGateUnchanged_andThresholdFormula() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(250, t += 100);
        g.processRms(500, t += 100);
        assertFalse("KWS 硬门照旧：500 < 底噪×2.5", g.hardRecent(t));
        g.processRms(900, t += 100);
        assertTrue(g.hardRecent(t));
        g.armCapture(t += 100);
        float floor = g.getNoiseFloor();
        assertEquals(Math.max(EnergyGate.CAPTURE_ABS_MIN, floor * EnergyGate.CAPTURE_FLOOR_RATIO), g.captureThreshold(), 0.01f);
        assertEquals(1.8f, EnergyGate.CAPTURE_FLOOR_RATIO, 0f);
        assertEquals(220f, EnergyGate.CAPTURE_ABS_MIN, 0f);
    }

    @Test
    public void renewRequiresEnoughSpeechFrames() {
        assertFalse("一帧零星人声不续期（10-15 第 1 次就是 1/4 续到 16 s）", EnergyGate.enoughSpeechToRenew(1, 4));
        assertFalse(EnergyGate.enoughSpeechToRenew(3, 4));
        assertTrue(EnergyGate.enoughSpeechToRenew(4, 4));
    }

    @Test
    public void captureSummaryCarriesStatsAndTrace() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(250, t += 100);
        g.armCapture(t);
        for (int v : SOFT) g.processRms(v, t += 100);
        String s = g.captureSummary();
        assertTrue(s, s.contains("帧=20"));
        assertTrue(s, s.contains("rms=[300,460,540"));
        assertTrue(s, s.contains("人声帧=13"));
        assertTrue(s, s.contains("底噪(冻结)=250 阈值=450"));
    }

    @Test
    public void selfTestSemantics_floorRisesSlowlyInSteadyNoise() {
        // V2SelfTest 12.6（debug 自测）：800 帧 rms=300 后底噪应显著高于安静（slow rise 0.4%/帧 → ≈296）
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 800; i++) g.processRms(300, t += 100);
        assertTrue(g.getNoiseFloor() > 280f);
        assertTrue(g.getNoiseLevel() >= 1);
    }

    @Test
    public void r141_steadyLoudAmbientOutsideCapture_floorCatchesUp_silentWakeHasNoSpeech() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(190, t += 100);   // 安静
        for (int i = 0; i < 600; i++) g.processRms(700, t += 100);   // 60 s 稳态噪声（10-15 白天实测 rms≈700）
        assertTrue("底噪跟上稳态噪声，实际 " + g.getNoiseFloor(), g.getNoiseFloor() > 450f);
        g.armCapture(t);
        for (int i = 0; i < 50; i++) g.processRms(700, t += 100);
        assertEquals("静音唤醒：稳态噪声不算人声", 0, g.getSpeechFrameCount());
        for (int i = 0; i < 10; i++) g.processRms(2500, t += 100);
        assertEquals("贴近说话仍算人声", 10, g.getSpeechFrameCount());
    }

    @Test
    public void r141_shortSpeechOutsideCapture_raisesFloorOnlyALittle() {
        EnergyGate g = new EnergyGate();
        long t = 0;
        for (int i = 0; i < 150; i++) g.processRms(200, t += 100);
        float f0 = g.getNoiseFloor();
        for (int i = 0; i < 30; i++) g.processRms(2000, t += 100);   // 窗外 3 s 大声说话
        assertTrue("3 s 说话最多把底噪抬 ~6 成差距的一小部分，实际 " + g.getNoiseFloor(), g.getNoiseFloor() < f0 + 0.07f * (2000 - f0));
    }
}
