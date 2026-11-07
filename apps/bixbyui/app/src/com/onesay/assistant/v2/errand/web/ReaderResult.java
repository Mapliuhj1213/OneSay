package com.onesay.assistant.v2.errand.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ fix/shop-research（11-03 14:37）：{@link BrowserReader} 读一页的结果（纯 Java）：正文文本 + 结构化价格 + 状态。
 */
public final class ReaderResult {

    public enum Status {
        /** 读到了（items 可能为空 = 页面里没价格）。 */
        OK,
        /** 要登录才出结果 → 跳过，不登录。 */
        LOGIN,
        /** 要验证码 / 滑块 → 跳过。 */
        CAPTCHA,
        /** 12 s 内没读完。 */
        TIMEOUT,
        /** 网络错误 / 证书错误 / HTTP ≥ 400 / 地址不是 https 网页。 */
        BLOCKED,
        /** 另一个读取正在进行（串行）。 */
        BUSY,
        /** 内部错误（WebView 建不起来、在主线程调用、脚本没返回）。 */
        ERROR
    }

    public final Status status;
    public final String url;
    public final String finalUrl;
    public final String title;
    /** 正文（已剔掉「下载 App」浮层文字）。 */
    public final String text;
    public final List<PriceItem> items;
    /** 拦下的唤起 App 跳转次数。 */
    public final int blockedSchemes;
    /** 剔掉的浮层条数。 */
    public final int overlays;
    public final long elapsedMs;
    public final long fetchedAt;
    /** 原因 / 说明（BLOCKED 的错误、跳过原因）。 */
    public final String note;

    public ReaderResult(Status status, String url, String finalUrl, String title, String text, List<PriceItem> items,
                        int blockedSchemes, int overlays, long elapsedMs, long fetchedAt, String note) {
        this.status = status;
        this.url = url == null ? "" : url;
        this.finalUrl = finalUrl == null || finalUrl.length() == 0 ? this.url : finalUrl;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.items = items == null ? Collections.<PriceItem>emptyList() : Collections.unmodifiableList(new ArrayList<PriceItem>(items));
        this.blockedSchemes = blockedSchemes;
        this.overlays = overlays;
        this.elapsedMs = elapsedMs;
        this.fetchedAt = fetchedAt;
        this.note = note == null ? "" : note;
    }

    public static ReaderResult fail(Status st, String url, long elapsedMs, String note) {
        return new ReaderResult(st, url, url, "", "", null, 0, 0, elapsedMs, System.currentTimeMillis(), note);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** 要登录 / 要验证 = 「跳过并注明」。 */
    public boolean skipped() {
        return status == Status.LOGIN || status == Status.CAPTCHA;
    }

    /** 换一组价格（相关性过滤之后）。 */
    public ReaderResult withItems(List<PriceItem> l) {
        return new ReaderResult(status, url, finalUrl, title, text, l, blockedSchemes, overlays, elapsedMs, fetchedAt, note);
    }

    /**
     * 给模型 / 兜底结论的文字：先列结构化价格（每行 标题 | 价格 | 店铺（类型）| 商城 | 日期 | 条件 | 链接），再接正文。
     * 跳过的页给一句说明（短，verdict 会判成 needs_sign_in / refused）。
     */
    public String asText(int textMax) {
        if (status == Status.LOGIN) return "页面要求登录才出结果，已跳过（没有尝试登录）。";
        if (status == Status.CAPTCHA) return "页面要安全验证（验证码 / 滑块），已跳过。";
        StringBuilder sb = new StringBuilder();
        if (!items.isEmpty()) {
            sb.append("【渲染后页面里的价格（抓取于 ").append(ReaderParser.stamp(fetchedAt)).append("，以下单页为准）】\n");
            for (PriceItem p : items) sb.append("- ").append(p.line()).append('\n');
            sb.append('\n');
        }
        String t = text;
        int room = Math.max(0, textMax - sb.length());
        if (t.length() > room) t = t.substring(0, room) + "…";
        sb.append(t);
        return sb.toString().trim();
    }

    @Override public String toString() {
        return status + " " + ShopSearchUrls.siteName(finalUrl) + " items=" + items.size() + " text=" + text.length() + " ms=" + elapsedMs
                + (blockedSchemes > 0 ? " blockedSchemes=" + blockedSchemes : "") + (overlays > 0 ? " overlays=" + overlays : "")
                + (note.length() > 0 ? " note=" + note : "");
    }
}
