package com.onesay.assistant.v2.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次要发出去的 HTTP 请求（纯数据，JVM 可测）。
 *
 * <p>Key <b>不在</b>这里：只记 Key 放进哪个请求头（{@link #keyHeader}）和前缀（{@link #keyPrefix}，如 {@code "Bearer "}）；
 * 真正发送时才由 {@link ProviderHttp} 现取现填，所以请求对象可以随便打日志、进测试快照。
 * Key 也绝不放进 URL 查询串（Gemini 用 {@code x-goog-api-key} 头，不用 {@code ?key=}）。
 */
public final class WireRequest {
    public final String method;
    public final String url;
    /** 不含 Key 的请求头（按插入顺序）。 */
    public final Map<String, String> headers;
    public final String keyHeader;
    public final String keyPrefix;
    /** POST 正文；GET 为 null。 */
    public final String body;
    public final boolean stream;

    public WireRequest(String method, String url, Map<String, String> headers, String keyHeader, String keyPrefix, String body,
                       boolean stream) {
        this.method = method;
        this.url = url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers == null
                ? new LinkedHashMap<String, String>() : headers));
        this.keyHeader = keyHeader;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.body = body;
        this.stream = stream;
    }

    @Override public String toString() {
        return method + " " + url + " " + headers + " key->" + keyHeader;
    }
}
