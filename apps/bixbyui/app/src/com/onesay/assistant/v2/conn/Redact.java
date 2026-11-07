package com.onesay.assistant.v2.conn;

import java.util.regex.Pattern;

/**
 * [conn] 日志脱敏：所有可能进日志的字符串（错误信息、URL、服务器回包片段）都先过这里。
 * 规则宁多勿少：Bearer 令牌、OAuth 字段、常见 Key 前缀、URL 查询串里的 token/key/code/secret。
 */
public final class Redact {

    private Redact() {}

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"),
            Pattern.compile("(?i)(\"?(?:access_token|refresh_token|id_token|client_secret|code_verifier|device_code|api[_-]?key|authorization|password|token)\"?\\s*[:=]\\s*\"?)[^\"&\\s,}]+"),
            Pattern.compile("(?i)([?&](?:access_token|refresh_token|token|key|api_key|code|client_secret|code_verifier)=)[^&\\s\"]+"),
            Pattern.compile("(tvly-)[A-Za-z0-9_-]+"),
            Pattern.compile("(sk-)[A-Za-z0-9_-]{6,}"),
            Pattern.compile("(ya29\\.)[A-Za-z0-9._-]+"),
            Pattern.compile("(1//)[A-Za-z0-9._-]+"),
            Pattern.compile("(GOCSPX-)[A-Za-z0-9_-]+"),
    };

    /** 脱敏并截断（默认 300 字）。 */
    public static String s(String in) {
        return s(in, 300);
    }

    public static String s(String in, int max) {
        if (in == null) return "";
        String out = in;
        for (Pattern p : PATTERNS) out = p.matcher(out).replaceAll("$1***");
        out = out.replace('\n', ' ').replace('\r', ' ');
        if (out.length() > max) out = out.substring(0, max) + "…";
        return out;
    }

    /** 异常 → 只留类名 + 脱敏后的消息。 */
    public static String t(Throwable t) {
        if (t == null) return "";
        return t.getClass().getSimpleName() + ": " + s(String.valueOf(t.getMessage()), 160);
    }
}
