package com.onesay.assistant.v2.net;

/**
 * ★ R13b 离线兜底（用户 2026-10-14 修订）：没网不再「按着头退回去」，而是直接打开网络设置。
 *
 * <p>纯函数，便于 JVM 测试：
 * <ul>
 *   <li>只在用户<b>真的说了话</b>（断句/收窗时判定没网）才打开 —— 离线误唤醒不会凭空弹设置页；</li>
 *   <li>节流 {@link #THROTTLE_MS}：连续唤醒不会反复把设置页顶上来；</li>
 *   <li>模式识别（用户 2026-10-14 定）：飞行模式开着 → 飞行模式开关页；没开 → Wi‑Fi 设置页。移动数据不管。</li>
 * </ul>
 */
public final class OfflineSettingsPolicy {
    private OfflineSettingsPolicy() {}

    public static final long THROTTLE_MS = 10_000L;
    /** 「没有网络 · 正在打开网络设置」卡片停留多久再跳转。 */
    public static final long HANDOFF_DELAY_MS = 700L;

    public enum Target { AIRPLANE, WIFI }

    public static boolean shouldOpen(long nowMs, long lastOpenMs) {
        return lastOpenMs <= 0L || nowMs - lastOpenMs >= THROTTLE_MS || nowMs < lastOpenMs;
    }

    public static Target target(boolean airplaneOn) {
        return airplaneOn ? Target.AIRPLANE : Target.WIFI;
    }
}
