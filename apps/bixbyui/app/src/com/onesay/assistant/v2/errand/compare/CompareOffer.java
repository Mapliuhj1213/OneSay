package com.onesay.assistant.v2.errand.compare;

/**
 * ★ fix/shop-research（11-03 15:53）：比价网上的一条在售报价（某平台某店铺的券后价）。纯数据，代码从页面里取，不经模型。
 */
public final class CompareOffer {
    /** 京东 / 天猫 / 淘宝 / 拼多多…（比价网原文）。 */
    public final String platform;
    /** 店铺名原文（「雷蛇（Razer）京东自营旗舰店」）。 */
    public final String shop;
    /** 比价网标了「官方」。 */
    public final boolean official;
    /** 券后价（元）。 */
    public final double price;
    /** 价格原文（「¥439.12」）。 */
    public final String priceText;
    /** 报价更新日期原文（「11/03」）。 */
    public final String updated;

    public CompareOffer(String platform, String shop, boolean official, double price, String priceText, String updated) {
        this.platform = platform == null ? "" : platform.trim();
        this.shop = shop == null ? "" : shop.trim();
        this.official = official;
        this.price = price;
        this.priceText = priceText == null ? "" : priceText.trim();
        this.updated = updated == null ? "" : updated.trim();
    }

    /** 自营：店铺名带「自营」（京东自营旗舰店 / 天猫国际自营 / 天猫数码家电自营旗舰店）。 */
    public boolean selfRun() {
        return shop.contains("自营");
    }

    /** 店铺类型：自营 / 官方旗舰 / 旗舰 / 专卖 / 小店（看不出为空串）。 */
    public String kind() {
        if (selfRun()) return "自营";
        if (shop.contains("旗舰")) return official ? "官方旗舰" : "旗舰";
        if (shop.contains("专卖")) return "专卖店";
        if (official) return "官方";
        if (shop.contains("小店") || shop.contains("百货") || shop.contains("买手") || shop.contains("拼购")) return "小店";
        return "";
    }

    @Override public String toString() {
        String k = kind();
        return platform + " " + shop + (k.length() > 0 ? "（" + k + "）" : "") + " " + priceText + (updated.length() > 0 ? "（" + updated + " 更新）" : "");
    }
}
