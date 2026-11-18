/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/action/RiskJudge.kt (ClassifiedRisk) and the guard → risk → consent order of
 * core/action/ActionExecutor.kt translated to Java 7; Mika's model-based irreversible gate is replaced by a
 * keyword detector over the target element (name / resource id), Chinese + English. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.action.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 动作守卫：判一个动作的风险档（读 / 改界面 / 对外 / 不可逆 / 不明）和花不花钱，决定要不要先问用户。
 * 对外（发送、提交）与不可逆（付款、下单、删除）一律要用户同意；屏上有带币种的金额时提交 / 删除按花钱算。
 */
public final class RiskGate {
    private RiskGate() {
    }

    public enum Level { READ, CHANGE, EXTERNAL, IRREVERSIBLE, UNKNOWN }

    public enum Irrev { SEND, SUBMIT, PAY, ORDER, DELETE }

    public static final class Risk {
        public final Level level;
        public final Irrev hit;
        public final boolean payment;

        Risk(Level l, Irrev hit, boolean payment) {
            this.level = l;
            this.hit = hit;
            this.payment = payment;
        }

        public boolean needsConsent() {
            return level == Level.EXTERNAL || level == Level.IRREVERSIBLE || payment;
        }

        public String label() {
            if (hit == null) return level == Level.CHANGE ? "改动" : "读取";
            switch (hit) {
                case SEND: return "发送";
                case SUBMIT: return "提交";
                case PAY: return "付款";
                case ORDER: return "下单";
                default: return "删除";
            }
        }
    }

    /** 动作是否改界面上的东西（点、输入、清空、按键）；返回 / 滑动 / 打开 App 只是移动。 */
    public static boolean changesState(String verb) {
        return "tap".equals(verb) || "type".equals(verb) || "clear".equals(verb) || "key".equals(verb) || "long".equals(verb);
    }

    private static final Pattern AMOUNT = Pattern.compile("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元");
    // ★ rel-1118 B05：补转账 / 充值 / 发红包 / 塞钱 / 打款（PAY），购买 / 抢购 / 订购（ORDER），解绑 / 退出群聊 / 删好友（DELETE），
    //   分享 / 上传 / 发朋友圈 / 拨打 / 呼叫（SEND：对外，拨号也算对外）。匹配前先 {@link #fold} 归一化。
    private static final Pattern PAY = Pattern.compile("(?i)(立即支付|确认支付|去支付|付款|支付|买单|pay\\b|pay_|checkout|收银台|确认付款|免密支付|指纹支付"
            + "|转账|充值|发红包|塞钱|打款|付钱|提现|transfer\\b|top.?up|recharge)");
    private static final Pattern ORDER = Pattern.compile("(?i)(提交订单|立即下单|确认下单|下单|立即购买|购买|抢购|订购|买入|去结算|结算|place.?order|buy.?now|\\bbuy\\b|purchase)");
    private static final Pattern DELETE = Pattern.compile("(?i)(删除|清空|注销|卸载|移除|撤回|解绑|解除绑定|退出群聊|退群|删好友|delete|remove|uninstall|unbind)");
    private static final Pattern SEND = Pattern.compile("(?i)(发送|^发$|发布|发表|转发|评论|回复|分享|上传|发朋友圈|发动态|拨打|呼叫|打电话|send\\b|send_|post\\b|publish|reply|share|upload|\\bcall\\b)");
    private static final Pattern SUBMIT = Pattern.compile("(?i)(提交|投递|申请|报名|立即预约|submit|apply)");   // 「确认 / 确定 / 同意」单独出现太常见（普通弹窗），不算

    /**
     * ★ sec-audit-1118 H3（严格）：开通 / 续费 / 订阅 / 确认收货 / 签署 / 授权 / 一键购买这类按完就生效的按钮。
     * 在 {@link com.onesay.assistant.v2.security.SafetyText#squash} 之后的<b>整串</b>上找（不截 24 字、不因为长就当导航），
     * 西里尔 / 希腊同形字、U+034F、零宽字符、全角、繁体、空格和分隔标点都先折掉。
     * 「订阅号」（微信的入口）不算订阅。
     */
    private static final Pattern STRICT_PAY = Pattern.compile("立即付|自动续费|确认收货|agreeandpay|agreetopay");
    private static final Pattern STRICT_ORDER = Pattern.compile("立即开通|订阅(?!号)|subscribe|placeyourorder|buywith1click|buywithoneclick|1clickbuy");
    private static final Pattern STRICT_SUBMIT = Pattern.compile("签署|同意授权|确认授权");
    /** 只有「订阅 / subscribe」这一档（入口条目里也常见，如「订阅管理」）：只取消「超长当导航」，不取消名词收尾的导航判定。 */
    private static final Pattern STRICT_SOFT = Pattern.compile("订阅(?!号)|subscribe");
    static final int STRICT_SCAN_MAX = 200;

    /** 严格词表命中哪一类（null = 没命中）。 */
    public static Irrev strictIrreversible(String label) {
        if (label == null) return null;
        String t = com.onesay.assistant.v2.security.SafetyText.squash(label);
        if (t.length() == 0) return null;
        if (t.length() > STRICT_SCAN_MAX) t = t.substring(0, STRICT_SCAN_MAX);
        if (STRICT_PAY.matcher(t).find()) return Irrev.PAY;
        if (STRICT_ORDER.matcher(t).find()) return Irrev.ORDER;
        if (STRICT_SUBMIT.matcher(t).find()) return Irrev.SUBMIT;
        return null;
    }

    /** 命中的只是「订阅 / subscribe」这一软档吗（没有别的严格词）。 */
    public static boolean strictSoftOnly(String label) {
        if (label == null) return false;
        String t = com.onesay.assistant.v2.security.SafetyText.squash(label);
        if (t.length() > STRICT_SCAN_MAX) t = t.substring(0, STRICT_SCAN_MAX);
        return STRICT_SOFT.matcher(t).find() && !STRICT_PAY.matcher(t).find() && !STRICT_SUBMIT.matcher(t).find()
                && !STRICT_ORDER.matcher(STRICT_SOFT.matcher(t).replaceAll("")).find();
    }

    /** 按目标元素的名字 / 资源 id 认不可逆动作（只对改界面的点 / 回车）。 */
    public static Irrev detect(String verb, String name, String resourceId, boolean editable) {
        if (!"tap".equals(verb) && !"key".equals(verb) && !"long".equals(verb)) return null;
        if (editable) return null;
        Irrev strict = strictIrreversible(name);   // ★ sec-audit-1118 H3：整串先判，长按钮文字不再截掉后半句
        if (strict == null) strict = strictIrreversible(resourceId);
        if (strict != null) return strict;
        String s = (fold(name, true) + " " + fold(resourceId, true)).trim().toLowerCase(Locale.ROOT);
        if (s.length() == 0) return null;
        if (s.length() > 24) s = s.substring(0, 24);   // 长段落不是按钮（误伤正文）
        Irrev a = match(s);
        if (a != null) return a;
        // ★ rel-1118 B05：名字单独再判一次（「发」+ 资源 id 时 ^发$ 认不出），再判去空白的紧凑形（「支 付」「删 除」）
        String[] more = { fold(name, true), fold(name, false), fold(resourceId, false) };
        for (String m : more) {
            String t = m.trim().toLowerCase(Locale.ROOT);
            if (t.length() == 0 || t.equals(s)) continue;
            if (t.length() > 24) t = t.substring(0, 24);
            a = match(t);
            if (a != null) return a;
        }
        return null;
    }

    private static Irrev match(String s) {
        if (PAY.matcher(s).find()) return Irrev.PAY;
        if (ORDER.matcher(s).find()) return Irrev.ORDER;
        if (DELETE.matcher(s).find()) return Irrev.DELETE;
        if (SEND.matcher(s).find()) return Irrev.SEND;
        if (SUBMIT.matcher(s).find()) return Irrev.SUBMIT;
        return null;
    }

    /** 常见繁体 → 简体（只收按钮上会出现的字）。 */
    private static final String TRAD = "刪發轉帳賬購買訂單結確認註銷綁傳號撥預約報評論復紅錢儲錄聯繫將應並開關";
    private static final String SIMP = "删发转账账购买订单结确认注销绑传号拨预约报评论复红钱储录联系将应并开关";

    /**
     * ★ rel-1118 B05：按钮文字归一化 —— NFKC（全角 ＰＡＹ → pay）、去掉零宽 / 格式字符（「付\u200b款」；sec-audit-1118 H3 起含 U+034F，并折西里尔 / 希腊同形字），keepSpace=false 时连空白也去掉（「支 付」）、
     * 常见繁体折成简体（「刪除」「發送」）。资源 id 也走一遍（无害）。
     */
    static String fold(String in, boolean keepSpace) {
        if (in == null) return "";
        String s = java.text.Normalizer.normalize(in, java.text.Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (com.onesay.assistant.v2.security.SafetyText.isInvisible(c)) continue;   // ★ sec-audit-1118 H3：含 U+034F / 变体选择符 / 韩文填充符
            c = com.onesay.assistant.v2.security.SafetyText.homo(c);                     // ★ sec-audit-1118 H3：西里尔 / 希腊同形字（Рау → Pay）
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (keepSpace && b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                continue;
            }
            int k = TRAD.indexOf(c);
            b.append(k >= 0 ? SIMP.charAt(k) : c);
        }
        return b.toString();
    }

    public static Risk assess(String verb, Irrev hit, List<String> screenTexts) {
        Level level;
        if (hit == null) level = changesState(verb) ? Level.CHANGE : Level.READ;
        else if (hit == Irrev.SEND || hit == Irrev.SUBMIT) level = Level.EXTERNAL;
        else level = Level.IRREVERSIBLE;
        if (hit == null) return new Risk(level, null, false);
        boolean payment = hit == Irrev.PAY || hit == Irrev.ORDER;
        if (!payment && hit != Irrev.SEND && screenTexts != null) {
            for (String t : screenTexts) if (t != null && AMOUNT.matcher(t).find()) {
                payment = true;
                break;
            }
        }
        return new Risk(level, hit, payment);
    }
}
