package com.onesay.assistant.v2.ui.wake;

/**
 * 流式回答每个 token 都整轮 v2Render（Markdown 重排 + setText + 滚到底）。
 * 改成 ≥{@link #MIN_GAP_MS} 合并刷新：第一块立刻上屏；之后间隔不到 50 ms 的只记下最新文本、排一次尾刷新；终稿直接上屏并取消尾刷新。
 * 纯 Java（时间由调用方给），JVM 可测。
 */
public final class StreamThrottle {
    public static final long MIN_GAP_MS = 50L;

    private long mLastFlush = Long.MIN_VALUE;
    private boolean mPending;

    /** 新的一块到了：返回 0 = 现在就刷；&gt;0 = 这么多 ms 后刷（已经排过尾刷新的返回 -1，不用再排）。 */
    public synchronized long offer(long now) {
        if (mLastFlush == Long.MIN_VALUE || now - mLastFlush >= MIN_GAP_MS || now < mLastFlush) {
            mLastFlush = now;
            mPending = false;
            return 0L;
        }
        if (mPending) return -1L;
        mPending = true;
        return MIN_GAP_MS - (now - mLastFlush);
    }

    /** 尾刷新执行时调用：还要不要刷（被终稿 / 复位取消了就不刷）。 */
    public synchronized boolean flushDue(long now) {
        if (!mPending) return false;
        mPending = false;
        mLastFlush = now;
        return true;
    }

    /** 终稿 / 新一轮：清状态，下一块立刻上屏。 */
    public synchronized void reset() {
        mLastFlush = Long.MIN_VALUE;
        mPending = false;
    }

    public synchronized boolean pending() {
        return mPending;
    }
}
