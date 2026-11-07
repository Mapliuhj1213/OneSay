package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.roles.RoleHooks;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [kernel] 默认应用角色（打车 / 导航 / 外卖 / 听歌，数据在 app_roles.json）。委托 {@link RoleHooks}。
 * 开关复用 bixby_s2 habit + habit.off（迁移前就是这样）。onWake 夹在识屏挂钩之后，仍由宿主直接调用。
 */
public final class RolesPlugin extends FeaturePlugin {

    @Override public String id() { return "roles"; }
    @Override public String title() { return "默认应用角色"; }
    @Override public String logTag() { return "[ROLES]"; }
    @Override public String prefsName() { return "bixby_s2"; }
    @Override public String[] killFiles() { return new String[]{"s2.off", "habit.off"}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return RoleHooks.enabled(ctx); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 50;       // ★ backlog #3：安装卡在屏时「不用了 / 算了 / 不装了」最先关卡（卡不在屏时不接管）
            case CLAIMS: return 50;
            case REPLY_SHOWN: return 200;   // 诊断之后
            case EXECUTED: return 300;      // 诊断 → S2 → 角色
            case EXEC_FAILED: return 100;
            case WAKE: return 100;          // 识屏 onWake 之后
            case CONSOLE: return 200;       // S2 卡之后、诊断卡之前
            default: return 1000;
        }
    }

    @Override public void onReplyShown(FloatingSheetService svc, Invocation inv) { RoleHooks.onReplyShown(svc, inv); }
    @Override public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) { RoleHooks.onExecuted(svc, cap, args, r); }
    @Override public void onExecFailed(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) { RoleHooks.onExecFailed(svc, cap, args, r); }

    @Override public Invocation route(Context ctx, String query, com.onesay.assistant.v2.router.L2LLMCompiler l2) { return RoleHooks.routeDismiss(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-ROLES] 安装卡「不用了」: "; }
    @Override public boolean claims(Context ctx, String query) { return com.onesay.assistant.v2.roles.InstallCardState.wantsDismiss(query); }

    /** 新一轮：收起上一轮残留的安装提示。 */
    @Override public void onWake(FloatingSheetService svc) { RoleHooks.onWake(svc); }

    @Override public String consoleCard() { return "默认应用卡（每个角色一行）"; }

    /** [R12] 控制台 v2：记忆页「默认应用」分组：习惯默认值开关（S2 habit，habit.off 优先）+ 每个角色一行（当前默认 · 来源 · 本机已装数）+「重置」。 */
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_MEMORY.equals(page)) return;
        final android.app.Activity act = host.activity();
        final android.widget.LinearLayout g = host.group(root, "默认应用");
        boolean killed = com.onesay.assistant.v2.s2.S2Settings.pref(act, com.onesay.assistant.v2.s2.S2Settings.KEY_HABIT)
                && !com.onesay.assistant.v2.s2.S2Settings.habitEnabled(act);
        com.onesay.assistant.v2.kernel.ConsoleHost.Row habit = host.switchRow(g, "习惯默认值",
                killed ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : "打车 / 导航 / 外卖 / 听歌用你常用的 App",
                com.onesay.assistant.v2.s2.S2Settings.pref(act, com.onesay.assistant.v2.s2.S2Settings.KEY_HABIT), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        com.onesay.assistant.v2.s2.S2Settings.set(act, com.onesay.assistant.v2.s2.S2Settings.KEY_HABIT, on);
                    }
                });
        if (killed) habit.setEnabled(false);
        host.footer(root, "说「默认应用有哪些」「以后导航用百度地图」「重置打车默认」。没设过默认、又装了好几个 App 时，面板会让你选一次。");
        refreshRoles(host, g);
    }

    /** 角色行要读包管理器和库：后台读完再上屏（和旧卡片一样）。 */
    static void refreshRoles(final com.onesay.assistant.v2.kernel.ConsoleHost host, final android.widget.LinearLayout g) {
        final android.app.Activity act = host.activity();
        com.onesay.assistant.v2.util.Threads.start("RolesCard", new Runnable() {
            @Override public void run() {
                final java.util.List<String[]> rows;
                try {
                    rows = RoleHooks.summary(act);
                } catch (Throwable t) {
                    return;
                }
                host.ui(new Runnable() {
                    @Override public void run() {
                        // 分组第一个子 View 是「习惯默认值」行；后面的角色行每次重画
                        while (g.getChildCount() > 1) g.removeViewAt(g.getChildCount() - 1);
                        for (final String[] r : rows) {
                            host.actionRow(g, r[1] + " · " + r[2], r[3], "重置", new Runnable() {
                                @Override public void run() {
                                    com.onesay.assistant.v2.util.Threads.start("rolesplugin", new Runnable() {
                                        @Override public void run() {
                                            try {
                                                org.json.JSONObject a = new org.json.JSONObject().put("groups", r[0]);
                                                com.onesay.assistant.v2.s2.S2Hooks.engine(act).execute(
                                                        com.onesay.assistant.v2.s2.S2Capabilities.SIGN_HABIT_RESET, a, com.onesay.assistant.v2.s2.S2Clock.now());
                                            } catch (Throwable ignored) {
                                            }
                                            host.ui(new Runnable() {
                                                @Override public void run() {
                                                    host.toast("已重置「" + r[1] + "」的默认");
                                                }
                                            });
                                            refreshRoles(host, g);
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override public String debugStatus(Context ctx) { return "enabled=" + RoleHooks.enabled(ctx); }

    /** debug 包调试入口 --es roles …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "roles"; }
    /** 依赖声明（只提示不改行为）。 */
    @Override public String[] dependsOn() { return new String[]{"s2"}; }
}
