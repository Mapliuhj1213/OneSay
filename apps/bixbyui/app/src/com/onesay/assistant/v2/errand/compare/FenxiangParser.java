package com.onesay.assistant.v2.errand.compare;

import com.onesay.assistant.v2.errand.ProductQuery;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 15:53「用比价网取价」）：粉象比价 compare.fenxianglife.com 的静态解析（纯 Java，JVM 可测）。
 * <p>11-03 沙箱探测：桌面 UA 直连 200、服务端渲染（不用跑 JS）、无验证码；商品页 /p/&lt;slug&gt;.html 有京东 / 天猫各店券后价（带「官方」标、
 * 更新日期）、当前最低、历史最低 + 日期、近 30 / 180 天最低、每月最低、大促价、数据截至时间。站内搜索是前端拿 /search.json
 * （约 4800 行 [标题, 别名, 路径, 现价, 品类]，~630 KB）在本地过滤，所以这里同样下载索引、用 {@link ProductQuery} 过相关性。</p>
 * <p>注意 slug 不可信（razer-v3-pwctx6nobj 其实是「毒蝰V3 极速版」），只按标题判断。没有商品直达链接（「App购买」是 App 落地页），
 * 所以商品页核价要靠别的比价源给的链接。</p>
 */
public final class FenxiangParser {

    private FenxiangParser() {}

    public static final String SITE = "粉象比价";
    public static final String BASE = "https://compare.fenxianglife.com";
    public static final String INDEX_URL = BASE + "/search.json";

    /** 索引里的一行。 */
    public static final class Hit {
        public final String title;
        public final String alias;
        public final String url;
        public final String price;
        public final String category;

        Hit(String title, String alias, String url, String price, String category) {
            this.title = title;
            this.alias = alias;
            this.url = url;
            this.price = price;
            this.category = category;
        }

        @Override public String toString() {
            return title + " " + price + " " + url;
        }
    }

    /** 解析索引 JSON（[[标题, 别名, 路径, 现价, 品类], …]）；坏数据返回空表。 */
    public static List<Hit> parseIndex(String json) {
        List<Hit> out = new ArrayList<Hit>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json.trim());
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.optJSONArray(i);
                if (r == null || r.length() < 3) continue;
                String path = r.optString(2, "");
                if (!path.startsWith("/p/")) continue;
                out.add(new Hit(r.optString(0, ""), r.optString(1, ""), BASE + path, r.optString(3, ""), r.optString(4, "")));
            }
        } catch (Throwable t) {
            // 索引格式变了：当作没搜到
        }
        return out;
    }

    /**
     * 和商品名相关的行（标题 + 别名过 {@link ProductQuery#relevant}；问句写了代际（V3）时代际必须一致，V4 不算 V3）。
     * 问句写了版本（「X 极速版」「专业版 35K」）只留同版本；最多 max 条。
     */
    public static List<Hit> matches(List<Hit> index, ProductQuery pq, String query, int max) {
        List<Hit> out = new ArrayList<Hit>();
        int gen = ShopVariant.generation(query);
        String want = ShopVariant.of(query);
        for (Hit h : index) {
            if (out.size() >= max) break;
            String t = h.title + " " + h.alias;
            if (!strict(pq, h.title + " " + h.alias)) continue;
            if (gen >= 0 && ShopVariant.generation(t) != gen) continue;
            if (want.length() > 0 && !ShopVariant.same(want, ShopVariant.of(h.title))) continue;
            out.add(h);
        }
        return out;
    }

    /**
     * 比价网标题比搜索摘要规整，用严格口径：除品牌外每个关键 token（系列 / 型号 / 不认识的中文段）都要命中，
     * 只写了品牌的问句才只看品牌。（「雷蛇巴塞利斯蛇v3」不能匹配到「雷蛇 猎魂光蛛 V3」「雷蛇 毒蝰V3」。）
     */
    public static boolean strict(ProductQuery pq, String text) {
        String low = (text == null ? "" : text).toLowerCase(java.util.Locale.ROOT);
        boolean any = false;
        for (ProductQuery.Token t : pq.keyTokens()) {
            if (t.type == ProductQuery.Type.BRAND) continue;
            any = true;
            if (!t.matches(low)) return false;
        }
        if (any) return true;
        for (ProductQuery.Token t : pq.keyTokens()) if (t.matches(low)) return true;
        return pq.keyTokens().isEmpty();
    }

    /**
     * 同品牌同系列、代际更新的行（问「V3」时的 V4）→ 结论里提一句「新一代已上架」。问句没写代际返回空表。
     */
    public static List<Hit> newerGeneration(List<Hit> index, ProductQuery pq, String query, int max) {
        List<Hit> out = new ArrayList<Hit>();
        int gen = ShopVariant.generation(query);
        if (gen < 0) return out;
        List<ProductQuery.Token> keys = new ArrayList<ProductQuery.Token>();
        for (ProductQuery.Token t : pq.keyTokens()) if (t.type != ProductQuery.Type.MODEL) keys.add(t);
        if (keys.isEmpty()) return out;
        for (Hit h : index) {
            if (out.size() >= max) break;
            String low = (h.title + " " + h.alias).toLowerCase(java.util.Locale.ROOT);
            boolean all = true;
            for (ProductQuery.Token t : keys) if (!t.matches(low)) { all = false; break; }
            if (!all) continue;
            if (ShopVariant.generation(h.title) > gen) out.add(h);
        }
        return out;
    }

    // ------------------------------------------------------------------ 商品页

    private static final Pattern H1 = Pattern.compile("(?s)<h1[^>]*>(.*?)</h1>");
    private static final Pattern ASOF = Pattern.compile("截至\\s*(20\\d{2}-\\d{2}-\\d{2}(?:\\s+\\d{1,2}:\\d{2})?)");
    private static final Pattern HIST = Pattern.compile("历史最低价\\s*(¥[\\d,]+(?:\\.\\d+)?)\\s*（(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern CUR = Pattern.compile("当前最低\\s*(¥[\\d,]+(?:\\.\\d+)?)");
    private static final Pattern L30 = Pattern.compile("近30天最低\\s*(¥[\\d,]+(?:\\.\\d+)?)");
    private static final Pattern L180 = Pattern.compile("近180天最低\\s*(¥[\\d,]+(?:\\.\\d+)?)");
    private static final Pattern TREND = Pattern.compile("(30 天前每日最低券后价为\\s*¥[\\d,.]+，现在\\s*¥[\\d,.]+，(?:下降|上涨|上升)\\s*[\\d.]+%)");
    private static final Pattern ADVICE = Pattern.compile("<span class=\"advice[^\"]*\">([^<]{2,12})</span>");
    private static final Pattern SPEC = Pattern.compile("各平台报价（([^）<]{1,20})）");
    private static final Pattern SECTION_COMPARE = Pattern.compile("(?s)<section[^>]*id=\"compare\"[^>]*>(.*?)</section>");
    private static final Pattern TR = Pattern.compile("(?s)<tr>(.*?)</tr>");
    private static final Pattern TD = Pattern.compile("(?s)<td[^>]*>(.*?)</td>");
    private static final Pattern MUTED = Pattern.compile("(?s)<div class=\"muted\">(.*?)</div>");
    private static final Pattern TAG = Pattern.compile("(?s)<span class=\"tag[^\"]*\">(.*?)</span>");
    private static final Pattern MONTH_ROW = Pattern.compile("(?s)<tr><td>(20\\d{2}-\\d{2})</td><td class=\"r\">(¥[\\d,.]+)</td>\\s*<td[^>]*>([\\d/]+)</td><td[^>]*>([^<]*)</td></tr>");
    private static final Pattern PROMO_ROW = Pattern.compile("(?s)<tr><td>(20\\d{2}\\s*(?:618|双11|双十一|双12|年货节)[^<]*)</td><td class=\"r\">(¥[\\d,.]+)</td><td[^>]*>(20\\d{2}-\\d{2}-\\d{2})</td></tr>");

    /** 解析商品页；不是商品页 / 没价格返回 null。 */
    public static CompareProduct parseProduct(String html, String url, long fetchedAt) {
        if (html == null || html.length() < 200) return null;
        Matcher h = H1.matcher(html);
        if (!h.find()) return null;
        String title = text(h.group(1)).replaceFirst("\\s*价格$", "").trim();
        if (title.length() == 0) return null;
        String plain = text(html);
        String asOf = first(ASOF, plain, 1);
        String hist = "", histDate = "";
        Matcher hm = HIST.matcher(plain);
        if (hm.find()) {
            hist = hm.group(1);
            histDate = hm.group(2);
        }
        String cur = first(CUR, plain, 1);
        String l30 = first(L30, plain, 1);
        String l180 = first(L180, plain, 1);
        String trend = first(TREND, plain, 1).replace("每日最低券后价为", "").replaceAll("\\s+", " ");
        String verdict = first(ADVICE, html, 1);
        String spec = first(SPEC, plain, 1);
        List<CompareOffer> offers = new ArrayList<CompareOffer>();
        Matcher sec = SECTION_COMPARE.matcher(html);
        if (sec.find()) {
            Matcher tr = TR.matcher(sec.group(1));
            while (tr.find()) {
                List<String> tds = new ArrayList<String>();
                Matcher td = TD.matcher(tr.group(1));
                while (td.find()) tds.add(td.group(1));
                if (tds.size() < 3) continue;
                String cell = tds.get(0);
                Matcher mu = MUTED.matcher(cell);
                String shop = mu.find() ? text(mu.group(1)) : "";
                boolean official = false;
                Matcher tg = TAG.matcher(cell);
                while (tg.find()) if (text(tg.group(1)).contains("官方")) official = true;
                String platform = text(cell.replaceAll("(?s)<span.*?</span>|<div.*?</div>", ""));
                String pt = text(tds.get(1));
                double p = money(pt);
                if (p <= 0 || platform.length() == 0) continue;
                offers.add(new CompareOffer(platform, shop, official, p, pt, text(tds.get(2))));
            }
        }
        List<String[]> monthly = new ArrayList<String[]>();
        Matcher mr = MONTH_ROW.matcher(html);
        while (mr.find()) monthly.add(new String[]{mr.group(1), mr.group(2), mr.group(3), text(mr.group(4))});
        List<String[]> promos = new ArrayList<String[]>();
        Matcher pr = PROMO_ROW.matcher(html);
        while (pr.find()) promos.add(new String[]{text(pr.group(1)), pr.group(2), pr.group(3)});
        CompareProduct cp = new CompareProduct(SITE, title, spec, offers, money(cur), cur, money(hist), hist, histDate, l30, l180, trend,
                monthly, promos, asOf, verdict, url, fetchedAt);
        return cp.usable() ? cp : null;
    }

    static String first(Pattern p, String s, int g) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(g).trim() : "";
    }

    /** 「¥1,143.12」→ 1143.12；解析不了为 -1。 */
    public static double money(String s) {
        if (s == null) return -1;
        Matcher m = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)").matcher(s);
        if (!m.find()) return -1;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 去标签 + 实体 + 压空白。 */
    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)\\b.*?</\\1\\s*>", " ").replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return s.replaceAll("\\s+", " ").trim();
    }
}
