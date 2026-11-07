package com.onesay.assistant.v2.tts;

/**
 * ★ 综合包（cloud-tts × sysux）：一句云端播报的进度闸门 —— 把 PcmStreamPlayer 的「真正出声 / 播完 / 被停」
 * 翻译成 sysux 的 TtsSpeaker.fireStart / fireDone / fireSkipped，且每句<b>只报一次</b>。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>{@link #audible()}：AudioTrack 即将 play（开头 250ms 静音之前）→ onStart（WakeService 申请 duck 焦点 + 打断检测）。</li>
 *   <li>{@link #finished(boolean, String)}：播放线程收尾。出过声 → onDone(stopped)；没出过声且不是被停 → onSilent
 *       （AudioTrack 初始化失败之类：让整轮马上结束，不用等 30s 焦点看门狗）；没出过声且被停 → 什么都不报（打断者接管）。</li>
 *   <li>{@link #cancel()}：新一句顶掉 / stopAll —— 同步报 onDone(true)（若已出声），之后播放线程迟到的收尾被吞掉，
 *       不会在下一句开始后再来一个「被打断」把新一句的打断检测关掉。</li>
 * </ul>
 *
 * <h3>音频焦点归属（明确先后，不互相抢）</h3>
 * sysux 的 {@link com.onesay.assistant.v2.wake.AssistAudioFocus} 状态机（WakeService 注册了 SpeechListener 时）统一管整轮焦点：
 * onStart → REQUEST_DUCK，播完 → 续听 TRANSIENT 或 ABANDON。此时播放器<b>不</b>自己申请焦点（否则两把请求、播放器
 * 播完 abandon 会把状态机持有的焦点语义打乱）。只有没有状态机（WakeService 没跑）时播放器才自己申请 / 归还 MAY_DUCK。
 */
public final class SpeechProgressGate {

    public interface Out {
        void onStart();

        void onDone(boolean interrupted);

        void onSilent(String why);
    }

    private final Out mOut;
    private boolean mStarted;
    private boolean mDone;

    public SpeechProgressGate(Out out) {
        mOut = out;
    }

    /** 有状态机（listener 已注册）→ 播放器不自己管焦点。 */
    public static boolean playerOwnsFocus(boolean turnFocusManagerPresent) {
        return !turnFocusManagerPresent;
    }

    public synchronized boolean started() { return mStarted; }

    public synchronized boolean done() { return mDone; }

    public void audible() {
        synchronized (this) {
            if (mStarted || mDone) return;
            mStarted = true;
        }
        mOut.onStart();
    }

    public void finished(boolean stopped, String err) {
        boolean started;
        synchronized (this) {
            if (mDone) return;
            mDone = true;
            started = mStarted;
        }
        if (started) mOut.onDone(stopped);
        else if (!stopped) mOut.onSilent(err == null || err.length() == 0 ? "云端播放没出声" : err);
    }

    public void cancel() {
        finished(true, null);
    }
}
