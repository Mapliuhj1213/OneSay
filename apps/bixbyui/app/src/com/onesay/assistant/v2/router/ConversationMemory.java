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
    /**
     * ★ fix/chat-vision-1124 第 4 条：面板正开着（{@link #onPanelShown} 置上、{@link #onPanelClosed} 放下）。开着就不到期：
     * 人还在对话窗里，没说话也是这次对话。不随 record / 清空改动（它是面板的状态，不是会话的）。
     */
    private boolean mPanelOpen;
    /** 更早轮次的摘要（被预算挤出的部分，不丢）。 */
    private String mSummary = "";
    private Clock mClock = SYSTEM_CLOCK;

    // ---------- ★ fix/1123-longctx-phone：长对话可靠性（总开关 LongCtx.enabled；关 = 下面这些都不参与，行为与改前一致） ----------
    /** 第 3 条：每条用户消息记账（待答 / 在做 / 已结）。 */
    private final com.onesay.assistant.v2.router.longctx.TurnLedger mLedger = new com.onesay.assistant.v2.router.longctx.TurnLedger();
    /** 第 2 条：状态摘要里的「已知事实」（只摘用户原话）。 */
    private final com.onesay.assistant.v2.router.longctx.FactNotes mFacts = new com.onesay.assistant.v2.router.longctx.FactNotes();
    /** 第 4 条：会话外原文存档（按需检索；单例可落盘）。 */
    private final com.onesay.assistant.v2.router.longctx.TurnArchive mArchive = new com.onesay.assistant.v2.router.longctx.TurnArchive();
    /** 与 mTurns 一一对应：每轮在账本里的编号（0 = 总开关关时记的）。 */
    private final List<Integer> mTurnIds = new ArrayList<Integer>();
    /** 第 5 条：逐字历史里被精简过的重活回执（存档里有全文，按需调出时不排除）。 */
    private final java.util.Set<Integer> mCompacted = new java.util.HashSet<Integer>();
    /** 技能「已加载清单」（id → 名称，按最近加载排序）；压缩 / 接力后重新加载一次。 */
    private final java.util.LinkedHashMap<String, String> mSkills = new java.util.LinkedHashMap<String, String>();
    private boolean mSkillReload;
    /** 接力链 id：同一条链（含自动接力开的新会话）里才按需调存档；手动新对话 / 关面板 / 超时 = 新链。 */
    private String mChain = newChain();
    private boolean mRelayed;
    private int mSinceRelay, mRelays, mRetries;
    private volatile int mRelayTurns = DEFAULT_RELAY_TURNS;

    /**
     * 自动接力（「超长会话」）：一段会话记满这么多轮，或者已经折叠进摘要的轮数达到 {@link #RELAY_FOLDED}（历史预算 32k 早被挤满过好几次），
     * 就带接力摘要开新会话（保留最近 {@link #RELAY_KEEP} 轮原文）。普通长度的会话（含 32k 预算内几十轮）不触发，原有「预算内原样保留」不变。
     */
    public static final int DEFAULT_RELAY_TURNS = 100;
    public static final int RELAY_FOLDED = 32;
    public static final int RELAY_KEEP = 2;
    /** 技能清单最多记几条。 */
    static final int MAX_SKILLS = 6;
    private static int sChainSeq;

    private static synchronized String newChain() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + (++sChainSeq);
    }

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
        if (leakOutput(answer)) {
            // ★ 1123d B20：整段判回吐时，先按句子挑出不回吐的部分留下（「Android 8.0.0」这种正常结论别跟着丢，追问才接得上）
            String kept = salvageNonLeak(answer);
            answer = kept != null ? kept : LEAK_PLACEHOLDER;
        }
        expireIfIdleLocked();   // 超时的旧会话先结束，再开新的一轮
        mClosedAt = 0L;
        mLastActivityAt = mClock.now();
        // ★ 综合包（soul × bike-chooser）：总闸 —— 应用选择尾巴（<app_choice>…store_query…）是界面指令，不进会话历史
        //   （下一轮会原样回灌给模型，也会被会话整理看到）。各调用点已剥，这里兜底。
        answer = com.onesay.assistant.v2.roles.AppChoiceSpec.stripForDisplay(answer);
        if (answer == null) answer = "";
        String q = com.onesay.assistant.v2.router.ctx.TokenEstimator.clipKeepTail(question.trim(), MAX_QUESTION_TOKENS);
        int id = 0;
        String forHistory = answer;
        if (com.onesay.assistant.v2.router.longctx.LongCtx.enabled()) {
            // ★ fix/1123-longctx-phone：记账（已结）→ 摘事实 → 原文进存档 → 重活回执精简后才进逐字历史
            com.onesay.assistant.v2.router.longctx.TurnLedger.Entry e = mLedger.close(q, answer, tainted);
            id = e.id;
            mFacts.observe(id, q);
            mArchive.add(mChain, id, q, answer, tainted, mClock.now());
            forHistory = com.onesay.assistant.v2.router.longctx.HeavyIsolation.compact(answer);
            if (forHistory != answer) mCompacted.add(id);
        }
        String stored = com.onesay.assistant.v2.router.ctx.TokenEstimator.clipKeepTail(forHistory, MAX_ANSWER_TOKENS);
        mTurns.add(new Turn(q, stored, tainted));
        mTurnIds.add(id);
        trim();
        if (id > 0) {
            mSinceRelay++;
            if ((mSinceRelay >= mRelayTurns || mFolded >= RELAY_FOLDED) && mTurns.size() > RELAY_KEEP) relayLocked();
        }
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
            foldIntoSummary(mTurns.remove(0), mTurnIds.remove(0));
        }
        if (totalTokens() > mBudgetTokens) {
            int low = (int) (mBudgetTokens * FOLD_LOW_WATER);
            while (totalTokens() > low && mTurns.size() > 1) {
                foldIntoSummary(mTurns.remove(0), mTurnIds.remove(0));
            }
        }
    }

    /**
     * 一轮折出逐字历史。总开关关：压成一行流水账（见 {@link #foldLine}），摘要超 {@link #MAX_SUMMARY_TOKENS} 时丢最早的行。
     * ★ fix/1123-longctx-phone 开：账本里标「已折叠」，摘要按<b>状态</b>重写（事实 / 已结 / 没答完 / 已加载技能，见 LongCtx.renderState）；
     * 原文早已进存档。压缩过一次 → 技能清单下一轮重新加载。
     */
    private void foldIntoSummary(Turn t, int id) {
        if (t == null) return;
        if (t.isTainted()) mSummaryTainted = true;
        mFolded++;
        com.onesay.assistant.v2.router.longctx.TurnLedger.Entry e = id > 0 ? mLedger.byId(id) : null;
        if (e == null) {
            mSummary = appendSummary(mSummary, foldLine(t), MAX_SUMMARY_TOKENS);
            return;
        }
        e.folded = true;
        mCompacted.remove(id);
        mSummary = renderStateLocked();
        if (!mSkills.isEmpty()) mSkillReload = true;
    }

    private String renderStateLocked() {
        List<com.onesay.assistant.v2.router.longctx.TurnLedger.Entry> folded = new ArrayList<com.onesay.assistant.v2.router.longctx.TurnLedger.Entry>();
        java.util.Set<Integer> ids = new java.util.HashSet<Integer>();
        for (com.onesay.assistant.v2.router.longctx.TurnLedger.Entry x : mLedger.entries()) {
            if (!x.folded) continue;
            folded.add(x);
            ids.add(x.id);
        }
        return com.onesay.assistant.v2.router.longctx.LongCtx.renderState(mRelayed, mFacts.from(ids), folded,
                new ArrayList<String>(mSkills.values()), MAX_SUMMARY_TOKENS);
    }

    /**
     * ★ fix/1123-longctx-phone：自动接力 —— 一段会话记满 {@link #mRelayTurns} 轮：除最近 {@link #RELAY_KEEP} 轮外全部折进<b>接力摘要</b>，
     * 当作新的一段会话继续（通知会话监听「上一段结束」，记忆整理照旧；账本编号、存档、接力链不断）；技能清单重新加载。
     */
    private void relayLocked() {
        final List<Turn> old = new ArrayList<Turn>(mTurns);
        mRelayed = true;
        while (mTurns.size() > RELAY_KEEP) foldIntoSummary(mTurns.remove(0), mTurnIds.remove(0));
        mSummary = renderStateLocked();
        mFolded = 0;
        mSinceRelay = 0;
        mRelays++;
        if (!mSkills.isEmpty()) mSkillReload = true;
        final Listener l = sListener;
        if (l != null) {
            com.onesay.assistant.v2.util.Threads.start("conv-relay", new Runnable() {
                @Override public void run() {
                    try { l.onClear(old); } catch (Throwable ignored) { }
                }
            });
        }
    }

    /** 「问：…（≤40 字）→ 答：…（≤80 字，结论优先）」；含外部内容的标注出来。ContextBudget 请求级夹紧也用它。 */
    static String foldLine(Turn t) {
        if (com.onesay.assistant.v2.router.longctx.LongCtx.enabled()) {
            // ★ fix/1123-longctx-phone：请求级夹紧（ContextBudget）折进来的也按状态写：标已结，不写成「问 / 答」流水账
            return "· （已结，不要再答）" + head(t.question, 40) + " → " + head(com.onesay.assistant.v2.router.longctx.TurnLedger.conclusionOf(t.answer), 80)
                    + (t.isTainted() ? "（含外部内容）" : "");
        }
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
        mPanelOpen = false;
        if (mTurns.isEmpty() && mSummary.length() == 0) return;
        mClosedAt = mClock.now();
    }

    /** 面板重新出现：会话没超时就继续。 */
    public synchronized void onPanelShown() {
        expireIfIdleLocked();
        mPanelOpen = true;
        mClosedAt = 0L;
    }

    /**
     * ★ fix/chat-vision-1124 第 4 条：用户还在用（打开「历史对话」页、回到首页）。没到期就把保持期从现在重新算，
     * 免得看着历史、翻着设置，这次对话在身后被清掉（以前第二次打开历史页就空了）。已经到期的照旧结束，不复活。
     *
     * @return 会话还在（有内容）
     */
    public synchronized boolean touch() {
        expireIfIdleLocked();
        if (mTurns.isEmpty() && mSummary.length() == 0) return false;
        mLastActivityAt = mClock.now();
        return true;
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

    /**
     * 到期判断。★ fix/chat-vision-1124 第 4 条：以前只按上次问答算 5 分钟（面板开着、人在看历史也照样清，
     * 历史页每次打开都读快照 → 第二次打开就空了）。现在：面板开着不到期；离屏后从「上次问答 / 用户还在用（{@link #touch}）」
     * 和「面板离屏」两者较晚的那个时刻起算。
     */
    private boolean idleExpired() {
        if (mTurns.isEmpty() && mSummary.length() == 0) return false;
        if (mPanelOpen) return false;
        if (mLastActivityAt <= 0) return false;
        long since = Math.max(mLastActivityAt, mClosedAt);
        return mClock.now() - since >= mKeepMs;
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
        mTurnIds.clear();
        mCompacted.clear();
        mLedger.reset();
        mFacts.reset();
        mSkills.clear();
        mSkillReload = false;
        mChain = newChain();
        mRelayed = false;
        mSinceRelay = 0;
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

    static final String LEAK_PLACEHOLDER = "（这一轮的回答被安全守卫拦下，未展示）";

    /**
     * ★ 1123d B20：回答整段被判回吐时，按句切开，只留本身不回吐的句子；留下的拼起来再判一次，还回吐或一句不剩 = null。
     */
    static String salvageNonLeak(String a) {
        if (a == null || a.length() == 0) return null;
        String[] parts = a.split("(?<=[。！？!?\n])");
        StringBuilder sb = new StringBuilder();
        int dropped = 0;
        for (String p : parts) {
            if (p.trim().length() == 0) continue;
            if (leakOutput(p)) { dropped++; continue; }
            sb.append(p);
        }
        String kept = sb.toString().trim();
        if (kept.length() == 0 || dropped == 0 || leakOutput(kept)) return null;
        return kept;
    }

    static boolean leakOutput(String a) {
        try {
            return a != null && a.length() > 0 && com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().checkOutput(a).leak();
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= ★ fix/1123-longctx-phone：长对话可靠性对外接口 =================

    /** 第 3 条：一条用户消息进来（路由入口调）→ 账本记「在做」。总开关关 / 空话 = 不记。返回编号（0 = 没记）。 */
    public synchronized int beginTurn(String query) {
        if (!com.onesay.assistant.v2.router.longctx.LongCtx.enabled() || query == null || query.trim().length() == 0) return 0;
        expireIfIdleLocked();
        com.onesay.assistant.v2.router.longctx.TurnLedger.Entry cur = mLedger.current();
        if (cur != null && cur.text.equals(query.trim())) return cur.id;   // 同一句重复进来（重试 / 兜底链）不重复记
        return mLedger.open(query).id;
    }

    /** 末尾账本只列最近这么多条以内没答完的。 */
    static final int OPEN_RECENT = 6;

    /** 回指时仍不重复调出的最近几轮（就在眼前，不用调）。 */
    static final int RECENT_KEEP = 4;

    /** 回指词：问到之前说过的事 → 放宽存档检索。 */
    static final java.util.regex.Pattern BACKREF = java.util.regex.Pattern.compile(
            "刚才|刚刚|之前|前面|上面|上次|一开始|最早|你说过|我说过|说过|跟你说|告诉过你|来着|记得|还记得|第.{1,3}(条|个|项|段|张|点|步)");

    /**
     * 第 6 条：本轮用户消息末尾的块（账本 → 按需调出的存档原文 → 守则 → [自检提示] → 最新一句）。
     * 总开关关 / 会话里还没有历史（第一轮）= null（提示词与改前逐字一致）。
     *
     * @param redo 出口自检打回时的提示（{@link com.onesay.assistant.v2.router.longctx.LongCtx#redoNote}），平时 null
     */
    public synchronized String tailFor(String query, String redo) {
        if (!com.onesay.assistant.v2.router.longctx.LongCtx.enabled() || query == null || query.trim().length() == 0) return null;
        expireIfIdleLocked();
        if (mTurns.isEmpty() && mSummary.length() == 0) return null;
        String q = query.trim();
        com.onesay.assistant.v2.router.longctx.TurnLedger.Entry cur = mLedger.current();
        if (cur == null || !cur.text.equals(q)) cur = mLedger.open(q);
        List<Integer> closed = new ArrayList<Integer>();
        List<com.onesay.assistant.v2.router.longctx.TurnLedger.Entry> open = new ArrayList<com.onesay.assistant.v2.router.longctx.TurnLedger.Entry>();
        for (com.onesay.assistant.v2.router.longctx.TurnLedger.Entry e : mLedger.entries()) {
            if (e.id == cur.id) continue;
            if (e.status == com.onesay.assistant.v2.router.longctx.TurnLedger.Status.DONE) closed.add(e.id);
            else if (cur.id - e.id <= OPEN_RECENT) open.add(e);   // 只列最近几条没答完的（更早的写进状态摘要）
        }
        // 第 4 条：按需检索 —— 逐字还完整留在历史里的一般不调（重活精简过的、已折叠的才调）；
        //   问到「之前说过的」（回指词）时，逐字历史里较早的轮次也调（夹在长历史中段的原话模型容易看漏），只排除最近 RECENT_KEEP 轮
        boolean back = BACKREF.matcher(q).find();
        java.util.Set<Integer> exclude = new java.util.HashSet<Integer>();
        for (int i = 0; i < mTurnIds.size(); i++) {
            Integer id = mTurnIds.get(i);
            if (id == null || id <= 0 || mCompacted.contains(id)) continue;
            if (!back || i >= mTurnIds.size() - RECENT_KEEP) exclude.add(id);
        }
        exclude.add(cur.id);
        List<com.onesay.assistant.v2.router.longctx.TurnArchive.Hit> hits = mArchive.search(mChain, q, exclude, back ? 2 : 1, back ? 0.25 : 0.34);
        return com.onesay.assistant.v2.router.longctx.LongCtx.tail(closed, open, hits, cur.id, q, redo);
    }

    /** 出口自检用：之前各轮（旧 → 新）{问句, 答复 / 结论}，含已折叠轮次的结论。 */
    public synchronized List<String[]> priorPairs() {
        List<String[]> out = new ArrayList<String[]>();
        for (com.onesay.assistant.v2.router.longctx.TurnLedger.Entry e : mLedger.entries()) {
            if (e.folded && e.status == com.onesay.assistant.v2.router.longctx.TurnLedger.Status.DONE) out.add(new String[]{e.text, e.conclusion});
        }
        for (Turn t : mTurns) out.add(new String[]{t.question, conclusionOf(t.answer)});
        return out;
    }

    /** 自检重答记一次（memdump / 评测用）。 */
    public synchronized void noteRetry() {
        mRetries++;
    }

    public synchronized int retries() {
        return mRetries;
    }

    public synchronized int relays() {
        return mRelays;
    }

    /** 自动接力的轮数门槛（≤0 = 默认 {@link #DEFAULT_RELAY_TURNS}；最少 8）。折叠轮数门槛固定 {@link #RELAY_FOLDED}。 */
    public void setRelayTurns(int n) {
        mRelayTurns = n <= 0 ? DEFAULT_RELAY_TURNS : Math.max(8, n);
    }

    public int relayTurns() {
        return mRelayTurns;
    }

    /** 技能：本轮实际加载了哪些（id, 名称）→ 进「已加载清单」（最近的排后面，最多 {@link #MAX_SKILLS} 个）。 */
    public synchronized void noteSkills(List<String[]> idName) {
        if (idName == null || !com.onesay.assistant.v2.router.longctx.LongCtx.enabled()) return;
        for (String[] p : idName) {
            if (p == null || p[0] == null) continue;
            mSkills.remove(p[0]);
            mSkills.put(p[0], p.length > 1 && p[1] != null ? p[1] : p[0]);
        }
        while (mSkills.size() > MAX_SKILLS) mSkills.remove(mSkills.keySet().iterator().next());
    }

    /** 技能：压缩 / 接力之后第一次取 → 返回「已加载清单」要重新加载（之后再取为空）。 */
    public synchronized List<String> takeSkillReload() {
        if (!mSkillReload || !com.onesay.assistant.v2.router.longctx.LongCtx.enabled()) return java.util.Collections.emptyList();
        mSkillReload = false;
        return new ArrayList<String>(mSkills.keySet());
    }

    public synchronized List<String> loadedSkills() {
        return new ArrayList<String>(mSkills.keySet());
    }

    /** 账本快照（测试 / memdump）。 */
    public synchronized List<com.onesay.assistant.v2.router.longctx.TurnLedger.Entry> ledger() {
        return mLedger.entries();
    }

    public synchronized List<com.onesay.assistant.v2.router.longctx.FactNotes.Fact> facts() {
        return mFacts.all();
    }

    public com.onesay.assistant.v2.router.longctx.TurnArchive archive() {
        return mArchive;
    }

    public synchronized String chain() {
        return mChain;
    }

    /** 手机上：存档落盘到应用私有目录（FloatingSheetService.onCreate 调）。 */
    public void setArchiveFile(java.io.File f) {
        mArchive.setFile(f);
    }

    /** memdump 一行。 */
    public synchronized String longCtxStats() {
        int open = mLedger.unfinished().size();
        return "长对话：" + (com.onesay.assistant.v2.router.longctx.LongCtx.enabled() ? "开" : "关") + " 账本 " + mLedger.size() + " 条（没答完 " + open + "）事实 "
                + mFacts.size() + " 条 存档 " + mArchive.size() + " 条 技能清单 " + mSkills.keySet() + " 自检重答 " + mRetries + " 次 自动接力 " + mRelays
                + " 次（门槛 " + mRelayTurns + " 轮，本段已 " + mSinceRelay + " 轮）";
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
