package com.onesay.assistant.v2.router;

/**
 * 「同一句话短时间内复用结果」的单槽缓存（审计 B-1）。
 *
 * <p>以前 {@code UnifiedRouter} 用两个独立的 volatile 字段（句子、结果）分别写入，两个线程几乎同时
 * 分诊两句不同的话时，读线程可能拿到「新句子 + 旧结果」。这里把句子、结果、时间合成一个不可变条目，
 * 一次 volatile 写发布 —— 读到的永远是成对的。
 *
 * <p>纯 Java，无 Android 依赖，JVM 测试覆盖（ReuseSlotTest）。
 */
public final class ReuseSlot<T> {

    private static final class Entry<T> {
        final String key;
        final T value;
        final long at;
        Entry(String key, T value, long at) { this.key = key; this.value = value; this.at = at; }
    }

    private final long mTtlMs;
    private volatile Entry<T> mEntry;

    public ReuseSlot(long ttlMs) { mTtlMs = ttlMs; }

    /** 命中（同一 key 且未过期）返回值，否则 null。 */
    public T get(String key, long nowMs) {
        Entry<T> e = mEntry;   // 一次读，句子与结果一定来自同一次写入
        if (e == null || key == null || !key.equals(e.key)) return null;
        if (nowMs - e.at >= mTtlMs || nowMs < e.at) return null;
        return e.value;
    }

    public void put(String key, T value, long nowMs) {
        mEntry = new Entry<T>(key, value, nowMs);
    }

    public void clear() { mEntry = null; }
}
