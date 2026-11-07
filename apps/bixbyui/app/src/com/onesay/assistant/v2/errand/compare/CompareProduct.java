package com.onesay.assistant.v2.errand.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ fix/shop-research（11-03 15:53）：比价网的一个商品页（一个 SKU 族）：各平台各店铺券后价 + 历史最低 + 走势。
 * 字段全部是页面原文解析出来的；缺的字段为空串 / -1，不猜。
 */
public final class CompareProduct {
    /** 比价网名（「粉象比价」）。 */
    public final String site;
    public final String title;
    /** {@link ShopVariant#of} 的版本标签。 */
    public final String variant;
    /** 报价表对应的规格（「白色」）。 */
    public final String spec;
    public final List<CompareOffer> offers;
    public final double currentLow;
    public final String currentLowText;
    public final double histLow;
    public final String histLowText;
    public final String histLowDate;
    public final String low30Text;
    public final String low180Text;
    /** 走势一句话（「30 天前 ¥527.12 → 现在 ¥439.12，下降 16.7%」）；没有为空串。 */
    public final String trend;
    /** 每月最低：{月份, 价格原文, 日期, 平台}。 */
    public final List<String[]> monthly;
    /** 大促价：{大促, 价格原文, 日期}。 */
    public final List<String[]> promos;
    /** 数据截至（「2026-11-03 04:42」）。 */
    public final String asOf;
    /** 比价网给的建议（「可以入手」「建议观望」）。 */
    public final String verdict;
    public final String url;
    public final long fetchedAt;

    public CompareProduct(String site, String title, String spec, List<CompareOffer> offers, double currentLow, String currentLowText,
                          double histLow, String histLowText, String histLowDate, String low30Text, String low180Text, String trend,
                          List<String[]> monthly, List<String[]> promos, String asOf, String verdict, String url, long fetchedAt) {
        this.site = nz(site);
        this.title = nz(title);
        this.variant = ShopVariant.of(this.title);
        this.spec = nz(spec);
        this.offers = offers == null ? Collections.<CompareOffer>emptyList() : Collections.unmodifiableList(new ArrayList<CompareOffer>(offers));
        this.currentLow = currentLow;
        this.currentLowText = nz(currentLowText);
        this.histLow = histLow;
        this.histLowText = nz(histLowText);
        this.histLowDate = nz(histLowDate);
        this.low30Text = nz(low30Text);
        this.low180Text = nz(low180Text);
        this.trend = nz(trend);
        this.monthly = monthly == null ? Collections.<String[]>emptyList() : monthly;
        this.promos = promos == null ? Collections.<String[]>emptyList() : promos;
        this.asOf = nz(asOf);
        this.verdict = nz(verdict);
        this.url = nz(url);
        this.fetchedAt = fetchedAt;
    }

    static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 现价不超过历史最低的 103%（比价网「接近史低」口径）。 */
    public boolean nearHistLow() {
        return currentLow > 0 && histLow > 0 && currentLow <= histLow * 1.03 + 0.005;
    }

    /** 最低的自营报价（没有为 null）。 */
    public CompareOffer cheapestSelfRun() {
        CompareOffer best = null;
        for (CompareOffer o : offers) if (o.selfRun() && (best == null || o.price < best.price)) best = o;
        return best;
    }

    /** 某平台最低报价（没有为 null）。 */
    public CompareOffer cheapestOn(String platform) {
        CompareOffer best = null;
        for (CompareOffer o : offers) if (o.platform.startsWith(platform) && (best == null || o.price < best.price)) best = o;
        return best;
    }

    /** 最低报价（没有为 null）。 */
    public CompareOffer cheapest() {
        CompareOffer best = null;
        for (CompareOffer o : offers) if (best == null || o.price < best.price) best = o;
        return best;
    }

    public boolean usable() {
        return title.length() > 0 && (currentLow > 0 || !offers.isEmpty());
    }
}
