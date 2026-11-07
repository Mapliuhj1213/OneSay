package com.onesay.assistant.v2.errand;

import java.util.List;

/**
 * ★ lane C3：把升级链结果排成「岛上结果卡 + 卡片全文」（纯 Java）。
 * 来源按 1、2、3 编号（和回答里的 [编号] 一致），用户可以说「打开第三个」。
 */
public final class ErrandReport {

    private ErrandReport() {}

    public static final int HEADLINE_MAX = 40;
    public static final int DETAIL_MAX = 60;
    public static final int SOURCES_SHOWN = 6;

    public static final class Card {
        public final boolean ok;
        public final String headline;
        public final String detail;
        public final String meta;
        /** 卡片全文（「查看」后看到的）。 */
        public final String full;

        Card(boolean ok, String headline, String detail, String meta, String full) {
            this.ok = ok;
            this.headline = headline;
            this.detail = detail;
            this.meta = meta;
            this.full = full;
        }
    }

    public static String tierLabel(ResearchLadder.Tier t) {
        if (t == null) return "";
        switch (t) {
            case SEARCH: return "搜索摘要";
            case EXTRACT: return "网页正文";
            case WEBVIEW: return "内置浏览器";
            case SAMSUNG: return "三星浏览器";
            case RENDER: return "浏览器取价";
            default: return t.name();
        }
    }

    public static Card card(ErrandIntent intent, ResearchLadder.Outcome out, boolean cancelled) {
        if (cancelled) return new Card(false, "已停止跑腿", null, "已停止", "跑腿已停止。");
        if (out == null) return new Card(false, "跑腿没完成", "出了点问题，请稍后再试", "失败", "跑腿没完成：出了点问题。");
        String meta = tierLabel(out.tier) + (out.sources.isEmpty() ? "" : " · " + out.sources.size() + " 个来源");
        String answer = out.answer.trim();
        StringBuilder full = new StringBuilder();
        if (answer.length() > 0) full.append(answer);
        if (out.note.length() > 0 && !out.ok) {
            if (full.length() > 0) full.append("\n\n");
            full.append(out.note);
        }
        appendSources(full, out.sources);
        if (full.length() == 0) full.append("没有拿到可用的资料。");

        String headline;
        String detail;
        if (out.browseStatus == BrowseResult.Status.ORDER_ALERT) {
            // 事后检测到「订单已提交 / 支付成功」—— 绝不说「没有提交订单」
            headline = "⚠ 订单可能已提交，请马上核对";
            detail = out.handoffUrl != null ? ResearchLadder.host(out.handoffUrl) : null;
            full.append("\n\n").append(com.onesay.assistant.v2.errand.web.CheckoutGuard.ALERT_ORDER_PLACED)
                    .append("。如不是你要的，请尽快在订单页取消或申请退款。");
        } else if (out.browseStatus == BrowseResult.Status.CHECKOUT) {
            headline = "已到结算页，付款请你来";
            detail = answer.length() > 0 ? clip(firstLine(answer), DETAIL_MAX) : ResearchLadder.host(out.handoffUrl);
            if (full.indexOf("没有提交订单") < 0) full.append("\n\n已停在结算页，没有提交订单、没有付款。");
        } else if (out.browseStatus == BrowseResult.Status.TAKEOVER) {
            headline = "停在了「" + (out.note.length() > 0 ? out.note : "需要你处理") + "」";
            detail = out.handoffUrl != null ? ResearchLadder.host(out.handoffUrl) : null;
        } else if (out.ok && answer.length() > 0) {
            String[] hb = splitHead(answer);
            headline = hb[0];
            detail = hb[1];
        } else if (out.handoffUrl != null) {
            headline = out.note.length() > 0 ? clip(out.note, HEADLINE_MAX) : "需要你接着处理";
            detail = ResearchLadder.host(out.handoffUrl);
        } else {
            headline = "没查到可用的资料";
            detail = out.note.length() > 0 ? clip(out.note, DETAIL_MAX) : null;
        }
        return new Card(out.ok, headline, detail, meta, full.toString());
    }

    static void appendSources(StringBuilder sb, List<ResearchLadder.Source> sources) {
        if (sources == null || sources.isEmpty()) return;
        sb.append("\n\n来源（可以说「打开第二个」）：");
        int n = Math.min(SOURCES_SHOWN, sources.size());
        for (int i = 0; i < n; i++) {
            ResearchLadder.Source s = sources.get(i);
            sb.append('\n').append(i + 1).append(". ")
                    .append(clip(s.title.length() > 0 ? s.title : ResearchLadder.host(s.url), 30));
            if (s.url.length() > 0) sb.append(" · ").append(ResearchLadder.host(s.url));
        }
    }

    /** 第一句当主句，剩下的第一行当次行。 */
    static String[] splitHead(String answer) {
        String a = answer.replaceAll("\\[\\d+\\]", "").trim();
        int cut = -1;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') { cut = i; break; }
        }
        String head = cut > 0 ? a.substring(0, cut) : a;
        String rest = cut > 0 ? a.substring(cut + 1).trim() : "";
        rest = rest.replaceAll("^[-•·\\s]+", "");
        int nl = rest.indexOf('\n');
        if (nl > 0) rest = rest.substring(0, nl);
        return new String[] {clip(head, HEADLINE_MAX), rest.length() == 0 ? null : clip(rest, DETAIL_MAX)};
    }

    /** 结算页交接时岛上确认卡的标题。 */
    public static String checkoutPrompt(ResearchLadder.Outcome out) {
        String a = out == null ? "" : out.answer.trim();
        return a.length() > 0 ? "已到结算页：" + clip(firstLine(a), 24) : CHECKOUT_TITLE;
    }

    public static final String CHECKOUT_TITLE = "已到结算页，去付款？";

    static String firstLine(String s) {
        String t = s.replaceAll("\\[\\d+\\]", "").trim();
        int k = t.indexOf('\n');
        return k > 0 ? t.substring(0, k) : t;
    }

    static String clip(String s, int n) {
        return ResearchLadder.clip(s, n);
    }
}
