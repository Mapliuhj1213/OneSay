package com.onesay.clock.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 三星模板的 PCM 拼接（纯 Java，JVM 可测；移植自 v2 539b99e，停顿表见 Pauses）：
 *   每件 → 24 kHz 单声道 16-bit PCM → 去首尾静音（留 5 ms）→ 响度归一（有声帧 RMS 对齐到同一目标）
 *   → 淡入 8 ms / 淡出 12 ms → 按停顿类型插静音 → 一个 WAV。
 *
 * 停顿数值来自三星原版录音 samsung-ref/a.wav 的实测（静音检测阈值 -39/-41 dBFS 两档取均值，
 * 见 mb-race/samsung-v2/AUDIO-ANALYSIS.md）：
 *   开场「嘿，你好，」之后 420 ms；开场长句内部逗号 ≈ 80 ms（原版整句连读，几乎不停）；
 *   「最低温度为X度，」之后 720 ms；模板引导件与变量件之间（「现在是本地时间|X点X分」）70 ms；
 *   开场句末 → 「我们来看看…」570 ms；「…消息吧。」→ 第一条 150 ms；条目之间 490 ms（原版四处 150/1100/50/675 的均值）；
 *   最后一条 → 「目前就是这样了，」550 ms；「目前就是这样了，」→「一天好心情。」675 ms。
 */
public final class PcmJoin {
    private PcmJoin() { }

    public static final int SR = 24000;

    /** mp3 等压缩格式的解码器（Android 用 MediaCodec 实现；JVM 测试可不给）。 */
    public interface Decoder { short[] decode(byte[] data) throws Exception; }


    public static final int KEEP_MS = 5, FADE_IN_MS = 8, FADE_OUT_MS = 12, HEAD_MS = 20, TAIL_MS = 400;
    /** 有声帧 RMS 目标（dBFS）：与旧版成品 / 预合成件同一水平（≈ -18 LUFS 整体）。 */
    public static final double TARGET_DB = -18.0;
    public static final double PEAK_CEIL_DB = -1.0;
    public static final double MAX_GAIN_DB = 12.0;

    // ------------------------------------------------------------ WAV 读写

    public static boolean isWav(byte[] b) {
        return b != null && b.length >= 44 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int le32(byte[] b, int o) { return le16(b, o) | (le16(b, o + 2) << 16); }

    /** RIFF/WAVE PCM16（单/双声道、任意采样率）→ 24 kHz 单声道。不是 PCM16 WAV 返回 null。 */
    public static short[] fromWav(byte[] b) {
        if (!isWav(b)) return null;
        int ch = 1, sr = SR, bits = 16, fmt = 1, o = 12;
        int dataOff = -1, dataLen = 0;
        while (o + 8 <= b.length) {
            int id0 = b[o], id1 = b[o + 1], id2 = b[o + 2], id3 = b[o + 3];
            int len = le32(b, o + 4);
            if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ') {
                fmt = le16(b, o + 8); ch = le16(b, o + 10); sr = le32(b, o + 12); bits = le16(b, o + 22);
            } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                dataOff = o + 8;
                dataLen = (len <= 0 || dataOff + len > b.length) ? b.length - dataOff : len;   // 流式 wav 常把长度写成 0/-1
                break;
            }
            if (len < 0) return null;
            o += 8 + len + (len & 1);
        }
        if (dataOff < 0 || bits != 16 || (fmt != 1 && fmt != 0xFFFE) || ch < 1) return null;
        int n = dataLen / (2 * ch);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            int acc = 0;
            for (int c = 0; c < ch; c++) acc += (short) le16(b, dataOff + (i * ch + c) * 2);
            s[i] = (short) (acc / ch);
        }
        return resample(s, sr, SR);
    }

    /** 线性插值重采样（件都是 24k 时原样返回）。 */
    public static short[] resample(short[] s, int from, int to) {
        if (from == to || from <= 0 || s.length == 0) return s;
        int n = (int) ((long) s.length * to / from);
        short[] r = new short[n];
        for (int i = 0; i < n; i++) {
            double x = (double) i * from / to;
            int k = (int) x;
            double f = x - k;
            int a = s[Math.min(k, s.length - 1)], c = s[Math.min(k + 1, s.length - 1)];
            r[i] = (short) Math.round(a + (c - a) * f);
        }
        return r;
    }

    public static byte[] toWav(short[] s) {
        int dl = s.length * 2;
        byte[] b = new byte[44 + dl];
        put(b, 0, "RIFF"); w32(b, 4, 36 + dl); put(b, 8, "WAVE"); put(b, 12, "fmt ");
        w32(b, 16, 16); w16(b, 20, 1); w16(b, 22, 1); w32(b, 24, SR); w32(b, 28, SR * 2); w16(b, 32, 2); w16(b, 34, 16);
        put(b, 36, "data"); w32(b, 40, dl);
        for (int i = 0; i < s.length; i++) w16(b, 44 + i * 2, s[i]);
        return b;
    }

    private static void put(byte[] b, int o, String t) { for (int i = 0; i < 4; i++) b[o + i] = (byte) t.charAt(i); }
    private static void w16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void w32(byte[] b, int o, int v) { w16(b, o, v); w16(b, o + 2, v >> 16); }

    /** 任意件字节 → PCM：WAV 直接解析；否则交给解码器（没有解码器或失败 → null）。 */
    public static short[] toPcm(byte[] b, Decoder dec) {
        if (b == null || b.length < 64) return null;
        short[] s = fromWav(b);
        if (s != null) return s;
        if (dec == null) return null;
        try { return dec.decode(b); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ 分析与处理

    private static final int FRAME = SR / 100;   // 10 ms

    public static double frameDb(short[] s, int from, int len) {
        double acc = 0;
        int end = Math.min(s.length, from + len);
        if (end <= from) return -120;
        for (int i = from; i < end; i++) acc += (double) s[i] * s[i];
        double rms = Math.sqrt(acc / (end - from)) / 32768.0;
        return rms <= 1e-9 ? -120 : 20 * Math.log10(rms);
    }

    public static double peakDb(short[] s) {
        int p = 1;
        for (short v : s) p = Math.max(p, Math.abs((int) v));
        return 20 * Math.log10(p / 32768.0);
    }

    /** 去首尾静音：阈值 = max(最响 10ms 帧 − 40 dB, −60 dBFS)，两头各留 5 ms。 */
    public static short[] trim(short[] s) {
        int nf = s.length / FRAME;
        if (nf < 3) return s;
        double[] db = new double[nf];
        double mx = -120;
        for (int i = 0; i < nf; i++) { db[i] = frameDb(s, i * FRAME, FRAME); mx = Math.max(mx, db[i]); }
        double th = Math.max(mx - 40, -60);
        int a = 0, z = nf - 1;
        while (a < nf && db[a] < th) a++;
        while (z > a && db[z] < th) z--;
        if (a >= nf) return new short[0];
        int keep = SR * KEEP_MS / 1000;
        int from = Math.max(0, a * FRAME - keep), to = Math.min(s.length, (z + 1) * FRAME + keep);
        short[] r = new short[to - from];
        System.arraycopy(s, from, r, 0, r.length);
        return r;
    }

    /** 有声帧 RMS（dBFS）：20 ms 帧，只计比最响帧低 25 dB 以内的帧 —— 与 AUDIO-ANALYSIS.md 的度量一致。 */
    public static double activeDb(short[] s) {
        int f = SR / 50, nf = s.length / f;
        if (nf == 0) return frameDb(s, 0, s.length);
        double[] db = new double[nf];
        double mx = -120;
        for (int i = 0; i < nf; i++) { db[i] = frameDb(s, i * f, f); mx = Math.max(mx, db[i]); }
        double acc = 0; int n = 0;
        for (int i = 0; i < nf; i++) if (db[i] > mx - 25) { acc += Math.pow(10, db[i] / 10); n++; }
        return n == 0 ? -120 : 10 * Math.log10(acc / n);
    }

    /** 增益使有声 RMS 到 target；受峰值上限与最大增益约束。返回实际增益 dB。 */
    public static double normalize(short[] s, double targetDb) {
        if (s.length == 0) return 0;
        double g = targetDb - activeDb(s);
        g = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, g));
        double headroom = PEAK_CEIL_DB - peakDb(s);
        if (g > headroom) g = headroom;
        double k = Math.pow(10, g / 20);
        for (int i = 0; i < s.length; i++) {
            long v = Math.round(s[i] * k);
            s[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return g;
    }

    public static void fade(short[] s, int inMs, int outMs) {
        int a = Math.min(s.length / 2, SR * inMs / 1000), z = Math.min(s.length / 2, SR * outMs / 1000);
        for (int i = 0; i < a; i++) s[i] = (short) (s[i] * (double) i / a);
        for (int i = 0; i < z; i++) { int j = s.length - 1 - i; s[j] = (short) (s[j] * (double) i / z); }
    }

    /** 处理单件：去静音 → 归一 → 淡入淡出（就地）。 */
    public static short[] prep(short[] s) {
        short[] t = trim(s);
        normalize(t, TARGET_DB);
        fade(t, FADE_IN_MS, FADE_OUT_MS);
        return t;
    }

    /** 拼接结果：WAV 字节 + 每件在成品里的起点（毫秒）。 */
    public static final class Out {
        public byte[] wav;
        public long durationMs;
        public final List<Long> startMs = new ArrayList<Long>();
    }

    /**
     * @param parts   已处理过的件（null = 缺件，跳过且不补停顿）
     * @param gapAfter 每件之后的停顿（毫秒）；最后一件忽略
     */
    public static Out join(List<short[]> parts, List<Integer> gapAfter) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        Out o = new Out();
        int pos = SR * HEAD_MS / 1000;
        writeSilence(pcm, pos);
        int pendingGap = 0;
        boolean any = false;
        for (int i = 0; i < parts.size(); i++) {
            short[] p = parts.get(i);
            if (p == null || p.length == 0) { o.startMs.add(Long.valueOf(-1)); continue; }
            if (any && pendingGap > 0) { int g = SR * pendingGap / 1000; writeSilence(pcm, g); pos += g; }
            o.startMs.add(Long.valueOf(pos * 1000L / SR));
            for (short v : p) { pcm.write(v & 0xff); pcm.write((v >> 8) & 0xff); }
            pos += p.length;
            any = true;
            pendingGap = gapAfter.get(i).intValue();
        }
        if (!any) return null;
        int tail = SR * TAIL_MS / 1000;
        writeSilence(pcm, tail);
        pos += tail;
        byte[] raw = pcm.toByteArray();
        short[] s = new short[raw.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
        o.wav = toWav(s);
        o.durationMs = pos * 1000L / SR;
        return o;
    }

    private static void writeSilence(ByteArrayOutputStream o, int n) {
        byte[] z = new byte[n * 2];
        o.write(z, 0, z.length);
    }
}
