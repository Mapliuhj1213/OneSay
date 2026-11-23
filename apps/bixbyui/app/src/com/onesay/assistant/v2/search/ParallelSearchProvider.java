package com.onesay.assistant.v2.search;

import android.util.Log;

import com.onesay.assistant.v2.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [fix-1123-misc] Parallel 搜索通道 —— <b>可选</b>：用户自己在设置里填了 Key 才参与，没填就跳过（走原来的搜索链）；不是默认首选。
 *
 * <pre>
 *   POST https://api.parallel.ai/v1/search
 *   Headers: x-api-key: &lt;用户自己的 Key&gt;
 *   Body   : {"objective":"..","search_queries":[".."],"mode":"fast","advanced_settings":{"max_results":5}}
 *   响应    : {"search_id":"..","results":[{"url":"..","title":"..","publish_date":null,"excerpts":[".."]}]}
 * </pre>
 * 接口形状按官方文档（docs.parallel.ai/search/search-quickstart，2026-11-23 查）：{@code search_queries} 必填，
 * {@code max_results} 要放进 {@code advanced_settings}，只给 objective 会 422。
 *
 * <p>OneSay <b>不用</b>它那个不要 Key 的匿名免费档（限流紧、只准个人用、匿名查询可能被拿去训练），设置里如实写明。
 * 实现约定同 {@link SearchProvider}：不抛异常，失败一律空列表。Key 不进日志。
 */
public final class ParallelSearchProvider implements SearchProvider, SearchOutcome {

    private static final String TAG = "Search/Parallel";
    public static final String ENDPOINT = "https://api.parallel.ai/v1/search";

    /** 设置页卡片上的用前须知（大白话）。 */
    public static final String NOTICE_ZH = "填了 Key 才会用它，不填照旧走现有的搜索，也不会变成默认。"
            + "提供方还有一个不用 Key 的免费档：限流很紧，只准个人自己用，不带 Key 的匿名查询可能被拿去训练模型；OneSay 不走那个档。"
            + "填了 Key 以后，按提供方的标准条款，查询内容也可能在去掉身份信息后被用来改进模型，介意的话别填。";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;

    /** Key 从哪来（生产 = SearchConfig；测试里直接给）。 */
    public interface KeySource {
        String key();
    }

    private final KeySource mKey;
    private final String mEndpoint;
    private final ThreadLocal<Boolean> mLastFailed = new ThreadLocal<Boolean>();

    public ParallelSearchProvider(final SearchConfig config) {
        this(new KeySource() {
            @Override public String key() { return config.getParallelKey(); }
        }, ENDPOINT);
    }

    /** 测试用：指定 Key 来源和接口地址（假服务端）。 */
    public ParallelSearchProvider(KeySource key, String endpoint) {
        mKey = key;
        mEndpoint = endpoint;
    }

    @Override
    public String name() {
        return "parallel";
    }

    @Override
    public boolean lastSearchFailed() {
        Boolean b = mLastFailed.get();
        return b != null && b;
    }

    private String key() {
        try {
            String k = mKey == null ? null : mKey.key();
            return k == null ? "" : k.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean isReady() {
        return key().length() > 0;
    }

    /** 请求体（测试里核对形状）。 */
    static String body(String query, int maxResults) throws Exception {
        String q = query.trim();
        JSONObject body = new JSONObject();
        body.put("objective", "查找和这个问题相关的最新网页资料：" + q);
        JSONArray qs = new JSONArray();
        qs.put(q.length() > 200 ? q.substring(0, Character.isHighSurrogate(q.charAt(199)) ? 199 : 200) : q);
        body.put("search_queries", qs);
        body.put("mode", "fast");
        JSONObject adv = new JSONObject();
        adv.put("max_results", Math.max(1, Math.min(maxResults, 10)));
        body.put("advanced_settings", adv);
        return body.toString();
    }

    /** 设置页「测试连通性」用的请求体。 */
    public static String probeBody() {
        try {
            return body("OneSay", 1);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 解析响应（测试里直接喂）。 */
    static List<SearchResult> parse(String resp) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        try {
            JSONArray arr = new JSONObject(resp).optJSONArray("results");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                StringBuilder sb = new StringBuilder();
                JSONArray ex = o.optJSONArray("excerpts");
                if (ex != null) for (int j = 0; j < ex.length() && sb.length() < 600; j++) {
                    String e = ex.optString(j, "").trim();
                    if (e.length() == 0) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(e);
                }
                String text = com.onesay.assistant.v2.util.TextUtil.truncate(sb.toString(), 600, "…");
                SearchResult r = new SearchResult(o.optString("title", ""), o.optString("url", ""), text, "parallel");
                if (!r.isEmpty()) out.add(r);
            }
        } catch (Throwable t) {
            out.clear();
        }
        return out;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        mLastFailed.set(Boolean.FALSE);
        List<SearchResult> out = new ArrayList<SearchResult>();
        String k = key();
        if (k.length() == 0 || query == null || query.trim().length() == 0) return out;
        try {
            long t0 = System.currentTimeMillis();
            HttpClient.Response resp = HttpClient.execute(new HttpClient.Request(mEndpoint).method("POST")
                    .timeouts(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).json(body(query, maxResults))
                    .header("x-api-key", k));
            long ms = System.currentTimeMillis() - t0;
            if (resp.code != 200 || resp.body == null) {
                mLastFailed.set(Boolean.TRUE);
                Log.w(TAG, "请求失败 HTTP " + resp.code + " (" + ms + "ms)");
                return out;
            }
            out = parse(resp.body);
            Log.i(TAG, "命中 " + out.size() + " 条 (" + ms + "ms)");
        } catch (Throwable t) {
            mLastFailed.set(Boolean.TRUE);
            Log.w(TAG, "出错: " + t.getClass().getSimpleName());
        }
        return out;
    }
}
