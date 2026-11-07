package com.onesay.assistant.v2.search;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [r2/uxcompare] 追问保留上下文：省略式追问（"那明天呢""那上海呢""后天呢"）拼回完整的<b>检索词</b>（纯 Java，JVM 全测）。
 *
 * <h3>差距</h3>
 * 问答通道会把同一面板里的历史问答带给模型（{@code ConversationMemory}），但<b>联网检索</b>用的还是原话：
 * 「佛山今天天气怎么样 → 那明天呢」第二问拿「那明天呢」去搜，搜回来的是无关网页，
 * 模型只能凭上一轮答案猜，或者答"没有查到明天的天气"。
 * 竞品（小爱「连续对话语料 + 槽位继承」、天猫精灵「前置意图」、小艺连续对话）都把城市 / 日期槽位从上一轮继承过来。
 *
 * <h3>规则</h3>
 * <ol>
 *   <li>只处理<b>短的省略式追问</b>：以「呢」收尾，或以「那 / 那么 / 还有」开头且剩下的是日期 / 地名；去壳后 ≤ 8 个字；</li>
 *   <li>上一问是天气类：追问是日期 → 替换上一问里的日期（没有就加在前面）；追问是地名 → 替换上一问里的地名（没有就加在前面）；
 *       其余（"那穿什么呢"）→ 上一问 + 追问；</li>
 *   <li>上一问不是天气类：追问 + 上一问（"以太坊 比特币现在多少钱"），资料里两边都有，模型再按历史取舍；</li>
 *   <li>不是省略式追问 / 没有上一问 → 原样（行为与旧版逐字一致）。</li>
 * </ol>
 * 只改<b>检索词</b>；交给模型的用户原话、记进会话历史的问题都不变。
 */
public final class FollowUpQuery {

    private FollowUpQuery() {}

    /** 省略式追问去壳后的最大字数。 */
    static final int MAX_CORE = 8;

    static final Pattern TIME = Pattern.compile(
            "大后天|后天|明天|今天|今晚|明晚|明早|今早|昨天|这周末|下周末|周末|"
            + "(?:下下|下个|下|这个|这|本)?(?:周|星期|礼拜)[一二三四五六日天]|"
            + "早上|上午|中午|下午|傍晚|晚上|夜里|今夜|"
            + "(?:未来|接下来)?[一二三四五六七两0-9]+天");

    /** 「那」后面跟「个/些/里/边…」是指代（"那个文件在哪"），不是追问。 */
    private static final Pattern LEAD = Pattern.compile("^(?:那么|那要是|那如果|那(?![个些里边儿样时种位])|还有|换成|换到)");
    private static final Pattern TAIL = Pattern.compile("(?:的天气|的)?(?:呢|怎么样|如何|咋样)?[？?。！!～~]*$");
    /** 动词开头的不是地名（"那打开微信"）。 */
    private static final Pattern VERB_LEAD = Pattern.compile("^(?:打开|关闭|关掉|帮|给|发|打|设|调|查|搜|看|听|放|播|记|删|开|关|去|买|叫|点)");

    /**
     * 按会话历史解析：从最早一问起逐轮解析（"佛山今天天气 → 那明天呢 → 后天呢" 第三问解析成「佛山后天天气…」）。
     *
     * @param query            这一问（用户原话）
     * @param historyQuestions 同一面板里之前的问题，旧 → 新（可空）
     */
    public static String forSearch(String query, java.util.List<String> historyQuestions) {
        if (historyQuestions == null || historyQuestions.isEmpty()) return query;
        String resolved = null;
        for (String h : historyQuestions) {
            if (h == null || h.trim().length() == 0) continue;
            resolved = resolved == null ? h.trim() : resolve(h.trim(), resolved);
        }
        return resolve(query, resolved);
    }

    /**
     * @param query        这一问（用户原话）
     * @param prevQuestion 上一问（已解析过的完整问句；可空）
     * @return 拿去检索的词；不需要改写时原样返回 query
     */
    public static String resolve(String query, String prevQuestion) {
        if (query == null) return null;
        String q = query.trim();
        String prev = prevQuestion == null ? "" : prevQuestion.trim();
        if (prev.length() == 0 || q.length() == 0 || prev.startsWith("[")) return query;
        if (!isElliptical(q)) return query;
        String core = core(q);
        if (core.length() == 0) return query;

        if (WeatherQuery.isWeatherQuery(prev)) {
            if (isTime(core)) {
                Matcher tPrev = TIME.matcher(prev);
                if (tPrev.find()) return prev.substring(0, tPrev.start()) + core + prev.substring(tPrev.end());
                return core + " " + prev;
            }
            if (looksLikePlace(core)) {
                String p = placeIn(prev);
                if (p != null) return prev.replace(p, core);
                return core + " " + prev;
            }
            return prev + " " + core;
        }
        return core + " " + prev;
    }

    /** 短的省略式追问吗（"那明天呢""后天呢""那上海呢"）。 */
    static boolean isElliptical(String q) {
        boolean lead = LEAD.matcher(q).find();
        String t = q.replaceAll("[？?。！!～~\\s]+$", "");
        boolean tail = t.endsWith("呢");
        if (!lead && !tail) return false;
        String c = core(q);
        if (c.length() == 0 || c.length() > MAX_CORE) return false;
        if (tail) return true;
        // 只有「那…」开头、没有「呢」：只认日期 / 地名（"那明天""那上海的"），别把"那打开微信"当追问
        return isTime(c) || looksLikePlace(c);
    }

    static String core(String q) {
        String c = LEAD.matcher(q.trim()).replaceFirst("");
        c = TAIL.matcher(c).replaceFirst("");
        return c.trim();
    }

    static boolean isTime(String core) {
        Matcher m = TIME.matcher(core);
        return m.find() && m.start() == 0 && m.group().length() == core.length();
    }

    /** 追问里的词像地名吗：命中主要城市名 / 地名后缀，或 2~4 个汉字且不是日期、天气词、疑问词、动词开头。 */
    static boolean looksLikePlace(String core) {
        if (VERB_LEAD.matcher(core).find()) return false;
        if (TIME.matcher(core).find() || WeatherQuery.isWeatherQuery(core)) return false;
        if (WeatherQuery.hasPlace(core, null)) return true;
        return core.matches("[\\u4e00-\\u9fa5]{2,4}") && !core.matches(".*(什么|怎么|多少|为什么|几|吗|么|呢).*");
    }

    /** 上一问里的地名（主要城市名表，取最长的；找不到返回 null）。 */
    static String placeIn(String prev) {
        String best = null;
        for (String p : WeatherQuery.PLACE_MARKS) {
            if (p.length() < 2) continue;   // 「市」「省」这类后缀不是地名本身
            if (prev.contains(p) && (best == null || p.length() > best.length())) best = p;
        }
        return best;
    }
}
