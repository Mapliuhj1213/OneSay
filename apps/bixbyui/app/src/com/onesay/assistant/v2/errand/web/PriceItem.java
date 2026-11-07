package com.onesay.assistant.v2.errand.web;

/**
 * ★ fix/shop-research（11-03 14:37）：{@link BrowserReader} 从渲染后的页面里抽出的一条商品价格（纯 Java，JVM 可测）。
 * 价格、店铺、自营 / 旗舰、标题、链接、抓取时间都是代码从页面节点里取的，不经模型。
 */
public final class PriceItem {

    /** 价格（元）；解析不了为 -1。 */
    public final double price;
    /** 页面上的价格原文（「￥439.12」）。 */
    public final String priceText;
    public final String title;
    /** 店铺名（「京东游戏外设旗舰店」）；没读到为空串。 */
    public final String shop;
    /** 商城（比价站条目里写的「京东」「天猫」；平台页为空串）。 */
    public final String mall;
    public final boolean selfOperated;
    public final boolean flagship;
    public final String link;
    /** 页面里写的日期（比价站爆料时间「11-03 10:20」「3小时前」）；没写为空串。 */
    public final String pageDate;
    /** 券后 / 补贴 / 会员等条件（没写为空串）。 */
    public final String cond;
    /** 抓取时间（毫秒）。 */
    public final long fetchedAt;

    public PriceItem(double price, String priceText, String title, String shop, String mall, boolean selfOperated, boolean flagship,
                     String link, String pageDate, String cond, long fetchedAt) {
        this.price = price;
        this.priceText = nz(priceText);
        this.title = nz(title);
        this.shop = nz(shop);
        this.mall = nz(mall);
        this.selfOperated = selfOperated;
        this.flagship = flagship;
        this.link = nz(link);
        this.pageDate = nz(pageDate);
        this.cond = nz(cond);
        this.fetchedAt = fetchedAt;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 「自营」/「旗舰」/「自营·旗舰」/ 空串。 */
    public String shopKind() {
        if (selfOperated && flagship) return "自营·旗舰";
        if (selfOperated) return "自营";
        if (flagship) return "旗舰";
        return "";
    }

    /** 价格写法统一成「¥439.12」。 */
    public String priceLabel() {
        if (price < 0) return priceText;
        if (price == Math.floor(price)) return "¥" + (long) price;
        return "¥" + String.format(java.util.Locale.ROOT, "%.2f", price);
    }

    /** 一行：标题 | ¥价格 | 店铺（类型）| 商城 | 日期 | 条件 | 链接。 */
    public String line() {
        StringBuilder sb = new StringBuilder();
        sb.append(title.length() > 0 ? title : "（无标题）").append(" | ").append(priceLabel());
        if (shop.length() > 0) sb.append(" | 店铺 ").append(shop).append(shopKind().length() > 0 ? "（" + shopKind() + "）" : "");
        else if (shopKind().length() > 0) sb.append(" | ").append(shopKind());
        if (mall.length() > 0) sb.append(" | ").append(mall);
        if (pageDate.length() > 0) sb.append(" | ").append(pageDate);
        if (cond.length() > 0) sb.append(" | ").append(cond);
        if (link.length() > 0) sb.append(" | ").append(link);
        return sb.toString();
    }

    @Override public String toString() {
        return line();
    }
}
