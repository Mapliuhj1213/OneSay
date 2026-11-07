package com.onesay.assistant.v2.errand.compare;

import com.onesay.assistant.v2.errand.ProductQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ fix/shop-research（用户 11-03 15:53「用能看历史价格、各平台价、现有购买方案的比价网试试」）：比价网取价。
 * <p>11-03 沙箱探测后选用（详见 RESEARCH-TEST-basilisk.md §7.9）：</p>
 * <ol>
 *   <li><b>粉象比价</b>（compare.fenxianglife.com）——静态、免登录、无反爬；索引 /search.json + 商品页：京东 / 天猫各店券后价、官方 / 自营、
 *       当前最低、历史最低与日期、近 30 / 180 天最低、每月最低、大促价、数据截至时间。覆盖面有限（只收录热门数码），没有的版本如实说没有。</li>
 *   <li><b>慢慢买</b>手机版爆料页（m.manmanbuy.com/zhekou.aspx?id=）——静态、免登录；电脑版链接改写成手机版同 id。给的是某天某商城的
 *       「购买方案」（原价 / 券 / 满减 / 补贴 / 实付）和「历史新低」标签；爆料有时效，超过 {@link CompareDeal#STALE_DAYS} 天标过期。
 *       慢慢买站内搜索在沙箱连不上、历史价接口要签名，所以爆料页链接靠网页搜索结果给。</li>
 *   <li>什么值得买搜索页（腾讯云 EdgeOne 校验，要 JS）——仍由 {@code BrowserReader} 渲染读，不在本类。</li>
 * </ol>
 * 购物宝 gwdang（滑块验证）、比一比价 bijiago（搜索 404 / 前端渲染）、慢慢买电脑版（阿里云滑块）免登录拿不到，不接。
 * <p>本类只读：GET 公开页面，不带 Cookie、不登录、不点「App购买」（那是 App 落地 / 返利链接）。</p>
 */
public final class CompareSites {

    private CompareSites() {}

    /** 读一个网址的原始正文（HTML / JSON）；失败返回 null。实现：安卓 = SimplePageReader.fetchRaw，JVM 测试 = fixture。 */
    public interface Http {
        String get(String url, int maxBytes);
    }

    /**
     * 粉象 search.json 读取上限。11-03 20:3x：索引已涨到 2,456,702 B，原 2 MiB 上限截断后 JSON 解析失败（0 款）→ 改 6 MiB。
     * 超过上限不解析截断内容：{@link Http#get} 回 {@link #OVERSIZE} 标记，记日志、写 note、走下一个比价源（慢慢买爆料）。
     */
    public static final int INDEX_MAX_BYTES = 6 * 1024 * 1024;
    /** {@link Http#get} 正文超过 maxBytes 时回的标记前缀（后跟已读到的字节数）；不是正文，调用方不得解析。 */
    public static final String OVERSIZE = "\u0000OVERSIZE:";

    /** 是不是「超过读取上限」标记。 */
    public static boolean oversize(String body) {
        return body != null && body.startsWith(OVERSIZE);
    }

    /** 正文；超限标记当没读到（null）。 */
    static String body(String s) {
        return oversize(s) ? null : s;
    }
    public static final int PAGE_MAX_BYTES = 600 * 1024;
    /** 粉象最多读几个商品页、慢慢买最多读几条爆料。 */
    public static final int FX_PAGES_MAX = 5;
    public static final int DEALS_MAX = 4;
    /** 索引缓存（进程内）。 */
    public static final long INDEX_TTL_MS = 6L * 3600 * 1000;

    private static String sIndex;
    private static long sIndexAt;

    /** 测试用：清缓存。 */
    public static synchronized void resetCache() {
        sIndex = null;
        sIndexAt = 0;
    }

    private static synchronized String index(Http http, long now, List<String> notes) {
        if (sIndex != null && now - sIndexAt < INDEX_TTL_MS && now >= sIndexAt) return sIndex;
        String j = http.get(FenxiangParser.INDEX_URL, INDEX_MAX_BYTES);
        if (oversize(j)) {
            notes.add("粉象比价索引超过 " + (INDEX_MAX_BYTES >> 20) + " MiB 读取上限（已读 " + j.substring(OVERSIZE.length()) + " B 即停），没解析截断内容，改用下一个比价源");
            return null;
        }
        if (j == null || !j.trim().startsWith("[")) {
            notes.add("粉象比价索引没读到（网络 / 页面变了），跳过");
            return null;
        }
        sIndex = j;
        sIndexAt = now;
        return j;
    }

    /** 一轮比价网结果。 */
    public static final class Report {
        public final List<CompareProduct> products = new ArrayList<CompareProduct>();
        public final List<CompareDeal> deals = new ArrayList<CompareDeal>();
        /** 新一代（问 V3 时的 V4）：{标题, 现价, 链接}。 */
        public final List<String[]> newer = new ArrayList<String[]>();
        public final List<String> notes = new ArrayList<String>();
        /** 读过的比价网页面。 */
        public final List<String> read = new ArrayList<String>();
        public long elapsedMs;

        public boolean any() {
            return !products.isEmpty() || !deals.isEmpty();
        }

        /** 带价格的条目数（每个商品页的各店报价 + 爆料）。 */
        public int priceCount() {
            int n = deals.size();
            for (CompareProduct p : products) n += Math.max(1, p.offers.size());
            return n;
        }

        /** 比价网够不够直接下结论：有一个带报价的商品页，或者 ≥2 条不过期的爆料。 */
        public boolean enough() {
            for (CompareProduct p : products) if (!p.offers.isEmpty()) return true;
            int fresh = 0;
            // 什么值得买搜索卡片没打开原页核过，不算（11-03 23:4x）
            for (CompareDeal d : deals) if (!d.stale() && (d.checked || !SmzdmParser.SITE.equals(d.site))) fresh++;
            return fresh >= 2;
        }
    }

    /** 粉象比价：索引 → 相关商品页（≤{@link #FX_PAGES_MAX}）；问 V3 顺带记新一代。 */
    public static void fenxiang(String query, ProductQuery pq, Http http, long now, Report out) {
        String j = index(http, now, out.notes);
        if (j == null) return;
        List<FenxiangParser.Hit> idx = FenxiangParser.parseIndex(j);
        List<FenxiangParser.Hit> hits = FenxiangParser.matches(idx, pq, query, FX_PAGES_MAX);
        for (FenxiangParser.Hit h : FenxiangParser.newerGeneration(idx, pq, query, 2)) out.newer.add(new String[]{h.title, h.price, h.url});
        if (hits.isEmpty()) {
            out.notes.add("粉象比价没收录这款（索引 " + idx.size() + " 款里没有和「" + query + "」相符的）");
            return;
        }
        for (FenxiangParser.Hit h : hits) {
            String html = body(http.get(h.url, PAGE_MAX_BYTES));
            out.read.add(h.url);
            CompareProduct p = FenxiangParser.parseProduct(html, h.url, now);
            if (p == null) {
                out.notes.add("粉象比价「" + h.title + "」页面没解析出价格");
                continue;
            }
            out.products.add(p);
        }
    }

    /** AgentLoop web.search 用：粉象索引里和商品名相符的行（≤max；索引读不到 = 空表）。 */
    public static List<FenxiangParser.Hit> fenxiangHits(String query, ProductQuery pq, Http http, long now, int max) {
        List<String> notes = new ArrayList<String>();
        String j = index(http, now, notes);
        if (j == null) return new ArrayList<FenxiangParser.Hit>();
        return FenxiangParser.matches(FenxiangParser.parseIndex(j), pq, query, max);
    }

    /**
     * AgentLoop web.read 用：比价网页面（粉象商品页 / 慢慢买爆料，电脑版链接改手机版）→ {实际网址, 结构化正文}；不是比价网页面或没解析出价格 = null。
     */
    public static String[] readPage(String url, Http http, long now) {
        if (url == null) return null;
        if (url.startsWith(FenxiangParser.BASE + "/p/")) {
            CompareProduct p = FenxiangParser.parseProduct(body(http.get(url, PAGE_MAX_BYTES)), url, now);
            return p == null ? null : new String[]{url, pageText(p)};
        }
        String m = ManmanbuyParser.mobileUrl(url);
        if (m != null) {
            CompareDeal d = ManmanbuyParser.parseDeal(body(http.get(m, PAGE_MAX_BYTES)), m, now);
            return d == null ? null : new String[]{m, pageText(d)};
        }
        String z = SmzdmParser.articleUrl(url);
        if (z != null) {
            CompareDeal d = SmzdmParser.parseArticle(body(http.get(z, PAGE_MAX_BYTES)), z, now);
            return d == null ? null : new String[]{z, pageText(d)};
        }
        return null;
    }

    /** 搜索词里去掉平台 / 价格类后缀，留商品名（「雷蛇巴塞利斯蛇V3 京东 价格」→「雷蛇巴塞利斯蛇V3」）。 */
    public static String productPart(String query) {
        if (query == null) return "";
        return query.replaceAll("(?i)京东|天猫|淘宝|拼多多|苏宁|什么值得买|值得买|慢慢买|粉象|比价|价格|多少钱|历史最低|历史价格|史低|券后|好价|优惠|报价|官网|旗舰店|自营|最便宜|哪里买|site:\\S+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** 慢慢买爆料：给的链接（网页搜索结果里的慢慢买链接）改写成手机版读，标题过相关性与代际。 */
    public static void manmanbuy(List<String> urls, String query, ProductQuery pq, Http http, long now, Report out) {
        Set<String> seen = new LinkedHashSet<String>();
        for (String u : urls) {
            String m = ManmanbuyParser.mobileUrl(u);
            if (m != null) seen.add(m);
        }
        int gen = ShopVariant.generation(query);
        String want = ShopVariant.of(query);
        int n = 0;
        for (String u : seen) {
            if (n >= DEALS_MAX) break;
            n++;
            String html = body(http.get(u, PAGE_MAX_BYTES));
            out.read.add(u);
            CompareDeal d = ManmanbuyParser.parseDeal(html, u, now);
            if (d == null) continue;
            if (!FenxiangParser.strict(pq, d.title)) continue;
            if (gen >= 0 && ShopVariant.generation(d.title) != gen) continue;
            if (want.length() > 0 && !ShopVariant.same(want, d.variant)) continue;
            out.deals.add(d);
        }
    }

    // ------------------------------------------------------------------ ★ 11-03 23:4x：什么值得买卡片进分组 + 每个版本组至少读一个比价网商品页

    /** 什么值得买搜索卡片最多收几条进分组（原来只取前 5 条进「其他比价站」，有线版的 183.05 排在后面就丢了）。 */
    public static final int RENDER_DEALS_MAX = 16;
    /** 没有比价商品页的版本组：每组最多打开几个爆料原页、一轮最多打开几个。 */
    public static final int VERIFY_PER_GROUP = 2;
    public static final int VERIFY_MAX = 5;

    /**
     * 什么值得买搜索页（浏览器渲染）的卡片 → 按版本分组的爆料：过严格相关性、代际（问 V3 不收 V4）、问句写了版本只收同版本，二手 / 翻新不收；
     * 同一链接或同标题同价去重。返回收进来的条数。
     */
    public static int fromRender(List<com.onesay.assistant.v2.errand.web.PriceItem> items, String query, ProductQuery pq, long now, Report out) {
        if (items == null || items.isEmpty()) return 0;
        int gen = ShopVariant.generation(query);
        String want = ShopVariant.of(query);
        Set<String> seen = new LinkedHashSet<String>();
        for (CompareDeal d : out.deals) seen.add(dealKey(d));
        int n = 0;
        for (com.onesay.assistant.v2.errand.web.PriceItem it : items) {
            if (n >= RENDER_DEALS_MAX) break;
            CompareDeal d = SmzdmParser.fromCard(it, now);
            if (d == null) continue;
            if (!FenxiangParser.strict(pq, d.title)) continue;
            if (gen >= 0 && ShopVariant.generation(d.title) != gen) continue;
            if (want.length() > 0 && !ShopVariant.same(want, d.variant)) continue;
            if (ShopVariant.used(d.title) || com.onesay.assistant.v2.errand.web.ReaderParser.accessory(d.title)) continue;
            if (!seen.add(dealKey(d))) continue;
            out.deals.add(d);
            n++;
        }
        return n;
    }

    static String dealKey(CompareDeal d) {
        String a = SmzdmParser.articleUrl(d.url);
        if (a == null) a = ManmanbuyParser.mobileUrl(d.url);
        return a != null ? a : d.title.replaceAll("\\s+", "") + "|" + d.priceText;
    }

    /**
     * 每个版本组至少尝试一个比价网商品页：已有粉象商品页、或已有慢慢买爆料页的组不动；其余组（例：粉象没收录的 V3 有线）
     * 按价格从低到高打开什么值得买爆料原页（≤{@link #VERIFY_PER_GROUP} 个 / 组、≤{@link #VERIFY_MAX} 个 / 轮），读到就用原页的价格、商城、
     * 更新时间、购买方案、过期标记替换搜索卡片；组里没有可读的原页链接就写 note。返回打开过的页数。
     */
    public static int verifyGroups(Report r, String query, ProductQuery pq, Http http, long now) {
        int opened = 0;
        int gen = ShopVariant.generation(query);
        for (Group g : groups(r)) {
            if (!g.products.isEmpty()) continue;
            boolean pageDeal = false;
            List<CompareDeal> cands = new ArrayList<CompareDeal>();
            for (CompareDeal d : g.deals) {
                if (d.checked || ManmanbuyParser.SITE.equals(d.site)) pageDeal = true;
                else if (SmzdmParser.articleUrl(d.url) != null) cands.add(d);
            }
            if (pageDeal) continue;
            if (cands.isEmpty()) {
                r.notes.add("【" + ShopVariant.label(g.variant) + "】粉象比价没收录、也没有可读的爆料原页链接，只有搜索卡片价");
                continue;
            }
            Collections.sort(cands, new Comparator<CompareDeal>() {
                @Override public int compare(CompareDeal a, CompareDeal b) {
                    return Double.compare(a.price, b.price);
                }
            });
            int k = 0, ok = 0;
            for (CompareDeal d : cands) {
                if (k >= VERIFY_PER_GROUP || opened >= VERIFY_MAX) break;
                k++;
                opened++;
                String u = SmzdmParser.articleUrl(d.url);
                CompareDeal v = SmzdmParser.parseArticle(body(http.get(u, PAGE_MAX_BYTES)), u, now);
                r.read.add(u);
                if (v == null) continue;
                ok++;
                int i = r.deals.indexOf(d);
                if (!FenxiangParser.strict(pq, v.title) || (gen >= 0 && ShopVariant.generation(v.title) != gen) || ShopVariant.used(v.title)) {
                    // 原页标题和商品对不上（卡片抽错链接 / 爆料改了标题）：卡片价不可信，丢掉
                    if (i >= 0) r.deals.remove(i);
                    r.notes.add("什么值得买卡片 " + d.priceText + " 的爆料原页是「" + clip(v.title, 24) + "」，和商品对不上，已丢弃");
                    continue;
                }
                if (i >= 0) r.deals.set(i, v);
                else r.deals.add(v);
            }
            if (k > 0 && ok == 0) r.notes.add("【" + ShopVariant.label(g.variant) + "】打开了 " + k + " 个什么值得买爆料原页都没解析出价格，只有搜索卡片价");
        }
        return opened;
    }

    // ------------------------------------------------------------------ 分组 / 推荐 / 文案

    /** 一个版本组。 */
    public static final class Group {
        public final String variant;
        public final List<CompareProduct> products = new ArrayList<CompareProduct>();
        public final List<CompareDeal> deals = new ArrayList<CompareDeal>();

        Group(String v) {
            variant = v;
        }

        /** 这组最低的现价（商品页当前最低，或者最新爆料价）；没有为 -1。 */
        public double low() {
            double best = -1;
            for (CompareProduct p : products) if (p.currentLow > 0 && (best < 0 || p.currentLow < best)) best = p.currentLow;
            if (best > 0) return best;
            for (CompareDeal d : deals) if (d.price > 0 && !d.stale() && (best < 0 || d.price < best)) best = d.price;
            if (best > 0) return best;
            for (CompareDeal d : deals) if (d.price > 0 && (best < 0 || d.price < best)) best = d.price;
            return best;
        }
    }

    /**
     * ★ 11-03 23:4x（用户：天猫国际自营 754.05 偏高）：同组里比本组最低高出 {@link #OUTLIER_RATIO} 倍以上的报价不当正常价并列，标「待核实」——
     * 比价网没给商品链接、核不了 SKU，可能是别的规格 / 套装 / 跨境版或店铺没跟价。跨境店（天猫国际 / 京东国际 / 全球购 / 海外）另标「跨境」。
     */
    public static final double OUTLIER_RATIO = 1.5;
    private static final java.util.regex.Pattern CROSS_BORDER = java.util.regex.Pattern.compile("天猫国际|京东国际|全球购|海外|跨境|免税");

    public static boolean crossBorder(String shop) {
        return shop != null && CROSS_BORDER.matcher(shop).find();
    }

    /** 待核实说明（不是离群价返回空串）。low = 本组参考最低价。 */
    public static String doubtNote(double price, double low, String shop) {
        if (price <= 0 || low <= 0 || price < low * OUTLIER_RATIO) return "";
        long pct = Math.round((price / low - 1) * 100);
        return "（待核实：比本组最低 ¥" + fmt(low) + " 高 " + pct + "%" + (crossBorder(shop) ? "，跨境店" : "")
                + "，比价网没给商品链接、核不了是不是同一规格 / 套装，不当正常价比较）";
    }

    /** 按版本分组（专业版 35K → 30K → 专业版 → X 极速版 → 35K 有线 → 有线 → 未标）。 */
    public static List<Group> groups(Report r) {
        Map<String, Group> m = new LinkedHashMap<String, Group>();
        for (CompareProduct p : r.products) group(m, p.variant).products.add(p);
        for (CompareDeal d : r.deals) group(m, d.variant).deals.add(d);
        List<Group> l = new ArrayList<Group>(m.values());
        Collections.sort(l, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                return ShopVariant.order(a.variant) - ShopVariant.order(b.variant);
            }
        });
        for (Group g : l) {
            Collections.sort(g.deals, new Comparator<CompareDeal>() {
                @Override public int compare(CompareDeal a, CompareDeal b) {
                    // ★ 11-03 23:4x：不过期的在前、按价格；过期的在后、按日期（原来一律按日期，组里只列 2 条时便宜的新爆料会被挤掉）
                    if (a.stale() != b.stale()) return a.stale() ? 1 : -1;
                    if (!a.stale()) return Double.compare(a.price, b.price);
                    return (a.ageDays < 0 ? 9999 : a.ageDays) - (b.ageDays < 0 ? 9999 : b.ageDays);
                }
            });
        }
        return l;
    }

    private static Group group(Map<String, Group> m, String v) {
        Group g = m.get(v);
        if (g == null) {
            g = new Group(v);
            m.put(v, g);
        }
        return g;
    }

    /** 推荐。 */
    public static final class Pick {
        public final String variant;
        public final String line;

        Pick(String variant, String line) {
            this.variant = variant;
            this.line = line;
        }
    }

    /**
     * 没说版本时默认推荐一款（规则，不经模型）：现价接近历史最低（≤103%）的版本里挑档次最高的（专业版 35K 优先）；
     * 都不在低位 → 挑最便宜的无线款；只有爆料 → 最新一条不过期的。理由写现价、史低与日期、30 天走势；同版本有自营且更贵就补一句。
     */
    public static Pick recommend(Report r) {
        List<Group> gs = groups(r);
        CompareProduct best = null;
        boolean low = false;
        for (Group g : gs) {
            for (CompareProduct p : g.products) {
                if (p.offers.isEmpty() && p.currentLow <= 0) continue;
                if (p.nearHistLow()) {
                    if (!low || ShopVariant.order(p.variant) < ShopVariant.order(best.variant)) best = p;
                    low = true;
                } else if (!low) {
                    if (best == null || (ShopVariant.wireless(p.variant) && (!ShopVariant.wireless(best.variant) || p.currentLow < best.currentLow))) best = p;
                }
            }
        }
        if (best != null) {
            CompareOffer c = best.cheapest();
            StringBuilder sb = new StringBuilder("推荐：").append(ShopVariant.label(best.variant)).append("（").append(best.title).append("）");
            if (c != null) sb.append("，").append(c.platform).append("「").append(c.shop).append("」").append(c.priceText);
            else sb.append("，").append(best.currentLowText);
            sb.append("——");
            if (low) {
                sb.append(best.currentLow <= best.histLow + 0.005 ? "现价和历史最低持平" : "现价接近历史最低");
                sb.append("（史低 ").append(best.histLowText).append(best.histLowDate.length() > 0 ? "，" + best.histLowDate : "").append("）");
            } else {
                sb.append("各版本现价都不在历史低位，这款是最便宜的").append(ShopVariant.wireless(best.variant) ? "无线款" : "一款")
                        .append("（史低 ").append(best.histLowText.length() > 0 ? best.histLowText : "未知").append("）");
            }
            if (best.trend.length() > 0) sb.append("，").append(best.trend);
            sb.append("。");
            CompareOffer self = best.cheapestSelfRun();
            if (self != null && c != null && self != c && self.price > c.price && doubtNote(self.price, c.price, self.shop).length() == 0) {
                sb.append("更在意售后就选").append(self.platform).append("自营「").append(self.shop).append("」").append(self.priceText)
                        .append("（多 ¥").append(fmt(self.price - c.price)).append("）。");
            }
            CompareProduct cheapW = null;
            for (CompareProduct p : r.products) {
                if (p == best || !ShopVariant.wireless(p.variant) || p.currentLow <= 0) continue;
                if (p.currentLow < (best.currentLow > 0 ? best.currentLow : Double.MAX_VALUE) && (cheapW == null || p.currentLow < cheapW.currentLow)) cheapW = p;
            }
            if (cheapW != null) {
                CompareOffer cc = cheapW.cheapest();
                sb.append("只要便宜的无线：").append(ShopVariant.label(cheapW.variant)).append(" ").append(cheapW.currentLowText);
                if (cc != null) sb.append("（").append(cc.platform).append("「").append(cc.shop).append("」）");
                sb.append("。");
            }
            CompareDeal wd = null;   // 有线：比价网没有商品页时，取有线各组里最便宜的一条爆料
            for (Group g : gs) {
                if (!(ShopVariant.WIRED.equals(g.variant) || ShopVariant.WIRED_35K.equals(g.variant)) || !g.products.isEmpty()) continue;
                for (CompareDeal d : g.deals) {
                    if (d.price <= 0) continue;
                    // 不过期的优先；都过期才用过期的
                    if (wd == null || (wd.stale() && !d.stale()) || (wd.stale() == d.stale() && d.price < wd.price)) wd = d;
                }
            }
            if (wd != null) {
                sb.append("要有线：").append(ShopVariant.label(wd.variant)).append(" 好价 ").append(wd.priceText).append("（")
                        .append(wd.mall).append("，").append(wd.date.length() > 0 ? wd.date : wd.time).append(wd.stale() ? "，已过期，下单前再核" : "").append("）。");
            }
            return new Pick(best.variant, sb.toString());
        }
        CompareDeal dd = null;
        for (CompareDeal d : r.deals) if (dd == null || (d.ageDays >= 0 && (dd.ageDays < 0 || d.ageDays < dd.ageDays))) dd = d;
        if (dd == null) return null;
        return new Pick(dd.variant, "推荐：" + ShopVariant.label(dd.variant) + "（" + dd.title + "），最近一条好价 " + dd.priceText + "（" + dd.mall + "，"
                + (dd.date.length() > 0 ? dd.date : dd.time) + (dd.stale() ? "，已过期，下单前再核" : "") + "）。");
    }

    static String fmt(double d) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", d);
        return s.endsWith(".00") ? s.substring(0, s.length() - 3) : s;
    }

    /** 每组最多列几条店铺报价。 */
    public static final int OFFERS_PER_GROUP = 4;
    /** 每组最多列几条爆料（原来 2 条）。 */
    public static final int DEALS_PER_GROUP = 3;

    /** 一组的报价行：最低 → 最低自营 → 其他平台最低 → 其余按价格。 */
    static List<CompareOffer> pickOffers(CompareProduct p) {
        List<CompareOffer> out = new ArrayList<CompareOffer>();
        CompareOffer c = p.cheapest();
        if (c != null) out.add(c);
        CompareOffer s = p.cheapestSelfRun();
        if (s != null && !out.contains(s)) out.add(s);
        Set<String> plats = new LinkedHashSet<String>();
        for (CompareOffer o : p.offers) plats.add(o.platform);
        for (String pl : plats) {
            CompareOffer o = p.cheapestOn(pl);
            if (o != null && !out.contains(o) && out.size() < OFFERS_PER_GROUP) out.add(o);
        }
        List<CompareOffer> rest = new ArrayList<CompareOffer>(p.offers);
        Collections.sort(rest, new Comparator<CompareOffer>() {
            @Override public int compare(CompareOffer a, CompareOffer b) {
                return Double.compare(a.price, b.price);
            }
        });
        for (CompareOffer o : rest) if (out.size() < OFFERS_PER_GROUP && !out.contains(o)) out.add(o);
        return out;
    }

    /** 一个商品页的「史低 / 走势」行。 */
    static String histLine(CompareProduct p) {
        StringBuilder sb = new StringBuilder();
        if (p.histLowText.length() > 0) sb.append("历史最低 ").append(p.histLowText).append(p.histLowDate.length() > 0 ? "（" + p.histLowDate + "）" : "");
        if (p.low30Text.length() > 0) sb.append(sb.length() > 0 ? " · " : "").append("近30天最低 ").append(p.low30Text);
        if (p.trend.length() > 0) sb.append(" · ").append(p.trend);
        if (!p.promos.isEmpty()) {
            String[] pr = p.promos.get(p.promos.size() - 1);
            sb.append(" · 上次").append(pr[0].replaceFirst("^20\\d{2}\\s*", "")).append("（").append(pr[0].substring(0, Math.min(4, pr[0].length())))
                    .append("）").append(pr[1]);
        }
        if (p.verdict.length() > 0) sb.append(" · ").append(p.site).append("：").append(p.verdict);
        return sb.toString();
    }

    /**
     * 没有模型时的结论（也是给模型的资料核心）：按版本分组列各平台价 + 历史最低 / 走势 + 爆料方案，提新一代，给推荐，停在付款前。
     */
    public static String answer(Report r, String extraPrices) {
        StringBuilder sb = new StringBuilder();
        String asOf = "";
        for (CompareProduct p : r.products) if (p.asOf.length() > 0) { asOf = p.asOf; break; }
        sb.append("按版本比价（").append(sourcesLabel(r)).append(asOf.length() > 0 ? "，粉象数据截至 " + asOf : "")
                .append("；券后价不含返利，以下单页为准）：");
        for (Group g : groups(r)) {
            sb.append("\n【").append(ShopVariant.label(g.variant)).append("】");
            double low = g.low();
            for (CompareProduct p : g.products) {
                sb.append(p.title).append(p.spec.length() > 0 ? "（" + p.spec + "）" : "");
                for (CompareOffer o : pickOffers(p)) sb.append("\n- ").append(o).append(doubtNote(o.price, low, o.shop));
                String h = histLine(p);
                if (h.length() > 0) sb.append("\n  ").append(h);
                sb.append("\n  来源：").append(p.url);
            }
            int k = 0;
            for (CompareDeal d : g.deals) {
                if (k++ >= DEALS_PER_GROUP) break;
                sb.append("\n- ").append(d).append(d.stale() ? "" : doubtNote(d.price, low, d.mall)).append("：").append(clip(d.title, 34));
                if (d.plan.length() > 0) sb.append("；").append(clip(d.plan, 70));
                sb.append("\n  来源：").append(d.url);
            }
        }
        if (extraPrices != null && extraPrices.trim().length() > 0) sb.append("\n其他比价站：").append(extraPrices.trim());
        for (String[] n : r.newer) sb.append("\n新一代：").append(n[0]).append(" 已上架（粉象现价 ").append(n[1]).append("）。");
        Pick pk = recommend(r);
        if (pk != null) sb.append("\n\n").append(pk.line);
        sb.append("\n我停在下单前，没有下单 / 付款。");
        return sb.toString();
    }

    static String sourcesLabel(Report r) {
        List<String> s = new ArrayList<String>();
        if (!r.products.isEmpty()) s.add(FenxiangParser.SITE);
        if (!r.deals.isEmpty()) s.add(ManmanbuyParser.SITE + "爆料");
        if (s.isEmpty()) return "比价网";
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(b.length() > 0 ? " + " : "").append(x);
        return b.toString();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    /** 给模型的资料块（结构化，编号外单独一段）。 */
    public static String material(Report r) {
        if (!r.any()) return "";
        return "【比价网结构化取价（按版本分组，代码从页面解析，不是模型生成；标「待核实」的价格照抄并保留「待核实」三个字，不和正常价并列、不拿来推荐）】\n"
                + answer(r, null) + "\n";
    }

    /** web.read 读到比价网页面时给 AgentLoop 的正文（结构化摘要 + 原文前段）。 */
    public static String pageText(CompareProduct p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.site).append("：").append(p.title).append("（版本 ").append(ShopVariant.label(p.variant)).append(p.spec.length() > 0 ? "，" + p.spec : "")
                .append("）数据截至 ").append(p.asOf.length() > 0 ? p.asOf : "未标").append("，券后价不含返利：");
        List<CompareOffer> all = new ArrayList<CompareOffer>(p.offers);
        double low = p.currentLow > 0 ? p.currentLow : -1;
        if (low <= 0) for (CompareOffer o : all) if (o.price > 0 && (low <= 0 || o.price < low)) low = o.price;
        for (CompareOffer o : all) sb.append("\n- ").append(o).append(doubtNote(o.price, low, o.shop));
        String h = histLine(p);
        if (h.length() > 0) sb.append("\n").append(h);
        for (String[] m : p.monthly) sb.append("\n每月最低 ").append(m[0]).append(" ").append(m[1]).append("（").append(m[2]).append("，").append(m[3]).append("）");
        return sb.toString();
    }

    public static String pageText(CompareDeal d) {
        return d.site + "爆料：" + d.title + "（版本 " + ShopVariant.label(d.variant) + "）\n- " + d + (d.plan.length() > 0 ? "\n购买方案：" + d.plan : "");
    }
}
