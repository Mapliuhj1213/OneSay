package com.onesay.assistant.v2.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ borrow-1112：说「发了」「做完了」「搞定 周报」，把正在提醒 / 最近提醒过的待办标成完成（纯 Java，JVM 全测）。
 *
 * <ol>
 *   <li>{@link #parse}：认这句话是不是「做完了」类（问句、否定句不算；光说「好了」太泛，不认）；能带出要完成的是哪件（hint）；</li>
 *   <li>{@link #resolve}：只在<b>正在提醒 / 最近提醒过</b>的待办里找（不去全部待办里猜）——
 *       没有最近提醒的 → {@link Kind#NONE}（不接，交回原流程，免得把一句闲聊当成勾待办）；
 *       正好一条（或正在响的只有一条）→ {@link Kind#DONE}；几条都对得上 → {@link Kind#ASK} 问一句「是哪一条」；</li>
 *   <li>{@link #answer}：问过之后，「第一个 / 第二个 / 都完成了 / 周报那个」→ 选定。</li>
 * </ol>
 * 标完成走计划 App 已有的接口（provider call completeRemindedTask，同证书才认，见 {@link TodoDoneLink}），
 * 计划 App 不在时走助手本地的待办（S3）。
 *
 * <p>思路借鉴涛声 TaoSheng（https://github.com/0xCoach/TaoSheng，MIT）「说一句『发了』就把对应待办标为完成」；代码是自己写的。
 */
public final class TodoVoiceDone {

    private TodoVoiceDone() {}

    /** 最近提醒过的窗口（分钟）：与计划 App listRemindedTasks 的默认一致。 */
    public static final int WINDOW_MIN = 30;
    /** 问过「是哪一条」之后，多久内的回答还算数。 */
    public static final long ASK_TTL_MS = 2 * 60_000L;

    /** 一条候选待办。 */
    public static final class Cand {
        public final long id;
        public final String title;
        /** ringing 正在响 / snoozed 稍后中 / notified 发过通知 / stopped / missed；本地提醒记 notified。 */
        public final String state;
        public final long remindedAt;
        /** planner = 计划 App；local = 助手本地待办。 */
        public final String source;

        public Cand(long id, String title, String state, long remindedAt, String source) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.state = state == null ? "" : state;
            this.remindedAt = remindedAt;
            this.source = source == null ? "" : source;
        }

        public boolean ringing() {
            return "ringing".equals(state);
        }

        @Override public String toString() {
            return source + "#" + id + "「" + title + "」" + state;
        }
    }

    public enum Kind { NONE, DONE, ASK }

    public static final class Decision {
        public final Kind kind;
        public final List<Cand> picks;
        public final String say;

        Decision(Kind kind, List<Cand> picks, String say) {
            this.kind = kind;
            this.picks = picks;
            this.say = say;
        }

        static Decision none() {
            return new Decision(Kind.NONE, new ArrayList<Cand>(), "");
        }
    }

    // ------------------------------------------------------------------ 认句子

    static final String DONE_WORDS = "发了|发过了|发出去了|发完了|做完了|做好了|弄完了|弄好了|搞定了|搞定|完成了|办完了|办好了|交了|交完了|交上去了|写完了|打完了|"
            + "买好了|买完了|去过了|吃过了|吃了药|喝过了|回过了|回了|打过了|处理完了|处理好了|已完成|结束了";
    static final Pattern BARE = Pattern.compile("^(?:我|那个|这个)?(?:已经|都)?(?:" + DONE_WORDS + ")(?:啊|哈|呀|了|啦|吧|哦|噢|喽)?$");
    /** 「搞定周报」「做完 周报 了」「完成 交电费」 */
    static final Pattern VERB_FIRST = Pattern.compile("^(?:我)?(?:已经)?(?:搞定|做完|完成|弄完|办完|写完|交完|发完|处理完)(?:了)?(?:那个|这个)?(.{1,24}?)(?:了|啦)?$");
    /** 「周报写完了」「电费交了」「给妈妈的消息发了」 */
    static final Pattern ITEM_FIRST = Pattern.compile("^(.{1,24}?)(?:我)?(?:已经|都)?(?:" + DONE_WORDS + ")(?:啊|哈|呀|啦|吧|哦)?$");
    static final Pattern NEG_OR_ASK = Pattern.compile("没|还没|未|不|别|吗|么|嘛|没有|是否|[？?]");

    public static final class Intent {
        public final String hint;

        Intent(String hint) {
            this.hint = hint == null ? "" : hint;
        }
    }

    static String norm(String q) {
        return q == null ? "" : q.trim().replaceAll("[\\s，,。.！!、~～]", "");
    }

    /** null = 不是「做完了」类的话。 */
    public static Intent parse(String q) {
        String n = norm(q);
        if (n.length() == 0 || n.length() > 30) return null;
        if (NEG_OR_ASK.matcher(n).find()) return null;
        if (BARE.matcher(n).matches()) return new Intent("");
        Matcher m = VERB_FIRST.matcher(n);
        if (m.matches()) {
            String h = cleanHint(m.group(1));
            if (h.length() > 0) return new Intent(h);
        }
        m = ITEM_FIRST.matcher(n);
        if (m.matches()) {
            String h = cleanHint(m.group(1));
            if (h.length() > 0 && !h.matches("^(?:我|那个|这个|都|已经|也)$")) return new Intent(h);
        }
        return null;
    }

    static String cleanHint(String h) {
        if (h == null) return "";
        String t = h.replaceAll("^(?:把|那个|这个|给)", "").replaceAll("(?:那个|这个|那件事|这件事|的事|这事|那事|事情)$", "");
        t = t.replaceAll("(?:我|已经|都)$", "");
        return t.trim();
    }

    // ------------------------------------------------------------------ 找哪一条

    public static Decision resolve(Intent in, List<Cand> reminded) {
        if (in == null || reminded == null || reminded.isEmpty()) return Decision.none();
        List<Cand> pool;
        if (in.hint.length() > 0) {
            pool = new ArrayList<Cand>();
            for (Cand c : reminded) if (matches(c.title, in.hint)) pool.add(c);
            if (pool.isEmpty()) return Decision.none();   // 说的那件不在最近提醒里：不接（可能是别的事）
        } else {
            List<Cand> ring = new ArrayList<Cand>();
            for (Cand c : reminded) if (c.ringing()) ring.add(c);
            pool = ring.size() == 1 ? ring : reminded;
        }
        if (pool.size() == 1) {
            List<Cand> one = new ArrayList<Cand>(pool);
            return new Decision(Kind.DONE, one, doneText(one));
        }
        return new Decision(Kind.ASK, new ArrayList<Cand>(pool.subList(0, Math.min(3, pool.size()))), askText(pool));
    }

    static boolean matches(String title, String hint) {
        String t = title.toLowerCase(Locale.ROOT).replaceAll("^待办[：:]", ""), h = hint.toLowerCase(Locale.ROOT);
        if (t.length() == 0 || h.length() == 0) return false;
        if (t.contains(h) || h.contains(t)) return true;
        int hit = 0, n = 0;   // 字重合：hint 里的字有六成以上出现在标题里（「交电费」~「电费交一下」）
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (Character.isWhitespace(c)) continue;
            n++;
            if (t.indexOf(c) >= 0) hit++;
        }
        return n >= 2 && hit * 10 >= n * 6;
    }

    static String doneText(List<Cand> l) {
        if (l.size() == 1) return "好，「" + l.get(0).title + "」标成完成了。";
        StringBuilder b = new StringBuilder("好，");
        for (int i = 0; i < l.size(); i++) b.append(i > 0 ? "、" : "").append("「").append(l.get(i).title).append("」");
        return b.append("都标成完成了。").toString();
    }

    static String askText(List<Cand> pool) {
        StringBuilder b = new StringBuilder("你说的是哪一条：");
        int n = Math.min(3, pool.size());
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : i == n - 1 ? "，还是" : "，").append("「").append(pool.get(i).title).append("」");
        return b.append("？").toString();
    }

    // ------------------------------------------------------------------ 问过之后的回答

    static final Pattern ORD = Pattern.compile("^(?:是)?(?:第)?([一二三123])(?:个|条|项)?(?:吧|啊|呀)?$");
    static final Pattern ALL = Pattern.compile("^(?:都|全部|全都|两个都|三个都|都是)(?:完成了|做完了|搞定了|好了|是)?$|^都(?:完成|做完|搞定)了?$");
    static final Pattern CANCEL = Pattern.compile("^(?:算了|不是|都不是|没有|不用了|取消)$");

    /**
     * 对「是哪一条」的回答。options = 上次问的那几条（顺序同问句）。
     * @return 选中的（可能多条）；空 = 用户说「算了 / 都不是」；null = 听不出来（交回原流程）
     */
    public static List<Cand> answer(String q, List<Cand> options) {
        if (options == null || options.isEmpty()) return null;
        String n = norm(q);
        if (n.length() == 0) return null;
        if (CANCEL.matcher(n).matches()) return new ArrayList<Cand>();
        if (ALL.matcher(n).matches()) return new ArrayList<Cand>(options);
        Matcher m = ORD.matcher(n);
        if (m.matches()) {
            String d = m.group(1);
            int k = "一1".indexOf(d) >= 0 ? 0 : "二2".indexOf(d) >= 0 ? 1 : 2;
            if (k < options.size()) {
                List<Cand> one = new ArrayList<Cand>();
                one.add(options.get(k));
                return one;
            }
            return null;
        }
        String h = n.replaceAll("^(?:是)?(?:那个|那条)?", "").replaceAll("(?:那个|那条|那件)?(?:吧|啊|呀)?$", "");
        List<Cand> hit = new ArrayList<Cand>();
        for (Cand c : options) if (h.length() > 0 && matches(c.title, h)) hit.add(c);
        return hit.size() == 1 ? hit : null;
    }

    /** 问过之后记住的候选（进程内，{@link #ASK_TTL_MS} 过期）。 */
    public static final class Pending {
        private List<Cand> mOptions;
        private long mAt;

        public synchronized void set(List<Cand> options, long now) {
            mOptions = options == null ? null : new ArrayList<Cand>(options);
            mAt = now;
        }

        public synchronized List<Cand> get(long now) {
            if (mOptions == null || now - mAt > ASK_TTL_MS) {
                mOptions = null;
                return null;
            }
            return new ArrayList<Cand>(mOptions);
        }

        public synchronized void clear() {
            mOptions = null;
        }
    }
}
