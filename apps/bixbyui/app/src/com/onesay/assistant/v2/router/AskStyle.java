package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * 问答回答形态的本轮约束（追加在 ASK 系统提示词末尾，优先于模板里的字数要求；
 * 模板可被 adb 热改，这几条不随模板丢）。纯函数，JVM 可测。
 * <ul>
 *   <li>翻译：只给译文，最多一个常用变体（同一种目标语言），不讲多语言 / 语法 / 读音；</li>
 *   <li>用户说「一句话」：就一句；</li>
 *   <li>没要求详细：简短（1~3 句、约 80 字内）；</li>
 *   <li>要求详细 / 展开：不加约束（模板上限）。</li>
 * </ul>
 */
public final class AskStyle {
    private AskStyle() {}

    public static final String HEAD = "\n【本轮回答形态（优先于上面的字数要求）】\n";
    public static final String RULE_TRANSLATE = "这是翻译请求：只输出译文本身；最多再给一个常用的另一种说法（同一种目标语言）。"
            + "不解释语法，不列出其他语言，不加读音 / 拼音，不加「翻译如下」之类前缀。\n";
    public static final String RULE_ONE_SENTENCE = "用户要「一句话」：只回答一句话，不分段、不补充。\n";
    /** 明确要表格 → 直接给表格，不受「简短」限制。 */
    public static final String RULE_TABLE = "用户要表格：直接给一个 Markdown 表格（表头 + |---| 分隔行，最多 10 行 6 列，单元格是短语），"
            + "表格前最多一句结论；不要先问用户要不要表格，不受「简短 / 80 字」限制。\n";
    public static final String RULE_BRIEF = "用户没要求详细：简短作答，1~3 句、约 80 字以内；简单事实一句即可。\n";

    private static final Pattern TRANSLATE = Pattern.compile(
            "翻译|译成|翻成|(?:用|拿)?(?:英|日|韩|法|德|俄|西班牙|意大利|葡萄牙|泰|越南|粤|中)(?:文|语|国话)(?:里|中)?(?:怎么|咋|如何)(?:说|讲|写|表达)"
                    + "|(?:用|拿)(?:英|日|韩|法|德|俄|西班牙|中)(?:文|语)(?:说|讲|写)|(?i:in english|translate)");
    private static final Pattern ONE_SENTENCE = Pattern.compile("一句话|用一句|一句说|就一句|一句讲|一句概括|一句总结");
    private static final Pattern DETAIL = Pattern.compile("详细|具体讲|具体说|展开|多讲|多说点|全面|深入|一步一步|步骤|原理|为什么.{0,6}(?:详细|具体)|写一篇|写篇|写一段|长一点");

    public static boolean isTranslate(String q) { return q != null && TRANSLATE.matcher(q).find(); }
    public static boolean wantsOneSentence(String q) { return q != null && ONE_SENTENCE.matcher(q).find(); }
    public static boolean wantsDetail(String q) { return q != null && DETAIL.matcher(q).find(); }

    /** 本轮追加规则；要求详细（且非翻译 / 非一句话）时返回空串。 */
    public static String rule(String q) {
        StringBuilder sb = new StringBuilder();
        if (isTranslate(q)) sb.append(RULE_TRANSLATE);
        if (com.onesay.assistant.plug.richout.core.RichOutPlugs.wantsTable(q)) sb.append(RULE_TABLE);
        else if (wantsOneSentence(q)) sb.append(RULE_ONE_SENTENCE);
        else if (!wantsDetail(q) && !isTranslate(q)) sb.append(RULE_BRIEF);
        return sb.length() == 0 ? "" : HEAD + sb;
    }
}
