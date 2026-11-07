package com.onesay.assistant.v2.kernel.plugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.LinearLayout;

import com.onesay.assistant.plug.phongent.core.PgNet;
import com.onesay.assistant.plug.phongent.core.PhongentPlug;
import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.ConsoleRows;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.phongent.PgSettings;
import com.onesay.assistant.v2.phongent.PhongentService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * [phongent] 多设备执行端（phongent 兼容，<b>默认关</b>）。插件本体在 com.onesay.assistant.plug.phongent.core（纯 Java，不认识 OneSay），
 * 这里只做：总开关（bixby_phongent.enabled / kill-switch phongent.off）→ 起停 {@link PhongentService}；控制台细项页。
 * 关着时零副作用：不起服务、不监听端口、不注册 mDNS、通知钩子立即返回。
 */
public final class PhongentPlugin extends FeaturePlugin {

    public static final String ID = "phongent";
    public static final String PAGE = "phongent";
    public static final FeatureSwitch SWITCH = PgSettings.SWITCH;

    @Override public String id() { return ID; }
    @Override public String title() { return "多设备执行端（phongent）"; }
    @Override public String logTag() { return "[PG]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return SWITCH.prefs; }
    @Override public String[] killFiles() { return SWITCH.killFiles; }
    @Override public boolean defaultOn() { return SWITCH.def; }
    @Override public boolean enabled(Context ctx) { return SWITCH.on(ctx); }
    @Override public String[] dependsOn() { return new String[]{"s3", "callback"}; }

    @Override public void onEnable(Context ctx) {
        PhongentService.sync(ctx);
    }

    @Override public void onDisable(Context ctx) {
        PhongentService.sync(ctx);
    }

    @Override public void onServiceCreate(com.onesay.assistant.v2.ui.FloatingSheetService svc) {
        if (enabled(svc)) PhongentService.sync(svc);
    }

    @Override public String debugStatus(Context ctx) {
        PhongentPlug p = PhongentService.plug();
        return "enabled=" + enabled(ctx) + " running=" + (p != null) + " port=" + (p == null ? -1 : p.port())
                + " peers=" + (p == null ? "?" : String.valueOf(p.peers().length())) + " rejected=" + (p == null ? 0 : p.rejectedPeers());
    }

    // ------------------------------------------------------------------ 控制台

    @Override public boolean consoleV2() { return true; }

    @Override public void addConsoleEntries(final ConsoleHost host, String page, LinearLayout root) {
        final Activity act = host.activity();
        boolean killed = SWITCH.killed(act);
        if (ConsoleHost.PAGE_FEATURES.equals(page)) {
            ConsoleRows.featureRow(host, root, title(), "让主力机（phongent 主控 App）给这台手机派任务", killed, mainPref(act), PAGE, "多设备执行端");
            return;
        }
        if (!PAGE.equals(page)) return;
        ConsoleRows.detailPage(host, root, "允许被主控连接", killed, mainPref(act),
                new String[]{"只允许 Tailscale", "把微信 / QQ 通知预览转给主控", "允许主控请求查看屏幕"},
                new String[]{"关：同一 Wi‑Fi 局域网 + Tailscale；公网地址一律拒绝",
                        "只读这两个 App 的通知；正文只在内存、不落盘", "每次还要在本机点「允许本次」；有任务在跑时不能接管"},
                new ConsoleRows.Pref[]{pref(act, PgSettings.KEY_TS_ONLY), pref(act, PgSettings.KEY_RELAY), pref(act, PgSettings.KEY_SCREEN)},
                null, null);
        LinearLayout st = host.group(root, "状态");
        PhongentPlug p = PhongentService.plug();
        List<String> addrs = PgNet.localAddresses();
        host.valueRow(st, "监听", p == null ? "未运行" : "端口 " + p.port(), p == null ? "关" : "运行中",
                p == null ? ConsoleHost.TONE_MUTED : ConsoleHost.TONE_OK);
        host.valueRow(st, "本机地址", addrs.isEmpty() ? "没有局域网 / Tailscale 地址" : join(addrs), null, ConsoleHost.TONE_NORMAL);
        if (p != null) host.valueRow(st, "已拒绝的外部连接", null, String.valueOf(p.rejectedPeers()), ConsoleHost.TONE_NORMAL);
        LinearLayout g = host.group(root, "已信任的主控");
        if (p != null) {
            JSONArray peers = p.peers();
            for (int i = 0; i < peers.length(); i++) {
                final JSONObject o = peers.optJSONObject(i);
                if (o == null) continue;
                host.actionRow(g, o.optString("name"), "上次连接 " + when(o.optLong("last")), "撤销", new Runnable() {
                    @Override public void run() {
                        host.confirm("撤销这个主控？", "撤销后它要重新申请、在本机允许才能再连。", "撤销", new Runnable() {
                            @Override public void run() {
                                PhongentPlug q = PhongentService.plug();
                                if (q != null && q.revoke(o.optString("key"))) host.toast("已撤销");
                            }
                        });
                    }
                });
            }
            host.actionRow(g, "手动配对链接", "收不到局域网发现时（例如走 Tailscale）用；链接就是凭据，只在本机看", "生成", new Runnable() {
                @Override public void run() {
                    manualLink(host, act);
                }
            });
            host.actionRow(g, "撤销全部主控", null, "撤销全部", new Runnable() {
                @Override public void run() {
                    host.confirm("撤销全部主控？", "所有主控都要重新申请。", "撤销全部", new Runnable() {
                        @Override public void run() {
                            PhongentPlug q = PhongentService.plug();
                            if (q != null) host.toast("已撤销 " + q.revokeAll() + " 个");
                        }
                    });
                }
            });
        } else {
            host.valueRow(g, "打开上面的开关后在这里管理", null, null, ConsoleHost.TONE_MUTED);
        }
        host.footer(root, "主控是 phongent App（iPhone 或 Android 11+）。第一次连接要在本机通知里点「允许连接」，之后凭 token 自动重连。"
                + "主控派的任务走 OneSay 自己的任务队列：发消息、付款、删除等动作照样在灵动岛上要你确认。传输是明文 HTTP（与 phongent 一致），"
                + "外出时建议只走 Tailscale。基于 phongent / Mika（Apache-2.0）。");
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String when(long ms) {
        if (ms <= 0) return "从未";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(ms));
    }

    private static void manualLink(final ConsoleHost host, final Activity act) {
        PhongentPlug p = PhongentService.plug();
        List<String> addrs = PgNet.localAddresses();
        if (p == null || addrs.isEmpty()) {
            host.toast("执行端没运行，或本机没有可用地址");
            return;
        }
        final String link = p.manualPairLink("手动配对", addrs.get(0), p.port());
        if (link == null) {
            host.toast("已信任的主控满了，先撤销一个");
            return;
        }
        new AlertDialog.Builder(act).setTitle("手动配对链接（含凭据）")
                .setMessage("在主控上导入这个链接即可连接；它等同于本机已允许，别发给别人。\n\n" + link)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phongent", link));
                        host.toast("已复制，导入后建议清空剪贴板");
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static ConsoleRows.Pref mainPref(final Context ctx) {
        return new ConsoleRows.Pref() {
            @Override public boolean get() { return SWITCH.on(ctx); }

            @Override public void set(boolean on) {
                SWITCH.set(ctx, on);
                PhongentService.sync(ctx);   // 关 = 立即停服务（撤端口 / mDNS / 通知来源）
            }
        };
    }

    private static ConsoleRows.Pref pref(final Context ctx, final String key) {
        return new ConsoleRows.Pref() {
            @Override public boolean get() { return PgSettings.get(ctx, key); }

            @Override public void set(boolean on) {
                PgSettings.set(ctx, key, on);
                PhongentPlug p = PhongentService.plug();
                if (!on && p != null && PgSettings.KEY_SCREEN.equals(key)) p.stopScreen();
            }
        };
    }
}
