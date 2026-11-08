package com.onesay.assistant.v2.router;

import android.content.Context;
import android.util.Log;

/**
 * 统一路由器（全系统唯一的意图入口，保证同 query 路由 100% 确定性）。
 *
 * 2026-10-02 按用户要求重构路由顺序。新链路：
 *
 *   输入指令
 *     ↓
 *   ① 【分类】IntentClassifier 判定 ASK / AGENT / MIXED
 *     ↓
 *   ② 【本地零成本层】0ms、零网络、零 Token
 *        ASK/MIXED → AskEngine 确定性问答（时间/日期/算术/换算/热线）
 *        AGENT     → L0RuleRouter 规则模板匹配
 *     ↓ 未命中
 *   ③ 【缓存查询】L1SkillRouter —— 决定「用缓存还是用 LLM」就在这一步
 *        命中 → 直接复用（0 网络、毫秒级）
 *     ↓ 未命中
 *   ④ 【LLM】L2LLMCompiler 按 kind 走三条通道之一
 *        ASK  → 纯自由问答     AGENT → 能力编译     MIXED → 多步计划
 *     ↓ 成功
 *   ⑤ 【回写缓存】按 kind 与时效性决定是否落库
 *
 * 路由层改造：
 *   - 入口分诊改由 {@link TriageDecider} 决策表负责：ASK 永不进 AgentLoop；
 *   - route()/routeStreaming() 合并为同一条 cascade；
 *   - L1 改为模板化技能缓存：执行成功后才写（{@link #reportExecution}），按分诊标签分组查询。
 *
 * 与旧版的差异（都是用户直接反馈的问题）：
 *   - 旧版无分类，天气问句被本地写死答案拦截 → 现在 AskEngine 不再假装知道天气；
 *   - 旧版 ask.reply 被显式排除在缓存外，同一个知识问题每次都要调 LLM → 现在会缓存；
 *   - 旧版 MIXED 只能执行一件事 → 现在支持多步计划；
 *   - 旧版 LLM 失败就直接返回 null → 现在按 kind 给诚实的兜底说明。
 */
public class UnifiedRouter implements IRouter, IOfflineProbe {

    private static final String TAG = "UnifiedRouter";
    private static volatile UnifiedRouter sInstance;

    private final L0RuleRouter mL0;
    private final L1SkillRouter mL1;
    private final L2LLMCompiler mL2;
    private final android.content.Context mContext;
    private final AgentLoop mAgentLoop;

    public static UnifiedRouter getInstance(Context context) {
        if (sInstance == null) {
            synchronized (UnifiedRouter.class) {
                if (sInstance == null) {
                    sInstance = new UnifiedRouter(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private UnifiedRouter(Context context) {
        this.mL0 = new L0RuleRouter(context);
        this.mL1 = new L1SkillRouter(context);
        this.mL2 = new L2LLMCompiler(context);
        this.mContext = context.getApplicationContext();
        this.mAgentLoop = new AgentLoop(mContext, mL2);
    }

    public L0RuleRouter getL0() { return mL0; }
    public L1SkillRouter getL1() { return mL1; }
    public L2LLMCompiler getL2() { return mL2; }

    /**
     * 取 AgentLoop（新增）。
     *
     * <p>由 UI 层在"判定该走执行体"时使用：单步能力搞不定的复合任务
     * （需要先查再判断、需要多步且后一步依赖前一步结果）。
     */
    public AgentLoop getAgentLoop() { return mAgentLoop; }

    // =====================================================================
    // 入口分诊（TriageDecider）
    // =====================================================================

    /** 一次分诊的完整结果（决策 + 顺手拿到的 L0 结果，避免同一句话被 L0 匹配多次）。 */
    public static final class Triage {
        public final TriageDecider.Decision decision;
        public final Invocation l0;
        /** ★ fix/triage-fast：一句多意图、每个子句都有零 LLM 模板 → 直接给计划（null = 不是）。 */
        public final Invocation plan;
        final long at;

        Triage(TriageDecider.Decision d, Invocation l0) {
            this(d, l0, null);
        }

        Triage(TriageDecider.Decision d, Invocation l0, Invocation plan) {
            this.decision = d;
            this.l0 = l0;
            this.plan = plan;
            this.at = System.currentTimeMillis();
        }

        public String label() { return decision.label.name(); }
    }

    /** 最近一次分诊（入口判定与随后的级联是同一句话，5 秒内复用，保证"只分类一次"）。 */
    // 审计 B-1：句子 + 结果 + 时间合成一个不可变条目一次发布，避免「新句子 + 旧结果」
    private static final long TRIAGE_REUSE_MS = 5000L;
    private final ReuseSlot<Triage> mLastTriage = new ReuseSlot<Triage>(TRIAGE_REUSE_MS);

    /**
     * 对一句话做分诊（规则层 → 可选 LLM 兜底，仅 AMBIGUOUS 时）。
     *
     * <p><b>线程</b>：AMBIGUOUS 且配置了 Key 时会发一次 ≤1.5 s 的网络请求 —— 必须在后台线程调用。
     */
    public Triage triage(String query) {
        if (query == null) query = "";
        Triage cached = mLastTriage.get(query, System.currentTimeMillis());
        if (cached != null) return cached;
        // ★ fix/triage-fast：分诊第 0 层 —— 零 LLM 快路径模板 / 一句多意图拆分（见 FastPathTriage）
        Triage fastT = fastTriage(query);
        if (fastT != null) {
            Log.i(TAG, "[TRIAGE] " + fastT.decision + (fastT.plan != null ? " 计划步数=" + fastT.plan.steps.size() : " l0=" + fastT.l0.sign)
                    + " 语句=" + query);
            mLastTriage.put(query, fastT, fastT.at);
            return fastT;
        }
        Invocation l0 = FastPathTriage.l0WithCore(query, new CompoundSplitter.Resolver() {   // ★ fix/triage-fast：原句没中再试核心短语
            @Override public Invocation resolve(String clause) { return safeL0(clause); }
        });
        // [kernel] 分诊开关信号由插件内核汇总（各插件声明 triageSignal）：
        //   诊断关 → 不产出 DIAGNOSE；免前缀记忆关 → 陈述句不判「记一下」；存图关 → 不判 6i；摘要关 → 不判 5s；S3 与待办都关 → 不判 6t/5t
        com.onesay.assistant.v2.kernel.TriageSwitches sw = com.onesay.assistant.v2.kernel.FeatureKernel.get().triageSwitches(mContext);
        boolean diagOn = sw.diag;
        boolean implicitOn = sw.implicit;
        boolean imgOn = sw.img;
        boolean sumOn = sw.sum;
        boolean s3On = sw.s3;
        TriageDecider.setWebResearch(com.onesay.assistant.v2.agent.LoopConfig.researchInLoop(mContext));   // ★ arch/agent-loop：调研句进 AgentLoop
        TriageDecider.Decision d0 = TriageDecider.decide(query, new TriageDecider.Signals(
                l0 != null ? l0.sign : null, false, false), diagOn, implicitOn, imgOn, sumOn, s3On);
        // 本地能答（时间/日期/算术）：用原句或剥框架后的核心短语
        boolean askLocal = AskEngine.tryAnswer(query) != null
                || (d0.core != null && d0.core.length() > 0 && AskEngine.tryAnswer(d0.core) != null);
        boolean cacheHit = d0.isDo() && mL1.peek(query, d0.label.name());
        TriageDecider.Decision d = TriageDecider.decide(query, new TriageDecider.Signals(
                l0 != null ? l0.sign : null, cacheHit, askLocal), diagOn, implicitOn, imgOn, sumOn, s3On);

        if (d.label == TriageDecider.Label.AMBIGUOUS) {
            // ★ mika-plug（mika.intent）：模糊句改由 IntentGate 判聊 / 查 / 做（照 Mika）；插件关 = 原 LLM 分类兜底
            boolean gate = com.onesay.assistant.v2.mikaplug.MikaPlugs.on(mContext, com.onesay.assistant.v2.mikaplug.MikaPlugs.INTENT);
            TriageDecider.Label byLlm = gate ? intentGate(query) : classifyWithLlm(query);
            if (byLlm != null) {
                d = d.relabel(byLlm, TriageDecider.LLM_MIN_CONFIDENCE, "llm",
                        new TriageDecider.Signals(l0 != null ? l0.sign : null, cacheHit, askLocal));
            } else {
                // 兜底按 ASK：问答不产生任何副作用，判错的代价最低
                d = d.relabel(TriageDecider.Label.ASK, 0.5, "fallback-ask",
                        new TriageDecider.Signals(l0 != null ? l0.sign : null, cacheHit, askLocal));
            }
        }
        Log.i(TAG, "[TRIAGE] " + d + " l0=" + (l0 != null ? l0.sign : "-")
                + " cache=" + cacheHit + " askLocal=" + askLocal + " 语句=" + query);
        Triage t = new Triage(d, l0);
        mLastTriage.put(query, t, t.at);
        return t;
    }

    /** ★ int/1102：分诊第 0 层单独拿出来（纯本地、零 LLM），shouldUseAgentLoop 先用它挡在诊断判断之前。 */
    private Triage fastTriage(String query) {
        return FastPathTriage.triage(query, new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String clause) { return safeL0(clause); }
        });
    }

    /** ★ mika-plug（mika.intent）：IntentGate 判 DISCUSS / READ / ACT → ASK / DO_READ / DO_ACT；判不出返回 null（按原规则兜底 ASK）。 */
    private TriageDecider.Label intentGate(String query) {
        if (!mL2.hasApiKey()) return null;
        final com.onesay.assistant.v2.net.LlmClient c = mL2.client();
        com.onesay.assistant.plug.intent.core.IntentGate.Intent it = com.onesay.assistant.plug.intent.core.IntentGate.assess(new com.onesay.assistant.plug.api.ModelPort() {
            @Override public String complete(String system, String user, int maxTokens, int timeoutMs) {
                try {
                    com.onesay.assistant.v2.net.LlmResponse r = c.withReadTimeout(timeoutMs).withTag("intent").post(com.onesay.assistant.v2.net.LlmClient.body(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, 0.0, maxTokens,
                            com.onesay.assistant.v2.net.LlmClient.messages(system, user), null, false).toString());
                    return r.ok() ? r.content() : null;
                } catch (Throwable t) {
                    return null;
                }
            }
        }, query, null, 1500);
        Log.i(TAG, "[TRIAGE] [MIKA-PLUG] IntentGate → " + (it == null ? "判不出" : it.name()));
        return mapIntent(it);
    }

    public static TriageDecider.Label mapIntent(com.onesay.assistant.plug.intent.core.IntentGate.Intent it) {
        if (it == null) return null;
        switch (it) {
            case READ: return TriageDecider.Label.DO_READ;
            case ACT: return TriageDecider.Label.DO_ACT;
            default: return TriageDecider.Label.ASK;
        }
    }

    /** LLM 分类兜底：温度 0、max_tokens 40、读超时 1.5 s；失败/低置信返回 null。 */
    private TriageDecider.Label classifyWithLlm(String query) {
        if (!mL2.hasApiKey()) return null;
        try {
            com.onesay.assistant.v2.net.LlmClient c = mL2.client().withReadTimeout(1500).withTag("triage");   // ★ 11-03：空响应体重试 1 次（LlmClient.post）
            String body = com.onesay.assistant.v2.net.LlmClient.body(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, 0.0, 40,
                    com.onesay.assistant.v2.net.LlmClient.messages(TriageDecider.llmPrompt(), query), null, false).toString();
            com.onesay.assistant.v2.net.LlmResponse r = c.post(body);
            if (!r.ok()) {
                Log.w(TAG, "[TRIAGE] LLM 分类失败: " + r.error);
                return null;
            }
            double[] conf = new double[1];
            TriageDecider.Label l = TriageDecider.parseLlmLabel(r.content(), conf);
            Log.i(TAG, "[TRIAGE] LLM 分类: " + r.content() + " → " + (l != null ? l : "不采信"));
            return l;
        } catch (Throwable t) {
            Log.w(TAG, "[TRIAGE] LLM 分类异常: " + t.getMessage());
            return null;
        }
    }

    private Invocation safeL0(String q) {
        if (q == null || q.trim().length() == 0) return null;
        try {
            Invocation inv = mL0.route(q);
            // ★ 11-04 ②：「搜索 X」不再零 LLM 拉起百度网页；没明确说浏览器 → 当未命中（调研 / 问答走搜索 API）
            if (inv != null && SearchRouting.dropL0(inv.sign, q)) {
                Log.i(TAG, "[ROUTE-SEARCH] L0 命中可见浏览器搜索但用户没要浏览器 → 改走搜索 API: " + inv.sign);
                return null;
            }
            return inv;
        } catch (Throwable t) {
            // JVM/异常环境下 {app} 槽位解析会抛 UnsatisfiedLinkError —— 当作未命中
            Log.w(TAG, "L0 匹配异常（按未命中处理）: " + t);
            return null;
        }
    }

    /**
     * 判断本轮是否应该交给 {@link AgentLoop}（改为分诊表的函数）。
     *
     * <p>旧判据最后一行 {@code return l0 == null} 把所有 L0 未命中的句子（含全部知识问答）
     * 送进工具循环 —— 审计实测 20 条 ASK 中 19 条进了循环。现在：
     * <ul>
     *   <li>ASK / CLARIFY / 兜底 ASK → <b>永不</b>进循环（AskEngine → L1 → 流式问答）；</li>
     *   <li>MIXED、多步、达成型（下载/搜索）→ 进循环；</li>
     *   <li>单步 DO → L0 或 L1 缓存接得住就走快路径，接不住才进循环（循环里有换路与探针链）。</li>
     * </ul>
     *
     * <p><b>线程</b>：可能触发一次 LLM 分类兜底，调用方须在后台线程调用。
     */
    public boolean shouldUseAgentLoop(String query) {
        if (query == null || query.trim().length() == 0) return false;
        // ★ feat/shortcuts：说的是已存快捷指令（名字 / 存下时的原话）→ 交给 AgentLoop 入口，由 runAgentLoop 开头按快捷指令执行
        //   （排在密钥检查之前：快捷指令本身不需要模型；只有「问模型」这步和失败后的正常模式要）
        if (com.onesay.assistant.v2.shortcut.ShortcutHooks.matches(mContext, query)) return true;
        // [companion] 自家计划 / 时钟 App 在场（装了且同证书）：待办 / 日程 / 闹钟句只走伴侣连接与规则层，永不进 AgentLoop
        //   （排在诊断插件 wantsAgentLoop 与分诊之前；不在场 = 原回落不变）
        if (mContext != null && com.onesay.assistant.v2.companion.CompanionHooks.claims(mContext, query)) return false;
        if (!mL2.hasApiKey()) return false;
        if (mL1.selfTestRoute(query) != null) return false;
        // ★ int/1102：triage-fast 快路径（整句模板 / 零 LLM 多意图计划）优先 —— 命中就永不进 AgentLoop，诊断判断排在它之后
        //   （例：「打开蓝牙」「连不上 WiFi 就打开 WiFi」这类模板句不会因含症状词被诊断插件拉进循环）
        Triage cachedT = mLastTriage.get(query, System.currentTimeMillis());
        boolean fast;
        if (cachedT != null) {
            fast = TriageDecider.isFastPath(cachedT.decision);   // 第 0 层是确定性的：缓存里不是快路径，重算也不是
        } else {
            Triage fastT = fastTriage(query);
            fast = fastT != null;
            if (fast) mLastTriage.put(query, fastT, fastT.at);
        }
        if (fast) return false;
        // ★ audit-J 2：点名的常见 App 没装（「打开抖音」「在小红书搜穿搭」）→ 本地下发 app.launch 出安装卡，不进 AgentLoop
        if (missingAppPlan(query) != null) return false;
        // ★ device-1102：症状句没命中已知案例 → 进 AgentLoop（先搜索、再列计划、再做只读检查），不再走无工具的模板诊断 / ASK
        if (com.onesay.assistant.v2.kernel.FeatureKernel.get().wantsAgentLoop(mContext, query, mL2)) return true;   // 诊断插件：没命中已知案例 → 先搜索
        boolean loop = triage(query).decision.useAgentLoop;
        // [kernel] 插件本地接管的句子永不进 AgentLoop：存图「保存图片」/ 摘要「总结这页」/ S3 定时任务与待办 /
        //   S2 记一下·本地回忆·习惯默认值（回忆是 ASK 通道）/ 诊断症状句与撤销口令（插件见 kernel/plugins）
        if (loop && com.onesay.assistant.v2.kernel.FeatureKernel.get().claims(mContext, query)) return false;
        return loop;
    }

    /** ★ audit-J 2：已装判断（包名 + 标签都查，标签解析得到 = 装了同名 / 变体 App，不拦截）。 */
    private volatile com.onesay.assistant.v2.executor.IntentExecutor mAppCheck;

    Invocation missingAppPlan(String query) {
        if (mContext == null) return null;
        return MissingAppIntercept.plan(query, new MissingAppIntercept.Installed() {
            @Override public boolean pkgInstalled(String pkg) {
                return com.onesay.assistant.v2.roles.RoleHooks.snapshot(mContext).isInstalled(pkg);
            }
            @Override public boolean labelResolves(String appName) {
                com.onesay.assistant.v2.executor.IntentExecutor ie = mAppCheck;
                if (ie == null) mAppCheck = ie = new com.onesay.assistant.v2.executor.IntentExecutor(mContext);
                return ie.resolveComponent(appName) != null;
            }
        });
    }

    /** 单独暴露分类结果，供 UI 展示与自动化测试断言（由新分诊映射回旧三分类）。 */
    public IntentClassifier.Kind classify(String query) {
        return toKind(triage(query).decision);
    }

    static IntentClassifier.Kind toKind(TriageDecider.Decision d) {
        switch (d.label) {
            case MIXED: return IntentClassifier.Kind.MIXED;
            case DO_ACT:
            case DO_READ:
            case CLARIFY: return IntentClassifier.Kind.AGENT;
            default: return IntentClassifier.Kind.ASK;
        }
    }

    // =====================================================================
    // 路由出口（route / routeStreaming 共用同一条级联 cascade）
    // =====================================================================

    @Override
    public Invocation route(String query) {
        final Invocation[] out = { null };
        cascade(query, new StreamHandler() {
            @Override public void onStage(int stage) {}
            @Override public void onPartial(String textSoFar) {}
            @Override public void onFinal(Invocation inv) { out[0] = inv; }
        }, null, false);
        return out[0];
    }

    // ---------- 流式出口（新增） ----------

    /**
     * 流式路由回调。
     *
     * <p><b>线程约定</b>：{@link #onFinal} 与三个流式回调都在**后台线程**触发
     * （就是发起调用那条线程），UI 侧自行切主线程。
     *
     * <p><b>终态保证</b>：无论走哪条分支，{@link #onFinal} 恰好被调用一次。
     * 它可能拿到 {@code null}（未能识别），语义与 {@link #route} 返回 null 一致。
     */
    public interface StreamHandler {
        /** 阶段切换（仅流式问答通道会发），取值见 {@link L2LLMCompiler#STAGE_SEARCH} */
        void onStage(int stage);
        /** 增量全文（仅流式问答通道会发） */
        void onPartial(String textSoFar);
        /** 终态：最终要执行/展示的结果 */
        void onFinal(Invocation inv);
    }

    /** 用户取消令牌：面板被收起时置位，流式读取循环会在下一行退出。 */
    public static final class Cancel implements SseStream.Cancel {
        private volatile boolean mCancelled;
        public void cancel() { mCancelled = true; }
        @Override public boolean isCancelled() { return mCancelled; }
    }

    /**
     * 流式路由：只有 ASK 的 LLM 通道会边走边吐，其余与 {@link #route} 逐行等价（同一条 cascade）。
     */
    public void routeStreaming(final String query, final StreamHandler h, final Cancel cancel) {
        cascade(query, h, cancel, true);
    }

    /**
     * 唯一的路由级联（P1-4 合并）。
     *
     * <pre>
     *   ⓪ 回读拦截 / 自测库 / 纠错语（"不对"→淘汰刚才那条缓存）/ 补槽应答
     *   ① 分诊（TriageDecider，只做一次）
     *   ② ASK：AskEngine → L1(ASK) → LLM 问答（流式或非流式）→ 成功后写 L1(ASK)
     *   ③ DO / MIXED / CLARIFY：L0 只读直出 → AskEngine(时间日期) → L0 → L0(核心短语)
     *        → 关键词倒排 → 缺参追问 → L1(DO) → LLM 编译/计划 → 诚实兜底
     *      LLM 单步结果登记为"待验证"，执行成功后（{@link #reportExecution}）才写 L1。
     * </pre>
     */
    private void cascade(final String query, final StreamHandler h, final Cancel cancel, boolean allowStream) {
        if (query == null || query.trim().length() == 0 || isAckEcho(query)) {
            h.onFinal(null);
            return;
        }
        String mode = allowStream ? " | 流式=on" : "";

        // ---------- ⓪a 自测库（只在自测窗口内生效，生产查询恒为 null） ----------
        Invocation selfTest = mL1.selfTestRoute(query);
        if (selfTest != null) {
            h.onFinal(selfTest);
            return;
        }

        // ---------- ⓪b 用户纠错："不对/错了" → 淘汰 30 秒内刚命中/写入的那条 ----------
        if (com.onesay.assistant.v2.router.cache.SkillCache.isCorrection(query)) {
            boolean evicted = mL1.cache().onUserCorrection();
            Log.i(TAG, "[L1] 用户纠错: " + com.onesay.assistant.v2.util.LogText.u(query) + (evicted ? " → 已淘汰刚才的缓存并加 1 天负缓存" : "（无可淘汰条目）"));
        }

        // ---------- ⓪c 补槽应答（上一轮问过"几点？"，这一轮是"七点"） ----------
        Invocation filled = SlotFiller.getInstance().maybeFill(query);
        if (filled != null) {
            Log.i(TAG, "[ROUTE-2f] 缺参补全（slot filling）: " + filled.sign + " " + com.onesay.assistant.v2.util.LogText.args(filled.args) + mode);
            h.onFinal(filled);
            return;
        }

        // ---------- ⓪c2–⓪e [kernel] 插件本地接管（顺序 = 各插件 TAKEOVER 优先级，复刻迁移前顺序）----------
        //   存图 image.save（100）→ 摘要 screen.summary（200）→ S3 定时任务/待办（300，「记个待办」不能被 S2「记个」吃掉）
        //   → S2 记一下/本地回忆/习惯默认值（400）→ 设置诊断（500，撤销口令 / 症状句）。
        //   直接交付：不查也不写 L1 / ASK 缓存、不登记待验证、不进 AgentLoop；写入类交给执行层（ExecutionGate → 各插件后端）。
        //   日志口径不变：[ROUTE-IMG] / [ROUTE-SUM] / [ROUTE-S3] / [ROUTE-S2] / [ROUTE-DIAG]；
        //   需要的插件先跑一次 triage(query)，只为 [TRIAGE] 日志口径一致（规则判，不触网；5 秒内复用）。
        com.onesay.assistant.v2.kernel.FeatureKernel.Hit take = com.onesay.assistant.v2.kernel.FeatureKernel.get().takeover(mContext, query, mL2);
        if (take != null) {
            if (take.plugin.triageBeforeRouteLog(take.inv)) triage(query);
            Log.i(TAG, take.plugin.routeLog() + take.inv.sign + mode);
            rememberTurn(take.plugin.id(), query, take.inv);   // 本地规则（计划 App / 记一记）的答复也进会话历史，追问有上下文
            markLocalRule(take.plugin.id(), take.inv);          // 对话窗页脚不显示「以上由 AI 生成」
            h.onFinal(take.inv);
            return;
        }

        // ---------- ① 分诊 ----------
        final Triage tr = triage(query);
        final TriageDecider.Decision d = tr.decision;
        final Invocation l0 = tr.l0;
        // ★ fix/triage-fast：多意图零 LLM 计划（「把亮度调低、开勿扰、10 分钟后提醒我」）→ 面板 executePlan 串行执行
        if (tr.plan != null) {
            Log.i(TAG, "[ROUTE-0c] 多意图拆分命中（零 LLM）: " + tr.plan + mode);
            h.onFinal(tr.plan);
            return;
        }
        final IntentClassifier.Kind kind = toKind(d);
        final int historyBefore = ConversationMemory.getInstance().size();
        Log.i(TAG, "[ROUTE-1] 分诊=" + d.label + " 类型=" + kind.name() + " L0命中=" + (l0 != null)
                + " 历史轮数=" + historyBefore + " 语句=" + query + mode);

        // ---------- ①m ★ audit-J 2：点名的常见 App 没装 → app.launch{app,pkg}（不带 role）→ NOT_FOUND_APP → 面板「未安装 X，点此安装」卡 ----------
        if (l0 == null) {
            Invocation miss = missingAppPlan(query);
            if (miss != null) {
                Log.i(TAG, "[ROUTE-APPMISS] 点名 App 未安装，本地下发安装卡: " + miss.args.optString("app") + " pkg=" + miss.args.optString("pkg") + mode);
                h.onFinal(miss);
                return;
            }
        }

        // ---------- ②d [diag] DIAGNOSE 但本地诊断没接住（特权通道不可用等）：走问答但不查/不写 ASK 缓存 ----------
        if (d.label == TriageDecider.Label.DIAGNOSE) {
            Log.i(TAG, "[ROUTE-DIAG] 本地诊断不可用，改走问答（不读写 ASK 缓存）: " + com.onesay.assistant.v2.util.LogText.u(query));
            Invocation llm = mL2.route(query, IntentClassifier.Kind.ASK);
            h.onFinal(llm != null ? llm : fallback(IntentClassifier.Kind.ASK, null, query));
            return;
        }

        // ---------- ② ASK ----------
        if (d.label == TriageDecider.Label.ASK) {
            String askAnswer = AskEngine.tryAnswer(query);
            if (askAnswer == null && d.core != null && d.core.length() > 0) askAnswer = AskEngine.tryAnswer(d.core);
            if (askAnswer != null) {
                Log.i(TAG, "[ROUTE-2] 本地即时问答命中（零成本层）: " + com.onesay.assistant.v2.util.LogText.u(askAnswer));
                h.onFinal(askReply(askAnswer, query));
                return;
            }
            if (historyBefore > 0) {
                Log.i(TAG, "[ROUTE-3] 本轮带 " + historyBefore + " 轮历史 → 绕过缓存，走 LLM 保证上下文一致");
            } else {
                com.onesay.assistant.v2.router.cache.SkillCache.Hit hit = mL1.lookup(query, "ASK");
                if (hit != null && hit.text() != null) {
                    Log.i(TAG, "[ROUTE-3] ASK 缓存命中，跳过 LLM");
                    org.json.JSONObject a = new org.json.JSONObject();
                    try {
                        a.put("text", hit.text());
                        a.put("query", query.trim());
                    } catch (Exception ignored) {}
                    h.onFinal(new Invocation("ask.reply", a, 0.95, Invocation.RouteSource.L1_SKILL, query));
                    return;
                }
            }
            if (allowStream && mL2.canStream()) {
                Log.i(TAG, "[ROUTE-4S] 进入流式问答通道（先检索，再逐字作答）");
                boolean handled = mL2.askStream(query, new L2LLMCompiler.StreamCallback() {
                    @Override public void onStage(int stage) { h.onStage(stage); }
                    @Override public void onPartial(String textSoFar) { h.onPartial(textSoFar); }
                    @Override public void onDone(String answer) {
                        Invocation inv = askReplyFromLlm(answer, query);
                        recordAsk(query, answer, historyBefore);
                        String preview = answer.length() > 80 ? answer.substring(0, 80) + "…" : answer;
                        Log.i(TAG, "[ROUTE-4] LLM 命中（流式）: ask.reply " + answer.length()
                                + " 字（带 " + historyBefore + " 轮历史）: " + preview.replace("\n", " "));
                        h.onFinal(inv);
                    }
                    @Override public void onFail(String reason) {
                        Log.w(TAG, "[ROUTE-4S] 流式失败，走诚实兜底: " + reason);
                        h.onFinal(fallback(IntentClassifier.Kind.ASK, null, query));
                    }
                }, cancel);
                if (handled) return;
            }
            Invocation llm = mL2.route(query, IntentClassifier.Kind.ASK);
            if (llm != null) {
                if ("ask.reply".equals(llm.sign)) recordAsk(query, llm.args.optString("text"), historyBefore);
                h.onFinal(llm);
                return;
            }
            h.onFinal(fallback(IntentClassifier.Kind.ASK, null, query));
            return;
        }

        // ★ int/1102 审计：改值追问但 3 分钟内没有可改的上一轮 → 本地问一句（不进 LLM / AgentLoop）
        if (d.rule != null && d.rule.startsWith("1f-followup-edit")) {
            Log.i(TAG, "[ROUTE-1f] 改值追问没有可引用的上一轮 → 本地追问");
            h.onFinal(askReply(FOLLOWUP_NO_CONTEXT, query));
            return;
        }

        // ---------- ③ DO / MIXED / CLARIFY ----------
        // ③a L0 命中了只读状态查询 → 直接交付（"蓝牙开着吗"）
        if (l0 != null && isReadOnlyQuery(l0.sign)) {
            Log.i(TAG, "[ROUTE-1q] 状态查询直接交付（L0 命中且只读）: " + l0.sign);
            h.onFinal(l0);
            return;
        }
        // ③b 时间/日期这类本地可答的读取（"帮我看看现在几点" → 核心短语"现在几点"）
        if (d.label == TriageDecider.Label.DO_READ || kind == IntentClassifier.Kind.MIXED) {
            String askAnswer = AskEngine.tryAnswer(query);
            if (askAnswer == null && d.label == TriageDecider.Label.DO_READ && d.core != null) {
                askAnswer = AskEngine.tryAnswer(d.core);
            }
            if (askAnswer != null) {
                Log.i(TAG, "[ROUTE-2] 本地即时问答命中（零成本层）: " + com.onesay.assistant.v2.util.LogText.u(askAnswer));
                h.onFinal(askReply(askAnswer, query));
                return;
            }
        }
        // ③c L0 规则（DO 单步）
        if (kind == IntentClassifier.Kind.AGENT && l0 != null && d.label != TriageDecider.Label.CLARIFY) {
            Log.i(TAG, "[ROUTE-2] L0 规则命中（零成本层）: " + l0.sign);
            h.onFinal(l0);
            return;
        }
        // ③d 剥掉礼貌框架后再试一次 L0（"能不能把亮度调高" → "亮度调高"）
        if (kind == IntentClassifier.Kind.AGENT && l0 == null && d.core != null && d.core.length() > 1
                && !d.core.equals(TriageDecider.normalize(query))) {
            Invocation l0core = safeL0(d.core);
            if (l0core != null) {
                Log.i(TAG, "[ROUTE-2] L0 核心短语命中: " + d.core + " → " + l0core.sign);
                h.onFinal(new Invocation(l0core.sign, l0core.args, l0core.confidence,
                        l0core.source, query));
                return;
            }
        }
        // ③e 关键词倒排兜底（只读）
        Invocation kw = keywordFallback(query, kind);
        if (kw != null) {
            Log.i(TAG, "[ROUTE-2k] 关键词倒排命中（零成本层）: " + kw.sign);
            h.onFinal(kw);
            return;
        }
        // ③f 缺参追问（"定个闹钟" → "几点？"，绝不让 LLM 编默认值）
        Invocation clarify = SlotFiller.getInstance().maybeAsk(query);
        if (clarify != null) {
            Log.i(TAG, "[ROUTE-2c] 意图明确但缺必填槽 → 追问（不执行不落缓存）: " + com.onesay.assistant.v2.util.LogText.u(query));
            h.onFinal(clarify);
            return;
        }
        // ③g L1 模板化技能缓存（DO 组）
        if (d.isDo()) {
            com.onesay.assistant.v2.router.cache.SkillCache.Hit hit = mL1.lookup(query, d.label.name());
            if (hit != null) {
                Invocation inv = new Invocation(hit.sign, hit.args, 0.95, Invocation.RouteSource.L1_SKILL, query);
                registerPending(query, d, inv, hit.key, hit.grp, historyBefore);
                Log.i(TAG, "[ROUTE-3] 缓存命中，跳过 LLM: " + hit.sign + " " + com.onesay.assistant.v2.util.LogText.args(hit.args));
                h.onFinal(inv);
                return;
            }
            String neg = mL1.cache().negativeReason(query, d.label.name());
            if (neg != null) Log.i(TAG, "[ROUTE-3] 负缓存在期（" + neg + "），照常交给 LLM 但结果不回写");
        }
        // ③h LLM（AGENT → 编译；MIXED → 计划）
        Invocation llm = mL2.route(query, kind);
        if (llm != null) {
            Log.i(TAG, "[ROUTE-4] LLM 命中: " + (llm.isPlan() ? "多步计划" : llm.sign)
                    + " (src=" + llm.source + ")");
            if (d.isDo() && !llm.isPlan()) registerPending(query, d, llm, null, null, historyBefore);
            h.onFinal(llm);
            return;
        }
        h.onFinal(fallback(kind, l0, query));
    }

    /** ★ int/1102：改值追问没有上一轮时的本地追问话术。 */
    public static final String FOLLOWUP_NO_CONTEXT = "要改哪一项？可以说完整一点，比如「把闹钟改成八点」「媒体音量再大一点」。";

    /** ASK 终稿写入缓存（时效/上下文/拒答等由 SkillCache 判定）。 */
    /** 计划 App（取件码 / 今天 / 存票）、记一记（回忆 / 列表 / 随口记）。 */
    static final java.util.Set<String> REMEMBER_PLUGINS = new java.util.HashSet<String>(java.util.Arrays.asList("planner", "quicknote", "rain"));

    /**
     * （真机：「今天有什么安排」之后追问取件码，模型看不到上一轮）：插件接管的 ask.reply 原来不写 ConversationMemory，
     * 下一轮走 LLM 时没有上下文。只记计划 App / 记一记两类本地规则的答复；记一记的内容来自识屏 → 记为 tainted（LOGIC-1）。
     * 返回是否记了（JVM 单测用）。
     */
    static boolean rememberTurn(String pluginId, String query, Invocation inv) {
        return rememberTurn(ConversationMemory.getInstance(), pluginId, query, inv);
    }

    static boolean rememberTurn(ConversationMemory mem, String pluginId, String query, Invocation inv) {
        try {
            if (mem == null || inv == null || inv.args == null || pluginId == null || !REMEMBER_PLUGINS.contains(pluginId)) return false;
            if (!"ask.reply".equals(inv.sign)) return false;
            String text = inv.args.optString("text", "");
            if (query == null || query.trim().length() == 0 || text.length() == 0) return false;
            mem.record(query, text, "quicknote".equals(pluginId));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本地规则答复的标记键（ask.reply 的 args 里；值 = 插件 id）。 */
    public static final String ARG_LOCAL_RULE = "local_rule";

    /**
     * （真机：计划 App / 记一记的本地答复下面也挂着「以上由 AI 生成，可能包含不准确的信息」）：
     * 这两类插件接管的 ask.reply 是规则拼出来的本机数据，不是 AI 生成的——在 args 里标上来源，界面据此不显示免责行。
     * 返回是否标了（JVM 单测用）。
     */
    static boolean markLocalRule(String pluginId, Invocation inv) {
        try {
            if (inv == null || inv.args == null || pluginId == null || !REMEMBER_PLUGINS.contains(pluginId)) return false;
            if (!"ask.reply".equals(inv.sign)) return false;
            inv.args.put(ARG_LOCAL_RULE, pluginId);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本地规则答复的插件 id；不是 → ""。 */
    public static String localRuleOf(Invocation inv) {
        if (inv == null || inv.args == null || !"ask.reply".equals(inv.sign)) return "";
        return inv.args.optString(ARG_LOCAL_RULE, "");
    }

    private void recordAsk(String query, String answer, int historyBefore) {
        if (answer == null || answer.length() == 0) return;
        org.json.JSONObject a = new org.json.JSONObject();
        try { a.put("text", answer); } catch (Exception ignored) {}
        mL1.recordSuccess(query, "ASK", "ask.reply", a,
                com.onesay.assistant.v2.router.cache.SkillCache.Outcome.success(false), historyBefore > 0);
    }

    // =====================================================================
    // 执行结果回报 → 缓存写入/回放计数（写入点在执行之后）
    // =====================================================================

    /** 一次"已路由、待执行"的单步调用（来自 LLM 编译或缓存命中）。 */
    private static final class Pending {
        final String query;
        final String label;
        final String sign;
        final org.json.JSONObject args;
        final String cacheKey;   // 非 null = 来自缓存命中
        final String cacheGrp;
        final int historyBefore;
        final long at;

        Pending(String query, String label, String sign, org.json.JSONObject args,
                String cacheKey, String cacheGrp, int historyBefore) {
            this.query = query;
            this.label = label;
            this.sign = sign;
            this.args = args;
            this.cacheKey = cacheKey;
            this.cacheGrp = cacheGrp;
            this.historyBefore = historyBefore;
            this.at = System.currentTimeMillis();
        }
    }

    private final Object mPendingLock = new Object();
    private Pending mPending;
    /** 待执行登记的有效期（确认卡最长等待也在这个量级内）。 */
    private static final long PENDING_TTL_MS = 3 * 60 * 1000L;

    private void registerPending(String query, TriageDecider.Decision d, Invocation inv,
                                 String cacheKey, String cacheGrp, int historyBefore) {
        if (inv == null || inv.sign == null) return;
        if ("ask.reply".equals(inv.sign) || "chat.reply".equals(inv.sign)
                || SlotFiller.SIGN_CLARIFY.equals(inv.sign)) return;
        org.json.JSONObject argsCopy;
        try {
            argsCopy = inv.args != null ? new org.json.JSONObject(inv.args.toString()) : new org.json.JSONObject();
        } catch (Exception e) {
            argsCopy = new org.json.JSONObject();
        }
        synchronized (mPendingLock) {
            mPending = new Pending(query, d.label.name(), inv.sign, argsCopy, cacheKey, cacheGrp, historyBefore);
        }
    }

    /**
     * 执行层回报（UI 在 {@code executeInternal} 返回后调用；只用 ExecutionResult 已有字段）。
     *
     * <ul>
     *   <li>LLM 编译出的单步调用执行成功 → 写入 L1（参数落地校验、时效、风险由 SkillCache 判）；</li>
     *   <li>缓存命中的回放 → 成功清零失败计数，失败计数，连续 2 次失败隔离；</li>
     *   <li>用户拒绝/取消：不会走到这里（确认卡拒绝不执行），即使走到也不计失败。</li>
     * </ul>
     */
    public void reportExecution(String query, String sign, com.onesay.assistant.v2.executor.ExecutionResult r) {
        if (query == null || sign == null || r == null) return;
        Pending p;
        synchronized (mPendingLock) {
            p = mPending;
            if (p == null || !p.sign.equals(sign) || !p.query.equals(query)
                    || System.currentTimeMillis() - p.at > PENDING_TTL_MS) {
                return;
            }
            mPending = null;
        }
        com.onesay.assistant.v2.router.cache.SkillCache.Outcome o = outcomeOf(r);
        if (p.cacheKey != null) {
            mL1.cache().onReplayResult(p.cacheKey, p.cacheGrp, o);
            Log.i(TAG, "[L1] 回放结果: " + sign + " ok=" + o.isSuccess() + " code=" + o.code);
        } else {
            mL1.recordSuccess(p.query, p.label, p.sign, p.args, o, p.historyBefore > 0);
        }
    }

    static com.onesay.assistant.v2.router.cache.SkillCache.Outcome outcomeOf(com.onesay.assistant.v2.executor.ExecutionResult r) {
        String code = r.code != null ? r.code.name() : null;
        boolean denied = "RISK_REJECTED".equals(code) || "USER_REJECTED".equals(code) || "CANCELLED".equals(code);
        return new com.onesay.assistant.v2.router.cache.SkillCache.Outcome(r.ok, code, r.verified, denied);
    }

    // =====================================================================
    // AgentLoop 包装 —— 负缓存提示 + 确定性单步计划回写为技能
    // =====================================================================

    /**
     * 跑 AgentLoop（UI 应调用本方法而不是直接 {@code getAgentLoop().run}）。
     *
     * <ul>
     *   <li>开跑前：若同一句话 10 分钟内刚失败过，把"上次试过什么"作为提示交给循环，避免原地重复；</li>
     *   <li>收工后：DO 单步任务、恰好一次成功的工具调用（非探针、未经确认卡）→ 作为技能写入 L1，
     *       下次同一句话（或同模板换槽值）直接走缓存，不再多轮 LLM；</li>
     *   <li>收工后：一步都没成功且答复是"做不到" → 记 10 分钟负缓存（带已尝试步骤）。</li>
     * </ul>
     */
    public AgentLoop.Result runAgentLoop(String query, final AgentLoop.Callback cb, final int maxRounds) {
        // ★ feat/shortcuts：命中已存快捷指令 → 逐步执行（同一个宿主回调：确认卡 / ask_user / 取消照旧）；
        //   某步失败时快捷指令自己转正常模式（下面的 Normal：直接跑 AgentLoop，不写 L1 技能缓存）。没命中返回 null，照常往下。
        AgentLoop.Result sc = com.onesay.assistant.v2.shortcut.ShortcutHooks.tryRun(mContext, query, cb, new com.onesay.assistant.v2.shortcut.ShortcutHooks.Normal() {
            @Override public AgentLoop.Result run(String prompt) {
                com.onesay.assistant.v2.agenttools.AgentToolset.prepare(prompt, TriageDecider.Label.DO_ACT.name());
                return mAgentLoop.run(prompt, cb, maxRounds, null);
            }
        });
        if (sc != null) return sc;
        Triage tr = triage(query);
        TriageDecider.Decision d = tr.decision;
        String cacheLabel = d.isDo() ? d.label.name() : "DO_ACT";
        String prior = mL1.cache().negativeReason(query, cacheLabel);
        int historyBefore = ConversationMemory.getInstance().size();
        com.onesay.assistant.v2.agenttools.AgentToolset.prepare(query, d.label.name());   // [arch-a] 分诊结论递给工具层（GUI 档折叠判定用；同一线程）
        AgentLoop.Result r = mAgentLoop.run(query, cb, maxRounds, prior);
        com.onesay.assistant.v2.shortcut.ShortcutHooks.rememberAgentRun(query, r);   // ★ feat/shortcuts：结果卡「存为快捷指令」用这次实际调用的步骤
        try {
            learnFromAgentLoop(query, d, r, historyBefore, cacheLabel);
        } catch (Throwable t) {
            Log.w(TAG, "[L1] AgentLoop 结果学习异常: " + t.getMessage());
        }
        return r;
    }

    private void learnFromAgentLoop(String query, TriageDecider.Decision d, AgentLoop.Result r,
                                    int historyBefore, String cacheLabel) {
        if (r == null) return;
        // [s1] 用户取消（胶囊停止键 / ✕）不是"做不到"：不写技能、也不记负缓存
        if (AgentLoop.REASON_CANCELLED.equals(r.reason)) return;
        AgentLoop.Step only = AgentLoop.deterministicSingleStep(r);
        if (only != null && com.onesay.assistant.v2.agenttools.AgentToolset.isMetaTool(only.tool)) only = null;   // [arch-a] gui.unfold 入口不能当技能回放
        if (only != null && d.isDo() && !d.multiStep) {
            mL1.recordSuccess(query, d.label.name(), only.tool, only.args,
                    com.onesay.assistant.v2.router.cache.SkillCache.Outcome.success(only.verified), historyBefore > 0);
            return;
        }
        boolean anyOk = false;
        for (AgentLoop.Step s : r.steps) if (s.ok) { anyOk = true; break; }
        boolean appMissing = false;   // ★ 11-04 I：没装应用不记负缓存（用户装好马上再说，不能被 10 分钟拒之门外）
        for (AgentLoop.Step s : r.steps) if (s.observation != null && s.observation.contains("NOT_FOUND_APP")) { appMissing = true; break; }
        if (!anyOk && !appMissing && (r.answer == null || com.onesay.assistant.v2.router.cache.SkillCache.looksLikeRefusal(r.answer))) {
            mL1.cache().recordRefusal(query, cacheLabel, AgentLoop.attemptedSummary(r.steps, r.reason),
                    com.onesay.assistant.v2.router.cache.SkillCache.TTL_NEGATIVE);
            Log.i(TAG, "[L1] AgentLoop 未达成，记 10 分钟负缓存（下次带上已试步骤换路）: " + com.onesay.assistant.v2.util.LogText.u(query));
        }
    }

    /** 流式/非流式两条路共用的 ask.reply 构造（来源标 L2_LLM，便于日志区分）。 */
    private static Invocation askReplyFromLlm(String answer, String query) {
        org.json.JSONObject args = new org.json.JSONObject();
        try {
            L2LLMCompiler.putTextAndChoice(args, answer);   // [r2/bike-chooser] 尾巴 → args.app_choice
            args.put("query", query != null ? query.trim() : "");
        } catch (Exception ignored) {}
        return new Invocation("ask.reply", args, 0.95, Invocation.RouteSource.L2_LLM, query);
    }

    /**
     * LLM 拿不到结果时的兜底。原则：**宁可如实说不知道，也不编造**。
     *
     * @param l0  本地规则结果（MIXED 场景下至少能完成"动作"那一半）
     */
    private Invocation fallback(IntentClassifier.Kind kind, Invocation l0, String query) {
        if (kind == IntentClassifier.Kind.MIXED && l0 != null) {
            Log.i(TAG, "[ROUTE-5] 混合任务 LLM 不可用，退化为执行本地可识别部分: " + l0.sign);
            return l0;
        }

        if (kind == IntentClassifier.Kind.ASK) {
            String msg = mL2.hasApiKey()
                    ? "网络暂时不可用，这个问题我先答不上来，等联网后再问我一次。"
                    : "还没有配置大模型密钥，暂时只能回答时间、日期、算术这类本地问题。";
            Log.i(TAG, "[ROUTE-5] 问答通道不可用，返回诚实兜底");
            return askReply(msg, query);
        }
        // ★ int/1102 release 测试：没配密钥时，本地接不住的动作（「给老王发微信说我到了」要一步步操作手机）
        //   说清楚是缺大模型密钥，而不是「这句我没听懂」（用户会以为是没听清、反复重说）
        if (!mL2.hasApiKey()) {
            Log.i(TAG, "[ROUTE-5] 动作需要大模型但没配密钥 → 说明缺密钥（类型=" + kind.name() + "）");
            return askReply(noKeyActLine(query), query);
        }

        Log.w(TAG, "全线未命中任何可用能力: " + com.onesay.assistant.v2.util.LogText.u(query) + " (类型=" + kind.name() + ")");
        return null;
    }

    /** ★ int/1102：没配大模型密钥时，本地接不住的动作句的回复（纯函数，JVM 单测）。不复述原话（可能很长 / 含隐私）。 */
    public static String noKeyActLine(String query) {
        String q = query == null ? "" : query;
        String what = q.matches(".*(微信|qq|QQ|钉钉|飞书|发消息|发条消息|发个消息).*") ? "发微信这类要我一步步操作手机的事"
                : q.matches(".*(买|下单|购物车|淘宝|京东|拼多多).*") ? "购物这类要我一步步操作手机的事"
                : "这件事要我一步步操作手机";
        return what + "，需要先配置大模型密钥（设置 › 模型与密钥 › API 密钥 › DeepSeek）。"
                + "打开应用、闹钟、音量、亮度、记一记这类本地指令现在就能用。";
    }

    private static Invocation askReply(String text, String query) {
        org.json.JSONObject args = new org.json.JSONObject();
        try {
            args.put("text", text);
            args.put("query", query != null ? query.trim() : "");
        } catch (Exception ignored) {}
        return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    // ---------- 唤醒热路径：纯离线级联（Ask → L0 → L1），绝不触网 ----------

    /**
     * 供唤醒链路的实时热路径使用（VAD 静音断句、一句式直说切分）。
     * 绝不含 L2：麦克风线程上不允许出现网络等待。
     */
    public Invocation routeOffline(String query) {
        if (query == null || query.trim().length() == 0) return null;
        if (isAckEcho(query)) return null;

        String askAnswer = AskEngine.tryAnswer(query);
        if (askAnswer != null) return askReply(askAnswer, query);

        Invocation inv = mL0.route(query);
        if (inv != null) return inv;

        return mL1.route(query, null);
    }

    /**
     * 判断一段转写是否"值得提前断句执行"。
     *
     * 背景：天气、知识这类问题必须走 LLM，离线探针天然判不了它们能否处理，
     * 于是旧逻辑会把「嗨比西比，今天天气怎么样」一直拖到 6.5 秒超时才处理。
     * 现在：离线能处理 → 立即执行；离线不能处理但**明显是一个完整问句** → 也允许断句，
     * 让它尽早进入 LLM 通道，用户不必干等。
     */
    public boolean isCommitWorthy(String candidate) {
        if (candidate == null) return false;
        if (canHandle(candidate)) return true;
        return candidate.length() >= 4 && IntentClassifier.looksLikeQuestion(candidate);
    }

    /** 应答音回读物理拦截（返回 true 表示应直接丢弃该话语） */
    private static boolean isAckEcho(String query) {
        if (com.onesay.assistant.v2.wake.WakeWordStripper.isAckSoundUtterance(query)) {
            Log.i(TAG, "拦截自身应答回读词，直接丢弃: " + com.onesay.assistant.v2.util.LogText.u(query));
            return true;
        }
        return false;
    }

    // ---------- 方案 A：状态查询直接交付 + 关键词倒排兜底 ----------

    /**
     * 判断一个能力是否为「只读状态查询」—— 只读查询即使句子带疑问词，
     * 也应当由本地规则直接交付，而不是被分类器转成 MIXED 交 LLM。
     *
     * <p>判据：sign 以状态查询/只读后缀结尾。这些能力只读设备状态、
     * 无副作用，直接交付零风险。
     *
     * <p><b>排除 time.now / date.today</b>：时间、日期是 {@code AskEngine}
     * 的专属领域（本地毫秒直出、措辞更丰富），不该被系统能力抢占。
     * 否则「现在几点」会跳过 AskEngine 被 time.now 直接交付，破坏既有分层。
     */
    private static boolean isReadOnlyQuery(String sign) {
        if (sign == null) return false;
        if (sign.equals("time.now") || sign.equals("date.today")) return false;
        return sign.endsWith(".state")
                || sign.endsWith(".get")
                || sign.endsWith(".check")
                || sign.endsWith(".query")
                || sign.endsWith(".list");
    }

    /**
     * 关键词倒排兜底：L0 say 模板未命中时，用倒排索引召回能力。
     *
     * <p>只在「命中唯一且明确」时交付 —— 第一名的命中词数须严格高于第二名
     * （避免「打开数据」这种 wifi/数据 都沾边的歧义）。命中多个打平或为空，
     * 返回 null 交回 LLM 精选。只交付**只读查询**，有副作用的能力不在这里
     * 冒进执行（有副作用的误判代价高，宁交 LLM 确认）。
     *
     * <p><b>为什么 ASK 分类也兜</b>：口语变体（「现在几点了」「蓝牙开了没」）
     * 因 say 模板是精确串匹配而漏掉，分类器看到"几/吗"又判成 ASK，
     * 最终掉进 LLM 甚至被编造答案。只读状态查询是零副作用的，被倒排
     * 唯一强命中时直接交付，比交给 LLM 更准更快。
     */
    private Invocation keywordFallback(String query, IntentClassifier.Kind kind) {
        // ASK / AGENT / MIXED 都允许兜底只读查询；但 ASK 时要求更强证据（命中词数更高）
        java.util.List<com.onesay.assistant.v2.capability.KeywordIndex.Hit> hits =
                com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().keywordHits(query);
        if (hits.isEmpty()) return null;

        com.onesay.assistant.v2.capability.KeywordIndex.Hit top = hits.get(0);
        int minCount = (kind == IntentClassifier.Kind.ASK) ? 3 : 2;
        // 命中词数须达到门槛才可信（单个词太泛，如"打开"）
        if (top.count < minCount) return null;
        // 唯一性：第一名必须严格压过第二名
        if (hits.size() > 1 && hits.get(1).count >= top.count) return null;

        com.onesay.assistant.v2.capability.Capability cap =
                com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance().describe(top.sign);
        if (cap == null) return null;
        // 只交付只读查询；有副作用的仍交 LLM 走完整门禁
        if (!isReadOnlyQuery(cap.sign)) return null;

        Log.i(TAG, "[KW-INDEX] 倒排唯一命中: " + cap.sign + " (词=" + top.words + ")");
        return new Invocation(cap.sign, new org.json.JSONObject(),
                0.85, Invocation.RouteSource.L0_RULE, query);
    }

    // ---------- IOfflineProbe ----------

    @Override
    public boolean canHandle(String utterance) {
        return routeOffline(utterance) != null;
    }

    @Override
    public int literalLength(String utterance) {
        if (utterance == null) return 0;
        int l0 = mL0.getQueryLiteralLength(utterance);
        if (l0 > 0) return l0;
        // Ask 问答按"整句即字面量"计（AskEngine 为精确短语/正则匹配，无模糊膨胀）
        if (AskEngine.tryAnswer(utterance) != null) {
            return L0RuleRouter.normalizeUtterance(utterance).length();
        }
        return 0;
    }
}
