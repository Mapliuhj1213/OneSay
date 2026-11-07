package com.onesay.assistant.v2.ui.wake;

/**
 * ★ r2/input（2026-10-24）「还在听 / 快要发送了」的倒计时环进度（和 {@link VoiceLevel} 同一种进程内静态黑板）。
 * WakeService 读循环每帧写（{@code UtteranceEndpointer.Verdict.ring}），OrbView 画环；超过 {@link #STALE_MS} 没更新 = 0（不画）。
 * 0 = 正在说 / 刚停顿（正常换气不打扰）；0→1 = 尾静音从 0.4 s 走到「判定说完」那一刻。
 */
public final class ListenProgress {

    public static final long STALE_MS = 400L;

    private static volatile float sValue;
    private static volatile long sAt;

    private ListenProgress() {}

    public static void publish(float v, long nowMs) {
        sValue = v < 0f ? 0f : (v > 1f ? 1f : v);
        sAt = nowMs;
    }

    public static void clear() {
        sValue = 0f;
        sAt = 0L;
    }

    /** 当前进度（过期 = 0）。 */
    public static float get(long nowMs) {
        long at = sAt;
        if (at <= 0L || nowMs - at > STALE_MS || nowMs < at) return 0f;
        return sValue;
    }
}
