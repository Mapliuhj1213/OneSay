package com.onesay.assistant.v2.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 路由层共用的 LLM 客户端（P1-1，仅服务 router 包）。
 *
 * <p>一份 HTTP 实现 + 一份响应解析（{@link LlmResponse}），替代原先
 * {@code L2LLMCompiler.httpPost}、{@code SseStream.post} 里各自的建连/读体代码。
 * 适用于任何 OpenAI 兼容端点（DeepSeek、DashScope 兼容模式）。
 *
 * <p>刻意直接用 {@link HttpURLConnection}：执行层另有通用 HttpClient 在建设中，
 * 本类不依赖它，避免两边同时改动同一文件。
 *
 * <p>无 Android 依赖（不打 Log），可在 JVM 单测里用本地 HttpServer 测。
 */
public final class LlmClient {

    /** 预置值；实际请求地址一律取 {@link LlmEndpoints}（用户可在「API 密钥」页改）。 */
    public static final String DEEPSEEK_URL = LlmEndpoints.DEEPSEEK_PRESET + "/chat/completions";
    public static final String DASHSCOPE_COMPAT_URL = LlmEndpoints.DASHSCOPE_PRESET + "/chat/completions";

    /** 取 Key 的方式（Key 存在 SharedPreferences 里，可能运行中被改，所以每次现取）。 */
    public interface KeyProvider {
        String apiKey();
    }

    private final String mUrl;
    private final KeyProvider mKey;
    private final int mConnectTimeoutMs;
    private final int mReadTimeoutMs;
    private final String mTag;
    /** 单次 {@link #post}（含重试）总时长上限（ms）。 */
    private final int mTotalMs;
    /** ★ borrow-1112：这是备用模型的客户端（失败不再换）。 */
    private final boolean mBackup;

    // 空响应体 / 首字节超时重试 1 次 + 连接 / 首字节 / 总时长分开（用户 11-03 17:4x）。
    //   旧行为：一个 8 s 连接 + 12 s 读超时、失败不重试 —— AgentLoop 第 5 轮一次读超时就整任务 blocked（股市句 58 s 收尾）。
    /** 空响应体后重试前的间隔。 */
    public static final int EMPTY_RETRY_DELAY_MS = 300;
    /** 主模型调用的连接超时 / 首字节超时（读超时）。 */
    public static final int DEFAULT_CONNECT_MS = 10000;
    public static final int DEFAULT_FIRST_BYTE_MS = 20000;
    /** 单次 post（含重试）总时长上限。 */
    public static final int MAX_TOTAL_MS = 60000;
    /** 首字节到了之后给读完正文的额外时间（单次尝试上限 = 连接 + 首字节 + 这个）。 */
    public static final int BODY_GRACE_MS = 15000;
    /** 首字节超时重试只对「慢调用」做（读超时 ≥ 这个值）；分诊 1.5 s 这类快速兜底超时不重试，免得拖慢首响。 */
    public static final int TIMEOUT_RETRY_MIN_READ_MS = 10000;
    /** 单测可调低（同包）；生产恒为 {@link #TIMEOUT_RETRY_MIN_READ_MS}。 */
    static volatile int sTimeoutRetryMinReadMs = TIMEOUT_RETRY_MIN_READ_MS;

    /** 请求日志出口（只收 HTTP 码 / 耗时 / 长度这类数字；绝不含 key、请求头、请求体）。null = 不记。 */
    public interface Trace {
        void line(String s);
    }

    private static volatile Trace sTrace;

    public static void setTrace(Trace t) { sTrace = t; }

    public LlmClient(String url, KeyProvider key, int connectTimeoutMs, int readTimeoutMs) {
        this(url, key, connectTimeoutMs, readTimeoutMs, "llm", MAX_TOTAL_MS);
    }

    private LlmClient(String url, KeyProvider key, int connectTimeoutMs, int readTimeoutMs, String tag, int totalMs) {
        this(url, key, connectTimeoutMs, readTimeoutMs, tag, totalMs, false);
    }

    private LlmClient(String url, KeyProvider key, int connectTimeoutMs, int readTimeoutMs, String tag, int totalMs, boolean backup) {
        this.mBackup = backup;
        this.mUrl = url;
        this.mKey = key;
        this.mConnectTimeoutMs = connectTimeoutMs;
        this.mReadTimeoutMs = readTimeoutMs;
        this.mTag = tag == null ? "llm" : tag;
        this.mTotalMs = totalMs > 0 ? totalMs : MAX_TOTAL_MS;
    }

    public int connectTimeoutMs() { return mConnectTimeoutMs; }

    public int readTimeoutMs() { return mReadTimeoutMs; }

    public int totalMs() { return mTotalMs; }

    /** 日志里的调用名（triage / agent / errand…）。 */
    public LlmClient withTag(String tag) {
        return new LlmClient(mUrl, mKey, mConnectTimeoutMs, mReadTimeoutMs, tag, mTotalMs);
    }

    /** 测试 / 特殊调用方改总时长上限。 */
    public LlmClient withTotalMs(int totalMs) {
        return new LlmClient(mUrl, mKey, mConnectTimeoutMs, mReadTimeoutMs, mTag, totalMs);
    }

    public String url() { return mUrl; }

    public String apiKey() {
        String k = mKey != null ? mKey.apiKey() : null;
        return k == null ? "" : k;
    }

    public boolean hasKey() {
        return apiKey().trim().length() > 0;
    }

    /** 以相同端点/Key、不同读超时派生一个客户端（例如分诊分类只给 1.5 s）。 */
    public LlmClient withReadTimeout(int readTimeoutMs) {
        return new LlmClient(mUrl, mKey, mConnectTimeoutMs, readTimeoutMs, mTag, mTotalMs);
    }

    // ---------------- 请求体 ----------------

    /**
     * 组装标准 chat/completions 请求体。
     *
     * @param tools null/空 时不带 tools 字段
     */
    public static JSONObject body(String model, double temperature, int maxTokens,
                                  JSONArray messages, JSONArray tools, boolean stream) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("temperature", temperature);
        req.put("max_tokens", maxTokens);
        if (stream) req.put("stream", true);
        req.put("messages", messages);
        if (tools != null && tools.length() > 0) {
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        return LlmModels.prepare(req);   // ★ int/1102：模型名走 LlmModels（deepseek-chat 已停用 → deepseek-flash + 关思考）
    }

    /** system + user 两条消息的便捷构造。 */
    public static JSONArray messages(String system, String user) throws Exception {
        JSONArray arr = new JSONArray();
        if (system != null) {
            JSONObject s = new JSONObject();
            s.put("role", "system");
            s.put("content", system);
            arr.put(s);
        }
        JSONObject u = new JSONObject();
        u.put("role", "user");
        u.put("content", user == null ? "" : user);
        arr.put(u);
        return arr;
    }

    // ---------------- 非流式 ----------------

    /**
     * 发起一次非流式请求，返回解析后的响应（永不为 null）。
     *
     * <p>★ 11-03：最多重试 1 次 ——
     * <ul>
     *   <li>200 但空响应体：隔 {@link #EMPTY_RETRY_DELAY_MS} 再发一次；</li>
     *   <li>连接超时 / 首字节（响应头）超时：读超时 ≥ {@link #TIMEOUT_RETRY_MIN_READ_MS} 的调用、且剩余总时长还够一次
     *       「连接 + 首字节」时立刻再发一次；</li>
     * </ul>
     * 每次尝试另有硬上限（连接 + 首字节 + {@link #BODY_GRACE_MS}，不超过剩余总时长），到点断开连接；
     * 总时长不超过 {@link #totalMs()}。两次都失败才把失败交回调用方走原兜底。
     */
    public LlmResponse post(String jsonBody) {
        long t0 = System.currentTimeMillis();
        Attempt a = attempt(jsonBody, 1, t0);
        Attempt b = null;
        if (a.emptyBody) {
            trace("[LLM] " + mTag + " 空响应体 → " + EMPTY_RETRY_DELAY_MS + " ms 后重试 1 次");
            sleep(EMPTY_RETRY_DELAY_MS);
            if (remaining(t0) > mConnectTimeoutMs / 2) b = attempt(jsonBody, 2, t0);
        } else if (a.noFirstByte && mReadTimeoutMs >= sTimeoutRetryMinReadMs
                && remaining(t0) >= (long) mConnectTimeoutMs + mReadTimeoutMs) {
            trace("[LLM] " + mTag + " " + (a.connectTimeout ? "连接" : "首字节") + "超时 → 取消并重试 1 次");
            b = attempt(jsonBody, 2, t0);
        }
        LlmResponse r = (b != null ? b : a).res;
        return mBackup ? r : maybeFallback(jsonBody, r);
    }

    /**
     * ★ borrow-1112：主模型超时 / 5xx / 429 → 换到已配置的备用模型再发一次（{@link LlmFallback}）。
     * 没配置备用、或主备是同一个 → 原样返回主模型的失败（照旧报错）；备用也失败 → 返回主模型的失败。
     */
    private LlmResponse maybeFallback(String jsonBody, LlmResponse r) {
        if (!LlmFallback.shouldSwitch(r)) return r;
        LlmFallback.Backup bk = LlmFallback.current();
        if (bk == null || LlmFallback.same(mUrl, LlmModels.prepareJson(jsonBody), bk)) return r;
        String why = LlmFallback.why(r);
        trace("[LLM] " + mTag + " " + why + " → 切到备用模型 " + bk.label);
        LlmClient c2 = new LlmClient(bk.url, bk.key, mConnectTimeoutMs, mReadTimeoutMs, mTag + "-backup", mTotalMs, true);
        LlmResponse r2 = c2.post(LlmFallback.rewrite(LlmModels.prepareJson(jsonBody), bk));
        com.onesay.assistant.v2.timeline.TimelineNotes.post(LlmFallback.NOTE + "（" + bk.label + "，" + why + "）" + (r2.ok() ? "" : "，备用也没成功"));
        return r2.ok() ? r2 : r;
    }

    /** 一次尝试的结果（重试判定用）。 */
    static final class Attempt {
        LlmResponse res;
        boolean emptyBody;
        boolean noFirstByte;
        boolean connectTimeout;
    }

    private Attempt attempt(String jsonBody, int no, long t0) {
        Attempt at = new Attempt();
        long s = System.currentTimeMillis();
        long cap = Math.min(remaining(t0), (long) mConnectTimeoutMs + mReadTimeoutMs + BODY_GRACE_MS);
        final HttpURLConnection[] holder = new HttpURLConnection[1];
        final boolean[] killed = new boolean[1];
        java.util.TimerTask kill = new java.util.TimerTask() {
            @Override public void run() {
                synchronized (holder) {
                    killed[0] = true;
                    if (holder[0] != null) { try { holder[0].disconnect(); } catch (Throwable ignored) {} }
                }
            }
        };
        watchdog().schedule(kill, Math.max(1L, cap));
        int code = -1;
        int len = -1;
        boolean headers = false;
        try {
            HttpURLConnection conn = connection();
            synchronized (holder) { holder[0] = conn; }
            if (killed[0]) throw new java.net.SocketTimeoutException("total cap");
            send(conn, jsonBody, false);
            code = conn.getResponseCode();
            headers = true;
            if (code != 200) {
                String eb = drain(conn.getErrorStream(), 2000);
                len = eb == null ? 0 : eb.length();
                at.res = LlmResponse.failure(code, eb, "HTTP " + code);
            } else {
                String body = drain0(conn.getInputStream(), Integer.MAX_VALUE);
                len = body == null ? 0 : body.length();
                at.res = LlmResponse.parse(code, body);
                at.emptyBody = body == null || body.trim().length() == 0;
            }
        } catch (Throwable t) {
            boolean timeout = t instanceof java.net.SocketTimeoutException || killed[0];
            at.noFirstByte = timeout && !headers;
            at.connectTimeout = at.noFirstByte && isConnectTimeout(t);
            String what = killed[0] ? "总时长到(" + cap + "ms)" : at.connectTimeout ? "连接超时" : at.noFirstByte ? "首字节超时" : null;
            at.res = LlmResponse.failure(-1, null, what != null ? what
                    : "连接异常(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        } finally {
            kill.cancel();
            synchronized (holder) { if (holder[0] != null) { try { holder[0].disconnect(); } catch (Throwable ignored) {} } }
        }
        trace(attemptLine(mTag, no, code, System.currentTimeMillis() - s, len, at.res.ok() ? null : at.res.error));
        return at;
    }

    /** 请求日志一行：只有调用名、第几次、HTTP 码、耗时、返回体长度、失败类别（不含 key / 请求头 / 请求体 / 响应原文）。 */
    public static String attemptLine(String tag, int attempt, int httpCode, long ms, int bodyLen, String error) {
        String err = error;
        if (err != null && err.startsWith("连接异常(")) {   // 只留异常类名，不带 message（可能含主机名等）
            int c = err.indexOf(':');
            err = c > 0 ? err.substring(0, c) + ")" : err;
        }
        return "[LLM] " + tag + " try=" + attempt + " http=" + httpCode + " ms=" + ms + " len=" + bodyLen
                + (err == null ? " ok" : " fail=" + err);
    }

    private static boolean isConnectTimeout(Throwable t) {
        String m = t.getMessage();
        return t instanceof java.net.SocketTimeoutException && m != null && m.toLowerCase(java.util.Locale.ROOT).contains("connect");
    }

    private long remaining(long t0) {
        return mTotalMs - (System.currentTimeMillis() - t0);
    }

    /** 重试前的等待（JVM 单测注入记录桩，不依赖墙钟；生产 = Thread.sleep）。 */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    static volatile Sleeper sSleeper = null;

    private static void sleep(long ms) {
        try {
            Sleeper sl = sSleeper;
            if (sl != null) sl.sleep(ms);
            else Thread.sleep(ms);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void trace(String s) {
        Trace t = sTrace;
        if (t != null) { try { t.line(s); } catch (Throwable ignored) {} }
    }

    private static java.util.Timer sWatchdog;

    private static synchronized java.util.Timer watchdog() {
        if (sWatchdog == null) sWatchdog = new java.util.Timer("llm-watchdog", true);
        return sWatchdog;
    }

    // ---------------- 流式（SSE） ----------------

    /** 流式增量接收端：返回 false 表示调用方要求中断。 */
    public interface StreamSink {
        /** @return 是否继续读 */
        boolean onLine(String line);
    }

    /** 流式请求结果（只关心建连与读完；增量解析由调用方按 {@link LlmResponse#deltaOf} 做）。 */
    public static final class StreamStatus {
        public int httpCode;
        public String error;
        public boolean aborted;
    }

    /**
     * 发起一次流式请求，逐行回调直到 [DONE]、流结束、或 sink 返回 false。
     * 异常会以 {@link StreamStatus#error} 返回而不是抛出。
     */
    public StreamStatus stream(String jsonBody, StreamSink sink) {
        StreamStatus st = new StreamStatus();
        HttpURLConnection conn = null;
        BufferedReader in = null;
        long s = System.currentTimeMillis();
        int chars = 0;
        try {
            conn = open(jsonBody, true);
            st.httpCode = conn.getResponseCode();
            if (st.httpCode != 200) {
                st.error = "HTTP " + st.httpCode + " " + drain(conn.getErrorStream(), 400);
                return st;
            }
            in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                chars += line.length();
                if (LlmResponse.isDoneLine(line)) break;
                if (!sink.onLine(line)) {
                    st.aborted = true;
                    break;
                }
            }
            return st;
        } catch (Throwable t) {
            st.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return st;
        } finally {
            trace(attemptLine(mTag + "/sse", 1, st.httpCode, System.currentTimeMillis() - s, chars,
                    st.error == null ? null : (st.error.startsWith("HTTP ") ? "HTTP " + st.httpCode : "连接异常(" + st.error + ")")));
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- 内部 ----------------

    private HttpURLConnection open(String jsonBody, boolean stream) throws Exception {
        HttpURLConnection conn = connection();
        send(conn, jsonBody, stream);
        return conn;
    }

    private HttpURLConnection connection() throws Exception {
        // ★ feat/api-first [api-first]：停止键真中断 —— 已停就不发；连接登记进当前停止范围（任务结束时范围整体丢弃）
        if (StopScope.currentStopped()) throw new java.io.InterruptedIOException("已停止");
        final HttpURLConnection c = (HttpURLConnection) new URL(mUrl).openConnection();
        StopScope.onStop(new Runnable() {
            @Override public void run() {
                c.disconnect();
            }
        });
        return c;
    }

    private void send(HttpURLConnection conn, String jsonBody, boolean stream) throws Exception {
        jsonBody = LlmModels.prepareJson(jsonBody);   // ★ int/1102：所有经本客户端的请求统一换模型名 / 补关思考
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (stream) {
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
        }
        if (!LlmEndpoints.keyAllowedFor(mUrl)) throw new java.io.IOException("拒绝：大模型 Key 只发往已配置的 https 主机");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey());
        conn.setConnectTimeout(mConnectTimeoutMs);
        conn.setReadTimeout(mReadTimeoutMs);
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write((jsonBody == null ? "" : jsonBody).getBytes("UTF-8"));
        os.flush();
        os.close();
    }

    /** 读正文；异常照抛（超时 / 被总时长看门狗断开要让调用方知道，不能当成空响应体）。 */
    private static String drain0(InputStream is, int maxChars) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l);
                if (sb.length() >= maxChars) break;
            }
            return sb.toString();
        } finally {
            try { br.close(); } catch (Throwable ignored) {}
        }
    }

    private static String drain(InputStream is, int maxChars) {
        if (is == null) return "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l);
                if (sb.length() >= maxChars) break;
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return "";
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
        }
    }
}
