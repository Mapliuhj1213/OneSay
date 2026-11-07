package com.onesay.assistant.v2.wake;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * lane-d 离线测试素材工具（只在测试里用）。
 *
 * <p>素材：test-jvm/fixtures/lane-d/
 * <ul>
 *   <li>joke.wav / weather.wav / mail.wav —— R14 上机用的 TTS 指令（verify-20261015-r14/wav，16 kHz 单声道，开头约 250 ms 静音）；</li>
 *   <li>start_tone_16k.pcm —— app/assets/start_tone.ogg 用 ffmpeg 解码成 16 kHz 单声道 s16le（原版提示音）。</li>
 * </ul>
 * 「提示音回声」用确定性的模拟：提示音 ⊛ 指数衰减混响（固定种子 LCG）→ tanh 软削波（小喇叭失真，产生谐波）→ 缩放到
 * R14 实测的开窗帧 RMS（1400–2800，另测 5000 余量）。全部确定性，测试可复现。
 */
final class LaneDAudio {

    private LaneDAudio() {}

    static final int SR = 16000;

    static File fixture(String name) {
        return new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/lane-d/" + name);
    }

    static byte[] readAll(File f) throws IOException {
        // 开源版：原版提示音 / 应答语音 / 真人录音素材不随仓库分发，缺素材的离线音频测试跳过（不算失败）。
        org.junit.Assume.assumeTrue("音频素材不在开源仓库里：" + f.getName(), f.isFile());
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } finally {
            in.close();
        }
    }

    static short[] le16(byte[] b, int off, int len) {
        short[] s = new short[len / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[off + 2 * i] & 0xff) | (b[off + 2 * i + 1] << 8));
        return s;
    }

    /** 读 16 kHz 单声道 16-bit WAV（找 data 块）。 */
    static short[] wav(String name) throws IOException {
        byte[] b = readAll(fixture(name));
        int p = 12;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int sz = (b[p + 4] & 0xff) | (b[p + 5] & 0xff) << 8 | (b[p + 6] & 0xff) << 16 | (b[p + 7] & 0xff) << 24;
            if (id.equals("data")) return le16(b, p + 8, Math.min(sz, b.length - p - 8));
            p += 8 + sz + (sz & 1);
        }
        throw new IOException("no data chunk: " + name);
    }

    static short[] startTone() throws IOException {
        byte[] b = readAll(fixture("start_tone_16k.pcm"));
        return le16(b, 0, b.length);
    }

    static short[] shortChime16k() {
        return Earcon.chimeA(SR);   // ★ device-1102：方案 A
    }

    /** 模拟提示音经扬声器 → 房间 → 麦克风的回声，最响 100 ms 帧的 RMS = targetRms。 */
    static double[] echo(short[] chime, double targetRms, double drive, double rt60s, long seed) {
        int irLen = (int) (SR * rt60s * 1.5);
        double[] ir = new double[irLen];
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        for (int i = 0; i < irLen; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            double u = ((s >>> 11) * (1.0 / (1L << 53))) * 2 - 1;
            ir[i] = u * Math.exp(-6.9 * i / (SR * rt60s));
        }
        ir[0] += 8;
        double[] y = new double[chime.length + irLen - 1];
        for (int i = 0; i < chime.length; i++) {
            double c = chime[i];
            if (c == 0) continue;
            for (int j = 0; j < irLen; j++) y[i + j] += c * ir[j];
        }
        double pk = 0;
        for (double v : y) pk = Math.max(pk, Math.abs(v));
        if (pk > 0) for (int i = 0; i < y.length; i++) y[i] = Math.tanh(y[i] / pk * drive);
        double mx = 0;
        for (int off = 0; off + 1600 <= y.length; off += 400) mx = Math.max(mx, rms(y, off, 1600));
        if (mx > 0) for (int i = 0; i < y.length; i++) y[i] *= targetRms / mx;
        return y;
    }

    static double rms(double[] x, int off, int len) {
        double a = 0;
        for (int i = off; i < off + len && i < x.length; i++) a += x[i] * x[i];
        return Math.sqrt(a / Math.max(1, len));
    }

    static double rms(short[] x, int off, int len) {
        double a = 0;
        int n = 0;
        for (int i = off; i < off + len && i < x.length; i++) { a += (double) x[i] * x[i]; n++; }
        return Math.sqrt(a / Math.max(1, n));
    }

    /** 确定性的高斯白噪声（Box-Muller，固定种子）。 */
    static double[] noise(int len, double sigma, long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] x = new double[len];
        for (int i = 0; i < len; i++) x[i] = r.nextGaussian() * sigma;
        return x;
    }

    /** dst[off..] += src * gain。 */
    static void mixInto(double[] dst, int off, double[] src, double gain) {
        for (int i = 0; i < src.length && off + i < dst.length; i++) if (off + i >= 0) dst[off + i] += src[i] * gain;
    }

    static void mixInto(double[] dst, int off, short[] src, int srcOff, double gain) {
        for (int i = srcOff; i < src.length && off + i - srcOff < dst.length; i++) dst[off + i - srcOff] += src[i] * gain;
    }

    static short[] toPcm(double[] x) {
        short[] s = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            long v = Math.round(x[i]);
            s[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
        return s;
    }

    static short[] slice(short[] x, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(x.length, to);
        short[] s = new short[Math.max(0, to - from)];
        System.arraycopy(x, from, s, 0, s.length);
        return s;
    }

    /** 只看掩码之外 200–4000 Hz 的能量（与 ChimeEchoGate 的残差同口径），用于「首字有没有保住」。 */
    static double outOfMaskRms(short[] x, boolean[] mask) {
        int n = ChimeEchoGate.N, hop = ChimeEchoGate.HOP;
        double[] w = ChimeEchoGate.sqrtHann();
        int lo = 200 * n / SR, hi = 4000 * n / SR;
        double acc = 0;
        int frames = 0;
        for (int off = 0; off + n <= x.length; off += hop) {
            double[] re = new double[n], im = new double[n];
            for (int i = 0; i < n; i++) re[i] = x[off + i] * w[i];
            ChimeEchoGate.fft(re, im, false);
            double p = 0;
            for (int b = lo; b <= hi; b++) if (!mask[b]) p += re[b] * re[b] + im[b] * im[b];
            acc += 4 * p / ((double) n * n);
            frames++;
        }
        return frames == 0 ? 0 : Math.sqrt(acc / frames);
    }
}
