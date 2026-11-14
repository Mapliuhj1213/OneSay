package com.onesay.assistant.v2.llm;

import java.net.URI;
import java.util.Locale;

/** 服务商 base URL 规范化（纯 Java）：只收 https、无用户信息 / 查询串 / 片段；去掉误填的接口路径。 */
public final class ProviderUrls {
    private ProviderUrls() {}

    /** 允许测试把请求发往本机假服务器（同 {@code LlmEndpoints.sTestAllowLoopback} 的思路）；生产代码从不设置。 */
    static volatile boolean sTestAllowLoopback = false;

    /**
     * 规范化：去空白、去末尾 "/"，按协议去掉误填的接口后缀（OpenAI 兼容：/chat/completions、/models；
     * Anthropic：/v1/messages、/v1/models、/v1；Gemini：/v1beta/models…、/v1beta、/v1）。不合法返回 null。
     */
    public static String normalize(ProviderKind kind, String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.length() == 0) return null;
        s = trimSlash(s);
        String low = s.toLowerCase(Locale.ROOT);
        String[] suffixes;
        switch (kind) {
            case ANTHROPIC:
                suffixes = new String[]{"/v1/messages", "/v1/models", "/v1"};
                break;
            case GEMINI:
                int i = low.indexOf("/v1beta");
                if (i < 0) i = low.indexOf("/v1/");
                if (i < 0 && low.endsWith("/v1")) i = low.length() - 3;
                if (i > 0) { s = s.substring(0, i); low = s.toLowerCase(Locale.ROOT); }
                suffixes = new String[0];
                break;
            default:
                suffixes = new String[]{"/chat/completions", "/models"};
        }
        for (String suf : suffixes) {
            if (low.endsWith(suf)) { s = s.substring(0, s.length() - suf.length()); break; }
        }
        s = trimSlash(s);
        try {
            URI u = new URI(s);
            boolean loop = sTestAllowLoopback && "http".equalsIgnoreCase(u.getScheme()) && "127.0.0.1".equals(u.getHost());
            if (!loop && !"https".equalsIgnoreCase(u.getScheme())) return null;
            if (u.getHost() == null || u.getHost().length() == 0) return null;
            if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        } catch (Exception e) {
            return null;
        }
        return s;
    }

    public static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** 带 Key 的请求只发 https（单测的本机假服务器除外）。 */
    public static boolean keySafe(String url) {
        if (url == null) return false;
        if (sTestAllowLoopback && url.startsWith("http://127.0.0.1:")) return true;
        return url.regionMatches(true, 0, "https://", 0, 8) && host(url) != null;
    }

    /** 模型名：字母数字与 . _ - / : @（OpenRouter 有 "vendor/model"，有的服务有 "model:tag"）。 */
    public static boolean validModel(String m) {
        return m != null && m.matches("[A-Za-z0-9][A-Za-z0-9._\\-/:@]{0,127}");
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
