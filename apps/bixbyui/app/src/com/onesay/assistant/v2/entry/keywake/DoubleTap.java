package com.onesay.assistant.v2.entry.keywake;

/** 原始按键事件路径的双击判定（纯 Java，JVM 单测）：两次按下间隔 ≤ 时间窗 = 双击；触发后清零，三连按只算一次。 */
public final class DoubleTap {
    private final int window;
    private long last = -1;

    public DoubleTap(int windowMs) {
        window = KeyWake.clampWindow(windowMs);
    }

    public boolean onPress(long atMs) {
        if (last >= 0 && atMs - last >= 0 && atMs - last <= window) {
            last = -1;
            return true;
        }
        last = atMs;
        return false;
    }
}
