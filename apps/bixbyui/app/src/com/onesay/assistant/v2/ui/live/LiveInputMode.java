package com.onesay.assistant.v2.ui.live;

/**
 * ★ 11-04 相机对话：输入栏三态（录音 / 键盘 / 相机）的模式记录。纯 Java，JVM 单测。
 *
 * <p>相机是「临时接管」的模式：进入时记下之前是录音还是键盘，退出时原样还回去；重复进入（连点）不覆盖之前的模式。
 */
public final class LiveInputMode {

    public enum Mode { VOICE, TEXT, CAMERA }

    /** 退出相机回到「录音」态时，卡片多久后照常自动收起（不自动开麦）。 */
    public static final long RESTORE_AUTO_HIDE_MS = 8000L;

    private static final LiveInputMode INSTANCE = new LiveInputMode();

    public static LiveInputMode get() {
        return INSTANCE;
    }

    private Mode mCurrent = Mode.VOICE;
    private Mode mBefore = Mode.VOICE;

    public synchronized Mode current() {
        return mCurrent;
    }

    public synchronized boolean inCamera() {
        return mCurrent == Mode.CAMERA;
    }

    /** 进入相机。from = 当前输入模式（CAMERA / null 按录音算）。已经在相机里 → false，不覆盖。 */
    public synchronized boolean enterCamera(Mode from) {
        if (mCurrent == Mode.CAMERA) return false;
        mBefore = (from == null || from == Mode.CAMERA) ? Mode.VOICE : from;
        mCurrent = Mode.CAMERA;
        return true;
    }

    /** 退出相机，返回要回到的模式；本来就不在相机里 → 返回当前模式、不变。 */
    public synchronized Mode exitCamera() {
        if (mCurrent != Mode.CAMERA) return mCurrent;
        mCurrent = mBefore;
        return mCurrent;
    }

    /** 录音 / 键盘之间切换（相机中忽略，退出时以进入前的为准）。 */
    public synchronized void set(Mode m) {
        if (m == null || m == Mode.CAMERA || mCurrent == Mode.CAMERA) return;
        mCurrent = m;
    }

    /** 单测用。 */
    public synchronized void resetForTest() {
        mCurrent = Mode.VOICE;
        mBefore = Mode.VOICE;
    }
}
