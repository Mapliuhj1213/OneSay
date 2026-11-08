package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.planner.PlannerHooks;
import com.onesay.assistant.v2.planner.PlannerLink;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [kernel] 2026-11-05 计划 App 适配层（feat/planner-link）：票 / 取件码 / 待办 / 日程 / 自动任务的数据放到「OneSay Plan」（com.onesay.plan），
 * OneSay 经后门读写；计划 App 没装 / 关掉开关 → 全部回落 OneSay 本地存储（功能不丢）。默认开。
 * 接管顺序在本地日程（290）之前：「取件码是…」「把这张票存起来」「今天有啥事」「打开计划」。其余说法照旧走 S3 / 日程 / 记一记。
 */
public final class PlannerPlugin extends FeaturePlugin {

    @Override public String id() { return "planner"; }
    @Override public String title() { return "计划 App 连接"; }
    @Override public String logTag() { return "[PLANNER]"; }
    @Override public String prefsName() { return "bixby_planner"; }
    @Override public String[] killFiles() { return new String[]{"planner.off"}; }
    @Override public boolean enabled(Context ctx) { return PlannerLink.SWITCH.on(ctx); }
    @Override public String acceptanceModule() { return null; }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 285;
            case CLAIMS: return 285;
            case SERVICE_CREATE: return 190;   // 在 S3（200）之前：引擎第一次建存储时已经知道计划 App 在不在
            case SERVICE_DESTROY: return 900;
            default: return 1000;
        }
    }

    /** [companion] 伴侣连接先判（删 / 改待办经连接执行；其余 CLAIM 返回 null 交回原规则层），再走计划 App 的取件码 / 票 / 今天。 */
    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) {
        Invocation c = com.onesay.assistant.v2.companion.CompanionHooks.route(ctx, query);
        return c != null ? c : PlannerHooks.route(ctx, query);
    }
    @Override public String routeLog() { return "[ROUTE-PLANNER] 计划 App 接管: "; }
    @Override public boolean claims(Context ctx, String query) { return com.onesay.assistant.v2.companion.CompanionHooks.claims(ctx, query) || PlannerHooks.claims(ctx, query); }
    @Override public String[] triageRules() { return new String[]{"6k-ticket-save"}; }
    /** 「帮我记下这张票」带「这张」、★ 11-06「A1-2345 这个取件码在哪取」带「这个」，不能让识屏 DEIXIS 先接走。 */
    @Override public boolean bypassScreenRef(Context ctx, String command) {
        return com.onesay.assistant.v2.planner.PlannerVoice.ticket(command) || com.onesay.assistant.v2.planner.PlannerVoice.pickupAsk(command) != null;
    }

    @Override public void onServiceCreate(FloatingSheetService svc) { PlannerHooks.onServiceCreate(svc); }
    @Override public void onServiceDestroy(FloatingSheetService svc) { PlannerHooks.onServiceDestroy(svc); }

    @Override public String consoleSummary(Context ctx) {
        return PlannerLink.installed(ctx) ? "已安装 · " + PlannerLink.get(ctx).status() : "没装计划 App（数据留在 OneSay 本地）";
    }

    @Override public boolean consoleV2() { return true; }

    /** 「功能」页最上面一组：计划 App 开关 + 「打开计划」。日程 / 待办 / 定时任务各自的行点进去也会跳到计划 App 对应页（见 PlannerLink.manageInPlanner）。 */
    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) return;
        final android.app.Activity act = host.activity();
        final boolean inst = PlannerLink.installed(act);
        android.widget.LinearLayout g = host.group(root, null);
        host.switchLinkRow(g, "计划 App", inst ? "日程、待办、票、取件码、自动任务都在「OneSay Plan」里看和改 · 点这里打开计划"
                        : "没装「OneSay Plan」，数据留在 OneSay 本地",
                PlannerLink.SWITCH.on(act), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) { PlannerLink.SWITCH.set(act, on); }
                }, new Runnable() {
                    @Override public void run() {
                        if (!PlannerLink.open(act, null)) android.widget.Toast.makeText(act, "没装计划 App", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override public String debugStatus(Context ctx) { return PlannerHooks.debugStatus(ctx) + "\n" + com.onesay.assistant.v2.companion.CompanionHooks.debugStatus(ctx); }
    @Override public String[] dependsOn() { return new String[]{"s3", "sched"}; }
}
