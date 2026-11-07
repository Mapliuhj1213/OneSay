package com.onesay.assistant.v2.soul;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.s2.MemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.s2.SqliteMemoryStore;

import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/**
 * [soul] 人设 + 长期记忆的<b>全部 Android 挂钩入口</b>（既有文件里只有几行 {@code // [soul]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>SOUL-H1</td><td>S2Hooks.engine</td><td>{@link #noteStore}：S2 记忆库换成长期记忆表（含一次性迁移）</td></tr>
 *   <tr><td>SOUL-H2</td><td>L2LLMCompiler.ask / askStream、AgentLoop</td><td>{@link #withPrefix}（系统提示词最前面的稳定前缀）、{@link #withHits}（当轮 user 消息前的检索块）</td></tr>
 *   <tr><td>SOUL-H3</td><td>S2Hooks.route / claims / executeLocal</td><td>{@link #route} / {@link #claims} / {@link #execute}：叫我X、回答风格、人设提议（确认卡）、回滚、清空对话</td></tr>
 *   <tr><td>SOUL-H4</td><td>ConversationMemory 监听</td><td>会话结束一次整理调用 → 候选进日志</td></tr>
 *   <tr><td>SOUL-H5</td><td>{@link SoulJobService}</td><td>每日整理（充电 + 空闲 + 联网）</td></tr>
 * </table>
 * 所有入口异常时返回「不干预」的值（原流程照旧）；开关 bixby_soul（inject / flush / consolidate，默认开）+ kill 文件 soul.off。
 */
public final class SoulHooks {

    private static final String TAG = "Soul";

    private SoulHooks() {}

    public static final String PREFS = "bixby_soul";
    public static final String KEY_INJECT = "inject";
    public static final String KEY_FLUSH = "flush";
    public static final String KEY_CONSOLIDATE = "consolidate";
    public static final String KEY_BUDGET = "budget";
    public static final String KILL = "soul.off";

    private static volatile Context sApp;
    private static volatile SoulEngine sEngine;
    private static volatile SoulEngine.Injection sLast;
    private static volatile String sFts = "?";
    private static final SoulSession sSession = new SoulSession();

    /** 记下应用 Context（L2LLMCompiler 构造时调；幂等）。 */
    public static void attach(Context ctx) {
        if (ctx == null || sApp != null) return;
        sApp = ctx.getApplicationContext();
        ConversationMemory.setListener(new ConversationMemory.Listener() {
            @Override public void onRecord(String question, boolean tainted) {
                sSession.onUser(question);
            }

            @Override public void onClear(List<ConversationMemory.Turn> turns) {
                onSessionEnd();
            }
        });
        try {
            SoulJobService.schedule(sApp);
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] 每日整理排程失败（忽略）: " + t.getMessage());
        }
    }

    static boolean on(Context ctx, String key) {
        if (ctx == null) return false;
        try {
            if (com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL)) return false;
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(Context ctx, String key, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply();
    }

    public static boolean pref(Context ctx, String key) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, true);
    }

    static boolean locked() {
        return com.onesay.assistant.v2.wake.ScreenOffPolicy.deviceLocked();
    }

    /** 生产引擎（与 S2 同一个 s2_memory.db）。第一次建时：探测 FTS、迁移旧笔记。 */
    public static SoulEngine engine(Context ctx) {
        SoulEngine e = sEngine;
        if (e != null) return e;
        synchronized (SoulHooks.class) {
            if (sEngine != null) return sEngine;
            attach(ctx);
            SqliteMemoryStore legacy = SqliteMemoryStore.get(ctx, SqliteMemoryStore.DB_PROD);
            SqliteSoulRepo repo = new SqliteSoulRepo(legacy.helper());
            SoulEngine en = new SoulEngine(repo, TimeZone.getDefault());
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                en.setBudget(SoulBudget.parse(sp.getString(KEY_BUDGET, null)));
            } catch (Throwable ignored) {
            }
            try {
                String f = repo.meta("fts");
                if (f == null) {
                    f = SoulSchema.probeFts(legacy.helper().getWritableDatabase());
                    repo.setMeta("fts", f);
                }
                sFts = f;
                Log.i(TAG, "[SOUL] SQLite 全文检索能力: " + f + "（首版检索用 Java bigram，不依赖它）");
            } catch (Throwable t) {
                Log.w(TAG, "[SOUL] FTS 探测异常: " + t.getMessage());
            }
            try {
                SoulEngine.MigrationReport r = en.migrate(SoulNoteStore.legacySource(legacy), S2Clock.now());
                if (r.found > 0 || r.error != null) Log.i(TAG, "[SOUL] 旧笔记迁移: " + r);
            } catch (Throwable t) {
                Log.w(TAG, "[SOUL] 迁移异常（下次重试）: " + t.getMessage());
            }
            sEngine = en;
            return en;
        }
    }

    /** [SOUL-H1] S2 记忆库存储。失败时退回旧表（不影响「记一下」）。 */
    public static MemoryStore noteStore(Context ctx, final SqliteMemoryStore legacy) {
        try {
            return new SoulNoteStore(engine(ctx), legacy, new SoulNoteStore.Clock() {
                @Override public long now() { return S2Clock.now(); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] 长期记忆不可用，S2 退回旧表: " + t.getMessage());
            return legacy;
        }
    }

    // ------------------------------------------------------------------ 注入

    /** [SOUL-H2] 系统提示词最前面加人设 / 画像 / 核心记忆（稳定前缀）；关着或异常原样返回。 */
    public static String withPrefix(String system) {
        return withPrefix("?", system);
    }

    // ★ device-1102 问题 3b：注入按调用路径记账（ask / stream / compile / plan / agentloop），memdump 打印「各路径最近一次注入」。
    public static final String PATH_ASK = "ask", PATH_STREAM = "stream", PATH_COMPILE = "compile", PATH_PLAN = "plan", PATH_AGENT = "agentloop";
    private static final java.util.Map<String, String> sPathLog = new java.util.LinkedHashMap<String, String>();
    private static volatile boolean sTestMode;
    private static volatile String sBudgetSpec;

    /**
     * [SOUL-H2] 同上，带调用路径。每次现读仓库（SQLite）+ 现读预算偏好 → 改人设 / 记忆 / 预算后<b>下一轮即生效</b>，不用重启。
     */
    public static String withPrefix(String path, String system) {
        Context ctx = sApp;
        if (!sTestMode && (ctx == null || !on(ctx, KEY_INJECT))) {
            notePath(path, ctx == null ? "未注入（引擎未挂 Context）" : "未注入（开关 inject=off 或 soul.off）");
            return system;
        }
        try {
            SoulEngine en = engine(ctx);
            refreshBudget(ctx, en);
            SoulEngine.Injection inj = en.inject(S2Clock.now(), locked());
            sLast = inj;
            notePath(path, inj.stats());
            Log.i(TAG, "[SOUL] 注入[" + path + "] " + inj.stats() + " ≈" + SoulBudget.estimateTokens(inj.prefix) + " tokens");
            return inj.prefix + (system == null ? "" : system);
        } catch (Throwable t) {
            notePath(path, "注入异常：" + t);
            Log.w(TAG, "[SOUL] 注入异常（本轮不注入）[" + path + "]: " + t);
            return system;
        }
    }

    private static void notePath(String path, String what) {
        synchronized (sPathLog) {
            sPathLog.put(path == null ? "?" : path, new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + what);
        }
    }

    /** 各路径最近一次注入（memdump / 单测用）。 */
    public static java.util.Map<String, String> pathLog() {
        synchronized (sPathLog) {
            return new java.util.LinkedHashMap<String, String>(sPathLog);
        }
    }

    /** 预算偏好热加载：变了才重新解析。 */
    private static void refreshBudget(Context ctx, SoulEngine en) {
        if (ctx == null) return;
        try {
            String spec = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BUDGET, null);
            String old = sBudgetSpec;
            if (spec == null ? old == null : spec.equals(old)) return;
            sBudgetSpec = spec;
            en.setBudget(SoulBudget.parse(spec));
            Log.i(TAG, "[SOUL] 预算热加载: " + en.budget().spec());
        } catch (Throwable ignored) {
        }
    }

    /** 单测：装一个内存引擎（不需要 Context；注入总开）。 */
    public static void installForTest(SoulEngine e) {
        sEngine = e;
        sTestMode = e != null;
        sLast = null;
        synchronized (sPathLog) { sPathLog.clear(); }
    }

    /** [SOUL-H2] 回忆 / 偏好 / 推荐类请求：当轮 user 消息前附检索块（锁屏不附）。 */
    public static String withHits(String query, String userContent) {
        Context ctx = sApp;
        if (ctx == null || !on(ctx, KEY_INJECT)) return userContent;
        try {
            String b = engine(ctx).hitsBlock(query, S2Clock.now(), locked(), sLast);
            if (b == null) return userContent;
            Log.i(TAG, "[SOUL] 检索命中附加 " + MemoryText.len(b) + " 字");
            return b + "————\n" + userContent;
        } catch (Throwable t) {
            return userContent;
        }
    }

    // ------------------------------------------------------------------ 语音命令

    /** [SOUL-H3] null = 不接管。 */
    public static Invocation route(Context ctx, String query) {
        try {
            SoulCommands.Cmd c = SoulCommands.parse(query);
            if (c == null) return null;
            Log.i(TAG, "[SOUL] 接管: " + c);
            JSONObject a = new JSONObject();
            switch (c.type) {
                case CLEAR_CHAT: {
                    sSession.discard();
                    ConversationMemory.getInstance().clear();
                    return reply(query, "这次对话的上下文已经清掉了，接下来我不会再参考刚才说的内容。本机不另存对话记录。");
                }
                case CALL_ME:
                case STYLE:
                    a.put("text", SoulCommands.profileText(c));
                    if (c.type == SoulCommands.Type.CALL_ME) a.put("call", c.value);
                    return new Invocation(SIGN_PROFILE, a, 1.0, Invocation.RouteSource.L0_RULE, query);
                case PERSONA_ADD: {
                    SoulEngine e = engine(ctx);
                    SoulVersion cur = e.activeSoul(S2Clock.now());
                    if (cur.userLocked) return reply(query, "人设是你手动改过的，我不会自动改。要改可以在控制台「记忆 › 人设」里编辑。");
                    String next = e.appendLine(c.value, S2Clock.now());
                    if (MemoryText.len(next) > e.budget().soulMax) return reply(query, "人设已经快到 " + e.budget().soulMax + " 字上限了，加不进去；可以在控制台里精简一下。");
                    a.put("mode", "text");
                    a.put("text", next);
                    a.put("preview", "人设加一条：" + MemoryText.clip(c.value, 40));
                    return new Invocation(SIGN_PROPOSE, a, 1.0, Invocation.RouteSource.L0_RULE, query);
                }
                case PERSONA_NAME:
                    a.put("mode", "name");
                    a.put("text", c.value);
                    a.put("preview", "把我的名字改成「" + c.value + "」");
                    return new Invocation(SIGN_PROPOSE, a, 1.0, Invocation.RouteSource.L0_RULE, query);
                case PERSONA_ROLLBACK:
                    a.put("mode", "rollback");
                    a.put("preview", "人设恢复到上一版");
                    return new Invocation(SIGN_ROLLBACK, a, 1.0, Invocation.RouteSource.L0_RULE, query);
                case PERSONA_RESET:
                    a.put("mode", "reset");
                    a.put("preview", "人设恢复成默认模板（历史版本保留）");
                    return new Invocation(SIGN_ROLLBACK, a, 1.0, Invocation.RouteSource.L0_RULE, query);
                default:
                    return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    public static boolean claims(String query) {
        try {
            return SoulCommands.parse(query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Invocation reply(String query, String text) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", text);
        a.put("query", query == null ? "" : query.trim());
        a.put("s2", "SOUL");
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    public static final String SIGN_PROFILE = S2Capabilities.SIGN_SOUL_PROFILE;
    public static final String SIGN_PROPOSE = S2Capabilities.SIGN_SOUL_PROPOSE;
    public static final String SIGN_ROLLBACK = S2Capabilities.SIGN_SOUL_ROLLBACK;

    /** [SOUL-H3] S2Backend 执行（门禁 / 确认卡已放行）。 */
    public static ExecutionResult execute(Context ctx, Capability cap, JSONObject args) {
        try {
            SoulEngine e = engine(ctx);
            long now = S2Clock.now();
            String msg;
            if (SIGN_PROFILE.equals(cap.sign)) {
                String text = args.optString("text", "");
                SoulEngine.WriteResult w = e.remember(text, Claim.SRC_USER, text, sSession.id(), false, "", "", "", now);
                if (!w.ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "没记下：" + w.reason, "soul profile", cap, args);
                String call = args.optString("call", "");
                if (call.length() > 0) e.setIdentity(SoulTemplate.ID_CALL, call);
                msg = call.length() > 0 ? "好的，以后叫你" + call + "。" : "好的，记住了：" + text + "。";
                if (w.superseded != null) msg += "（之前那条「" + MemoryText.clip(w.superseded.text, 12) + "」不再生效）";
            } else if (SIGN_PROPOSE.equals(cap.sign)) {
                String mode = args.optString("mode", "text");
                if ("name".equals(mode)) {
                    SoulEngine.SoulResult r = e.setIdentity(SoulTemplate.ID_NAME, args.optString("text", ""));
                    if (!r.ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, r.reason, "soul name", cap, args);
                    msg = "好，以后我就叫" + args.optString("text", "") + "。";
                } else {
                    SoulEngine.SoulResult r = e.proposeSoul(args.optString("text", ""), "语音：" + args.optString("preview", ""), false, now);
                    if (!r.ok || !e.approveSoul(r.id, now)) {
                        return ExecutionResult.failed(ErrorCode.EXEC_FAILED, r.reason != null ? r.reason : "人设没改成", "soul propose", cap, args);
                    }
                    msg = "好，我把人设改了：" + args.optString("preview", "") + "。不满意可以说「人设恢复上一版」。";
                }
            } else if (SIGN_ROLLBACK.equals(cap.sign)) {
                if ("reset".equals(args.optString("mode"))) {
                    e.resetSoul(false, now);
                    msg = "人设已恢复成默认模板，之前的版本都还留着。";
                } else if (e.rollbackSoul(now)) {
                    msg = "人设已恢复到上一版。";
                } else {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "没有更早的人设版本可以恢复", "soul rollback", cap, args);
                }
            } else {
                return null;
            }
            Log.i(TAG, "[SOUL] 执行 " + cap.sign + " ok");
            return ExecutionResult.success(cap, args, msg, ExecutionResult.Verification.VERIFIED, "soul");
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "人设 / 记忆异常：" + t.getMessage(), t.toString(), cap, args);
        }
    }

    /** 锁屏时 S2 的回忆 / 列表回答换成「先解锁」（与 ScreenOffPolicy 一致：锁着不读私密记忆）。null = 不替换。 */
    public static String lockedReply(String s2Type) {
        if (!SoulCommands.lockedHides(s2Type)) return null;
        return locked() ? SoulCommands.LOCKED_REPLY : null;
    }

    // ------------------------------------------------------------------ 会话结束整理

    static void onSessionEnd() {
        final Context ctx = sApp;
        if (ctx == null) return;
        final String sid = sSession.id();
        final List<String> utter = sSession.end(locked(), SoulPrompts.FLUSH_MIN_TURNS);
        if (utter == null || !on(ctx, KEY_FLUSH)) return;
        com.onesay.assistant.v2.util.Threads.start("soul-flush", new Runnable() {
            @Override public void run() {
                try {
                    com.onesay.assistant.v2.net.LlmClient client = new com.onesay.assistant.v2.router.L2LLMCompiler(ctx).client();
                    if (!client.hasKey()) return;
                    String body = com.onesay.assistant.v2.net.LlmClient.body(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, 0.1, SoulPrompts.FLUSH_MAX_TOKENS,
                            com.onesay.assistant.v2.net.LlmClient.messages(SoulPrompts.FLUSH_SYSTEM, SoulPrompts.flushUser(utter)), null, false).toString();
                    com.onesay.assistant.v2.net.LlmResponse r = client.post(body);
                    if (r == null || r.content() == null) {
                        Log.w(TAG, "[SOUL] 会话整理调用失败（忽略）: " + (r != null ? r.error : "null"));
                        return;
                    }
                    int n = engine(ctx).ingestCandidates(r.content(), utter, sid, S2Clock.now());
                    Log.i(TAG, "[SOUL] 会话整理：" + utter.size() + " 句 → 候选 " + n + " 条（只进日志）");
                } catch (Throwable t) {
                    Log.w(TAG, "[SOUL] 会话整理异常（忽略）: " + t.getMessage());
                }
            }
        });
    }

    /** 每日整理（SoulJobService 调；也给 debug 驱动用）。 */
    public static SoulEngine.ConsolidateReport consolidate(Context ctx, boolean force) {
        if (!force && !on(ctx, KEY_CONSOLIDATE)) return null;
        SoulEngine e = engine(ctx);
        long now = S2Clock.now();
        if (!force && !e.consolidateDue(now)) return null;
        SoulEngine.ConsolidateReport r = e.consolidate(now);
        Log.i(TAG, "[SOUL] 每日整理: " + r);
        return r;
    }

    // ------------------------------------------------------------------ 调试 / 控制台

    public static SoulEngine.Injection lastInjection() { return sLast; }

    public static String fts() { return sFts; }

    /**
     * ★ device-1102 问题 3b：debug 广播 {@code --es memdump any} → 打印当前会注入的 soul / 记忆摘要与来源路径，逐行 [MEMDUMP]。
     * 只读：不改仓库、不改 sLast；ctx 可为 null（单测）。
     */
    public static List<String> memDump(Context ctx) {
        List<String> out = new java.util.ArrayList<String>();
        try {
            SoulEngine en = engine(ctx);
            if (ctx != null) refreshBudget(ctx, en);
            long now = S2Clock.now();
            String db = "?";
            try { if (ctx != null) db = ctx.getDatabasePath(SqliteMemoryStore.DB_PROD).getAbsolutePath(); } catch (Throwable ignored) { }
            out.add("来源: SQLite " + db + " 表 soul（人设版本）/ " + Claim.T_PROFILE + "（画像）/ " + Claim.T_CORE + "（核心记忆）/ " + Claim.T_LOG + "（日志候选，不注入）");
            out.add("开关: inject=" + (sTestMode || on(ctx, KEY_INJECT)) + " flush=" + (sTestMode || on(ctx, KEY_FLUSH)) + " locked=" + locked()
                    + " budget=" + en.budget().spec() + "（prefs " + PREFS + "/" + KEY_BUDGET + "，每轮现读）");
            SoulVersion sv = en.activeSoul(now);
            out.add("人设: v" + sv.id + " source=" + sv.source + (sv.userLocked ? " userLocked" : "") + " " + MemoryText.len(sv.text) + " 字 | " + MemoryText.clip(sv.text.replace('\n', ' '), 60));
            SoulEngine.Injection inj = en.inject(now, locked());
            out.add("本轮将注入: " + inj.stats() + " ≈" + SoulBudget.estimateTokens(inj.prefix) + " tokens");
            for (String line : inj.prefix.split("\n")) {
                if (line.trim().length() == 0) continue;
                out.add("  | " + MemoryText.clip(line, 60));
            }
            java.util.Map<String, String> pl = pathLog();
            if (pl.isEmpty()) out.add("各路径最近注入: （本进程还没有 LLM 调用）");
            for (java.util.Map.Entry<String, String> e : pl.entrySet()) out.add("路径 " + e.getKey() + ": " + e.getValue());
            ConversationMemory cm = ConversationMemory.getInstance();
            out.add("会话: " + cm.size() + " 轮 摘要 " + cm.summaryChars() + " 字（已折叠 " + cm.foldedTurns() + " 轮）历史约 " + cm.historyTokens()
                    + " token / 预算 " + cm.budgetTokens() + " token 保持 " + (cm.keepMs() / 1000) + " 秒（单答 ≤" + ConversationMemory.MAX_ANSWER_TOKENS
                    + " token、轮数安全上限 " + ConversationMemory.MAX_TURNS + "）");
            out.add("模型窗口: " + com.onesay.assistant.v2.router.ctx.ModelWindows.table().replace('\n', ' '));
            out.add("计数: " + en.counts() + " fts=" + sFts + " session=" + sSession.size());
        } catch (Throwable t) {
            out.add("memdump 异常: " + t);
        }
        return out;
    }

    public static String debugStatus(Context ctx) {
        SoulEngine e = engine(ctx);
        SoulEngine.Injection inj = e.inject(S2Clock.now(), false);
        return "inject=" + on(ctx, KEY_INJECT) + " flush=" + on(ctx, KEY_FLUSH) + " consolidate=" + on(ctx, KEY_CONSOLIDATE)
                + " budget=" + e.budget().spec() + " fts=" + sFts + " counts=" + e.counts() + " | " + inj.stats()
                + " | session=" + sSession.size() + " locked=" + locked();
    }
}
