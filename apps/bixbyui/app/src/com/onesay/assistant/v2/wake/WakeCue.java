package com.onesay.assistant.v2.wake;

/**
 * ★ int/1102 唤醒提示音来源（纯 Java，JVM 单测 WakeCueTest）。默认 App 自带合成音，可改用手机自带的通知音。
 *
 * <h3>来源（pref {@code wake_tuning/wake_cue}；debug 广播 {@code --es wakecue system|a|legacy}）</h3>
 * <ul>
 *   <li>{@link Source#SYSTEM}：用户从手机自带的通知音 / 铃声里选一个（RingtoneManager 列出，运行时解码，不打包进 APK）；
 *       没选就用系统默认通知音；太长的截到 {@link #MAX_MS} 并渐弱；解码失败退回方案 A。候选由 {@link #setCandidates} 给；</li>
 *   <li>{@link Source#A}：device-1102 方案 A（C6→E6 240 ms 渐弱，{@link Earcon#chimeA}）；</li>
 *   <li>{@link Source#LEGACY}：更早的 160 ms 880→1320 Hz（{@link Earcon#legacyChime}）。</li>
 * </ul>
 * 解码（Android MediaExtractor / MediaCodec）在 {@code WakeCueLoader}；这里只管选来源、去首尾静音、重采样、回退，
 * 回声门掩码（{@link ChimeEchoGate#shortChimeMask}）按<b>实际</b>播放的那段音频现算（换来源 → {@link #version()} 变 → 掩码重算），
 * 播放后解除抑制的等待也按实际时长（{@link Earcon#durationMs}）。
 */
public final class WakeCue {

    private WakeCue() {}

    public enum Source { SYSTEM, A, LEGACY }

    public static final String K_WAKE_CUE = "wake_cue";
    public static final int MIN_MS = 80, MAX_MS = 1000;
    /** 去首尾静音的门限（16-bit 幅度）。 */
    public static final int SILENCE_THR = 300;

    /** 候选：kind = uri（content:// 或 file://，如 RingtoneManager 的铃声）/ file（绝对路径）/ raw（包名 + res/raw 名）。 */
    public static final class Candidate {
        public final String kind, pkg, name;

        public Candidate(String kind, String pkg, String name) {
            this.kind = kind;
            this.pkg = pkg;
            this.name = name;
        }

        public String label() {
            return "file".equals(kind) || "uri".equals(kind) ? name : pkg + "/raw/" + name;
        }

        @Override public String toString() {
            return label();
        }
    }

    /** 系统提示音候选（WakeCueLoader 按用户的选择 / 系统默认通知音填入）；空 = 只能用 App 自带提示音。 */
    private static volatile Candidate[] sCandidates = new Candidate[0];

    public static void setCandidates(Candidate[] c) {
        sCandidates = c == null ? new Candidate[0] : c.clone();
    }

    public static Candidate[] candidates() {
        return sCandidates.clone();
    }

    /** 解码器（Android 实现在 WakeCueLoader；单测注入假实现）。返回单声道 16-bit PCM + 采样率；拿不到返回 null。 */
    public interface Decoder {
        Decoded decode(Candidate c);
    }

    public static final class Decoded {
        public final short[] pcm;
        public final int sampleRate;

        public Decoded(short[] pcm, int sampleRate) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
        }
    }

    private static volatile Source sPref = Source.A;   // OneSay：默认自带合成音；系统提示音为可选
    private static volatile short[] sVendor;      // Earcon.SAMPLE_RATE 单声道，已去首尾静音
    private static volatile String sVendorFrom;
    private static volatile int sVersion;

    public static Source parse(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("a") || s.equals("chime_a") || s.equals("方案a")) return Source.A;
        if (s.equals("legacy") || s.equals("old")) return Source.LEGACY;
        if (s.equals("system") || s.equals("vendor")) return Source.SYSTEM;   // vendor = 旧 prefs 值，兼容
        return Source.A;
    }

    public static String key(Source s) {
        return s == Source.A ? "a" : s == Source.LEGACY ? "legacy" : "system";
    }

    public static Source pref() {
        return sPref;
    }

    public static synchronized void setPref(Source s) {
        if (s == null) s = Source.A;
        if (s != sPref) {
            sPref = s;
            sVersion++;
        }
    }

    /** 实际在用的来源（选了系统提示音但不可用 → A）。 */
    public static Source effective() {
        Source p = sPref;
        return p == Source.SYSTEM && sVendor == null ? Source.A : p;
    }

    /** 系统提示音出处（没加载 = null）。 */
    public static String vendorFrom() {
        return sVendorFrom;
    }

    public static int version() {
        return sVersion;
    }

    /** 当前要播的 PCM（{@link Earcon#SAMPLE_RATE}，单声道）。 */
    public static short[] pcm() {
        switch (effective()) {
            case SYSTEM: return sVendor;
            case LEGACY: return Earcon.legacyChime(Earcon.SAMPLE_RATE);
            default: return Earcon.chimeA(Earcon.SAMPLE_RATE);
        }
    }

    /** 回声门用：当前提示音按 16 kHz 的波形（A / 旧版直接按 16 kHz 合成，系统提示音重采样）。 */
    public static short[] at(int sampleRate) {
        switch (effective()) {
            case SYSTEM: return resample(sVendor, Earcon.SAMPLE_RATE, sampleRate);
            case LEGACY: return Earcon.legacyChime(sampleRate);
            default: return Earcon.chimeA(sampleRate);
        }
    }

    /**
     * 按候选顺序加载系统提示音。@return 用上的候选出处；都不行返回 null（之后 {@link #effective()} = A）。
     */
    public static synchronized String loadVendor(Decoder d) {
        String used = null;
        short[] got = null;
        if (d != null) {
            for (Candidate c : sCandidates) {
                Decoded x;
                try {
                    x = d.decode(c);
                } catch (Throwable t) {
                    x = null;
                }
                if (x == null || x.pcm == null || x.sampleRate <= 0) continue;
                short[] p = resample(trimSilence(x.pcm, SILENCE_THR), x.sampleRate, Earcon.SAMPLE_RATE);
                int ms = Earcon.durationMs(p, Earcon.SAMPLE_RATE);
                if (ms < MIN_MS) continue;
                if (ms > MAX_MS) p = clip(p, Earcon.SAMPLE_RATE * MAX_MS / 1000, Earcon.SAMPLE_RATE * 40 / 1000);
                got = p;
                used = c.label();
                break;
            }
        }
        sVendor = got;
        sVendorFrom = used;
        sVersion++;
        return used;
    }

    /** 单测 / 缓存恢复：直接给系统提示音 PCM（{@link Earcon#SAMPLE_RATE}）。 */
    public static synchronized void setVendor(short[] pcm22k, String from) {
        sVendor = pcm22k;
        sVendorFrom = pcm22k == null ? null : from;
        sVersion++;
    }

    public static String describe() {
        Source e = effective();
        short[] p = pcm();
        return "pref=" + key(sPref) + " effective=" + key(e) + (e == Source.SYSTEM ? " from=" + sVendorFrom : "")
                + (sPref == Source.SYSTEM && e != Source.SYSTEM ? "（系统提示音不可用，退回方案 A）" : "")
                + " dur=" + Earcon.durationMs(p, Earcon.SAMPLE_RATE) + "ms";
    }

    /** 截到 n 个样本，最后 fade 个样本线性渐弱（太长的系统提示音用）。 */
    public static short[] clip(short[] in, int n, int fade) {
        if (in == null || in.length <= n) return in;
        short[] o = new short[n];
        System.arraycopy(in, 0, o, 0, n);
        int f = Math.min(fade, n);
        for (int i = 0; i < f; i++) {
            int k = n - f + i;
            o[k] = (short) (o[k] * (f - i) / (double) f);
        }
        return o;
    }

    /** 去掉首尾低于门限的样本（保留 5 ms 余量）。全静音返回空数组。 */
    public static short[] trimSilence(short[] pcm, int thr) {
        if (pcm == null) return new short[0];
        int a = 0, b = pcm.length - 1;
        while (a < pcm.length && Math.abs(pcm[a]) < thr) a++;
        while (b > a && Math.abs(pcm[b]) < thr) b--;
        if (a >= pcm.length) return new short[0];
        int pad = Earcon.SAMPLE_RATE * 5 / 1000;
        a = Math.max(0, a - pad);
        b = Math.min(pcm.length - 1, b + pad);
        short[] o = new short[b - a + 1];
        System.arraycopy(pcm, a, o, 0, o.length);
        return o;
    }

    /** 线性插值重采样。 */
    public static short[] resample(short[] in, int from, int to) {
        if (in == null) return null;
        if (from == to || in.length == 0) return in.clone();
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double src = (double) i * from / to;
            int k = (int) src;
            double f = src - k;
            int a = in[Math.min(k, in.length - 1)], b = in[Math.min(k + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * f);
        }
        return out;
    }

    /** 交错多声道 → 单声道（平均）。 */
    public static short[] downmix(short[] interleaved, int channels) {
        if (interleaved == null || channels <= 1) return interleaved;
        int n = interleaved.length / channels;
        short[] o = new short[n];
        for (int i = 0; i < n; i++) {
            int s = 0;
            for (int c = 0; c < channels; c++) s += interleaved[i * channels + c];
            o[i] = (short) (s / channels);
        }
        return o;
    }
}
