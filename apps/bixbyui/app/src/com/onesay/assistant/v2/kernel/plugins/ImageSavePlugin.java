package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.imgsave.ImageSaveHooks;
import com.onesay.assistant.v2.imgsave.ImageSaveSettings;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.TriageSwitches;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/** [kernel] 语音存图（image.save）。委托 {@link ImageSaveHooks}。 */
public final class ImageSavePlugin extends FeaturePlugin {

    @Override public String id() { return "imgsave"; }
    @Override public String title() { return "语音存图"; }
    @Override public String logTag() { return "[IMGSAVE]"; }
    @Override public String prefsName() { return "bixby_imgsave"; }
    @Override public String[] killFiles() { return new String[]{"imgsave.off"}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return ImageSaveHooks.enabled(ctx); }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case L0: return 100;                // 最先（在摘要之前）
            case TAKEOVER: return 100;
            case CLAIMS: return 100;
            case INTERCEPT: return 200;         // 圈一下之后
            case BYPASS_SCREENREF: return 400;
            case DISPATCH: return 100;
            case SERVICE_DESTROY: return 300;
            case CONSOLE: return 720;
            default: return 1000;
        }
    }

    @Override public String triageSignal() { return TriageSwitches.IMAGE_SAVE; }
    @Override public String[] triageRules() { return new String[]{"6i-image-save"}; }

    @Override public Invocation l0(Context ctx, String raw) { return ImageSaveHooks.l0(ctx, raw); }
    @Override public String l0Log(Invocation inv) { return "L0 命中规则: image.save " + inv.args; }
    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return ImageSaveHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-IMG] 语音存图接管: "; }
    @Override public boolean triageBeforeRouteLog(Invocation inv) { return true; }
    @Override public boolean claims(Context ctx, String query) { return ImageSaveHooks.claims(ctx, query); }
    @Override public boolean interceptCommand(FloatingSheetService svc, String command) { return ImageSaveHooks.interceptCommand(svc, command); }
    @Override public boolean bypassScreenRef(Context ctx, String command) { return ImageSaveHooks.bypassScreenRef(ctx, command); }

    @Override public boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        if (!ImageSaveHooks.SIGN.equals(inv.sign)) return false;
        ImageSaveHooks.onDispatch(svc, inv, command);
        return true;
    }

    @Override public void onServiceDestroy(FloatingSheetService svc) { ImageSaveHooks.onServiceDestroy(svc); }
    @Override public String consoleCard() { return null; }
    @Override public String debugStatus(Context ctx) { return ImageSaveHooks.debugStatus(ctx); }

    /** debug 包调试入口 --es imgsave …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "imgsave"; }

    // ------------------------------------------------------------------ [R12] 控制台 v2（D6：第一次给存图加 UI，默认值不变）

    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        boolean killed = ImageSaveSettings.killed(act);
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_SCREEN.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, "保存图片", "「保存这张图片」存到相册", killed,
                    pref(act, ImageSaveSettings.KEY_ENABLED), "imgsave", "保存图片");
        } else if ("imgsave".equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.detailPage(host, root, "使用保存图片", killed, pref(act, ImageSaveSettings.KEY_ENABLED),
                    new String[]{"先找应用缓存里的原图"}, new String[]{"找不到再截屏裁剪"},
                    new com.onesay.assistant.v2.kernel.ConsoleRows.Pref[]{pref(act, ImageSaveSettings.KEY_CACHE)},
                    new boolean[]{ImageSaveSettings.cacheKilled(act)},
                    "说「保存这张图片」「下载这张图片」：屏幕上有几张图就给编号芯片选。");
        }
    }

    private static com.onesay.assistant.v2.kernel.ConsoleRows.Pref pref(final Context ctx, final String key) {
        return new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {
            @Override public boolean get() { return ImageSaveSettings.pref(ctx, key); }
            @Override public void set(boolean on) { ImageSaveSettings.set(ctx, key, on); }
        };
    }
}
