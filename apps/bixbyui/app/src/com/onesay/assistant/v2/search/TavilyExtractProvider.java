package com.onesay.assistant.v2.search;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ lane C3：Tavily extract（抽网页正文）。浏览器跑腿「升级链」的第一档后半段：search 摘要不够时，
 * 先用 extract 把前几条结果（或用户给的链接）的正文抽出来，还不够才开内置 WebView。
 *
 * <pre>
 *   POST https://api.tavily.com/extract
 *   Headers: Authorization: Bearer tvly-xxxxxxxx
 *   Body   : {"urls":["https://…"],"query":"用户问题","chunks_per_source":3,"extract_depth":"basic","format":"text"}
 *   响应    : {"results":[{"url":"..","raw_content":".."}],"failed_results":[{"url":"..","error":".."}]}
 * </pre>
 * 文档：https://docs.tavily.com/documentation/api-reference/endpoint/extract（HTTP 200 也可能整批失败，两个数组都要看）。
 * Key 与 {@link TavilySearchProvider} 共用（{@link SearchConfig#getTavilyKey()}）。
 */
public final class TavilyExtractProvider {

    private static final String TAG = "Search/TavilyExtract";
    private static final String ENDPOINT = "https://api.tavily.com/extract";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** 一次最多抽几个链接（basic 每 5 个成功算 1 次额度）。 */
    public static final int MAX_URLS = 5;
    /** 单页正文保留上限（拼进 prompt 前再截）。 */
    public static final int MAX_CHARS_PER_PAGE = 6000;

    /** 一页抽取结果（不可变）。 */
    public static final class Page {
        public final String url;
        public final String content;
        public final String error;

        public Page(String url, String content, String error) {
            this.url = url == null ? "" : url.trim();
            String c = content == null ? "" : content.trim();
            this.content = c.length() > MAX_CHARS_PER_PAGE ? c.substring(0, MAX_CHARS_PER_PAGE) : c;
            this.error = error;
        }

        public boolean ok() {
            return error == null && content.length() > 0;
        }

        @Override
        public String toString() {
            return url + (ok() ? " (" + content.length() + "字)" : " ✗ " + error);
        }
    }

    private final SearchConfig mConfig;

    public TavilyExtractProvider(SearchConfig config) {
        mConfig = config;
    }

    public boolean isReady() {
        return mConfig != null && mConfig.getTavilyKey().length() > 0;
    }

    /** 阻塞（后台线程）。永不返回 null；失败的链接以 error 非空的 Page 返回。 */
    public List<Page> extract(List<String> urls, String query) {
        List<Page> out = new ArrayList<Page>();
        if (!isReady() || urls == null || urls.isEmpty()) return out;
        try {
            String body = requestBody(urls, query);
            long t0 = System.currentTimeMillis();
            String resp = SearchHttp.post(ENDPOINT, "Bearer " + mConfig.getTavilyKey(), body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;
            if (resp == null) {
                Log.w(TAG, "请求失败或超时 (" + ms + "ms)");
                for (String u : urls) out.add(new Page(u, null, "请求失败"));
                return out;
            }
            out = parse(resp);
            Log.i(TAG, "抽取 " + urls.size() + " 个链接 → " + out + " (" + ms + "ms)");
        } catch (Throwable t) {
            Log.w(TAG, "异常: " + t.getMessage());
        }
        return out;
    }

    /** 请求体（纯函数，JVM 可测）。只收 http/https，去重，最多 {@link #MAX_URLS} 个。 */
    public static String requestBody(List<String> urls, String query) throws Exception {
        JSONObject b = new JSONObject();
        JSONArray arr = new JSONArray();
        List<String> seen = new ArrayList<String>();
        for (String u : urls) {
            if (u == null) continue;
            String s = u.trim();
            String low = s.toLowerCase();
            if (!(low.startsWith("http://") || low.startsWith("https://")) || seen.contains(s)) continue;
            seen.add(s);
            arr.put(s);
            if (arr.length() >= MAX_URLS) break;
        }
        b.put("urls", arr);
        if (query != null && query.trim().length() > 0) {
            b.put("query", query.trim());
            b.put("chunks_per_source", 3);
        }
        b.put("extract_depth", "basic");
        b.put("format", "text");
        return b.toString();
    }

    /** 解析响应（纯函数）：成功的在前，失败的在后。 */
    public static List<Page> parse(String json) {
        List<Page> out = new ArrayList<Page>();
        if (json == null) return out;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray ok = o.optJSONArray("results");
            if (ok != null) {
                for (int i = 0; i < ok.length(); i++) {
                    JSONObject r = ok.optJSONObject(i);
                    if (r == null) continue;
                    String c = r.optString("raw_content", "");
                    out.add(new Page(r.optString("url"), c, c.trim().length() == 0 ? "正文为空" : null));
                }
            }
            JSONArray bad = o.optJSONArray("failed_results");
            if (bad != null) {
                for (int i = 0; i < bad.length(); i++) {
                    JSONObject r = bad.optJSONObject(i);
                    if (r == null) continue;
                    String e = r.optString("error", "");
                    out.add(new Page(r.optString("url"), null, e.length() == 0 ? "抽取失败" : e));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
