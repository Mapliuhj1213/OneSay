package com.onesay.assistant.v2.diag;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.wake.WakeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [diag] #9 设置诊断的<b>全部挂钩入口</b>（写法同 S1Hooks / S2Hooks：既有文件里只有几行 {@code // [diag]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>D-H1</td><td>UnifiedRouter.cascade：S2 之后、分诊之前</td><td>{@link #route}：撤销口令 → diag.undo；症状句 → 只读快照 → 诊断回答（ask.reply + 芯片）</td></tr>
 *   <tr><td>D-H2</td><td>UnifiedRouter.shouldUseAgentLoop</td><td>{@link #claims}：诊断/撤销句永不进 AgentLoop</td></tr>
 *   <tr><td>D-H2b</td><td>UnifiedRouter.triage</td><td>{@link #enabled}：开关关闭时分诊不产出 DIAGNOSE</td></tr>
 *   <tr><td>D-H3</td><td>FloatingSheetService.onSubmitCommand：识屏指代之前</td><td>{@link #bypassScreenRef}：「把刚才的改回去」「为什么这个屏幕老亮」不交给识屏</td></tr>
 *   <tr><td>D-H4</td><td>FloatingSheetService.dispatch</td><td>{@link #describe}（diag.undo）+ {@link #onReplyShown}（显示修复芯片）</td></tr>
 *   <tr><td>D-H5</td><td>FloatingSheetService.executeCapability 成功后</td><td>{@link #onExecuted}：修复成功 → 显示「撤销」芯片</td></tr>
 *   <tr><td>D-H6</td><td>UnifiedExecutor 后端列表</td><td>{@code DiagBackend}（executor="diag"）→ {@link #executeLocal}</td></tr>
 * </table>
 * 读是只读、免确认；写只经芯片 → {@code FloatingSheetService.screenRefExecute} → ExecutionGate（黄卡）→ UnifiedExecutor → DiagBackend。
 */
public final class DiagHooks {

    private static final String TAG = "Diag";

    private DiagHooks() {}

    private static final DiagUndoStore UNDO = new DiagUndoStore();

    /** 最近一次诊断的芯片（主线程点击时取用）。 */
    private static volatile List<DiagEngine.Chip> sChips = new ArrayList<DiagEngine.Chip>();

    public static DiagUndoStore undoStore() {
        return UNDO;
    }

    public static boolean enabled(Context ctx) {
        return DiagSettings.enabled(ctx);
    }

    // ------------------------------------------------------------------ 路由（后台线程）

    /** [D-H1] null = 不接管。 */
    public static Invocation route(Context ctx, String query, L2LLMCompiler l2) {
        try {
            if (ctx == null || query == null) return null;
            String q = TriageDecider.normalize(query);
            // 撤销：只要有 10 分钟内的记录就接（开关关了也允许恢复，只恢复不新改）
            if (DiagMatcher.isUndo(q)) {
                DiagUndoStore.Record rec = UNDO.latest(System.currentTimeMillis());
                if (rec == null) return null;
                Log.i(TAG, "[DIAG] 撤销口令 → " + rec);
                return new Invocation(DiagCapabilities.SIGN_UNDO, DiagCapabilities.undoArgs(rec.id), 1.0,
                        Invocation.RouteSource.L0_RULE, query);
            }
            if (!DiagSettings.enabled(ctx)) return null;
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(query);
            if (m == null) return null;
            DiagEngine.Report rep = diagnose(ctx, query, q, m, l2);
            if (rep == null) return null;
            return toInvocation(rep, query);
        } catch (Throwable t) {
            Log.w(TAG, "[DIAG] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    /**
     * ★ device-1102：症状句要不要升级到 AgentLoop（先搜索、再查、再给结论）。UnifiedRouter.shouldUseAgentLoop 在分诊前调用（后台线程）。
     * 已知案例命中（知识表症状 + 至少一条规则触发）→ false，仍走本地诊断（route 会再读一次快照，读数是实时的）；
     * 未知症状 / 一条规则没中 / 读不到快照 → true。
     */
    public static boolean escalateToAgent(Context ctx, String query, L2LLMCompiler l2) {
        try {
            if (ctx == null || query == null || !DiagSettings.enabled(ctx)) return false;
            String q = TriageDecider.normalize(query);
            if (DiagMatcher.isUndo(q)) return false;
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(query);
            if (m == null) return false;
            DiagEngine.Report rep = m.unknown ? null : diagnose(ctx, query, q, m, null);
            boolean up = DiagSearchPolicy.escalate(m, rep);
            Log.i(TAG, "[DIAG] " + (up ? "没有命中已知案例 → 升级到 AgentLoop（先搜索再动手）" : "命中已知案例 → 本地诊断直接回答")
                    + " 症状=" + m.id() + " 发现=" + (rep == null ? "无快照" : String.valueOf(rep.findings.size())));
            return up;
        } catch (Throwable t) {
            Log.w(TAG, "[DIAG] escalate 判断异常，按原流程: " + t.getMessage());
            return false;
        }
    }

    /** ★ device-1102：AgentLoop 用——这句是不是诊断类（纯正则，不读设备）。 */
    public static boolean isDiagnosisQuery(String query) {
        try {
            return query != null && TriageDecider.diagnoseMatch(query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读快照并诊断（也给调试驱动用）。读失败（守护未就绪）返回 null。 */
    public static DiagEngine.Report diagnose(Context ctx, String query, String normalized, DiagMatcher.Match m, L2LLMCompiler l2) {
        List<String> keys;
        String title;
        if (m.symptom != null) {
            keys = m.symptom.keyIds;
            title = m.symptom.title;
        } else {
            keys = null;
            if (l2 != null && DiagSettings.llmEnabled(ctx)) keys = pickKeysWithLlm(query, l2);
            if (keys == null || keys.isEmpty()) {
                keys = DiagMatcher.keysForUnknown(normalized);
                Log.i(TAG, "[DIAG] 未知症状：按领域词取键 " + keys);
            }
            title = query.trim();
        }
        long t0 = System.currentTimeMillis();
        Map<String, String> snap = readSnapshot(ctx, keys);
        if (snap == null) return null;
        Log.i(TAG, "[DIAG] 读取 " + keys.size() + " 项 " + (System.currentTimeMillis() - t0) + "ms 症状=" + m.id()
                + " 快照=" + DiagSnapshot.dump(snap));
        DiagEngine.Report rep = m.symptom != null ? DiagEngine.diagnose(m.symptom, snap) : DiagEngine.diagnoseKeys(title, keys, snap);
        Log.i(TAG, "[DIAG] 诊断: " + rep);
        return rep;
    }

    /**
     * 只读快照。null = 通道不可用。
     * 2026-10-25 exec 守护 v4：不再拼成一条复合命令，每个键一条单命令依次执行（DiagSnapshot.buildReadSteps）。
     */
    public static Map<String, String> readSnapshot(Context ctx, List<String> keys) {
        ShellRunner sh = shell(ctx);
        if (sh == null) return null;
        List<com.onesay.assistant.v2.executor.ShellScript.Step> steps = DiagSnapshot.buildReadSteps(keys);
        if (steps.isEmpty()) return null;
        ShellExecutor.ShellResponse r = com.onesay.assistant.v2.executor.ShellScript.exec(sh, steps);
        if (r.isDown || r.isTimeout) {
            Log.w(TAG, "[DIAG] 读取失败（特权通道 down=" + r.isDown + " timeout=" + r.isTimeout + "）");
            return null;
        }
        return DiagSnapshot.parse(keys, r.output);
    }

    private static List<String> pickKeysWithLlm(String query, L2LLMCompiler l2) {
        try {
            if (!l2.hasApiKey()) return null;
            com.onesay.assistant.v2.net.LlmClient c = l2.client().withReadTimeout(3000);
            String body = com.onesay.assistant.v2.net.LlmClient.body(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, 0.0, 120,
                    com.onesay.assistant.v2.net.LlmClient.messages(DiagLlm.systemPrompt(), query), null, false).toString();
            com.onesay.assistant.v2.net.LlmResponse r = c.post(body);
            if (!r.ok()) {
                Log.w(TAG, "[DIAG] LLM 选键失败: " + r.error);
                return null;
            }
            List<String> keys = DiagLlm.parseKeys(r.content());
            Log.i(TAG, "[DIAG] LLM 选键（白名单过滤后）: " + keys);
            return keys;
        } catch (Throwable t) {
            Log.w(TAG, "[DIAG] LLM 选键异常: " + t.getMessage());
            return null;
        }
    }

    static Invocation toInvocation(DiagEngine.Report rep, String query) {
        JSONObject a = new JSONObject();
        try {
            a.put("text", rep.text);
            a.put("query", query.trim());
            a.put("diag", rep.symptomId);
            JSONArray chips = new JSONArray();
            for (DiagEngine.Chip c : rep.chips) {
                JSONObject o = new JSONObject();
                o.put("fix", c.fixId);
                o.put("label", c.label);
                o.put("summary", c.summary);
                chips.put(o);
            }
            a.put("diag_chips", chips);
        } catch (Exception ignored) {
        }
        sChips = new ArrayList<DiagEngine.Chip>(rep.chips);
        // 本地回答：不经 L1（不写 ASK 缓存 —— 设置值会变），不登记待验证，不进 AgentLoop
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /** [D-H2] 后台线程：诊断句 / 有效撤销口令永不进 AgentLoop。 */
    public static boolean claims(Context ctx, String query) {
        try {
            if (ctx == null || query == null) return false;
            String q = TriageDecider.normalize(query);
            if (DiagMatcher.isUndo(q) && UNDO.latest(System.currentTimeMillis()) != null) return true;
            return DiagSettings.enabled(ctx) && TriageDecider.diagnoseMatch(query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [D-H3] 主线程（纯正则）：诊断句 / 有效撤销口令不交给识屏指代（「把刚才的改回去」里的「刚才的」不是屏幕指代）。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        boolean b = claims(ctx, command);
        if (b) Log.i(TAG, "[DIAG] 诊断/撤销句式，不交给识屏指代");
        return b;
    }

    /** [D-H4a] */
    public static Capability describe(String sign) {
        return DiagCapabilities.describe(sign);
    }

    // ------------------------------------------------------------------ 面板（主线程）

    /** [D-H4b] ask.reply 上屏之后：诊断回答带芯片就显示；否则什么都不做。 */
    public static void onReplyShown(final FloatingSheetService svc, Invocation inv) {
        try {
            if (svc == null || inv == null || inv.args == null || !inv.args.has("diag")) return;
            final List<DiagEngine.Chip> chips = sChips;
            final String query = inv.args.optString("query", "");
            ActionChipsView v = ensureChipsView(svc);
            if (v == null) return;
            if (chips.isEmpty()) {
                v.hideChips();
                return;
            }
            List<String> labels = new ArrayList<String>();
            for (DiagEngine.Chip c : chips) labels.add(c.label);
            v.setListener(new ActionChipsView.Listener() {
                @Override
                public void onChipClicked(int index) {
                    if (index < 0 || index >= chips.size()) return;
                    stopListening();
                    hideChips(svc);
                    runChip(svc, chips.get(index), query);
                }
            });
            v.showChips(labels);
            Log.i(TAG, "[DIAG] 显示修复芯片 " + chips);
        } catch (Throwable t) {
            Log.w(TAG, "[DIAG] 显示芯片异常: " + t.getMessage());
        }
    }

    static void runChip(FloatingSheetService svc, DiagEngine.Chip c, String query) {
        DiagKnowledge.Fix f = DiagKnowledge.fix(c.fixId);
        if (f == null) return;
        Log.i(TAG, "[DIAG] 芯片点击: " + c);
        if (f.kind == DiagKnowledge.FixKind.PAGE) {
            Capability page = CapabilityRegistry.getInstance().describe(f.capSign);
            if (page != null) svc.screenRefExecute(page, new JSONObject(), c.label);
            return;
        }
        // 写设置：动态能力（summary = 改前 → 改后），risk 2 + confirm → 黄卡确认 → 令牌 → UnifiedExecutor → DiagBackend
        svc.screenRefExecute(DiagCapabilities.fix(f, c.summary), DiagCapabilities.fixArgs(f.id), query.length() > 0 ? query : c.label);
    }

    /** [D-H5] 主线程：修复执行成功 → 显示「撤销」芯片。 */
    public static void onExecuted(final FloatingSheetService svc, Capability cap, ExecutionResult r) {
        try {
            if (svc == null || cap == null || r == null || !r.ok || cap.sign == null || !cap.sign.startsWith(DiagCapabilities.PREFIX_FIX)) return;
            final DiagUndoStore.Record rec = UNDO.latest(System.currentTimeMillis());
            if (rec == null || !cap.sign.endsWith("." + rec.fixId)) return;
            ActionChipsView v = ensureChipsView(svc);
            if (v == null) return;
            final String summary = "撤销：" + rec.what + " 改回 " + rec.before;
            List<String> labels = new ArrayList<String>();
            labels.add("撤销（改回 " + rec.before + "）");
            v.setListener(new ActionChipsView.Listener() {
                @Override
                public void onChipClicked(int index) {
                    stopListening();
                    hideChips(svc);
                    svc.screenRefExecute(DiagCapabilities.undo(summary), DiagCapabilities.undoArgs(rec.id), "撤销");
                }
            });
            v.showChips(labels);
        } catch (Throwable t) {
            Log.w(TAG, "[DIAG] 撤销芯片异常: " + t.getMessage());
        }
    }

    private static void stopListening() {
        try {
            WakeService.cancelCaptureFromUi();   // 用户改用手点：停止本轮收音
        } catch (Throwable ignored) {
        }
    }

    private static ActionChipsView ensureChipsView(FloatingSheetService svc) {
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());   // 与识屏芯片同一位置（状态行、模式胶囊行之后）
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private static void hideChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }

    // ------------------------------------------------------------------ 执行（门禁已放行或令牌已批准）

    /** [D-H6] DiagBackend 调用。 */
    public static ExecutionResult executeLocal(Context ctx, ShellRunner sh, Capability cap, JSONObject args) {
        if (cap == null) return null;
        try {
            if (sh == null) sh = shell(ctx);
            long now = System.currentTimeMillis();
            DiagEngine.ExecOutcome x;
            if (DiagCapabilities.SIGN_UNDO.equals(cap.sign)) {
                String id = args != null && args.has("undo") ? args.optString("undo") : null;
                x = DiagEngine.undo(id, sh, CAPS, UNDO, now);
            } else {
                if (!DiagSettings.enabled(ctx)) {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "设置诊断已关闭，没有改动", "diag disabled", cap, args);
                }
                String fixId = DiagCapabilities.fixIdOf(cap, args);
                if (fixId == null) {
                    return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "这个修复不在知识表里，已拒绝", "fix id mismatch", cap, args);
                }
                x = DiagEngine.executeFix(fixId, sh, CAPS, UNDO, now);
            }
            Log.i(TAG, "[DIAG] 执行 " + cap.sign + " " + x);
            if (!x.ok) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, x.message, x.detail, cap, args);
            return ExecutionResult.success(cap, args, x.message,
                    x.verified ? ExecutionResult.Verification.VERIFIED : ExecutionResult.Verification.UNVERIFIED, x.detail);
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "设置修复异常：" + t.getMessage(), t.toString(), cap, args);
        }
    }

    /** 现有能力 → 命令（参数只来自知识表或撤销记录）。 */
    static final DiagEngine.CapResolver CAPS = new DiagEngine.CapResolver() {
        @Override
        public String command(String sign, JSONObject args) {
            Capability c = CapabilityRegistry.getInstance().describe(sign);
            if (c == null || c.cmdTemplate == null) return null;
            try {
                return ShellCommand.fill(c.cmdTemplate, ShellCommand.slotValues(args != null ? args : new JSONObject(), null, null));
            } catch (Throwable t) {
                return null;
            }
        }
    };

    static ShellRunner shell(Context ctx) {
        try {
            return ctx == null ? null : UnifiedExecutor.getInstance(ctx).getShellExecutor();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 调试（DiagTestDriver）

    public static String debugStatus(Context ctx) {
        DiagUndoStore.Record r = UNDO.latest(System.currentTimeMillis());
        return DiagSettings.dump(ctx) + " | undo=" + UNDO.size(System.currentTimeMillis()) + (r != null ? " latest=" + r : "")
                + " | symptoms=" + DiagKnowledge.SYMPTOMS.size() + " keys=" + DiagKnowledge.KEYS.size() + " fixes=" + DiagKnowledge.FIXES.size();
    }
}
