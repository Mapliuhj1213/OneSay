package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级对话记忆（**同一面板生命周期内**的多轮上下文）。
 *
 * <p><b>动机（2026-10-03 用户提出）</b>：原实现每次问答都是**无状态单次调用** ——
 * 模型看不到上一轮说了什么，所以「为什么天空是蓝色的？→ 那为什么日落是红的？」
 * 第二问里的"那"模型完全不知道指什么。
 *
 * <p>用户原话："llm是单次输出没有上下文，我觉得可以在单次打开窗口里加上上下文，
 * 如果问答不关闭的话。" —— 关键限定就是"**面板不关闭**"：
 * <ul>
 *   <li>面板弹着 = 同一会话，后续追问带上历史；</li>
 *   <li>面板收起（✕ / 点外部 / 下拉）= 会话结束，历史清空；</li>
 *   <li>下次唤醒 = 全新会话，绝不带上一轮的残留（避免"上次问的股票"污染这次）。</li>
 * </ul>
 *
 * <p><b>预算</b>（★ int/1102 改按 token）：历史总量 ≤ {@link #budgetTokens()}（默认 32,768 token），
 * 超出时最旧的轮成批折叠成摘要（不直接丢）；请求级再按模型窗口夹紧（{@link ContextBudget}）。
 *
 * <p><b>线程安全</b>：问答在后台线程写、面板可能主线程清，所以全部方法加锁。
 * 本类零 Android 依赖，可在宿主机直接测。
 */
public final class ConversationMemory {

    private static final String TAG = "ConversationMemory";

    /**
     * ★ int/1102（用户 2026-11-02 20:18：「以前的设计过时了」）：预算改按 <b>token</b> 计。
     * 历史经历：3 轮 / 1600 字 / 单条 400 字（原始）→ device-1102 的 10 轮 / 6000 字 / 单条 1200 字 → 现在：
     * 历史总预算 {@link #DEFAULT_HISTORY_BUDGET_TOKENS}（32,768 token，prefs {@code bixby_conv/ctx_budget_tokens} 可改），
     * 轮数只留一个很宽的安全上限 {@link #MAX_TURNS}，单条回答 {@link #MAX_ANSWER_TOKENS} token（保留结尾），
     * 超预算时最旧的轮折叠进摘要（不直接丢）。token 用 {@link com.onesay.assistant.v2.router.ctx.TokenEstimator} 偏保守估算。
     * 每次请求还会按所用模型的窗口再夹紧一次（{@link ContextBudget}）。
     */
    public static final int DEFAULT_HISTORY_BUDGET_TOKENS = 32768;

    /** 预算可配的下限 / 上限（防误配）。 */
    public static final int MIN_HISTORY_BUDGET_TOKENS = 2048, MAX_HISTORY_BUDGET_TOKENS = 262144;

    /** 轮数安全上限（不是主约束；主约束是 token 预算）。 */
    public static final int MAX_TURNS = 64;

    /** 单条答案入历史前的上限（token）：超长时保留开头约 2/3 + 结尾约 1/3（Agent 回执的「结论」在结尾）。 */
    public static final int MAX_ANSWER_TOKENS = 4000;

    /** 单条问句入历史的上限（token；正常口语远小于此，防粘贴长文把预算吃光）。 */
    public static final int MAX_QUESTION_TOKENS = 2000;

    /** 被挤出预算的更早轮次压成的摘要上限（token）。 */
    public static final int MAX_SUMMARY_TOKENS = 2048;

    /**
     * 折叠回落线：超预算时一次折到预算的 75% 以下（成批折叠），而不是每来一轮就折一轮 ——
     * 历史前缀不必每轮都变，DeepSeek 前缀缓存（按完整前缀单元命中）在两次折叠之间能一直命中。
     */
    public static final double FOLD_LOW_WATER = 0.75;

    private volatile int mBudgetTokens = DEFAULT_HISTORY_BUDGET_TOKENS;

    /** 设历史预算（token）；≤0 = 恢复默认；夹在 [MIN, MAX] 内。返回生效值。 */
    public synchronized int setBudgetTokens(int tokens) {
        int v = tokens <= 0 ? DEFAULT_HISTORY_BUDGET_TOKENS : Math.max(MIN_HISTORY_BUDGET_TOKENS, Math.min(MAX_HISTORY_BUDGET_TOKENS, tokens));
        mBudgetTokens = v;
        trim();
        return v;
    }

    public int budgetTokens() { return mBudgetTokens; }

    /**
     * ★ device-1102：会话保持时长（毫秒）。面板开着、距上一轮在这个时间内 → 第二次输入接着上轮；
     * 面板关了也先不清，超过这个时间没有新一轮才算会话结束（说「新对话 / 换个话题」立即清）。可配（{@link #setKeepMs}）。
     */
    public static final long DEFAULT_KEEP_MS = 5 * 60_000L;

    private volatile long mKeepMs = DEFAULT_KEEP_MS;
    /** 最近一次记录 / 续用的时刻（单调时钟毫秒；0 = 空会话）。 */
    private long mLastActivityAt;
    /** 面板离屏的时刻（0 = 面板开着）。 */
    private long mClosedAt;
    /** 更早轮次的摘要（被预算挤出的部分，不丢）。 */
    private String mSummary = "";
    private Clock mClock = SYSTEM_CLOCK;

    /** 时钟（单测可替换）。 */
    public interface Clock { long now(); }

    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.nanoTime() / 1000000L; }
    };

    private static volatile ConversationMemory sInstance;

    private final List<Turn> mTurns = new ArrayList<Turn>();

    private ConversationMemory() {}

    public static ConversationMemory getInstance() {
        if (sInstance == null) {
            synchronized (ConversationMemory.class) {
                if (sInstance == null) sInstance = new ConversationMemory();
            }
        }
        return sInstance;
    }

    /** 一问一答。 */
    public static final class Turn {
        public final String question;
        public final String answer;
        /**
         * 这一轮的答复里是否带了<b>不可信的外部内容</b>
         * （识屏 / 读信 / 联网搜索 / 跑腿 / 远程工具回执）。AgentLoop 起手时只要历史里有一条 tainted Turn，本轮就从 tainted 起步。
         */
        public final boolean tainted;
        Turn(String q, String a) { this(q, a, false); }
        Turn(String q, String a, boolean tainted) { this.question = q; this.answer = a; this.tainted = tainted; }

        /** tainted 位，或答复带外部回执前缀（兜底：老调用方没传位时按前缀判，宁严勿宽）。 */
        public boolean isTainted() {
            return tainted || looksExternalReceipt(answer);
        }
    }

    /** LOGIC-1 兜底：这些回执前缀一定含屏幕 / 外部内容。 */
    static final String[] EXTERNAL_RECEIPT_PREFIXES = {"[视觉识屏回执]", "[圈选识屏回执]", "[识屏指代回执]", "[跑腿回执]", "[读信回执]"};

    static boolean looksExternalReceipt(String answer) {
        if (answer == null) return false;
        for (String p : EXTERNAL_RECEIPT_PREFIXES) if (answer.startsWith(p)) return true;
        return false;
    }

    /** 测试用：独立实例（不碰单例）。 */
    static ConversationMemory newForTest() {
        return new ConversationMemory();
    }

    /** 测试用：带假时钟的独立实例。 */
    public static ConversationMemory newForTest(Clock c) {
        ConversationMemory m = new ConversationMemory();
        m.mClock = c;
        return m;
    }

    public void setKeepMs(long ms) { mKeepMs = ms <= 0 ? DEFAULT_KEEP_MS : ms; }

    public long keepMs() { return mKeepMs; }

    /** 历史里是否有任一 tainted Turn。 */
    public synchronized boolean anyTainted() {
        for (Turn t : mTurns) if (t.isTainted()) return true;
        return false;
    }

    /**
     * 记录一轮问答。
     *
     * @param question 用户原始问句（**不要**带检索资料 —— 那只是当轮的辅助材料，
     *                 带进历史会膨胀且误导）
     * @param answer   最终交付给用户的答案（polish 之后的）
     */
    public synchronized void record(String question, String answer) {
        record(question, answer, false);
    }

    /**
     * ★ LOGIC-1：带污点位记录。识屏 / 读信 / 联网搜索 / 跑腿 / 远程工具 / 污染过的 Agent 循环的回执必须传 tainted=true。
     */
    public synchronized void record(String question, String answer, boolean tainted) {
        if (question == null || question.trim().length() == 0) return;
        if (answer == null) answer = "";
        answer = UntrustedTags.strip(answer);   // ★ audit-J 6：回喂会话前剥掉标签（不然下一轮模型更会照抄）
        // ★ device-1102：被回吐守卫拦掉的文字不进历史（问句是热词表 / 提示词回吐 → 整轮不记；答复是回吐 → 只记一句占位）
        if (leakInput(question)) return;
        if (leakOutput(answer)) answer = "（这一轮的回答被安全守卫拦下，未展示）";
        expireIfIdleLocked();   // 超时的旧会话先结束，再开新的一轮
        mClosedAt = 0L;
        mLastActivityAt = mClock.now();
        // ★ 综合包（soul × bike-chooser）：总闸 —— 应用选择尾巴（<app_choice>…store_query…）是界面指令，不进会话历史
        //   （下一轮会原样回灌给模型，也会被会话整理看到）。各调用点已剥，这里兜底。
        answer = com.onesay.assistant.v2.roles.AppChoiceSpec.stripForDisplay(answer);
        if (answer == null) answer = "";
        String stored = com.onesay.assistant.v2.router.ctx.TokenEstimator.clipKeepTail(answer, MAX_ANSWER_TOKENS);
        String q = com.onesay.assistant.v2.router.ctx.TokenEstimator.clipKeepTail(question.trim(), MAX_QUESTION_TOKENS);
        mTurns.add(new Turn(q, stored, tainted));
        trim();
        Listener l = sListener;
        if (l != null) {
            try {
                l.onRecord(question.trim(), tainted);
            } catch (Throwable ignored) {
            }
        }
    }

    /** [soul] 会话监听：记录每句用户原话、会话结束时（面板收起）做一次记忆整理。监听方异常不影响这里。 */
    public interface Listener {
        void onRecord(String question, boolean tainted);

        void onClear(List<Turn> turns);
    }

    private static volatile Listener sListener;

    public static void setListener(Listener l) {
        sListener = l;
    }

    /**
     * 按预算裁剪（都从最旧的开始，挤出去的轮次压进摘要，不直接丢）：
     * 轮数超安全上限 → 折到上限；token 超预算 → 成批折到预算的 {@link #FOLD_LOW_WATER} 以下（至少留最近一轮）。
     */
    private void trim() {
        while (mTurns.size() > MAX_TURNS) {
            foldIntoSummary(mTurns.remove(0));
        }
        if (totalTokens() > mBudgetTokens) {
            int low = (int) (mBudgetTokens * FOLD_LOW_WATER);
            while (totalTokens() > low && mTurns.size() > 1) {
                foldIntoSummary(mTurns.remove(0));
            }
        }
    }

    /** 一轮压成一行（见 {@link #foldLine}）。摘要超 {@link #MAX_SUMMARY_TOKENS} 时丢最早的行。 */
    private void foldIntoSummary(Turn t) {
        if (t == null) return;
        mSummary = appendSummary(mSummary, foldLine(t), MAX_SUMMARY_TOKENS);
        if (t.isTainted()) mSummaryTainted = true;
        mFolded++;
    }

    /** 「问：…（≤40 字）→ 答：…（≤80 字，结论优先）」；含外部内容的标注出来。ContextBudget 请求级夹紧也用它。 */
    static String foldLine(Turn t) {
        return "问：" + head(t.question, 40) + " → 答：" + head(conclusionOf(t.answer), 80) + (t.isTainted() ? "（含外部内容）" : "");
    }

    static String appendSummary(String summary, String line, int maxTokens) {
        String s = summary == null || summary.length() == 0 ? line : summary + "\n" + line;
        while (com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(s) > maxTokens && s.indexOf('\n') > 0) s = s.substring(s.indexOf('\n') + 1);
        if (com.onesay.assistant.v2.router.ctx.TokenEstimator.estimate(s) > maxTokens) {
            s = s.substring(s.length() - com.onesay.assistant.v2.router.ctx.TokenEstimator.suffixLenPublic(s, maxTokens));
        }
        return s;
    }

    /** 累计折叠过的轮数（memdump / 测试用；会话结束清零）。 */
    private int mFolded;

    public synchronized int foldedTurns() { return mFolded; }

    private boolean mSummaryTainted;

    /** Agent 回执的「结论：」之后才是给用户的话；没有就整段。 */
    static String conclusionOf(String a) {
        if (a == null) return "";
        int i = a.lastIndexOf("结论：");
        return i >= 0 ? a.substring(i + 3) : a;
    }

    static String head(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    /** 超长截断（按字符）：保留开头 2/3 + 结尾 1/3（中间用「…」）。旧接口保留给其他调用方；历史入库已改按 token（TokenEstimator.clipKeepTail）。 */
    static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int tail = max / 3;
        int headN = max - tail - 1;
        return s.substring(0, headN) + "…" + s.substring(s.length() - tail);
    }

    /** 历史（摘要 + 各轮问答）的估算 token 数（含每条消息开销）。 */
    static int tokensOf(List<Turn> turns, String summary) {
        int n = summary == null || summary.length() == 0 ? 0 : com.onesay.assistant.v2.router.ctx.TokenEstimator.message(SUMMARY_QUESTION)
                + com.onesay.assistant.v2.router.ctx.TokenEstimator.message(summary);
        for (Turn t : turns) n += turnTokens(t);
        return n;
    }

    static int turnTokens(Turn t) {
        return com.onesay.assistant.v2.router.ctx.TokenEstimator.message(t.question) + com.onesay.assistant.v2.router.ctx.TokenEstimator.message(t.answer);
    }

    private int totalTokens() {
        return tokensOf(mTurns, mSummary);
    }

    /** 当前历史估算 token（memdump / 测试用）。 */
    public synchronized int historyTokens() { return totalTokens(); }

    /** 摘要在快照里的问句占位（让模型知道这是更早的对话）。 */
    public static final String SUMMARY_QUESTION = "（更早的对话摘要，供参考）";

    /** 历史快照（只读副本，供请求组装使用）。有摘要时第一条是摘要。会话超时则先结束。 */
    public synchronized List<Turn> snapshot() {
        expireIfIdleLocked();
        List<Turn> l = new ArrayList<Turn>(mTurns.size() + 1);
        if (mSummary.length() > 0) l.add(new Turn(SUMMARY_QUESTION, mSummary, mSummaryTainted));
        l.addAll(mTurns);
        return l;
    }

    /** 会话里有没有内容（含摘要）。 */
    public synchronized boolean hasContext() {
        expireIfIdleLocked();
        return !mTurns.isEmpty() || mSummary.length() > 0;
    }

    /**
     * ★ device-1102：面板离屏。<b>不再立即清空</b>——{@link #mKeepMs} 内重新唤醒 / 再按录音，仍接着上轮；
     * 超时后由 {@link #expireIfIdle()}（面板离屏时排的延时任务，或下一次读写）结束会话并触发 onClear（soul 记忆整理照旧）。
     */
    public synchronized void onPanelClosed() {
        if (mTurns.isEmpty() && mSummary.length() == 0) return;
        mClosedAt = mClock.now();
    }

    /** 面板重新出现：会话没超时就继续。 */
    public synchronized void onPanelShown() {
        expireIfIdleLocked();
        mClosedAt = 0L;
    }

    /** 超时则结束会话（返回是否结束了）。 */
    public boolean expireIfIdle() {
        List<Turn> old = null;
        synchronized (this) {
            if (!idleExpired()) return false;
            old = drainLocked();
        }
        fireClear(old);
        return true;
    }

    private boolean idleExpired() {
        if (mTurns.isEmpty() && mSummary.length() == 0) return false;
        long now = mClock.now();
        return mLastActivityAt > 0 && now - mLastActivityAt >= mKeepMs;
    }

    /** 锁内调用：超时就清（onClear 通知放到锁外，避免监听方回调进来死锁 —— 这里只在 record / snapshot 里用，先收集再异步通知）。 */
    private void expireIfIdleLocked() {
        if (!idleExpired()) return;
        final List<Turn> old = drainLocked();
        final Listener l = sListener;
        if (l != null && !old.isEmpty()) {
            com.onesay.assistant.v2.util.Threads.start("conv-expire", new Runnable() {
                @Override public void run() {
                    try { l.onClear(old); } catch (Throwable ignored) { }
                }
            });
        }
    }

    private List<Turn> drainLocked() {
        List<Turn> old = new ArrayList<Turn>(mTurns);
        mTurns.clear();
        mSummary = "";
        mSummaryTainted = false;
        mFolded = 0;
        mLastActivityAt = 0L;
        mClosedAt = 0L;
        return old;
    }

    private void fireClear(List<Turn> old) {
        Listener l = sListener;
        if (l != null) {
            try {
                l.onClear(old);
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean leakInput(String q) {
        try {
            return com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().check(q).leak();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean leakOutput(String a) {
        try {
            return a != null && a.length() > 0 && com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().checkOutput(a).leak();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 会话立即结束（「新对话 / 换个话题 / 清掉上下文」）。 */
    public void clear() {
        List<Turn> old;
        synchronized (this) {
            old = drainLocked();
        }
        Listener l = sListener;
        if (l != null) {
            try {
                l.onClear(old);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 摘要字数（memdump 用）。 */
    public synchronized int summaryChars() { return mSummary.length(); }

    public synchronized int size() {
        expireIfIdleLocked();
        return mTurns.size();
    }

    public synchronized boolean isEmpty() {
        expireIfIdleLocked();
        return mTurns.isEmpty() && mSummary.length() == 0;
    }
}
