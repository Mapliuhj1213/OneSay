package com.onesay.assistant.v2.wake;

/**
 * ★ r2/sysux P1-3 说话打断播报（保守版 v1）。纯 Java，JVM 可测；WakeService 读循环在「正在播报且本轮允许打断」时逐块喂 RMS。
 *
 * <h3>判据（三条同时满足才算人声，连续 {@code minFrames} 块才触发）</h3>
 * <ol>
 *   <li>RMS ≥ 绝对下限 {@code absMin}；</li>
 *   <li>RMS ≥ 底噪 × {@code floorRatio}；</li>
 *   <li>RMS ≥ 回声基线 × {@code echoRatio}。回声基线 = 播报开头 {@code calibrateMs} 内（AEC 后）麦克风电平的最大值，
 *       之后用没超阈值的块慢慢跟踪（只升不快降），播报音量变大不会被当成人声。</li>
 * </ol>
 * 标定期内永不触发；每次播报最多触发一次。宁可漏打断（用户还能喊唤醒词打断，已有），不可被自己的声音打断。
 */
public final class BargeInDetector {

    public static final float DEF_ABS_MIN = 1200f;
    public static final float DEF_FLOOR_RATIO = 4f;
    public static final float DEF_ECHO_RATIO = 2.5f;
    public static final int DEF_MIN_FRAMES = 3;          // 3 × 100 ms
    public static final long DEF_CALIBRATE_MS = 500;

    private final float mAbsMin, mFloorRatio, mEchoRatio;
    private final int mMinFrames;
    private final long mCalibrateMs;

    private boolean mActive;
    private boolean mFired;
    private long mStartAt;
    private float mFloor;
    private float mEcho;
    private int mRun;

    public BargeInDetector() {
        this(DEF_ABS_MIN, DEF_FLOOR_RATIO, DEF_ECHO_RATIO, DEF_MIN_FRAMES, DEF_CALIBRATE_MS);
    }

    public BargeInDetector(float absMin, float floorRatio, float echoRatio, int minFrames, long calibrateMs) {
        mAbsMin = absMin;
        mFloorRatio = floorRatio;
        mEchoRatio = echoRatio;
        mMinFrames = Math.max(2, minFrames);
        mCalibrateMs = Math.max(200, calibrateMs);
    }

    /** 播报开始。noiseFloor = 播报前的底噪。 */
    public synchronized void start(long now, float noiseFloor) {
        mActive = true;
        mFired = false;
        mStartAt = now;
        mFloor = Math.max(0f, noiseFloor);
        mEcho = 0f;
        mRun = 0;
    }

    public synchronized void stop() {
        mActive = false;
        mRun = 0;
    }

    public synchronized boolean active() { return mActive; }

    public synchronized float threshold() {
        return Math.max(mAbsMin, Math.max(mFloor * mFloorRatio, mEcho * mEchoRatio));
    }

    /** @return true = 这一块触发打断（每次播报只返回一次 true）。 */
    public synchronized boolean offer(long now, float rms) {
        if (!mActive || mFired) return false;
        if (now - mStartAt < mCalibrateMs) {
            if (rms > mEcho) mEcho = rms;
            return false;
        }
        float thr = threshold();
        if (rms >= thr) {
            if (++mRun >= mMinFrames) {
                mFired = true;
                mActive = false;
                return true;
            }
        } else {
            mRun = 0;
            if (rms > mEcho) mEcho = mEcho * 0.7f + rms * 0.3f;   // 跟踪回声变大（慢升）
        }
        return false;
    }
}
