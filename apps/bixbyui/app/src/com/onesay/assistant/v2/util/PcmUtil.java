package com.onesay.assistant.v2.util;

/**
 * 16-bit 单声道 PCM 小工具（收敛 asr 通道里各自计算"字节/秒、上限、截尾"的代码）。
 * 纯 Java，无 android 依赖。
 */
public final class PcmUtil {

    private PcmUtil() {}

    /** 16-bit mono：每秒字节数 = 采样率 × 2。 */
    public static int bytesPerSecond(int sampleRate) {
        return sampleRate * 2;
    }

    /** 给定时长上限对应的字节数。 */
    public static int maxBytes(int sampleRate, int maxSeconds) {
        return bytesPerSecond(sampleRate) * maxSeconds;
    }

    /** 是否超过时长上限。 */
    public static boolean exceeds(byte[] pcm, int sampleRate, int maxSeconds) {
        return pcm != null && pcm.length > maxBytes(sampleRate, maxSeconds);
    }

    /**
     * 截取前 {@code maxSeconds} 秒（未超出时原样返回同一数组，不拷贝）。
     * 截断点向下对齐到 2 字节，避免切开一个采样。
     */
    public static byte[] clip(byte[] pcm, int sampleRate, int maxSeconds) {
        if (pcm == null) return new byte[0];
        int max = maxBytes(sampleRate, maxSeconds) & ~1;
        if (pcm.length <= max) return pcm;
        byte[] out = new byte[max];
        System.arraycopy(pcm, 0, out, 0, max);
        return out;
    }

    /** 时长（整秒，向下取整）。 */
    public static int seconds(byte[] pcm, int sampleRate) {
        if (pcm == null || sampleRate <= 0) return 0;
        return pcm.length / bytesPerSecond(sampleRate);
    }
}
