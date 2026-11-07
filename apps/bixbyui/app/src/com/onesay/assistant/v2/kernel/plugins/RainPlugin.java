package com.onesay.assistant.v2.kernel.plugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.rain.RainHooks;
import com.onesay.assistant.v2.rain.RainPolicy;
import com.onesay.assistant.v2.rain.RainService;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [kernel] ★ feat/rain-minutely 分钟级降水（和风天气 /v7/minutely/5m）：
 * 语音「会下雨吗 / 雨什么时候停 / 要带伞吗 / 天气怎么样」本地接管（答复进会话历史、不挂「AI 生成」免责行）；
 * 灵动岛「12 分钟后下雨」「雨约 40 分钟后停」（提醒中枢的拉型来源）。
 *
 * <p>能力探测：Key / Host 不齐 → 语音交回原来的联网问答、岛不跑、控制台写明原因。语音接管本身不受岛提醒开关影响（只看 Key / Host / rain.off）。
 * 设计与估算：docs/RAIN-MINUTELY.md。
 */
public final class RainPlugin extends FeaturePlugin {

    public static final String ID = "rain";

    @Override public String id() { return ID; }
    @Override public String title() { return "分钟级降水"; }
    @Override public String logTag() { return "[RAIN]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return RainService.PREFS; }
    @Override public String[] killFiles() { return new String[]{RainService.KILL}; }
    @Override public boolean defaultOn() { return true; }
    /** 总开关 = 没被 rain.off 停用（语音接管只看这个 + Key / Host）；岛提醒另有子开关。 */
    @Override public boolean enabled(Context ctx) { return !RainService.killed(ctx); }
    @Override public String[] dependsOn() { return new String[]{"notice", "island"}; }

    @Override public void onInit(Context ctx) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.shortcut.ShortcutActions.weatherProbe = new com.onesay.assistant.v2.shortcut.ShortcutActions.Probe() {
            @Override public String reason() { return RainService.unavailableReason(app); }
        };
    }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 270;
            case CLAIMS: return 270;
            case SERVICE_CREATE: return 150;   // 在 S3（拉起提醒中枢）之前登记来源
            case SERVICE_DESTROY: return 900;
            default: return 1000;
        }
    }

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) { return RainHooks.route(ctx, query); }
    @Override public String routeLog() { return "[ROUTE-RAIN] 分钟级降水接管: "; }

    @Override public void onServiceCreate(FloatingSheetService svc) { RainHooks.Source.install(svc); }
    @Override public void onServiceDestroy(FloatingSheetService svc) { RainHooks.Source.uninstall(svc); }

    @Override public String[] islandActivities() {
        return new String[]{"下雨 / 停雨倒计时胶囊（60 分钟内要开始或停才放）；点开 = 和风一句话 + 2 小时柱状条；结束自动收起"};
    }
    @Override public String[] islandActivityKinds() { return new String[]{IslandHost.KIND_NOTICE}; }
    @Override public String consoleCard() { return "功能页「分钟级降水」一组"; }
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        String why = RainService.unavailableReason(ctx);
        if (why != null) return "不可用：" + why;
        return RainService.ALERTS.on(ctx) ? "已开启 · 灵动岛提醒下雨 / 停雨" : "语音可问 · 岛提醒已关";
    }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, LinearLayout root) {
        if (!ConsoleHost.PAGE_FEATURES.equals(page)) return;
        final Activity act = host.activity();
        final LinearLayout g = host.group(root, "分钟级降水");
        final String why = RainService.unavailableReason(act);
        final ConsoleHost.Row alerts = host.switchRow(g, "下雨 / 停雨提醒（灵动岛）",
                why != null ? "不可用：" + why : "60 分钟内要下或要停时上岛倒计时；亮屏每 15 分钟查一次（下雨时 5 分钟），息屏 30 分钟",
                RainService.ALERTS.on(act) && why == null, new ConsoleHost.Toggle() {
                    @Override public void onToggle(boolean on) {
                        RainService.ALERTS.set(act, on);
                        RainHooks.Source.kick(act);
                    }
                });
        if (why != null) alerts.setEnabled(false);
        final int kl = RainService.key(act).length();
        host.actionRow(g, "和风天气 API Key", kl > 0 ? "已填（" + kl + " 位，Keystore 加密只存本机）" : "未填：语音问雨和岛提醒都要它",
                kl > 0 ? "更换" : "填写", new Runnable() {
                    @Override public void run() { askText(host, act, "和风天气 API Key", true); }
                });
        boolean hs = RainService.host(act).length() > 0;
        host.actionRow(g, "和风天气 API Host", hs ? "已设置（不显示）" : "未设置：控制台 → 设置 里的 API Host", "修改", new Runnable() {
            @Override public void run() { askText(host, act, "和风天气 API Host", false); }
        });
        host.footer(root, "数据来自和风天气，只覆盖国内。估算每月约 " + RainPolicy.monthlyEstimate(16, 0, 30) + " 次（晴天）到 "
                + RainPolicy.monthlyEstimate(16, 16, 30) + " 次（天天下雨），免费额度 5 万次 / 月。只发两位小数的粗位置。");
    }

    static void askText(final ConsoleHost host, final Activity act, final String title, final boolean secret) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(secret ? "粘贴 32 位 Key" : "xxxx.re.qweatherapi.com");
        com.onesay.assistant.v2.ui.console.KeyMask.secure(new AlertDialog.Builder(act).setTitle(title).setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (secret) {
                            RainService.setKey(act, v);
                            host.toast(v.length() > 0 ? "已保存（加密）" : "已清除");
                        } else {
                            String h = RainService.cleanHost(v);
                            if (v.length() > 0 && h.length() == 0) {
                                host.toast("看着不像域名");
                                return;
                            }
                            RainService.setHost(act, h);
                            host.toast(h.length() > 0 ? "已保存" : "已恢复默认");
                        }
                        RainHooks.Source.kick(act);
                        if (secret) com.onesay.assistant.v2.ui.console.KeyMask.clearClipIf(act, v);
                    }
                }).setNegativeButton("取消", null).show());
    }

    @Override public String debugStatus(Context ctx) {
        RainHooks.Source s = RainHooks.Source.sInstanceForStatus();
        return RainService.status(ctx) + (s == null ? "" : " src{" + s.status() + "}");
    }
}
