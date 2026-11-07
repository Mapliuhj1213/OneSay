package com.onesay.assistant.v2.util;

/**
 * 语音播报文本整形（P1-10，纯 Java，JVM 单测）。
 *
 * <p>只播"短答案与动作确认"：去 Markdown、去 emoji/符号、压成一行；超过上限时截到最后一个句末标点，
 * 实在没有句末就截断加"等"。太长（例如整段百科）只播第一句，面板上有全文。
 */
public final class SpeechText {

    private SpeechText() {}

    /** 默认播报上限（字符）。中文语速约 4–5 字/秒，60 字 ≈ 12–15 秒。 */
    public static final int DEFAULT_MAX = 60;

    /**
     * [r2/uxcompare] 问答答案的播报上限：40 字 ≈ 8–10 秒（原来问答也按 60 字播）。
     * 对标小爱 / 小艺 / Siri / Google：嘴上只说核心（"佛山今天多云，23 到 28 度"），细节留在卡片上；
     * 同一句话每天要听很多遍，越短越不烦（Apple HIG「Keep responses as succinct as possible」）。
     */
    public static final int ANSWER_MAX = 40;

    /** 失败提示的播报上限（只说"哪儿不行 + 下一步"）。 */
    public static final int FAILURE_MAX = 40;

    /** 问答答案的播报文本：去铺垫 → 只播前 {@link #ANSWER_MAX} 字内的完整句子（全文在卡片上）。 */
    public static String forAnswer(String text) {
        if (text == null) return "";
        return forSpeech(stripLeadFiller(text), ANSWER_MAX);
    }

    /**
     * 开头的客套 / 来源铺垫（"好的，""根据检索结果，""根据最新资料显示："）。
     * 竞品的答案第一句就是结论；这类铺垫上卡片占掉大字首行、播报又多两秒，都没信息量。
     */
    private static final java.util.regex.Pattern LEAD_FILLER = java.util.regex.Pattern.compile(
            "^(?:(?:好的呀|好的|好嘞|好|嗯嗯|嗯|当然可以|没问题)[，,。！!～~\\s]+"
            + "|根据(?:你提供的|您提供的|联网|网络|最新|目前|现有|以上|上述)?(?:的)?"
            + "(?:检索|搜索|查询|搜到|查到|检索到|搜索到|查询到)?(?:到)?(?:的)?(?:结果|资料|信息|数据|内容)"
            + "(?:显示|来看|可知|表明)?[，,：:]\\s*)+");

    /** 去掉开头的客套 / 来源铺垫；去完不剩实质内容（少于 2 个字）就原样返回。 */
    public static String stripLeadFiller(String text) {
        if (text == null) return null;
        String t = text.trim();
        java.util.regex.Matcher m = LEAD_FILLER.matcher(t);
        if (!m.find() || m.end() == 0) return t;
        String rest = t.substring(m.end()).trim();
        return rest.length() >= 2 ? rest : t;
    }

    /** @return 适合播报的文本；没有可读内容返回空串 */
    public static String forSpeech(String text, int max) {
        if (text == null) return "";
        String s = TextUtil.cleanMarkdown(com.onesay.assistant.v2.router.UntrustedTags.strip(text));   // ★ audit-J 6：不朗读 <不可信数据> 标签
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) continue;   // emoji
            if (c >= 0x2190 && c <= 0x2BFF) continue;                                    // 箭头/符号/✓✕
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c == '·' || c == '•' || c == '|') c = ' ';
            sb.append(c);
        }
        String t = sb.toString().replaceAll("\\s{2,}", " ").trim();
        if (t.length() <= max) return t;
        int cut = -1;
        for (int i = Math.min(max, t.length()) - 1; i >= 4; i--) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?') {
                cut = i + 1;
                break;
            }
        }
        if (cut > 0) return t.substring(0, cut).trim();
        return t.substring(0, max).trim() + "等";
    }
}
