package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.errand.ErrandHooks;
import com.onesay.assistant.v2.errand.ErrandSettings;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [kernel] ★ lane C3 浏览器跑腿（调研 / 读链接 / 购物到结算页）。委托 {@link ErrandHooks}。
 * 新插件，按内核门控（总开关 bixby_errand.enabled，默认开；kill-switch errand.off）。
 */
public final class ErrandPlugin extends FeaturePlugin {

    @Override public String id() { return ErrandHooks.OWNER; }
    @Override public String title() { return "C3 浏览器跑腿"; }
    @Override public String logTag() { return "[ERRAND]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return ErrandSettings.PREF; }
    @Override public String[] killFiles() { return ErrandSettings.MAIN.killFiles; }
    @Override public boolean defaultOn() { return ErrandSettings.MAIN.def; }
    @Override public boolean enabled(Context ctx) { return ErrandHooks.enabled(ctx); }

    /** 排在所有迁移插件之后（它们的句子更具体），示例插件 hello（9000）之前。 */
    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER:
            case CLAIMS:
            case DISPATCH:
            case CONSOLE:
                return 1500;
            default:
                return 1000;
        }
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return ErrandHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-ERRAND] 浏览器跑腿接管: "; }
    @Override public boolean claims(Context ctx, String query) { return ErrandHooks.claims(ctx, query); }

    @Override public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        if (!ErrandHooks.SIGN.equals(inv.sign)) return false;
        ErrandHooks.onDispatch(svc, inv, command);
        return true;
    }

    /** ★ C3-5：结果卡来源编号 →「打开第三个」。只在最近摆出的编号列表是跑腿来源时接（识屏芯片更近就让给识屏）。 */
    /** ★ fix/shop-research：「好 / 你决定」接回上一轮任务（TaskContinuation）排在编号打开之前。 */
    @Override public boolean interceptCommand(FloatingSheetService svc, String command) { return ErrandHooks.interceptContinuation(svc, command) || ErrandHooks.interceptOrdinal(svc, command); }

    @Override public String[] islandActivities() { return new String[]{"跑腿进度 / 停止", "跑腿结果卡"}; }
    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_BG, IslandHost.KIND_NOTE}; }
    @Override public String consoleCard() { return "浏览器跑腿（搜索 → 网页正文 → 内置浏览器 → 三星浏览器）"; }
    @Override public String debugStatus(Context ctx) { return ErrandHooks.debugStatus(ctx); }

    // ------------------------------------------------------------------ [R12] 控制台 v2

    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        final boolean killed = ErrandSettings.MAIN.killed(act);
        if (ConsoleHost.PAGE_FEATURES.equals(page)) {
            ConsoleHost.Row r = host.switchLinkRow(root, "浏览器跑腿",
                    killed ? ConsoleHost.KILLED : "调研 / 读链接 / 购物到结算页（付款你来）",
                    ErrandSettings.MAIN.on(act), new ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) {
                            ErrandSettings.MAIN.set(act, on);
                        }
                    }, new Runnable() {
                        @Override public void run() {
                            host.openPage("errand", "浏览器跑腿");
                        }
                    });
            if (killed) r.setEnabled(false);
            return;
        }
        if (!"errand".equals(page)) return;
        final ConsoleHost.Row[] sub = new ConsoleHost.Row[3];
        ConsoleHost.Row main = host.mainSwitch(root, "使用浏览器跑腿", ErrandSettings.MAIN.on(act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                ErrandSettings.MAIN.set(act, on);
                for (ConsoleHost.Row x : sub) if (x != null) x.setEnabled(on && !killed);
            }
        });
        android.widget.LinearLayout g = host.group(root, "升级链");
        sub[0] = host.switchRow(g, "搜索不够时开内置浏览器", "电脑版网页；遇到登录 / 验证码会在灵动岛上请你接手",
                ErrandSettings.WEBVIEW.on(act), toggle(act, ErrandSettings.WEBVIEW));
        sub[1] = host.switchRow(g, "最后用默认浏览器打开", com.onesay.assistant.v2.errand.DefaultBrowser.TOGGLE_NOTE,
                ErrandSettings.BROWSER.on(act), toggle(act, ErrandSettings.BROWSER));
        sub[2] = host.switchRow(g, "购物", "搜索、比价、加购物车、进结算页；付款永远由你自己完成",
                ErrandSettings.SHOP.on(act), toggle(act, ErrandSettings.SHOP));
        for (ConsoleHost.Row x : sub) x.setEnabled(main.isOn() && !killed);
        if (killed) {
            main.setEnabled(false);
            host.footer(root, ConsoleHost.KILLED);
        }
        host.footer(root, "说「帮我调研一下…」「看看这个链接…」「在京东买…」。先用搜索（Tavily），够了就直接回答；不够再读网页正文，再不够才开浏览器。"
                + "不操作任何第三方 App。");
    }

    private static ConsoleHost.Toggle toggle(final Context ctx, final com.onesay.assistant.v2.kernel.FeatureSwitch sw) {
        return new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                sw.set(ctx, on);
            }
        };
    }
}
