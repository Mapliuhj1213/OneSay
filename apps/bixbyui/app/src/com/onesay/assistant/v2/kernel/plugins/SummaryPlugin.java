package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.TriageSwitches;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.summary.SummaryHooks;
import com.onesay.assistant.v2.summary.SummarySettings;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/** [kernel] #18 长内容摘要。委托 {@link SummaryHooks}。 */
public final class SummaryPlugin extends FeaturePlugin {

    @Override public String id() { return "summary"; }
    @Override public String title() { return "#18 长内容摘要"; }
    @Override public String logTag() { return "[SUMMARY]"; }
    @Override public String prefsName() { return SummarySettings.PREF; }
    @Override public String[] killFiles() { return new String[]{SummarySettings.KILL}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return SummaryHooks.enabled(ctx); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case L0: return 200;                // 存图之后
            case TAKEOVER: return 200;
            case CLAIMS: return 200;
            case BYPASS_SCREENREF: return 500;
            case DISPATCH: return 200;
            case SERVICE_DESTROY: return 400;
            case CONSOLE: return 400;
            default: return 1000;
        }
    }

    @Override public String triageSignal() { return TriageSwitches.SUMMARY; }
    @Override public String[] triageRules() { return new String[]{"5s-screen-summary"}; }

    @Override public Invocation l0(Context ctx, String raw) { return SummaryHooks.l0(ctx, raw); }
    @Override public String l0Log(Invocation inv) { return "L0 命中规则: screen.summary"; }
    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return SummaryHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-SUM] 长内容摘要接管: "; }
    @Override public boolean triageBeforeRouteLog(Invocation inv) { return true; }
    @Override public boolean claims(Context ctx, String query) { return SummaryHooks.claims(ctx, query); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return SummaryHooks.bypassScreenRef(ctx, command); }

    @Override public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        if (!SummaryHooks.SIGN.equals(inv.sign)) return false;
        SummaryHooks.onDispatch(svc, inv, command);
        return true;
    }

    @Override public void onServiceDestroy(FloatingSheetService svc) { SummaryHooks.onServiceDestroy(svc); }
    @Override public String consoleCard() { return "摘要卡（开关 / 截图兜底）"; }

    /** [R12] 控制台 v2：功能与权限一行 + 细项页（kill-switch 文件 summary.off 优先）。 */
    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        final boolean killed = SummarySettings.killed(act);
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_FEATURES.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleHost.Row r = host.switchLinkRow(root, "长内容摘要",
                    killed ? com.onesay.assistant.v2.kernel.ConsoleHost.KILLED : "「总结一下」读屏幕给要点",
                    SummarySettings.pref(act, SummarySettings.KEY_ENABLED), new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            SummarySettings.set(act, SummarySettings.KEY_ENABLED, on);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            host.openPage("summary", "长内容摘要");
                        }
                    });
            if (killed) r.setEnabled(false);
            return;
        }
        if (!"summary".equals(page)) return;
        final com.onesay.assistant.v2.kernel.ConsoleHost.Row[] sub = new com.onesay.assistant.v2.kernel.ConsoleHost.Row[1];
        com.onesay.assistant.v2.kernel.ConsoleHost.Row main = host.mainSwitch(root, "使用长内容摘要", SummarySettings.pref(act, SummarySettings.KEY_ENABLED),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        SummarySettings.set(act, SummarySettings.KEY_ENABLED, on);
                        if (sub[0] != null) sub[0].setEnabled(on && !killed);
                    }
                });
        android.widget.LinearLayout g = host.group(root, "细项");
        sub[0] = host.switchRow(g, "文字太少时看图总结", "会把截图发给视觉模型；禁止截图的页面不上传", SummarySettings.pref(act, SummarySettings.KEY_VL),
                new com.onesay.assistant.v2.kernel.ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        SummarySettings.set(act, SummarySettings.KEY_VL, on);
                    }
                });
        sub[0].setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, com.onesay.assistant.v2.kernel.ConsoleHost.KILLED);
        }
        host.footer(root, "说「总结一下 / 这篇讲了什么 / 太长不看」→ 读屏幕文字，给一句话 + 3–5 条要点。只读；密码 / 验证码页不读。");
    }
    @Override public String debugStatus(Context ctx) { return SummaryHooks.debugStatus(ctx); }

    /** debug 包调试入口 --es summary …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "summary"; }
    /** 依赖声明（只提示不改行为）。 */
    @Override public String[] dependsOn() { return new String[]{"s2"}; }
}
