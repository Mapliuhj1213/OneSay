package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;

import com.onesay.assistant.v2.island.IslandSettings;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.s1.S1Hooks;

/**
 * [kernel] 灵动岛 v3（悬浮窗、状态栏下 2dp 居中）。island.off → 不画浮层，任务留在面板（2026-10-10 起没有旧胶囊兜底）。
 * 岛本身是各插件「灵动岛活动」的承载面（IslandController / IslandModel），挂钩由 S1 的服务生命周期驱动；
 * 本插件只登记开关、验收模块和状态，不新增行为。
 */
public final class IslandPlugin extends FeaturePlugin {

    @Override public String id() { return "island"; }
    @Override public String title() { return "灵动岛 v3"; }
    @Override public String logTag() { return "[ISLAND]"; }
    @Override public String prefsName() { return "bixby_island"; }
    @Override public String[] killFiles() { return new String[]{"island.off"}; }
    @Override public boolean kernelGated() { return false; }
    @Override public boolean enabled(Context ctx) { return IslandSettings.enabled(ctx); }

    @Override public String[] islandActivities() { return new String[]{"承载面：紧凑 / 展开 / 双活动 / 暂避 60 s / 全屏只弹关键事件"}; }
    @Override public String consoleCard() { return "灵动岛卡（开关 / 全屏隐藏 / 横滑暂避）"; }

    @Override public int priority(FeatureHook hook) {
        return hook == FeatureHook.CONSOLE ? 500 : 1000;
    }

    /** [R12] 控制台 v2：灵动岛页（示意图 03）：预览 + 主开关 + 显示（全屏隐藏 / 横滑暂避）+ 权限（悬浮窗）。island.off 优先。 */
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        if (IslandSettings.killed(ctx)) return "已被停用";
        if (!IslandSettings.pref(ctx, IslandSettings.KEY_ENABLED)) return "已关闭";
        boolean overlayOk = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx);
        if (!overlayOk) return "缺悬浮窗权限";
        return "已开启" + (IslandSettings.pref(ctx, IslandSettings.KEY_FULLSCREEN_HIDE) ? " · 全屏时隐藏" : " · 全屏也显示");
    }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, android.widget.LinearLayout root) {
        if (!ConsoleHost.PAGE_ISLAND.equals(page)) return;
        final android.app.Activity act = host.activity();
        final boolean killed = IslandSettings.killed(act);
        root.addView(new com.onesay.assistant.v2.ui.console.IslandPreview(act).card());
        final ConsoleHost.Row[] subs = new ConsoleHost.Row[2];
        ConsoleHost.Row main = host.mainSwitch(root, "使用灵动岛", IslandSettings.pref(act, IslandSettings.KEY_ENABLED), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                IslandSettings.set(act, IslandSettings.KEY_ENABLED, on);
                S1Hooks.refreshIslandMode("console");
                for (ConsoleHost.Row r : subs) if (r != null) r.setEnabled(on && !killed);
            }
        });
        android.widget.LinearLayout g = host.group(root, "显示");
        subs[0] = host.switchRow(g, "全屏时隐藏", "只弹确认、失败这类关键事件", IslandSettings.pref(act, IslandSettings.KEY_FULLSCREEN_HIDE),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        IslandSettings.set(act, IslandSettings.KEY_FULLSCREEN_HIDE, on);
                    }
                });
        subs[1] = host.switchRow(g, "横滑暂避 60 秒", "关键事件会提前叫回", IslandSettings.pref(act, IslandSettings.KEY_TUCK),
                new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        IslandSettings.set(act, IslandSettings.KEY_TUCK, on);
                    }
                });
        for (ConsoleHost.Row r : subs) r.setEnabled(main.isOn() && !killed);
        android.widget.LinearLayout p = host.group(root, "权限");
        boolean overlayOk = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(act);
        if (overlayOk) {
            host.valueRow(p, "悬浮窗权限", "岛靠它显示，不用无障碍", "已授权", ConsoleHost.TONE_OK);
        } else {
            host.actionRow(p, "悬浮窗权限", "没授权时岛显示不了，长任务只留在面板上", "去授权", new Runnable() {
                @Override public void run() {
                    try {
                        act.startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + act.getPackageName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        host.toast("打不开悬浮窗设置：" + t.getMessage());
                    }
                }
            });
        }
        if (killed) {
            main.setEnabled(false);
            host.footer(root, ConsoleHost.KILLED);
        }
        host.footer(root, "后台任务、确认卡、提醒和取件码显示在状态栏下方居中。关掉后不显示浮层，任务留在面板里。");
    }
    @Override public String debugStatus(Context ctx) { return IslandSettings.dump(ctx); }

    /** debug 包调试入口 --es island …（见 test/PluginDebugDrivers）。 */
    @Override public String debugExtra() { return "island"; }
}
