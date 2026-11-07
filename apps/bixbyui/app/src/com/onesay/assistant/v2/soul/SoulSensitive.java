package com.onesay.assistant.v2.soul;

import com.onesay.assistant.v2.s2.SensitiveGuard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [soul] 记忆写入的敏感闸门。复用 S2 的 {@link SensitiveGuard}（密码 / 验证码 / 银行卡 / 证件号，Luhn 与身份证校验），
 * 再补 API key / token 形态（研究 §6.5，照 Muse「Never record account or API credentials … Record that the item exists
 * and where it lives, not the value」）。
 *
 * <ul>
 *   <li>{@link #reason}：命中返回原因，不回显内容；</li>
 *   <li>{@link #existenceOnly}：候选 / 整理路径用——把值去掉，只留「有这么一项（值未保存）」；去不干净就返回 null 不存。</li>
 * </ul>
 * 「记一下」显式路径保持 S2 原行为（整句拒存并解释），见 S2Engine。
 */
public final class SoulSensitive {

    private SoulSensitive() {}

    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(sk|pk|ak|rk)[-_][a-z0-9._-]{8,}|\\bAKIA[0-9A-Z]{12,}|\\b[A-Za-z0-9+/_-]{28,}={0,2}|api[ _-]?key|access[ _-]?token|secret|私钥|助记词|令牌");

    public static String reason(String text) {
        if (text == null || text.trim().length() == 0) return null;
        String r = SensitiveGuard.reason(text);
        if (r != null) return r;
        if (API_KEY.matcher(text).find()) return "密钥";
        return null;
    }

    /** 关键词后面的值（「是 / 为 / ： / 空格」之后的串）。 */
    private static final Pattern VALUE_AFTER = Pattern.compile(
            "(密码|口令|验证码|校验码|动态码|卡号|账号|帐号|身份证号|证件号|护照号|api ?key|token|密钥|私钥|助记词)(是|为|叫|：|:|\\s)*[^，。,；;\\s]*");

    /**
     * 只记「存在」：「WiFi 密码是 abc123」→「WiFi 密码（值未保存）」。仍然敏感（例如没有关键词的裸卡号）→ null。
     */
    public static String existenceOnly(String text) {
        if (text == null) return null;
        if (reason(text) == null) return text;
        Matcher m = VALUE_AFTER.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "（值未保存）"));
        }
        if (!any) return null;
        m.appendTail(sb);
        String out = sb.toString();
        // 去掉值后应只剩关键词本身命中（「密码（值未保存）」）；再剥掉关键词复查一遍，不能有残留的数字串 / key
        String probe = out.replaceAll("密码|口令|验证码|校验码|动态码|卡号|账号|帐号|身份证号|证件号|护照号|(?i)api ?key|token|密钥|私钥|助记词|（值未保存）", "");
        if (reason(probe) != null) return null;
        return out;
    }
}
