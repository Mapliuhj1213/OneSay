package com.onesay.assistant.v2.errand.compare;

/**
 * ★ fix/shop-research（11-03 15:53）：比价网的一条好价爆料 / 购买方案（慢慢买等）：某商城某天的实付价 + 怎么凑（券 / 满减 / 补贴）。
 * 爆料有时效：{@link #ageDays} 超过 {@link #STALE_DAYS} 天标「过期爆料」，只作参考，不当现价。
 */
public final class CompareDeal {
    public static final int STALE_DAYS = 14;

    public final String site;
    public final String title;
    public final String variant;
    /** 实付价（元）；解析不了为 -1。 */
    public final double price;
    /** 价格原文（「535.73元+3.52元淘金币」）。 */
    public final String priceText;
    /** 商城原文（「京东自营」「天猫旗舰店」「京东商城」）。 */
    public final String mall;
    /** 爆料时间原文（「04-30 18:42」）。 */
    public final String time;
    /** 推断出的日期（yyyy-MM-dd；推断不了为空串）。 */
    public final String date;
    /** 距今天数；不知道为 -1。 */
    public final int ageDays;
    /** 购买方案原文（「该商品原价299元，使用（满减：满200减25…），下单1件，实付254元。」）。 */
    public final String plan;
    /** 标签（「历史新低」）；没有为空串。 */
    public final String tags;
    public final String url;
    public final long fetchedAt;
    /** ★ 11-03 23:4x：打开过爆料原页核对（什么值得买 /p/ 页）；搜索卡片 / 网页搜索摘要来的为 false。 */
    public final boolean checked;
    /** 爆料原页写了「价格可能已过期 / 优惠已错过 / 已售罄」。 */
    public final boolean expired;

    public CompareDeal(String site, String title, double price, String priceText, String mall, String time, String date, int ageDays,
                       String plan, String tags, String url, long fetchedAt) {
        this(site, title, price, priceText, mall, time, date, ageDays, plan, tags, url, fetchedAt, false, false);
    }

    public CompareDeal(String site, String title, double price, String priceText, String mall, String time, String date, int ageDays,
                       String plan, String tags, String url, long fetchedAt, boolean checked, boolean expired) {
        this.site = CompareProduct.nz(site);
        this.title = CompareProduct.nz(title);
        this.variant = ShopVariant.of(this.title);
        this.price = price;
        this.priceText = CompareProduct.nz(priceText);
        this.mall = CompareProduct.nz(mall);
        this.time = CompareProduct.nz(time);
        this.date = CompareProduct.nz(date);
        this.ageDays = ageDays;
        this.plan = CompareProduct.nz(plan);
        this.tags = CompareProduct.nz(tags);
        this.url = CompareProduct.nz(url);
        this.fetchedAt = fetchedAt;
        this.checked = checked;
        this.expired = expired;
    }

    public boolean stale() {
        return expired || ageDays < 0 || ageDays > STALE_DAYS;
    }

    public boolean usable() {
        return title.length() > 0 && price > 0;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(site).append(' ').append(mall.length() > 0 ? mall + " " : "").append(priceText);
        sb.append(" · ").append(date.length() > 0 ? date : time.length() > 0 ? time : "日期未标");
        if (expired) sb.append("（爆料原页显示已过期 / 售罄，仅参考）");
        else if (stale()) sb.append("（过期爆料，仅参考）");
        else if (checked) sb.append("（已打开爆料原页核对）");
        if (tags.length() > 0) sb.append(" · ").append(tags);
        if (ShopVariant.noAccessory(title)) sb.append(" · 无配件版");
        if (ShopVariant.used(title)) sb.append(" · 二手/翻新");
        return sb.toString();
    }
}
