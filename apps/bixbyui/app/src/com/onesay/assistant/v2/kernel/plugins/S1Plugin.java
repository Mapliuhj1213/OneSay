package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.s1.S1Hooks;
import com.onesay.assistant.v2.s1.S1Settings;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [kernel] S1「看得见的后台」：后台任务胶囊 / 取件码浮卡 / 圈一下 / 剪贴板地址芯片。
 * 委托 {@link S1Hooks}，子开关在 {@link S1Settings} 里现查（行为与迁移前一致）。
 * 后台任务的岛上生命周期（onTaskStart / onTaskStep / 确认卡 …）属于宿主的任务模型，仍由 FloatingSheetService 直接调 S1Hooks。
 */
public final class S1Plugin extends FeaturePlugin {

    @Override public String id() { return "s1"; }
    @Override public String title() { return "S1 看得见的后台（胶囊 / 取件码 / 圈一下 / 剪贴板芯片）"; }
    @Override public String logTag() { return "[S1]"; }
    @Override public String prefsName() { return S1Settings.PREF; }
    @Override public String[] killFiles() {
        return new String[]{S1Settings.KILL_ALL, S1Settings.KILL_CAPSULE, S1Settings.KILL_PICKUP, S1Settings.KILL_CIRCLE, S1Settings.KILL_CLIP};
    }
    @Override public boolean kernelGated() { return false; }

    @Override public boolean enabled(Context ctx) {
        return S1Settings.capsuleEnabled(ctx) || S1Settings.pickupEnabled(ctx)
                || S1Settings.circleEnabled(ctx) || S1Settings.clipChipEnabled(ctx);
    }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case INTERCEPT: return 100;        // 「圈一下」在存图选择之前
            case SERVICE_CREATE: return 100;   // 在 S3 之前
            case SERVICE_DESTROY: return 200;  // S3Runner 之后、存图之前
            case WAKE_EARLY: return 100;
            case CONSOLE: return 710;          // 控制台「屏幕与剪贴板」：识屏 → 圈一下 / 取件码 / 剪贴板 → 存图
            default: return 1000;
        }
    }

    @Override public boolean interceptCommand(FloatingSheetService svc, String command) {
        return S1Hooks.interceptCommand(svc, command);
    }

    @Override public void onServiceCreate(FloatingSheetService svc) {
        S1Hooks.onServiceCreate(svc);
    }

    @Override public void onServiceDestroy(FloatingSheetService svc) {
        S1Hooks.onServiceDestroy(svc);
    }

    /** 收起在岛上的前台任务被新唤醒接管（按取消处理）；定时任务 / 提醒 / 取件码不受影响（v3 多任务）。 */
    @Override public void onWakeEarly(FloatingSheetService svc) {
        S1Hooks.onNewWake(svc);
    }

    /** 第 4 阶段（D-3a）：S1 自己持有岛控制器（任务收起 / 取件码），种类登记在内核里供 status 与文档。 */
    @Override public String[] islandActivityKinds() {
        return new String[]{com.onesay.assistant.v2.kernel.IslandHost.KIND_TASK, com.onesay.assistant.v2.kernel.IslandHost.KIND_PICKUP};
    }

    @Override public String[] islandActivities() {
        return new String[]{"后台任务胶囊（步骤轻提醒 / 确认 / 完成 / 失败）", "取件码 / 取餐码浮卡", "剪贴板地址芯片"};
    }

    @Override public String consoleCard() { return "S1 开关卡（胶囊 / 自动收起 / 取件码 / 圈一下 / 剪贴板）"; }

    @Override public String debugStatus(Context ctx) { return S1Hooks.debugStatus(ctx); }

    /** debug 包调试入口 --es s1 …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "s1"; }
    /** 依赖声明（只提示不改行为）。 */
    @Override public String[] dependsOn() { return new String[]{"s2"}; }

    // ------------------------------------------------------------------ [R12] 控制台 v2（D6：第一次给 S1 加 UI，默认值不变）

    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(com.onesay.assistant.v2.kernel.ConsoleHost host, String page, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        if (com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_SCREEN.equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, "圈一下", "说「圈一下」框出屏幕一块再问",
                    S1Settings.killedFor(act, S1Settings.KILL_CIRCLE), pref(act, S1Settings.KEY_CIRCLE), null, null);
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, "取件码浮卡", "短信里的取件码挂在岛上（验证码从不显示）",
                    S1Settings.killedFor(act, S1Settings.KILL_PICKUP), pref(act, S1Settings.KEY_PICKUP), null, null);
            com.onesay.assistant.v2.kernel.ConsoleRows.featureRow(host, root, "剪贴板地址芯片", "复制了地址就给「导航 / 打车」",
                    S1Settings.killedFor(act, S1Settings.KILL_CLIP), pref(act, S1Settings.KEY_CLIP_CHIP), "s1.clip", "剪贴板地址芯片");
        } else if ("s1.clip".equals(page)) {
            com.onesay.assistant.v2.kernel.ConsoleRows.detailPage(host, root, "使用剪贴板地址芯片", S1Settings.killedFor(act, S1Settings.KILL_CLIP),
                    pref(act, S1Settings.KEY_CLIP_CHIP),
                    new String[]{"「导航到刚复制的地址」"}, new String[]{"说这句时直接用剪贴板里的地址"},
                    new com.onesay.assistant.v2.kernel.ConsoleRows.Pref[]{pref(act, S1Settings.KEY_CLIP_DEIXIS)}, null,
                    "复制了一个地址后唤醒，面板上出「导航」「打车」芯片，6 秒没人碰就消失。默认应用在「记忆」里设。");
        }
    }

    private static com.onesay.assistant.v2.kernel.ConsoleRows.Pref pref(final Context ctx, final String key) {
        return new com.onesay.assistant.v2.kernel.ConsoleRows.Pref() {
            @Override public boolean get() { return S1Settings.pref(ctx, key); }
            @Override public void set(boolean on) { S1Settings.set(ctx, key, on); }
        };
    }
}
