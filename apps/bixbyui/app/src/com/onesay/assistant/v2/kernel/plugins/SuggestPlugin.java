package com.onesay.assistant.v2.kernel.plugins;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;

import com.onesay.assistant.plug.suggest.core.SceneRegistry;
import com.onesay.assistant.plug.suggest.core.SuggestPlug;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.suggest.SuggestHost;
import com.onesay.assistant.v2.suggest.SuggestPlugs;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [suggest] ★ feat/suggest 主动建议（对标 Flyme Aicy 建议）：截图要不要分析、复制链接 / 单号直达、取件码计频、耳机 → 音乐、手电筒忘关、新装应用。
 * 核心在 com.onesay.assistant.plug.suggest.core（纯 Java），这里只管生命周期与控制台。
 *
 * <p>开关 = 统一插件开关 {@code suggest}（prefs bixby_plug / kill 文件 plug_suggest.off / {@code --es plug suggest=on|off}）：
 * <b>debug / release 都默认开</b>（2026-11-04 用户拍板；老用户没写过这个键也按开）；关掉时内核不调任何挂钩，S1 剪贴板 / 取件码 / 岛走原路径（零副作用）。
 */
public final class SuggestPlugin extends FeaturePlugin {

    public static final String ID = SuggestPlugs.MASTER;
    public static final String PAGE = "suggest.settings";
    static final String MASTER_SUB = "截图分析、复制链接直达、耳机 → 音乐、手电筒忘关……（岛上小卡，6 s 自动收）";

    @Override public String id() { return ID; }
    @Override public String title() { return "主动建议"; }
    @Override public String logTag() { return "[SUGGEST]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return com.onesay.assistant.v2.mikaplug.MikaPlugs.PREFS; }
    @Override public String[] killFiles() { return new String[]{"plug_" + ID + ".off"}; }
    @Override public boolean defaultOn() { return SuggestPlugs.MASTER_DEFAULT; }
    @Override public boolean enabled(Context ctx) { return SuggestPlugs.masterOn(ctx); }
    @Override public String[] dependsOn() { return new String[]{"island"}; }

    @Override public String[] islandActivities() {
        return new String[]{"主动建议卡：截图要不要分析 / 复制链接在 App 打开 / 复制单号地址芯片 / 耳机打开音乐 / 手电筒忘关 / 新装应用打开（6 s 自动收，优先级最低）"};
    }

    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_SUGGEST, IslandHost.KIND_PICKUP}; }

    @Override public void onServiceCreate(FloatingSheetService svc) {
        SuggestHost.start(svc);
    }

    @Override public void onServiceDestroy(FloatingSheetService svc) {
        SuggestHost.stop();
    }

    @Override public void onEnable(Context ctx) {
        FloatingSheetService svc = SuggestHost.service();
        if (svc != null) SuggestHost.start(svc);   // 装配时的首次启用：服务还没起来，由 onServiceCreate 启动
    }

    @Override public void onDisable(Context ctx) {
        SuggestHost.stop();
    }

    @Override public String debugStatus(Context ctx) {
        return "enabled=" + enabled(ctx) + " " + SuggestHost.status().replace('\n', ' ');
    }

    /**
     * ★ fix/shot-1104 release 内引导授权：总开关开着、截图场景开着、但没有存储权限 → 一行「截图分析需要存储权限 · 去授权」
     * （透明 StoragePermActivity 弹系统授权框；永久拒绝过就跳应用权限页）。授权后页面回前台即消失。
     */
    static void addStoragePermRow(ConsoleHost host, LinearLayout g, final Activity act) {
        if (!SuggestPlugs.masterOn(act) || !SuggestPlugs.sceneOn(act, "screenshot")) return;
        if (com.onesay.assistant.v2.suggest.sources.MediaStoreScreenshotSource.storageGranted(act)) return;
        ConsoleHost.Row r = host.actionRow(g, "截图分析需要存储权限", "授权后截屏，岛上会问要不要分析这张截图", "去授权", new Runnable() {
            @Override public void run() { com.onesay.assistant.v2.suggest.StoragePermActivity.open(act); }
        });
        r.setSubTone(ConsoleHost.TONE_WARN);
    }

    @Override public String consoleCard() { return "灵动岛页一行 + 细项页（总开关、每场景开关）"; }
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        if (FeatureSwitch.killFilePresent(ctx, "plug_" + ID + ".off")) return "已被停用";
        return enabled(ctx) ? "已开启" : "已关闭";
    }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, LinearLayout root) {
        final Activity act = host.activity();
        if (ConsoleHost.PAGE_ISLAND.equals(page) || ConsoleHost.PAGE_FEATURES.equals(page)) {
            // 用户可见的「主动建议」总开关（右边开关即时生效，点行进细项页）
            LinearLayout g = ConsoleHost.PAGE_ISLAND.equals(page) ? host.group(root, "主动建议") : root;
            final boolean killed = FeatureSwitch.killFilePresent(act, "plug_" + ID + ".off");
            ConsoleHost.Row r = host.switchLinkRow(g, "主动建议", killed ? ConsoleHost.KILLED : MASTER_SUB, SuggestPlugs.masterSelfOn(act),
                    new ConsoleHost.Toggle() {
                        @Override public void onToggle(boolean on) { SuggestPlugs.setMasterNow(act, on); }
                    }, new Runnable() {
                        @Override public void run() { host.openPage(PAGE, "主动建议"); }
                    });
            if (killed) r.setEnabled(false);
            addStoragePermRow(host, g, act);
            return;
        }
        if (!PAGE.equals(page)) return;
        final SceneRegistry sr = SuggestPlug.defaultScenes(null, null);
        final java.util.List<ConsoleHost.Row> rows = new java.util.ArrayList<ConsoleHost.Row>();
        host.mainSwitch(root, "主动建议", enabled(act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                SuggestPlugs.setMasterNow(act, on);
                for (ConsoleHost.Row r : rows) r.setEnabled(on);
            }
        });
        LinearLayout g = host.group(root, "场景（读个人数据 / 改系统设置的默认关；骨架场景还没接信号）");
        for (SceneRegistry.Entry e : sr.entries()) {
            final String sid = e.scene.id();
            String sub = (e.skeleton ? "规则骨架 · " : "") + (e.scene.privacyClass() == 0 ? "只在本机处理" : e.scene.privacyClass() == 1 ? "会读个人数据" : "会改系统设置");
            if ("screenshot".equals(sid) && !com.onesay.assistant.v2.suggest.sources.MediaStoreScreenshotSource.storageGranted(act)) sub = "需要存储权限 · " + sub;
            ConsoleHost.Row r = host.switchRow(g, e.scene.title(), sub, SuggestPlugs.sceneSelfOn(act, sid), new ConsoleHost.Toggle() {
                @Override public void onToggle(boolean on) { SuggestPlugs.setScene(act, sid, on); }
            });
            r.setEnabled(enabled(act));
            rows.add(r);
        }
        LinearLayout st = host.group(root, "状态");
        addStoragePermRow(host, st, act);
        host.valueRow(st, "运行", SuggestHost.status().split("\n")[0], "", 0);
    }
}
