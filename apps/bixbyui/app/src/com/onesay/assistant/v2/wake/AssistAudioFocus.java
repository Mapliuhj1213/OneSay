package com.onesay.assistant.v2.wake;

/**
 * ★ r2/sysux P1-2：一整轮对话的音频焦点状态机（纯 Java，JVM 可测；Android 壳在 WakeService 里按 {@link Action} 执行）。
 *
 * <pre>
 * IDLE ──收音开始──▶ LISTENING（GAIN_TRANSIENT：暂停媒体，麦克风干净）
 * LISTENING ──拿到指令──▶ THINKING（保持，不再像旧代码那样一拿到指令就归还）
 * THINKING / LISTENING ──开始播报──▶ SPEAKING（GAIN_TRANSIENT_MAY_DUCK：媒体压低；duckWhileSpeaking=false 时保持暂停）
 * SPEAKING ──播完、开续听窗──▶ LISTENING（重新 GAIN_TRANSIENT）
 * 任何状态 ──整轮结束（面板收起 / 续听超时没人说 / 没听清 / 任务进岛 / 用户取消 / 无播报的结果已出）──▶ IDLE（归还）
 * </pre>
 * 别的 App 抢走焦点（LOSS）：记下，不再自己恢复；整轮结束照常 abandon（幂等）。
 */
public final class AssistAudioFocus {

    public enum State { IDLE, LISTENING, THINKING, SPEAKING }

    /** 壳层要执行的动作。 */
    public enum Action {
        NONE,
        /** requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT) */
        REQUEST_TRANSIENT,
        /** requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) */
        REQUEST_DUCK,
        /** abandonAudioFocus */
        ABANDON
    }

    private State mState = State.IDLE;
    /** 当前持有的请求类型（NONE = 没持有）。 */
    private Action mHeld = Action.NONE;
    private boolean mLost;
    private final boolean mDuckWhileSpeaking;

    public AssistAudioFocus(boolean duckWhileSpeaking) {
        mDuckWhileSpeaking = duckWhileSpeaking;
    }

    public synchronized State state() { return mState; }

    public synchronized boolean held() { return mHeld != Action.NONE; }

    public synchronized boolean lost() { return mLost; }

    /** 开收音窗（唤醒 / 续听 / ✦ 语音）。 */
    public synchronized Action onListen() {
        mState = State.LISTENING;
        return want(Action.REQUEST_TRANSIENT);
    }

    /** 拿到指令（语音）：保持焦点进入思考。没持有（例如文字指令）不主动申请。 */
    public synchronized Action onCommand() {
        if (mState == State.IDLE) return Action.NONE;
        mState = State.THINKING;
        return Action.NONE;
    }

    /** 开始播报。 */
    public synchronized Action onSpeakStart() {
        mState = State.SPEAKING;
        return want(mDuckWhileSpeaking ? Action.REQUEST_DUCK : Action.REQUEST_TRANSIENT);
    }

    /** 播报结束但不开续听窗（例如文字发起的回合、续听关闭）= 整轮结束。 */
    public synchronized Action onSpeakDoneNoFollowUp() {
        return onTurnEnd();
    }

    /** 整轮结束：归还。 */
    public synchronized Action onTurnEnd() {
        mState = State.IDLE;
        boolean wasLost = mLost;
        mLost = false;
        if (mHeld == Action.NONE && !wasLost) return Action.NONE;   // 被抢走过也 abandon 一次（幂等，清掉系统里可能残留的请求）
        mHeld = Action.NONE;
        return Action.ABANDON;
    }

    /** 系统回调：焦点被别的 App 永久拿走（AUDIOFOCUS_LOSS）。 */
    public synchronized void onLoss() {
        mLost = true;
        mHeld = Action.NONE;   // 系统已把我们移出焦点栈
    }

    private Action want(Action a) {
        mLost = false;
        if (mHeld == a) return Action.NONE;
        mHeld = a;
        return a;
    }
}
