package com.onesay.assistant.v2.exec;

/**
 * [fix-1123-dexec B9] 用户点了「停止」= 整个任务停（纯逻辑，JVM 可测）。
 *
 * <p>真机（偶发）：点停止后约 6 秒又自己点了一下屏幕。原因两处：①界面那一步收到停止后报成「用户拒绝了这一步」，
 * 外层智能体循环照常往下走，又调了一次点界面 / 打开 App；②停止前已经发出去的动作（模型刚回来的一批步骤、点按核对里的补点）
 * 没有作废。现在：停止时记下时间点，之后这次任务里的一切代操作动作直接作废；点界面工具的结果标成「整任务取消」。</p>
 */
public final class ExecStopGate {

    private ExecStopGate() {}

    private static volatile long sStopAt = -1L;
    private static volatile String sWhy = "";

    /** 用户停止（岛上的停止、面板停止键）。 */
    public static void userStopped(String why, long nowMs) {
        sWhy = why == null ? "" : why;
        sStopAt = nowMs;
    }

    /** 这次任务（从 startMs 开始）里用户点过停止没有。 */
    public static boolean stoppedSince(long startMs) {
        long s = sStopAt;
        return s >= 0 && s >= startMs;
    }

    public static String why() {
        return sWhy;
    }

    /** 测试用。 */
    public static void reset() {
        sStopAt = -1L;
        sWhy = "";
    }
}
