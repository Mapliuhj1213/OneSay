package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.roles.AndroidInstallEnv;
import com.onesay.assistant.v2.roles.InstallLinkPlanner;
import com.onesay.assistant.v2.roles.RoleCatalog;
import com.onesay.assistant.v2.roles.RoleHooks;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;

import org.json.JSONObject;

/**
 * [roles] 真机验收用的调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es roles status"                         # 开关 + 每个角色的当前默认/来源/本机已装数
 * su -c "am broadcast -n $R --es roles candidates"                     # 每个角色推导出的候选（来源 seed/installed/intent/mined）+ 裁决
 * su -c "am broadcast -n $R --es roles ask --es text '打车'"            # 只跑判定（不写库、不执行）：OVERRIDE/EXPLICIT/…/CHOOSER/INSTALL
 * su -c "am broadcast -n $R --es roles set --es text 'taxi=didi'"      # 等同在选择芯片上点了滴滴（写显式默认）
 * su -c "am broadcast -n $R --es roles reset --es text 'taxi'"         # 重置该角色默认（all = 全部）
 * su -c "am broadcast -n $R --es roles choice --es text 'nav=googlemap'"  # R5：点这个芯片会怎样（SET_DEFAULT / INSTALL_ONLY），只判定不执行
 * su -c "am broadcast -n $R --es roles install --es text 'com.sdu.didi.psnger'"  # 只打印区域 + 预选链接（不联网、不跳转）
 * su -c "am broadcast -n $R --es roles net"                                      # 只读：Play 连通性 / 是否已有 VPN / 三个商店与代理 App是否安装
 * su -c "am broadcast -n $R --es roles open --es text 'com.sdu.didi.psnger'"     # 真跳转（同点击安装提示，不上屏）；国外 App 不通且无 VPN 会自动启动代理 App
 * su -c "am broadcast -n $R --es roles vpnchip --es text 'com.ubercab'"          # 等同点「开代理 App」芯片：启动代理 App + 复测，只打印结论（会断开 Tailscale！）
 * </pre>
 * 日志 tag：RolesTestDriver、Roles。
 */
final class RolesTestDriver {

    private static final String TAG = "RolesTestDriver";

    private RolesTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("roles");
        final String text = intent.getStringExtra("text");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text);
                } catch (Throwable t) {
                    Log.e(TAG, "[ROLES-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        }, "RolesTestDriver").start();
    }

    private static void dispatch(Context ctx, String cmd, String text) throws Exception {
        S2Engine e = S2Hooks.engine(ctx);
        if ("status".equals(cmd)) {
            StringBuilder sb = new StringBuilder("enabled=" + RoleHooks.enabled(ctx) + " (" + S2Settings.dump(ctx) + ")");
            for (String[] row : RoleHooks.summary(ctx)) sb.append(" | ").append(row[0]).append('=').append(row[2]).append(' ').append(row[3]);
            Log.i(TAG, "[ROLES-TEST] status " + sb);
        } else if ("candidates".equals(cmd)) {
            Log.i(TAG, "[ROLES-TEST] candidates " + RoleHooks.debugCandidates(ctx));
        } else if ("ask".equals(cmd)) {
            S2Engine.Result r = e.handle(text, S2Clock.now(), S2Settings.flags(ctx));
            Log.i(TAG, "[ROLES-TEST] ask → " + r + (r != null && r.reply != null ? " | " + r.reply.replace('\n', ' ') : "")
                    + (r != null && r.args != null ? " | args=" + r.args : "") + (r != null && r.ui != null ? " | ui=" + r.ui : ""));
        } else if ("set".equals(cmd) && text != null && text.contains("=")) {
            String[] p = text.split("=", 2);
            JSONObject a = new JSONObject().put("group", p[0].trim()).put("app_id", p[1].trim());
            S2Engine.Exec x = e.execute(S2Capabilities.SIGN_HABIT_SET, a, S2Clock.now());
            Log.i(TAG, "[ROLES-TEST] set " + text + " ok=" + x.ok + " verified=" + x.verified + " | " + x.message);
        } else if ("reset".equals(cmd)) {
            StringBuilder groups = new StringBuilder();
            if (text == null || "all".equals(text.trim())) {
                for (RoleCatalog.Role g : e.roles().roles()) {
                    if (groups.length() > 0) groups.append(',');
                    groups.append(g.id);
                }
            } else {
                groups.append(text.trim());
            }
            S2Engine.Exec x = e.execute(S2Capabilities.SIGN_HABIT_RESET, new JSONObject().put("groups", groups.toString()), S2Clock.now());
            Log.i(TAG, "[ROLES-TEST] reset " + groups + " ok=" + x.ok + " | " + x.message);
        } else if ("choice".equals(cmd) && text != null && text.contains("=")) {
            String[] p = text.split("=", 2);
            Log.i(TAG, "[ROLES-TEST] choice " + text + " → " + e.choiceAction(p[0].trim(), p[1].trim()));
        } else if ("install".equals(cmd)) {
            String pkg = text == null ? "" : text.trim();
            InstallLinkPlanner.Region rg = InstallLinkPlanner.regionOf(RoleHooks.catalog(ctx), pkg);
            InstallLinkPlanner.Link l = InstallLinkPlanner.preview(pkg, rg, RoleHooks.snapshot(ctx));
            Log.i(TAG, "[ROLES-TEST] install pkg=" + pkg + " installed=" + RoleHooks.snapshot(ctx).isInstalled(pkg)
                    + " region=" + rg.id() + " 预选(不联网)=" + l);
        } else if ("net".equals(cmd)) {
            // 只读：连通性 + 是否已有 VPN（不启动任何 App）
            AndroidInstallEnv env = new AndroidInstallEnv(ctx, RoleHooks.snapshot(ctx));
            boolean vpn;
            try {
                vpn = env.vpnActive();
            } catch (Throwable t) {
                vpn = true;
            }
            Log.i(TAG, "[ROLES-TEST] net connectivity=" + env.connectivityOk() + " vpnActive=" + vpn
                    + " vpnApp=" + env.isInstalled(InstallLinkPlanner.vpnPackage())
                    + " galaxy=" + env.isInstalled(InstallLinkPlanner.GALAXY_PKG) + " play=" + env.isInstalled(InstallLinkPlanner.PLAY_PKG));
        } else if ("open".equals(cmd)) {
            // 真跳转（同点击安装提示，但不上屏）：国外 App 不通且没有 VPN 时会自动启动代理 App！
            final String pkg = text == null ? "" : text.trim();
            RoleHooks.openInstall(ctx, null, pkg, pkg, new RoleHooks.InstallDone() {
                @Override
                public void done(InstallLinkPlanner.Decision d) {
                    Log.i(TAG, "[ROLES-TEST] open pkg=" + pkg + " → " + (d == null ? "已安装直接打开 / 包名不合法" : d.toString()));
                }
            });
        } else if ("vpnchip".equals(cmd)) {
            // 等同点了「打开代理 App（会断开当前 VPN）」：会断开 Tailscale，只在手动测试时用
            String pkg = text == null ? "" : text.trim();
            InstallLinkPlanner.Decision d = InstallLinkPlanner.afterVpnChip(pkg, new AndroidInstallEnv(ctx, RoleHooks.snapshot(ctx)));
            Log.i(TAG, "[ROLES-TEST] vpnchip pkg=" + pkg + " → " + d + "（只打印，不跳转）");
        } else {
            Log.w(TAG, "[ROLES-TEST] 未知子命令: " + cmd);
        }
    }
}
