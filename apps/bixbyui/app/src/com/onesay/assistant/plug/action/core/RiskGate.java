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
    private static final Pattern PAY = Pattern.compile("(?i)(立即支付|确认支付|去支付|付款|支付\\s*¥?|买单|pay\\b|pay_|checkout|收银台|确认付款|免密支付|指纹支付)");
    private static final Pattern ORDER = Pattern.compile("(?i)(提交订单|立即下单|确认下单|下单|立即购买|去结算|结算|place.?order|buy.?now)");
    private static final Pattern DELETE = Pattern.compile("(?i)(删除|清空|注销|卸载|移除|撤回|delete|remove|uninstall)");
    private static final Pattern SEND = Pattern.compile("(?i)(发送|^发$|发布|发表|转发|评论|回复|send\\b|send_|post\\b|publish|reply)");
    private static final Pattern SUBMIT = Pattern.compile("(?i)(提交|投递|申请|报名|立即预约|submit|apply)");   // 「确认 / 确定 / 同意」单独出现太常见（普通弹窗），不算

    /** 按目标元素的名字 / 资源 id 认不可逆动作（只对改界面的点 / 回车）。 */
    public static Irrev detect(String verb, String name, String resourceId, boolean editable) {
        if (!"tap".equals(verb) && !"key".equals(verb) && !"long".equals(verb)) return null;
        if (editable) return null;
        String s = ((name == null ? "" : name) + " " + (resourceId == null ? "" : resourceId)).trim().toLowerCase(Locale.ROOT);
        if (s.length() == 0) return null;
        if (s.length() > 24) s = s.substring(0, 24);   // 长段落不是按钮（误伤正文）
        if (PAY.matcher(s).find()) return Irrev.PAY;
        if (ORDER.matcher(s).find()) return Irrev.ORDER;
        if (DELETE.matcher(s).find()) return Irrev.DELETE;
        if (SEND.matcher(s).find()) return Irrev.SEND;
        if (SUBMIT.matcher(s).find()) return Irrev.SUBMIT;
        return null;
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
