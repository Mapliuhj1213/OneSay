package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.vyrse.VyrseLinkService;
import com.onesay.assistant.v2.vyrse.VyrseSettings;
import com.onesay.assistant.v2.vyrse.VyrseUi;

/**
 * [kernel] ★ compat-vyrse：VYRSE（Windows 电脑上的 AI 助手，MIT）兼容——让 OneSay 充当 VYRSE 的「手机」。
 * 插件本体在 com.onesay.assistant.plug.vyrse.core（纯 Java，不认识 OneSay）；这里只做总开关 → 起停 {@link VyrseLinkService}、控制台页。
 * <b>默认关</b>：关着 / 没配对时不起服务、不联网。协议与安全取舍：docs/COMPAT_VYRSE.md。
 */
public final class VyrsePlugin extends FeaturePlugin {

    public static final String ID = "vyrse";

    @Override public String id() { return ID; }
    @Override public String title() { return VyrseUi.TITLE; }
    @Override public String logTag() { return "[VYRSE]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return VyrseSettings.PREF; }
    @Override public String[] killFiles() { return new String[]{VyrseSettings.KILL}; }
    @Override public boolean defaultOn() { return false; }
    @Override public boolean enabled(Context ctx) { return VyrseSettings.SWITCH.on(ctx); }
    /** 控制台入口关着时也要画出来（好让用户打开），不由内核门控；服务自己现查开关 + 配对。 */
    @Override public boolean kernelGated() { return false; }

    @Override public void onEnable(Context ctx) { VyrseLinkService.sync(ctx); }

    @Override public void onDisable(Context ctx) { VyrseLinkService.sync(ctx); }

    /** 开机 / 进程重启后面板服务起来时，按开关把连接服务拉回来（与 phongent 同一挂点）。 */
    @Override public void onServiceCreate(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        VyrseLinkService.sync(svc);
    }

    @Override public String consoleCard() { return "设置 › 功能 › VYRSE 电脑兼容（细项页 vyrse）"; }
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        if (!VyrseSettings.SWITCH.on(ctx)) return "已关闭";
        return VyrseSettings.paired(ctx) ? "已配对 " + VyrseSettings.pcName(ctx) : "未配对";
    }

    @Override public void addConsoleEntries(ConsoleHost host, String page, LinearLayout root) {
        if (ConsoleHost.PAGE_FEATURES.equals(page)) VyrseUi.addEntryRow(host, root);
        else if (VyrseUi.PAGE.equals(page)) VyrseUi.buildPage(host, root);
    }

    @Override public String debugStatus(Context ctx) { return VyrseSettings.status(ctx); }
}
