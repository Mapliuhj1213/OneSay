package com.onesay.assistant.v2.ui.ime;

/**
 * ★ r2/kb-fix（LC-2 锁屏复发）：锁屏托底与打字的纯逻辑（JVM 单测覆盖）。
 */
public final class KbLockPolicy {

    private KbLockPolicy() {}

    /** 托底绝对上限：打字再久也不超过 10 分钟（防止黑底一直挡着锁屏）。 */
    public static final long HOLD_CAP_MS = 10 * 60 * 1000L;
    /** 每次顺延的步长。 */
    public static final long HOLD_STEP_MS = 15 * 1000L;

    /** 托底到点：面板还在用（在屏且有输入栏 / 打字中）且没到上限 → 顺延。 */
    public static boolean shouldExtend(boolean panelInUse, long shownForMs) {
        return panelInUse && shownForMs >= 0 && shownForMs < HOLD_CAP_MS;
    }

    /**
     * 托底退出后要不要收键盘 + 收面板：只有「锁屏还锁着」且「面板还在屏」时 ——
     * 这时面板已被锁屏藏住，留着只剩一个挂在锁屏上的键盘（现场照片）。已解锁 = 面板照常可见，不动。
     */
    public static boolean collapseOnHostGone(boolean keyguardLocked, boolean panelOnScreen) {
        return keyguardLocked && panelOnScreen;
    }
}
