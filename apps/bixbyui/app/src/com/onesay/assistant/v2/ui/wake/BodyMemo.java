package com.onesay.assistant.v2.ui.wake;

/**
 * v2Render 每次（进度跳秒、阶段事件、芯片挂上…）都把同一段回答重新做 Markdown / 分段排版。
 * 按（原文, 排版方式）记住上一次结果，原文没变就直接复用，不再重排。纯 Java，JVM 可测。
 */
public final class BodyMemo<V> {
    public interface Maker<V> {
        V make(String text);
    }

    private String mKey;
    private int mMode = Integer.MIN_VALUE;
    private V mVal;
    private int mHits, mMisses;

    public synchronized V get(String text, int mode, Maker<V> maker) {
        if (mVal != null && mode == mMode && (text == null ? mKey == null : text.equals(mKey))) {
            mHits++;
            return mVal;
        }
        mMisses++;
        V v = maker.make(text);
        mKey = text;
        mMode = mode;
        mVal = v;
        return v;
    }

    public synchronized void clear() {
        mKey = null;
        mMode = Integer.MIN_VALUE;
        mVal = null;
    }

    public synchronized int hits() { return mHits; }
    public synchronized int misses() { return mMisses; }
}
