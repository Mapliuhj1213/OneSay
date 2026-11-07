package com.onesay.assistant.v2.entry.keywake;

/**
 * 音量键长按检测（纯 Java，JVM 单测）：按下记时刻，自动重复的 DOWN 忽略；松开时按住 ≥ 阈值 = 长按（触发），否则 = 短按
 * （宿主照常调一次音量）。
 */
public final class LongPressDetector {
    public enum Result { NONE, SHORT, LONG }

    private final int thresholdMs;
    private long downAt = -1;

    public LongPressDetector(int thresholdMs) {
        this.thresholdMs = thresholdMs <= 0 ? KeyWake.LONG_PRESS_MS : thresholdMs;
    }

    public void onDown(long atMs) {
        if (downAt < 0) downAt = atMs;
    }

    public boolean pressed() {
        return downAt >= 0;
    }

    public Result onUp(long atMs) {
        if (downAt < 0) return Result.NONE;
        long held = atMs - downAt;
        downAt = -1;
        return held >= thresholdMs ? Result.LONG : Result.SHORT;
    }
}
