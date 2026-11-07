package com.onesay.assistant.v2.errand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ lane C3：一次浏览器跑腿（内置 WebView 代理）的结果（不可变，纯 Java）。
 */
public final class BrowseResult {

    public enum Status {
        /** 拿到了答案 / 资料。 */
        DONE,
        /** 遇到登录 / 验证码 / 人机校验：要用户在岛上点「接手」后自己处理。 */
        TAKEOVER,
        /** 购物：已到结算页（付款前）停下，交给用户自己付。 */
        CHECKOUT,
        /** 某次点击后页面出现「订单已提交 / 支付成功」特征 —— 立即停下并告警（不是正常结算页停下）。 */
        ORDER_ALERT,
        /** 步数 / 时长到上限，带着已完成的部分返回。 */
        LIMIT,
        /** 页面打不开 / WebView 被站点拒绝（→ 升级链可退到三星浏览器）。 */
        BLOCKED,
        /** 其他失败（模型不可用、用户停止等）。 */
        FAILED
    }

    public final Status status;
    /** 给用户的结论（DONE / LIMIT 时是模型从页面里整理的答案或已完成到哪一步）。 */
    public final String answer;
    /** 停下时所在页面（TAKEOVER / CHECKOUT / BLOCKED 用它交还给用户）。 */
    public final String url;
    /** 原因（TAKEOVER：「需要登录」；BLOCKED：「站点拒绝 WebView」…）。 */
    public final String reason;
    /** 访问过、可作为来源的页面（按访问顺序，编号从 1 开始给用户看）。 */
    public final List<String[]> sources;   // {title, url}
    public final int steps;

    public BrowseResult(Status status, String answer, String url, String reason, List<String[]> sources, int steps) {
        this.status = status;
        this.answer = answer == null ? "" : answer;
        this.url = url;
        this.reason = reason == null ? "" : reason;
        this.sources = sources == null ? Collections.<String[]>emptyList() : Collections.unmodifiableList(new ArrayList<String[]>(sources));
        this.steps = steps;
    }

    public static BrowseResult failed(String reason) {
        return new BrowseResult(Status.FAILED, null, null, reason, null, 0);
    }

    public static BrowseResult blocked(String url, String reason) {
        return new BrowseResult(Status.BLOCKED, null, url, reason, null, 0);
    }

    @Override
    public String toString() {
        return status + "{steps=" + steps + (url != null ? " url=" + url : "") + (reason.length() > 0 ? " reason=" + reason : "")
                + " answer=" + answer.length() + "字}";
    }
}
