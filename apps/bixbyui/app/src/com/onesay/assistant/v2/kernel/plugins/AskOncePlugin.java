package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.askonce.AskOnce;
import com.onesay.assistant.v2.askonce.AskOnceHooks;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONObject;

/**
 * [kernel] ★ int/1102「问一次、记住」（用户 2026-11-03 拍板）：发消息没说渠道、购物走浏览器还是 App 第一次问一次并记住；
 * 「看看我的邮件」先问想看哪类；「播放音乐」没有播放器时交给默认音乐 App。委托 {@link AskOnceHooks}。默认开，kill-switch askonce.off。
 */
public final class AskOncePlugin extends FeaturePlugin {

    public static final FeatureSwitch SWITCH = new FeatureSwitch(AskOnce.PREFS, "enabled", true, "askonce.off");

    @Override public String id() { return "askonce"; }
    @Override public String title() { return "问一次记住"; }
    @Override public String logTag() { return "[ASK-ONCE]"; }
    @Override public String prefsName() { return AskOnce.PREFS; }
    @Override public String[] killFiles() { return new String[]{"askonce.off"}; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }
    @Override public String acceptanceModule() { return null; }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 510;    // 迁移插件（存图 → 摘要 → 日程 → S3 → S2 → 诊断）之后、跑腿（1500）之前：先问购物方式
            case CLAIMS: return 510;
            case INTERCEPT: return 500;   // 圈选 / 存图待选之后（它们只在各自待选时拦）
            case REPLY_SHOWN: return 900;
            case EXECUTED: return 900;
            case CONSOLE: return 210;     // 记忆页「默认应用」之后
            default: return 1000;
        }
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return AskOnceHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-ASKONCE] 先问一次: "; }
    @Override public boolean claims(Context ctx, String query) { return AskOnceHooks.claims(ctx, query); }
    @Override public boolean interceptCommand(FloatingSheetService svc, String command) { return AskOnceHooks.interceptCommand(svc, command); }
    @Override public void onReplyShown(FloatingSheetService svc, Invocation inv) { AskOnceHooks.onReplyShown(svc, inv); }
    @Override public void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) { AskOnceHooks.onExecuted(svc, cap, args, r); }

    @Override public String consoleCard() { return "记忆页「常用默认」"; }
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_MEMORY.equals(page)) return;
        AskOnceHooks.addConsole(host, root);
    }

    @Override public String consoleSummary(Context ctx) { return SWITCH.on(ctx) ? "已开启" : "已关闭"; }
    @Override public String debugStatus(Context ctx) { return AskOnceHooks.debugStatus(ctx); }
}
