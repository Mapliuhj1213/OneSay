package com.onesay.assistant.v2.ui.wake;

/**
 * [R13 卡死兜底] 唤醒界面各状态的最长停留（纯函数，JVM 可测）。宿主（FloatingSheetService）在每次状态事件后只挂<b>一个</b>延时检查，
 * 到点按 {@link #verdict} 执行；不轮询、不叠加。
 *
 * <p>为什么要：新界面在 LISTENING 时边缘光 30 fps 全屏重绘、THINKING / ERROR 时挂着全屏压暗窗；只要上游有一条路径漏了收尾
 * （2026-10-13 飞行模式下「正在想」卡住就是一例），旧界面顶多是一块静止的面板，新界面却是一直挂着的全屏窗口 + 动画。
 */
public final class StageWatchdog {

    private StageWatchdog() {}

    public enum Action { NONE, TIMEOUT_ERROR, FORCE_DETACH }

    /** 聆听：从进入算（捕获窗整段上限 20 s + 余量）。 */
    public static final long LISTENING_MAX_MS = 26000L;
    /** 正在想：从最后一次事件算（云端 ASR 连 8 s + 读 ≤15 s；LLM 连 10 s + 读 15 s）。 */
    public static final long THINKING_IDLE_MS = 40000L;
    /** 作答：从最后一次流式增量算。 */
    public static final long ANSWERING_IDLE_MS = 30000L;
    /** 「没听清」/「没有网络」：错误策略 2.5 s 收起，这里只兜它漏掉的情况。 */
    public static final long ERROR_MAX_MS = 8000L;
    /** 退场动画 0.4 s（兜底 0.9 s）。 */
    public static final long DISMISSING_MAX_MS = 3000L;
    /** 形变舞台（去程 / 回程都 ≤ 1.4 s）。 */
    public static final long HANDOFF_MAX_MS = 4000L;

    /** 这个状态要不要看门（不看 = 合法地可以久留：结果 / 确认卡（自有 120 s 超时）/ 长任务 / 打字 / 岛上）。 */
    public static boolean watched(WakeStage.Stage s) {
        return deadlineMs(s, 0L, 0L) >= 0L;
    }

    /** 下一次该检查的时刻（相对时间基准同 enterAt / lastEventAt；-1 = 不看门）。 */
    public static long deadlineMs(WakeStage.Stage s, long enterAt, long lastEventAt) {
        if (s == null) return -1L;
        switch (s) {
            case LISTENING: return enterAt + LISTENING_MAX_MS;
            case THINKING: return lastEventAt + THINKING_IDLE_MS;
            case ANSWERING: return lastEventAt + ANSWERING_IDLE_MS;
            case ERROR: return enterAt + ERROR_MAX_MS;
            case DISMISSING: return enterAt + DISMISSING_MAX_MS;
            case HANDOFF: return enterAt + HANDOFF_MAX_MS;
            default: return -1L;
        }
    }

    public static Action verdict(WakeStage.Stage s, long now, long enterAt, long lastEventAt) {
        long dl = deadlineMs(s, enterAt, lastEventAt);
        if (dl < 0L || now < dl) return Action.NONE;
        switch (s) {
            case LISTENING:
            case THINKING:
            case ANSWERING:
                return Action.TIMEOUT_ERROR;
            default:
                return Action.FORCE_DETACH;
        }
    }
}
