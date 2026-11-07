package com.onesay.assistant.v2.search;

import com.onesay.assistant.v2.net.HttpClient;

import java.net.URLEncoder;

/**
 * 搜索通道共用的 HTTP 工具（零第三方依赖；底层统一走 {@link HttpClient}）。
 *
 * <p>约定：所有方法**都不抛异常**，失败一律返回 {@code null}。
 * 这是刻意的 —— 搜索只是"给模型补资料"，它失败不该让整条问答链路崩掉。
 *
 * <p>⚠️ 所有方法都是阻塞的，必须在后台线程调用（Android 主线程会直接抛
 * {@code NetworkOnMainThreadException}）。
 */
final class SearchHttp {

    /**
     * 桌面 UA。必应网页版对移动 UA 返回的 HTML 结构不一样（更多脚本渲染），
     * 用桌面 UA 拿到的静态 HTML 更好解析。
     */
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private SearchHttp() {}

    /** POST JSON，可选 Bearer 头。失败返回 null。（底层改走 net.HttpClient） */
    static String post(String urlStr, String authHeader, String jsonBody,
                       int connectMs, int readMs) {
        HttpClient.Request r = new HttpClient.Request(urlStr).method("POST")
                .timeouts(connectMs, readMs).json(jsonBody);
        if (authHeader != null) r.header("Authorization", authHeader);
        HttpClient.Response resp = HttpClient.execute(r);
        return resp.code == 200 ? resp.body : null;
    }

    /** GET（带浏览器 UA 与中文语言偏好）。失败返回 null。 */
    static String get(String urlStr, int connectMs, int readMs) {
        HttpClient.Response resp = HttpClient.execute(new HttpClient.Request(urlStr).method("GET")
                .timeouts(connectMs, readMs)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml"));
        return resp.code == 200 ? resp.body : null;
    }

    /** URL 编码（路径安全，空格转 %20）。 */
    static String encode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    /** 简易 HTML 反转义 + 去标签（够用于提取标题/摘要）。 */
    static String stripHtml(String s) {
        if (s == null) return "";
        String r = s.replaceAll("<[^>]+>", "");
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
             .replace("&#x27;", "'").replace("&hellip;", "…")
             // 必应网页版在时间戳两侧塞的是 ensp/thinsp（实测形如
             // "16 小时之前&ensp;&#0183;&ensp;正文…"），不处理会留下乱码残渣
             .replace("&ensp;", " ").replace("&emsp;", " ").replace("&thinsp;", " ");
        r = r.replaceAll("&#\\d+;", " ");
        return r.replaceAll("\\s{2,}", " ").trim();
    }
}
