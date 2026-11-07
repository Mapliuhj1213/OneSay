package com.onesay.assistant.v2.errand.compare;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 15:53）：慢慢买好价爆料页的静态解析（纯 Java，JVM 可测）。
 * <p>11-03 沙箱探测：电脑版 cu.manmanbuy.com/discuxiao_&lt;id&gt;.aspx、zhekou.manmanbuy.com/tag/… 从机房 IP 访问都跳
 * alival.manmanbuy.com 阿里云滑块校验；手机版 <b>m.manmanbuy.com/zhekou.aspx?id=&lt;同一个 id&gt;</b> 桌面 UA 直连 200、服务端渲染，
 * 有标题、实付价、商城（京东自营 / 天猫旗舰店…）、爆料时间（「04-30 18:42」，不带年份）、购买方案原文（原价、券、满减、补贴、实付）、
 * 标签（历史新低）。所以电脑版链接一律改写成手机版同 id 再读。站内搜索 s.manmanbuy.com 在沙箱连不上（代理 502），
 * 历史价 tool.manmanbuy.com/api.ashx 要混淆脚本签名（直连回「无效的票据」）+ 可能要微信登录 → 不用。</p>
 */
public final class ManmanbuyParser {

    private ManmanbuyParser() {}

    public static final String SITE = "慢慢买";

    private static final Pattern ID_PC = Pattern.compile("manmanbuy\\.com/discuxiao_(\\d{5,12})\\.aspx", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_Q = Pattern.compile("manmanbuy\\.com/(?:discuxiao0531|zhekou)\\.aspx\\?id=(\\d{5,12})", Pattern.CASE_INSENSITIVE);

    /** 慢慢买爆料链接（电脑版 / 手机版）→ 手机版同 id 链接；不是爆料页返回 null。 */
    public static String mobileUrl(String url) {
        if (url == null) return null;
        Matcher m = ID_PC.matcher(url);
        if (m.find()) return "https://m.manmanbuy.com/zhekou.aspx?id=" + m.group(1);
        m = ID_Q.matcher(url);
        if (m.find()) return "https://m.manmanbuy.com/zhekou.aspx?id=" + m.group(1);
        return null;
    }

    private static final Pattern TITLE = Pattern.compile("(?s)<h3 class=\"title\">(.*?)</h3>\\s*<h4>(.*?)</h4>");
    private static final Pattern MALL = Pattern.compile("(?s)<span class=\"mall\">(.*?)</span>");
    private static final Pattern TIME = Pattern.compile("(?s)<span class=\"addtime\"[^>]*>(.*?)</span>");
    private static final Pattern CONN = Pattern.compile("(?s)<div class=\"conn\">(.*?)</div>");
    private static final Pattern TAGS = Pattern.compile("标签：\\s*([^<]*(?:<a[^>]*>[^<]*</a>[^<]*)*)");
    private static final Pattern PLAN_START = Pattern.compile("该商品|此款商品|此商品|商品原价|活动售价|原价\\d");
    private static final Pattern MD = Pattern.compile("(?:(20\\d{2})-)?(\\d{1,2})-(\\d{1,2})(?:\\s+\\d{1,2}:\\d{2})?");

    /** 解析手机版爆料页；不是爆料页 / 没价格返回 null。now = 抓取时刻（推断年份、算距今天数，按 Asia/Shanghai）。 */
    public static CompareDeal parseDeal(String html, String url, long now) {
        if (html == null) return null;
        Matcher t = TITLE.matcher(html);
        if (!t.find()) return null;
        String title = FenxiangParser.text(t.group(1));
        String priceText = FenxiangParser.text(t.group(2));
        double price = FenxiangParser.money(priceText);
        String mall = first(MALL, html);
        String time = first(TIME, html);
        String plan = "";
        Matcher c = CONN.matcher(html);
        if (c.find()) {
            // 正文前面是券 / 满减小标签（<p>满200减25</p>）和配图，购买方案那句（「该商品原价…实付…」）写全了，从它开始取
            String all = FenxiangParser.text(c.group(1));
            Matcher pm = PLAN_START.matcher(all);
            plan = pm.find() ? all.substring(pm.start()) : all;
            if (plan.length() > 160) plan = plan.substring(0, 160) + "…";
        }
        String tags = "";
        Matcher tg = TAGS.matcher(html);
        if (tg.find()) tags = FenxiangParser.text(tg.group(1)).replaceAll("\\s*[，,]\\s*", "、").replaceAll("[、\\s]+$", "");
        String[] d = inferDate(time, now);
        CompareDeal deal = new CompareDeal(SITE, title, price, priceText, mall, time, d[0], Integer.parseInt(d[1]), plan, tags, url, now);
        return deal.usable() ? deal : null;
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? FenxiangParser.text(m.group(1)) : "";
    }

    /**
     * 「04-30 18:42」→ {「2026-04-30」, 距今天数}：没写年份就取不晚于今天的最近一年；推断不了 → {"", "-1"}。
     */
    static String[] inferDate(String time, long now) {
        if (time == null) return new String[]{"", "-1"};
        Matcher m = MD.matcher(time.trim());
        if (!m.find()) return new String[]{"", "-1"};
        TimeZone tz = TimeZone.getTimeZone("Asia/Shanghai");
        Calendar today = Calendar.getInstance(tz, Locale.ROOT);
        today.setTimeInMillis(now);
        int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) return new String[]{"", "-1"};
        int year = m.group(1) != null ? Integer.parseInt(m.group(1)) : today.get(Calendar.YEAR);
        Calendar d = Calendar.getInstance(tz, Locale.ROOT);
        d.clear();
        d.set(year, month - 1, day);
        if (m.group(1) == null && d.getTimeInMillis() > now) d.set(year - 1, month - 1, day);
        long days = (now - d.getTimeInMillis()) / 86400000L;
        String iso = String.format(Locale.ROOT, "%04d-%02d-%02d", d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH));
        return new String[]{iso, String.valueOf(Math.max(0, days))};
    }
}
