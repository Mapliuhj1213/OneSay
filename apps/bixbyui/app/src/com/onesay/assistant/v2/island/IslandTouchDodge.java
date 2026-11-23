package com.onesay.assistant.v2.island;

/**
 * [fix-1123-dexec B38] 执行任务时灵动岛点不到（纯逻辑，JVM 可测）。
 *
 * <p>用户判断（真机）：岛跟状态栏重叠。状态栏窗口的层级在普通悬浮窗（TYPE_APPLICATION_OVERLAY）之上，Android 12 起
 * 这块区域的触摸先到状态栏，悬浮窗收不到 —— 执行中想点岛停止 / 展开点不动。做法：执行中（Android 12+）岛窗口的上沿不高于状态栏下沿，
 * 整个岛挪到状态栏下面；不执行时照旧贴着挖孔。代操作要点到岛所在的位置时，岛临时不接触摸（让开），点完马上恢复。</p>
 */
public final class IslandTouchDodge {

    private IslandTouchDodge() {}

    /** 这台机器、这个状态下要不要把岛挪到状态栏下面。 */
    public static boolean needBelowStatusBar(int sdk, boolean execActive, boolean fullscreen) {
        return sdk >= 31 && execActive && !fullscreen;
    }

    /** 岛窗口的上沿：要挪就不高于状态栏下沿（statusBarPx ≤ 0 = 不知道 → 不挪）。 */
    public static int windowTop(int top, int statusBarPx, boolean below) {
        if (!below || statusBarPx <= 0) return top;
        return Math.max(top, statusBarPx);
    }

    /** 注入点（l,t,r,b）和岛窗口重叠 → 岛要临时让开。 */
    public static boolean overlaps(int[] inject, int[] island) {
        if (inject == null || island == null || inject.length < 4 || island.length < 4) return false;
        if (island[2] <= island[0] || island[3] <= island[1]) return false;
        return inject[0] < island[2] && island[0] < inject[2] && inject[1] < island[3] && island[1] < inject[3];
    }
}
