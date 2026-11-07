package com.onesay.assistant.v2.search;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 联网搜索统一入口 —— 给 L2 问答通道"补一双眼睛"。
 *
 * <h3>它在整个链路里的位置</h3>
 * <pre>
 *   用户问"比特币多少钱"
 *     → L2.ask()
 *        → 【本类】先搜一次，拿到几条带时间的资料
 *        → 把资料连同问题一起交给 DeepSeek
 *        → 模型基于资料作答（而不是"我无法获取实时数据"）
 * </pre>
 *
 * <h3>为什么是"先搜再问"而不是"让模型自己决定要不要搜"</h3>
 * 用户定档：**Ask 一律先搜**。理由很实在 ——
 * 让模型自己决定就得先跑一轮 function calling（多一次往返），
 * 而且模型判断"这个问题要不要联网"本身就不可靠（它不知道自己不知道什么）。
 * 直接搜的成本是几百毫秒 + 一次搜索额度，换来的是"永远不会一本正经地说不知道"。
 *
 * <h3>降级链（这是本类最重要的设计）</h3>
 * <pre>
 *   按配置顺序尝试候选数据源，全部失败 → 返回 null
 *   → 上层退回"如实说明无法获取"，**绝不编造**
 * </pre>
 * 任何单一数据源挂掉都不会让功能消失，只会让答案的时效性下降一档。
 * 免 Key 的必应网页通道永远排在队尾，保证"一个 Key 都没配"时功能依然可用。
 */
public final class WebSearchClient {

    private static final String TAG = "WebSearch";

    private static final int MAX_RESULTS = 5;
    /** 拼进 prompt 的资料块长度上限（防止把上下文撑爆） */
    private static final int MAX_PROMPT_CHARS = 1800;

    private static volatile WebSearchClient sInstance;

    private final SearchConfig mConfig;
    private final Context mApp;
    private final TavilySearchProvider mTavily;
    private final BochaSearchProvider mBocha;
    private final BingHtmlSearchProvider mBing;

    private WebSearchClient(Context context) {
        Context app = context.getApplicationContext();
        mApp = app;
        mConfig = new SearchConfig(app);
        mTavily = new TavilySearchProvider(mConfig);
        mBocha = new BochaSearchProvider(mConfig);
        mBing = new BingHtmlSearchProvider();
    }

    public static WebSearchClient getInstance(Context context) {
        if (sInstance == null) {
            synchronized (WebSearchClient.class) {
                if (sInstance == null) {
                    sInstance = new WebSearchClient(context);
                }
            }
        }
        return sInstance;
    }

    public SearchConfig config() {
        return mConfig;
    }

    /** 当前会走的数据源名（日志/自测用，不发起网络）。 */
    public String activeProviderName() {
        List<SearchProvider> chain = candidateChain();
        return chain.isEmpty() ? "(无)" : chain.get(0).name();
    }

    /**
     * 搜索并拼成可以直接塞进 prompt 的资料块。
     *
     * @return 资料文本；搜索关闭 / 无结果 / 全部数据源失败时返回 <b>null</b>
     *         （调用方据此走"如实说明无法获取"的分支）
     */
    public String searchForPrompt(String query) {
        // R15：天气问句没带地点时补上用户设置的常用城市（没设置就原样）。
        // ★ lane C：常用城市 = 手动 home_city → 三星系统天气城市列表 → 定位反查 → 空（旧行为）。只有「天气问句且没带地点」才去解析
        //   （要走 root / 网络，别的问句不碰）。
        String q = query;
        if (WeatherQuery.isWeatherQuery(query) && !WeatherQuery.hasPlace(query, mConfig.getHomeCity())) {
            q = WeatherQuery.withHomeCity(query, com.onesay.assistant.v2.city.HomeCityProvider.cityForWeather(mApp, mConfig.getHomeCity()));
        }
        if (q != null && !q.equals(query)) Log.i(TAG, "天气问句补城市: 已补");
        List<SearchResult> list = search(q);
        if (list.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("以下是通过联网检索获得的最新资料（检索时间：")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date()))
          .append("）：\n");

        int n = 0;
        for (SearchResult r : list) {
            if (sb.length() > MAX_PROMPT_CHARS) break;
            n++;
            sb.append("[").append(n).append("] ").append(r.title).append("\n");
            if (r.snippet.length() > 0) sb.append(r.snippet).append("\n");
            if (r.url.length() > 0) sb.append("来源：").append(r.url).append("\n");
        }
        if (n == 0) return null;
        Log.i(TAG, "资料块已就绪: " + n + " 条 / " + sb.length() + " 字");
        return sb.toString();
    }

    /** 原始搜索（自测/诊断用）。永不返回 null。 */
    public List<SearchResult> search(String query) {
        List<SearchResult> empty = new ArrayList<SearchResult>();
        if (!mConfig.isEnabled()) {
            Log.i(TAG, "联网搜索已关闭，跳过");
            return empty;
        }
        if (query == null || query.trim().length() == 0) return empty;

        List<SearchProvider> chain = candidateChain();
        if (chain.isEmpty()) {
            Log.w(TAG, "没有任何可用数据源");
            return empty;
        }

        for (SearchProvider p : chain) {
            List<SearchResult> r = p.search(query, MAX_RESULTS);
            if (r != null && !r.isEmpty()) {
                Log.i(TAG, "数据源命中: " + p.name() + " (" + r.size() + " 条)");
                return r;
            }
            Log.i(TAG, "数据源 " + p.name() + " 无结果，尝试下一个");
        }
        Log.w(TAG, "全部数据源均无结果: " + com.onesay.assistant.v2.util.LogText.u(query));
        return empty;
    }

    /**
     * 候选数据源链。
     *
     * <p>规则：
     * <ol>
     *   <li>配置里点名的数据源排第一，另一个付费源排第二；</li>
     *   <li>未点名的（{@code auto}）按 Tavily → 博查 排；</li>
     *   <li>免 Key 的必应网页**永远排最后**当兜底 —— 除非用户明确指定只用它。</li>
     * </ol>
     * 只有 {@link SearchProvider#isReady()} 为真的才会进链，所以
     * "配了一个没配另一个"不会有任何额外开销。
     */
    private List<SearchProvider> candidateChain() {
        List<SearchProvider> chain = new ArrayList<SearchProvider>();
        String pref = mConfig.getProvider();

        if (SearchConfig.PROVIDER_BING.equals(pref)) {
            chain.add(mBing);
            return chain;
        }

        if (SearchConfig.PROVIDER_BOCHA.equals(pref)) {
            addIfReady(chain, mBocha);
            addIfReady(chain, mTavily);
        } else if (SearchConfig.PROVIDER_TAVILY.equals(pref)) {
            addIfReady(chain, mTavily);
            addIfReady(chain, mBocha);
        } else { // auto
            addIfReady(chain, mTavily);
            addIfReady(chain, mBocha);
        }

        chain.add(mBing); // 免 Key 兜底，永远垫底
        return chain;
    }

    private static void addIfReady(List<SearchProvider> chain, SearchProvider p) {
        if (p.isReady()) chain.add(p);
    }

    /** 一行状态摘要（自测输出用）。 */
    public String describe() {
        return mConfig.describe() + ", 首选=" + activeProviderName();
    }
}
