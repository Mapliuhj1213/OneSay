package com.onesay.assistant.v2.exec;

/**
 * [fix-1123-dexec B8] 手势导航下，护罩上的「返回 / 回桌面」手势 = 紧急接管（纯逻辑，JVM 可测）。
 *
 * <p>真机（魅族，手势导航）：执行中从屏幕左右边缘往里划（返回）、从底边往上划（回桌面）全被护罩当误触吞掉，
 * 任务停不下来 —— 以前只认三键导航栏的按键区域（NavKeys），手势导航时那块区域是空的。</p>
 *
 * <p>判定：按下点落在左 / 右边缘带或底边带里，之后①往里 / 往上移动超过阈值，或②这一串触摸被系统收走（ACTION_CANCEL，
 * 系统手势识别器抢走了手指）→ 算一次导航手势。只看真人手指：代操作自己注入时护罩已开孔，触摸根本到不了护罩。</p>
 */
public final class EdgeGesture {

    public static final String BACK = "从边缘划了返回";
    public static final String HOME = "从底边上划回桌面";

    /** 系统导航方式：Settings.Secure navigation_mode（0 = 三键，1 = 两键，2 = 全手势）。 */
    public static boolean gestureNav(int navigationMode) {
        return navigationMode == 2;
    }

    private final int mW, mH, mEdge, mBottom, mTravel;
    private int mZone;   // 0 = 不在边缘；1 = 左；2 = 右；3 = 底
    private float mX0, mY0;
    private boolean mFired;

    /**
     * @param edgePx   左右边缘带宽（约 24dp）
     * @param bottomPx 底边带高（约 32dp）
     * @param travelPx 往里划多远算手势（约 40dp）
     */
    public EdgeGesture(int screenW, int screenH, int edgePx, int bottomPx, int travelPx) {
        mW = screenW;
        mH = screenH;
        mEdge = Math.max(1, edgePx);
        mBottom = Math.max(1, bottomPx);
        mTravel = Math.max(1, travelPx);
    }

    /** 按下：返回落在哪个边缘带（0 = 不在）。 */
    public int down(float x, float y) {
        mFired = false;
        mX0 = x;
        mY0 = y;
        if (mW <= 0 || mH <= 0) return mZone = 0;
        if (y >= mH - mBottom) return mZone = 3;
        if (x <= mEdge) return mZone = 1;
        if (x >= mW - mEdge) return mZone = 2;
        return mZone = 0;
    }

    public boolean inZone() {
        return mZone != 0;
    }

    /** 移动：够远就返回 BACK / HOME（一串触摸只报一次），否则 null。 */
    public String move(float x, float y) {
        if (mZone == 0 || mFired) return null;
        boolean hit;
        if (mZone == 1) hit = x - mX0 >= mTravel;
        else if (mZone == 2) hit = mX0 - x >= mTravel;
        else hit = mY0 - y >= mTravel;
        if (!hit) return null;
        mFired = true;
        return mZone == 3 ? HOME : BACK;
    }

    /** 被系统收走（CANCEL）：按下在边缘带里 = 系统手势抢走了手指，同样算导航。 */
    public String cancel() {
        if (mZone == 0 || mFired) return null;
        mFired = true;
        return mZone == 3 ? HOME : BACK;
    }

    /** 抬起：一串触摸结束。 */
    public void up() {
        mZone = 0;
    }
}
