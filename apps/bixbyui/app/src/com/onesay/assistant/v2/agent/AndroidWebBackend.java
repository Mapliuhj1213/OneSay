package com.onesay.assistant.v2.agent;

import android.content.Context;

import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;
import com.onesay.assistant.v2.search.WebSearchClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ arch/agent-loop（B 路）：{@link WebTools.Backend} 的安卓实现 —— <b>复用</b>现有搜索栈，不新增 Key 槽位：
 * <ul>
 *   <li>搜索：{@link WebSearchClient#search}（配置 {@code bixby_search_cfg}，Tavily → 博查 → 免 Key 必应网页，Key 走 SecretPrefs）；</li>
 *   <li>正文：{@link TavilyExtractProvider}（与 Tavily 搜索共用同一把 Key {@code SearchConfig#getTavilyKey}）；没 Key → readReady=false，
 *       观察值降级为浏览器深链（{@link WebTools#readObservation}）。</li>
 * </ul>
 */
public final class AndroidWebBackend implements WebTools.Backend {

    private final WebSearchClient mClient;
    private final TavilyExtractProvider mExtract;
    private final Context mCtx;

    public AndroidWebBackend(Context ctx) {
        mCtx = ctx.getApplicationContext();
        mClient = WebSearchClient.getInstance(ctx);
        mExtract = new TavilyExtractProvider(mClient.config());
    }

    @Override public String searchProvider() {
        try {
            return mClient.activeProviderName();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override public List<SearchResult> search(String query, int max) {
        List<SearchResult> cmp = compareFirst(query);
        try {
            List<SearchResult> r = mClient.search(query);
            List<SearchResult> all = new ArrayList<SearchResult>(cmp);
            if (r != null) all.addAll(r);
            if (all.size() <= max + cmp.size()) return all;
            return new ArrayList<SearchResult>(all.subList(0, max + cmp.size()));
        } catch (Throwable t) {
            return cmp;
        }
    }

    /**
     * ★ fix/shop-research（用户 11-03 15:53「用比价网取价」）：购物 / 比价类搜索词先查比价网（粉象比价索引，静态、免登录），
     * 命中的商品页排在搜索结果最前面（provider=compare），提示模型先 web.read 它拿各平台券后价与历史最低，再读商品页核价，网页搜索兜底。
     */
    static boolean shopQuery(String q) {
        if (q == null) return false;
        if (com.onesay.assistant.v2.router.ResearchGate.kindOf(q) == com.onesay.assistant.v2.router.ResearchGate.Kind.SHOP) return true;
        return q.matches("(?s).*(价格|比价|多少钱|史低|历史最低|券后|京东|天猫|淘宝|拼多多).*");
    }

    private List<SearchResult> compareFirst(String query) {
        List<SearchResult> out = new ArrayList<SearchResult>();
        try {
            if (!shopQuery(query)) return out;
            String name = com.onesay.assistant.v2.errand.compare.CompareSites.productPart(query);
            com.onesay.assistant.v2.errand.ProductQuery pq = com.onesay.assistant.v2.errand.ProductQuery.parse(name);
            if (pq.keyTokens().size() < 2) return out;
            for (com.onesay.assistant.v2.errand.compare.FenxiangParser.Hit h : com.onesay.assistant.v2.errand.compare.CompareSites.fenxiangHits(name, pq,
                    com.onesay.assistant.v2.errand.ErrandHooks.compareHttpPublic(), System.currentTimeMillis(), 3)) {
                out.add(new SearchResult("粉象比价：" + h.title + " 当前最低 " + h.price, h.url,
                        "比价网（京东 / 天猫各店券后价、官方 / 自营、历史最低与日期、价格走势）。先 web.read 这条拿各平台价，再读商品页核价。", "compare"));
            }
            android.util.Log.i("AgentWeb", "[AGENT-WEB] 比价网优先：粉象命中 " + out.size());
        } catch (Throwable t) {
            android.util.Log.w("AgentWeb", "[AGENT-WEB] 比价网优先异常 " + t);
        }
        return out;
    }

    /** 比价网页面（粉象商品页 / 慢慢买爆料）→ 结构化正文；不是比价网页面 = null。 */
    static WebTools.Page compareRead(String url, com.onesay.assistant.v2.errand.compare.CompareSites.Http http, long now) {
        String[] r = com.onesay.assistant.v2.errand.compare.CompareSites.readPage(url, http, now);
        return r == null ? null : new WebTools.Page(r[0], r[1], null);
    }

    /** ★ fix/shop-research（11-03 14:37）：没配 Tavily 也能读 —— 直连读原网页，JS 渲染页再走离屏浏览器（BrowserReader）。 */
    @Override public boolean readReady() {
        return true;
    }

    /**
     * 读正文：Tavily（有 Key）→ 否则直连 GET（{@link com.onesay.assistant.v2.errand.SimplePageReader}）；
     * 静态结果没读到 / 太短 / JS 渲染站没有价格（{@link com.onesay.assistant.v2.errand.web.ReaderPolicy#needsRender}）→ 离屏浏览器渲染读取（电脑模式），
     * 站内搜索页（search.jd.com 等）的结果标题按 URL 里的搜索词过 ProductQuery 相关性。要登录 / 验证 → 短说明（verdict needs_sign_in / refused），不登录。
     */
    @Override public WebTools.Page read(String url, String query) {
        try {
            WebTools.Page cp = compareRead(url, com.onesay.assistant.v2.errand.ErrandHooks.compareHttpPublic(), System.currentTimeMillis());
            if (cp != null) {
                android.util.Log.i("AgentWeb", "[AGENT-WEB] web.read 比价网结构化 " + com.onesay.assistant.v2.router.ResearchGate.host(url) + " len=" + cp.text.length());
                return cp;
            }
        } catch (Throwable t) {
            android.util.Log.w("AgentWeb", "[AGENT-WEB] 比价网读取异常 " + t);
        }
        WebTools.Page st = null;
        try {
            if (mExtract.isReady()) {
                List<String> one = new ArrayList<String>();
                one.add(url);
                List<TavilyExtractProvider.Page> pages = mExtract.extract(one, null);
                for (TavilyExtractProvider.Page p : pages) {
                    if (p != null) { st = new WebTools.Page(p.url.length() > 0 ? p.url : url, p.content, p.ok() ? null : p.error); break; }
                }
            } else if (!com.onesay.assistant.v2.errand.web.ReaderPolicy.jsHeavy(url)) {
                TavilyExtractProvider.Page p = com.onesay.assistant.v2.errand.SimplePageReader.fetch(url);
                if (p != null) st = new WebTools.Page(url, p.content, p.ok() ? null : p.error);
            }
        } catch (Throwable t) {
            st = null;
        }
        String stText = st != null && st.ok() ? st.text : null;
        if (!com.onesay.assistant.v2.errand.web.ReaderPolicy.needsRender(url, stText)) return st;
        try {
            com.onesay.assistant.v2.errand.web.BrowserReader br = com.onesay.assistant.v2.errand.web.BrowserReader.get(mCtx);
            if (!br.available()) return st != null ? st : new WebTools.Page(url, null, "读不到");
            com.onesay.assistant.v2.errand.web.ReaderResult r;
            try {
                r = br.read(url, false, com.onesay.assistant.v2.errand.web.ReaderPolicy.AGENT_PAGE_TIMEOUT_MS);   // ★ 11-04 午：调研读页 6 s 上限
            } finally {
                br.clear();
            }
            android.util.Log.i("AgentWeb", "[AGENT-WEB] web.read 浏览器取价 " + r);
            WebTools.Page rp = com.onesay.assistant.v2.errand.web.ReaderParser.toPage(r, stText);
            if (rp != null) return rp;
        } catch (Throwable t) {
            android.util.Log.w("AgentWeb", "[AGENT-WEB] 浏览器取价异常 " + t);
        }
        return st != null ? st : new WebTools.Page(url, null, "读不到");
    }
}
