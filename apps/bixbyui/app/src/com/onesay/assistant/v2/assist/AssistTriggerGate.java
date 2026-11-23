package com.onesay.assistant.v2.assist;

/**
 * ★ feat/assist-role：系统助理入口的「只唤起一次」闸门。纯 Java，JVM 可测。
 *
 * <p>同一次长按电源 / 角落上滑，系统可能先后给我们好几个回调：{@code onShow}、{@code onHandleAssist}，
 * 锁屏上还可能再来一个 {@code onLaunchVoiceAssistFromKeyguard}。每个都去唤起会让唤醒卡片闪两下、提示音响两次。
 * 两层防重：</p>
 * <ol>
 *   <li>{@link Once}：同一次显示（一次 onShow 起算）里只放行第一个；</li>
 *   <li>{@link Window}：跨回调 / 跨 session 的时间窗（{@link #WINDOW_MS}），窗内后到的一律丢掉
 *       （锁屏路径和 session 路径不共享 {@link Once}，靠它兜住）。</li>
 * </ol>
 */
public final class AssistTriggerGate {

    private AssistTriggerGate() {}

    /** 同一次按键的几个回调一般在几百毫秒内到齐；1.5 秒内的算同一次。 */
    public static final long WINDOW_MS = 1500L;

    /** 进程内共享的时间窗（session 和 VIS 锁屏回调共用）。 */
    public static final Window SHARED = new Window();

    /** 跨回调的时间窗。 */
    public static final class Window {
        private boolean mHas;
        private long mLastAt;

        /** @return true = 放行并记下时间；false = 窗内重复，丢掉（不刷新时间，避免连按把窗口一直往后推）。 */
        public synchronized boolean admit(long nowMs, long windowMs) {
            if (mHas && nowMs >= mLastAt && nowMs - mLastAt < windowMs) return false;
            mHas = true;
            mLastAt = nowMs;
            return true;
        }

        /** 单测用。 */
        public synchronized void reset() {
            mHas = false;
            mLastAt = 0L;
        }
    }

    /** 一次显示内只放行一次（每次 onShow 新建一个）。 */
    public static final class Once {
        private boolean mFired;

        /** @return true = 这次该唤起；false = 已经唤起过或窗内重复。 */
        public synchronized boolean tryFire(Window w, long nowMs) {
            if (mFired) return false;
            mFired = true;
            return w == null || w.admit(nowMs, WINDOW_MS);
        }

        public synchronized boolean fired() {
            return mFired;
        }
    }
}
