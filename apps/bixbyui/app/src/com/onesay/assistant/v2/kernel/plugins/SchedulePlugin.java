package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.sched.SchedHooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [kernel] 2026-10-24 本地日程：语音 / 文字建、查、改、删本机日程；灵动岛提醒；晨间播报；控制台列表。默认开。
 * 接管顺序在 S3 之前：「明天下午三点开会」这类陈述句以前没人接（掉进 AgentLoop → Google / 系统日历预填页），现在本地直接建；
 * 「提醒我…」「闹钟」「待办」「谷歌日历」照旧交给 S3 / 闹钟 / 连接器。全部委托 {@link SchedHooks}。
 */
public final class SchedulePlugin extends FeaturePlugin {

    @Override public String id() { return "sched"; }
    @Override public String title() { return "本地日程"; }
    @Override public String logTag() { return "[SCHED]"; }
    @Override public String prefsName() { return SchedHooks.PREFS; }
    @Override public String[] killFiles() { return new String[]{"sched.off"}; }
    @Override public boolean enabled(Context ctx) { return SchedHooks.SWITCH.on(ctx); }
    /** 验收脚本暂未加模块（上机清单见 verify-20261024-r2/schedule-device-checklist.md）。 */
    @Override public String acceptanceModule() { return null; }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 290;   // 摘要之后、S3 之前
            case CLAIMS: return 290;
            case BYPASS_SCREENREF: return 190;
            case SERVICE_CREATE: return 250;   // S3 之后（共用闹钟已就位）
            case CONSOLE: return 610;
            default: return 1000;
        }
    }

    @Override public void onInit(Context ctx) {
        if (ctx != null) com.onesay.assistant.v2.router.TriageDecider.sSchedOn = SchedHooks.enabled(ctx) && SchedHooks.routeLocal(ctx);   // ★ int/1102 分诊 6d
        if (ctx != null) SchedHooks.registerSource(ctx);
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return SchedHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-SCHED] 本地日程接管: "; }
    @Override public boolean claims(Context ctx, String query) { return SchedHooks.claims(ctx, query); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return SchedHooks.bypassScreenRef(ctx, command); }

    @Override public void onServiceCreate(FloatingSheetService svc) {
        SchedHooks.registerSource(svc);
        SchedHooks.refreshNotices(svc);
    }

    @Override public void onReplyShown(FloatingSheetService svc, Invocation inv) { SchedHooks.onReplyShown(svc, inv); }   // ★ int/1102 日程卡芯片

    @Override public String[] islandActivities() { return new String[]{"日程提醒（经消息提醒队列）"}; }

    @Override public String consoleCard() { return "功能与权限：日程（SchedConsole.addEntries）"; }
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        com.onesay.assistant.v2.sched.SchedConsole.addEntries(host, page, root);
    }

    @Override public String consoleSummary(Context ctx) {
        return SchedHooks.enabled(ctx) ? "已开启 · 存在本机" : "已关闭";
    }

    @Override public String debugStatus(Context ctx) { return SchedHooks.debugStatus(ctx); }
    @Override public String[] dependsOn() { return new String[]{"notice"}; }
}
