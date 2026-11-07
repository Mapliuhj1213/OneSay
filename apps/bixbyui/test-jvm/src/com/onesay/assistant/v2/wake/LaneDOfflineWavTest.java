package com.onesay.assistant.v2.wake;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * D1 离线验收：用 R14 的 wav 素材（joke / weather / mail）模拟「按键 → 提示音 → 抢话」的整条麦克风流，
 * 按 WakeService 的节奏（100 ms 一块、块尾时刻判归属）喂给 {@link KeyPreRoll}，比较 D1 与 R14 实际会上传的音频。
 *
 * <ul>
 *   <li>只有提示音（原版 / 短提示音）→ 预录整段丢弃，D1 上传的音频与 R14 <b>逐样本相同</b>（提示音本身不会多出任何可转写的东西）；</li>
 *   <li>提示音 + 抢话（唤醒后 50 / 150 ms 开口）→ 预录接上，[开口, R14 第一块起点) 这段首字的语音能量在 D1 里保住了，R14 里没有。</li>
 * </ul>
 * 需要把模拟出的音频交给云识别核对文字时：{@code JAVA_OPTS=-Dlane.d.dump=/某目录 bash run_unit_tests.sh LaneDOfflineWavTest}
 * 会写出 *_r14.wav / *_d1.wav，再跑 tools/lane-d/offline_asr.py。
 */
public class LaneDOfflineWavTest {

    private static final int SR = 16000;
    private static final int CHUNK = 1600;
    private static final long WAKE_AT = 50_000;       // elapsedRealtime 毫秒（任意基准）
    private static final long CAPTURE_FROM = WAKE_AT + 300;
    private static final int CHIME_DELAY_MS = 60;      // MediaPlayer 起播延迟
    private static final int FIRST_CHUNK_END_OFFSET_MS = 40;   // 第一块块尾在唤醒后 40 ms（块跨过唤醒时刻）
    private static final int TOTAL_MS = 2600;

    /** 一次模拟的结果。 */
    static final class Run {
        short[] r14;
        short[] d1;
        KeyPreRoll.Flushed flushed;
        int offsetSamplesOfStreamAtWake;   // 流数组里唤醒时刻对应的下标
    }

    /**
     * @param chime      提示音（16 kHz），null = 没有
     * @param ack        原版应答语音（16 kHz），接在提示音之后播；null = 没有
     * @param speech     抢话素材（R14 wav，开头约 250 ms 静音已跳过），null = 不说话
     * @param onsetMs    唤醒后多少 ms 开口
     * @param shortChime 用短提示音掩码
     */
    static Run simulate(short[] chime, short[] ack, short[] speech, int onsetMs, boolean shortChime, String dumpName) throws IOException {
        return simulate(chime, ack, speech, onsetMs, CHIME_DELAY_MS, ChimeEchoGate.fromParams(shortChime, 35, ChimeEchoGate.DEF_MIN_FRAMES, 2, 220, 180, 0), dumpName);
    }

    /** 同上，可指定提示音起播延迟和回声门（调参评估用）。 */
    static Run simulate(short[] chime, short[] ack, short[] speech, int onsetMs, int chimeDelayMs, ChimeEchoGate gate, String dumpName) throws IOException {
        // 流从「唤醒前 60 ms」开始，这样第一块会跨过唤醒时刻（检验起点前的样本被丢掉）
        int pre = (CHUNK / 16) - FIRST_CHUNK_END_OFFSET_MS;     // 60 ms
        int wakeIdx = pre * 16;
        double[] x = LaneDAudio.noise((pre + TOTAL_MS) * 16, 60, 17);
        if (chime != null) {
            double[] e = LaneDAudio.echo(chime, 2800, 2, 0.15, 5);
            LaneDAudio.mixInto(x, wakeIdx + chimeDelayMs * 16, e, 1);
            if (ack != null) {
                double[] ea = LaneDAudio.echo(ack, 3400, 0.5, 0.15, 6);
                LaneDAudio.mixInto(x, wakeIdx + chimeDelayMs * 16 + chime.length, ea, 1);
            }
        }
        if (speech != null) LaneDAudio.mixInto(x, wakeIdx + onsetMs * 16, speech, 250 * 16, 0.5);
        short[] stream = LaneDAudio.toPcm(x);

        KeyPreRoll pr = new KeyPreRoll(SR);
        pr.arm(WAKE_AT, CAPTURE_FROM, 300, gate, 75);

        java.io.ByteArrayOutputStream r14 = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream d1 = new java.io.ByteArrayOutputStream();
        Run run = new Run();
        run.offsetSamplesOfStreamAtWake = wakeIdx;
        for (int off = 0; off + CHUNK <= stream.length; off += CHUNK) {
            short[] chunk = LaneDAudio.slice(stream, off, off + CHUNK);
            long chunkEnd = WAKE_AT - pre + (off + CHUNK) / 16;
            if (pr.wants(chunkEnd)) pr.offer(chunk, CHUNK, chunkEnd);
            if (chunkEnd > CAPTURE_FROM) {                 // R14：块尾晚于起点才收
                if (pr.armed()) {
                    run.flushed = pr.flush(chunk, CHUNK);
                    if (run.flushed.emitted()) write(d1, run.flushed.pcm);
                }
                write(r14, chunk);
                write(d1, chunk);
            }
        }
        run.r14 = toShorts(r14.toByteArray());
        run.d1 = toShorts(d1.toByteArray());
        String dump = System.getProperty("lane.d.dump");
        if (dump != null && dumpName != null) {
            new File(dump).mkdirs();
            writeWav(new File(dump, dumpName + "_r14.wav"), run.r14);
            writeWav(new File(dump, dumpName + "_d1.wav"), run.d1);
        }
        return run;
    }

    @Test public void chimeOnlyOriginal_identicalToR14() throws Exception {
        Run r = simulate(LaneDAudio.startTone(), null, null, 0, false, "chime_orig");
        assertNotNull(r.flushed);
        assertFalse("只有提示音 → 预录不接：" + r.flushed.gate, r.flushed.emitted());
        assertArrayEquals("上传音频与 R14 逐样本相同", r.r14, r.d1);
    }

    @Test public void chimeOnlyShort_identicalToR14() throws Exception {
        Run r = simulate(LaneDAudio.shortChime16k(), null, null, 0, true, "chime_short");
        assertFalse(String.valueOf(r.flushed.gate), r.flushed.emitted());
        assertArrayEquals(r.r14, r.d1);
    }

    @Test public void privacy_noSampleBeforeWake() throws Exception {
        Run r = simulate(LaneDAudio.startTone(), null, LaneDAudio.wav("joke.wav"), 50, false, null);
        assertTrue(r.flushed.emitted());
        assertTrue("起点不早于唤醒", r.flushed.startAt >= WAKE_AT);
        // 块尾在唤醒后 40 / 140 / 240 / 340 ms：R14 收的第一块是块尾 +340 的那块（实际覆盖 +240 起），
        // 所以 R14 真正丢掉的是 [唤醒, +240)；预录正好补这 240 ms —— 第一块里唤醒前的 60 ms 被丢掉，不重叠、不留缝
        assertEquals(240, r.flushed.bufferedMs);
        assertEquals(240 * 16, r.flushed.pcm.length);
        assertEquals(r.r14.length + 240 * 16, r.d1.length);
    }

    @Test public void bargeIn_firstSyllableKept_allR14Wavs() throws Exception {
        boolean[] mask = ChimeEchoGate.startToneMask();
        for (String w : new String[]{"joke.wav", "weather.wav", "mail.wav"}) {
            short[] sp = LaneDAudio.wav(w);
            for (int onset : new int[]{50, 150}) {
                Run r = simulate(LaneDAudio.startTone(), null, sp, onset, false, w.replace(".wav", "") + "_orig_" + onset);
                assertTrue(w + " onset=" + onset + " " + r.flushed.gate, r.flushed.emitted());
                // D1 开头 = 预录（唤醒 → R14 第一块起点）；对照：同一时间段的干净语音（掩码外能量同口径）
                int preLen = r.flushed.pcm.length;
                short[] d1Head = LaneDAudio.slice(r.d1, onset * 16, preLen);
                double[] clean = new double[preLen];
                LaneDAudio.mixInto(clean, onset * 16, sp, 250 * 16, 0.5);
                short[] cleanHead = LaneDAudio.slice(LaneDAudio.toPcm(clean), onset * 16, preLen);
                double kept = LaneDAudio.outOfMaskRms(d1Head, mask);
                double ref = LaneDAudio.outOfMaskRms(cleanHead, mask);
                assertTrue(w + " onset=" + onset + " 首字语音能量保留 " + (int) kept + " / " + (int) ref, kept > 0.75 * ref);
                // R14 的音频从第一块起点才开始：这段首字在 R14 里根本不存在
                assertEquals(r.r14.length + preLen, r.d1.length);
                // 预录之后的部分与 R14 完全相同（D1 不改 R14 的帧）
                assertArrayEquals(r.r14, LaneDAudio.slice(r.d1, preLen, r.d1.length));
            }
        }
    }

    /** ★ D-1：短提示音下抢话（R14 三条 wav × 唤醒后 50 / 150 ms）同样接上预录、首字保住。 */
    @Test public void bargeIn_shortChime_firstSyllableKept_allR14Wavs() throws Exception {
        boolean[] mask = ChimeEchoGate.shortChimeMask();
        for (String w : new String[]{"joke.wav", "weather.wav", "mail.wav"}) {
            short[] sp = LaneDAudio.wav(w);
            for (int onset : new int[]{50, 150}) {
                Run r = simulate(LaneDAudio.shortChime16k(), null, sp, onset, true, null);
                assertTrue(w + " onset=" + onset + " " + r.flushed.gate, r.flushed.emitted());
                int preLen = r.flushed.pcm.length;
                short[] d1Head = LaneDAudio.slice(r.d1, onset * 16, preLen);
                double[] clean = new double[preLen];
                LaneDAudio.mixInto(clean, onset * 16, sp, 250 * 16, 0.5);
                short[] cleanHead = LaneDAudio.slice(LaneDAudio.toPcm(clean), onset * 16, preLen);
                double kept = LaneDAudio.outOfMaskRms(d1Head, mask), ref = LaneDAudio.outOfMaskRms(cleanHead, mask);
                assertTrue(w + " onset=" + onset + " 首字语音能量保留 " + (int) kept + " / " + (int) ref, kept > 0.75 * ref);
                assertArrayEquals(r.r14, LaneDAudio.slice(r.d1, preLen, r.d1.length));
            }
        }
    }

    /** ★ D-1：只有提示音、起播延迟 0–280 ms（尾巴跨过预录边界、落进第一块 R14 帧）→ 两种提示音一律丢弃，与 R14 逐样本相同。 */
    @Test public void chimeOnly_anyDelay_bothChimes_identicalToR14() throws Exception {
        for (int c = 0; c < 2; c++) {
            short[] chime = c == 0 ? LaneDAudio.startTone() : LaneDAudio.shortChime16k();
            ChimeEchoGate g = ChimeEchoGate.fromParams(c == 1, 35, ChimeEchoGate.DEF_MIN_FRAMES, 2, 220, 180, 0);
            for (int d = 0; d <= 280; d += 20) {
                Run r = simulate(chime, null, null, 0, d, g, null);
                assertFalse("c=" + c + " delay=" + d + " " + r.flushed.gate, r.flushed.emitted());
                assertArrayEquals(r.r14, r.d1);
            }
        }
    }

    /**
     * ★ LB-1：0–200 ms 每 10 ms 开口 × 3 条 wav × 两种提示音（126 个点）全扫，新判定（窗外续段 / 响度 / 短字头三条）的代价对比：
     * D-1 判定（ctx 续段 0）接上的点一个不少；D1-6 验收点（50 / 150 ms）全部接上；另外救回短提示音 mail@120（窗内 3 帧 + 窗外 5 帧）。
     */
    @Test public void lb1FollowUpRule_costOver0to200msSweep() throws Exception {
        int oldDropped = 0, newDropped = 0;
        StringBuilder lost = new StringBuilder(), gained = new StringBuilder();
        for (int c = 0; c < 2; c++) {
            short[] chime = c == 0 ? LaneDAudio.startTone() : LaneDAudio.shortChime16k();
            ChimeEchoGate gNew = ChimeEchoGate.fromParams(c == 1, 35, ChimeEchoGate.DEF_MIN_FRAMES, 2, 220, 180, 0);
            ChimeEchoGate gOld = ChimeEchoGate.fromParams(c == 1, 35, ChimeEchoGate.DEF_MIN_FRAMES, 2, 220, 180, 0, 0);
            for (String w : new String[]{"joke.wav", "weather.wav", "mail.wav"}) {
                short[] sp = LaneDAudio.wav(w);
                for (int on = 0; on <= 200; on += 10) {
                    boolean kn = simulate(chime, null, sp, on, CHIME_DELAY_MS, gNew, null).flushed.emitted();
                    boolean ko = simulate(chime, null, sp, on, CHIME_DELAY_MS, gOld, null).flushed.emitted();
                    if (!kn) newDropped++;
                    if (!ko) oldDropped++;
                    String tag = " c" + c + ":" + w.replace(".wav", "") + "@" + on;
                    if (ko && !kn) lost.append(tag);
                    if (kn && !ko) gained.append(tag);
                    if (on == 50 || on == 150) assertTrue("D1-6 验收点" + tag, kn);
                }
            }
        }
        assertEquals("LB-1 判定多丢的点", "", lost.toString());
        assertEquals(" c1:mail@120", gained.toString());
        // ★ device-1102：短提示音换成方案 A（C6→E6 240 ms 渐弱）后重算（旧 880→1320 160 ms 时是 17 / 16）；「多丢的点」仍为空
        assertEquals("old=" + oldDropped + " new=" + newDropped, 15, oldDropped);
        assertEquals("old=" + oldDropped + " new=" + newDropped, 14, newDropped);
    }

    @Test public void bargeIn_withOriginalAckVoice_and_shortChime() throws Exception {
        short[] ack = ackVoice();
        Run a = simulate(LaneDAudio.startTone(), ack, LaneDAudio.wav("joke.wav"), 50, false, "joke_orig_ack_50");
        assertTrue(String.valueOf(a.flushed.gate), a.flushed.emitted());
        Run b = simulate(LaneDAudio.shortChime16k(), null, LaneDAudio.wav("mail.wav"), 50, true, "mail_short_50");
        assertTrue(String.valueOf(b.flushed.gate), b.flushed.emitted());
        // 原版：提示音 + 应答语音、没人说话 → 预录不接
        Run c = simulate(LaneDAudio.startTone(), ack, null, 0, false, "chime_orig_ack");
        assertFalse(String.valueOf(c.flushed.gate), c.flushed.emitted());
        assertArrayEquals(c.r14, c.d1);
    }

    /** 原版应答语音（wake_ack1.wav，24 kHz）简单抽取到 16 kHz（线性插值，测试素材够用）。 */
    static short[] ackVoice() throws IOException {
        File f = new File(System.getProperty("bixby.root", "."), "app/assets/wake_ack1.wav");
        byte[] b = LaneDAudio.readAll(f);
        int p = 12, rate = 24000;
        short[] src = null;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int sz = (b[p + 4] & 0xff) | (b[p + 5] & 0xff) << 8 | (b[p + 6] & 0xff) << 16 | (b[p + 7] & 0xff) << 24;
            if (id.equals("fmt ")) rate = (b[p + 12] & 0xff) | (b[p + 13] & 0xff) << 8 | (b[p + 14] & 0xff) << 16 | (b[p + 15] & 0xff) << 24;
            if (id.equals("data")) { src = LaneDAudio.le16(b, p + 8, Math.min(sz, b.length - p - 8)); break; }
            p += 8 + sz + (sz & 1);
        }
        assertNotNull(src);
        int n = (int) ((long) src.length * SR / rate);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i * rate / SR;
            int k = (int) t;
            double fr = t - k;
            int a = src[Math.min(k, src.length - 1)], c = src[Math.min(k + 1, src.length - 1)];
            out[i] = (short) Math.round(a + (c - a) * fr);
        }
        return out;
    }

    // ---- 小工具 ----

    private static void write(java.io.ByteArrayOutputStream o, short[] s) {
        for (short v : s) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    }

    private static short[] toShorts(byte[] b) {
        return LaneDAudio.le16(b, 0, b.length);
    }

    static void writeWav(File f, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes("US-ASCII")).putInt(36 + dataLen).put("WAVE".getBytes("US-ASCII"));
        bb.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(SR).putInt(SR * 2)
                .putShort((short) 2).putShort((short) 16);
        bb.put("data".getBytes("US-ASCII")).putInt(dataLen);
        for (short v : pcm) bb.putShort(v);
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(bb.array()); } finally { out.close(); }
    }
}
