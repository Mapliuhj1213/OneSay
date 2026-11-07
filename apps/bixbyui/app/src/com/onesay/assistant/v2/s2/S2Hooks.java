package com.onesay.assistant.v2.s2;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver;
import com.onesay.assistant.v2.vision.screenref.ScreenAction;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.TimeZone;

/**
 * [s2] S2「记得住」的<b>全部挂钩入口</b>（写法同 S1Hooks / ScreenRefHooks：既有文件里只有几行 {@code // [s2]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>S2-H1</td><td>UnifiedRouter.cascade：补槽应答之后、分诊之前</td><td>{@link #route}：S2 接管 → 本地回答（ask.reply）或 S2/习惯能力调用</td></tr>
 *   <tr><td>S2-H2</td><td>UnifiedRouter.shouldUseAgentLoop：分诊之后</td><td>{@link #claims}：S2 接管的句子永不进 AgentLoop</td></tr>
 *   <tr><td>S2-H3</td><td>FloatingSheetService.onSubmitCommand：识屏指代拦截之前</td><td>{@link #bypassScreenRef}：「删掉停车那条」不让识屏把「那条」当指代</td></tr>
 *   <tr><td>S2-H4</td><td>FloatingSheetService.dispatch：注册表查不到能力时</td><td>{@link #describe}</td></tr>
 *   <tr><td>S2-H5</td><td>FloatingSheetService：单步执行 / AgentLoop 每步执行之后</td><td>{@link #onExecuted}：习惯默认值从真实执行里学</td></tr>
 *   <tr><td>S2-H6</td><td>UnifiedExecutor 后端列表</td><td>{@code S2Backend}（executor="s2"）→ {@link #executeLocal}</td></tr>
 *   <tr><td>S2-H7</td><td>AgentLoop：工具导出 / 能力查找 / 执行</td><td>{@link #appendTools} / {@link #describeForAgent} / {@link #executeForAgent}（只读 memory.search）</td></tr>
 *   <tr><td>S2-H8</td><td>S1 ClipboardWatcher：芯片 → 动作</td><td>{@link #chipAction}：打车/导航芯片按习惯默认 App 改道</td></tr>
 *   <tr><td>S2-H9</td><td>FloatingSheetService.executeCapability 成功后</td><td>{@link #onNoteAdded}：记下一条（含 round3 免前缀）→「撤销」芯片</td></tr>
 * </table>
 * 所有入口在开关关闭或异常时返回「不干预」的值（原流程照旧）。
 */
public final class S2Hooks {

    private static final String TAG = "S2";

    private S2Hooks() {}

    private static volatile S2Engine sEngine;

    /** 生产引擎（独立库 s2_memory.db）。 */
    public static S2Engine engine(Context ctx) {
        S2Engine e = sEngine;
        if (e != null) return e;
        synchronized (S2Hooks.class) {
            if (sEngine == null) {
                SqliteMemoryStore st = SqliteMemoryStore.get(ctx, SqliteMemoryStore.DB_PROD);
                // [soul] 笔记存进长期记忆表（memory_core / user_profile / daily_log，含旧笔记一次性迁移）；账目仍在旧表
                S2Engine e2 = new S2Engine(com.onesay.assistant.v2.soul.SoulHooks.noteStore(ctx, st), st, TimeZone.getDefault());
                com.onesay.assistant.v2.roles.RoleHooks.attach(ctx, e2);   // [roles] 角色目录 + 设备快照
                attachQuickNote(ctx.getApplicationContext(), e2);
                sEngine = e2;
            }
            return sEngine;
        }
    }

    /** ★ R10（D10）：S2 看得到记一记独立库的条目；「清空记忆」连记一记一起清。 */
    private static void attachQuickNote(final Context app, S2Engine e) {
        e.setExtraNotes(new S2Engine.ExtraNotes() {
            @Override public java.util.List<MemoryEntry> notes() {
                if (!com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.on(app)) return new java.util.ArrayList<MemoryEntry>();
                return com.onesay.assistant.v2.quicknote.QuickNoteHooks.book(app).s2View();
            }

            @Override public int clear() {
                return com.onesay.assistant.v2.quicknote.QuickNoteHooks.clearAll(app);
            }
        });
    }

    /** ★ R10：记一记要先接的句式（随口记 / 按类别列 / 我的取件码是多少 / 车停哪了）—— 记一记开着时 S2 让出。 */
    static boolean yieldToQuickNote(Context ctx, String query) {
        try {
            return com.onesay.assistant.v2.quicknote.QuickNoteIntent.beforeS2(query) && com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH.on(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自测引擎（隔离库 s2_memory_selftest.db，每次先清空）。 */
    public static S2Engine selfTestEngine(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return null;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        SqliteMemoryStore st = SqliteMemoryStore.get(ctx, SqliteMemoryStore.DB_SELFTEST);
        st.clear(null);
        st.clearHabits();
        S2Engine e = new S2Engine(st, st, TimeZone.getDefault());
        com.onesay.assistant.v2.roles.RoleHooks.attach(ctx, e);
        return e;
    }

    // ------------------------------------------------------------------ 路由

    /** [S2-H1] 后台线程。null = 不接管。 */
    public static Invocation route(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return null;
            S2Engine.Flags f = S2Settings.flags(ctx);
            if (!f.any()) return null;
            if (yieldToQuickNote(ctx, query)) {
                Log.i(TAG, "[S2] 让给记一记: " + com.onesay.assistant.v2.util.LogText.u(query));
                return null;
            }
            if (f.memory) {
                Invocation si = com.onesay.assistant.v2.soul.SoulHooks.route(ctx, query);   // [soul] 叫我X / 回答风格 / 人设 / 清空对话
                if (si != null) return si;
            }
            S2Engine.Result r = engine(ctx).handle(query, S2Clock.now(), f);
            if (r == null) return null;
            Log.i(TAG, "[S2] 接管: " + r);
            if (r.isLocalReply()) {
                String lr = com.onesay.assistant.v2.soul.SoulHooks.lockedReply(r.type.name());   // [soul] 锁屏不读私密记忆
                if (lr != null) {
                    Log.i(TAG, "[S2] 锁屏中，" + r.type + " 改答「先解锁」");
                    JSONObject a = new JSONObject();
                    try {
                        a.put("text", lr);
                        a.put("query", query.trim());
                        a.put("s2", r.type.name());
                    } catch (Exception ignored) {
                    }
                    return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
                }
            }
            return toInvocation(r, query);
        } catch (Throwable t) {
            Log.w(TAG, "[S2] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    static Invocation toInvocation(S2Engine.Result r, String query) {
        if (r.isLocalReply()) {
            JSONObject a = new JSONObject();
            try {
                a.put("text", r.reply);
                a.put("query", query.trim());
                a.put("s2", r.type.name());
                if (r.ui != null) a.put("role_ui", r.ui);   // [roles] 选择芯片 / 安装提示（RoleHooks.onReplyShown 渲染）
            } catch (Exception ignored) {
            }
            // 本地回答：不经 L1（不写 ASK 缓存 —— 记忆会变），不进 AgentLoop
            return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
        }
        return new Invocation(r.sign, r.args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /** [S2-H2] 后台线程：S2 是否接管这句话（接管 = 不进 AgentLoop）。 */
    public static boolean claims(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return false;
            S2Engine.Flags f = S2Settings.flags(ctx);
            if (!f.any()) return false;
            if (yieldToQuickNote(ctx, query)) return false;
            if (f.memory && com.onesay.assistant.v2.soul.SoulHooks.claims(query)) return true;   // [soul]
            S2Engine.Result r = engine(ctx).handle(query, S2Clock.now(), f);
            if (r != null) Log.i(TAG, "[S2] 本地接管，不进 AgentLoop: " + r.type);
            return r != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [S2-H3] 主线程（纯正则，不读库）。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        try {
            if (ctx == null) return false;
            boolean b = S2Engine.bypassScreenRef(command, S2Settings.flags(ctx));
            if (b) Log.i(TAG, "[S2] 记忆管理句式，不交给识屏指代");
            return b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [S2-H4] S2 自带能力对象。 */
    public static Capability describe(String sign) {
        Capability c = S2Capabilities.get(sign);
        // [roles] 角色交接可能落到 S1 的打车深链（不在注册表里）—— 修复「用高德打车去机场」报未知能力
        if (c == null && com.onesay.assistant.v2.s1.S1Capabilities.SIGN_TAXI.equals(sign)) c = com.onesay.assistant.v2.s1.S1Capabilities.taxi();
        return c;
    }

    // ------------------------------------------------------------------ 执行

    /** [S2-H6] S2Backend 调用（门禁已放行或令牌已批准）。 */
    public static ExecutionResult executeLocal(Context ctx, Capability cap, JSONObject args) {
        if (cap == null) return null;
        try {
            if (ctx == null) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "记忆库不可用", "no context", cap, args);
            }
            S2Engine.Flags f = S2Settings.flags(ctx);
            boolean ledger = cap.sign.startsWith("s2.ledger.");
            boolean habit = cap.sign.startsWith("s2.habit.");
            if ((ledger && !f.ledger) || (habit && !f.habit) || (!ledger && !habit && !f.memory && !S2Capabilities.SIGN_CLEAR.equals(cap.sign))) {
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "这项功能已关闭", "s2 disabled", cap, args);
            }
            if (cap.sign.startsWith("s2.soul.")) return com.onesay.assistant.v2.soul.SoulHooks.execute(ctx, cap, args);   // [soul]
            S2Engine.Exec x = engine(ctx).execute(cap.sign, args, S2Clock.now());
            Log.i(TAG, "[S2] 执行 " + cap.sign + " ok=" + x.ok + " verified=" + x.verified);
            if (!x.ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, x.message, "s2 exec failed", cap, args);
            return ExecutionResult.success(cap, args, x.message,
                    x.verified ? ExecutionResult.Verification.VERIFIED : ExecutionResult.Verification.UNVERIFIED,
                    x.output != null ? x.output : "s2");
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "记忆库异常：" + t.getMessage(), t.toString(), cap, args);
        }
    }

    /** [S2-H5] 任意后台线程：成功执行之后，记一次「意图 → 实际用的 App」。 */
    public static void onExecuted(Context ctx, String query, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (ctx == null || cap == null || r == null || !r.ok || !S2Settings.habitEnabled(ctx)) return;
            String rec = engine(ctx).recordExecution(query, cap.sign, args, r.ok, S2Clock.now());
            if (rec != null) Log.i(TAG, "[S2] 习惯记一次: " + rec);
        } catch (Throwable ignored) {
        }
    }

    /** [S2-H8] 剪贴板芯片「打车」「导航」按习惯默认 App 改道；不改返回原动作。 */
    public static ScreenAction chipAction(Context ctx, String chipKind, ScreenEntity entity, ScreenAction original) {
        try {
            if (ctx == null || entity == null || !S2Settings.habitEnabled(ctx)) return original;
            S2Engine.Result r = engine(ctx).chipOverride(chipKind, entity.value, original != null ? original.sign : null);
            if (r == null) return original;
            Log.i(TAG, "[S2] 剪贴板芯片按习惯改道: " + r.log);
            return new ScreenAction(DeixisResolver.Action.NAVIGATE, r.sign, r.args,
                    ("TAXI".equals(chipKind) ? "打车去 " : "导航到 ") + entity.shortDisplay(12), entity);
        } catch (Throwable t) {
            return original;
        }
    }

    // ------------------------------------------------------------------ round3 #2：「撤销」芯片

    private static final java.util.regex.Pattern ID_RE = java.util.regex.Pattern.compile("id=(\\d+)");

    /** 从执行结果 detail（「id=123」）取出新条目 id；取不到返回 -1。 */
    static long noteIdOf(ExecutionResult r) {
        if (r == null || r.detail == null) return -1;
        java.util.regex.Matcher m = ID_RE.matcher(r.detail);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** [S2-H9] 主线程：记下一条成功 → 显示「撤销」芯片；点了删掉刚记的这一条（s2.memory.delete，与「删掉…那条」同一能力）。 */
    public static void onNoteAdded(final com.onesay.assistant.v2.ui.FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (svc == null || cap == null || r == null || !r.ok || !S2Capabilities.SIGN_NOTE_ADD.equals(cap.sign)) return;
            final long id = noteIdOf(r);
            if (id <= 0) return;
            final String content = args != null ? args.optString("content", "") : "";
            boolean implicit = args != null && "1".equals(args.optString("implicit", ""));
            com.onesay.assistant.v2.ui.ActionChipsView v = ensureChipsView(svc);
            if (v == null) return;
            java.util.List<String> labels = new java.util.ArrayList<String>();
            labels.add("撤销");
            v.setListener(new com.onesay.assistant.v2.ui.ActionChipsView.Listener() {
                @Override
                public void onChipClicked(int index) {
                    try { com.onesay.assistant.v2.wake.WakeService.cancelCaptureFromUi(); } catch (Throwable ignored) { }
                    hideChips(svc);
                    JSONObject a = new JSONObject();
                    try {
                        a.put("ids", String.valueOf(id));
                        a.put("preview", "刚才记的「" + S2Text.brief(content, 16) + "」");
                    } catch (Exception ignored) {
                    }
                    Log.i(TAG, "[S2] 用户点「撤销」→ 删除 #" + id);
                    svc.screenRefExecute(S2Capabilities.get(S2Capabilities.SIGN_NOTE_DELETE), a, "撤销");
                }
            });
            v.showChips(labels);
            Log.i(TAG, "[S2] 记下 #" + id + (implicit ? "（免前缀）" : "") + "，显示「撤销」芯片");
        } catch (Throwable t) {
            Log.w(TAG, "[S2] 撤销芯片异常: " + t.getMessage());
        }
    }

    private static com.onesay.assistant.v2.ui.ActionChipsView ensureChipsView(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) return (com.onesay.assistant.v2.ui.ActionChipsView) existing;
        com.onesay.assistant.v2.ui.ActionChipsView v = new com.onesay.assistant.v2.ui.ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());   // 与识屏 / 诊断芯片同一位置
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private static void hideChips(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) ((com.onesay.assistant.v2.ui.ActionChipsView) existing).hideChips();
    }

    // ------------------------------------------------------------------ AgentLoop（只读 memory.search）

    /** [S2-H7a] 记忆库开着且非空时，把 memory.search 追加进本轮工具。 */
    public static void appendTools(Context ctx, JSONArray tools, String query) {
        try {
            if (ctx == null || tools == null || !S2Settings.memoryEnabled(ctx)) return;
            if (engine(ctx).store().count(null) == 0) return;
            String wire = CapabilityRegistry.signToToolName(S2Capabilities.SIGN_SEARCH);
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                JSONObject fn = t != null ? t.optJSONObject("function") : null;
                if (fn != null && wire.equals(fn.optString("name"))) return;
            }
            java.util.List<Capability> one = new java.util.ArrayList<Capability>();
            one.add(S2Capabilities.get(S2Capabilities.SIGN_SEARCH));
            JSONArray mine = CapabilityRegistry.getInstance().exportToolsSchema(one);
            for (int i = 0; i < mine.length(); i++) tools.put(mine.opt(i));
            Log.i(TAG, "[S2] 追加只读工具 memory.search");
        } catch (Throwable t) {
            Log.w(TAG, "[S2] 追加工具异常: " + t.getMessage());
        }
    }

    /** [S2-H7b] AgentLoop 只能看到 memory.search（写/删一律只走用户亲口的指令）。 */
    public static Capability describeForAgent(Context ctx, String sign) {
        if (ctx == null || !S2Capabilities.SIGN_SEARCH.equals(sign) || !S2Settings.memoryEnabled(ctx)) return null;
        return S2Capabilities.get(sign);
    }

    /** [S2-H7c] 门禁已放行后执行 memory.search；不是 S2 工具返回 null。 */
    public static ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        if (ctx == null || cap == null || !S2Capabilities.SIGN_SEARCH.equals(cap.sign)) return null;
        return executeLocal(ctx, cap, args);
    }

    // ------------------------------------------------------------------ 调试（S2TestDriver）

    public static String debugStatus(Context ctx) {
        S2Engine e = engine(ctx);
        StringBuilder sb = new StringBuilder(S2Settings.dump(ctx));
        sb.append(" | notes=").append(e.store().count(MemoryEntry.KIND_NOTE)).append(" ledger=").append(e.store().count(MemoryEntry.KIND_LEDGER));
        for (com.onesay.assistant.v2.roles.RoleCatalog.Role g : e.roles().roles()) {
            sb.append(" | ").append(g.id).append('=').append(HabitPolicy.resolve(e.habits(), g.id));
        }
        sb.append(" | clockOffsetMs=").append(S2Clock.offsetMs());
        return sb.toString();
    }
}
