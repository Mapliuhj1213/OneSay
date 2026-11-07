package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** D1：按键预录缓存的时序与隐私边界。 */
public class KeyPreRollTest {

    private static final int SR = 16000;
    private static final int CHUNK = 1600;   // micLoop 一次读 100 ms

    @Test public void onlyKeyWakeAndSwitchOn() {
        assertTrue(KeyPreRoll.allowed(true, true));
        assertFalse("唤醒词唤醒不预录", KeyPreRoll.allowed(true, false));
        assertFalse("开关关 = R14", KeyPreRoll.allowed(false, true));
        assertFalse(KeyPreRoll.allowed(false, false));
    }

    @Test public void startNeverBeforeWake() {
        assertEquals(1000, KeyPreRoll.startAt(1000, 1300, 300));
        assertEquals("要求更长也不能早于唤醒", 1000, KeyPreRoll.startAt(1000, 1300, 5000));
        assertEquals("preroll_ms 只能让预录更短", 1200, KeyPreRoll.startAt(1000, 1300, 100));
        assertEquals("0 = 不预录", 1300, KeyPreRoll.startAt(1000, 1300, 0));
        assertEquals(1300, KeyPreRoll.startAt(1000, 1300, -5));
        for (long pre = -100; pre <= 10000; pre += 37) {
            long s = KeyPreRoll.startAt(1000, 1300, pre);
            assertTrue("pre=" + pre, s >= 1000 && s <= 1300);
        }
    }

    @Test public void defaultLengthEqualsR14KeyGuard() {
        // WakeService 在 JVM 里加载不了（静态初始化要 Looper），按源码核对 R14 的按键起点常量
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(ws.contains("private static final long WAKE_LOCAL_GUARD_MS = 300;"));
        assertEquals("预录正好补上 R14 按键起点前的 300 ms",
                CaptureSessionPolicy.captureFromOffsetMs(true, false, 300, 450, 1100), KeyPreRoll.DEFAULT_PRE_ROLL_MS);
        assertEquals(CaptureSessionPolicy.captureFromOffsetMs(true, true, 300, 450, 1100), KeyPreRoll.DEFAULT_PRE_ROLL_MS);
    }

    static String src(String rel) {
        try {
            return new String(LaneDAudio.readAll(new java.io.File(System.getProperty("bixby.root", "."), rel)), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static short[] ramp(int n, int base) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (base + i % 1000);
        return s;
    }

    @Test public void samplesBeforeWakeAreDropped() {
        KeyPreRoll p = new KeyPreRoll(SR);
        p.arm(10_000, 10_300, 300, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 50);
        assertTrue(p.armed());
        // 块尾在唤醒之前：整块不要
        assertFalse(p.wants(9_990));
        assertEquals(0, p.offer(ramp(CHUNK, 0), CHUNK, 9_990));
        // 块 [9_960, 10_060]：只要唤醒之后的 60 ms = 960 样本，前 640 个（唤醒前 40 ms）丢掉
        assertEquals(960, p.offer(ramp(CHUNK, 0), CHUNK, 10_060));
        assertEquals(640, p.droppedBeforeStart());
        assertEquals(960, p.bufferedSamples());
        assertEquals(CHUNK, p.offer(ramp(CHUNK, 0), CHUNK, 10_160));
        assertEquals(CHUNK, p.offer(ramp(CHUNK, 0), CHUNK, 10_260));
        // 块尾越过 captureFrom 的那块归 R14
        assertFalse(p.wants(10_360));
        assertEquals(0, p.offer(ramp(CHUNK, 0), CHUNK, 10_360));
        assertEquals(960 + 2 * CHUNK, p.bufferedSamples());
    }

    @Test public void chunkEndingExactlyAtCaptureFromIsPreRoll() {
        KeyPreRoll p = new KeyPreRoll(SR);
        p.arm(0, 300, 300, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 50);
        assertTrue(p.wants(300));      // R14 按 now > captureFrom 收，== 的那块原本被丢，正好归预录
        assertFalse(p.wants(301));
        assertFalse(p.wants(0));
    }

    @Test public void notArmedIsNoop() {
        KeyPreRoll p = new KeyPreRoll(SR);
        assertFalse(p.armed());
        assertEquals(0, p.offer(ramp(CHUNK, 0), CHUNK, 100));
        KeyPreRoll.Flushed f = p.flush(null, 0);
        assertFalse(f.emitted());
        assertNull(f.gate);
        // preroll_ms=0 → 起点 = captureFrom → 不武装
        p.arm(0, 300, 0, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 50);
        assertFalse(p.armed());
    }

    @Test public void flushOnceThenDisarmed() {
        KeyPreRoll p = new KeyPreRoll(SR);
        p.arm(0, 300, 300, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 50);
        p.offer(new short[CHUNK], CHUNK, 100);
        KeyPreRoll.Flushed f = p.flush(null, 0);
        assertNotNull(f.gate);
        assertFalse("全静音 → 不接", f.emitted());
        assertFalse(p.armed());
        assertNull("第二次 flush 什么都不做", p.flush(null, 0).gate);
    }

    @Test public void disarmDropsBuffer() {
        KeyPreRoll p = new KeyPreRoll(SR);
        p.arm(0, 300, 300, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 50);
        p.offer(ramp(CHUNK, 1000), CHUNK, 100);
        p.disarm();
        assertEquals(0, p.bufferedSamples());
        assertFalse(p.flush(null, 0).emitted());
    }

    @Test public void contextIsAnalysedButNotEmitted() throws Exception {
        // 提示音 + 抢话的 300 ms 预录，再给 100 ms 右侧上下文：输出长度只等于预录长度
        double[] x = LaneDAudio.noise(4800 + CHUNK, 60, 3);
        LaneDAudio.mixInto(x, 60 * 16, LaneDAudio.echo(LaneDAudio.startTone(), 2800, 2, 0.15, 4), 1);
        LaneDAudio.mixInto(x, 50 * 16, LaneDAudio.wav("joke.wav"), 250 * 16, 0.5);
        short[] pcm = LaneDAudio.toPcm(x);
        KeyPreRoll p = new KeyPreRoll(SR);
        p.arm(0, 300, 300, ChimeEchoGate.withDefaults(ChimeEchoGate.startToneMask()), 75);
        for (int c = 0; c < 3; c++) p.offer(LaneDAudio.slice(pcm, c * CHUNK, (c + 1) * CHUNK), CHUNK, (c + 1) * 100L);
        short[] ctx = LaneDAudio.slice(pcm, 4800, 4800 + CHUNK);
        KeyPreRoll.Flushed f = p.flush(ctx, ctx.length);
        assertTrue(String.valueOf(f.gate), f.emitted());
        assertEquals(4800, f.pcm.length);
        assertEquals(300, f.bufferedMs);
        assertEquals(0, f.startAt);
    }
}
