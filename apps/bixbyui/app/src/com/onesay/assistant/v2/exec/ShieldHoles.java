package com.onesay.assistant.v2.exec;

/**
 * [fix-1123-dexec B8/B40] 护罩上的「注入孔」按引用计数叠加（纯逻辑，JVM 可测）。
 *
 * <p>真机现象：代操作自己注入的点按 / 手势（无障碍 dispatchGesture、助手注入）也走触摸管线，落到全屏护罩上就被当误触吞掉
 * （日志「护罩吞掉误触 @x,y」），步骤看着执行了、界面却没变 → 任务做不完。以前只有 FastScreenDriver 的部分路径开孔，
 * 而且孔只有一个：里层开 / 关会把外层的孔一起关掉。现在：每次注入都开孔，孔取并集，最后一个关掉才补上。</p>
 */
public final class ShieldHoles {

    private int mDepth;
    private int mL, mT, mR, mB;

    /** 开一个孔（屏幕坐标），和已开着的孔取并集。返回开完后的层数。 */
    public synchronized int open(int l, int t, int r, int b) {
        if (r <= l || b <= t) return mDepth;
        if (mDepth == 0) {
            mL = l; mT = t; mR = r; mB = b;
        } else {
            mL = Math.min(mL, l); mT = Math.min(mT, t); mR = Math.max(mR, r); mB = Math.max(mB, b);
        }
        return ++mDepth;
    }

    /** 关一层；返回剩余层数（0 = 孔已补上）。多关不会变负。 */
    public synchronized int close() {
        if (mDepth > 0) mDepth--;
        if (mDepth == 0) {
            mL = mT = mR = mB = 0;
        }
        return mDepth;
    }

    public synchronized boolean empty() {
        return mDepth == 0;
    }

    public synchronized int depth() {
        return mDepth;
    }

    /** 当前孔 {l,t,r,b}；没孔 = 全 0。 */
    public synchronized int[] rect() {
        return new int[]{mL, mT, mR, mB};
    }

    public synchronized void clear() {
        mDepth = 0;
        mL = mT = mR = mB = 0;
    }

    /** 一笔手势路径的外框 + 边距（点按 = 单点；滑动 = 两端点）。 */
    public static int[] around(int x1, int y1, int x2, int y2, int pad) {
        return new int[]{Math.min(x1, x2) - pad, Math.min(y1, y2) - pad, Math.max(x1, x2) + pad, Math.max(y1, y2) + pad};
    }
}
