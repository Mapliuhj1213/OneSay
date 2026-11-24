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
    /** [chat-1124] 没做成的任务等「再试一次」多久：15 分钟（用户可能先自己去看了一眼再回来）。 */
    public static final long UNFINISHED_TTL_MS = 15 * 60 * 1000L;

    public enum Origin { AGENT, ERRAND }

    public static final class Last {
        public final String query;
        public final String answer;
        public final Origin origin;
        public final long at;
        /** [chat-1124] 上一轮没做成（失败 / 取消 / 卡住）：「再试一次」「你打开错了」可以接回来重做。 */
        public final boolean unfinished;

        Last(String query, String answer, Origin origin, long at) {
            this(query, answer, origin, at, false);
        }

        Last(String query, String answer, Origin origin, long at, boolean unfinished) {
            this.query = query;
            this.answer = answer;
            this.origin = origin;
            this.at = at;
            this.unfinished = unfinished;
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

    /**
     * [chat-1124] 任务没做成时调用（失败 / 取消 / 卡住 / 只做了一半）：记下原话，之后 {@link #TTL_MS} 内的「再试一次」「你打开错了」接回来重做。
     * 原话里已有接续标记 → 只记标记前的原话（重试几次都还是同一个任务）。
     */
    public static void recordUnfinished(String query, String answer, Origin origin, long at) {
        if (query == null || strip(query).length() == 0) return;
        sLast = new Last(strip(query), answer == null ? "" : answer.trim(), origin, at, true);
    }

    public static void clear() {
        sLast = null;
    }

    public static Last last(long now) {
        Last l = sLast;
        if (l == null) return null;
        if (now - l.at > (l.unfinished ? UNFINISHED_TTL_MS : TTL_MS) || now < l.at) {
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
        // [chat-1124] 上一轮没做成 + 这句是「再试一次」或纠错（「你打开错了」「不是 X 是 Y」）→ 接回同一个任务，带上用户的话重做
        if (l.unfinished && RetryIntent.isRetryOrCorrection(command)) {
            sLast = null;
            return retryText(l, command.trim());
        }
        if (!isAffirm(command)) {
            sLast = null;
            return null;
        }
        sLast = null;   // 一次应答只接一次
        return l.query + " " + MARKER + "用户答：" + command.trim() + "；你上一轮最后说：「" + lastSentence(l.answer, 60)
                + "」。按你上一轮的提议 / 你自己的判断直接接着做，不要再反问。";
    }

    /** [chat-1124] 重做上一轮没做成的任务时交给模型的整句（原话照常被分诊认出，标记后的内容只给模型看）。 */
    static String retryText(Last l, String command) {
        String said = RetryIntent.isRetry(command) && !RetryIntent.isCorrection(command) ? "让你再试一次" : "纠正你：" + command;
        String prev = l.answer == null || l.answer.length() == 0 ? "" : "你上一轮最后说：「" + lastSentence(l.answer, 60) + "」。";
        return l.query + " " + MARKER + "用户答：" + command + "；" + RETRY_TAG + "，用户" + said + "。" + prev
                + "这是同一个任务，用你手里的全部工具从头再做一遍（要在 App 里点就用点界面的工具），把用户的纠正当真："
                + "上一轮哪里错了就照用户说的改（比如打开错了 App，这次先确认打开的是用户说的那个）。"
                + "不要说自己没有这个能力，也不要再解释上一轮为什么失败；真做不到，说清卡在哪一步。";
    }

    /** [chat-1124] 重做上一轮没做成的任务时整句里带的标记语。 */
    public static final String RETRY_TAG = "上一轮这个任务没做成";

    /** [chat-1124] 是不是「重做没做成的任务」改写出来的整句（工具层据此一开局就把点界面的工具给全）。 */
    public static boolean isRetry(String q) {
        if (q == null) return false;
        int k = q.indexOf(MARKER);
        return k >= 0 && q.indexOf(RETRY_TAG, k) > 0;
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
