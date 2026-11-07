package com.onesay.assistant.v2.tts;

import com.onesay.assistant.v2.net.WebSocketClient;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★ r2/cloud-tts（第一阶段）：百炼 CosyVoice 实时语音合成（WebSocket duplex）的一次任务。纯 Java（org.json +
 * {@link WebSocketClient}），不碰 android.*，JVM 单测用本地桩服务跑完整事件序列。
 *
 * <pre>
 *   wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference   （或 wss://dashscope.aliyuncs.com/api-ws/v1/inference）
 *   Authorization: Bearer sk-…
 *   → run-task {task_group:audio, task:tts, function:SpeechSynthesizer, model, parameters{voice, format:pcm, sample_rate}}
 *   ← task-started
 *   → continue-task {input:{text}} → finish-task
 *   ← binary（PCM 16-bit 单声道小端）× N，穿插 result-generated
 *   ← task-finished / task-failed
 * </pre>
 *
 * 第一阶段一次任务只送一整句（终稿），一次连接一次任务；连接复用 / 按句流式是第二阶段。
 * 复刻音色绑定它复刻时的模型（音色「c」= cosyvoice-v3.5-flash），不能跨模型用。
 *
 * <h3>回调约定</h3>
 * 回调都在 WebSocket 读线程 / 会话线程上，调用方不要阻塞（音频交给播放器的队列）。
 * {@link Listener#onDone} / {@link Listener#onError} 二者只回调其一，且只一次；{@link #cancel()} 之后不再回调。
 */
public final class DashScopeTtsSession implements WebSocketClient.Listener, WebSocketClient.BinaryListener {

    public interface Listener {
        /** WebSocket 升级完成（建连阶段结束）。 */
        void onConnected(DashScopeTtsSession s);

        /** 一段 PCM（16-bit 小端单声道，采样率 = 构造参数）。 */
        void onAudio(DashScopeTtsSession s, byte[] pcm);

        /** task-finished：音频已全部送达。 */
        void onDone(DashScopeTtsSession s);

        /** 建连失败 / task-failed / 中途断开。只一次。 */
        void onError(DashScopeTtsSession s, String reason);
    }

    public enum State { NEW, CONNECTING, STARTED, DONE, FAILED, CANCELLED }

    private final String mUrl;
    private final String mApiKey;
    private final String mModel;
    private final String mVoice;
    private final int mSampleRate;
    private final int mConnectTimeoutMs;
    private final Listener mListener;
    private final String mTaskId;

    private volatile State mState = State.NEW;
    private volatile WebSocketClient mWs;
    private volatile String mText;
    private volatile boolean mTerminal;
    private volatile int mAudioBytes;
    /** 服务端报的计费字符数（task-finished 的 usage.characters；没有为 -1）。 */
    public volatile int usageCharacters = -1;
    /** 失败时服务端的 error_code（建连失败时为 "HTTP xxx" / 异常类名）。 */
    public volatile String errorCode;
    /** 握手 HTTP 状态码（升级失败时解析出来；用于判断“专属域名鉴权不过”）。 */
    public volatile int handshakeHttpCode = -1;

    public DashScopeTtsSession(String url, String apiKey, String model, String voice, int sampleRate,
                               int connectTimeoutMs, Listener listener) {
        mUrl = url;
        mApiKey = apiKey;
        mModel = model;
        mVoice = voice;
        mSampleRate = sampleRate > 0 ? sampleRate : 24000;
        mConnectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 3000;
        mListener = listener;
        mTaskId = UUID.randomUUID().toString().replace("-", "");
    }

    public State state() { return mState; }
    public String taskId() { return mTaskId; }
    public String url() { return mUrl; }
    public int audioBytes() { return mAudioBytes; }
    public int sampleRate() { return mSampleRate; }

    /** 开始合成（立即返回；建连在后台线程）。 */
    public void start(String text) {
        if (mState != State.NEW) return;
        mText = text;
        mState = State.CONNECTING;
        com.onesay.assistant.v2.util.Threads.start("tts-ws", new Runnable() {
            @Override
            public void run() {
                runConnect();
            }
        });
    }

    /** 打断：立即断开（不发 finish-task，不回调）。幂等。 */
    public void cancel() {
        State s = mState;
        if (s == State.DONE || s == State.FAILED || s == State.CANCELLED) return;
        mState = State.CANCELLED;
        mTerminal = true;
        WebSocketClient ws = mWs;
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------ 会话线程

    private void runConnect() {
        WebSocketClient ws = new WebSocketClient(this);
        mWs = ws;
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Authorization", "Bearer " + mApiKey);
            // 只读超时：一句话的合成正常 1–3 s 内结束；30 s 收不到任何帧算断
            ws.connect(mUrl, h, mConnectTimeoutMs, 30000);
        } catch (Throwable t) {
            handshakeHttpCode = parseHttpCode(String.valueOf(t.getMessage()));
            fail(handshakeHttpCode > 0 ? "HTTP " + handshakeHttpCode : t.getClass().getSimpleName(),
                    "建连失败: " + t.getMessage());
            return;
        }
        if (mState == State.CANCELLED) {
            ws.cancel();
            return;
        }
        try {
            mListener.onConnected(this);
        } catch (Throwable ignore) {
        }
        if (!ws.sendText(runTaskJson(mTaskId, mModel, mVoice, mSampleRate))) {
            fail("send", "run-task 发送失败");
        }
    }

    @Override
    public void onText(WebSocketClient ws, String text) {
        if (mTerminal) return;
        Event e = parseEvent(text);
        if (e == null) return;
        if ("task-started".equals(e.event)) {
            mState = State.STARTED;
            String t = mText == null ? "" : mText;
            if (!ws.sendText(continueTaskJson(mTaskId, t)) || !ws.sendText(finishTaskJson(mTaskId))) {
                fail("send", "continue/finish-task 发送失败");
            }
        } else if ("task-finished".equals(e.event)) {
            usageCharacters = e.characters;
            synchronized (this) {
                if (mTerminal) return;
                mTerminal = true;
                mState = State.DONE;
            }
            try {
                ws.close(1000, "done");
            } catch (Throwable ignore) {
            }
            try {
                mListener.onDone(this);
            } catch (Throwable ignore) {
            }
        } else if ("task-failed".equals(e.event)) {
            fail(e.errorCode, "task-failed " + e.errorCode + ": " + e.errorMessage);
        }
        // result-generated 等：忽略
    }

    @Override
    public void onBinary(WebSocketClient ws, byte[] data) {
        if (mTerminal || data == null || data.length == 0) return;
        mAudioBytes += data.length;
        try {
            mListener.onAudio(this, data);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onClosed(WebSocketClient ws, int code, String reason) {
        if (!mTerminal) fail("closed", "服务端关闭连接 " + code + " " + reason);
    }

    @Override
    public void onFailure(WebSocketClient ws, Throwable t) {
        if (!mTerminal) fail(t == null ? "io" : t.getClass().getSimpleName(), "连接中断: " + (t == null ? "" : t.getMessage()));
    }

    private void fail(String code, String reason) {
        synchronized (this) {
            if (mTerminal) return;
            mTerminal = true;
            mState = State.FAILED;
        }
        errorCode = code;
        WebSocketClient ws = mWs;
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Throwable ignore) {
            }
        }
        try {
            mListener.onError(this, reason);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 纯函数（JVM 单测）

    /** 解析后的服务端事件。 */
    public static final class Event {
        public final String event;
        public final String errorCode;
        public final String errorMessage;
        public final int characters;

        Event(String event, String errorCode, String errorMessage, int characters) {
            this.event = event;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.characters = characters;
        }
    }

    public static Event parseEvent(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject h = o.optJSONObject("header");
            if (h == null) return null;
            int chars = -1;
            JSONObject p = o.optJSONObject("payload");
            if (p != null) {
                JSONObject u = p.optJSONObject("usage");
                if (u != null) chars = u.optInt("characters", -1);
            }
            return new Event(h.optString("event", ""), h.optString("error_code", ""), h.optString("error_message", ""), chars);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String runTaskJson(String taskId, String model, String voice, int sampleRate) {
        try {
            JSONObject params = new JSONObject();
            params.put("text_type", "PlainText");
            params.put("voice", voice);
            params.put("format", "pcm");
            params.put("sample_rate", sampleRate);
            params.put("volume", 50);
            params.put("rate", 1);
            params.put("pitch", 1);
            JSONObject payload = new JSONObject();
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", model);
            payload.put("parameters", params);
            payload.put("input", new JSONObject());
            return envelope("run-task", taskId, payload);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static String continueTaskJson(String taskId, String text) {
        try {
            JSONObject input = new JSONObject();
            input.put("text", text);
            JSONObject payload = new JSONObject();
            payload.put("input", input);
            return envelope("continue-task", taskId, payload);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static String finishTaskJson(String taskId) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("input", new JSONObject());
            return envelope("finish-task", taskId, payload);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static String envelope(String action, String taskId, JSONObject payload) throws Exception {
        JSONObject header = new JSONObject();
        header.put("action", action);
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        JSONObject o = new JSONObject();
        o.put("header", header);
        o.put("payload", payload);
        return o.toString();
    }

    /** 从 WebSocketClient 的升级失败信息（"WebSocket 升级失败: HTTP 401 (…)"）里取状态码；取不到 -1。 */
    static int parseHttpCode(String msg) {
        if (msg == null) return -1;
        int i = msg.indexOf("HTTP ");
        if (i < 0) return -1;
        int j = i + 5, k = j;
        while (k < msg.length() && Character.isDigit(msg.charAt(k))) k++;
        if (k == j) return -1;
        try {
            return Integer.parseInt(msg.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
