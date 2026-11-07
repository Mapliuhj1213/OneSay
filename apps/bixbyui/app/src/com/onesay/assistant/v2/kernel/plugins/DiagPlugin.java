package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.diag.DiagHooks;
import com.onesay.assistant.v2.diag.DiagSettings;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.TriageSwitches;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/** [kernel] #9 设置诊断：症状句 → 只读快照 → 解释 + 修复芯片（黄卡）→ 10 分钟撤销。委托 {@link DiagHooks}；执行后端 DiagBackend 仍直接注册在 UnifiedExecutor。 */
public final class DiagPlugin extends FeaturePlugin {

    @Override public String id() { return "diag"; }
    @Override public String title() { return "#9 设置诊断"; }
    @Override public String logTag() { return "[DIAG]"; }
    @Override public String prefsName() { return "bixby_diag"; }
    @Override public String[] killFiles() { return new String[]{DiagSettings.KILL}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return DiagHooks.enabled(ctx); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 500;          // S2 之后、分诊与 ASK 缓存之前
            case CLAIMS: return 500;
            case BYPASS_SCREENREF: return 300;
            case DESCRIBE: return 200;
            case REPLY_SHOWN: return 100;       // 诊断 → 角色
            case EXECUTED: return 100;          // 诊断 → S2 → 角色
            case BACKENDS: return 300;          // 必须在 Shell 后端之前（executor=diag 只走白名单键）
            case CONSOLE: return 300;
            default: return 1000;
        }
    }

    @Override public String triageSignal() { return TriageSwitches.DIAG; }
    @Override public String[] triageRules() { return new String[]{"DIAGNOSE"}; }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return DiagHooks.route(ctx, query, l2); }
    @Override public String routeLog() { return "[ROUTE-DIAG] 设置诊断接管: "; }
    @Override public boolean triageBeforeRouteLog(Invocation inv) { return "ask.reply".equals(inv.sign); }
    @Override public boolean claims(Context ctx, String query) { return DiagHooks.claims(ctx, query); }

    // ★ device-1102：「不懂先搜索」—— 没命中已知案例的症状句进 AgentLoop；AgentLoop 里诊断类开局提示 + 没搜过就收工提醒一次
    @Override public boolean wantsAgentLoop(Context ctx, String query, L2LLMCompiler l2) { return DiagHooks.escalateToAgent(ctx, query, l2); }

    @Override public String agentStartHint(Context ctx, String query, boolean webOffered) {
        return webOffered && DiagHooks.isDiagnosisQuery(query) ? com.onesay.assistant.v2.diag.DiagSearchPolicy.START_HINT : null;
    }

    @Override public String agentFinishNudge(Context ctx, String query, boolean webOffered, java.util.List<String> calledSigns) {
        return com.onesay.assistant.v2.diag.DiagSearchPolicy.needsSearchNudge(DiagHooks.isDiagnosisQuery(query), webOffered, calledSigns, false)
                ? com.onesay.assistant.v2.diag.DiagSearchPolicy.NUDGE : null;
    }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return DiagHooks.bypassScreenRef(ctx, command); }
    @Override public Capability describe(String sign) { return DiagHooks.describe(sign); }
    @Override public void onReplyShown(FloatingSheetService svc, Invocation inv) { DiagHooks.onReplyShown(svc, inv); }

    @Override public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        DiagHooks.onExecuted(svc, cap, r);   // 修复成功 → 「撤销」芯片
    }

    /** 设置诊断的修复 / 撤销（executor=diag，白名单键）。 */
    @Override public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return new com.onesay.assistant.v2.executor.backend.DiagBackend();
    }

    @Override public String consoleCard() { return "设置诊断卡（开关 / 大模型兜底）"; }

    /** [R12] 控制台 v2：功能与权限一行 + 细项页（kill-switch 文件 diag.off 优先 → 显示「已被停用」）。 */
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        final boolean killed = DiagSettings.killed(act);
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleHost.Row r = host.switchLinkRow(root, "设置诊断",
                    killed ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : "说症状 → 讲原因 → 一键修复",
                    DiagSettings.pref(act, DiagSettings.KEY_ENABLED), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            DiagSettings.set(act, DiagSettings.KEY_ENABLED, on);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            host.openPage("diag", "设置诊断");
                        }
                    });
            if (killed) r.setEnabled(false);
            return;
        }
        if (!"diag".equals(page)) return;
        final com.onesay.assistant.v2.kernel.ConsoleHost.Row[] sub = new com.onesay.assistant.v2.kernel.ConsoleHost.Row[1];
        com.onesay.assistant.v2.kernel.ConsoleHost.Row main = host.mainSwitch(root, "使用设置诊断", DiagSettings.pref(act, DiagSettings.KEY_ENABLED),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        DiagSettings.set(act, DiagSettings.KEY_ENABLED, on);
                        if (sub[0] != null) sub[0].setEnabled(on && !killed);
                    }
                });
        android.widget.LinearLayout g = host.group(root, "细项");
        sub[0] = host.switchRow(g, "没见过的症状让大模型挑", "只读，只在白名单里挑要读的设置", DiagSettings.pref(act, DiagSettings.KEY_LLM),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        DiagSettings.set(act, DiagSettings.KEY_LLM, on);
                    }
                });
        sub[0].setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, com.onesay.assistant.v2.kernel.ConsoleHost.KILLED);
        }
        host.footer(root, "说症状 → 读当前设置 → 讲原因 → 给修复按钮。改之前会确认，10 分钟内说「撤销」能改回去。");
    }
    @Override public String debugStatus(Context ctx) { return DiagHooks.debugStatus(ctx); }

    /** debug 包调试入口 --es diag …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "diag"; }
}
