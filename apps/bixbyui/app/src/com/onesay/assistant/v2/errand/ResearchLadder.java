package com.onesay.assistant.v2.errand;

import com.onesay.assistant.vendor.samsung.SamsungBrowser;

import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ★ lane C3：调研类跑腿的「升级链」（纯 Java，各档通过接口注入，JVM 可测）。
 *
 * <h3>顺序（用户 10-22 23:35 定）</h3>
 * <ol>
 *   <li>{@link Tier#SEARCH} Tavily search：摘要够了就直接回调，不开浏览器；</li>
 *   <li>{@link Tier#EXTRACT} Tavily extract：把前几条结果（或用户给的链接）的正文抽出来，够了就回调；</li>
 *   <li>{@link Tier#WEBVIEW} 内置 WebView 浏览代理（默认 PC UA）：点击 / 输入 / 翻页，遇登录验证码请用户接手；</li>
 *   <li>{@link Tier#SAMSUNG} 三星浏览器桌面模式兜底：<b>只</b>用 Intent 打开链接交给用户，不做 GUI 点击。</li>
 * </ol>
 * 购物（{@link ErrandIntent.Kind#SHOP}）要加购物车，搜索 API 做不到 → 直接从 WebView 开始。
 * 三星浏览器只在 WebView <b>打不开页面</b>（{@link BrowseResult.Status#BLOCKED}）时用；登录 / 验证码是 TAKEOVER，
 * 由 App 内的接手页处理（Cookie 与代理共享，用户登完代理能接着跑）。
 */
public final class ResearchLadder {

    /** RENDER = ★ 11-03 14:37 离屏 WebView 只读取价（{@link com.onesay.assistant.v2.errand.web.BrowserReader}，渲染后的价格节点）。 */
    /** COMPARE = ★ 11-03 15:53 比价网静态取价（{@link com.onesay.assistant.v2.errand.compare.CompareSites}：粉象比价 + 慢慢买爆料）。 */
    public enum Tier { SEARCH, EXTRACT, WEBVIEW, SAMSUNG, RENDER, COMPARE }

    public interface Searcher {
        List<SearchResult> search(String query);
    }

    public interface Extractor {
        boolean ready();

        List<TavilyExtractProvider.Page> extract(List<String> urls, String query);
    }

    public interface Browser {
        boolean available();

        BrowseResult browse(ErrandIntent intent, String startUrl, Progress progress);
    }

    /**
     * ★ fix/shop-research（用户 11-03 14:37）：渲染读取（离屏 WebView，电脑模式）—— 京东 / 天猫 / 淘宝站内搜索、JS 渲染的商品页、比价站。
     * 只读：不点击、不登录、不加购；要登录 / 验证码由实现回 LOGIN / CAPTCHA，这里跳过并注明。
     */
    public interface Rendered {
        boolean available();

        com.onesay.assistant.v2.errand.web.ReaderResult read(String url);

        /** 一轮结束：清本轮 Cookie / 缓存。 */
        void clear();
    }

    /** 三星浏览器兜底（只开链接）。返回是否拉起成功。 */
    public interface External {
        boolean available();

        boolean open(String url);

        /** ★ LC-4：上一次 {@link #open} 发出的 Intent 是否真的带了桌面模式参数（决定文案说不说「桌面版」）。 */
        boolean openedDesktop();
    }

    /** 把资料整理成回答（LLM）；拿不到返回 null，由本类用资料原文兜底。 */
    public interface Summarizer {
        String answer(ErrandIntent intent, String material);
    }

    public interface Progress {
        void step(String text);

        boolean cancelled();
    }

    /** 一条来源。 */
    public static final class Source {
        public final String title;
        public final String url;
        public final String text;
        public final Tier tier;

        public Source(String title, String url, String text, Tier tier) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.text = text == null ? "" : text;
            this.tier = tier;
        }
    }

    /** 升级链的最终结果。 */
    public static final class Outcome {
        public final boolean ok;
        /** 最后用到的一档。 */
        public final Tier tier;
        public final List<Tier> tried;
        public final List<Source> sources;
        public final String answer;
        /** 需要用户接手（登录 / 结算 / 交给三星浏览器）时的链接。 */
        public final String handoffUrl;
        public final BrowseResult.Status browseStatus;
        public final String note;

        Outcome(boolean ok, Tier tier, List<Tier> tried, List<Source> sources, String answer, String handoffUrl,
                BrowseResult.Status browseStatus, String note) {
            this.ok = ok;
            this.tier = tier;
            this.tried = tried;
            this.sources = sources;
            this.answer = answer == null ? "" : answer;
            this.handoffUrl = handoffUrl;
            this.browseStatus = browseStatus;
            this.note = note == null ? "" : note;
        }

        @Override
        public String toString() {
            return (ok ? "OK" : "NO") + "@" + tier + " tried=" + tried + " src=" + sources.size()
                    + (browseStatus != null ? " browse=" + browseStatus : "") + (handoffUrl != null ? " handoff=" + handoffUrl : "")
                    + (note.length() > 0 ? " note=" + note : "");
        }
    }

    // ------------------------------------------------------------------ 门槛（纯函数）

    /** 普通调研：至少 2 条有内容的摘要、合计 ≥ 300 字。 */
    public static final int SEARCH_MIN_RESULTS = 2;
    public static final int SEARCH_MIN_CHARS = 300;
    /** 「对比 / 调研」类：至少 3 条、合计 ≥ 900 字。 */
    public static final int SEARCH_DEEP_MIN_RESULTS = 3;
    public static final int SEARCH_DEEP_MIN_CHARS = 900;
    /** 一条摘要至少 40 字才算「有内容」。 */
    public static final int SNIPPET_MIN = 40;
    /** extract：单页 ≥ 200 字才算可用；没给链接时合计 ≥ 800 字。 */
    public static final int PAGE_MIN = 200;
    public static final int EXTRACT_MIN_TOTAL = 800;
    /** extract 取前几条搜索结果。 */
    public static final int EXTRACT_TOP = 3;
    /** 拼给模型的资料上限。 */
    public static final int MATERIAL_MAX = 6000;

    /** 登录墙 / 验证码 / 反爬页的特征（抽出来的「正文」其实是拦截页）。 */
    private static final Pattern WALL = Pattern.compile(
            "请先?登录|登录后(?:查看|继续|可见|阅读)|扫码登录|验证码|安全验证|人机验证|滑动验证|拖动滑块|访问异常|访问过于频繁|异常流量|"
                    + "Access Denied|Just a moment|Checking your browser|enable JavaScript|Please enable cookies|verify you are human|captcha|"
                    + "Sign in to continue|Log in to continue|403 Forbidden", Pattern.CASE_INSENSITIVE);

    public static boolean looksBlocked(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.length() == 0) return true;
        // 拦截页通常很短；长文里偶尔出现「登录」不算
        return t.length() < 1500 && WALL.matcher(t).find();
    }

    public static boolean searchEnough(ErrandIntent intent, List<SearchResult> results) {
        if (intent == null || results == null) return false;
        if (intent.kind == ErrandIntent.Kind.SHOP || intent.url != null) return false;   // 购物要进站；给了链接要看那一页
        int n = 0, chars = 0;
        for (SearchResult r : results) {
            if (r == null || r.snippet.length() < SNIPPET_MIN) continue;
            if (r.snippet.length() < 120 && looksBlocked(r.snippet)) continue;   // 整条摘要就是「请登录」提示
            n++;
            chars += r.snippet.length();
        }
        if (intent.deep) return n >= SEARCH_DEEP_MIN_RESULTS && chars >= SEARCH_DEEP_MIN_CHARS;
        return n >= SEARCH_MIN_RESULTS && chars >= SEARCH_MIN_CHARS;
    }

    public static boolean usablePage(TavilyExtractProvider.Page p) {
        return p != null && p.ok() && p.content.length() >= PAGE_MIN && !looksBlocked(p.content);
    }

    public static boolean extractEnough(ErrandIntent intent, List<TavilyExtractProvider.Page> pages) {
        if (intent == null || pages == null) return false;
        if (intent.kind == ErrandIntent.Kind.SHOP) return false;
        if (intent.url != null) {
            for (TavilyExtractProvider.Page p : pages) {
                if (sameUrl(p.url, intent.url) && usablePage(p)) return true;
            }
            return false;
        }
        int total = 0;
        for (TavilyExtractProvider.Page p : pages) if (usablePage(p)) total += p.content.length();
        return total >= EXTRACT_MIN_TOTAL;
    }

    static boolean sameUrl(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String u) {
        if (u == null) return "";
        String s = u.trim().toLowerCase().replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 资料块（编号与 {@link Outcome#sources} 一致，给模型引用「[2]」）。 */
    public static String material(List<Source> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            if (sb.length() >= MATERIAL_MAX) break;
            sb.append('[').append(i + 1).append("] ").append(s.title.length() > 0 ? s.title : s.url).append('\n');
            String t = s.text;
            int room = MATERIAL_MAX - sb.length();
            if (room <= 0) break;
            if (t.length() > room) t = t.substring(0, room) + "…";
            sb.append(t).append('\n');
            if (s.url.length() > 0) sb.append("来源：").append(s.url).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 运行

    private final Searcher mSearch;
    private final Extractor mExtract;
    private final Browser mBrowser;
    private final External mExternal;
    private final Summarizer mSummarizer;

    private Rendered mRender;
    private com.onesay.assistant.v2.errand.compare.CompareSites.Http mCompareHttp;
    /** 测试注入时钟（爆料距今天数）；0 = 系统时间。 */
    long mNow;

    public ResearchLadder(Searcher s, Extractor e, Browser b, External x, Summarizer sum) {
        mSearch = s;
        mExtract = e;
        mBrowser = b;
        mExternal = x;
        mSummarizer = sum;
    }

    /** ★ 11-03 14:37：挂上渲染读取（null = 不用，行为与之前相同）。 */
    public ResearchLadder withRendered(Rendered r) {
        mRender = r;
        return this;
    }

    /** ★ 11-03 15:53：挂上比价网取价（null = 不用，行为与之前相同）。 */
    public ResearchLadder withCompare(com.onesay.assistant.v2.errand.compare.CompareSites.Http http) {
        mCompareHttp = http;
        return this;
    }

    private long now() {
        return mNow > 0 ? mNow : System.currentTimeMillis();
    }

    private boolean renderReady() {
        try {
            return mRender != null && mRender.available();
        } catch (Throwable t) {
            return false;
        }
    }

    public Outcome run(ErrandIntent intent, Progress p) {
        List<Tier> tried = new ArrayList<Tier>();
        List<Source> sources = new ArrayList<Source>();
        Progress pr = p != null ? p : new Progress() {
            @Override public void step(String text) {
            }

            @Override public boolean cancelled() {
                return false;
            }
        };
        boolean shop = intent.kind == ErrandIntent.Kind.SHOP;
        // ★ fix/shop-research：没点名网站的「帮我买 X / 比价 X」→ 多平台比价调研（搜索 + 读原网页核价；★ 11-03 14:37 起站内搜索 / JS 页用离屏 BrowserReader 只读取价），仍不进 WebView 代理加购物车
        if (shop && intent.compare) return runCompare(intent, pr, tried, sources);

        // ① Tavily search
        List<SearchResult> results = new ArrayList<SearchResult>();
        if (!shop && intent.url == null && mSearch != null && intent.query.length() > 0) {   // 给了链接：直接读那一页
            tried.add(Tier.SEARCH);
            pr.step("搜索：" + clip(intent.query, 16));
            List<SearchResult> r = mSearch.search(intent.query);
            if (r != null) results.addAll(r);
            if (searchEnough(intent, results)) {
                for (SearchResult s : results) sources.add(new Source(s.title, s.url, s.snippet, Tier.SEARCH));
                return finish(intent, Tier.SEARCH, tried, sources, null, null, "搜索摘要已够用");
            }
        }
        if (pr.cancelled()) return stopped(tried, sources);

        // ② Tavily extract（给了链接就抽这个链接；否则抽前几条结果）
        if (!shop && mExtract != null && mExtract.ready()) {
            List<String> urls = new ArrayList<String>();
            if (intent.url != null) urls.add(intent.url);
            for (SearchResult s : results) {
                if (urls.size() >= EXTRACT_TOP + (intent.url != null ? 1 : 0)) break;
                if (s.url.length() > 0 && !urls.contains(s.url)) urls.add(s.url);
            }
            if (!urls.isEmpty()) {
                tried.add(Tier.EXTRACT);
                pr.step("读取网页正文（" + urls.size() + " 个）");
                List<TavilyExtractProvider.Page> pages = mExtract.extract(urls, intent.query.length() > 0 ? intent.query : null);
                if (pages != null && extractEnough(intent, pages)) {
                    for (TavilyExtractProvider.Page pg : pages) {
                        if (usablePage(pg)) sources.add(new Source(titleFor(pg.url, results), pg.url, pg.content, Tier.EXTRACT));
                    }
                    return finish(intent, Tier.EXTRACT, tried, sources, null, null, "网页正文已够用");
                }
            }
        }
        if (pr.cancelled()) return stopped(tried, sources);

        // ③ 内置 WebView（PC UA）
        String start = startUrl(intent, results);
        if (mBrowser != null && mBrowser.available() && start != null) {
            tried.add(Tier.WEBVIEW);
            pr.step("打开浏览器：" + host(start));
            BrowseResult br = mBrowser.browse(intent, start, pr);
            if (br == null) br = BrowseResult.failed("浏览器没有返回");
            for (String[] s : br.sources) sources.add(new Source(s[0], s[1], "", Tier.WEBVIEW));
            switch (br.status) {
                case DONE:
                case LIMIT:
                    return new Outcome(br.answer.length() > 0, Tier.WEBVIEW, tried, sources, br.answer, null, br.status,
                            br.status == BrowseResult.Status.LIMIT ? "到步数 / 时长上限：" + br.reason : "浏览器完成");
                case TAKEOVER:
                case CHECKOUT:
                case ORDER_ALERT:
                    return new Outcome(false, Tier.WEBVIEW, tried, sources, br.answer, br.url, br.status, br.reason);
                case BLOCKED:
                    start = br.url != null ? br.url : start;
                    break;   // → ④
                case FAILED:
                default:
                    if (pr.cancelled()) return stopped(tried, sources);
                    break;   // → ④（模型不可用等：退给用户自己看）
            }
        }

        // ④ 三星浏览器桌面模式兜底：只开链接，交给用户
        if (mExternal != null && mExternal.available() && start != null) {
            tried.add(Tier.SAMSUNG);
            boolean ok = mExternal.open(start);
            if (ok) {
                // 已经拿到的部分资料仍然整理给用户
                String partial = sources.isEmpty() ? "" : material(sources);
                return new Outcome(false, Tier.SAMSUNG, tried, sources, partial, start, null,
                        SamsungBrowser.handoffNote(mExternal.openedDesktop()));
            }
        }

        // 都不行：有多少资料给多少，绝不编造
        if (!results.isEmpty() && sources.isEmpty()) {
            for (SearchResult s : results) sources.add(new Source(s.title, s.url, s.snippet, Tier.SEARCH));
        }
        if (sources.isEmpty()) {
            Tier last = tried.isEmpty() ? Tier.SEARCH : tried.get(tried.size() - 1);
            return new Outcome(false, last, tried, sources, null, null, null, "没有拿到可用的资料");
        }
        return finish(intent, tried.isEmpty() ? Tier.SEARCH : tried.get(tried.size() - 1), tried, sources, null, null, "资料不完整，按现有资料回答");
    }

    // ------------------------------------------------------------------ ★ fix/shop-research 多平台比价

    /** 比价时每个原网页最多拼多少字进资料；资料总上限。 */
    public static final int SHOP_PAGE_CHARS = 1400;
    public static final int SHOP_MATERIAL_MAX = 9000;
    /** 读原网页的个数上限、每站上限。 */
    public static final int SHOP_EXTRACT_TOP = 6;
    public static final int SHOP_EXTRACT_PER_SITE = 2;
    /** 资料里最多放几条来源。 */
    public static final int SHOP_SOURCES_MAX = 10;

    private static final Pattern[] PLATFORMS = {
            Pattern.compile("京东|jd\\.com|3\\.cn", Pattern.CASE_INSENSITIVE),
            Pattern.compile("天猫|淘宝|tmall\\.com|taobao\\.com", Pattern.CASE_INSENSITIVE),
            Pattern.compile("拼多多|yangkeduo|pinduoduo|pdd", Pattern.CASE_INSENSITIVE),
            Pattern.compile("苏宁|suning", Pattern.CASE_INSENSITIVE),
            Pattern.compile("官网|razer\\.com|razerzone", Pattern.CASE_INSENSITIVE),
    };
    private static final Pattern PRICE = Pattern.compile("(?:[¥￥]\\s*\\d{2,6}(?:\\.\\d{1,2})?)|(?:\\d{2,6}(?:\\.\\d{1,2})?\\s*元)");
    /**
     * ★ 11-03 下午：比价 / 史低 / 好价站（直连能读到带日期的价格，京东天猫商品页 JS 渲染读不到）—— 读原网页时排最前。
     */
    private static final Pattern DEAL_HOST = Pattern.compile("smzdm|manmanbuy|fenxianglife|gwdang|xianbao|huihui|bijiago|zhidemai", Pattern.CASE_INSENSITIVE);
    /** 购物平台商品 / 搜索页（排第二）。 */
    private static final Pattern PLATFORM_HOST = Pattern.compile("jd\\.com|3\\.cn|tmall\\.com|taobao\\.com|yangkeduo|pinduoduo|suning", Pattern.CASE_INSENSITIVE);
    /** 资讯 / 评测站（排第三，常写首发价 / 到手价）。 */
    private static final Pattern NEWS_HOST = Pattern.compile("zol\\.com|ithome|pconline|mydrivers|ifanr|sspai|zhihu|bilibili", Pattern.CASE_INSENSITIVE);
    /** 京东 / 天猫 / 淘宝商品页（JS 渲染，直连常读不到价）。 */
    private static final Pattern JS_PLATFORM = Pattern.compile("jd\\.com|3\\.cn|tmall\\.com|taobao\\.com", Pattern.CASE_INSENSITIVE);
    /** 拼多多网页（不登录基本读不到）。 */
    private static final Pattern PDD_HOST = Pattern.compile("yangkeduo|pinduoduo|pdd", Pattern.CASE_INSENSITIVE);

    /** 来源里出现了几个不同平台（京东 / 天猫淘宝 / 拼多多 / 苏宁 / 官网）。 */
    public static int platformsCovered(List<Source> sources) {
        int n = 0;
        for (Pattern p : PLATFORMS) {
            for (Source s : sources) {
                if (p.matcher(s.url).find() || p.matcher(s.title).find() || p.matcher(s.text).find()) { n++; break; }
            }
        }
        return n;
    }

    /** 来源里不同站点数（jd.com / smzdm.com …）。 */
    public static int sitesCovered(List<Source> sources) {
        java.util.Set<String> h = new java.util.HashSet<String>();
        for (Source s : sources) {
            String x = com.onesay.assistant.v2.router.ResearchGate.host(s.url);
            if (x.length() > 0) h.add(x);
        }
        return h.size();
    }

    /** 比价证据够不够：≥2 个不同站点、≥1 个读过原文的网页、≥2 个平台、读过的原文里有价格。 */
    public static boolean shopEnough(List<Source> sources) {
        if (sources == null || sources.isEmpty()) return false;
        boolean readPrice = false;
        for (Source s : sources) if ((s.tier == Tier.EXTRACT || s.tier == Tier.RENDER || s.tier == Tier.COMPARE) && PRICE.matcher(s.text).find()) { readPrice = true; break; }
        return readPrice && sitesCovered(sources) >= 2 && platformsCovered(sources) >= 2;
    }

    /** 比价各平台的搜索词。 */
    public static List<String> compareQueries(ErrandIntent intent) {
        List<String> q = new ArrayList<String>();
        if (intent.recommend) {   // ★ 11-04 D：推荐类 → 先搜「品类 推荐」与价位段，价格只作筛选（不塞进站内搜索词）
            String band = intent.priceMax > 0 ? " " + intent.priceMax + "元以内" : "";
            q.add(intent.query + band + " 推荐");
            q.add(intent.query + band + " 性价比 型号 测评");
            for (ErrandIntent.Site s : intent.sites) if (q.size() < 4) q.add(intent.query + " " + s.name + " 价格");
            return q;
        }
        for (ErrandIntent.Site s : intent.sites) q.add(intent.query + " " + s.name + " 价格");
        q.add(intent.query + " 历史价格 比价 券后");
        return q;
    }

    /** 链接的读取优先级：0 比价站、1 平台、2 资讯评测、3 其他。 */
    static int urlRank(String url) {
        if (url == null) return 3;
        if (DEAL_HOST.matcher(url).find()) return 0;
        if (PLATFORM_HOST.matcher(url).find()) return 1;
        if (NEWS_HOST.matcher(url).find()) return 2;
        return 3;
    }

    /**
     * 挑要读原文的链接：比价站 → 平台 → 资讯评测 → 其他（同档按搜索顺序），每站最多 {@link #SHOP_EXTRACT_PER_SITE} 个，
     * 共 {@link #SHOP_EXTRACT_TOP} 个。
     */
    static List<String> pickShopUrls(List<SearchResult> results) {
        return pickShopUrls(results, SHOP_EXTRACT_TOP, 3, null);
    }

    /** maxRank：只挑优先级 ≤ maxRank 的；skip：已经读过的（norm 后）。 */
    static List<String> pickShopUrls(List<SearchResult> results, int top, int maxRank, java.util.Set<String> skip) {
        List<List<String>> tiers = new ArrayList<List<String>>();
        for (int k = 0; k < 4; k++) tiers.add(new ArrayList<String>());
        java.util.Map<String, Integer> per = new java.util.HashMap<String, Integer>();
        for (SearchResult r : results) {
            if (r == null || r.url.length() == 0) continue;
            if (skip != null && skip.contains(norm(r.url))) continue;
            int rank = urlRank(r.url);
            if (rank > maxRank) continue;
            String h = com.onesay.assistant.v2.router.ResearchGate.host(r.url);
            Integer c = per.get(h);
            if (c != null && c >= SHOP_EXTRACT_PER_SITE) continue;
            per.put(h, c == null ? 1 : c + 1);
            tiers.get(rank).add(r.url);
        }
        List<String> out = new ArrayList<String>();
        for (List<String> t : tiers) for (String u : t) if (out.size() < top) out.add(u);
        return out;
    }

    // ------------------------------------------------------------------ ★ 11-03 下午：价格线索（价格 + 日期 + 券后条件）

    /** 一条读到的价格。 */
    public static final class PriceNote {
        public final String site;
        public final String price;
        /** 日期（页面里离价格最近的那个；没写为空串）。 */
        public final String date;
        /** 券后 / 补贴 / 会员等条件（没写为空串）。 */
        public final String cond;

        /** ★ 11-03 14:37 渲染取价才有：商品标题、店铺（含自营 / 旗舰）、链接；其他来源为空串。 */
        public final String title;
        public final String shop;
        public final String link;

        PriceNote(String site, String price, String date, String cond) {
            this(site, price, date, cond, "", "", "");
        }

        PriceNote(String site, String price, String date, String cond, String title, String shop, String link) {
            this.site = site;
            this.price = price;
            this.date = date == null ? "" : date;
            this.cond = cond == null ? "" : cond;
            this.title = title == null ? "" : title;
            this.shop = shop == null ? "" : shop;
            this.link = link == null ? "" : link;
        }

        /** 渲染取价的一条（站点 = 京东 / 天猫 / 什么值得买；比价站条目写了商城就用「什么值得买·京东」）。 */
        static PriceNote of(String site, com.onesay.assistant.v2.errand.web.PriceItem it) {
            String kind = it.shopKind();
            String shop = it.shop.length() > 0 ? it.shop + (kind.length() > 0 ? "（" + kind + "）" : "") : kind;
            String date = (it.pageDate.length() > 0 ? it.pageDate + "，" : "") + "抓取 " + com.onesay.assistant.v2.errand.web.ReaderParser.stamp(it.fetchedAt);
            String s = it.mall.length() > 0 && !site.equals(it.mall) && !site.equals("京东") && !site.startsWith("天猫") && !site.equals("淘宝")
                    ? site + "·" + it.mall : site;
            return new PriceNote(s, it.priceLabel(), date, it.cond, it.title, shop, it.link);
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder(site).append(' ').append(price);
            if (title.length() > 0) sb.append(" · ").append(clip(title, 40));
            if (shop.length() > 0) sb.append(" · 店铺 ").append(shop);
            sb.append(" · ").append(date.length() > 0 ? date : "日期未标").append(" · ").append(cond.length() > 0 ? cond : "券后条件未标");
            return sb.toString();
        }
    }

    private static final Pattern DATE = Pattern.compile(
            "20\\d{2}\\s*[-/.年]\\s*\\d{1,2}\\s*[-/.月]\\s*\\d{1,2}\\s*日?|\\d{1,2}月\\d{1,2}日|\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}|\\d+\\s*(?:分钟|小时|天)前|今天|昨天|前天");
    private static final Pattern COND = Pattern.compile(
            "券后|用券|领券|叠券|满\\d+减\\d+|立减\\d+元?|PLUS(?:会员)?价?|plus价|百亿补贴|国补|国家补贴|政府补贴|以旧换新|到手价?|秒杀|限时|"
                    + "会员价|学生价|定金|预售|首发价?|红包|返现", Pattern.CASE_INSENSITIVE);
    public static final int PRICE_NOTES_MAX = 8;

    /** 从读过原文的来源里抠价格线索（每个价格看前 60 / 后 30 字找日期和条件；附近没日期就用页面里第一个日期）。 */
    public static List<PriceNote> priceNotes(List<Source> sources) {
        List<PriceNote> out = new ArrayList<PriceNote>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Source s : sources) {
            if (s.tier != Tier.EXTRACT) continue;
            String t = s.text;
            java.util.regex.Matcher pd = DATE.matcher(t);
            String pageDate = pd.find() ? pd.group().replaceAll("\\s+", "") : "";
            java.util.regex.Matcher m = PRICE.matcher(t);
            while (m.find() && out.size() < PRICE_NOTES_MAX) {
                String price = m.group().replaceAll("\\s+", "");
                String site = com.onesay.assistant.v2.router.ResearchGate.host(s.url);
                if (!seen.add(site + "|" + price)) continue;
                String win = t.substring(Math.max(0, m.start() - 60), Math.min(t.length(), m.end() + 30));
                java.util.regex.Matcher dm = DATE.matcher(win);
                String date = dm.find() ? dm.group().replaceAll("\\s+", "") : pageDate;
                java.util.LinkedHashSet<String> cs = new java.util.LinkedHashSet<String>();
                java.util.regex.Matcher cm = COND.matcher(win);
                while (cm.find()) cs.add(cm.group());
                StringBuilder c = new StringBuilder();
                for (String x : cs) c.append(c.length() > 0 ? "、" : "").append(x);
                out.add(new PriceNote(site, price, date, c.toString()));
            }
        }
        return out;
    }

    private static boolean anyReadPrice(List<Source> sources) {
        for (Source s : sources) if ((s.tier == Tier.EXTRACT || s.tier == Tier.RENDER || s.tier == Tier.COMPARE) && PRICE.matcher(s.text).find()) return true;
        return false;
    }

    /** 比价站补读用的查询（京东天猫商品页读不到价时）。 */
    public static List<String> dealSiteQueries(String q) {
        List<String> l = new ArrayList<String>();
        l.add(q + " 什么值得买");
        l.add(q + " 慢慢买 历史价格");
        return l;
    }

    /** 搜索一轮：免 key 的必应结果做相关性过滤。返回 false = 用户取消。 */
    private boolean searchRound(List<String> queries, ProductQuery pq, Progress pr, List<SearchResult> results,
                                java.util.Set<String> seen, int[] stat) {
        for (String q : queries) {
            if (pr.cancelled()) return false;
            pr.step("搜索：" + clip(q, 22));
            List<SearchResult> r = mSearch.search(q);
            if (r == null) continue;
            for (SearchResult s : r) {
                if (s == null || s.url.length() == 0) continue;
                if (s.keyless()) {
                    stat[1]++;
                    if (!pq.relevant(s)) { stat[0]++; continue; }   // ★ 和商品名无关（例：只含「雷」字的迅雷 / 扫雷）→ 丢
                }
                if (seen.add(norm(s.url))) results.add(s);
            }
        }
        return true;
    }

    /** 读一批原网页：拼多多读不到价 = 要登录，跳过；京东天猫读到了但没价 = JS 渲染。返回读到的个数。 */
    private int readPages(List<String> urls, ErrandIntent intent, String q, Progress pr, List<SearchResult> results, List<Source> sources,
                          java.util.Set<String> read, java.util.Set<String> attempted, java.util.Set<String> jsNoPrice, boolean[] pddSkipped) {
        if (urls.isEmpty()) return 0;
        StringBuilder hs = new StringBuilder();
        for (String u : urls) hs.append(hs.length() > 0 ? "、" : "").append(host(u));
        pr.step("读原网页核价（" + urls.size() + " 个）：" + hs);
        for (String u : urls) attempted.add(norm(u));
        List<TavilyExtractProvider.Page> pages = mExtract.extract(urls, q + " 价格");
        int n = 0;
        if (pages == null) return 0;
        for (TavilyExtractProvider.Page pg : pages) {
            String u = pg == null ? "" : pg.url;
            boolean pdd = PDD_HOST.matcher(u).find();
            boolean usable = usablePage(pg);
            if (pdd && (!usable || !PRICE.matcher(pg.content).find())) {
                pddSkipped[0] = true;
                pr.step("拼多多网页要登录，跳过：" + host(u));
                continue;
            }
            if (!usable && com.onesay.assistant.v2.errand.web.ReaderPolicy.jsHeavy(u)) {
                Source rs = renderFallback(u, q, pr, results);
                if (rs != null) {
                    sources.add(rs);
                    read.add(norm(u));
                    n++;
                    continue;
                }
            }
            if (!usable) {
                pr.step("没读到：" + host(u) + (pg != null && pg.error != null ? "（" + pg.error + "）" : "（内容太短 / 拦截页）"));
                continue;
            }
            if (JS_PLATFORM.matcher(u).find() && !PRICE.matcher(pg.content).find()) {
                Source rs = renderFallback(u, q, pr, results);
                if (rs != null) {
                    sources.add(rs);
                    read.add(norm(u));
                    n++;
                    continue;
                }
                jsNoPrice.add(com.onesay.assistant.v2.router.ResearchGate.host(u));
                pr.step("商品页是脚本渲染，读不到价：" + host(u));
            }
            sources.add(new Source(titleFor(u, results), u, pg.content, Tier.EXTRACT));
            read.add(norm(u));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ ★ 11-03 14:37：走浏览器取价（离屏 WebView，电脑模式）

    /** 每个站内搜索页最多取几个相关结果。 */
    public static final int RENDER_TOP_N = 5;
    /** 静态读不到价时，最多再用渲染读几个原网页（商品页 / 比价站）。 */
    public static final int RENDER_FALLBACK_MAX = 2;
    /** 渲染来源放进资料的正文字数。 */
    public static final int RENDER_TEXT_CHARS = 600;

    /**
     * 站内搜索（电脑版）：按 intent.sites 依次 京东 → 天猫 → 淘宝 → 拼多多，最后什么值得买（比价站兜底）。
     * 拼多多没有电脑版搜索 → 跳过并注明；天猫跳到淘宝且要登录 → 淘宝不再重复读；要登录 / 验证码 → 跳过并注明，不登录。
     * 标题过 {@link ProductQuery} 相关性（去配件），每站取前 {@link #RENDER_TOP_N} 个。
     */
    void renderRound(ErrandIntent intent, ProductQuery pq, Progress pr, List<Tier> tried, List<Source> sources,
                     List<PriceNote> renderNotes, List<String> notes) {
        List<String> sites = new ArrayList<String>();
        for (ErrandIntent.Site st : intent.sites) sites.add(st.name);
        sites.add("什么值得买");
        renderRound(sites, intent, pq, pr, tried, sources, renderNotes, notes);
    }

    void renderRound(List<String> sites, ErrandIntent intent, ProductQuery pq, Progress pr, List<Tier> tried, List<Source> sources,
                     List<PriceNote> renderNotes, List<String> notes) {
        java.util.Set<String> walled = new java.util.HashSet<String>();   // 已判定要登录 / 验证的注册域
        boolean any = false;
        for (String site : sites) {
            if (pr.cancelled()) return;
            String url = com.onesay.assistant.v2.errand.web.ShopSearchUrls.search(site, intent.query);
            if (url == null) {
                if (site.contains("拼多多")) addNote(notes, "拼多多没有电脑版网页搜索（手机网页要登录），已跳过");
                continue;
            }
            String reg = regOf(url);
            if (walled.contains(reg)) {
                addNote(notes, site + "电脑版网页要登录才出结果，已跳过（没有尝试登录）");
                continue;
            }
            if (!any) {
                tried.add(Tier.RENDER);
                any = true;
            }
            pr.step("浏览器读（电脑版）：" + site + "搜索「" + clip(intent.query, 16) + "」");
            com.onesay.assistant.v2.errand.web.ReaderResult r;
            try {
                r = mRender.read(url);
            } catch (Throwable t) {
                r = null;
            }
            if (r == null) continue;
            if (r.skipped()) {
                walled.add(reg);
                walled.add(regOf(r.finalUrl));
                addNote(notes, com.onesay.assistant.v2.errand.web.ReaderPolicy.skipNote(site, r.status == com.onesay.assistant.v2.errand.web.ReaderResult.Status.LOGIN
                        ? com.onesay.assistant.v2.errand.web.ReaderPolicy.Verdict.LOGIN : com.onesay.assistant.v2.errand.web.ReaderPolicy.Verdict.CAPTCHA));
                pr.step(site + "要登录 / 验证，跳过");
                continue;
            }
            if (!r.ok()) {
                addNote(notes, site + "搜索页没读出来（" + (r.status == com.onesay.assistant.v2.errand.web.ReaderResult.Status.TIMEOUT ? "12 秒超时" : r.note) + "）");
                continue;
            }
            List<com.onesay.assistant.v2.errand.web.PriceItem> keep = com.onesay.assistant.v2.errand.web.ReaderParser.relevant(r.items, pq, RENDER_TOP_N);
            // ★ 11-03 23:4x：什么值得买卡片另留前 RENDER_DEALS_MAX 条相符的，比价模式下按版本分组（原来只取前 5 条，排在后面的有线版好价就丢了）
            if (site.contains("值得买")) mSmzdmItems = com.onesay.assistant.v2.errand.web.ReaderParser.relevant(r.items, pq,
                    com.onesay.assistant.v2.errand.compare.CompareSites.RENDER_DEALS_MAX);
            pr.step(site + "：读到 " + r.items.size() + " 个价格，和商品名相符 " + keep.size() + " 个（" + (r.elapsedMs / 100) / 10.0 + " s）");
            if (keep.isEmpty()) {
                if (!r.items.isEmpty()) addNote(notes, site + "搜索结果的标题都和商品名不符，已丢弃 " + r.items.size() + " 条");
                continue;
            }
            String label = com.onesay.assistant.v2.errand.web.ShopSearchUrls.siteName(r.finalUrl);
            if (site.contains("天猫") && !label.equals("天猫")) label = "天猫";
            for (com.onesay.assistant.v2.errand.web.PriceItem it : keep) renderNotes.add(PriceNote.of(label, it));
            com.onesay.assistant.v2.errand.web.ReaderResult kept = r.withItems(keep);
            sources.add(new Source(site + "搜索：" + intent.query + "（浏览器渲染）", r.finalUrl, kept.asText(RENDER_TEXT_CHARS), Tier.RENDER));
        }
    }

    private static void addNote(List<String> notes, String n) {
        if (n != null && n.length() > 0 && !notes.contains(n)) notes.add(n);
    }

    static String regOf(String url) {
        String h = host(url);
        String[] p = h.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : h;
    }

    private int mRenderFallbacks;

    /** 静态读取没价 / 没读到的 JS 渲染页 → 渲染读一次（每轮最多 {@link #RENDER_FALLBACK_MAX} 个）；读不到价 / 要登录 = null。 */
    private Source renderFallback(String url, String q, Progress pr, List<SearchResult> results) {
        if (!renderReady() || mRenderFallbacks >= RENDER_FALLBACK_MAX || PDD_HOST.matcher(url).find()) return null;
        mRenderFallbacks++;
        pr.step("静态读不到价，改用浏览器读：" + host(url));
        com.onesay.assistant.v2.errand.web.ReaderResult r;
        try {
            r = mRender.read(url);
        } catch (Throwable t) {
            return null;
        }
        if (r == null || !r.ok() || r.items.isEmpty()) return null;
        List<com.onesay.assistant.v2.errand.web.PriceItem> keep = com.onesay.assistant.v2.errand.web.ReaderParser.relevant(r.items, ProductQuery.parse(q), RENDER_TOP_N);
        if (keep.isEmpty()) return null;
        return new Source(titleFor(url, results), r.finalUrl, r.withItems(keep).asText(SHOP_PAGE_CHARS), Tier.RENDER);
    }

    /** 本轮什么值得买搜索页里和商品名相符的卡片（≤ CompareSites.RENDER_DEALS_MAX）；没渲染为 null。 */
    private List<com.onesay.assistant.v2.errand.web.PriceItem> mSmzdmItems;

    Outcome runCompare(ErrandIntent intent, Progress pr, List<Tier> tried, List<Source> sources) {
        mRenderFallbacks = 0;
        mSmzdmItems = null;
        try {
            return runCompare0(intent, pr, tried, sources);
        } finally {
            if (mRender != null) {
                try {
                    mRender.clear();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    Outcome runCompare0(ErrandIntent intent, Progress pr, List<Tier> tried, List<Source> sources) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        ProductQuery pq = ProductQuery.parse(intent.query);
        int[] stat = new int[2];   // [0] 相关性过滤丢掉的、[1] 免 key 结果总数
        List<String> notes = new ArrayList<String>();
        String q = intent.query;
        String english = null;
        List<PriceNote> renderNotes = new ArrayList<PriceNote>();
        // ★ 11-03 15:53（用户「用比价网取价」）：比价网 → 比价网给的商品链接读商品页核价 → 不够才走站内搜索 / 网页搜索兜底
        com.onesay.assistant.v2.errand.compare.CompareSites.Report cmp = null;
        boolean smzdmDone = false;
        if (mCompareHttp != null && intent.query.length() > 0) {
            long t0 = System.currentTimeMillis();
            cmp = compareRound(intent, pq, pr, tried, sources, results, seen, stat, notes);
            if (cmp == null || pr.cancelled()) return stopped(tried, sources);
            if (renderReady()) {   // 什么值得买搜索页（比价站，要渲染）
                renderRound(java.util.Collections.singletonList("什么值得买"), intent, pq, pr, tried, sources, renderNotes, notes);
                smzdmDone = true;
                if (pr.cancelled()) return stopped(tried, sources);
                groupRound(intent, pq, cmp, pr, sources, notes);
                if (pr.cancelled()) return stopped(tried, sources);
                itemCheck(renderNotes, pq, pr, tried, sources, notes);
                if (pr.cancelled()) return stopped(tried, sources);
            } else {
                groupRound(intent, pq, cmp, pr, sources, notes);
            }
            cmp.elapsedMs = System.currentTimeMillis() - t0;
            if (cmp.enough() || renderNotes.size() >= 3) return compareOutcome(intent, cmp, tried, sources, renderNotes, notes);
            addNote(notes, "比价网资料不够（" + cmp.products.size() + " 个比价商品页、" + cmp.deals.size() + " 条爆料），再用站内搜索 / 网页搜索补");
        }
        if (renderReady() && intent.query.length() > 0) {
            List<String> sites = new ArrayList<String>();
            for (ErrandIntent.Site st : intent.sites) sites.add(st.name);
            if (!smzdmDone) sites.add("什么值得买");
            renderRound(sites, intent, pq, pr, tried, sources, renderNotes, notes);
        }
        if (pr.cancelled()) return stopped(tried, sources);
        if (mSearch != null && intent.query.length() > 0) {
            tried.add(Tier.SEARCH);
            if (!searchRound(compareQueries(intent), pq, pr, results, seen, stat)) return stopped(tried, sources);
            // ★ 中文搜索结果全被过滤 → 用英文名补搜一次（品牌型号映射；认不出就不做）
            if (results.isEmpty() && stat[0] > 0) {
                english = pq.englishName();
                if (english != null) {
                    pr.step("搜索结果都和商品无关，改用英文名补搜：" + english);
                    if (!searchRound(ProductQuery.fallbackQueries(english), pq, pr, results, seen, stat)) return stopped(tried, sources);
                    if (!results.isEmpty()) {
                        q = english;
                        notes.add("中文搜索结果都和这款商品无关，改用英文名「" + english + "」补搜");
                    }
                }
            }
        }
        if (pr.cancelled()) return stopped(tried, sources);
        if (stat[0] > 0) pr.step("相关性过滤：丢掉 " + stat[0] + " / " + stat[1] + " 条和商品名无关的结果（关键词 " + pq.keyTokens() + "）");
        if (results.isEmpty() && stat[0] > 0 && renderNotes.isEmpty() && (cmp == null || !cmp.any())) {
            String ans = "没搜到「" + intent.query + "」的相关价格资料：免 key 搜索返回的 " + stat[0] + " 条结果都和这款商品无关，已全部丢弃"
                    + (english != null ? "（英文名「" + english + "」补搜也没有）" : "（认不出英文名，没有补搜）")
                    + "，没有编造价格。配好搜索 Key 后再试会准确很多。";
            ans = withNotes(ans, notes);   // ★ 11-03 上机：京东要登录 / 值得买校验页这类跳过说明也要写进结论
            return new Outcome(false, Tier.SEARCH, tried, sources, ans, null, null, "没搜到这款商品（" + stat[0] + " 条无关结果已丢弃）");
        }
        java.util.Set<String> read = new java.util.HashSet<String>(), attempted = new java.util.HashSet<String>();
        java.util.Set<String> jsNoPrice = new java.util.LinkedHashSet<String>();
        boolean[] pddSkipped = {false};
        boolean canRead = mExtract != null && mExtract.ready();
        List<String> urls = pickShopUrls(results);
        if (canRead && !urls.isEmpty()) {
            tried.add(Tier.EXTRACT);
            readPages(urls, intent, q, pr, results, sources, read, attempted, jsNoPrice, pddSkipped);
        }
        // ★ 京东 / 天猫商品页 JS 渲染读不到价 → 比价站（什么值得买 / 慢慢买 / 粉象…）优先补读一轮
        if (canRead && !anyReadPrice(sources) && mSearch != null && !pr.cancelled()) {
            int before = results.size();
            if (!searchRound(dealSiteQueries(q), pq, pr, results, seen, stat)) return stopped(tried, sources);
            List<String> more = pickShopUrls(results, 4, 2, attempted);
            if (!more.isEmpty()) {
                if (!tried.contains(Tier.EXTRACT)) tried.add(Tier.EXTRACT);
                int got = readPages(more, intent, q, pr, results, sources, read, attempted, jsNoPrice, pddSkipped);
                notes.add((jsNoPrice.isEmpty() ? "原网页没读到价" : "京东 / 天猫商品页是脚本渲染、直连读不到价") + "，补读了比价站 " + got + " 个");
            } else if (results.size() == before && !jsNoPrice.isEmpty()) {
                notes.add("京东 / 天猫商品页是脚本渲染、直连读不到价，也没搜到可直接读的比价站");
            }
        }
        if (pddSkipped[0]) notes.add("拼多多网页要登录，已跳过（没有核到拼多多价格）");
        for (SearchResult s : results) {
            if (sources.size() >= SHOP_SOURCES_MAX) break;
            if (read.contains(norm(s.url)) || s.snippet.length() == 0) continue;
            sources.add(new Source(s.title, s.url, s.snippet, Tier.SEARCH));
        }
        if (sources.isEmpty()) {
            return new Outcome(false, tried.isEmpty() ? Tier.SEARCH : tried.get(tried.size() - 1), tried, sources, null, null, null, "没有拿到可用的价格资料");
        }
        List<PriceNote> prices = new ArrayList<PriceNote>(renderNotes);
        for (PriceNote p : priceNotes(sources)) if (prices.size() < PRICE_NOTES_MAX + RENDER_TOP_N * 2) prices.add(p);
        boolean enough = shopEnough(sources);
        String note = (enough ? "多平台核价：" : "比价资料不完整：") + sitesCovered(sources) + " 个站点、" + platformsCovered(sources) + " 个平台、"
                + read.size() + " 个原网页、" + renderNotes.size() + " 条渲染取价、" + prices.size() + " 条价格" + (stat[0] > 0 ? "、滤掉 " + stat[0] + " 条无关结果" : "");
        pr.step(enough ? "整理比价结论" : "资料不完整，按现有资料整理");
        Tier last = tried.isEmpty() ? Tier.SEARCH : tried.get(tried.size() - 1);
        String mat = shopMaterial(sources, prices, notes);
        boolean hasCmp = cmp != null && cmp.any();
        if (hasCmp) mat = com.onesay.assistant.v2.errand.compare.CompareSites.material(cmp) + mat;
        String ans = mSummarizer != null ? mSummarizer.answer(intent, mat) : null;
        if (ans == null || ans.trim().length() == 0) ans = hasCmp ? com.onesay.assistant.v2.errand.compare.CompareSites.answer(cmp, joinPrices(prices)) : fallbackAnswer(sources, prices);
        ans = withNotes(ans, notes);
        return new Outcome(true, last, tried, sources, ans, null, null, note);
    }

    // ------------------------------------------------------------------ ★ 11-03 15:53：比价网取价

    /** 比价网一轮：粉象比价（索引 + 商品页，静态）→ 网页搜索一次「X 慢慢买」拿爆料链接 → 慢慢买手机版爆料页（静态）。取消 = null。 */
    private com.onesay.assistant.v2.errand.compare.CompareSites.Report compareRound(ErrandIntent intent, ProductQuery pq, Progress pr, List<Tier> tried,
            List<Source> sources, List<SearchResult> results, java.util.Set<String> seen, int[] stat, List<String> notes) {
        com.onesay.assistant.v2.errand.compare.CompareSites.Report r = new com.onesay.assistant.v2.errand.compare.CompareSites.Report();
        tried.add(Tier.COMPARE);
        pr.step("比价网取价：粉象比价「" + clip(intent.query, 16) + "」");
        try {
            com.onesay.assistant.v2.errand.compare.CompareSites.fenxiang(intent.query, pq, mCompareHttp, now(), r);
        } catch (Throwable t) {
            r.notes.add("粉象比价读取出错，跳过");
        }
        pr.step("粉象比价：" + r.products.size() + " 个商品页");
        if (pr.cancelled()) return null;
        if (mSearch != null) {
            List<SearchResult> mm = new ArrayList<SearchResult>();
            if (!searchRound(java.util.Collections.singletonList(intent.query + " 慢慢买"), pq, pr, mm, seen, stat)) return null;
            results.addAll(mm);
            List<String> urls = new ArrayList<String>();
            for (SearchResult s : mm) if (com.onesay.assistant.v2.errand.compare.ManmanbuyParser.mobileUrl(s.url) != null) urls.add(s.url);
            if (!urls.isEmpty()) {
                pr.step("慢慢买爆料：读 " + Math.min(urls.size(), com.onesay.assistant.v2.errand.compare.CompareSites.DEALS_MAX) + " 条（手机版页面）");
                try {
                    com.onesay.assistant.v2.errand.compare.CompareSites.manmanbuy(urls, intent.query, pq, mCompareHttp, now(), r);
                } catch (Throwable t) {
                    r.notes.add("慢慢买爆料读取出错，跳过");
                }
            } else {
                r.notes.add("网页搜索没给出慢慢买爆料链接");
            }
        }
        for (com.onesay.assistant.v2.errand.compare.CompareProduct p : r.products) {
            sources.add(new Source(p.site + "：" + p.title, p.url, com.onesay.assistant.v2.errand.compare.CompareSites.pageText(p), Tier.COMPARE));
        }
        for (com.onesay.assistant.v2.errand.compare.CompareDeal d : r.deals) {
            sources.add(new Source(d.site + "爆料：" + clip(d.title, 30), d.url, com.onesay.assistant.v2.errand.compare.CompareSites.pageText(d), Tier.COMPARE));
        }
        for (String n : r.notes) addNote(notes, n);
        pr.step("比价：" + r.priceCount() + " 条价格、" + r.products.size() + " 个商品页、" + r.deals.size() + " 条爆料");
        return r;
    }

    /**
     * ★ 11-03 23:4x（用户：有线版 183.05 连续两轮没拿到）：什么值得买卡片按版本收进分组，再让每个版本组至少尝试一个比价网商品页
     * （粉象没收录的组 → 打开什么值得买爆料原页核价 / 看过期）。根因见 CompareSites.verifyGroups。
     */
    private void groupRound(ErrandIntent intent, ProductQuery pq, com.onesay.assistant.v2.errand.compare.CompareSites.Report cmp, Progress pr,
                            List<Source> sources, List<String> notes) {
        if (cmp == null) return;
        int before = cmp.notes.size();
        int got = 0, opened = 0;
        try {
            if (mSmzdmItems != null) got = com.onesay.assistant.v2.errand.compare.CompareSites.fromRender(mSmzdmItems, intent.query, pq, now(), cmp);
            if (pr.cancelled()) return;
            if (mCompareHttp != null) opened = com.onesay.assistant.v2.errand.compare.CompareSites.verifyGroups(cmp, intent.query, pq, mCompareHttp, now());
        } catch (Throwable t) {
            cmp.notes.add("什么值得买爆料分组 / 原页核价出错，跳过");
        }
        for (com.onesay.assistant.v2.errand.compare.CompareDeal d : cmp.deals) {
            if (!d.checked || !com.onesay.assistant.v2.errand.compare.SmzdmParser.SITE.equals(d.site)) continue;
            sources.add(new Source(d.site + "爆料：" + clip(d.title, 30), d.url, com.onesay.assistant.v2.errand.compare.CompareSites.pageText(d), Tier.COMPARE));
        }
        for (int i = before; i < cmp.notes.size(); i++) addNote(notes, cmp.notes.get(i));
        pr.step("比价分组：什么值得买卡片收进 " + got + " 条、打开爆料原页 " + opened + " 个（" + com.onesay.assistant.v2.errand.compare.CompareSites.groups(cmp).size() + " 个版本组）");
    }

    /** 商品直达链接（京东 / 天猫 / 淘宝商品页；返利跳转 union-click / uland / go.smzdm 不算，不去点）。 */
    private static final Pattern ITEM_LINK = Pattern.compile(
            "^https://(?:item\\.jd\\.com/\\d+\\.html|detail\\.tmall\\.com/item\\.htm\\?\\S*\\bid=\\d+|item\\.taobao\\.com/item\\.htm\\?\\S*\\bid=\\d+)");
    public static final int ITEM_CHECK_MAX = 2;

    static boolean directItem(String url) {
        return url != null && ITEM_LINK.matcher(url).find();
    }

    /** 比价网给的商品直达链接 → 渲染读商品页核价（≤{@link #ITEM_CHECK_MAX} 个；要登录 / 验证就跳过并注明）。 */
    private void itemCheck(List<PriceNote> renderNotes, ProductQuery pq, Progress pr, List<Tier> tried, List<Source> sources, List<String> notes) {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<String>();
        for (PriceNote p : renderNotes) if (directItem(p.link)) links.add(p.link);
        if (links.isEmpty()) {
            addNote(notes, "比价网没给商品直达链接（粉象只有 App 购买入口、慢慢买是返利跳转），没有逐个打开商品页核价");
            return;
        }
        int n = 0;
        for (String u : links) {
            if (n++ >= ITEM_CHECK_MAX || pr.cancelled()) break;
            pr.step("商品页核价（电脑版）：" + host(u));
            com.onesay.assistant.v2.errand.web.ReaderResult r;
            try {
                r = mRender.read(u);
            } catch (Throwable t) {
                r = null;
            }
            if (r == null) continue;
            if (r.skipped()) {
                addNote(notes, host(u) + " 商品页要登录 / 验证，没核价（没有尝试登录）");
                continue;
            }
            if (!r.ok() || r.items.isEmpty()) {
                addNote(notes, host(u) + " 商品页没读出价格");
                continue;
            }
            List<com.onesay.assistant.v2.errand.web.PriceItem> keep = com.onesay.assistant.v2.errand.web.ReaderParser.relevant(r.items, pq, 1);
            if (keep.isEmpty()) continue;
            String label = com.onesay.assistant.v2.errand.web.ShopSearchUrls.siteName(r.finalUrl) + "商品页";
            for (com.onesay.assistant.v2.errand.web.PriceItem it : keep) renderNotes.add(PriceNote.of(label, it));
            sources.add(new Source(label + "核价", r.finalUrl, r.withItems(keep).asText(RENDER_TEXT_CHARS), Tier.RENDER));
        }
    }

    /** 「其他比价站」只列没收进版本分组的（什么值得买卡片已按版本分组就不再重复）。 */
    static List<PriceNote> ungrouped(List<PriceNote> prices, com.onesay.assistant.v2.errand.compare.CompareSites.Report cmp) {
        boolean smz = false;
        for (com.onesay.assistant.v2.errand.compare.CompareDeal d : cmp.deals) if (com.onesay.assistant.v2.errand.compare.SmzdmParser.SITE.equals(d.site)) smz = true;
        if (!smz) return prices;
        List<PriceNote> out = new ArrayList<PriceNote>();
        for (PriceNote p : prices) if (!p.site.startsWith(com.onesay.assistant.v2.errand.compare.SmzdmParser.SITE)) out.add(p);
        return out;
    }

    static String joinPrices(List<PriceNote> prices) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (PriceNote p : prices) {
            if (k++ >= 5) break;
            sb.append("\n- ").append(p);
        }
        return sb.toString();
    }

    /** 比价网够用时的结论：结构化分组（无模型时直接用；有模型时作资料核心）。 */
    private Outcome compareOutcome(ErrandIntent intent, com.onesay.assistant.v2.errand.compare.CompareSites.Report cmp, List<Tier> tried, List<Source> sources,
                                   List<PriceNote> renderNotes, List<String> notes) {
        String mat = com.onesay.assistant.v2.errand.compare.CompareSites.material(cmp) + shopMaterial(sources, renderNotes, notes);
        String ans = mSummarizer != null ? mSummarizer.answer(intent, mat) : null;
        if (ans == null || ans.trim().length() == 0) {
            ans = cmp.any() ? com.onesay.assistant.v2.errand.compare.CompareSites.answer(cmp, joinPrices(ungrouped(renderNotes, cmp))) : fallbackAnswer(sources, renderNotes);
        }
        ans = withNotes(ans, notes);
        String note = "比价网取价：" + cmp.products.size() + " 个比价商品页、" + cmp.priceCount() + " 条价格、" + cmp.deals.size() + " 条爆料、"
                + renderNotes.size() + " 条渲染取价、" + sitesCovered(sources) + " 个站点、" + platformsCovered(sources) + " 个平台、"
                + (cmp.elapsedMs / 100) / 10.0 + " s";
        return new Outcome(true, Tier.COMPARE, tried, sources, ans, null, null, note);
    }

    /** 把「说明」插进结论：结论最后一行是反问 / 提议时插在它前面（「好 / 你决定」接续靠结尾那句）。 */
    static String withNotes(String ans, List<String> notes) {
        if (notes == null || notes.isEmpty()) return ans;
        StringBuilder n = new StringBuilder("说明：");
        for (int i = 0; i < notes.size(); i++) n.append(i > 0 ? "；" : "").append(notes.get(i));
        n.append('。');
        String a = ans == null ? "" : ans.trim();
        int k = a.lastIndexOf('\n');
        String lastLine = k >= 0 ? a.substring(k + 1).trim() : a;
        boolean asks = lastLine.endsWith("？") || lastLine.endsWith("?") || lastLine.endsWith("吗") || lastLine.endsWith("吗。");
        if (asks && k >= 0) return a.substring(0, k).trim() + "\n\n" + n + "\n\n" + lastLine;
        return a + "\n\n" + n;
    }

    public static String shopMaterial(List<Source> sources) {
        return shopMaterial(sources, priceNotes(sources), null);
    }

    /**
     * 比价资料：先列「读到的价格」（价格 + 日期 + 券后条件），再列来源（每条原网页截 {@link #SHOP_PAGE_CHARS} 字，
     * 总共 {@link #SHOP_MATERIAL_MAX} 字，编号与来源一致），最后「说明」（拼多多跳过 / JS 渲染 / 英文补搜）。
     */
    public static String shopMaterial(List<Source> sources, List<PriceNote> prices, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        if (prices != null && !prices.isEmpty()) {
            sb.append("【读到的价格（站点 价格 · 日期 · 券后条件）】\n");
            for (PriceNote p : prices) sb.append("- ").append(p).append('\n');
        }
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            if (sb.length() >= SHOP_MATERIAL_MAX) break;
            sb.append('[').append(i + 1).append("] ").append(s.title.length() > 0 ? s.title : s.url)
                    .append(s.tier == Tier.EXTRACT ? "（原网页）" : s.tier == Tier.RENDER ? "" : "（搜索摘要）").append('\n');
            String t = s.text;
            int room = Math.min(SHOP_PAGE_CHARS, SHOP_MATERIAL_MAX - sb.length());
            if (room <= 0) break;
            if (t.length() > room) t = t.substring(0, room) + "…";
            sb.append(t).append('\n');
            if (s.url.length() > 0) sb.append("来源：").append(s.url).append('\n');
        }
        if (notes != null && !notes.isEmpty()) {
            sb.append("【说明（结论里要如实写）】\n");
            for (String n : notes) sb.append("- ").append(n).append('\n');
        }
        return sb.toString();
    }

    private Outcome finish(ErrandIntent intent, Tier tier, List<Tier> tried, List<Source> sources, String handoff,
                           BrowseResult.Status st, String note) {
        String mat = material(sources);
        String ans = mSummarizer != null ? mSummarizer.answer(intent, mat) : null;
        if (ans == null || ans.trim().length() == 0) ans = fallbackAnswer(sources);
        return new Outcome(true, tier, tried, sources, ans, handoff, st, note);
    }

    private static Outcome stopped(List<Tier> tried, List<Source> sources) {
        return new Outcome(false, tried.isEmpty() ? Tier.SEARCH : tried.get(tried.size() - 1), tried, sources, null, null,
                BrowseResult.Status.FAILED, "已停止");
    }

    /** 没有模型时（比价）：先列读到的价格（价格 + 日期 + 券后条件，以下单页为准），再列来源。 */
    static String fallbackAnswer(List<Source> sources, List<PriceNote> prices) {
        if (prices == null || prices.isEmpty()) return fallbackAnswer(sources);
        StringBuilder sb = new StringBuilder("读到的价格（以下单页为准）：");
        for (PriceNote p : prices) sb.append("\n- ").append(p);
        sb.append("\n\n").append(fallbackAnswer(sources));
        return sb.toString();
    }

    /** 没有模型时：列出来源标题 + 一句摘要。 */
    static String fallbackAnswer(List<Source> sources) {
        StringBuilder sb = new StringBuilder("找到这些资料：");
        for (int i = 0; i < sources.size() && i < 5; i++) {
            Source s = sources.get(i);
            sb.append('\n').append(i + 1).append(". ").append(s.title.length() > 0 ? s.title : s.url);
            if (s.text.length() > 0) sb.append("：").append(clip(s.text, 60));
        }
        return sb.toString();
    }

    /** WebView 从哪儿开始：用户给的链接 → 购物站搜索页 → 搜索结果第一条 → 必应搜索页（PC）。 */
    static String startUrl(ErrandIntent intent, List<SearchResult> results) {
        if (intent.url != null) return intent.url;
        if (intent.kind == ErrandIntent.Kind.SHOP) {
            ErrandIntent.Site s = intent.site != null ? intent.site : ErrandIntent.DEFAULT_SHOP;
            return s.searchUrl(intent.query);
        }
        if (intent.query.length() == 0) return null;
        return "https://cn.bing.com/search?q=" + ErrandIntent.urlEncode(intent.query);
    }

    private static String titleFor(String url, List<SearchResult> results) {
        for (SearchResult r : results) if (sameUrl(r.url, url) && r.title.length() > 0) return r.title;
        return host(url);
    }

    static String host(String url) {
        if (url == null) return "";
        String s = url.replaceFirst("^https?://", "");
        int k = s.indexOf('/');
        return k > 0 ? s.substring(0, k) : s;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
