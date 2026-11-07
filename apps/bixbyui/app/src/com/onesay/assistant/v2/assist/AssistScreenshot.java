package com.onesay.assistant.v2.assist;

import android.graphics.Bitmap;
import android.os.SystemClock;

/** ★ r2/sysux P2：系统给的助理截图，只留在内存、短 TTL（不落盘、不上传；VL 兜底按需取）。 */
public final class AssistScreenshot {

    public static final long TTL_MS = 15000L;
    private static Bitmap sShot;
    private static long sAt;

    private AssistScreenshot() {}

    static synchronized void put(Bitmap b) {
        sShot = b;
        sAt = SystemClock.elapsedRealtime();
    }

    /** 新鲜的截图（过期返回 null 并丢弃）。 */
    public static synchronized Bitmap fresh() {
        if (sShot != null && SystemClock.elapsedRealtime() - sAt > TTL_MS) sShot = null;
        return sShot;
    }

    public static synchronized long capturedAt() { return sShot == null ? 0L : sAt; }
}
