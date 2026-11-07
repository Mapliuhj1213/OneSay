package com.onesay.assistant.v2.errand;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ lane C3：浏览器跑腿的意图识别（纯 Java，JVM 可测）。
 *
 * <h3>只认「跑腿」说法，不抢普通问答</h3>
 * 「比特币多少钱」「明天天气」这类单点问句仍走 L2 问答（先搜再答）；这里只接：
 * <ul>
 *   <li>{@link Kind#RESEARCH} 调研：调研 / 研究一下 / 查资料 / 对比一下 / 去网上查…；</li>
 *   <li>{@link Kind#READ} 读链接：话里带 http(s) 链接或域名，且要「看 / 读 / 总结 / 帖子」；</li>
 *   <li>{@link Kind#SHOP} 购物：加购物车 / 比价 / 网上买，或「买 / 下单 / 搜」+ 购物网站名。<b>只到结算页，付款永远交给用户</b>。</li>
 * </ul>
 *
 * <h3>不做第三方 App 自动化（用户 10-22 决定）</h3>
 * 「在淘宝 App 里帮我…」「用微信…」→ {@link Kind#NONE}：不接，也不改走网页（用户点名了 App，换成网页会违背原意）。
 * 购物说的是网站名（京东 / 淘宝）时走<b>网页版</b>，不碰 App。
 */
public final class ErrandIntent {

    public enum Kind { NONE, RESEARCH, READ, SHOP }

    public final Kind kind;
    /** 原话（去首尾空白）。 */
    public final String raw;
    /** 交给搜索 / 浏览器的关键词（去掉了「帮我调研一下」这类指令词）。 */
    public final String query;
    /** 话里带的链接（READ 必有；其他可为 null）。 */
    public final String url;
    /** 购物网站（SHOP；没点名为 null → 默认京东网页版）。 */
    public final Site site;
    /** 调研要「深」：对比 / 调研 / 横评（摘要门槛更高）。 */
    public final boolean deep;
    /** ★ fix/shop-research：要比价的平台（点名了网站 = 只这一家；没点名 = {@link #DEFAULT_SHOPS} 多平台）。SHOP 以外为空。 */
    public final java.util.List<Site> sites;
    /**
     * ★ fix/shop-research：比价调研模式 —— 没点名网站、也没说「加购物车」的购物句（「帮我买 X」「比价一下 X」）：
     * 先多平台搜索 + 读原网页核价，给版本推荐和价格，停在下单前；不进 WebView 加购物车。
     */
    public final boolean compare;
    /** ★ fix/shop-research：多轮接续 —— 用户对上一轮提议 / 反问的回答（「你决定」「好」）；没有为 null。 */
    public final String followup;
    /** ★ 11-04 D：价格上限（元，0 = 没说）——筛选条件，不进关键词。 */
    public final int priceMax;
    /** ★ 11-04 D：价格下限（元，0 = 没说）。 */
    public final int priceMin;
    /** ★ 11-04 D：没有具体型号的推荐类（「1000 以内的人体工学椅有没有推荐」）→ 推荐 3 款代表款，不做单品比价。 */
    public final boolean recommend;

    private ErrandIntent(Kind kind, String raw, String query, String url, Site site, boolean deep) {
        this(kind, raw, query, url, site, deep, false, null);
    }

    private ErrandIntent(Kind kind, String raw, String query, String url, Site site, boolean deep, boolean compare, String followup) {
        this(kind, raw, query, url, site, deep, compare, followup, null);
    }

    private ErrandIntent(Kind kind, String raw, String query, String url, Site site, boolean deep, boolean compare, String followup, ShopQuery sq) {
        this.priceMax = sq == null ? 0 : sq.priceMax;
        this.priceMin = sq == null ? 0 : sq.priceMin;
        this.recommend = sq != null && sq.recommend;
        this.kind = kind;
        this.raw = raw;
        this.query = query;
        this.url = url;
        this.site = site;
        this.deep = deep;
        this.compare = compare;
        this.followup = followup;
        java.util.List<Site> l = new java.util.ArrayList<Site>();
        if (kind == Kind.SHOP) {
            if (compare) l.addAll(DEFAULT_SHOPS);
            else l.add(site != null ? site : DEFAULT_SHOP);
        }
        this.sites = java.util.Collections.unmodifiableList(l);
    }

    /** 用户把决定权交给了助手（「你决定 / 你看着办 / 随便 / 都行」）。 */
    public boolean userDelegated() {
        return followup != null && DELEGATE.matcher(followup).find();
    }

    private static final Pattern DELEGATE = Pattern.compile("你决定|你看着办|你定|你来定|你挑|你选|随便|都行|都可以|看着办");

    public boolean isErrand() {
        return kind != Kind.NONE;
    }

    @Override
    public String toString() {
        return kind + "{q=" + query + (url != null ? " url=" + url : "") + (site != null ? " site=" + site.name : "") + (deep ? " deep" : "")
                + (compare ? " compare=" + sites.size() + "站" : "") + (followup != null ? " followup" : "")
                + (priceMax > 0 ? " max=" + priceMax : "") + (priceMin > 0 ? " min=" + priceMin : "") + (recommend ? " recommend" : "") + "}";
    }

    // ------------------------------------------------------------------ 购物网站（网页版，PC 端搜索地址）

    public static final class Site {
        public final String name;
        public final String host;
        private final String searchPrefix;

        Site(String name, String host, String searchPrefix) {
            this.name = name;
            this.host = host;
            this.searchPrefix = searchPrefix;
        }

        public String searchUrl(String keyword) {
            return searchPrefix + urlEncode(keyword == null ? "" : keyword.trim());
        }
    }

    public static final Site JD = new Site("京东", "jd.com", "https://search.jd.com/Search?enc=utf-8&keyword=");
    public static final Site TAOBAO = new Site("淘宝", "taobao.com", "https://s.taobao.com/search?q=");
    public static final Site TMALL = new Site("天猫", "tmall.com", "https://list.tmall.com/search_product.htm?q=");
    public static final Site PDD = new Site("拼多多", "yangkeduo.com", "https://mobile.yangkeduo.com/search_result.html?search_key=");
    public static final Site DANGDANG = new Site("当当", "dangdang.com", "https://search.dangdang.com/?key=");
    public static final Site SUNING = new Site("苏宁", "suning.com", "https://search.suning.com/");
    public static final Site AMAZON = new Site("亚马逊", "amazon.com", "https://www.amazon.com/s?k=");

    /** 没点名网站、要进站加购物车时的主站（京东网页版搜索结果带价格，PC 版无需 App）。 */
    public static final Site DEFAULT_SHOP = JD;
    /**
     * ★ fix/shop-research：没点名网站时的比价平台（用户 11-03：至少京东 + 淘宝 / 天猫两家，拿得到再加拼多多）。
     * 顺序 = 搜索顺序；拼多多网页常要登录，拿不到不算失败。
     */
    public static final java.util.List<Site> DEFAULT_SHOPS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(JD, TMALL, TAOBAO, PDD));

    private static final Object[][] SITES = {
            {Pattern.compile("京东|jd\\.com"), JD},
            {Pattern.compile("天猫|tmall"), TMALL},
            {Pattern.compile("淘宝|taobao"), TAOBAO},
            {Pattern.compile("拼多多|pdd|yangkeduo"), PDD},
            {Pattern.compile("当当"), DANGDANG},
            {Pattern.compile("苏宁"), SUNING},
            {Pattern.compile("亚马逊|amazon", Pattern.CASE_INSENSITIVE), AMAZON},
    };

    // ------------------------------------------------------------------ 规则

    private static final Pattern URL = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)|((?:www\\.)?[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.(?:com|cn|net|org|io|me|tv|cc|top)(?:/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?)", Pattern.CASE_INSENSITIVE);

    /** 第三方 App 里操作：不接（用户 10-22：GUI 兜底先别碰第三方 App 自动化）。 */
    private static final Pattern IN_APP = Pattern.compile(
            "(?:在|用|打开|去)(?:微信|支付宝|淘宝|京东|拼多多|美团|饿了么|抖音|小红书|闲鱼|天猫|滴滴|高德|百度地图|微博|QQ)\\s*(?:App|app|APP|客户端|应用)(?:里|上|中)?|"
                    + "(?:微信|支付宝|美团|饿了么|抖音|小红书|闲鱼|滴滴|QQ)(?:里|上)(?:帮我|给我)?");

    private static final Pattern RESEARCH = Pattern.compile(
            "调研|研究一下|研究研究|帮我研究|查资料|查查资料|找资料|搜集资料|收集资料|整理一下资料|做个?对比|对比一下|比较一下|横向对比|横评|"
                    + "去网上(?:查|找|看|搜)|上网(?:查|找|看|搜)|网上(?:查一?下|找找|看看|搜搜)|帮我(?:在)?网上|跑个腿|跑一趟腿|浏览器帮我|用浏览器(?:查|看|搜)");
    private static final Pattern DEEP = Pattern.compile("调研|对比|比较|横评|研究|整理");

    private static final Pattern READ_VERB = Pattern.compile("看看|看一下|看下|读一下|读读|读一读|打开看|总结|概括|讲了什么|说了什么|写了什么|帖子|文章|链接");

    private static final Pattern SHOP_STRONG = Pattern.compile("加(?:入|进|到)?购物车|放(?:进|到|入)购物车|比价|比比价|比一下价|哪家便宜|哪里买便宜|最便宜|网上买|网购|帮我淘");
    private static final Pattern SHOP_VERB = Pattern.compile("买|下单|搜|找|挑|选购|购买|入手");
    /** 加购物车 / 下单进站这类要动网页的说法（不是比价调研）。 */
    private static final Pattern CART = Pattern.compile("购物车|加购|结算|提交订单");

    /**
     * ★ fix/shop-research（RESEARCH-TEST-basilisk R1）：没点名网站的「买 / 入手 / 下单 / 想买 / 帮我买 + 商品」也算购物。
     * 动词要在句首或跟在「帮我 / 给我 / 我想 / 想 / 我要 / 要 / 准备 / 打算 / 去」后面；后面至少两个字的商品名。
     */
    private static final Pattern BUY = Pattern.compile(
            "(?:^|帮我|给我|替我|帮忙|我想|想|我要|要|准备|打算|去|请)(?:买|购买|入手|下单|下个单)(?:一下|一个|一台|一部|一双|一件|一只|一款|一箱|一盒|一瓶|个|台|部|双|件|只|款|箱|盒|瓶|点|些)?\\s*([^，。？?！!]{2,})");
    /** 不是购物：买单 / 买菜谱 / 已经买了 / 票务 / 外卖充值（另有入口）/ 只是问句。 */
    private static final Pattern BUY_EXCLUDE = Pattern.compile(
            "买单|埋单|买菜谱|菜谱|买账|买了吗|买过|买了没|买的(?:快递|东西|那个|啥)|我买的|买到了吗|下单了吗|下过单|订单(?:到|状态|物流)|"
                    + "(?:机|车|火车|高铁|动车|电影|门|演唱会|船)票|外卖|点餐|话费|充值|缴费|打车|保险|股票|基金|彩票|房子|买房");

    /** 指令词：从原话里剥掉，剩下的当搜索词。 */
    private static final Pattern STRIP = Pattern.compile(
            "^(?:请|麻烦|嗨)|你帮我|替我|帮忙|"
                    + "调研一下|调研|研究一下|研究研究|研究|查查资料|查资料|找资料|搜集资料|收集资料|整理一下资料|整理一下|整理|"
                    + "做个对比|做对比|对比一下|比较一下|横向对比|横评|去网上|上网|网上|跑个腿|跑一趟腿|用浏览器|浏览器帮我|浏览器|"
                    + "加入购物车|加进购物车|加到购物车|加购物车|放进购物车|放到购物车|放入购物车|购物车|"
                    + "比比价|比一下价|比价|哪家便宜|哪里买便宜|网购|网上买|帮我淘|"
                    + "在(?:京东|淘宝|天猫|拼多多|当当|苏宁|亚马逊)(?:上|里|网)?|(?:京东|淘宝|天猫|拼多多|当当|苏宁|亚马逊)(?:上|里)?|"
                    + "读一下|读读|读一读|打开看看|讲了什么|说了什么|写了什么|讲的什么|这个帖子|这篇文章|这个链接|帖子|文章|链接|总结一下|概括一下|"
                    + "看看|看一下|查一下|查查|搜一下|搜搜|找找|找一下|帮我|给我|一下|然后|并且|再|吧|呢|啊|关于|有关|的资料|资料|"
                    + "^(?:我想|我要|想要|想|要|准备|打算|去)?(?:搜|查|找|买|购买|入手|下单|下个单)(?:一下|一个|一台|一部|一双|一件|一只|一款|一箱|一盒|一瓶|个|台|部|双|件|只|款|箱|盒|瓶)?");

    private ErrandIntent() {
        this(Kind.NONE, "", "", null, null, false);
    }

    public static ErrandIntent none(String raw) {
        return new ErrandIntent(Kind.NONE, raw == null ? "" : raw.trim(), "", null, null, false);
    }

    /** 主入口。 */
    public static ErrandIntent parse(String utterance) {
        String raw = utterance == null ? "" : utterance.trim();
        // ★ fix/shop-research：多轮接续改写（TaskContinuation）—— 标记后面是用户对上一轮的回答，不进搜索词
        String followup = null;
        int mk = raw.indexOf(com.onesay.assistant.v2.router.TaskContinuation.MARKER);
        if (mk >= 0) {
            followup = com.onesay.assistant.v2.router.TaskContinuation.answerOf(raw.substring(mk));
            raw = raw.substring(0, mk).trim();
        }
        if (raw.length() < 3) return none(raw);
        if (IN_APP.matcher(raw).find()) return none(raw);

        String url = findUrl(raw);
        Site site = findSite(raw);

        boolean shopStrong = SHOP_STRONG.matcher(raw).find();
        boolean shopSite = site != null && SHOP_VERB.matcher(raw).find();
        boolean shopBuy = site == null && url == null && isBuy(raw);
        if (shopStrong || shopSite || shopBuy) {
            String q = clean(raw, url);
            // ★ 11-04 D：搜索词 = 品类 + 型号（ShopQuery 抽取：去口语填充、价格转筛选条件、量词「张 / 把 / 套」也剥掉），过校验才用
            ShopQuery sq = url == null ? ShopQuery.parse(raw) : null;
            if (sq != null && ShopQuery.valid(sq.keyword, raw)) q = sq.keyword;
            else sq = sq != null ? new ShopQuery(q, sq.priceMax, sq.priceMin, sq.recommend) : null;
            if (q.length() == 0) return none(raw);
            // ★ fix/shop-research：没点名网站、不加购物车 → 多平台比价调研（停在下单前）
            boolean compare = site == null && !CART.matcher(raw).find();
            return new ErrandIntent(Kind.SHOP, raw, q, url, site != null ? site : DEFAULT_SHOP, true, compare, followup, sq);
        }
        if (url != null && READ_VERB.matcher(raw).find()) {
            String q = clean(raw, url);
            return new ErrandIntent(Kind.READ, raw, q, url, null, false);
        }
        if (RESEARCH.matcher(raw).find()) {
            String q = clean(raw, url);
            if (q.length() == 0 && url == null) return none(raw);
            return new ErrandIntent(Kind.RESEARCH, raw, q, url, null, DEEP.matcher(raw).find());
        }
        return none(raw);
    }

    /** ★ fix/shop-research：「帮我买 X / 我想入手 X / 下单 X」（排除买单、买菜谱、已买、票务等）。 */
    public static boolean isBuy(String utterance) {
        if (utterance == null) return false;
        String s = utterance.trim();
        if (BUY_EXCLUDE.matcher(s).find()) return false;
        Matcher m = BUY.matcher(s);
        if (!m.find()) return false;
        String item = m.group(1).replaceAll("[吧呢啊呀吗嘛了\\s]+$", "").trim();
        if (item.length() < 2) return false;
        // 商品名不能只是语气 / 代词
        return !item.matches("(?:什么|啥|哪个|哪款|东西|点东西|个东西|这个|那个|一个|好的|好|了)+");
    }

    /** ★ arch/agent-loop：点名要跑腿 / 用浏览器（这类调研仍归跑腿，其余调研句进 AgentLoop）。 */
    private static final Pattern EXPLICIT_ERRAND = Pattern.compile("跑个腿|跑一趟腿|跑腿|浏览器帮我|用浏览器|浏览器里|打开浏览器");

    public static boolean explicitErrand(String utterance) {
        return utterance != null && EXPLICIT_ERRAND.matcher(utterance).find();
    }

    static String findUrl(String s) {
        Matcher m = URL.matcher(s);
        while (m.find()) {
            String u = m.group();
            // 去掉尾部中文标点
            u = u.replaceAll("[，。,.!！?？)）]+$", "");
            if (u.length() < 4) continue;
            if (!u.toLowerCase().startsWith("http")) u = "https://" + u;
            return u;
        }
        return null;
    }

    static Site findSite(String s) {
        for (Object[] kv : SITES) {
            if (((Pattern) kv[0]).matcher(s).find()) return (Site) kv[1];
        }
        return null;
    }

    static String clean(String raw, String url) {
        String s = raw;
        if (url != null) {
            Matcher m = URL.matcher(s);
            s = m.replaceAll(" ");
        }
        s = s.replaceAll("[，,。.！!？?、~～…:：\"“”'‘’]+", " ");
        for (int i = 0; i < 3; i++) s = STRIP.matcher(s.trim()).replaceAll(" ");
        s = s.replaceAll("^(?:个|下|的|一|把|将)+", "").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
