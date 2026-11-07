package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.search.SearchProvider;
import com.onesay.assistant.v2.search.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn] 现有 Tavily 搜索的<b>适配层</b>：把 {@link com.onesay.assistant.v2.search.TavilySearchProvider} 包成连接器的 {@code search} 工具。
 *
 * <p><b>行为不变</b>：WebSearchClient / AskEngine 的搜索链路一行没改，仍直接用 TavilySearchProvider（含降级到 Bocha / 必应）；
 * 本适配器 {@link #exposeToAgent()} = false，不往 AgentLoop 加新工具。它只给 App 内新调用方（P3 监控任务、A 线回调）
 * 一个与 MCP / Google 一致的调用面：{@code registry.findAny("conn.tavily.search")}。Key 仍在原来的 SearchConfig（已加密）里。
 */
public final class TavilyConnector implements Connector {

    public static final String ID = "tavily";
    public static final String TOOL_SEARCH = "search";

    private final SearchProvider mProvider;

    public TavilyConnector(SearchProvider provider) {
        mProvider = provider;
    }

    @Override public String id() { return ID; }
    @Override public String title() { return "Tavily 搜索"; }
    @Override public boolean isReady() { return mProvider != null && mProvider.isReady(); }
    @Override public boolean exposeToAgent() { return false; }

    @Override
    public List<ConnTool> tools() {
        List<ConnTool> l = new ArrayList<ConnTool>();
        try {
            JSONObject schema = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("query", new JSONObject().put("type", "string").put("description", "搜索词"))
                            .put("max_results", new JSONObject().put("type", "integer").put("description", "1–8，默认 5")))
                    .put("required", new JSONArray().put("query"));
            l.add(new ConnTool(ID, TOOL_SEARCH, "联网搜索（Tavily），返回标题 / 链接 / 摘要", schema, ToolEffect.READ));
        } catch (Exception ignored) {
        }
        return l;
    }

    @Override public ConnResult refreshTools() { return ConnResult.ok("本地定义工具"); }

    @Override
    public ConnResult call(String toolName, JSONObject args) {
        if (!TOOL_SEARCH.equals(toolName)) return ConnResult.fail(ConnResult.Code.DENIED, "未知工具 " + toolName);
        if (!isReady()) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "Tavily Key 未配置");
        String q = args == null ? "" : args.optString("query", "").trim();
        if (q.length() == 0) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "缺 query");
        int n = args.optInt("max_results", 5);
        List<SearchResult> rs = mProvider.search(q, n);   // 与 WebSearchClient 同一个实现：失败收敛成空列表
        if (rs == null || rs.isEmpty()) {
            // 2026-10-24 r2/fixes：「没有结果」不是「网络不可达」
            if (mProvider instanceof com.onesay.assistant.v2.search.SearchOutcome) {
                if (!((com.onesay.assistant.v2.search.SearchOutcome) mProvider).lastSearchFailed()) {
                    return ConnResult.ok("没有搜到相关结果", "[]");
                }
                return ConnResult.fail(ConnResult.Code.UNREACHABLE, "搜索请求失败（网络 / 服务不可用）");
            }
            // 数据源分不清失败还是没结果：报远端错误，不给「网络到不了、别重试」的提示
            return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "搜索没有结果或请求失败");
        }
        JSONArray arr = new JSONArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rs.size(); i++) {
            SearchResult r = rs.get(i);
            try {
                arr.put(new JSONObject().put("title", r.title).put("url", r.url).put("snippet", r.snippet));
            } catch (Exception ignored) {
            }
            sb.append('[').append(i + 1).append("] ").append(r.title).append(" | ").append(r.url).append('\n').append(r.snippet).append('\n');
        }
        return ConnResult.ok(sb.toString().trim(), arr.toString());
    }

    @Override public String status() { return "tavily ready=" + isReady() + " agent=false"; }
}
