package com.onesay.assistant.v2.entry.keywake;

/**
 * 双击电源键检测（纯 Java，JVM 单测）：电源键每按一次，屏幕就在亮 / 灭之间切一次，所以「两次相反的屏幕状态变化落在时间窗内」
 * = 双击。亮屏时双击 = 灭→亮；熄屏时双击 = 亮→灭。被标记 suppress 的变化（通话中距离传感器熄屏、超时熄屏）不参与配对。
 * 触发后进入一个时间窗的冷却，避免连按三下触发两次。
 */
public final class PowerDoubleDetector {
    private int window;
    private boolean has;
    private boolean lastOn;
    private long lastAt;
    private long coolUntil = Long.MIN_VALUE;

    public PowerDoubleDetector(int windowMs) {
        window = KeyWake.clampWindow(windowMs);
    }

    public void setWindow(int windowMs) {
        window = KeyWake.clampWindow(windowMs);
    }

    public int window() {
        return window;
    }

    public void reset() {
        has = false;
    }

    /**
     * @param screenOn  true = 亮屏（ACTION_SCREEN_ON），false = 熄屏（ACTION_SCREEN_OFF）
     * @param atMs      单调时钟（elapsedRealtime）
     * @param suppress  这次变化不是按电源键造成的（距离传感器 / 超时）
     * @return 是否判定为一次双击
     */
    public boolean onScreen(boolean screenOn, long atMs, boolean suppress) {
        if (suppress) {
            has = false;
            return false;
        }
        if (atMs < coolUntil) {
            has = false;
            return false;
        }
        if (has && lastOn != screenOn && atMs - lastAt >= 0 && atMs - lastAt <= window) {
            has = false;
            coolUntil = atMs + window;
            return true;
        }
        has = true;
        lastOn = screenOn;
        lastAt = atMs;
        return false;
    }
}
