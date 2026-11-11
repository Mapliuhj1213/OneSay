package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.apifirst.ApiFirstAndroid;
import com.onesay.assistant.v2.apifirst.ApiFirstSettings;
import com.onesay.assistant.v2.apifirst.RouteTool;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeaturePlugin;

import org.json.JSONObject;

/**
 * ★ feat/api-first（2026-11-11）：接口优先 —— 给 AgentLoop 挂路由工具 route.open（系统接口 → 已配置接口 → App 深链 / 浏览器 → 点界面兜底），
 * 每步走了哪条路、为什么，写进日志（[API-FIRST]）和工具观测。默认开；关掉 = 不挂工具、无副作用。
 * 不由内核门控（工具挂载时现查开关），与其它迁移插件同口径。
 */
public final class ApiFirstPlugin extends FeaturePlugin {

    @Override public String id() { return "apifirst"; }
    @Override public String title() { return "接口优先（系统接口 / 已配置接口 / 深链优先，点界面兜底）"; }
    @Override public String logTag() { return "[API-FIRST]"; }
    @Override public String prefsName() { return ApiFirstSettings.PREFS; }
    @Override public String[] killFiles() { return new String[]{ApiFirstSettings.KILL}; }
    @Override public boolean kernelGated() { return false; }
    @Override public String acceptanceModule() { return null; }   // 没有设备验收模块（真机点测见 REPORT）

    @Override public boolean enabled(Context ctx) {
        return ctx != null && ApiFirstSettings.ROUTER.on(ctx);
    }

    @Override public void appendAgentTools(Context ctx, org.json.JSONArray tools, String query) {
        if (enabled(ctx)) RouteTool.append(tools);
    }

    @Override public Capability describeForAgent(Context ctx, String name) {
        return RouteTool.isSign(name) && enabled(ctx) ? RouteTool.capability() : null;
    }

    @Override public ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        if (cap == null || !RouteTool.SIGN.equals(cap.sign) || !enabled(ctx)) return null;
        return ApiFirstAndroid.execute(ctx, cap, args);
    }

    // ------------------------------------------------------------------ 控制台（v2：设置 › 功能）

    @Override public String consoleCard() { return "设置 › 功能 › 接口优先"; }
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        return ApiFirstSettings.ROUTER.on(ctx) ? "已开启" : "已关闭";
    }

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) return;
        final android.app.Activity act = host.activity();
        switchRow(host, act, root, ApiFirstSettings.ROUTER, "接口优先", "先走系统接口 / 已配置接口 / App 深链，都不行才点界面");
        switchRow(host, act, root, ApiFirstSettings.KEYED, "接口优先 · 已配置接口", "商品检索等需要你自己填 key 的接口；关了只用系统接口和深链");
    }

    private static void switchRow(com.onesay.assistant.v2.kernel.ConsoleHost host, final android.app.Activity act, android.widget.LinearLayout root,
                                  final com.onesay.assistant.v2.kernel.FeatureSwitch sw, String title, String sub) {
        boolean killed = sw.killed(act);
        com.onesay.assistant.v2.kernel.ConsoleHost.Row r = host.switchRow(root, title, killed ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : sub,
                act.getSharedPreferences(sw.prefs, Context.MODE_PRIVATE).getBoolean(sw.key, sw.def),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) { sw.set(act, on); }
                });
        if (killed) r.setEnabled(false);
    }

    @Override public String debugStatus(Context ctx) {
        return "route.open " + (enabled(ctx) ? "开" : "关") + "，已配置接口 " + (ApiFirstSettings.KEYED.on(ctx) ? "开" : "关");
    }
}
