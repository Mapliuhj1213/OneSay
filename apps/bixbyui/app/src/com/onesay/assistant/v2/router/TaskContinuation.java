package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（RESEARCH-TEST-basilisk R5）：多轮接续 —— 上一轮任务（AgentLoop / 跑腿）结尾提议或反问了，
 * 用户只回「好 / 行 / 可以 / 你决定 / 你看着办」时，接回<b>同一个任务</b>，不重新分诊成问答。
 *
 * <h3>怎么做</h3>
 * <ol>
 *   <li>{@link #record}：任务收工时记下（原话, 终稿, 来源）。终稿结尾不是提议 / 反问 → 清掉（链断了）；</li>
 *   <li>{@link #rewrite}：面板输入入口问这里：{@link #TTL_MS} 内有上一轮、且这句是纯应答 → 改写成
 *       「原话 {@link #MARKER}用户答：…；你上一轮最后说：…」重新提交。原话照常被跑腿 / 分诊认出（购物 → 跑腿；
 *       其余 → 分诊规则 {@code 0c-continue} 直接进 AgentLoop），标记后的内容只给模型看；</li>
 *   <li>不是应答的话 → 链断开（清掉上一轮），照常处理。</li>
 * </ol>
 * 纯 Java（JVM 可测），Java 7 语法。
 */
public final class TaskContinuation {

    private TaskContinuation() {}

    public static final String MARKER = "【接着上一轮】";
    /** 上一轮有效期：5 分钟。 */
    public static final long TTL_MS = 5 * 60 * 1000L;

    public enum Origin { AGENT, ERRAND }

    public static final class Last {
        public final String query;
        public final String answer;
        public final Origin origin;
        public final long at;

        Last(String query, String answer, Origin origin, long at) {
            this.query = query;
            this.answer = answer;
            this.origin = origin;
            this.at = at;
        }
    }

    private static volatile Last sLast;

    /** 纯应答（同意 / 授权助手决定）。 */
    private static final Pattern AFFIRM = Pattern.compile(
            "^(?:那|嗯|恩|哦|噢|好|行)?(?:好|好的|好啊|好呀|好吧|行|行啊|行吧|可以|可以的|可以啊|嗯|嗯嗯|对|是的|是|要|要的|需要|没问题|ok|okay|OK|Ok|去吧|开吧|打开吧|帮我打开|就这样|就这么办|听你的|"
                    + "你决定|你决定吧|你看着办|你看着办吧|你定|你定吧|你来定|你来决定|你帮我决定|你帮我选|你选|你挑|你选吧|随便|都行|都可以|你说了算)$");

    public static boolean isAffirm(String s) {
        if (s == null) return false;
        String t = s.trim().replaceAll("[，,。.！!？?~～…\\s]+", "");
        if (t.length() == 0 || t.length() > 10) return false;
        return AFFIRM.matcher(t).matches();
    }

    /** 终稿结尾在提议 / 反问（最后 80 字里）。 */
    public static boolean endsWithOffer(String answer) {
        if (answer == null) return false;
        String a = answer.trim();
        if (a.length() == 0) return false;
        String tail = a.length() > 80 ? a.substring(a.length() - 80) : a;
        return tail.contains("？") || tail.contains("?") || tail.contains("要不要") || tail.contains("要我")
                || tail.contains("需要我") || tail.contains("是否");
    }

    /** 任务收工时调用。原话里已有接续标记 → 只记标记前的原话（不叠加）。 */
    public static void record(String query, String answer, Origin origin, long at) {
        if (query == null || query.trim().length() == 0) return;
        String q = strip(query);
        if (endsWithOffer(answer)) sLast = new Last(q, answer.trim(), origin, at);
        else sLast = null;
    }

    public static void clear() {
        sLast = null;
    }

    public static Last last(long now) {
        Last l = sLast;
        if (l == null) return null;
        if (now - l.at > TTL_MS || now < l.at) {
            sLast = null;
            return null;
        }
        return l;
    }

    /**
     * 面板输入：是应答且有上一轮 → 改写后的整句（调用方重新提交）；否则 null（并且不是应答时断开链）。
     */
    public static String rewrite(String command, long now) {
        if (command == null || command.indexOf(MARKER) >= 0) return null;
        Last l = last(now);
        if (l == null) return null;
        if (!isAffirm(command)) {
            sLast = null;
            return null;
        }
        sLast = null;   // 一次应答只接一次
        return l.query + " " + MARKER + "用户答：" + command.trim() + "；你上一轮最后说：「" + lastSentence(l.answer, 60)
                + "」。按你上一轮的提议 / 你自己的判断直接接着做，不要再反问。";
    }

    public static boolean isContinuation(String q) {
        return q != null && q.indexOf(MARKER) >= 0;
    }

    /** 去掉标记及其后内容（得到原话）。 */
    public static String strip(String q) {
        if (q == null) return "";
        int k = q.indexOf(MARKER);
        return (k >= 0 ? q.substring(0, k) : q).trim();
    }

    /** 从「{@link #MARKER}用户答：X；…」里取 X。 */
    public static String answerOf(String tail) {
        if (tail == null) return null;
        int a = tail.indexOf("用户答：");
        if (a < 0) return null;
        String s = tail.substring(a + 4);
        int b = s.indexOf('；');
        return (b >= 0 ? s.substring(0, b) : s).trim();
    }

    static String lastSentence(String a, int n) {
        String t = a == null ? "" : a.replaceAll("\\s+", " ").trim();
        int cut = -1;
        for (int i = t.length() - 2; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '\n' || c == '；') { cut = i; break; }
        }
        String s = cut >= 0 ? t.substring(cut + 1).trim() : t;
        return s.length() > n ? s.substring(s.length() - n) : s;
    }
}
