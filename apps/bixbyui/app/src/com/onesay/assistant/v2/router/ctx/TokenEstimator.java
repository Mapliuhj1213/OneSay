package com.onesay.assistant.v2.router.ctx;

/**
 * ★ int/1102 用户新要求（2026-11-02 20:18）：对话上下文按 token 计预算。纯 Java、零依赖、偏保守的 token 估算。
 *
 * <ul>
 *   <li>CJK（汉字 / 假名 / 谚文 / 全角标点）≈ 1 字 1 token（DeepSeek / Qwen 的 BPE 对常用汉字多为 0.6–1 token/字，按 1 计偏保守）；</li>
 *   <li>ASCII ≈ 4 字符 1 token（英文 / 代码 / JSON 实测多在 3.5–4.5，向上取整）；</li>
 *   <li>其他非 ASCII（emoji、拉丁扩展、代理对等）一律按 1 个码点 1 token（保守）。</li>
 *   <li>每条消息另加 {@link #PER_MESSAGE} 个 token 的角色 / 分隔开销。</li>
 * </ul>
 * 只用于「留余量的预算」，不追求精确；宁可高估让历史早一点折叠，也不让请求超窗口报错。
 */
public final class TokenEstimator {
    private TokenEstimator() {
    }

    /** OpenAI 兼容消息格式每条的角色 / 分隔开销（保守取 4）。 */
    public static final int PER_MESSAGE = 4;

    public static int estimate(CharSequence s) {
        if (s == null) return 0;
        int ascii = 0, other = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                ascii++;
            } else {
                if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) i++;   // 一个码点
                other++;
            }
        }
        return other + (ascii + 3) / 4;
    }

    /** 一条消息（内容 + 开销）。 */
    public static int message(CharSequence content) {
        return estimate(content) + PER_MESSAGE;
    }

    /** CJK 判定（单测 / 报告用）。 */
    public static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    /**
     * 按 token 截断：超过 maxTokens 时保留开头约 2/3 + 结尾约 1/3（中间放「…」），Agent 回执的「结论」在结尾不会被砍。
     */
    public static String clipKeepTail(String s, int maxTokens) {
        if (s == null || estimate(s) <= maxTokens) return s;
        int tailBudget = maxTokens / 3;
        int headBudget = maxTokens - tailBudget - 1;
        int h = prefixLen(s, headBudget);
        int t = suffixLen(s, tailBudget);
        if (h + t >= s.length()) return s;
        return s.substring(0, h) + "…" + s.substring(s.length() - t);
    }

    /** 不超过 budget token 的最长前缀长度（字符数）。 */
    static int prefixLen(String s, int budget) {
        int ascii = 0, other = 0, i = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            int na = ascii, no = other;
            if (c < 0x80) na++; else no++;
            if (no + (na + 3) / 4 > budget) break;
            ascii = na;
            other = no;
        }
        if (i > 0 && i < s.length() && Character.isHighSurrogate(s.charAt(i - 1))) i--;
        return i;
    }

    /** 不超过 budget token 的最长后缀长度（字符数）。 */
    public static int suffixLenPublic(String s, int budget) {
        return suffixLen(s, budget);
    }

    static int suffixLen(String s, int budget) {
        int ascii = 0, other = 0, k = 0;
        for (int i = s.length() - 1; i >= 0; i--, k++) {
            char c = s.charAt(i);
            int na = ascii, no = other;
            if (c < 0x80) na++; else no++;
            if (no + (na + 3) / 4 > budget) break;
            ascii = na;
            other = no;
        }
        if (k > 0 && k < s.length() && Character.isLowSurrogate(s.charAt(s.length() - k))) k--;
        return k;
    }
}
