package com.onesay.assistant.v2.errand.web;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 14:38：默认电脑模式，不用 m. 移动站）：站内搜索 / 商品页的 <b>PC 域名</b> URL 构造与移动链接改写（纯 Java）。
 * <ul>
 *   <li>京东 search.jd.com / item.jd.com；天猫 list.tmall.com（站点会跳 s.taobao.com tab=mall）；淘宝 s.taobao.com；
 *       什么值得买 search.smzdm.com / www.smzdm.com；</li>
 *   <li>拼多多没有电脑版商品搜索（只有 mobile.yangkeduo.com 且要登录）→ {@link #search} 返回 null，调用方跳过并注明；</li>
 *   <li>一律 https；移动链接（item.m.jd.com、detail.m.tmall.com、h5.m.taobao.com、m.smzdm.com…）改写成 PC 链接。</li>
 * </ul>
 */
public final class ShopSearchUrls {

    private ShopSearchUrls() {}

    public static final String JD = "https://search.jd.com/Search?enc=utf-8&keyword=";
    public static final String TMALL = "https://list.tmall.com/search_product.htm?q=";
    public static final String TAOBAO = "https://s.taobao.com/search?q=";
    public static final String SMZDM = "https://search.smzdm.com/?c=home&v=b&s=";

    /** 平台名（ErrandIntent.Site.name：京东 / 天猫 / 淘宝 / 拼多多 / 什么值得买）→ PC 站内搜索 URL；没有 PC 搜索 = null。 */
    public static String search(String site, String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.length() == 0 || site == null) return null;
        String e = enc(k);
        if (site.contains("京东")) return JD + e;
        if (site.contains("天猫")) return TMALL + e;
        if (site.contains("淘宝")) return TAOBAO + e;
        if (site.contains("值得买") || site.equalsIgnoreCase("smzdm")) return SMZDM + e;
        return null;   // 拼多多 / 其他：没有电脑版站内搜索
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static final Pattern JD_ITEM_M = Pattern.compile("^https?://item\\.m\\.jd\\.com/(?:product/|ware/view\\.action\\?wareId=)(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JD_SEARCH_M = Pattern.compile("^https?://so\\.m\\.jd\\.com/.*[?&]keyword=([^&#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TMALL_ITEM_M = Pattern.compile("^https?://detail\\.m\\.tmall\\.com/item\\.htm\\?(?:.*&)?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TB_ITEM_M = Pattern.compile("^https?://(?:h5|market|main)\\.m\\.taobao\\.com/.*[?&]id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMZDM_M = Pattern.compile("^https?://(post\\.)?m\\.smzdm\\.com/(.*)$", Pattern.CASE_INSENSITIVE);

    /** 移动链接 → PC 链接；http → https；其他原样。 */
    public static String toDesktop(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.regionMatches(true, 0, "http://", 0, 7)) u = "https://" + u.substring(7);
        Matcher m = JD_ITEM_M.matcher(u);
        if (m.find()) return "https://item.jd.com/" + m.group(1) + ".html";
        m = JD_SEARCH_M.matcher(u);
        if (m.find()) return JD + m.group(1);
        m = TMALL_ITEM_M.matcher(u);
        if (m.find()) return "https://detail.tmall.com/item.htm?id=" + m.group(1);
        m = TB_ITEM_M.matcher(u);
        if (m.find()) return "https://item.taobao.com/item.htm?id=" + m.group(1);
        m = SMZDM_M.matcher(u);
        if (m.find()) return "https://" + (m.group(1) != null ? "post." : "www.") + "smzdm.com/" + m.group(2);
        if (u.regionMatches(true, 0, "https://m.jd.com", 0, 16)) return "https://www.jd.com" + u.substring(16);
        return u;
    }

    private static final Pattern KW = Pattern.compile("[?&](?:keyword|q|s|key|search_key)=([^&#]*)");

    /** 站内搜索页 URL 里的搜索词（AgentLoop 读 search.jd.com 等时据此做相关性过滤）；不是站内搜索页 = null。 */
    public static String keywordOf(String url) {
        if (url == null || !isShopSearch(url)) return null;
        Matcher m = KW.matcher(url);
        while (m.find()) {
            String v = m.group(1);
            try {
                v = java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            if (v.trim().length() > 0) return v.trim();
        }
        return null;
    }

    public static boolean isShopSearch(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return u.contains("search.jd.com/") || u.contains("list.tmall.com/") || u.contains("s.taobao.com/")
                || u.contains("search.smzdm.com/");
    }

    /** 站点显示名（京东 / 天猫 / 淘宝 / 什么值得买 / 慢慢买 / 拼多多 / host）。 */
    public static String siteName(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains("tab=mall") || u.contains("tmall.com")) return "天猫";
        if (u.contains("jd.com") || u.contains("3.cn")) return "京东";
        if (u.contains("taobao.com")) return "淘宝";
        if (u.contains("smzdm.com")) return "什么值得买";
        if (u.contains("manmanbuy.com")) return "慢慢买";
        if (u.contains("yangkeduo") || u.contains("pinduoduo")) return "拼多多";
        String s = u.replaceFirst("^https?://", "");
        int k = s.indexOf('/');
        return k > 0 ? s.substring(0, k) : s;
    }
}
