package com.onesay.assistant.v2.applog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [settings-diag-1124] 应用内日志写盘 / 导出前的最后一道脱敏（纯 Java，JVM 可测）。
 *
 * <p>先过 {@link com.onesay.assistant.v2.util.SafeLog#clean}（Redactor：手机号、身份证、银行卡、邮箱、验证码，含全角写法），
 * 再把任何像密钥 / 凭据的东西换成占位：
 * <ul>
 *   <li>{@code Authorization / api_key / token / password / secret / cookie … = 值}（带不带引号、冒号或等号）→ 值换成「‹已隐藏›」；</li>
 *   <li>{@code Bearer xxx}、{@code sk-xxx} 这类密钥前缀；</li>
 *   <li>32 位以上的连续字母数字串（密钥、令牌、签名摘要）；</li>
 *   <li>网址只留主机名（路径和查询参数里可能带 key / 用户内容）。</li>
 * </ul>
 * 控制字符去掉；单行最长 {@link #MAX_LINE} 字，带堆栈的行最长 {@link #MAX_TRACE} 字。
 * 正则只用 Android 7/8 也支持的写法（没有命名组、没有 \p{IsHan}）。
 */
public final class AppLogSanitizer {

    private AppLogSanitizer() {}

    public static final int MAX_LINE = 800;
    public static final int MAX_TRACE = 4000;
    public static final String HIDDEN = "‹已隐藏›";

    static final Pattern KV_SECRET = Pattern.compile(
            "(?i)(\"?)(authorization|proxy-authorization|x-api-key|api[-_ ]?key|apikey|access[-_]?token|refresh[-_]?token|id[-_]?token"
                    + "|client[-_]?secret|secret|password|passwd|pwd|passphrase|storepass|keypass|token|cookie|set-cookie|session[-_]?id|signature|sig)"
                    + "\\1(\\s*[:=]\\s*)(\"?)((?:(?:bearer|basic)\\s+)?[^\\s,;\"}&]+)");
    static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{6,}");
    static final Pattern SK = Pattern.compile("(?<![A-Za-z0-9])(sk|ak|pk|rk)-[A-Za-z0-9_\\-]{8,}");
    static final Pattern LONG = Pattern.compile("(?<![A-Za-z0-9_+/=\\-])[A-Za-z0-9_+/=\\-]{32,}(?![A-Za-z0-9_+/=\\-])");
    static final Pattern URL = Pattern.compile("(?i)\\b(https?|wss?|ftp)://([^/\\s?#\"'<>]+)([/?#][^\\s\"'<>]*)?");

    /** 一行日志正文的最终写法（null → "null"）。 */
    public static String clean(String s) {
        return clean(s, false);
    }

    /** @param multiLine true = 允许换行（异常堆栈），长度上限放宽到 {@link #MAX_TRACE}。 */
    public static String clean(String s, boolean multiLine) {
        if (s == null) return "null";
        String t;
        try {
            // 网址先收成只留主机名：不然「user:pw@host」会被当成邮箱打码，主机名也跟着没了
            t = com.onesay.assistant.v2.util.SafeLog.clean(maskUrls(s));
        } catch (Throwable e) {
            return "‹" + s.length() + "字›";
        }
        t = maskSecrets(t);
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') {
                b.append(multiLine ? "\n    " : " ⏎ ");
            } else if (c == '\r' || c == '\t') {
                b.append(' ');
            } else if (c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || c == 0xfeff) {
                // 控制字符 / 零宽字符：丢掉
            } else {
                b.append(c);
            }
        }
        int max = multiLine ? MAX_TRACE : MAX_LINE;
        if (b.length() > max) {
            int more = b.length() - max;
            b.setLength(max);
            b.append("…(+").append(more).append(')');
        }
        return b.toString();
    }

    /** 只做密钥 / 凭据 / 网址这一层（不调 Redactor），单测与导出复扫用。 */
    public static String maskSecrets(String s) {
        if (s == null || s.length() == 0) return s;
        String t = s;
        Matcher m = KV_SECRET.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + m.group(1) + m.group(3) + m.group(4) + HIDDEN));
        }
        m.appendTail(sb);
        t = sb.toString();
        t = BEARER.matcher(t).replaceAll("$1 " + HIDDEN);
        t = SK.matcher(t).replaceAll(HIDDEN);
        t = maskUrls(t);
        t = LONG.matcher(t).replaceAll("‹长串已隐藏›");
        return t;
    }

    /** 网址只留协议 + 主机名（去掉 userinfo、路径、查询参数）。 */
    static String maskUrls(String t) {
        if (t == null || t.length() == 0) return t;
        Matcher m = URL.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String host = m.group(2);
            int at = host.lastIndexOf('@');
            if (at >= 0) host = host.substring(at + 1);   // 去掉 userinfo
            String rest = m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "://" + host + (rest == null || rest.length() <= 1 ? "" : "/…")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
