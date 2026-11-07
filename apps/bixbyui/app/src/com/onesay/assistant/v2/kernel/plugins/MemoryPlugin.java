package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.TriageSwitches;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [kernel] S2「记得住」：记一下 / 免前缀记忆 / 本地回忆 / 列表与删除（记账 #20 代码保留、默认关、不路由）。
 * 委托 {@link S2Hooks}。习惯默认值记账（S2Hooks.onExecuted）、AgentLoop 只读 memory.search、执行后端 S2Backend 仍是直接调用。
 */
public final class MemoryPlugin extends FeaturePlugin {

    @Override public String id() { return "s2"; }
    @Override public String title() { return "S2 记得住（记一下 / 本地回忆）"; }
    @Override public String logTag() { return "[S2]"; }
    @Override public String prefsName() { return "bixby_s2"; }
    @Override public String[] killFiles() { return new String[]{"s2.off", "memory.off", "habit.off", "ledger.off"}; }
    @Override public boolean kernelGated() { return false; }

    @Override public boolean enabled(Context ctx) {
        return S2Settings.memoryEnabled(ctx) || S2Settings.habitEnabled(ctx);
    }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 400;          // 存图 / 摘要 / S3 之后（「记个待办」不能被「记个」吃掉）、诊断之前
            case CLAIMS: return 400;
            case BYPASS_SCREENREF: return 100;  // 迁移前顺序：S2 → S3 → 诊断 → 存图 → 摘要
            case DESCRIBE: return 100;          // S2 → 诊断 → S3
            case EXECUTED: return 200;          // 诊断 → S2 → 角色
            case AFTER_EXEC: return 100;
            case AGENT_TOOLS: return 100;
            case AGENT_DESCRIBE: return 100;
            case AGENT_EXECUTE: return 100;
            case BACKENDS: return 100;          // S2 → S3 → 诊断（都在 Intent / Shell 兜底之前）
            case CONSOLE: return 100;
            default: return 1000;
        }
    }

    @Override public String triageSignal() { return TriageSwitches.IMPLICIT_MEMORY; }
    @Override public boolean triageOn(Context ctx) { return S2Settings.implicitEnabled(ctx); }
    @Override public String[] triageRules() { return new String[]{"3m-memory-recall", "6m-memory-write", "6m-memory-manage", "6m-memory-implicit"}; }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return S2Hooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-S2] S2 本地接管: "; }
    @Override public boolean claims(Context ctx, String query) { return S2Hooks.claims(ctx, query); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return S2Hooks.bypassScreenRef(ctx, command); }
    @Override public Capability describe(String sign) { return S2Hooks.describe(sign); }

    @Override public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        S2Hooks.onNoteAdded(svc, cap, args, r);   // 记下了 → 「撤销」芯片（round3 #2）
    }

    /** 习惯默认值：记下实际用的 App（单步与循环每一步）。 */
    @Override public void afterExec(Context ctx, String query, Capability cap, JSONObject args, ExecutionResult r) {
        S2Hooks.onExecuted(ctx, query, cap, args, r);
    }

    /** 只读记忆检索 memory.search（记忆库非空时）。 */
    @Override public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) { S2Hooks.appendTools(ctx, tools, query); }
    @Override public Capability describeForAgent(Context ctx, String name) { return S2Hooks.describeForAgent(ctx, name); }
    @Override public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) { return S2Hooks.executeForAgent(ctx, cap, args); }

    /** 记忆库 / 习惯 / 记账（executor=s2，App 内执行）。 */
    @Override public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return new com.onesay.assistant.v2.executor.backend.S2Backend();
    }

    /** [R12] 控制台 v2：记忆页主开关「记一下」记忆库（默认应用分组由 RolesPlugin 接着画）；首页「记忆」大卡。 */
    @Override public boolean consoleV2() { return true; }

    @Override public String[] consoleTile(Context ctx) {
        com.onesay.assistant.v2.s2.S2Engine e = S2Hooks.engine(ctx);
        int notes = e.store().count(com.onesay.assistant.v2.s2.MemoryEntry.KIND_NOTE);
        int defs = 0;
        for (com.onesay.assistant.v2.roles.RoleCatalog.Role g : e.roles().roles()) {
            if (e.habits().explicitDefault(g.id) != null) defs++;
        }
        return new String[]{String.valueOf(notes), "默认应用 " + defs + " 个"};
    }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (com.onesay.assistant.v2.soul.SoulConsole.addEntries(host, page, root)) return;   // [soul] 人设 / 长期记忆（查看、编辑、清空）
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_MEMORY.equals(page)) return;
        final android.app.Activity act = host.activity();
        boolean killed = S2Settings.pref(act, S2Settings.KEY_MEMORY) && !S2Settings.memoryEnabled(act);
        com.onesay.assistant.v2.kernel.ConsoleHost.Row main = host.mainSwitch(root, "「记一下」记忆库", S2Settings.pref(act, S2Settings.KEY_MEMORY),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        S2Settings.set(act, S2Settings.KEY_MEMORY, on);
                    }
                });
        if (killed) {
            main.setEnabled(false);
            host.footer(root, com.onesay.assistant.v2.kernel.ConsoleHost.KILLED);
        }
        host.footer(root, "说「记一下 车停在 B2-117」，之后问「车停哪了」本机就能答；也能「列出记忆」「删掉刚才那条」。只存本机。");
    }

    @Override public String[] islandActivities() { return new String[0]; }
    @Override public String consoleCard() { return "记忆卡（条数 / 清空）+ S2 开关"; }
    @Override public String debugStatus(Context ctx) { return S2Hooks.debugStatus(ctx); }

    /** debug 包调试入口 --es s2 …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "s2"; }
}
