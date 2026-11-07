package com.onesay.assistant.v2.search;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 搜索通道（首选，面向 LLM 场景设计的搜索 API）。
 *
 * <pre>
 *   POST https://api.tavily.com/search
 *   Headers: Authorization: Bearer tvly-xxxxxxxx
 *   Body   : {"query":"比特币价格","max_results":5,"search_depth":"basic"}
 *   响应    : {"results":[{"title":"..","url":"..","content":".."}, ...]}
 * </pre>
 *
 * <h3>为什么选它作为首选</h3>
 * <ul>
 *   <li>返回的是**为模型整理过的摘要**（{@code content}），不是网页原文，
 *       不用二次抽正文，延迟与 token 都低；</li>
 *   <li>免费额度 1000 次/月，够个人助手日常使用；</li>
 *   <li>2026-10-03 实测：宿主机与设备**均可直连**（设备侧 TCP 443 探测 OPEN）。
 *       DuckDuckGo / Brave / Google 在这个网络环境下全部超时，只有 Tavily 通。</li>
 * </ul>
 *
 * <p>若将来连不通，{@link WebSearchClient} 会自动退到免 Key 的必应网页通道，不会硬挂。
 */
public final class TavilySearchProvider implements SearchProvider, SearchOutcome {

    private static final String TAG = "Search/Tavily";

    /** 本线程最近一次 search 是否失败（见 {@link SearchOutcome}）。 */
    private final ThreadLocal<Boolean> mLastFailed = new ThreadLocal<Boolean>();

    @Override
    public boolean lastSearchFailed() {
        Boolean b = mLastFailed.get();
        return b != null && b;
    }
    private static final String ENDPOINT = "https://api.tavily.com/search";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;

    private final SearchConfig mConfig;

    public TavilySearchProvider(SearchConfig config) {
        mConfig = config;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean isReady() {
        return mConfig.getTavilyKey().length() > 0;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        mLastFailed.set(Boolean.TRUE);   // 只有完整拿到并解析出 results 才置 false
        if (!isReady() || query == null || query.trim().length() == 0) return out;

        try {
            JSONObject body = new JSONObject();
            body.put("query", query.trim());
            body.put("max_results", Math.max(1, Math.min(maxResults, 8)));
            body.put("search_depth", "basic");
            // 明确不要"模型代答"和"网页原文"：前者会和本地 LLM 打架，
            // 后者会显著抬高延迟与 token，对"查个数"没有帮助。
            body.put("include_answer", false);
            body.put("include_raw_content", false);

            long t0 = System.currentTimeMillis();
            String resp = SearchHttp.post(ENDPOINT, "Bearer " + mConfig.getTavilyKey(),
                    body.toString(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;

            if (resp == null) {
                Log.w(TAG, "请求失败或超时 (" + ms + "ms) —— 交由上层降级");
                return out;
            }

            JSONObject json = new JSONObject(resp);
            JSONArray arr = json.optJSONArray("results");
            if (arr == null) {
                Log.w(TAG, "响应中没有 results 字段");
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                SearchResult r = new SearchResult(
                        o.optString("title"), o.optString("url"), o.optString("content"));
                if (!r.isEmpty()) out.add(r);
            }
            mLastFailed.set(Boolean.FALSE);
            Log.i(TAG, "命中 " + out.size() + " 条 (" + ms + "ms)");
        } catch (Throwable t) {
            Log.w(TAG, "解析异常: " + t.getMessage());
        }
        return out;
    }
}
