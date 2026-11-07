package com.onesay.assistant.v2.asr;

import com.onesay.assistant.v2.net.WebSocketClient;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ★ R14 流式识别：百炼 qwen3-asr-flash-realtime（WebSocket + 服务端 VAD）的一次会话。纯 Java（org.json + {@link WebSocketClient}），
 * 不碰 android.*，JVM 单测可直接跑（含沙箱真连百炼的手动探针）。
 *
 * <pre>
 *   wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime     Authorization: Bearer sk-…
 *   → session.update {input_audio_format:pcm, sample_rate:16000, input_audio_transcription:{language:zh, corpus:{text:热词}},
 *                     turn_detection:{type:server_vad, threshold:0.0, silence_duration_ms:450}}
 *   → input_audio_buffer.append {audio: base64(100 ms PCM)} × N
 *   ← input_audio_buffer.speech_started / conversation.item.input_audio_transcription.text {text, stash}（中间结果）
 *   ← input_audio_buffer.speech_stopped → …transcription.completed {transcript}（最终文本）
 * </pre>
 *
 * <h3>生命周期</h3>
 * {@link #start()}（按下键时）→ 后台线程建连、发 session.update；建连期间 {@link #append} 进来的音频先排队，连上后按序补发；
 * 收到第一条 completed（非空）→ {@link Listener#onFinal}，会话随即关闭（一次唤醒一句话）。
 * 任何失败（建连失败 / error 事件 / 中途断开）→ {@link Listener#onError} 一次，调用方回退非流式整段识别。
 *
 * <h3>隐私</h3>
 * 本类只发调用方 append 进来的音频；WakeService 只 append 捕获窗（隐私起点之后）的帧 —— 和非流式缓冲同一条边界。
 */
public final class RealtimeAsrSession implements WebSocketClient.Listener {

    public static final String DEFAULT_MODEL = "qwen3-asr-flash-realtime";
    /** 预置的实时识别 WebSocket 地址（不含 model 参数）；用户可在「API 密钥」页改成任何兼容服务（只收 wss://）。 */
    public static final String DEFAULT_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    public static final String DEFAULT_URL_BASE = DEFAULT_ENDPOINT + "?model=";
    /** 预置识别语言（百炼 input_audio_transcription.language）；空串 = 让服务端自动判断。 */
    public static final String DEFAULT_LANGUAGE = "zh";
    private static volatile String sLanguage = DEFAULT_LANGUAGE;

    /** 识别语言（进程内；由 {@code AliyunAsrConfig.getRealtimeUrl()} 按用户设置更新）。null / 非法 → 预置；空串 = 自动。 */
    public static void setLanguage(String lang) {
        String l = lang == null ? DEFAULT_LANGUAGE : lang.trim();
        sLanguage = l.length() == 0 || com.onesay.assistant.v2.net.UserEndpoint.validId(l) ? l : DEFAULT_LANGUAGE;
    }

    public static String language() { return sLanguage; }
    /**
     * ★ r2/input（2026-10-24）：450 → 700。服务端 VAD 现在只负责「分段」，说完没有由本地 UtteranceEndpointer 按文字完整度判断；
     * 450 ms 会把一个词中间的短停顿切成两段（百炼文档：300 ms「可能导致在自然停顿处发生不合理的断句」，默认 800）。
     */
    public static final int DEFAULT_SILENCE_MS = 700;

    /** 事件回调（WebSocket 读线程 / 会话线程上调用，调用方自己切主线程）。 */
    public interface Listener {
        /** 建连 + session.update 已发出（可以收音了）。 */
        void onReady(RealtimeAsrSession s);

        void onSpeechStarted(RealtimeAsrSession s);

        void onSpeechStopped(RealtimeAsrSession s);

        /** 中间结果（text + stash，已拼好）。 */
        void onPartial(RealtimeAsrSession s, String text);

        /** 最终文本（非空）。 */
        void onFinal(RealtimeAsrSession s, String text);

        /** 失败（只回调一次；之后不会再有任何回调）。 */
        void onError(RealtimeAsrSession s, String reason);
    }

    public enum State { NEW, CONNECTING, READY, DONE, FAILED, CLOSED }

    /** 解析后的服务端事件（纯数据，单测用）。 */
    public static final class Event {
        public final String type;
        public final String text;     // partial: text+stash；completed: transcript；error: message
        public final String code;     // error code

        Event(String type, String text, String code) {
            this.type = type;
            this.text = text;
            this.code = code;
        }

        public boolean isPartial() { return "conversation.item.input_audio_transcription.text".equals(type); }
        public boolean isFinal() { return "conversation.item.input_audio_transcription.completed".equals(type); }
        public boolean isSpeechStarted() { return "input_audio_buffer.speech_started".equals(type); }
        public boolean isSpeechStopped() { return "input_audio_buffer.speech_stopped".equals(type); }
        public boolean isError() { return "error".equals(type) || (type != null && type.endsWith(".failed")); }
    }

    private static final byte[] POISON = new byte[0];
    /** 排队上限：30 s 音频（100 ms 一帧）；建连卡住时多的直接丢（窗最长 20 s，正常到不了）。 */
    static final int QUEUE_CAP = 400;   // ★ r2/input：整段上限 20 → 30 s，排队上限跟着放到 40 s

    private final String mUrl;
    private final String mApiKey;
    private final int mSilenceMs;
    private final String mCorpus;
    private final Listener mListener;
    private final int mConnectTimeoutMs;
    private final LinkedBlockingQueue<byte[]> mQueue = new LinkedBlockingQueue<byte[]>(QUEUE_CAP);

    /**
     * ★ r2/input：多段模式。true = 每段 completed 都交付（{@link Listener#onFinal}），会话<b>不关</b>，继续送音频，
     * 由调用方判断说完后 {@link #cancel()}；false（默认）= R14 行为，第一段非空 completed 交付后关会话。
     */
    private volatile boolean mMultiSegment;
    private volatile int mSegments;

    private volatile State mState = State.NEW;
    private volatile WebSocketClient mWs;
    private volatile boolean mErrored;
    private volatile boolean mSawSpeech;
    private volatile boolean mFinalDelivered;
    private volatile String mLastPartial = "";
    private volatile int mSentChunks;
    private volatile int mDroppedChunks;

    /** 时间点（System.nanoTime 毫秒；0 = 没发生）：start、建连完成、首个中间结果、speech_stopped、最终结果。 */
    public volatile long tStart, tReady, tFirstPartial, tSpeechStopped, tFinal;

    /** 调用方的代际号（WakeService 用来识别跨窗迟到的回调）。 */
    public final int gen;

    public RealtimeAsrSession(String url, String apiKey, int silenceMs, String hotwords, int connectTimeoutMs,
                              int gen, Listener listener) {
        mUrl = url;
        mApiKey = apiKey;
        mSilenceMs = silenceMs <= 0 ? DEFAULT_SILENCE_MS : silenceMs;
        mCorpus = corpusFromHotwords(hotwords);
        mConnectTimeoutMs = connectTimeoutMs <= 0 ? 4000 : connectTimeoutMs;
        mListener = listener;
        this.gen = gen;
    }

    public static String urlFor(String model) {
        return urlFor(DEFAULT_ENDPOINT, model);
    }

    /** 用户配置的地址 + 模型名 → 完整 wss 地址（地址已带 ? 时用 & 接 model）。 */
    public static String urlFor(String endpoint, String model) {
        String base = endpoint == null || endpoint.trim().length() == 0 ? DEFAULT_ENDPOINT : endpoint.trim();
        String m = model == null || model.trim().length() == 0 ? DEFAULT_MODEL : model.trim();
        return com.onesay.assistant.v2.net.UserEndpoint.withParam(base, "model", m);
    }

    public State state() { return mState; }

    /** 开多段模式（start 之前调）。 */
    public RealtimeAsrSession multiSegment(boolean on) {
        mMultiSegment = on;
        return this;
    }

    public boolean isMultiSegment() { return mMultiSegment; }

    /** 已交付的段数。 */
    public int segments() { return mSegments; }

    /** 建连中或已就绪、且没出错没结束 = 这次唤醒的识别归它管。 */
    public boolean isHealthy() {
        State s = mState;
        return !mErrored && !mFinalDelivered && (s == State.CONNECTING || s == State.READY);
    }

    public boolean isReady() { return mState == State.READY && !mErrored; }
    public boolean sawSpeech() { return mSawSpeech; }
    public boolean finalDelivered() { return mFinalDelivered; }
    public int sentChunks() { return mSentChunks; }

    private volatile int mFillerTurns = 0;
    /** 被当作空 turn 丢掉的「嗯」类最终结果条数（日志用）。 */
    public int fillerTurns() { return mFillerTurns; }

    /**
     * 最终文本是不是在回吐热词表（corpus）。★ R14 上机（10-15）：静音时模型把 corpus 原文
     * 「必应、bing、百度、谷歌、…」当成转写结果交了出来，并被当成指令执行。
     * 判据：按「、，, 空格」切开后，有 ≥3 段原样出现在 corpus 里且占一半以上；或去掉标点后 ≥8 字且整段就是 corpus 的子串。
     */
    public static boolean isCorpusEcho(String transcript, String corpus) {
        if (transcript == null || corpus == null || corpus.length() == 0) return false;
        String t = transcript.trim();
        String[] parts = t.split("[、，,\\s。；;]+");
        int total = 0, hit = 0;
        for (String p : parts) {
            if (p.length() == 0) continue;
            total++;
            if (corpus.contains(p)) hit++;
        }
        if (hit >= 3 && hit * 2 >= total) return true;
        String bare = t.replaceAll("[\\s，。？！、：；,.?!:;]", "");
        String bareCorpus = corpus.replaceAll("[\\s，。？！、：；,.?!:;]", "");
        return bare.length() >= 8 && bareCorpus.contains(bare);
    }

    /** 最终文本是不是纯应答音 / 语气词（「嗯。」「诶」「我在」…）：这种 turn 不算用户的话。 */
    public static boolean isFillerTurn(String transcript) {
        return com.onesay.assistant.v2.wake.WakeWordStripper.isAckSoundUtterance(transcript);
    }
    public int droppedChunks() { return mDroppedChunks; }

    /** 开会话（立即返回；建连在后台线程）。 */
    public void start() {
        if (mState != State.NEW) return;
        mState = State.CONNECTING;
        tStart = nowMs();
        com.onesay.assistant.v2.util.Threads.start("asr-rt", new Runnable() {
            @Override
            public void run() {
                runSender();
            }
        });
    }

    /** 追加一段 16 kHz / 16-bit / 单声道 PCM（short[] 小端转字节；麦克风线程调用，不阻塞）。 */
    public void appendShorts(short[] buf, int n) {
        if (buf == null || n <= 0 || !acceptingAudio()) return;
        byte[] b = new byte[n * 2];
        long sq = 0;
        for (int i = 0; i < n; i++) {
            short v = buf[i];
            b[i * 2] = (byte) (v & 0xFF);
            b[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            sq += (long) v * v;
        }
        noteEnergy((int) Math.sqrt((double) sq / n), n);
        offer(b);
    }

    // ------------------------------------------------------------------ device-1102：开麦闸门
    /**
     * ★ device-1102 问题 2「录音按钮一亮就吐一大段」：开麦瞬间送上去的是近空音频 / 应答提示音 / 上一轮 TTS 尾音，
     * qwen3-asr-flash-realtime 拿 corpus（热词表）幻觉出一整段，中间结果<b>不经任何判据</b>直接 onPartial → 上屏
     * （release 的 pushPartialToUi 只滤「嗯」类应答音）。闸门：
     * <ol>
     *   <li>开麦后前 {@link #OPEN_GATE_MS} 毫秒内的中间 / 最终结果一律不交付；</li>
     *   <li>服务端 VAD 没报 speech_started 之前，中间结果不交付；</li>
     *   <li>本地能量：送上去的音频里没有一帧 RMS ≥ {@link #VOICE_RMS} 的，最终结果按空 turn 丢掉（近空音频不可能是用户的话）；</li>
     *   <li>中间结果也过 corpus 回吐判据（{@link #isCorpusEcho}），命中不交付。</li>
     * </ol>
     * 闸门挡住的结果只打 [ASR-GATE] 日志（前 16 字），会话照旧继续收音。
     */
    public static final int OPEN_GATE_MS = 500;
    /** 16-bit PCM 的 RMS 门限：安静房间底噪约 30–150，正常说话 ≥ 800；提示音尾巴回采通常 < 300。 */
    public static final int VOICE_RMS = 400;
    /** 至少多少毫秒「有声」音频才算有人说话（16 kHz：1600 采样 = 100 ms）。 */
    public static final int VOICED_MS_MIN = 150;

    private volatile int mVoicedSamples;
    private volatile int mGateDropped;
    private volatile long mClockOverride = -1;   // 单测用

    private volatile int mNotedSamples;
    private volatile int mVoiceRms = VOICE_RMS;

    /**
     * ★ 11-04 相机对话：本会话的「有声」RMS 门限（start 之前调；默认 {@link #VOICE_RMS}）。相机对话借的是带 AEC / NS 的唤醒麦克风、
     * 人离手机常有一臂远，真机上用户正常说话的整句被 400 的门限判成「无有声音频」丢掉；相机对话不带热词 corpus（幻觉源头），门限放低。
     */
    public RealtimeAsrSession voiceRms(int rms) {
        mVoiceRms = rms <= 0 ? VOICE_RMS : rms;
        return this;
    }

    void noteEnergy(int rms, int samples) {
        mNotedSamples += samples;
        if (rms >= mVoiceRms) mVoicedSamples += samples;
    }

    /** 单测 / 无麦克风路径直接喂能量。 */
    public void noteEnergyForTest(int rms, int samples) { noteEnergy(rms, samples); }

    void setClockForTest(long ms) { mClockOverride = ms; }

    /** 能量未知（没走 appendShorts，如单测直接喂事件）按「有声」算，不改变旧行为。 */
    public boolean hasVoicedAudio() { return mNotedSamples == 0 || mVoicedSamples >= VOICED_MS_MIN * 16; }

    public int gateDropped() { return mGateDropped; }

    /** 纯判据（JVM 单测）：这条结果此刻能不能交付。 */
    public static boolean gateAllows(boolean isFinal, long msSinceOpen, boolean sawSpeech, boolean voiced, boolean corpusEcho) {
        if (corpusEcho) return false;
        if (msSinceOpen < OPEN_GATE_MS) return false;
        if (!voiced) return false;
        return isFinal || sawSpeech;
    }

    /** 真正开麦（绑到捕获窗）的时刻；0 = 没标（按 tStart 算）。按下键预建的会话要等绑窗才算开麦。 */
    public volatile long tMicOpen;

    public void markMicOpen() { tMicOpen = nowMs(); }

    void markMicOpenForTest(long ms) { tMicOpen = ms; }

    private boolean gate(boolean isFinal, String text) {
        long now = mClockOverride >= 0 ? mClockOverride : nowMs();
        long open = tMicOpen != 0 ? tMicOpen : tStart;
        long since = open == 0 ? Long.MAX_VALUE : now - open;
        boolean ok = gateAllows(isFinal, since, mSawSpeech, hasVoicedAudio(), isCorpusEcho(text, mCorpus));
        if (!ok) {
            mGateDropped++;
            String head = text == null ? "" : (text.length() > 16 ? text.substring(0, 16) + "…" : text);
            try {
                android.util.Log.i("RealtimeAsr", "[ASR-GATE] 丢弃" + (isFinal ? "最终" : "中间") + "结果 since=" + since
                        + "ms speech=" + mSawSpeech + " voiced=" + hasVoicedAudio() + " len=" + (text == null ? 0 : text.length())
                        + " head=" + head);
            } catch (Throwable ignore) { }
        }
        return ok;
    }

    private boolean acceptingAudio() {
        State s = mState;
        return !mErrored && !mFinalDelivered && (s == State.CONNECTING || s == State.READY);
    }

    private void offer(byte[] b) {
        if (!mQueue.offer(b)) mDroppedChunks++;
    }

    /** 不再要结果：关闭会话（不回调）。幂等。 */
    public void cancel() {
        State s = mState;
        if (s == State.CLOSED || s == State.FAILED) return;
        mState = State.CLOSED;
        mQueue.clear();
        mQueue.offer(POISON);
        WebSocketClient ws = mWs;
        if (ws != null) {
            try {
                ws.close(1000, "bye");
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------ 会话线程

    private void runSender() {
        WebSocketClient ws = new WebSocketClient(this);
        mWs = ws;
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Authorization", "Bearer " + mApiKey);
            ws.connect(mUrl, h, mConnectTimeoutMs, 30000);
            if (mState == State.CLOSED) {
                ws.close(1000, "cancelled");
                return;
            }
            if (!ws.sendText(sessionUpdateJson(mSilenceMs, mCorpus))) {
                fail("session.update 发送失败");
                return;
            }
            tReady = nowMs();
            if (mState == State.CONNECTING) mState = State.READY;
            try {
                mListener.onReady(this);
            } catch (Throwable ignore) {
            }
            int seq = 0;
            while (true) {
                byte[] b = mQueue.poll(25, TimeUnit.SECONDS);
                if (b == null || b == POISON) break;
                if (mState != State.READY || mErrored || mFinalDelivered) break;
                if (!ws.sendText(appendJson(b, seq++))) {
                    fail("音频发送失败");
                    return;
                }
                mSentChunks++;
            }
        } catch (Throwable t) {
            fail("建连失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        if (mState != State.FAILED) {
            try {
                ws.close(1000, "done");
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------ WebSocket 回调（读线程）

    @Override
    public void onText(WebSocketClient ws, String text) {
        Event e = parseEvent(text);
        if (e == null || mState == State.CLOSED || mErrored) return;
        if (e.isError()) {
            fail("服务端错误 " + (e.code == null ? "" : e.code + " ") + (e.text == null ? "" : e.text));
            return;
        }
        if (mFinalDelivered) return;
        if (e.isSpeechStarted()) {
            mSawSpeech = true;
            try { mListener.onSpeechStarted(this); } catch (Throwable ignore) { }
        } else if (e.isSpeechStopped()) {
            tSpeechStopped = nowMs();
            try { mListener.onSpeechStopped(this); } catch (Throwable ignore) { }
        } else if (e.isPartial()) {
            if (e.text != null && e.text.length() > 0 && !e.text.equals(mLastPartial)) {
                if (!gate(false, e.text)) return;   // ★ device-1102：开麦闸门
                mLastPartial = e.text;
                if (tFirstPartial == 0) tFirstPartial = nowMs();
                try { mListener.onPartial(this, e.text); } catch (Throwable ignore) { }
            }
        } else if (e.isFinal()) {
            String t = e.text == null ? "" : e.text.trim();
            if (t.length() == 0) return;   // 空 turn（咳嗽 / 噪声）：继续听
            // ★ R14 上机（10-15）：短提示音尾巴漏进麦克风，服务端 VAD 先切出一段「嗯。」。
            //   这种纯应答音 turn 也当空 turn：不交付、会话不关，继续把后面的音频送上去等用户真正那句话。
            if (isFillerTurn(t) || !gate(true, t)) {   // ★ device-1102：corpus 回吐 + 开麦闸门 + 无有声音频
                mLastPartial = "";
                tFirstPartial = 0;
                mFillerTurns++;
                return;
            }
            tFinal = nowMs();
            mSegments++;
            if (mMultiSegment) {
                // 多段：这一段交付，会话照旧收音（中间结果从下一段重新开始）
                mLastPartial = "";
                try { mListener.onFinal(this, t); } catch (Throwable ignore) { }
                return;
            }
            mFinalDelivered = true;
            mState = State.DONE;
            mQueue.offer(POISON);
            try { mListener.onFinal(this, t); } catch (Throwable ignore) { }
        }
    }

    @Override
    public void onClosed(WebSocketClient ws, int code, String reason) {
        if (mState == State.CLOSED || mFinalDelivered || mErrored) return;
        fail("服务端关闭连接 code=" + code + (reason == null || reason.length() == 0 ? "" : " " + reason));
    }

    @Override
    public void onFailure(WebSocketClient ws, Throwable t) {
        if (mState == State.CLOSED || mFinalDelivered || mErrored) return;
        fail("连接中断: " + (t == null ? "?" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    private void fail(String reason) {
        synchronized (this) {
            if (mErrored || mState == State.CLOSED || mFinalDelivered) return;
            mErrored = true;
            mState = State.FAILED;
        }
        mQueue.clear();
        mQueue.offer(POISON);
        WebSocketClient ws = mWs;
        if (ws != null) ws.cancel();
        try {
            mListener.onError(this, reason);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 纯函数（JVM 单测）

    /** session.update（server_vad，silence_duration_ms 可调；热词放 corpus）。 */
    public static String sessionUpdateJson(int silenceMs, String corpus) {
        try {
            JSONObject vad = new JSONObject();
            vad.put("type", "server_vad");
            vad.put("threshold", 0.0);
            vad.put("silence_duration_ms", silenceMs);
            JSONObject tr = new JSONObject();
            String lang = sLanguage;
            if (lang != null && lang.length() > 0) tr.put("language", lang);
            if (corpus != null && corpus.length() > 0) {
                JSONObject c = new JSONObject();
                c.put("text", corpus);
                tr.put("corpus", c);
            }
            JSONObject s = new JSONObject();
            JSONObject mod = new JSONObject();
            s.put("modalities", new org.json.JSONArray().put("text"));
            s.put("input_audio_format", "pcm");
            s.put("sample_rate", 16000);
            s.put("input_audio_transcription", tr);
            s.put("turn_detection", vad);
            mod.put("event_id", "s0");
            mod.put("type", "session.update");
            mod.put("session", s);
            return mod.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** input_audio_buffer.append（手拼，音频帧是热路径，不走 JSONObject）。 */
    public static String appendJson(byte[] pcm, int seq) {
        String b64 = WebSocketClient.base64(pcm);
        StringBuilder sb = new StringBuilder(b64.length() + 80);
        sb.append("{\"event_id\":\"a").append(seq).append("\",\"type\":\"input_audio_buffer.append\",\"audio\":\"")
                .append(b64).append("\"}");
        return sb.toString();
    }

    /** 解析服务端事件；不认识 / 坏 JSON 返回 null。 */
    public static Event parseEvent(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type", "");
            if (type.length() == 0) return null;
            if ("conversation.item.input_audio_transcription.text".equals(type)) {
                return new Event(type, o.optString("text", "") + o.optString("stash", ""), null);
            }
            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                return new Event(type, o.optString("transcript", ""), null);
            }
            if ("error".equals(type) || type.endsWith(".failed")) {
                JSONObject err = o.optJSONObject("error");
                String msg = err != null ? err.optString("message", "") : o.optString("message", "");
                String code = err != null ? err.optString("code", err.optString("type", "")) : o.optString("code", "");
                return new Event(type, msg, code);
            }
            return new Event(type, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 热词 → corpus 文本：去掉非流式通道的提示句开场白（"语音识别热词表，以下…："），只留词表。
     * 开场白在非流式那边被回吐过（见 {@link AsrHotwords#HOTWORD_PROMPT_PREFIX}），这里干脆不发。
     */
    public static String corpusFromHotwords(String hotwords) {
        if (hotwords == null) return "";
        String h = hotwords.trim();
        if (h.startsWith(AsrHotwords.HOTWORD_PROMPT_PREFIX)) h = h.substring(AsrHotwords.HOTWORD_PROMPT_PREFIX.length());
        // ★ device-1102：词表改为空格分隔、上限 400 → CORPUS_MAX（短表少幻觉，且「、」连写的长句最像一段可读的转写）
        h = h.replace('、', ' ').replaceAll("\\s+", " ").trim();
        if (h.length() > CORPUS_MAX) {
            int cut = h.lastIndexOf(' ', CORPUS_MAX);
            h = h.substring(0, cut > 0 ? cut : CORPUS_MAX);
        }
        return h;
    }

    /** ★ device-1102：corpus 上限（字）。qwen3-asr-flash(-realtime) 不支持 vocabulary 热词参数，只能走 corpus/system 上下文。 */
    public static final int CORPUS_MAX = 240;

    private static long nowMs() {
        return System.nanoTime() / 1000000L;
    }
}
