package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.util.IoUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 HTTP 客户端（P1-1）。
 *
 * <p>背景：审计发现 7 处各自手写 {@code HttpURLConnection}（超时、写 body、读 errorStream、读全文），
 * 失败一律折叠成 null / 字符串，上层无法分辨"鉴权失败 / 超时 / 断网 / 服务端错误"。
 * 本类收敛这一套，并给出结构化的 {@link Response#kind}。
 *
 * <p>约定：
 * <ul>
 *   <li><b>从不抛异常</b>：网络异常落在 {@link Response#error}，{@link Response#code} = -1；</li>
 *   <li>2xx 读 inputStream，其余读 errorStream（可能为空串）—— 与原 asr 三处实现一致；</li>
 *   <li>阻塞调用，必须在后台线程（Android 主线程会 NetworkOnMainThreadException）；</li>
 *   <li>纯 java.net，无 android 依赖，可在 JVM 单测里用 {@code com.sun.net.httpserver} 桩服务测。</li>
 * </ul>
 *
 * <p>迁移状态：search/**、asr/** 已迁移；router/**（L2LLMCompiler、SseStream）与 vision/** 由其它改造负责
 * （router 侧计划在 net/LlmClient 之上复用本类，{@link #stream} 即为 SSE 预留的流式钩子）。
 */
public final class HttpClient {

    /** 失败类别（上层据此给出可操作的文案 / 错误码）。 */
    public enum Kind {
        /** 2xx */
        OK,
        /** 401 / 403 */
        AUTH,
        /** 其它非 2xx 状态码 */
        HTTP_ERROR,
        /** 连接或读取超时 */
        TIMEOUT,
        /** DNS / 连接被拒 / 被掐断 / TLS 等网络层错误 */
        NETWORK,
        /** 请求被取消（线程中断），不是超时也不是网络问题（2026-10-24 r2/fixes） */
        CANCELLED
    }

    /** 请求描述（Builder 风格，Java 7 友好）。 */
    public static final class Request {
        final String url;
        String method = "GET";
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body;
        int connectTimeoutMs = 10000;
        int readTimeoutMs = 15000;
        boolean fixedLength;

        public Request(String url) {
            this.url = url;
        }

        public Request method(String m) {
            this.method = m;
            return this;
        }

        public Request header(String k, String v) {
            if (k != null && v != null) headers.put(k, v);
            return this;
        }

        public Request body(byte[] b) {
            this.body = b;
            return this;
        }

        /** 设 body 并声明 {@code Content-Type: application/json; charset=UTF-8}。 */
        public Request json(String json) {
            try {
                this.body = (json == null ? "" : json).getBytes(IoUtil.UTF8);
            } catch (java.io.UnsupportedEncodingException e) {
                this.body = new byte[0];
            }
            if (!headers.containsKey("Content-Type")) {
                headers.put("Content-Type", "application/json; charset=UTF-8");
            }
            return this;
        }

        public Request timeouts(int connectMs, int readMs) {
            this.connectTimeoutMs = connectMs;
            this.readTimeoutMs = readMs;
            return this;
        }

        /** 使用 setFixedLengthStreamingMode（大 body 时避免整段缓冲；阿里云 NLS 用）。 */
        public Request fixedLength(boolean on) {
            this.fixedLength = on;
            return this;
        }
    }

    /** 响应（code=-1 表示没拿到 HTTP 状态，看 error）。 */
    public static final class Response {
        public final int code;
        public final String body;
        public final Throwable error;
        public final long latencyMs;
        public final Kind kind;

        Response(int code, String body, Throwable error, long latencyMs) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.error = error;
            this.latencyMs = latencyMs;
            this.kind = classify(code, error);
        }

        public boolean isOk() {
            return kind == Kind.OK;
        }

        /** 形如 {@code "SocketTimeoutException: connect timed out"}，无异常时为 ""。 */
        public String errorText() {
            return error == null ? "" : error.getClass().getSimpleName() + ": " + error.getMessage();
        }

        @Override
        public String toString() {
            return "HttpResponse{code=" + code + ", kind=" + kind + ", " + latencyMs + "ms"
                    + (error != null ? ", err=" + errorText() : "") + "}";
        }
    }

    /** 流式钩子：逐行回调（SSE 用）。返回 false 立即停止读取。 */
    public interface LineListener {
        boolean onLine(String line);
    }

    private HttpClient() {}

    /** 是否是携带凭据的请求头（Authorization / 各家 *-Key 头）。 */
    public static boolean isCredentialHeader(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("authorization") || n.endsWith("-key") || n.endsWith("api-key") || n.contains("token");
    }

    // ------------------------------------------------------------------ 便捷方法

    /** POST JSON。{@code headers} 可为 null。 */
    public static Response postJson(String url, Map<String, String> headers, String json,
                                    int connectMs, int readMs) {
        Request r = new Request(url).method("POST").timeouts(connectMs, readMs);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) r.header(e.getKey(), e.getValue());
        }
        return execute(r.json(json));
    }

    /** GET。{@code headers} 可为 null。 */
    public static Response get(String url, Map<String, String> headers, int connectMs, int readMs) {
        Request r = new Request(url).method("GET").timeouts(connectMs, readMs);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) r.header(e.getKey(), e.getValue());
        }
        return execute(r);
    }

    /** {@code "Bearer " + key} 的单头 Map（常用于 OpenAI 兼容端点）。 */
    public static Map<String, String> bearer(String key) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (key != null) m.put("Authorization", "Bearer " + key);
        return m;
    }

    // ------------------------------------------------------------------ 核心

    /** 发请求并读完整个响应体。从不抛异常。 */
    public static Response execute(Request req) {
        return doExecute(req, null);
    }

    /**
     * 流式：2xx 时把响应体逐行交给 {@code listener}（返回体 body 为空串）；非 2xx 时读完 errorStream 放进 body。
     * 供 SSE（{@code data: ...} 行）使用。
     */
    public static Response stream(Request req, LineListener listener) {
        return doExecute(req, listener);
    }

    /**
     * ★ R14（diag B 节第 6 条）：成功路径<b>不再</b> {@code disconnect()}。
     *
     * <p>Android 的 HttpURLConnection 是 OkHttp 2.7（com.android.okhttp）：{@code disconnect()} → {@code HttpEngine.cancel()}
     * → {@code StreamAllocation.cancel()} → 连接还挂在 allocation 上时直接关 socket，连接回不了连接池，下一次请求又是冷连接
     * （TCP + TLS 约 360 ms，沙箱对照：冷连接 737–771 ms、同连接复用 391–454 ms）。
     * 官方用法是「读完响应体并关流」，连接自动回池；只有出错（流没读完、状态不明）时才 disconnect 释放。
     * 所以现在：响应体读完（readAll / readLines 都会关流）→ 不 disconnect；任何异常 → disconnect。
     */
    private static Response doExecute(Request req, LineListener listener) {
        long t0 = System.nanoTime();
        HttpURLConnection conn = null;
        boolean clean = false;
        // ★ feat/api-first [api-first]：停止键真中断 —— 已停就不发；发出去的连接登记进当前停止范围，停止时立刻断开
        if (StopScope.currentStopped()) return new Response(-1, "", new java.io.InterruptedIOException("已停止"), 0L);
        Runnable unregister = StopScope.NOOP;
        try {
            conn = (HttpURLConnection) new URL(req.url).openConnection();
            final HttpURLConnection fc = conn;
            unregister = StopScope.onStop(new Runnable() {
                @Override public void run() {
                    fc.disconnect();
                }
            });
            conn.setRequestMethod(req.method);
            conn.setConnectTimeout(req.connectTimeoutMs);
            conn.setReadTimeout(req.readTimeoutMs);
            boolean keyed = false;
            for (Map.Entry<String, String> e : req.headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
                if (isCredentialHeader(e.getKey())) keyed = true;
            }
            // OneSay：带凭据的请求不跟随重定向（Key 不会被带到别的主机）
            if (keyed) conn.setInstanceFollowRedirects(false);
            if (req.body != null) {
                conn.setDoOutput(true);
                if (req.fixedLength) conn.setFixedLengthStreamingMode(req.body.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(req.body);
                    os.flush();
                } finally {
                    IoUtil.closeQuietly(os);
                }
            }
            int code = conn.getResponseCode();
            boolean ok2xx = code >= 200 && code < 300;
            InputStream is = ok2xx ? conn.getInputStream() : conn.getErrorStream();
            String body;
            if (ok2xx && listener != null) {
                readLines(is, listener);
                body = "";
            } else {
                body = is == null ? "" : IoUtil.readAll(is);
            }
            clean = true;
            return new Response(code, body, null, ms(t0));
        } catch (Throwable t) {
            // ★ [api-first] 被停止键断开的连接按「已取消」报（不是网络错误）
            if (StopScope.currentStopped()) t = new java.io.InterruptedIOException("已停止");
            return new Response(-1, "", t, ms(t0));
        } finally {
            unregister.run();
            if (conn != null && (!clean || !KEEP_ALIVE)) conn.disconnect();
        }
    }

    /** 成功路径保留连接（回连接池）。出问题时可以把它关掉回到旧行为。 */
    static volatile boolean KEEP_ALIVE = true;

    /**
     * ★ R14：连接预热（HEAD，读完即关流、不 disconnect → TCP + TLS 留在连接池）。从不抛异常。
     * 返回的 code 无所谓（DashScope 对 HEAD 可能是 404/405），能握手就达到目的。
     */
    public static Response prewarm(String url, int timeoutMs) {
        return execute(new Request(url).method("HEAD").timeouts(timeoutMs, timeoutMs));
    }

    /**
     * 审计 B-5：在一个调用方已配置好的连接上写请求体、读响应体（2xx 读 inputStream，否则读 errorStream），
     * <b>无论成功还是抛异常都关流并 disconnect</b>；{@code getErrorStream()} 为 null 时 body 为 ""。
     * IO 异常原样抛给调用方（调用方原有的 catch 语义不变）。
     */
    public static Response exchange(HttpURLConnection conn, byte[] body) throws java.io.IOException {
        long t0 = System.nanoTime();
        boolean ok = false;
        try {
            if (body != null) {
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                    os.flush();
                } finally {
                    IoUtil.closeQuietly(os);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = is == null ? "" : IoUtil.readAll(is);   // readAll 读完即关
            Response r = new Response(code, text, null, ms(t0));
            ok = true;
            return r;
        } finally {
            if (!ok || !KEEP_ALIVE) conn.disconnect();   // ★ R14：读完即回池，见 doExecute 注释
        }
    }

    private static void readLines(InputStream is, LineListener l) throws java.io.IOException {
        if (is == null) return;
        BufferedReader br = new BufferedReader(new InputStreamReader(is, IoUtil.UTF8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (!l.onLine(line)) break;
            }
        } finally {
            IoUtil.closeQuietly(br);
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }

    /** 纯函数：状态码 + 异常 → 类别。public 便于单测与上层复用。 */
    public static Kind classify(int code, Throwable error) {
        if (error != null) {
            for (Throwable c = error; c != null; c = c.getCause()) {
                if (c instanceof SocketTimeoutException) return Kind.TIMEOUT;
                // SocketTimeoutException 也是 InterruptedIOException 的子类，所以排在它后面：剩下的是线程被中断 = 取消
                if (c instanceof java.io.InterruptedIOException || c instanceof InterruptedException
                        || c.getClass().getName().endsWith("ClosedByInterruptException")) return Kind.CANCELLED;
                if (c.getCause() == c) break;
            }
            return Kind.NETWORK;
        }
        if (code >= 200 && code < 300) return Kind.OK;
        if (code == 401 || code == 403) return Kind.AUTH;
        return Kind.HTTP_ERROR;
    }
}
