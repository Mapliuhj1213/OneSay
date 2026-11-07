package com.onesay.assistant.v2.quicknote;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [quicknote] 语音句式（纯 Java）。不和 S2「记一下…」抢（「记一下 X」照旧进 S2；只有「帮我记一下 X」「随口记 X」是随口记，D6）。
 *
 * <ul>
 *   <li>RUN「记一记」/ RECALL「刚才记一记的是什么」/ LIST「记一记列表」/ DELETE_LAST「删掉刚才的记一记」（R9 起）；</li>
 *   <li>★ R10 SUIKOUJI「随口记 明早八点交房租」「帮我记一下 车停在 B2-17」→ 存进记一记（类别随口记）；</li>
 *   <li>★ R10 CATEGORY「我记了哪些取件码」「记一记里的文章」；</li>
 *   <li>★ R10 SEARCH（硬）「我的取件码是多少」「车停哪了」「记一记里关于三亚的」——一定由记一记回答；</li>
 *   <li>★ R10 SEARCH_SOFT「我记过的那个三亚攻略」「找一下我记的地址」——记一记里搜到才接，否则留给 S2；</li>
 *   <li>★ R10 OPEN「打开记一记」→ 控制台记一记列表。</li>
 *   <li>TICKET「帮我记下这张票」「把这张票存下来」「记下票」→ 识屏记一记（计划 App 在时由 PlannerPlugin 285 先接走存进票夹；
 *       这里是计划 App 不在时的退路）。说法与 {@link com.onesay.assistant.v2.planner.PlannerVoice#ticket} 同源；抢在 S2 之前，不进 memory.add。</li>
 * </ul>
 */
public final class QuickNoteIntent {

    public enum Kind { NONE, RUN, TICKET, RECALL, LIST, DELETE_LAST, SUIKOUJI, CATEGORY, SEARCH, SEARCH_SOFT, OPEN }

    private static final Pattern DELETE = Pattern.compile("(删掉|删除|撤销|去掉|不要)(刚才|刚刚|刚|最近)?(那条|那个)?(的)?记一记|记一记.*(删掉|删除|撤销)");
    private static final Pattern LIST = Pattern.compile("记一记(的)?(列表|清单|都有哪些|有哪些|记录)$|^(我|都)?记一记(过|了)(哪些|什么东西|几条)$|^记一记(的)?(列表|清单)");
    private static final Pattern RECALL = Pattern.compile("(刚才|刚刚|刚|最近|上次)(那条|那个)?记一记(的|了)?(那条|那个)?(是什么|是啥|内容|什么)?|记一记(的|了)(是什么|是啥|什么|内容)|记一记的那(条|个)$");
    private static final Pattern RUN = Pattern.compile("^(请|帮我|给我|你)?(把)?(这个|这页|屏幕|当前屏幕|这屏)?(给我)?记一记(这个|这页|屏幕|当前屏幕|一下)?$");
    private static final Pattern SUIKOUJI = Pattern.compile("^(?:请你?|麻烦你?|你)?(?:随口记(?:一下|一条|个)?|帮我记一下|帮我随手记(?:一下)?|随手记(?:一下)?)[:：,，]?(.*)$");
    private static final Pattern OPEN = Pattern.compile("^(打开|进入|看看|查看|显示)(我的)?记一记(列表|页面)?$");
    private static final Pattern CATEGORY_ASK = Pattern.compile("(记了|记过|记一记里|记一记的)(哪些|几个|几条|什么)?|(哪些|几个|几条)");
    private static final Pattern CODE_ASK = Pattern.compile("^(我的|我|刚才的|刚才那个|那个)?(取件码|取货码|取餐码|取餐号|券码|核销码|兑换码|入园码|快递单号)(是多少|是啥|是什么|多少|呢|在哪)?$");
    private static final Pattern PARK_ASK = Pattern.compile("(我的)?车(子)?停(在)?(哪|哪里|哪儿|什么地方)|停车(的)?(位置|位|地方)(在哪|是哪|是多少)?|车位(在)?(哪|是多少|号)");
    /** ★ int/1102 常识：「查一下我的快递」「我的快递到哪了」（没给单号）= 先看本机记下的快递单号 / 取件码，不是去百度搜「我的快递」。带单号的照旧交给查快递技能。 */
    static final Pattern PARCEL_ASK = Pattern.compile("^(帮我)?(查一下|查查|查|看一下|看看|找一下)?(我的|我)(快递|包裹|物流)(到哪了|到哪儿了|到哪里了|到了没|到了吗|到没到|在哪|在哪儿|怎么样了|什么时候到|呢)?$"
            + "|^(帮我)?(查一下|查查|查|看一下|看看)(快递|包裹|物流)(到哪了|到了没|到了吗|到没到)?$|^(我有|有没有)(快递|包裹)(吗|没有)?$");
    private static final Pattern SOFT = Pattern.compile("^(帮我)?(找一下|找找|查一下|翻一下)?我(之前|上次|刚才)?(记过|记下|记)的(那个|那条|那篇)?(.+)$");

    private QuickNoteIntent() {}

    static String norm(String query) {
        return query == null ? "" : query.trim().replaceAll("[。！!？?，,\\s]", "");
    }

    public static Kind of(String query) {
        if (query == null) return Kind.NONE;
        String raw = query.trim();
        String q = norm(query);
        if (q.length() == 0) return Kind.NONE;
        if (com.onesay.assistant.v2.planner.PlannerVoice.ticket(raw)) return Kind.TICKET;
        Matcher sk = SUIKOUJI.matcher(raw.replaceAll("^[\\s]+", ""));
        if (sk.matches() && !q.contains("记一记")) return Kind.SUIKOUJI;
        if (OPEN.matcher(q).matches()) return Kind.OPEN;
        if (CODE_ASK.matcher(q).matches()) return Kind.SEARCH;
        if (PARCEL_ASK.matcher(q).matches()) return Kind.SEARCH;
        if (PARK_ASK.matcher(q).find() && !q.startsWith("记") && !q.contains("导航")) return Kind.SEARCH;
        if (q.contains("记一记")) {
            if (DELETE.matcher(q).find()) return Kind.DELETE_LAST;
            if (LIST.matcher(q).find()) return Kind.LIST;
            if (QuickNoteSearch.categoryWord(q.replace("记一记", "")) != null && CATEGORY_ASK.matcher(q).find()) return Kind.CATEGORY;
            if (RECALL.matcher(q).find()) return Kind.RECALL;
            if (RUN.matcher(q).matches()) return Kind.RUN;
            if (q.contains("记一记里") || q.contains("记一记中") || q.contains("记一记的")) return Kind.SEARCH;
            return Kind.NONE;
        }
        // 「我记了哪些取件码」：S2 的「我记了哪些」+ 记一记的类别词 → 记一记按类列
        String cat = QuickNoteSearch.categoryWord(q);
        if (cat != null && !"码".equals(cat) && q.matches("^(我|我都)?记(了|过)(哪些|几个|几条|什么)(.+)$")) return Kind.CATEGORY;
        if (SOFT.matcher(q).matches()) return Kind.SEARCH_SOFT;
        return Kind.NONE;
    }

    /** 随口记的正文（「随口记 明早八点交房租」→「明早八点交房租」）。 */
    public static String suikoujiText(String query) {
        if (query == null) return "";
        Matcher m = SUIKOUJI.matcher(query.trim());
        return m.matches() ? m.group(1).trim() : "";
    }

    /** 需要抢在 S2 之前处理的句式（S2Hooks 在开关开着时据此放行给记一记）。SEARCH_SOFT 不在此列（搜不到要留给 S2）。 */
    public static boolean beforeS2(String query) {
        Kind k = of(query);
        return k == Kind.TICKET || k == Kind.SUIKOUJI || k == Kind.CATEGORY || k == Kind.SEARCH || k == Kind.OPEN;
    }
}
