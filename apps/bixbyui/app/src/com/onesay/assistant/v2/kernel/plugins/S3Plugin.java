package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.TriageSwitches;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.s3.S3Hooks;
import com.onesay.assistant.v2.s3.S3Runner;
import com.onesay.assistant.v2.s3.S3Settings;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/** [kernel] S3 定时任务 + 灵动岛提醒 / 待办。委托 {@link S3Hooks} / {@link S3Runner}；执行后端 S3Backend、闹钟 BootReceiver 不变。 */
public final class S3Plugin extends FeaturePlugin {

    @Override public String id() { return "s3"; }
    @Override public String title() { return "S3 定时任务 / 提醒 / 待办"; }
    @Override public String logTag() { return "[S3]"; }
    @Override public String prefsName() { return "bixby_s3"; }
    @Override public String[] killFiles() { return new String[]{"s3.off", "todo.off"}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return S3Settings.flags(ctx).any(); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 300;          // 摘要之后、S2 之前
            case CLAIMS: return 300;
            case BYPASS_SCREENREF: return 200;
            case DESCRIBE: return 300;
            case SERVICE_CREATE: return 200;    // S1 之后（岛先就位）
            case SERVICE_DESTROY: return 100;   // 最先
            case BACKENDS: return 200;
            case CONSOLE: return 600;           // 最后（灵动岛之后）
            default: return 1000;
        }
    }

    @Override public String triageSignal() { return TriageSwitches.S3; }
    @Override public String[] triageRules() { return new String[]{"6t-s3-create", "6t-s3-cancel", "6t-todo-add", "6t-todo-done", "5t-s3-list", "5t-todo-list"}; }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return S3Hooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-S3] 定时任务/待办接管: "; }
    @Override public boolean triageBeforeRouteLog(Invocation inv) { return true; }
    @Override public boolean claims(Context ctx, String query) { return S3Hooks.claims(ctx, query); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return S3Hooks.bypassScreenRef(ctx, command); }
    @Override public Capability describe(String sign) { return S3Hooks.describe(sign); }

    @Override public void onServiceCreate(FloatingSheetService svc) { S3Runner.onServiceCreate(svc); }
    @Override public void onServiceDestroy(FloatingSheetService svc) { S3Runner.onServiceDestroy(); }

    /** 第 4 阶段（D-3a）：经 FeatureKernel.island("s3") 放上岛的活动种类。 */
    @Override public String[] islandActivityKinds() {
        return new String[]{com.onesay.assistant.v2.kernel.IslandHost.KIND_BG, com.onesay.assistant.v2.kernel.IslandHost.KIND_REMIND, com.onesay.assistant.v2.kernel.IslandHost.KIND_TODO};
    }

    @Override public String[] islandActivities() { return new String[]{"定时任务进度 / 结果回执", "提醒（完成 / 稍后 10 分钟）", "待办 chip"}; }
    /** 定时任务 / 待办（executor=s3，App 内执行）。 */
    @Override public com.onesay.assistant.v2.executor.backend.ExecutorBackend executorBackend() {
        return new com.onesay.assistant.v2.executor.backend.S3Backend();
    }

    @Override public String consoleCard() { return "功能与权限：定时任务 / 待办（S3Hooks.addConsoleEntries）"; }

    /** 定时任务 / 待办（开关 bixby_s3 / bixby_todo；kill-switch s3.off / todo.off）。 */
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        S3Hooks.addConsoleEntries(host, page, root);
    }
    @Override public String debugStatus(Context ctx) { return S3Hooks.debugStatus(ctx); }

    /** debug 包调试入口 --es s3 …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "s3"; }
    /** 依赖声明（只提示不改行为）。 */
    @Override public String[] dependsOn() { return new String[]{"s1", "island"}; }
}
