package com.onesay.assistant.v2.s2;

import com.onesay.assistant.v2.s1.ClipChipPlanner;

import java.util.regex.Pattern;

/**
 * [s2] 记忆库写入前的敏感内容闸门（纯 Java）：<b>密码、验证码、银行卡号、身份证/证件号、密钥一律不存</b>，并向用户解释原因。
 *
 * <p>复用已有的两层判定，不另起一套口径：
 * <ol>
 *   <li>识屏的 {@code UiDumpParser.maskSecrets}（Luhn 校验的银行卡号、带出生日期校验的 18 位身份证号）——
 *       通过 S1 剪贴板芯片的 {@link ClipChipPlanner#sensitiveReason} 间接复用（同一套：验证码词、密钥前缀、密码类词、
 *       纯 4–8 位数字、形似密码）；</li>
 *   <li>本类追加「证件/卡号 + 一串数字」规则：用户口述的卡号常常不过 Luhn（念错一位、只念一部分），
 *       但「我的身份证号是 4401…」「银行卡号 6222…」显然不该存。没有数字的「护照放在书房抽屉」照常可记。</li>
 * </ol>
 * 只返回原因，不回显内容；调用方日志只打长度。
 */
public final class SensitiveGuard {

    private SensitiveGuard() {}

    private static final Pattern ID_WORDS = Pattern.compile(
            "身份证|证件号|护照号|社保号|社保卡号|医保卡号|驾驶证号|银行卡|信用卡|储蓄卡|借记卡|卡号|账号|帐号|开户");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d[\\d\\s-]{4,}\\d");
    private static final Pattern PASSWORD_LIKE = Pattern.compile(
            "密码|口令|支付密码|开机密码|锁屏密码|pin码|PIN码|验证码|校验码|动态码|cvv|CVV|安全码");

    /** @return 敏感原因（给用户看的短语）；不敏感返回 null */
    public static String reason(String content) {
        if (content == null) return null;
        String t = content.trim();
        if (t.length() == 0) return null;
        if (PASSWORD_LIKE.matcher(t).find()) {
            return t.contains("验证码") || t.contains("校验码") || t.contains("动态码") ? "验证码" : "密码";
        }
        if (ID_WORDS.matcher(t).find() && DIGIT_RUN.matcher(t).find()) {
            return t.contains("卡") ? "银行卡号" : "证件号码";
        }
        // 句子里夹着一串卡号/身份证号（不带关键词）：Luhn / 身份证日期校验命中
        String r = ClipChipPlanner.sensitiveReason(t);
        if (r == null) return null;
        // 剪贴板口径里「纯 4–8 位数字」是为了防验证码；口述「记一下 1234」同样可能是密码 → 照样拒
        if (r.startsWith("银行卡")) return "银行卡号或身份证号";
        return r;
    }

    /** 拒绝时给用户的解释。 */
    public static String refusal(String reason) {
        return "这条像是" + reason + "，为了安全我不记这类内容（密码、验证码、银行卡号、证件号都不存）。"
                + "可以只记放在哪，比如「记一下护照放在书房抽屉」。";
    }
}
