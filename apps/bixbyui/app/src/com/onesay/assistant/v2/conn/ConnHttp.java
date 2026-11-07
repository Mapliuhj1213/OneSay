package com.onesay.assistant.v2.conn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [conn] 连接器层的 HTTP 抽象（2026-10-22 B 线）。
 *
 * <p>为什么不直接用 {@code net.HttpClient}：MCP 需要读响应头（{@code Mcp-Session-Id}、{@code Content-Type}）、
 * 需要逐行读 SSE 长连接；Google 需要表单 POST、PATCH / DELETE。这里只定义最小接口，真机实现 {@link UrlConnHttp}，
 * 单测用假实现（不触网）。
 *
 * <p>约定：实现方<b>不得抛异常</b>，网络错误收敛到 {@link Resp#error}；<b>不得记录</b>请求头和请求体（里面有令牌）。
 */
public interface ConnHttp {

    /** 普通请求：读完整个响应体。 */
    Resp send(Req req);

    /**
     * 流式请求（SSE）：响应体逐行交给 sink，sink 返回 false 时立即断开。
     * 返回的 Resp.body 为空串，code / headers / error 照常。
     */
    Resp stream(Req req, LineSink sink);

    interface LineSink {
        /** @return false = 不再需要更多数据，断开连接 */
        boolean onLine(String line);
    }

    final class Req {
        public final String method;
        public final String url;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public byte[] body;
        public int connectMs = 8000;
        public int readMs = 20000;

        public Req(String method, String url) {
            this.method = method;
            this.url = url;
        }

        public Req header(String k, String v) {
            if (k != null && v != null) headers.put(k, v);
            return this;
        }

        public Req body(String s, String contentType) {
            try {
                body = s == null ? null : s.getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                body = null;
            }
            if (contentType != null) headers.put("Content-Type", contentType);
            return this;
        }

        public Req timeouts(int connect, int read) {
            connectMs = connect;
            readMs = read;
            return this;
        }
    }

    final class Resp {
        public final int code;
        /** 头名一律小写。 */
        public final Map<String, String> headers;
        public final String body;
        public final Throwable error;

        public Resp(int code, Map<String, String> headers, String body, Throwable error) {
            this.code = code;
            this.headers = headers != null ? headers : new LinkedHashMap<String, String>();
            this.body = body != null ? body : "";
            this.error = error;
        }

        public static Resp fail(Throwable t) {
            return new Resp(-1, null, "", t);
        }

        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        public boolean ok() {
            return error == null && code >= 200 && code < 300;
        }

        /** 网络层失败（没拿到 HTTP 状态）。 */
        public boolean networkError() {
            return code < 0;
        }
    }
}
