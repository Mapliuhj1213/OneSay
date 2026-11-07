package com.onesay.assistant.v2.wake;

/**
 * 短唤醒提示音合成（P1-9，纯 Java，JVM 单测）。
 *
 * <p>旧唤醒反馈 = start_tone.ogg(0.42 s) + wake_ack*.wav(0.8 s)，两次 MediaPlayer prepare，
 * 实测唤醒到麦克风解除抑制约 1.6–1.7 s，期间用户说的话整段丢弃。
 * 短提示音模式改为 <b>~160 ms 的双音上扬"叮"</b>（原版 Bixby 也是短 earcon），用 AudioTrack 静态模式播放，
 * 没有解码与 prepare 开销；播放结束 + 尾音余量后立即解除抑制。
 *
 * <p>生成 16-bit 单声道 PCM。★ device-1102 起默认是方案 A（C6 → E6、240 ms、渐弱，见 {@link #twoToneDecay}）；
 * 旧的两段平顶正弦（880 Hz → 1320 Hz、160 ms）保留为 {@link #legacyChime}。
 */
public final class Earcon {

    private Earcon() {}

    public static final int SAMPLE_RATE = 22050;

    /**
     * ★ device-1102 唤醒音方案 A：C6 → E6（大三度上行），总长 240 ms，每个音快起（6 ms）+ 指数渐弱，尾音收到 0。
     * 替换旧的 160 ms 纯正弦 880 → 1320 Hz（纯五度、两段等长平顶，听感偏「电子哔」）。运行时合成，不加资源文件。
     */
    public static final int DEFAULT_MS = 240;
    public static final double C6 = 1046.50;
    public static final double E6 = 1318.51;
    /** 第一个音占总长的比例（C6 短、E6 长，落在高音上渐弱）。 */
    public static final double FIRST_SHARE = 0.4;

    /** 旧版参数（对比试听 / 回滚用）。 */
    public static final int LEGACY_MS = 160;
    public static final double LEGACY_F1 = 880.0, LEGACY_F2 = 1320.0;

    /** 当前短提示音：★ int/1102 起按 {@link WakeCue} 的来源（默认厂商原声，找不到退回方案 A）。 */
    public static short[] wakeChime() {
        short[] p = WakeCue.pcm();
        return p != null && p.length > 0 ? p : chimeA(SAMPLE_RATE);
    }

    /** 方案 A 按任意采样率合成（回声门按 16 kHz 现算掩码用）。 */
    public static short[] chimeA(int sampleRate) {
        return twoToneDecay(sampleRate, DEFAULT_MS, C6, E6, 0.35);
    }

    /** 旧版 160 ms 880 → 1320 Hz。 */
    public static short[] legacyChime(int sampleRate) {
        return twoTone(sampleRate, LEGACY_MS, LEGACY_F1, LEGACY_F2, 0.35);
    }

    /**
     * 两音渐弱：第一音占 {@link #FIRST_SHARE}，每音 6 ms 升余弦起音 + 指数衰减（第一音轻衰减到 ~60%，第二音衰减到 ~8%），
     * 每音末尾 10 ms 余弦收尾到 0（交界与结尾都无爆音）。相位连续累加。
     */
    public static short[] twoToneDecay(int sampleRate, int totalMs, double f1, double f2, double volume) {
        int n = (int) ((long) sampleRate * totalMs / 1000);
        short[] out = new short[n];
        int split = (int) Math.round(n * FIRST_SHARE);
        int attack = Math.max(1, sampleRate * 6 / 1000);
        int release = Math.max(1, sampleRate * 10 / 1000);
        double amp = Math.max(0, Math.min(1, volume)) * Short.MAX_VALUE;
        double phase = 0;
        for (int i = 0; i < n; i++) {
            boolean first = i < split;
            int segStart = first ? 0 : split;
            int segLen = first ? split : n - split;
            int k = i - segStart;
            double x = k / (double) segLen;
            double env = Math.exp(-(first ? 0.5 : 2.5) * x);
            if (k < attack) env *= 0.5 - 0.5 * Math.cos(Math.PI * k / attack);
            int left = segLen - 1 - k;
            if (left < release) env *= 0.5 - 0.5 * Math.cos(Math.PI * left / release);
            phase += 2 * Math.PI * (first ? f1 : f2) / sampleRate;
            out[i] = (short) Math.round(Math.sin(phase) * env * amp);
        }
        return out;
    }

    /**
     * @param totalMs 总时长（两段各占一半）
     * @param volume  峰值幅度 0..1
     */
    public static short[] twoTone(int sampleRate, int totalMs, double f1, double f2, double volume) {
        int n = (int) ((long) sampleRate * totalMs / 1000);
        short[] out = new short[n];
        int half = n / 2;
        int fade = Math.max(1, sampleRate * 8 / 1000);
        double amp = Math.max(0, Math.min(1, volume)) * Short.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            boolean first = i < half;
            int segStart = first ? 0 : half;
            int segLen = first ? half : n - half;
            int k = i - segStart;
            double env = 1.0;
            if (k < fade) env = 0.5 - 0.5 * Math.cos(Math.PI * k / fade);
            else if (segLen - k < fade) env = 0.5 - 0.5 * Math.cos(Math.PI * (segLen - k) / fade);
            double f = first ? f1 : f2;
            double v = Math.sin(2 * Math.PI * f * i / sampleRate) * env * amp;
            out[i] = (short) Math.round(v);
        }
        return out;
    }

    /** 时长（毫秒）。 */
    public static int durationMs(short[] pcm, int sampleRate) {
        return pcm == null ? 0 : (int) ((long) pcm.length * 1000 / sampleRate);
    }
}
