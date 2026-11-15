package com.onesay.assistant.v2.a11ylabel;

/**
 * ★ a11y-labels（社区借鉴 · 屏幕输入独占租约）：同一时刻只有一个任务能往屏幕上点 / 输入（纯 Java，JVM 全测）。
 *
 * <p>思路借鉴 DroidBridge（https://github.com/zephyr7030/DroidBridge ，Apache-2.0）的「屏幕输入独占租约」：
 * 语音任务、定时任务、编号点选谁先拿到租约谁用屏幕，别人拿不到就如实说「屏幕正被 xx 占用」，不抢着点；
 * 租约带有效期，持有方崩了 / 忘了释放，到期自动作废（不会一直锁死）；持有方可以续期。代码按思路自己写，没有复制其源码。
 */
public final class ScreenLease {

    public interface Clock {
        long now();
    }

    public static final Clock SYSTEM = new Clock() {
        @Override public long now() {
            return System.currentTimeMillis();
        }
    };

    /** 手机操作任务默认租期（任务本身有预算上限，这里只防「异常没释放」）。 */
    public static final long TASK_TTL_MS = 15 * 60 * 1000L;
    /** 编号点选这类一下就完的动作。 */
    public static final long SHORT_TTL_MS = 10 * 1000L;

    /** 一张租约。 */
    public static final class Lease {
        public final String owner;
        final long token;
        long expiresAt;

        Lease(String owner, long token, long expiresAt) {
            this.owner = owner;
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    private static final ScreenLease GLOBAL = new ScreenLease(SYSTEM);

    public static ScreenLease get() {
        return GLOBAL;
    }

    private final Clock mClock;
    private Lease mHeld;
    private long mNext = 1;
    /** 统计：拒绝次数、到期作废次数。 */
    public int refused, expired;

    public ScreenLease(Clock clock) {
        mClock = clock == null ? SYSTEM : clock;
    }

    /** 拿租约；别人持有且没到期 → null。 */
    public synchronized Lease tryAcquire(String owner, long ttlMs) {
        long now = mClock.now();
        if (mHeld != null && now >= mHeld.expiresAt) {
            mHeld = null;
            expired++;
        }
        if (mHeld != null) {
            refused++;
            return null;
        }
        mHeld = new Lease(owner == null ? "?" : owner, mNext++, now + Math.max(1, ttlMs));
        return mHeld;
    }

    /** 续期；租约已经不是自己的（到期被别人拿走）→ false。 */
    public synchronized boolean renew(Lease l, long ttlMs) {
        if (!holds(l)) return false;
        mHeld.expiresAt = mClock.now() + Math.max(1, ttlMs);
        return true;
    }

    /** 释放（只放自己的；放别人的 / 已到期的无事发生）。 */
    public synchronized void release(Lease l) {
        if (l != null && mHeld != null && mHeld.token == l.token) mHeld = null;
    }

    public synchronized boolean holds(Lease l) {
        return l != null && mHeld != null && mHeld.token == l.token && mClock.now() < mHeld.expiresAt;
    }

    /** 当前持有方（没人 / 已到期 → null）。 */
    public synchronized String holder() {
        if (mHeld == null || mClock.now() >= mHeld.expiresAt) return null;
        return mHeld.owner;
    }

    /** 拿不到时给用户的一句话。 */
    public String busySay() {
        String h = holder();
        return h == null ? "" : "屏幕正被「" + h + "」占用，等它做完再来。";
    }
}
