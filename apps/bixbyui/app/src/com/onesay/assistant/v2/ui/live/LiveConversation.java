package com.onesay.assistant.v2.ui.live;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.asr.AliyunAsrConfig;
import com.onesay.assistant.v2.asr.RealtimeAsrSession;
import com.onesay.assistant.v2.ui.TtsSpeaker;
import com.onesay.assistant.v2.vision.LiveVisionClient;
import com.onesay.assistant.v2.vision.LiveVisionPrompt;
import com.onesay.assistant.v2.wake.BargeInDetector;
import com.onesay.assistant.v2.wake.WakeService;

/**
 * ★ 11-04 相机对话的语音循环（「流式 ASR 一轮 → 最新帧 + 文本 → qwen3-vl → TTS」）。
 *
 * <ul>
 *   <li>麦克风（★ ui-K2 回声修复）：一律自己开 {@code VOICE_COMMUNICATION}，并在这个 AudioRecord 的 session 上开 AEC / NS（isAvailable 才开）；
 *       WakeService 那一路（VOICE_RECOGNITION）在相机对话期间让出麦克风（{@link WakeService#setLiveMicTap(WakeService.LiveMicTap, boolean)}，
 *       ownMic=true → 停录、不跑 KWS，挂断后原样恢复；普通唤醒录音的音源 / 参数不变）。通话模式 + 外放，TTS 走 USAGE_VOICE_COMMUNICATION，
 *       播放与采集同一条通话路由，AEC 才拿得到参考信号。</li>
 *   <li>ASR：qwen3-asr-flash-realtime 多段模式，服务端 VAD 断句，不需要按住说话；出错自动重连。</li>
 *   <li>打断：播报中流式中间结果不是回声 / 本地能量判据（{@link BargeInDetector}）→ 立刻 {@link TtsSpeaker#stopAll()}。</li>
 *   <li>隐私：PCM、帧只在内存里；日志只记状态、字数、字节数，不记用户原话 / 回答原文。</li>
 * </ul>
 */
final class LiveConversation implements RealtimeAsrSession.Listener, WakeService.LiveMicTap {

    private static final String TAG = "CameraLive";
    private static final int SR = 16000;
    private static final int CHUNK = 1600;
    private static final int MAX_ASR_FAILS = 4;

    interface Host {
        /** 主线程。 */
        void onLiveState(LiveTurnPolicy.State st, boolean muted);
        /** 主线程：用户这句话（中间 / 最终）。 */
        void onUserText(String text, boolean fin);
        /** 主线程：回答字幕。 */
        void onAnswerText(String text);
        /** 主线程：提示（错误 / 状态），null = 清掉。 */
        void onNotice(String text);
        /** 工作线程调用：当前最新一帧（内存 JPEG），没有 → null。 */
        LiveFrame grabFrame();
    }

    private final Context mApp;
    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    final LiveTurnPolicy mPolicy = new LiveTurnPolicy();
    /** 比唤醒那套更保守：扬声器外放 + AEC 不彻底时，自己的播报不能把自己打断（真机 11-04：默认参数出声 0.7 s 就自打断）。 */
    //   ★ ui-K2 ②：朗读中门槛再抬高（绝对 2600 / 回声 ×4.5 / 连续 6 帧，见 LiveEchoPolicy）
    private final BargeInDetector mBarge = new BargeInDetector(LiveEchoPolicy.GUARD_ABS_MIN, 4f, LiveEchoPolicy.GUARD_ECHO_RATIO,
            LiveEchoPolicy.GUARD_MIN_FRAMES, LiveTurnPolicy.BARGE_CALIBRATE_MS);
    /** ★ ui-K2 ①：进相机对话前的音频模式 / 外放状态（挂断时还原）。 */
    private int mSavedAudioMode = Integer.MIN_VALUE;
    private boolean mSavedSpeaker;
    private final long mStartedAt = SystemClock.elapsedRealtime();

    private volatile RealtimeAsrSession mAsr;
    private volatile boolean mEnded;
    private volatile boolean mTapped;
    private volatile Thread mOwnMic;
    private volatile boolean mOwnMicOn;
    private volatile int mAsrFails;
    private volatile float mFloor = 150f;
    private volatile boolean mTtsSeen;
    private volatile long mSpeakAt;
    private volatile boolean mBargeArmed;

    LiveConversation(Context ctx, Host host) {
        mApp = ctx.getApplicationContext();
        mHost = host;
    }

    long startedAt() { return mStartedAt; }

    void start() {
        // ★ ui-K2 ①：WakeService 让出麦克风（ownMic=true：它停录、唤醒请求照旧被吞），我们自己开 VOICE_COMMUNICATION + AEC / NS
        mTapped = WakeService.setLiveMicTap(this, true);
        enterCommRoute();
        startOwnMic();
        Log.i(TAG, "[LIVE] 语音循环开始 mic=自开 VOICE_COMMUNICATION" + (mTapped ? "（WakeService 已让出麦克风）" : "（WakeService 未在录）"));
        openAsr("开始");
        postState();
    }

    void stop() {
        if (mEnded) return;
        mEnded = true;
        mPolicy.end();
        mOwnMicOn = false;
        mOwnMic = null;
        WakeService.clearLiveMicTap(this);   // ★ 11-04 G：只卸自己的（ownMic 模式下 WakeService 在这里恢复录音）
        mTapped = false;
        RealtimeAsrSession s = mAsr;
        mAsr = null;
        if (s != null) s.cancel();
        mBarge.stop();
        mMain.removeCallbacksAndMessages(null);
        TtsSpeaker.stopAll();
        leaveCommRoute();
        Log.i(TAG, "[LIVE] 语音循环结束，历史已清空");
    }

    /** ★ ui-K2 ①：通话模式 + 外放（AEC 在通信模式下才对外放回声有效），TTS 同步走 USAGE_VOICE_COMMUNICATION。 */
    private void enterCommRoute() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                mSavedAudioMode = am.getMode();
                mSavedSpeaker = am.isSpeakerphoneOn();
                am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[LIVE-ECHO] 切通话模式失败: " + t.getClass().getSimpleName());
        }
        TtsSpeaker.setCommRoute(true);
        Log.i(TAG, "[LIVE-ECHO] 通话路由：MODE_IN_COMMUNICATION + 外放，TTS=USAGE_VOICE_COMMUNICATION");
    }

    private void leaveCommRoute() {
        TtsSpeaker.setCommRoute(false);
        try {
            android.media.AudioManager am = (android.media.AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && mSavedAudioMode != Integer.MIN_VALUE) {
                am.setSpeakerphoneOn(mSavedSpeaker);
                am.setMode(mSavedAudioMode);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[LIVE-ECHO] 还原音频模式失败: " + t.getClass().getSimpleName());
        }
        mSavedAudioMode = Integer.MIN_VALUE;
    }

    void setMuted(boolean on) {
        if (mEnded) return;
        mPolicy.setMuted(on);
        if (on) {
            RealtimeAsrSession s = mAsr;
            mAsr = null;
            if (s != null) s.cancel();
        } else {
            mAsrFails = 0;
            openAsr("取消静音");
        }
        Log.i(TAG, "[LIVE] 麦克风" + (on ? "静音" : "恢复"));
        postState();
    }

    boolean muted() { return mPolicy.muted(); }

    // ------------------------------------------------------------------ 音频

    @Override
    public boolean liveTapAlive() {
        return !mEnded;
    }

    @Override
    public void onPcm(short[] buf, int n) {
        if (mEnded || n <= 0 || mPolicy.muted()) return;
        long sq = 0;
        for (int i = 0; i < n; i++) sq += (long) buf[i] * buf[i];
        float rms = (float) Math.sqrt((double) sq / n);
        LiveTurnPolicy.State st = mPolicy.state();
        if (st == LiveTurnPolicy.State.SPEAKING) {
            if (mBarge.active() && mBarge.offer(SystemClock.elapsedRealtime(), rms)
                    && mPolicy.onEnergyBarge() == LiveTurnPolicy.Action.BARGE_IN) {
                bargeIn("能量");
            }
        } else if (st == LiveTurnPolicy.State.LISTENING && rms < 1000f) {
            mFloor = mFloor * 0.95f + rms * 0.05f;   // 底噪慢跟踪（安静时）
        }
        RealtimeAsrSession s = mAsr;
        if (s != null) s.appendShorts(buf, n);
    }

    private void startOwnMic() {
        mOwnMicOn = true;
        mOwnMic = com.onesay.assistant.v2.util.Threads.start("live-mic", new Runnable() {
            @Override
            public void run() {
                AudioRecord ar = null;
                android.media.audiofx.AcousticEchoCanceler aec = null;
                android.media.audiofx.NoiseSuppressor ns = null;
                try {
                    // ★ ui-K2 ①：等 WakeService 真正停录（Android 8 同时只能一路在录；最多等 1.2 s）
                    long t0 = SystemClock.elapsedRealtime();
                    while (mTapped && !WakeService.liveMicReleased() && !mEnded && SystemClock.elapsedRealtime() - t0 < 1200L) SystemClock.sleep(40);
                    int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4));
                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord 未初始化");
                    int session = ar.getAudioSessionId();
                    boolean aecOn = false, nsOn = false;
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        aec = android.media.audiofx.AcousticEchoCanceler.create(session);
                        if (aec != null) aecOn = aec.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS;
                    }
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(session);
                        if (ns != null) nsOn = ns.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS;
                    }
                    Log.i(TAG, "[LIVE-ECHO] 录音 VOICE_COMMUNICATION session=" + session + " AEC=" + aecOn + " NS=" + nsOn);
                    for (int i = 0; i < 8 && !mEnded; i++) {   // 让出麦克风稍慢时重试（读不到 RECORDING 就等一下再开）
                        ar.startRecording();
                        if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) break;
                        SystemClock.sleep(150);
                    }
                    short[] buf = new short[CHUNK];
                    while (!mEnded && mOwnMicOn) {
                        int r = ar.read(buf, 0, buf.length);
                        if (r > 0) onPcm(buf, r);
                        else SystemClock.sleep(20);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "[LIVE] 自开麦克风失败: " + e.getClass().getSimpleName());
                    notice("麦克风打不开（可能被别的应用占用），只能看不能听");
                } finally {
                    try { if (aec != null) aec.release(); } catch (Throwable ignore) { }
                    try { if (ns != null) ns.release(); } catch (Throwable ignore) { }
                    try { if (ar != null) { ar.stop(); ar.release(); } } catch (Throwable ignore) { }
                }
            }
        });
    }

    // ------------------------------------------------------------------ ASR

    private void openAsr(String why) {
        if (mEnded || mPolicy.muted()) return;
        AliyunAsrConfig c;
        String key;
        try {
            c = new AliyunAsrConfig(mApp);
            key = c.getDashScopeKey();
        } catch (Throwable t) {
            c = null;
            key = null;
        }
        if (c == null || key == null || key.trim().length() == 0) {
            notice("还没有配置百炼（DashScope）Key，听不了你说话");
            return;
        }
        RealtimeAsrSession s = new RealtimeAsrSession(c.getRealtimeUrl(), key.trim(),
                c.getRealtimeSilenceMs(), null, com.onesay.assistant.v2.asr.RealtimeAsrPolicy.CONNECT_TIMEOUT_MS, 0, this);
        s.multiSegment(true).voiceRms(LiveTurnPolicy.ASR_VOICE_RMS);
        RealtimeAsrSession old = mAsr;
        mAsr = s;
        if (old != null) old.cancel();
        s.start();
        s.markMicOpen();
        Log.i(TAG, "[LIVE] ASR 连接（" + why + "）model=" + c.getRealtimeModel());
    }

    @Override
    public void onReady(RealtimeAsrSession s) {
        if (s != mAsr) return;
        notice(null);
    }

    @Override
    public void onSpeechStarted(RealtimeAsrSession s) { }

    @Override
    public void onSpeechStopped(RealtimeAsrSession s) { }

    @Override
    public void onPartial(RealtimeAsrSession s, final String text) {
        if (s != mAsr || mEnded) return;
        LiveTurnPolicy.Action a = mPolicy.onPartial(text);
        if (a == LiveTurnPolicy.Action.BARGE_IN) bargeIn("开口");
        if (mPolicy.showPartial(text, SystemClock.elapsedRealtime())) {
            mMain.post(new Runnable() {
                @Override public void run() { if (!mEnded) mHost.onUserText(text, false); }
            });
        }
    }

    @Override
    public void onFinal(RealtimeAsrSession s, final String text) {
        if (s != mAsr || mEnded) return;
        mAsrFails = 0;
        LiveTurnPolicy.Action a = mPolicy.onFinal(text, SystemClock.elapsedRealtime());
        Log.i(TAG, "[LIVE] 一句话结束 " + (text == null ? 0 : text.length()) + "字 → " + a);
        if (a == LiveTurnPolicy.Action.DROP_ECHO) {   // ★ ui-K2 ③：只记字数与相似度，不记原话
            Log.i(TAG, "[LIVE-ECHO] 丢弃疑似回声 " + (text == null ? 0 : text.length()) + "字 相似度="
                    + LiveEchoPolicy.ratio(text, mPolicy.spokenText()) + " 连续=" + LiveEchoPolicy.lcs(LiveTurnPolicy.norm(text), LiveTurnPolicy.norm(mPolicy.spokenText())));
        }
        if (a != LiveTurnPolicy.Action.START_TURN) return;
        TtsSpeaker.stopAll();
        mBarge.stop();
        final int gen = mPolicy.gen();
        final String q = LiveTurnPolicy.questionOrDefault(text);
        mMain.post(new Runnable() {
            @Override public void run() {
                if (mEnded) return;
                mHost.onUserText(text, true);
                mHost.onNotice(null);
            }
        });
        postState();
        com.onesay.assistant.v2.util.Threads.start("live-turn", new Runnable() {
            @Override public void run() { runTurn(gen, q); }
        });
    }

    @Override
    public void onError(RealtimeAsrSession s, String reason) {
        if (s != mAsr || mEnded) return;
        int n = ++mAsrFails;
        Log.w(TAG, "[LIVE] ASR 断开（第 " + n + " 次）: " + (reason == null ? "?" : reason.replaceAll("sk-[A-Za-z0-9._\\-]{4,}", "sk-***")));
        if (n >= MAX_ASR_FAILS) {
            notice("语音识别连不上了，挂断后再试一次");
            return;
        }
        mMain.postDelayed(new Runnable() {
            @Override public void run() { openAsr("重连"); }
        }, 500L * n);
    }

    // ------------------------------------------------------------------ 一轮

    private void runTurn(int gen, String question) {
        LiveFrame f = null;
        LiveVisionClient.Result r;
        try {
            f = mHost.grabFrame();
            if (gen != mPolicy.gen()) return;   // 已被新一句顶掉：帧不上传
            r = LiveVisionClient.ask(mApp, mPolicy.history(), f == null ? null : f.bytes(), question);
        } finally {
            if (f != null) f.wipe();
        }
        if (mEnded) return;
        if (r.ok) {
            if (!mPolicy.onAnswer(gen, question, r.text)) return;
            final String ans = r.text;
            mMain.post(new Runnable() {
                @Override public void run() {
                    if (mEnded) return;
                    mHost.onAnswerText(ans);
                    speak(ans);
                }
            });
        } else {
            if (!mPolicy.onError(gen)) return;
            notice(r.text);
        }
        postState();
    }

    /** 主线程。 */
    private void speak(String text) {
        mTtsSeen = false;
        mBargeArmed = false;
        mSpeakAt = SystemClock.elapsedRealtime();
        TtsSpeaker.get(mApp).speakLive(text, LiveVisionPrompt.SPOKEN_MAX);
        mMain.removeCallbacks(mTtsWatch);
        mMain.postDelayed(mTtsWatch, 150L);
    }

    /** 播报进度：开始出声 → 标定打断检测；停了 → 回到听。没出声超 8 s（云端合成失败且无回退）→ 当播完。 */
    private final Runnable mTtsWatch = new Runnable() {
        private int quiet;

        @Override
        public void run() {
            if (mEnded || mPolicy.state() != LiveTurnPolicy.State.SPEAKING) {
                quiet = 0;
                mBarge.stop();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            boolean sp = TtsSpeaker.isSpeaking();
            if (sp && !mTtsSeen) mTtsSeen = true;
            // 回声基线必须在真正出声后标定（云端首包约 1 s，之前 isSpeaking 已为真但还没声音 → 基线≈0 → 自己把自己打断）
            if (sp && !mBarge.active() && !mBargeArmed && TtsSpeaker.lastAudibleAt() >= mSpeakAt) {
                mBargeArmed = true;
                mBarge.start(now, mFloor);
            }
            if (sp) quiet = 0;
            else if (mTtsSeen) quiet++;
            if ((mTtsSeen && quiet >= 2) || (!mTtsSeen && now - mSpeakAt > 8000L)) {
                quiet = 0;
                mBarge.stop();
                mPolicy.onSpeechDone(now);
                postState();
                return;
            }
            mMain.postDelayed(this, 150L);
        }
    };

    private void bargeIn(String why) {
        Log.i(TAG, "[LIVE] 打断播报（" + why + "）");
        mBarge.stop();
        TtsSpeaker.stopAll();
        mPolicy.onSpeechDone(SystemClock.elapsedRealtime());
        postState();
    }

    private void notice(final String text) {
        mMain.post(new Runnable() {
            @Override public void run() { if (!mEnded) mHost.onNotice(text); }
        });
    }

    private void postState() {
        mMain.post(new Runnable() {
            @Override public void run() { if (!mEnded) mHost.onLiveState(mPolicy.state(), mPolicy.muted()); }
        });
    }
}
