package com.onesay.assistant.v2.util;

/**
 * 文本小工具（收敛各处重复的 truncate / brief / clip / cleanMarkdown）。
 * 纯 Java，无 android 依赖。
 */
public final class TextUtil {

    private TextUtil() {}

    public static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** null → ""。 */
    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 截断到 {@code max} 个字符，超出时追加 {@code ellipsis}（不计入 max）。
     * {@code null} 返回 ""。
     */
    public static String truncate(String s, int max, String ellipsis) {
        if (s == null) return "";
        if (max < 0) max = 0;
        return s.length() <= max ? s : s.substring(0, max) + (ellipsis == null ? "" : ellipsis);
    }

    /** 等价于旧版 asr 里的 {@code truncate(s, max)}：超出追加 "..."。 */
    public static String truncate(String s, int max) {
        return truncate(s, max, "...");
    }

    /**
     * 日志/面板用：把换行压成空格、trim 后截断。
     *
     * @param ifNull {@code s == null} 时的返回值
     */
    public static String oneLine(String s, int max, String ellipsis, String ifNull) {
        if (s == null) return ifNull;
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return truncate(t, max, ellipsis);
    }

    /**
     * 剥离 Markdown 字符（*、#、`、_、[]、()、>、~），确保 TTS / 面板显示自然。
     * 与 {@code UnifiedExecutor.cleanMarkdown} / {@code L2LLMCompiler.cleanMarkdown} 行为逐字一致。
     */
    public static String cleanMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\*#`_\\[\\]\\(\\)>~]", "").trim();
    }

    /**
     * 密钥/凭据写日志时的唯一允许形态 —— 只说「已配置/未配置」，
     * 不露任何字符、前后缀或长度（长度也能缩小爆破范围，且能区分授权码类型）。
     */
    public static String secretState(String secret) {
        return secret == null || secret.trim().length() == 0 ? "未配置" : "已配置";
    }
}
