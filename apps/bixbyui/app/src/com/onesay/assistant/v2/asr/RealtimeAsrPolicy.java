package com.onesay.assistant.v2.asr;

/**
 * ★ R14 流式识别的纯函数裁决（JVM 可测），WakeService 只按结果执行。
 *
 * <h3>主次关系</h3>
 * 流式（qwen3-asr-flash-realtime，服务端 VAD 450 ms）是主路：按下键就建连，说完 ~0.3 s 出最终文本。
 * 本地能量门 + 非流式整段识别（qwen3-asr-flash）是自动兜底：建连失败、服务端报错、中途断开、或本地已断句但流式迟迟不给结果，
 * 都回退到它；用户关掉开关（bixby_asr_cfg/realtime=false）也走它。
 *
 * <h3>熔断</h3>
 * 连续 {@link #BREAKER_FAILS} 次流式失败 → {@link #BREAKER_COOLDOWN_MS} 内不再尝试（省掉每次白等的建连），冷却后再试一次。
 */
public final class RealtimeAsrPolicy {

    private RealtimeAsrPolicy() {}

    /** 本地能量门已经断句、流式还没给最终结果时，再等它这么久；到点没来就回退非流式整段识别。 */
    public static final long LOCAL_EP_GRACE_MS = 1200L;
    /** 按下键预建的连接：这么久还没等到唤醒（比如变成了长按 / 记一记）就关掉。 */
    public static final long IDLE_CLOSE_MS = 4000L;
    /** 建连超时（Note 8 走 Wi‑Fi 到北京约 0.6–0.9 s；超过这个就别等了，直接兜底）。 */
    public static final int CONNECT_TIMEOUT_MS = 3500;
    public static final int BREAKER_FAILS = 3;
    public static final long BREAKER_COOLDOWN_MS = 5 * 60 * 1000L;

    /** 这次唤醒要不要开流式会话。 */
    public static boolean shouldUseRealtime(boolean enabled, boolean keyConfigured, boolean online, boolean breakerOpen) {
        return enabled && keyConfigured && online && !breakerOpen;
    }

    public enum OnLocalEndpoint {
        /** 流式不可用 / 已失败：照旧发非流式整段识别。 */
        FIRE_NONSTREAM,
        /** 流式还健康：先等它 {@link #LOCAL_EP_GRACE_MS}。 */
        WAIT_FOR_REALTIME
    }

    public static OnLocalEndpoint onLocalEndpoint(boolean rtHealthy) {
        return rtHealthy ? OnLocalEndpoint.WAIT_FOR_REALTIME : OnLocalEndpoint.FIRE_NONSTREAM;
    }

    public enum OnExpiry {
        /** 流式没在管：走原来的窗到点逻辑（CaptureSessionPolicy）。 */
        DEFAULT,
        /** 流式健康、但有人声证据却没出最终结果：关流式，整段送非流式。 */
        FALLBACK_NONSTREAM,
        /** 流式健康、服务端 VAD 和本地能量门都没听到人声：不发任何识别请求，直接「没听清」收尾（静音超时）。 */
        MISS_NO_SPEECH
    }

    /**
     * 窗到点时本地能量门至少要数到这么多帧人声，才算「有人说过话」。
     * ★ R14 上机（10-15）：短提示音的尾巴会漏进麦克风 —— 本地记 1 帧、服务端 VAD 也报「开始说话」并转写出「嗯」，
     *   旧判据（服务端 VAD 说过 或 本地 ≥1 帧）于是把纯静音窗整段 11.7 s 送去非流式识别。真人一句话本地 ≥7 帧。
     */
    public static final int EXPIRY_MIN_SPEECH_FRAMES = 3;

    /**
     * ★ 11-04 H（用户 17:51「录音又录不进去」，机上 logcat 17:50）：嘈杂环境开窗时冻结底噪偏高（374 / 542 → 阈值 673 / 976），
     * 正常音量说话峰值 1167–1246 只有 2 帧过线，服务端 VAD 已确认说话、已转出 7 个字，仍被当幻觉丢掉。
     * 流式最终结果的「本地人声佐证」：本地 ≥3 帧照旧放行；不足 3 帧时，服务端 VAD 本窗报过「开始说话」、本地至少 1 帧、
     * 且结果 ≥3 个字（应答音「嗯」/ 词表回吐在调用前已丢）也放行。0 帧（纯提示音尾巴 / 静音）仍按幻觉丢。
     */
    public static boolean finalCorroborated(int localSpeechFrames, boolean serverSawSpeech, int textLen) {
        if (localSpeechFrames >= EXPIRY_MIN_SPEECH_FRAMES) return true;
        return serverSawSpeech && localSpeechFrames >= 1 && textLen >= 3;
    }

    public static OnExpiry onWindowExpired(boolean rtHealthy, boolean rtSawSpeech, int localSpeechFrames) {
        // 本地几乎没听到人声 → 不管流式开没开、服务端 VAD 说什么，都按静音超时收尾，不发识别请求
        if (localSpeechFrames < EXPIRY_MIN_SPEECH_FRAMES) return OnExpiry.MISS_NO_SPEECH;
        if (!rtHealthy) return OnExpiry.DEFAULT;
        return OnExpiry.FALLBACK_NONSTREAM;
    }

    /**
     * 窗到点时还要不要续期（在 WakeService.shouldRenewCapture 的时间条件之外）：本地人声帧够数，或服务端 VAD 说「正在说」。
     * 10-15 第 1 次尝试的死局就是一帧零星人声（1/4）把窗续到 16 s。
     */
    public static boolean renewAllowed(int speechFrames, int needFrames, boolean rtSpeaking) {
        return rtSpeaking || speechFrames >= Math.max(1, needFrames);
    }

    /** 熔断器（线程安全）。 */
    public static final class Breaker {
        private int mFails;
        private long mOpenedAt;

        public synchronized boolean isOpen(long now) {
            if (mFails < BREAKER_FAILS) return false;
            if (now - mOpenedAt >= BREAKER_COOLDOWN_MS) {
                mFails = BREAKER_FAILS - 1;   // 半开：放一次试探，再失败立刻重新熔断
                return false;
            }
            return true;
        }

        public synchronized void onFailure(long now) {
            mFails++;
            if (mFails >= BREAKER_FAILS) mOpenedAt = now;
        }

        public synchronized void onSuccess() {
            mFails = 0;
            mOpenedAt = 0L;
        }

        public synchronized int fails() {
            return mFails;
        }
    }
}
