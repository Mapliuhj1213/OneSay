package com.onesay.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [longctx-1123] 长单会话的可靠性：每句话记账、会话状态、末尾标出最新一句、换段接力。
 *
 * <p>起因：长会话里模型复述用户的话、答偏到前面的问题上、忘了前面说过的事、人设和规矩慢慢失效，新开一段就好了 ——
 * 上下文被旧内容（尤其是它自己以前的回答和已经答完的问题）污染了。做法（只借思路，自己写）：
 * <ul>
 *   <li>每条用户消息记账：{@link #OPEN} 待答 / {@link #DOING} 在做 / {@link #DONE} 已结 / {@link #PARTIAL} 没做完；已结的不再当待答。</li>
 *   <li>窗口外的部分不写流水账，只写「状态」：用户说过的事（原话）、已经答完的问题（标已答）、没做完的任务。</li>
 *   <li>最新一句放在最后单独标出，写明只回答这一句；核心人设和硬规矩每轮在末尾短短再说一次。</li>
 *   <li>一段对话太长就自动换段，带上接力摘要（同样只有状态）；也可以手动「新对话」。</li>
 * </ul>
 */
final class LongCtx {
    private LongCtx() {}

    static final String OPEN = "open", DOING = "doing", DONE = "done", PARTIAL = "partial";

    /** 最近这么多轮原样给；再往前到 {@link #RECENT_MAX} 轮：用户原话（截短）+ 助手回答只留一句要点；更早的进「状态」。 */
    static final int RECENT_FULL = 3, RECENT_MAX = 8;
    /** 一段对话超过这么多句用户消息、或这么多字，就自动换段（带接力摘要）。 */
    static final int SESSION_TURNS = 40, SESSION_CHARS = 60000;
    static final int STATE_FACTS = 14, STATE_DONE = 8;

    static final Pattern QUESTION = Pattern.compile("[？?]|吗[。！!]?$|呢[。！!]?$|怎么|怎样|如何|为什么|为啥|多少|几[个点号天分岁]|哪[个里儿些天]|什么|是不是|能不能|可不可以|有没有|要不要");
    static final Pattern REQUEST = Pattern.compile("^(帮我|给我|请|麻烦|你|能不能|可以|把|让|去|再|继续|接着|打开|关掉|删|查|看看|搜)|帮我|给我|替我");
    static final Pattern FIRST_PERSON = Pattern.compile("我|俺|咱");

    /** 用户这句是一件「事实」（以后可能会被问到）：第一人称的陈述句，不是问题、不是请求，不太长。 */
    static boolean isFact(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 5 || s.length() > 200 || s.indexOf('\n') >= 0) return false;
        if (!FIRST_PERSON.matcher(s).find()) return false;
        if (QUESTION.matcher(s).find() || REQUEST.matcher(s).find()) return false;
        return true;
    }

    static boolean isQuestion(String t) { return t != null && QUESTION.matcher(t).find(); }

    static String oneLine(String s, int n) {
        String t = s == null ? "" : Outcomes.stripTag(s).replaceAll("\\s+", " ").trim();
        return ChatAgent.clip(t, n);
    }

    /** 助手以前的回答压成一句要点（第一句，最多 n 字）。 */
    static String gist(String s, int n) {
        String t = s == null ? "" : Outcomes.stripTag(s).trim();
        int cut = -1;
        for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c == '。' || c == '！' || c == '？' || c == '\n') { cut = i + 1; break; } }
        if (cut > 0 && cut < t.length()) t = t.substring(0, cut);
        return oneLine(t, n);
    }

    /** 核心人设和硬规矩：每轮在末尾短短说一次。 */
    static String rules(boolean tools) {
        return "规矩：你是 OneSay（不是别的助手），在用户自己的电脑上帮他做事。用简体中文、大白话，先给结论；不写工具名，不用 markdown 符号；"
                + "没查到、记不清的不要编" + (tools ? "，记不清就用 recall 查以前的对话，查不到就直说" : "，记不清就直说") + "；不输出密码和 Key"
                + (tools ? "；被拒绝就停下说明，不换写法绕过；任务结束时第一行写结果标记" : "") + "。";
    }

    /**
     * 末尾那条（用户角色）：程序提示（规矩、已加载准则、还没答的）+ 单独标出来的最新一句。最新一句原样放在最后。
     */
    static String endBlock(String latest, List<String> open, Collection<String> guides, boolean tools) {
        StringBuilder b = new StringBuilder("（OneSay 程序提示，不是用户说的话）\n").append(rules(tools)).append('\n');
        if (guides != null && !guides.isEmpty()) b.append("这段对话已加载的做法准则：").append(ChatGuides.names(guides)).append("（正文在最上面）。\n");
        if (open != null && !open.isEmpty()) {
            b.append("前面还没答上的（之前出错或没来得及回）：");
            List<String> l = new ArrayList<>();
            for (String o : open) l.add("「" + oneLine(o, 60) + "」");
            b.append(String.join("、", l)).append("——用户最新这句没提到就别管。\n");
        }
        b.append("上面对话里更早的问题都已经答过了；不要复述用户的话，不要再答前面的问题，除非最新这句在问它。\n");
        b.append("【用户最新一句：只回答 / 只照做这一句】\n").append(latest == null ? "" : latest);
        return b.toString();
    }

    /** 会话状态（窗口外的部分）：只有事实，已答的标已答。全空返回 ""。 */
    static String stateBlock(List<String> facts, List<String[]> done, List<String> todo, String relayNote) {
        if (facts.isEmpty() && done.isEmpty() && todo.isEmpty() && (relayNote == null || relayNote.isBlank())) return "";
        StringBuilder b = new StringBuilder("（OneSay 程序记录，不是用户说的话：这段对话前面部分的状态，只记事实，不是要你回答的问题）\n");
        if (relayNote != null && !relayNote.isBlank()) b.append(relayNote.trim()).append('\n');
        if (!facts.isEmpty()) {
            b.append("用户在这段对话里说过的事（原话）：\n");
            for (String f : facts) b.append("- ").append(oneLine(f, 120)).append('\n');
        }
        if (!done.isEmpty()) {
            b.append("已经答完的问题（都已经答过，用户没再问就不用再答）：\n");
            for (String[] d : done) b.append("- 「").append(oneLine(d[0], 50)).append("」已答").append(d[1] == null || d[1].isBlank() ? "" : "：" + gist(d[1], 60)).append('\n');
        }
        if (!todo.isEmpty()) {
            b.append("没做完的任务（用户说「继续」才接着做，别自己接着做）：\n");
            for (String t : todo) b.append("- ").append(oneLine(t, 120)).append('\n');
        }
        return b.toString();
    }

    /** 去重、保留最近的 n 条（顺序从旧到新）。 */
    static List<String> lastDistinct(List<String> in, int n) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> rev = new ArrayList<>();
        for (int i = in.size() - 1; i >= 0 && rev.size() < n; i--) if (seen.add(in.get(i).trim())) rev.add(in.get(i).trim());
        java.util.Collections.reverse(rev);
        return rev;
    }

    // ------------------------------------------------------------------ 长期记忆按需检索

    /** 记忆不超过这么多字就整份给。 */
    static final int MEMORY_ALL = 300;
    /** 称呼类（用户叫什么）每轮都给。 */
    static final Pattern NAME_LINE = Pattern.compile("^(用户)?(叫|名叫|的名字|希望被叫|称呼)");

    /** 记忆不长就整份给；长了只给和 query 相关的几条（最多 k 条）+ 称呼。 */
    static String memoryFor(String memory, String query, int k) {
        if (memory == null || memory.isBlank()) return "";
        if (memory.length() <= MEMORY_ALL) return memory;
        List<String> lines = MemoryRules.lines(memory);
        List<double[]> sc = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            double s = Chat.related(MemoryRules.norm(lines.get(i)), query);
            if (s > 0) sc.add(new double[] {s, i});
        }
        sc.sort((a, b) -> Double.compare(b[0], a[0]));
        Set<Integer> keep = new java.util.TreeSet<>();
        for (int i = 0; i < Math.min(k, sc.size()); i++) keep.add((int) sc.get(i)[1]);
        for (int i = 0; i < lines.size(); i++) if (NAME_LINE.matcher(MemoryRules.norm(lines.get(i))).find()) keep.add(i);
        StringBuilder b = new StringBuilder();
        for (int i : keep) b.append(lines.get(i)).append('\n');
        int rest = lines.size() - keep.size();
        if (rest > 0) b.append("（还有 ").append(rest).append(" 条和这句话关系不大，没列出来；要用时用 recall 查）");
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ 换段

    /** 最近一个换段标记之后从哪条开始（没有标记 = 0）。 */
    static int sessionStart(List<Map<String, Object>> all) {
        for (int i = all.size() - 1; i >= 0; i--) if ("session".equals(all.get(i).get("via"))) return i + 1;
        return 0;
    }

    @SuppressWarnings("unchecked")
    static List<String> strList(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) if (x != null) l.add(String.valueOf(x));
        return l;
    }
}
