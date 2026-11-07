package com.onesay.assistant.v2.errand.web;

import com.onesay.assistant.v2.errand.ProductQuery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（11-03 14:37）：把 {@link ReaderScript} 的原始输出解析成 {@link ReaderResult}（纯 Java，JVM 可测）。
 * <ul>
 *   <li>价格：价格节点原文优先（「￥439.12」「439元」「券后¥399」），没有再从卡片文字里找；1–200000 元之外的丢；</li>
 *   <li>店铺：店铺节点文字，没有就从卡片文字里找「…旗舰店 / 专卖店 / 专营店 / 官方店 / 京东自营」；自营 = 卡片里有「自营」；旗舰 = 店名含「旗舰店」；</li>
 *   <li>比价站条目：商城（京东 / 天猫…）、爆料时间、券后条件；</li>
 *   <li>「下载 App / 打开 App」浮层：脚本标出的浮层文字从正文里整段剔掉，再丢掉剩下的短引导行；浮层里的价格卡片不要；</li>
 *   <li>相关性：{@link #relevant} 用 {@link ProductQuery} 过滤标题，并丢掉配件（防滑贴、脚贴、鼠标垫、「适用…」）。</li>
 * </ul>
 */
public final class ReaderParser {

    private ReaderParser() {}

    private static final Pattern PRICE_SYM = Pattern.compile("[¥￥]\\s*(\\d{1,6}(?:\\.\\d{1,2})?)");
    private static final Pattern THOUSANDS = Pattern.compile("(\\d)[,，](\\d{3})(?!\\d)");
    private static final Pattern PRICE_YUAN = Pattern.compile("(\\d{1,6}(?:\\.\\d{1,2})?)\\s*元");
    private static final Pattern PRICE_BARE = Pattern.compile("^\\D{0,6}(\\d{1,6}\\.\\d{1,2})\\D{0,6}$");
    private static final Pattern SHOP_IN_TEXT = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9（）()·&\\-]{2,24}?(?:官方旗舰店|旗舰店|专卖店|专营店|官方店|企业店|自营店))|(京东自营)");
    private static final Pattern MALL = Pattern.compile("京东|天猫超市|天猫|淘宝|拼多多|苏宁易购|抖音商城|唯品会|亚马逊|当当");
    private static final Pattern DATE = Pattern.compile(
            "20\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?|\\d{1,2}月\\d{1,2}日|\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}|\\d+\\s*(?:分钟|小时|天)前|今天\\s*\\d{1,2}:\\d{2}|昨天\\s*\\d{1,2}:\\d{2}");
    private static final Pattern COND = Pattern.compile(
            "券后|用券|需用券|领券|叠券|满\\d+减\\d+|立减\\d+元?|PLUS(?:会员)?价?|百亿补贴|国补|国家补贴|政府补贴|以旧换新|到手价?|秒杀|限时|会员价|学生价|定金|预售|首发价?|红包|返现",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern APP_LINE = Pattern.compile(ReaderScript.APP_RE);

    /** 正文少于这么多字且没价格 = 没渲染出内容。 */
    public static final int EMPTY_TEXT = 20;

    /** 解析 evaluateJavascript 的返回值（JSON 编码过的字符串，或已经是对象文本）。 */
    static JSONObject obj(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            if (s.length() == 0 || "null".equals(s)) return null;
            if (s.startsWith("\"")) s = new JSONArray("[" + s + "]").getString(0);
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 探针：{n 价格节点数, len 正文长度, pw, rs readyState}；解析失败 = null。 */
    public static int[] probe(String raw) {
        JSONObject o = obj(raw);
        if (o == null || o.has("err")) return null;
        return new int[]{o.optInt("n", 0), o.optInt("len", 0), o.optBoolean("pw") ? 1 : 0, "complete".equals(o.optString("rs")) ? 1 : 0};
    }

    /** 抽取结果 → ReaderResult（状态按 {@link ReaderPolicy#classify}）。 */
    public static ReaderResult parse(String raw, String url, long elapsedMs, long fetchedAt, int blockedSchemes) {
        JSONObject o = obj(raw);
        if (o == null) return ReaderResult.fail(ReaderResult.Status.ERROR, url, elapsedMs, "脚本没有返回");
        if (o.has("err")) return ReaderResult.fail(ReaderResult.Status.ERROR, url, elapsedMs, "脚本出错：" + clip(o.optString("err"), 80));
        String finalUrl = o.optString("u", url);
        List<String> ovs = new ArrayList<String>();
        JSONArray ova = o.optJSONArray("ov");
        if (ova != null) for (int i = 0; i < ova.length(); i++) {
            String x = ova.optString(i, "").trim();
            if (x.length() > 0) ovs.add(x);
        }
        String text = stripOverlays(o.optString("tx", ""), ovs);
        List<PriceItem> items = new ArrayList<PriceItem>();
        Set<String> seen = new HashSet<String>();
        JSONArray cards = o.optJSONArray("cards");
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject c = cards.optJSONObject(i);
                if (c == null) continue;
                PriceItem p = card(c, ovs, fetchedAt);
                if (p == null) continue;
                if (!seen.add(p.title + "|" + p.priceLabel())) continue;
                items.add(p);
            }
        }
        JSONObject main = o.optJSONObject("main");
        if (items.isEmpty() && main != null) {
            PriceItem p = mainItem(main, finalUrl, o.optString("ti", ""), text, fetchedAt);
            if (p != null) items.add(p);
        }
        ReaderPolicy.Verdict v = ReaderPolicy.classify(finalUrl, text, o.optBoolean("pw"), items.size());
        ReaderResult.Status st = v == ReaderPolicy.Verdict.LOGIN ? ReaderResult.Status.LOGIN
                : v == ReaderPolicy.Verdict.CAPTCHA ? ReaderResult.Status.CAPTCHA : ReaderResult.Status.OK;
        if (st != ReaderResult.Status.OK) items.clear();
        // ★ 11-03 上机：天猫跳淘宝 / 值得买腾讯云校验页，抽取时 body 是空的 —— 不算「读到了」
        if (st == ReaderResult.Status.OK && items.isEmpty() && text.trim().length() < EMPTY_TEXT) {
            return new ReaderResult(ReaderResult.Status.BLOCKED, url, finalUrl, o.optString("ti", ""), text, items, blockedSchemes, ovs.size(),
                    elapsedMs, fetchedAt, "页面没渲染出内容（可能是反爬校验页或还在跳转）");
        }
        return new ReaderResult(st, url, finalUrl, o.optString("ti", ""), text, items, blockedSchemes, ovs.size(), elapsedMs, fetchedAt,
                st == ReaderResult.Status.OK ? "" : ReaderPolicy.skipNote(ShopSearchUrls.siteName(finalUrl), v));
    }

    /** 正文里剔掉浮层文字（整段），再丢掉 ≤40 字的「下载 App」引导行。 */
    static String stripOverlays(String text, List<String> ovs) {
        String t = text == null ? "" : text;
        String flat = t;
        for (String ov : ovs) {
            if (ov.length() < 2) continue;
            flat = flat.replace(ov, " ");
        }
        StringBuilder sb = new StringBuilder();
        for (String line : flat.split("\n")) {
            String l = line.trim();
            if (l.length() == 0) continue;
            if (l.length() <= 40 && APP_LINE.matcher(l).find()) continue;
            boolean inOv = false;
            for (String ov : ovs) if (l.length() <= 60 && ov.replaceAll("\\s+", " ").contains(l.replaceAll("\\s+", " "))) { inOv = true; break; }
            if (inOv) continue;
            sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    /** 一张卡片 → PriceItem；价格读不出 / 在浮层里 = null。 */
    static PriceItem card(JSONObject c, List<String> ovs, long fetchedAt) {
        String t = c.optString("t", "");
        String pt = c.optString("p", "");
        for (String ov : ovs) if (ov.length() > 0 && t.length() > 0 && ov.contains(t)) return null;
        double price = price(pt);
        String priceText = pt;
        if (price < 0) {
            price = price(t);
            priceText = t;
        }
        if (price < 1 || price > 200000) return null;
        String title = c.optString("a", "").trim();
        if (title.length() == 0) title = clip(t, 60);
        String shop = c.optString("s", "").trim();
        if (shop.length() > 0 && price(shop) >= 0) shop = "";      // 店铺节点里混进了价格 → 不可信
        if (shop.length() == 0 || !SHOP_IN_TEXT.matcher(shop).find()) {
            Matcher sm = SHOP_IN_TEXT.matcher(t);
            if (sm.find()) shop = sm.group(1) != null ? sm.group(1) : sm.group(2);
        }
        shop = cleanShop(shop);
        boolean self = t.contains("自营") || shop.contains("自营");
        boolean flag = shop.contains("旗舰店");
        String mall = "";
        Matcher mm = MALL.matcher(t.replace(title, " "));
        if (mm.find()) mall = mm.group();
        Matcher dm = DATE.matcher(t);
        String date = dm.find() ? dm.group().replaceAll("\\s+", " ") : "";
        String cond = cond(pt + " " + t);
        return new PriceItem(price, symbolText(priceText, price), title, shop, mall, self, flag, c.optString("h", ""), date, cond, fetchedAt);
    }

    /** 商品页：主标题 + 首屏第一个价格 + 店铺。 */
    static PriceItem mainItem(JSONObject m, String url, String docTitle, String text, long fetchedAt) {
        String pt = m.optString("p", "");
        double price = price(pt);
        if (price < 1 || price > 200000) return null;
        String title = m.optString("t", "").trim();
        if (title.length() == 0) title = docTitle == null ? "" : docTitle.trim();
        String shop = cleanShop(m.optString("s", ""));
        if (shop.length() == 0) {
            Matcher sm = SHOP_IN_TEXT.matcher(text == null ? "" : (text.length() > 3000 ? text.substring(0, 3000) : text));
            if (sm.find()) shop = cleanShop(sm.group(1) != null ? sm.group(1) : sm.group(2));
        }
        String head = text == null ? "" : (text.length() > 1500 ? text.substring(0, 1500) : text);
        boolean self = shop.contains("自营") || head.contains("京东自营") || head.contains("自营");
        return new PriceItem(price, symbolText(pt, price), title, shop, "", self, shop.contains("旗舰店"), url, "", cond(pt + " " + head),
                fetchedAt);
    }

    private static String cleanShop(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", "").replaceAll("^(?:进店|店铺|进入店铺)", "").replaceAll("(?:进店|关注|联系客服|进入店铺|>)+$", "");
        return x.length() > 30 ? x.substring(0, 30) : x;
    }

    /** 价格数值：先找带 ¥ 的，再找「…元」，再找节点里唯一的小数（京东 .p-price i「439.00」）；读不出 = -1。 */
    public static double price(String s) {
        if (s == null) return -1;
        String t = THOUSANDS.matcher(s).replaceAll("$1$2");
        Matcher m = PRICE_SYM.matcher(t);
        if (m.find()) return num(m.group(1));
        m = PRICE_YUAN.matcher(t);
        if (m.find()) return num(m.group(1));
        m = PRICE_BARE.matcher(t.trim());
        if (m.find()) return num(m.group(1));
        return -1;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String symbolText(String raw, double price) {
        String r = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (r.length() > 0 && r.length() <= 24) return r;
        return price < 0 ? "" : "¥" + price;
    }

    static String cond(String s) {
        LinkedHashSet<String> cs = new LinkedHashSet<String>();
        Matcher m = COND.matcher(s == null ? "" : s);
        while (m.find()) cs.add(m.group());
        StringBuilder sb = new StringBuilder();
        for (String x : cs) sb.append(sb.length() > 0 ? "、" : "").append(x);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 相关性

    private static final Pattern ACCESSORY = Pattern.compile(
            "防滑贴|脚贴|鼠标脚|贴纸|贴膜|保护套|保护壳|收纳包|收纳盒|鼠标垫|替换线|充电线|数据线|接收器延长|皮肤|手柄贴|按键帽|微动开关|维修|配件");
    private static final Pattern FOR_OTHER = Pattern.compile("^(?:\\S{0,6})?(?:适用于?|适配|兼容|专用于?)");

    /** 配件 / 「适用 X 的…」：标题像配件就不算这款商品。 */
    public static boolean accessory(String title) {
        String t = title == null ? "" : title.trim();
        return ACCESSORY.matcher(t).find() || FOR_OTHER.matcher(t).find();
    }

    /** 用商品名过滤标题（{@link ProductQuery#relevant}）并去配件，保留前 n 个。 */
    public static List<PriceItem> relevant(List<PriceItem> items, ProductQuery pq, int n) {
        List<PriceItem> out = new ArrayList<PriceItem>();
        if (items == null) return out;
        for (PriceItem p : items) {
            if (out.size() >= n) break;
            if (pq != null && !pq.relevant(p.title, "")) continue;
            if (accessory(p.title)) continue;
            out.add(p);
        }
        return out;
    }

    /** AgentLoop web.read 站内搜索页最多回几个结果。 */
    public static final int AGENT_TOP_N = 8;

    /**
     * 渲染结果 → web.read 的页面：要登录 / 验证 = 一句短说明（verdict needs_sign_in / refused）；读失败 = null（调用方用静态结果）；
     * 站内搜索页按 URL 里的搜索词过相关性（去配件）、取前 {@link #AGENT_TOP_N} 个；没价格且正文不比静态结果长 = null。
     */
    public static com.onesay.assistant.v2.agent.WebTools.Page toPage(ReaderResult r, String staticText) {
        if (r == null) return null;
        if (r.skipped()) return new com.onesay.assistant.v2.agent.WebTools.Page(r.finalUrl, r.asText(200), null);
        if (!r.ok()) return null;
        List<PriceItem> items = r.items;
        String kw = ShopSearchUrls.keywordOf(r.url);
        if (kw == null) kw = ShopSearchUrls.keywordOf(r.finalUrl);
        if (kw != null) items = relevant(items, ProductQuery.parse(kw), AGENT_TOP_N);
        int stLen = staticText == null ? 0 : staticText.trim().length();
        if (items.isEmpty() && r.text.length() <= stLen) return null;
        return new com.onesay.assistant.v2.agent.WebTools.Page(r.finalUrl, r.withItems(items).asText(ReaderPolicy.TEXT_MAX), null);
    }

    /** 抓取时间「2026-11-03 14:52」（本机时区）。 */
    public static String stamp(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
        return f.format(new java.util.Date(ms));
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
