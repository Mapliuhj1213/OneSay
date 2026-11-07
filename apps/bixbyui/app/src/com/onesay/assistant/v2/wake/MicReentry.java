package com.onesay.assistant.v2.wake;

/** ★ device-1102 问题 2：面板 🎤 按钮重入防抖（纯 Java）。 */
public final class MicReentry {

    public static final long DEBOUNCE_MS = 400L;

    private MicReentry() {}

    /** lastAcceptedAt = 上次受理的时刻（elapsedRealtime，0 = 没有）；now 与它相距 &lt; DEBOUNCE_MS 视为重复点击。 */
    public static boolean debounced(long lastAcceptedAt, long now) {
        return lastAcceptedAt > 0L && now >= lastAcceptedAt && now - lastAcceptedAt < DEBOUNCE_MS;
    }
}
