package com.onesay.assistant.v2.ui.console;

/**
 * [console R12] 开发者模式开关（方案 §5 / D5）：关于页版本号连点 7 次打开；debug 包默认开（没设过时）。
 * 纯 Java，JVM 可测；存储在 MainActivity 侧（prefs {@code bixby_console/dev_mode}）。
 */
public final class DevMode {

    public static final int TAPS = 7;
    /** 两次点击间隔超过这个就重新数（防止隔很久的误触累加）。 */
    public static final long RESET_GAP_MS = 3000L;
    /** 剩这么多次时开始提示「再点 N 次」（和 Android 系统一样，前几下不吭声）。 */
    public static final int HINT_FROM = 4;

    private int mCount;
    private long mLastAt = -1L;

    /** 是否处于开发者模式：显式设过就听设置；没设过 → debug 包开、release 包关。 */
    public static boolean isOn(boolean debugBuild, Boolean pref) {
        if (pref != null) return pref.booleanValue();
        return debugBuild;
    }

    /**
     * 点一次版本号。返回还差几次（0 = 这一下刚好打开）；已经开着返回 -1。
     */
    public int tap(long now, boolean alreadyOn) {
        if (alreadyOn) {
            mCount = 0;
            return -1;
        }
        if (mLastAt >= 0 && now - mLastAt > RESET_GAP_MS) mCount = 0;
        mLastAt = now;
        mCount++;
        int left = TAPS - mCount;
        if (left <= 0) {
            mCount = 0;
            return 0;
        }
        return left;
    }

    /** 给用户看的提示（null = 不提示）。 */
    public static String hint(int left) {
        if (left < 0) return "已经处于开发者模式";
        if (left == 0) return "已打开开发者模式（首页最下面「开发者」里）";
        if (left <= HINT_FROM) return "再点 " + left + " 次进入开发者模式";
        return null;
    }
}
