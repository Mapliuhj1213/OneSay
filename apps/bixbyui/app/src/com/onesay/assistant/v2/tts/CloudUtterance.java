package com.onesay.assistant.v2.tts;

/**
 * ★ r2/cloud-tts（第一阶段）：一句话的云端播报编排 —— 会话 + 播放器 + 超时回退。纯 Java（不碰 android.*），JVM 可测。
 *
 * <h3>回退规则（只要还没出声，任何问题都当场改走系统 TTS，整句一次）</h3>
 * <ul>
 *   <li>建连（WebSocket 升级完成）超过 {@code connectTimeoutMs}（默认 1500）→ 回退；</li>
 *   <li>从开始算超过 {@code firstAudioTimeoutMs}（默认 2500）还没收到第一段音频 → 回退；</li>
 *   <li>建连失败 / task-failed / 断开 / task-finished 却一个字节音频都没有 → 回退。</li>
 * </ul>
 * 已经出声之后中途断流：播完已收到的部分就结束，<b>不</b>补播系统 TTS（避免同一句话两种声音）。
 * {@link #cancel()}（barge-in / 关开关）：立即停播、断开会话，之后什么都不做。
 */
public final class CloudUtterance implements DashScopeTtsSession.Listener {

    /** 音频出口（PcmStreamPlayer；单测用假实现）。begin 在第一段音频前调一次。 */
    public interface Sink {
        void begin(int sampleRate);

        void write(byte[] pcm);

        /** 数据送完：播完队列里剩下的再收尾。 */
        void end();

        /** 立即停止并丢弃。 */
        void stop();
    }

    /** 回退到系统 TTS。 */
    public interface Fallback {
        void speakSystem(String text, String reason);
    }

    /** 会话工厂（单测注入桩会话）。 */
    public interface SessionFactory {
        DashScopeTtsSession create(DashScopeTtsSession.Listener l);
    }

    /** 结果回调（日志 / 计量）。 */
    public interface Observer {
        void onFinished(CloudUtterance u);
    }

    public enum Outcome { PENDING, CLOUD_OK, CLOUD_PARTIAL, FALLBACK, CANCELLED }

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 1500;
    public static final int DEFAULT_FIRST_AUDIO_TIMEOUT_MS = 2500;

    private final String mText;
    private final SessionFactory mFactory;
    private final Sink mSink;
    private final Fallback mFallback;
    private final Observer mObserver;
    private final int mConnectTimeoutMs;
    private final int mFirstAudioTimeoutMs;
    private final Object mLock = new Object();

    private DashScopeTtsSession mSession;
    private volatile boolean mConnected;
    private volatile boolean mAudioStarted;
    private volatile boolean mFinished;
    private volatile Outcome mOutcome = Outcome.PENDING;
    private volatile String mReason = "";

    /** 时间（ms，相对 start；-1 = 没发生）。 */
    public volatile long connectMs = -1, firstAudioMs = -1, totalMs = -1;
    private long mT0;

    public CloudUtterance(String text, SessionFactory factory, Sink sink, Fallback fallback, Observer observer,
                          int connectTimeoutMs, int firstAudioTimeoutMs) {
        mText = text;
        mFactory = factory;
        mSink = sink;
        mFallback = fallback;
        mObserver = observer;
        mConnectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        mFirstAudioTimeoutMs = firstAudioTimeoutMs > 0 ? firstAudioTimeoutMs : DEFAULT_FIRST_AUDIO_TIMEOUT_MS;
    }

    public String text() { return mText; }
    public Outcome outcome() { return mOutcome; }
    public String reason() { return mReason; }
    public boolean isActive() { return !mFinished; }
    public boolean audioStarted() { return mAudioStarted; }
    public DashScopeTtsSession session() { return mSession; }

    public void start() {
        mT0 = now();
        mSession = mFactory.create(this);
        mSession.start(mText);
        com.onesay.assistant.v2.util.Threads.start("tts-watch", new Runnable() {
            @Override
            public void run() {
                watchdog();
            }
        });
    }

    /** 打断：停播 + 断会话，不回退。幂等。 */
    public void cancel() {
        synchronized (mLock) {
            if (mFinished) return;
            mFinished = true;
            mOutcome = Outcome.CANCELLED;
            mReason = "cancelled";
            mLock.notifyAll();
        }
        DashScopeTtsSession s = mSession;
        if (s != null) s.cancel();
        try {
            mSink.stop();
        } catch (Throwable ignore) {
        }
        notifyObserver();
    }

    private void watchdog() {
        try {
            synchronized (mLock) {
                waitUntil(mConnectTimeoutMs, true);
                if (mFinished) return;
            }
            if (!mConnected) {
                fallback("建连超时 " + mConnectTimeoutMs + "ms");
                return;
            }
            synchronized (mLock) {
                waitUntil(mFirstAudioTimeoutMs, false);
                if (mFinished) return;
            }
            if (!mAudioStarted) fallback("首包超时 " + mFirstAudioTimeoutMs + "ms");
        } catch (InterruptedException ignore) {
        }
    }

    /** 持锁调用：等到 deadline（相对 start）或条件满足 / 结束。 */
    private void waitUntil(long deadlineMs, boolean forConnect) throws InterruptedException {
        while (!mFinished && !(forConnect ? mConnected : mAudioStarted)) {
            long left = deadlineMs - (now() - mT0);
            if (left <= 0) return;
            mLock.wait(left);
        }
    }

    // ------------------------------------------------------------------ 会话回调

    @Override
    public void onConnected(DashScopeTtsSession s) {
        synchronized (mLock) {
            if (mFinished) return;
            mConnected = true;
            connectMs = now() - mT0;
            mLock.notifyAll();
        }
    }

    @Override
    public void onAudio(DashScopeTtsSession s, byte[] pcm) {
        boolean first = false;
        synchronized (mLock) {
            if (mFinished) return;
            if (!mAudioStarted) {
                mAudioStarted = true;
                firstAudioMs = now() - mT0;
                first = true;
                mLock.notifyAll();
            }
        }
        if (first) mSink.begin(s.sampleRate());
        mSink.write(pcm);
    }

    @Override
    public void onDone(DashScopeTtsSession s) {
        if (!mAudioStarted) {
            fallback("合成结束但没有音频");
            return;
        }
        finishCloud(Outcome.CLOUD_OK, "ok");
    }

    @Override
    public void onError(DashScopeTtsSession s, String reason) {
        if (!mAudioStarted) {
            fallback(reason);
            return;
        }
        // 已出声：播完已收到的部分，不补系统 TTS
        finishCloud(Outcome.CLOUD_PARTIAL, reason);
    }

    private void finishCloud(Outcome o, String reason) {
        synchronized (mLock) {
            if (mFinished) return;
            mFinished = true;
            mOutcome = o;
            mReason = reason == null ? "" : reason;
            totalMs = now() - mT0;
            mLock.notifyAll();
        }
        try {
            mSink.end();
        } catch (Throwable ignore) {
        }
        notifyObserver();
    }

    private void fallback(String reason) {
        synchronized (mLock) {
            if (mFinished || mAudioStarted) return;
            mFinished = true;
            mOutcome = Outcome.FALLBACK;
            mReason = reason == null ? "" : reason;
            totalMs = now() - mT0;
            mLock.notifyAll();
        }
        DashScopeTtsSession s = mSession;
        if (s != null) s.cancel();
        try {
            mFallback.speakSystem(mText, mReason);
        } catch (Throwable ignore) {
        }
        notifyObserver();
    }

    private void notifyObserver() {
        if (mObserver == null) return;
        try {
            mObserver.onFinished(this);
        } catch (Throwable ignore) {
        }
    }

    private static long now() {
        return System.nanoTime() / 1000000L;
    }
}
