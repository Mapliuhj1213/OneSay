package com.onesay.assistant.v2.errand.compare;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 23:35「有线版 183.05 连续两轮没拿到」）：什么值得买好价爆料原页（www.smzdm.com/p/&lt;id&gt;/）的静态解析，
 * 以及把什么值得买搜索页（浏览器渲染）里的卡片转成按版本分组的爆料（纯 Java，JVM 可测）。
 * <p>11-03 23:4x 沙箱探测：搜索页 search.smzdm.com 要 EdgeOne JS 校验（照旧由 BrowserReader 渲染读），
 * 但爆料原页 /p/&lt;id&gt;/ 桌面 UA 直连 200、服务端渲染：标题（h1.J_title）、价格块（「183.05 元 （淘金币可抵9.03元起） 258元」）、
 * 更新时间（当天只写「09:53」、往前「10-20 09:56」、跨年「2025-12-30 15:03」）、购买渠道（「天猫国际官方直营」）、
 * og:description 里的购买方案（「…领取88VIP 95折，88VIP消费券 满200减25元优惠券，最终到手价183.05元/件…」）；
 * 过期的爆料页顶部写「此爆料发布时间较久，价格可能已过期」、底部「来晚了~优惠已错过」（card-bottom-overdue）。</p>
 * <p>只读：GET 公开页面，不带 Cookie、不登录、不点「去购买」（go.smzdm 返利跳转）。</p>
 */
public final class SmzdmParser {

    private SmzdmParser() {}

    public static final String SITE = "什么值得买";

    private static final Pattern ARTICLE = Pattern.compile("^https?://(?:www\\.|m\\.)?smzdm\\.com/p/(\\d{6,12})(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);

    /** 什么值得买爆料原页链接（电脑版 / 手机版）→ 电脑版 https://www.smzdm.com/p/&lt;id&gt;/；不是爆料页（post. 文章、go. 跳转、搜索页）返回 null。 */
    public static String articleUrl(String url) {
        if (url == null) return null;
        Matcher m = ARTICLE.matcher(url.trim());
        return m.find() ? "https://www.smzdm.com/p/" + m.group(1) + "/" : null;
    }

    private static final Pattern H1 = Pattern.compile("(?s)<h1 class=\"title J_title\">(.*?)</h1>");
    private static final Pattern PRICE_BLOCK = Pattern.compile("(?s)<div class=\"price\">(.*?)</div>\\s*</div>");
    private static final Pattern PRICE = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*元(\\s*[（(][^）)]{1,40}[）)])?");
    private static final Pattern TIME = Pattern.compile("<span class=\"time\">\\s*更新时间：\\s*([^<]{3,24}?)\\s*</span>");
    private static final Pattern MALL = Pattern.compile("(?s)<div class=\"item-title\">购买渠道</div>\\s*<div class=\"item-subtitle\">\\s*<span>(.*?)</span>");
    private static final Pattern OG_DESC = Pattern.compile("<meta property=\"og:description\" content=\"([^\"]{4,400})\"");
    private static final Pattern EXPIRED = Pattern.compile("此爆料发布时间较久|价格可能已过期|优惠已错过|class=\"card-bottom-overdue\"|<span[^>]*>\\s*已售罄\\s*</span>|<span[^>]*>\\s*已过期\\s*</span>");

    /** 解析爆料原页；不是爆料页 / 没价格返回 null。now = 抓取时刻（推断日期、算距今天数，按 Asia/Shanghai）。 */
    public static CompareDeal parseArticle(String html, String url, long now) {
        if (html == null || html.length() < 200) return null;
        Matcher h = H1.matcher(html);
        if (!h.find()) return null;
        String title = FenxiangParser.text(h.group(1));
        if (title.length() == 0) return null;
        double price = -1;
        String priceText = "";
        Matcher pb = PRICE_BLOCK.matcher(html);
        if (pb.find(h.end())) {
            Matcher pm = PRICE.matcher(FenxiangParser.text(pb.group(1)));
            if (pm.find()) {
                price = FenxiangParser.money(pm.group(1));
                priceText = pm.group(1) + "元" + (pm.group(2) != null ? pm.group(2).trim() : "");
            }
        }
        Matcher tm = TIME.matcher(html);
        String time = tm.find() ? tm.group(1).trim() : "";
        Matcher mm = MALL.matcher(html);
        String mall = mm.find() ? FenxiangParser.text(mm.group(1)) : "";
        Matcher od = OG_DESC.matcher(html);
        String plan = od.find() ? FenxiangParser.text(od.group(1)) : "";
        if (plan.length() > 160) plan = plan.substring(0, 160) + "…";
        boolean expired = EXPIRED.matcher(html).find();
        String[] d = inferDate(time, now);
        CompareDeal deal = new CompareDeal(SITE, title, price, priceText, mall, time, d[0], Integer.parseInt(d[1]), plan,
                expired ? "已过期" : "", articleUrl(url) != null ? articleUrl(url) : url, now, true, expired);
        return deal.usable() ? deal : null;
    }

    /**
     * 什么值得买搜索卡片（浏览器渲染抽出的一条）→ 爆料（没打开原页，checked=false）。价格 / 标题 / 商城 / 日期 / 条件 / 链接都是页面原文。
     */
    public static CompareDeal fromCard(com.onesay.assistant.v2.errand.web.PriceItem it, long now) {
        if (it == null || it.price <= 0 || it.title.length() == 0) return null;
        String[] d = inferDate(it.pageDate, now);
        String link = articleUrl(it.link) != null ? articleUrl(it.link) : it.link;
        CompareDeal deal = new CompareDeal(SITE, it.title, it.price, it.priceLabel() + (it.cond.length() > 0 ? "（" + it.cond + "）" : ""), it.mall,
                it.pageDate, d[0], Integer.parseInt(d[1]), "", "", link, it.fetchedAt > 0 ? it.fetchedAt : now, false, false);
        return deal.usable() ? deal : null;
    }

    private static final Pattern HM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern AGO = Pattern.compile("^(?:刚刚|\\d{1,2}\\s*(?:秒|分钟|小时)前)$");
    private static final Pattern YDAY = Pattern.compile("^昨天(?:\\s*\\d{1,2}:\\d{2})?$");

    /** 「09:53」「3小时前」「刚刚」= 今天，「昨天 21:10」= 昨天，其余（「10-20 09:56」「2025-12-30 15:03」）同慢慢买推断；推断不了 {"", "-1"}。 */
    static String[] inferDate(String time, long now) {
        String t = time == null ? "" : time.trim();
        int back = -1;
        if (HM.matcher(t).find() || AGO.matcher(t).find()) back = 0;
        else if (YDAY.matcher(t).find()) back = 1;
        if (back < 0) return ManmanbuyParser.inferDate(t, now);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.ROOT);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, -back);
        return new String[]{String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)),
                String.valueOf(back)};
    }
}
