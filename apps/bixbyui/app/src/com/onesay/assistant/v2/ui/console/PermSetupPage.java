package com.onesay.assistant.v2.ui.console;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.v2.platform.ShizukuActions;

import java.util.List;

/**
 * [mz21-perm-1118] 控制台「权限配置」：首页「权限未就绪」入口点进来。按步骤列出每项权限 / 通道，每项一行：状态点 + 现状 + 一键去配置。
 * 判定全部在 {@link PermSetup}（纯 Java，JVM 测试）；这里只画和执行按钮动作。必须项红 / 黄 / 绿，可选项灰 / 绿。
 */
final class PermSetupPage extends Console.BuiltPage {

    private ConsoleStatus.Snapshot mSnap;
    private boolean mProbing;

    PermSetupPage(Console c) {
        super(c, "权限配置");
    }

    @Override String rightAction() {
        return "刷新";
    }

    @Override void onRight() {
        probe();
    }

    @Override protected View create() {
        mSnap = StatusProbe.last();
        View v = super.create();
        probe();
        return v;
    }

    @Override public void onShown() {
        super.onShown();
        probe();   // 从系统设置页 / Shizuku 回来：重新检测
    }

    private final Runnable mRefresh = new Runnable() {
        @Override public void run() {
            probe();
        }
    };

    void probe() {
        if (mProbing) return;
        mProbing = true;
        StatusProbe.run(c.act, c.compiler, new StatusProbe.Listener() {
            @Override public void onStatus(ConsoleStatus.Snapshot s) {
                mSnap = s;
                if (s.done) mProbing = false;
                if (view != null && col != null) fill();
            }
        });
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        ConsoleStatus.Snapshot s = mSnap != null ? mSnap : StatusProbe.seed(c.act);
        List<PermSetup.Item> items = PermSetup.items(s);

        TextView head = k.text(PermSetup.headline(s), ConsoleTheme.SP_ITEM + 2, k.t.text1, ConsoleKit.W_BOLD);
        head.setPadding(k.dp(8), k.dp(8), k.dp(8), k.dp(2));
        col.addView(head);
        TextView legend = k.text("必须项：红 = 缺失 · 黄 = 部分或待确认 · 绿 = 就绪\n可选项：灰 = 未开 · 绿 = 已开", ConsoleTheme.SP_SUB, k.t.text2,
                ConsoleKit.W_REGULAR);
        legend.setPadding(k.dp(8), k.dp(2), k.dp(8), 0);
        col.addView(legend);

        LinearLayout req = k.group(col, "必须（按顺序配好）");
        LinearLayout opt = k.group(col, "可选（需要时再开）");
        int nReq = 0, nOpt = 0;
        for (PermSetup.Item it : items) {
            if (it.required) row(req, it, nReq++ > 0);
            else row(opt, it, nOpt++ > 0);
        }
        k.footer(col, "执行通道：有 root 守护就用它；没有 root 时用 Shizuku（shell 身份，只跑白名单里的命令）。"
                + "去系统设置开完权限回到这里会自动重新检测。");
    }

    private void row(LinearLayout group, final PermSetup.Item it, boolean divider) {
        final ConsoleKit k = c.kit;
        if (divider) {
            View d = new View(c.act);
            d.setBackgroundColor(k.t.line);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, k.dp(0.7f)));
            dl.leftMargin = k.dp(16);
            dl.rightMargin = k.dp(16);
            group.addView(d, dl);
        }
        LinearLayout r = k.rowLayout();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(k.dp(64));
        r.setPadding(k.dp(16), k.dp(10), k.dp(12), k.dp(10));
        View dot = k.dot(k.levelColor(it.level), 10);
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) dot.getLayoutParams();
        dlp.rightMargin = k.dp(12);
        r.addView(dot, dlp);
        LinearLayout tc = k.column();
        TextView title = k.text(it.step + "  " + it.title, ConsoleTheme.SP_ITEM, k.t.text1, ConsoleKit.W_REGULAR);
        tc.addView(title);
        TextView sub = k.text(it.status, ConsoleTheme.SP_SUB, it.level == ConsoleStatus.Level.BAD ? k.t.danger
                : (it.level == ConsoleStatus.Level.WARN ? k.t.warn : k.t.text2), ConsoleKit.W_REGULAR);
        sub.setPadding(0, k.dp(2), 0, 0);
        tc.addView(sub);
        r.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (it.action != null) {
            TextView b = k.pillButton(it.action, new Runnable() {
                @Override public void run() {
                    act(it);
                }
            });
            LinearLayout.LayoutParams blp = k.lp(ViewGroup.LayoutParams.WRAP_CONTENT, k.dp(40));
            blp.leftMargin = k.dp(12);
            r.addView(b, blp);
        } else {
            TextView v = k.text(it.level == ConsoleStatus.Level.OK ? (it.required ? "就绪" : "已开") : "", ConsoleTheme.SP_BODY + 1,
                    k.levelColor(it.level), ConsoleKit.W_MEDIUM);
            v.setPadding(k.dp(12), 0, k.dp(4), 0);
            r.addView(v);
        }
        r.setContentDescription(it.step + " " + it.title + "，" + it.status);
        group.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ 一键去配置

    void act(PermSetup.Item it) {
        final String pkg = c.act.getPackageName();
        final int sdk = android.os.Build.VERSION.SDK_INT;
        String id = it.id;
        if (PermSetup.OVERLAY.equals(id)) {
            c.openOverlaySettings();
        } else if (PermSetup.MIC.equals(id)) {
            try {
                c.act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7731);
            } catch (Throwable t) {
                c.openAppSettings();
            }
        } else if (PermSetup.EXEC.equals(id)) {
            execAction(it.doWhat);
        } else if (PermSetup.LLM_KEY.equals(id)) {
            c.editApiKey(mRefresh);
        } else if (PermSetup.ASR_KEY.equals(id)) {
            c.editAsrKey(mRefresh);
        } else if (PermSetup.APP_LIST.equals(id)) {
            appListAction();
        } else if (PermSetup.A11Y.equals(id)) {
            runShizukuThen(restricted(pkg, sdk), null, new Runnable() {
                @Override public void run() {
                    open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "在列表里找到本 App 的读屏服务并打开");
                }
            });
        } else if (PermSetup.NOTIF.equals(id)) {
            if (shizukuReady()) {
                runShizukuThen(ShizukuActions.notificationAccess(pkg, sdk), "通知使用权", null);
            } else {
                open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), "在列表里打开本 App");
            }
        } else if (PermSetup.CLIPBOARD.equals(id)) {
            if (!shizukuReady()) {
                c.toast("后台读剪贴板要经 Shizuku 授予：先把第 3 步执行通道配成 Shizuku");
                return;
            }
            runShizukuThen(ShizukuActions.clipboardGrant(pkg), "后台读剪贴板", new Runnable() {
                @Override public void run() {
                    StatusProbe.rememberClipboard(c.act.getApplicationContext(), Boolean.TRUE);
                }
            });
        } else if (PermSetup.ASSISTANT.equals(id)) {
            c.openAssistRole();   // ★ feat/assist-role：和「高级 › 设为默认助理」同一套跳转顺序
        } else if (PermSetup.BATTERY.equals(id)) {
            batteryAction();
        } else if (PermSetup.CLOUD_TTS.equals(id)) {
            ConsoleStatus.Snapshot s = mSnap;
            if (s != null && Boolean.FALSE.equals(s.cloudTtsKey)) {
                c.editAsrKey(mRefresh);
                return;
            }
            new com.onesay.assistant.v2.tts.CloudTtsConfig(c.act).setEnabled(true);
            c.toast("已开启云端朗读");
            probe();
        }
    }

    private void execAction(String doWhat) {
        if (PermSetup.DO_SHIZUKU_ENABLE.equals(doWhat) || PermSetup.DO_SHIZUKU_GRANT.equals(doWhat)) {
            com.onesay.assistant.v2.platform.ExecBackendHost.setShizukuEnabled(c.act, true);
            c.act.getWindow().getDecorView().postDelayed(mRefresh, 1200);
        } else if (PermSetup.DO_SHIZUKU_START.equals(doWhat)) {
            Intent li = c.act.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
            if (li == null || !open(li, "在 Shizuku 里用「无线调试」启动服务，再回到这里")) c.toast("打不开 Shizuku");
        } else {
            c.kit.dialog().setTitle("配置执行通道")
                    .setMessage("系统设置类操作（开关蓝牙 / Wi-Fi、识屏截图、模拟按键）需要一个执行通道：\n\n"
                            + "· 有 root：装好 root 守护后回来刷新即可；\n"
                            + "· 没有 root（大多数手机）：用 Shizuku ——\n"
                            + "  1. 安装 Shizuku；\n  2. 在 Shizuku 里用「无线调试」启动服务；\n  3. 回到这里点「授权」，授权后自动启用。\n\n"
                            + "Shizuku 只执行白名单里的命令（拒绝电源键、拒绝任何 shell 拼接）。")
                    .setPositiveButton("去下载 Shizuku", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")), null);
                        }
                    })
                    .setNegativeButton("知道了", null).show();
        }
    }

    /** ★ design-1118 开源版：没有个人版的应用清单流程 —— 打开本 App 的应用信息页，让用户允许「获取应用列表」（Flyme / MIUI 等）。 */
    private void appListAction() {
        c.openAppSettings();
        c.toast("在「权限」里允许「获取应用列表 / 读取应用列表」，回来会自动重新检测");
    }

    private void batteryAction() {
        ConsoleStatus.Snapshot s = mSnap;
        if (s != null && Boolean.TRUE.equals(s.rootOk)) {
            c.joinBatteryWhitelist(mRefresh);
            return;
        }
        Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.act.getPackageName()));
        if (!open(it, null)) open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "在列表里把本 App 设为「不优化」");
    }

    private boolean open(Intent it, String hint) {
        try {
            c.act.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (hint != null) c.toast(hint);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    /** Shizuku 插件开着且此刻可用（开源版走 ExecBackendHost / ShizukuExecBackend，命令仍过白名单）。 */
    private boolean shizukuReady() {
        return Boolean.TRUE.equals(com.onesay.assistant.v2.platform.ExecBackendHost.shizukuReady(c.act))
                && com.onesay.assistant.v2.platform.ExecBackendHost.shizukuEnabled(c.act);
    }

    /** 放开「受限设置」（Android 13+ 侧载应用开无障碍 / 通知使用权前要这一步）；白名单内。 */
    private static List<String> restricted(String pkg, int sdk) {
        List<String> l = new java.util.ArrayList<String>();
        if (sdk >= 33) l.add("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow");
        return l;
    }

    /** 授权命令交给 Shizuku（仍过白名单；Shizuku 不可用就跳过命令）；做完在主线程跑 then。what = null 不弹结果。 */
    private void runShizukuThen(final List<String> cmds, final String what, final Runnable then) {
        final com.onesay.plugin.exec.shizuku.ShizukuExecBackend ex = com.onesay.assistant.v2.platform.ExecBackendHost.shizuku();
        if (ex == null || !shizukuReady() || cmds.isEmpty()) {
            if (then != null) then.run();
            return;
        }
        com.onesay.assistant.v2.util.Threads.start("perm-setup-shizuku", new Runnable() {
            @Override public void run() {
                String fail = null;
                for (String cmd : cmds) {
                    com.onesay.plugin.api.exec.ExecResult r = ex.run(cmd, 8000);
                    if (r == null || r.rc != 0) {
                        String o = r == null || r.output == null ? "" : r.output;
                        fail = o.length() > 80 ? o.substring(0, 80) : o;
                        break;
                    }
                }
                final String f = fail;
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (what != null) c.toast(f == null ? what + "：已完成" : what + "：失败（" + f + "）");
                        if (then != null && f == null) then.run();
                        else if (then != null && what == null) then.run();   // 只是放开受限设置：失败也照样打开系统页让用户手动开
                        probe();
                    }
                });
            }
        });
    }
}
