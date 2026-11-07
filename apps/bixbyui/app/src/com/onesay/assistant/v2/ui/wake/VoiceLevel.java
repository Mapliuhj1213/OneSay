package com.onesay.assistant.v2.ui.wake;

/**
 * [R13 唤醒界面 v2] 麦克风音量的只读出口（方案 §6）：EnergyGate 每个音频缓冲写一次 volatile，光晕在 onDraw 里读，
 * 不跨线程 post、不持锁、不分配。映射与包络是纯函数（JVM 可测）。
 */
public final class VoiceLevel {

    private VoiceLevel() {}

    private static volatile float sRms;
    private static volatile float sFloor = 200f;
    private static volatile long sAtMs;

    /** 采集线程调用（EnergyGate.processFrame）。 */
    public static void publish(float rms, float noiseFloor, long nowMs) {
        sRms = rms;
        sFloor = noiseFloor;
        sAtMs = nowMs;
    }

    public static float rms() {
        return sRms;
    }

    public static float floor() {
        return sFloor;
    }

    public static long at() {
        return sAtMs;
    }

    /** 读数是否新鲜（超过 300 ms 没更新 = 麦克风没在读，比如识屏 / TTS：光走「思考」形态，不伪造音量）。 */
    public static boolean fresh(long nowMs) {
        return sAtMs > 0 && nowMs - sAtMs < 300L;
    }

    /**
     * rms（16-bit PCM 均方根）→ 0..1：底噪 +3 dB 记 0，−18 dBFS（大声说话）记 1。
     */
    public static float norm(float rms, float noiseFloor) {
        double db = 20.0 * Math.log10(Math.max(rms, 1f) / 32768.0);
        double floorDb = 20.0 * Math.log10(Math.max(noiseFloor, 1f) / 32768.0) + 3.0;
        double top = -18.0;
        if (floorDb >= top - 1.0) floorDb = top - 1.0;
        double n = (db - floorDb) / (top - floorDb);
        return (float) Math.max(0.0, Math.min(1.0, n));
    }

    /** 单极点包络：attack 30 ms（起头立刻亮）、release 220 ms（停顿慢慢落）。 */
    public static final class Envelope {
        public float value;

        public float step(float target, float dtS) {
            float tau = target > value ? 0.030f : 0.220f;
            float k = (float) (1.0 - Math.exp(-Math.max(0f, dtS) / tau));
            value += (target - value) * k;
            return value;
        }
    }
}
