package com.onesay.assistant.v2.wake;

/**
 * ★ r2/sysux R1：麦克风「是否真的在录」的纯 Java 状态（PARTIAL_WAKE_LOCK 只在它为真时持有）。
 *
 * <p>息屏分支的原策略只看「没暂停 + 息屏」，AudioRecord 初始化失败（没授权 / release 占着麦克风）时
 * debug 也会一直息屏持锁白耗电（SCREENOFF-WAKE.md 风险 R1）。这里把「在录」定义为：
 * startRecording 之后 getRecordingState()==RECORDING 得到确认，且读循环没有连续读失败。</p>
 *
 * <ul>
 *   <li>初始化失败 / 构造前：不在录</li>
 *   <li>startRecording 成功确认：在录；失败：不在录</li>
 *   <li>暂停 stop()：不在录；恢复后 startRecording 成功才回到在录</li>
 *   <li>读循环连续 {@link #READ_FAIL_LIMIT} 次 read&lt;=0 或底层状态不是 RECORDING：不在录；之后读成功且状态为 RECORDING 自动恢复</li>
 *   <li>读循环退出 / 服务销毁：不在录</li>
 * </ul>
 * 线程：读循环写、主线程读；所有方法同步。每个变更方法返回「状态是否变化」，调用方据此刷新唤醒锁。
 */
public final class MicRecordingState {

    /** 连续读失败多少次判定为不在录（读循环失败时每次 sleep 20 ms → 约 1 s）。 */
    public static final int READ_FAIL_LIMIT = 50;

    private boolean mRecording;
    private int mReadFails;
    private String mReason = "未初始化";

    public synchronized boolean recording() { return mRecording; }

    public synchronized String reason() { return mReason; }

    /** AudioRecord 构造 / 初始化失败。 */
    public synchronized boolean onInitFailed(String why) {
        return set(false, "初始化失败" + (why == null ? "" : ": " + why));
    }

    /** startRecording 之后的确认结果（ok = getRecordingState()==RECORDSTATE_RECORDING）。 */
    public synchronized boolean onStartResult(boolean ok, String where) {
        mReadFails = 0;
        return set(ok, (ok ? "已开始录音" : "startRecording 未进入录制态") + (where == null ? "" : "(" + where + ")"));
    }

    /** 主动 stop()（暂停）或读循环退出。 */
    public synchronized boolean onStopped(String why) {
        mReadFails = 0;
        return set(false, why == null ? "已停止" : why);
    }

    /**
     * 每次 read 之后调用。
     * @param read        read() 返回值
     * @param stateRecording 底层 getRecordingState()==RECORDING（读成功时才需要准确；读失败时可传最近一次查询值）
     */
    public synchronized boolean onRead(int read, boolean stateRecording) {
        if (read > 0 && stateRecording) {
            mReadFails = 0;
            return mRecording ? false : set(true, "读取恢复");
        }
        if (!stateRecording) {
            mReadFails = 0;
            return set(false, "底层不在录制态");
        }
        if (++mReadFails >= READ_FAIL_LIMIT) {
            return set(false, "连续读失败 " + mReadFails + " 次");
        }
        return false;
    }

    private boolean set(boolean rec, String why) {
        boolean changed = rec != mRecording;
        mRecording = rec;
        mReason = why;
        return changed;
    }
}
