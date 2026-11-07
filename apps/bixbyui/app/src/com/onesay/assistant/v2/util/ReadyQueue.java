package com.onesay.assistant.v2.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 冷启动「就绪前到达的请求」排队（纯 Java，JVM 可测；时间由调用方传入）。
 *
 * <h3>为什么需要</h3>
 * <p>真机第 3 轮：服务冷启动后头两条指令丢失（triage_1.log 只有 28 字节 = logcat 空头），重发即正常。
 * 代码里有三处「就绪前到达就静默丢掉」的入口：
 * <ul>
 *   <li>{@code WakeService.requestWake}：服务实例还没建好（{@code sInstance == null}）时直接 return，没有任何日志；</li>
 *   <li>唤醒落在语音引擎启动期（KWS 解压/加载、AudioRecord 还没 startRecording）：捕获窗开着但麦克风没在录，
 *       窗口到点只能报「没听清」；</li>
 *   <li>面板服务 {@code FloatingSheetService.show/showListening}：Android 8 后台启动限制下，进程还没有前台服务时
 *       {@code startService} 被拒（App 内抛 IllegalStateException；adb {@code am startservice} 只在 stdout 打
 *       「app is in background」，被脚本 {@code >/dev/null} 吞掉）。</li>
 * </ul>
 * 本类只管「排队 → 就绪后按到达顺序补发 → 超时丢弃并说明」这一段纯逻辑；宿主负责打日志与真正执行。
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>就绪后 {@link #offer} 返回 {@link Offer#RUN_NOW}：调用方直接执行（零开销）；</li>
 *   <li>未就绪：入队（FIFO），返回 {@link Offer#QUEUED}；队列满时丢最旧的一条，返回 {@link Offer#QUEUED_DROPPED_OLDEST}
 *       （被挤掉的那条可由 {@link #takeDropped} 取出打日志）；</li>
 *   <li>{@link #markReady}：标记就绪，返回<b>未超时</b>的排队项（按到达顺序），超时项放进 {@link Drain#expired}；</li>
 *   <li>{@link #expire}：超时检查（宿主在 timeout 到点时调用）：把超过 {@code timeoutMs} 的排队项取出丢弃；
 *       {@code failOpen=true} 时同时把队列置为就绪，防止一次初始化卡死让服务永远「聋掉」。</li>
 * </ol>
 */
public final class ReadyQueue<T> {

    /** offer 的结论。 */
    public enum Offer { RUN_NOW, QUEUED, QUEUED_DROPPED_OLDEST }

    /** 一条排队项。 */
    public static final class Item<T> {
        public final T value;
        public final long enqueuedAt;

        Item(T value, long enqueuedAt) {
            this.value = value;
            this.enqueuedAt = enqueuedAt;
        }

        public long waitedMs(long now) {
            return Math.max(0, now - enqueuedAt);
        }

        @Override public String toString() {
            return String.valueOf(value) + "@" + enqueuedAt;
        }
    }

    /** markReady / expire 的结果。 */
    public static final class Drain<T> {
        /** 就绪后要补发的（按到达顺序）。 */
        public final List<Item<T>> ready;
        /** 已超时、要丢弃并告知用户的。 */
        public final List<Item<T>> expired;

        Drain(List<Item<T>> ready, List<Item<T>> expired) {
            this.ready = Collections.unmodifiableList(ready);
            this.expired = Collections.unmodifiableList(expired);
        }

        public boolean isEmpty() {
            return ready.isEmpty() && expired.isEmpty();
        }
    }

    private final long mTimeoutMs;
    private final int mCapacity;
    private final List<Item<T>> mItems = new ArrayList<Item<T>>();
    private Item<T> mDropped;
    private boolean mReady;
    private long mReadyAt = -1;

    /**
     * @param timeoutMs 排队项最长等待（超过即丢弃，不在「迟到很久」之后突然执行）
     * @param capacity  最多排几条（≥1）
     */
    public ReadyQueue(long timeoutMs, int capacity) {
        this.mTimeoutMs = Math.max(1, timeoutMs);
        this.mCapacity = Math.max(1, capacity);
    }

    public long timeoutMs() {
        return mTimeoutMs;
    }

    public synchronized boolean isReady() {
        return mReady;
    }

    /** 就绪时刻（未就绪为 -1）。 */
    public synchronized long readyAt() {
        return mReadyAt;
    }

    public synchronized int size() {
        return mItems.size();
    }

    /** 最早一条的入队时刻（空队列 -1），宿主据此安排超时检查。 */
    public synchronized long oldestAt() {
        return mItems.isEmpty() ? -1 : mItems.get(0).enqueuedAt;
    }

    public synchronized Offer offer(T value, long now) {
        if (mReady) return Offer.RUN_NOW;
        mItems.add(new Item<T>(value, now));
        if (mItems.size() > mCapacity) {
            mDropped = mItems.remove(0);
            return Offer.QUEUED_DROPPED_OLDEST;
        }
        return Offer.QUEUED;
    }

    /** 回到未就绪（例如服务被销毁、麦克风释放）：之后的 offer 重新排队。已排队项保留。 */
    public synchronized void reset() {
        mReady = false;
        mReadyAt = -1;
    }

    /** 取出（并清空）最近一次因队列满被挤掉的项；没有返回 null。 */
    public synchronized Item<T> takeDropped() {
        Item<T> d = mDropped;
        mDropped = null;
        return d;
    }

    /** 标记就绪：返回要补发的（未超时，按到达顺序）与已超时的。重复调用只在第一次有排队项。 */
    public synchronized Drain<T> markReady(long now) {
        if (!mReady) {
            mReady = true;
            mReadyAt = now;
        }
        return drain(now, true);
    }

    /**
     * 超时检查。
     * @param failOpen true = 若仍未就绪则强制置为就绪（初始化卡住时不让服务永远排队）
     * @return expired = 超时被丢弃的；ready = failOpen 打开后剩余未超时的（应立即执行）
     */
    public synchronized Drain<T> expire(long now, boolean failOpen) {
        if (failOpen && !mReady) {
            mReady = true;
            mReadyAt = now;
            return drain(now, true);
        }
        return drain(now, mReady);
    }

    private Drain<T> drain(long now, boolean takeFresh) {
        List<Item<T>> fresh = new ArrayList<Item<T>>();
        List<Item<T>> old = new ArrayList<Item<T>>();
        List<Item<T>> keep = new ArrayList<Item<T>>();
        for (Item<T> it : mItems) {
            if (now - it.enqueuedAt > mTimeoutMs) old.add(it);
            else if (takeFresh) fresh.add(it);
            else keep.add(it);
        }
        mItems.clear();
        mItems.addAll(keep);
        return new Drain<T>(fresh, old);
    }
}
