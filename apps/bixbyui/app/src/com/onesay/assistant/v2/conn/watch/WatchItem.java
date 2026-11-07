package com.onesay.assistant.v2.conn.watch;

import com.onesay.assistant.v2.conn.Codec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** [conn/watch] 一条搜索结果（标题 / 链接 / 摘要）+ 链接规范化。全部是不可信外部内容，只展示，不当指令。 */
public final class WatchItem {

    public final String title;
    public final String url;
    public final String snippet;

    public WatchItem(String title, String url, String snippet) {
        this.title = clip(title, 200);
        this.url = url == null ? "" : url.trim();
        this.snippet = clip(snippet, 300);
    }

    static String clip(String s, int n) {
        String v = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > n ? v.substring(0, n) + "…" : v;
    }

    /** Tavily 适配器的 data（[{title,url,snippet}]）→ 列表。 */
    public static List<WatchItem> parse(String json) {
        List<WatchItem> out = new ArrayList<WatchItem>();
        try {
            JSONArray arr = new JSONArray(json == null || json.length() == 0 ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("url", "").length() == 0) continue;
                out.add(new WatchItem(o.optString("title"), o.optString("url"), o.optString("snippet", o.optString("content", ""))));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 判重用的链接：小写主机、去 www.、去 #片段、去末尾 /、去 utm_* / spm / from 等跟踪参数（参数排序）。
     * 解析不了的原样小写返回。
     */
    public static String normUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        try {
            URI x = new URI(u);
            String host = x.getHost() == null ? "" : x.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = x.getRawPath() == null ? "" : x.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            List<String> keep = new ArrayList<String>();
            String q = x.getRawQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    String k = kv.contains("=") ? kv.substring(0, kv.indexOf('=')) : kv;
                    String kl = k.toLowerCase(Locale.ROOT);
                    if (kl.startsWith("utm_") || kl.equals("spm") || kl.equals("from") || kl.equals("share") || kl.equals("ref")
                            || kl.equals("fbclid") || kl.equals("gclid") || kl.equals("share_source") || kl.length() == 0) continue;
                    keep.add(kv);
                }
            }
            java.util.Collections.sort(keep);
            StringBuilder sb = new StringBuilder(host).append(path);
            for (int i = 0; i < keep.size(); i++) sb.append(i == 0 ? '?' : '&').append(keep.get(i));
            return sb.toString();
        } catch (Exception e) {
            return u.toLowerCase(Locale.ROOT);
        }
    }

    public String key() {
        return normUrl(url);
    }

    public String titleHash() {
        return Codec.sha256Hex(title.toLowerCase(Locale.ROOT).replaceAll("\\s+", "")).substring(0, 16);
    }

    public boolean mentionsAny(List<String> kws) {
        if (kws == null || kws.isEmpty()) return true;
        String hay = (title + " " + snippet).toLowerCase(Locale.ROOT);
        for (String k : kws) if (hay.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title).put("url", url).put("snippet", snippet);
        } catch (Exception ignored) {
        }
        return o;
    }

    static WatchItem fromJson(JSONObject o) {
        if (o == null || o.optString("url", "").length() == 0) return null;
        return new WatchItem(o.optString("title"), o.optString("url"), o.optString("snippet"));
    }
}
