package com.onesay.assistant.v2.search;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 博查（BochaAI）搜索通道 —— **境外通道不可用时的国内备选**。
 *
 * <pre>
 *   POST https://api.bochaai.com/v1/web-search
 *   Headers: Authorization: Bearer sk-xxxxxxxx
 *   Body   : {"query":"比特币价格","count":5,"summary":true}
 *   响应    : {"data":{"webPages":{"value":[
 *                {"name":"..","url":"..","snippet":"..","summary":".."}]}}}
 * </pre>
 *
 * <p>选它做备选的理由：服务器在国内，不依赖出海链路，中文搜索质量好，
 * 同样按次计费（约 ¥0.03/次）、新用户有免费额度。
 *
 * <p>它默认**不参与**轮询 —— 只有配了 Key 才会被 {@link WebSearchClient} 选中，
 * 不配就静默跳过，不会因为"多一个没配的服务"而拖慢任何请求。
 */
public final class BochaSearchProvider implements SearchProvider {

    private static final String TAG = "Search/Bocha";
    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;

    private final SearchConfig mConfig;

    public BochaSearchProvider(SearchConfig config) {
        mConfig = config;
    }

    @Override
    public String name() {
        return "bocha";
    }

    @Override
    public boolean isReady() {
        return mConfig.getBochaKey().length() > 0;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        if (!isReady() || query == null || query.trim().length() == 0) return out;

        try {
            JSONObject body = new JSONObject();
            body.put("query", query.trim());
            body.put("count", Math.max(1, Math.min(maxResults, 8)));
            body.put("summary", true);
            body.put("freshness", "noLimit");

            long t0 = System.currentTimeMillis();
            String resp = SearchHttp.post(ENDPOINT, "Bearer " + mConfig.getBochaKey(),
                    body.toString(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;

            if (resp == null) {
                Log.w(TAG, "请求失败或超时 (" + ms + "ms)");
                return out;
            }

            JSONObject json = new JSONObject(resp);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return out;
            JSONObject webPages = data.optJSONObject("webPages");
            if (webPages == null) return out;
            JSONArray arr = webPages.optJSONArray("value");
            if (arr == null) return out;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // summary 是模型友好的浓缩摘要，优先用它；没有才退到 snippet
                String text = o.optString("summary");
                if (text == null || text.trim().length() == 0) text = o.optString("snippet");
                SearchResult r = new SearchResult(o.optString("name"), o.optString("url"), text);
                if (!r.isEmpty()) out.add(r);
            }
            Log.i(TAG, "命中 " + out.size() + " 条 (" + ms + "ms)");
        } catch (Throwable t) {
            Log.w(TAG, "解析异常: " + t.getMessage());
        }
        return out;
    }
}
