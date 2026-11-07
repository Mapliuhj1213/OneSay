package com.onesay.assistant.v2.ui;

/**
 * 面板（卡片窗 mSheetView）「在不在屏幕上」的唯一真相 + 告诉灵动岛的唯一出口。
 *
 * <p>FloatingSheetService 只在卡片窗<b>真正</b> addView / removeView 的两个汇合点（{@code addSheetWindow} /
 * {@code removeSheetWindow}）调用 {@link #attached} / {@link #removed}；所有关闭路径（HIDE_SHEET 立即销毁、✕、
 * 自动收起、返回键、点外部、错误态到点、看门狗、LC-5 全屏回答后关闭、任务进岛、服务销毁）最终都经过 removeView，
 * 所以不用在每条路径上各补一次通知。
 *
 * <p>通知规则：
 * <ul>
 *   <li>状态改完后合并成一次 post 回调（主线程），回调里读<b>最终</b>状态，与上次报给岛的不同才报 —— 同一次关闭
 *       无论经过几层（hideSheet → 动画 → 兜底 → detach / onDestroy 再收一次）都只报一次 {@code panel(false)}；
 *       同一轮里「摘了又挂」（收起动画中途来了新唤醒）最终状态没变，就不报，岛上不抖。</li>
 *   <li>{@link #flushNow()}：服务销毁时同步报掉（岛随后就要拆，不能等 post）。</li>
 * </ul>
 * 纯 Java，JVM 单测（PanelPresenceTest）。
 */
public final class PanelPresence {

    /** 报给岛（S1Hooks.onPanelVisibility）。 */
    public interface Sink {
        void onPanelVisibility(boolean on, String why);
    }

    /** 主线程投递（Android 上是 Handler；单测里手动跑）。 */
    public interface Poster {
        void post(Runnable r);

        void cancel(Runnable r);
    }

    private final Sink mSink;
    private final Poster mPoster;

    private boolean mAttached;
    private boolean mReported;
    private boolean mScheduled;
    private String mWhy = "";
    private int mReports;

    private final Runnable mFlush = new Runnable() {
        @Override public void run() {
            flush();
        }
    };

    public PanelPresence(Sink sink, Poster poster) {
        mSink = sink;
        mPoster = poster;
    }

    /** 卡片窗已 addView（唯一调用点：FloatingSheetService.addSheetWindow）。 */
    public void attached(String why) {
        set(true, why);
    }

    /** 卡片窗已 removeView（唯一调用点：FloatingSheetService.removeSheetWindow）。重复调用无害。 */
    public void removed(String why) {
        set(false, why);
    }

    private void set(boolean on, String why) {
        boolean post;
        synchronized (this) {
            mAttached = on;
            mWhy = why == null ? "" : why;
            post = !mScheduled;
            mScheduled = true;
        }
        if (post) mPoster.post(mFlush);
    }

    /** 立即把最终状态报掉（服务销毁前）。 */
    public void flushNow() {
        mPoster.cancel(mFlush);
        flush();
    }

    void flush() {
        boolean on;
        String why;
        synchronized (this) {
            mScheduled = false;
            if (mAttached == mReported) return;
            mReported = mAttached;
            on = mAttached;
            why = mWhy;
            mReports++;
        }
        mSink.onPanelVisibility(on, why);
    }

    /** 卡片窗此刻挂在 WindowManager 上（收起动画进行中也算，直到真的 removeView）。 */
    public synchronized boolean onScreen() {
        return mAttached;
    }

    /** 最近一次报给岛的状态。 */
    public synchronized boolean reported() {
        return mReported;
    }

    /** 一共报过几次（单测 / dump）。 */
    public synchronized int reports() {
        return mReports;
    }

    public synchronized String dump() {
        return "attached=" + mAttached + " reported=" + mReported + " pending=" + mScheduled + " reports=" + mReports
                + " why=" + mWhy;
    }
}
