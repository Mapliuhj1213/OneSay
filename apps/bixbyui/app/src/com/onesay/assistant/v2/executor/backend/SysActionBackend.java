package com.onesay.assistant.v2.executor.backend;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.caps.FastCaps;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ShellExecutor;

/**
 * ★ fix/triage-fast：快路径里需要 App 侧编排的两个系统动作（executor=custom）。
 *
 * <h3>清空后台 sys.recents.clear（用户原话：「清空后台应用它直接打开了应用管理器，而不是 menu 键清除」）</h3>
 * <ol>
 *   <li><b>系统级优先</b>：exec 守护内建固定模板 {@link RecentsClear#DAEMON_TEMPLATE}（守护 v4.4：回桌面 →
 *       {@code am stack remove} 普通应用栈 1–4（Android 8 移除栈 = 结束任务并从最近任务里删掉）→ {@code am kill-all}）。
 *       不带参数、守护执行自己的字面量。手机上还是 v4.3 时守护回 TMPL_UNKNOWN（rc=77）→ 走下一步；</li>
 *   <li><b>读屏助手兜底</b>（零 LLM、不截图）：多任务键（keyevent 187）→ 常驻 root 读屏助手读树 →
 *       找「全部关闭 / 关闭全部 / Close all」或 id 含 clear_all 的节点 → 无障碍点击；</li>
 *   <li>都不行：已经打开了最近任务界面，诚实告诉用户点「全部关闭」（NOT_DONE，不假装成功）。</li>
 * </ol>
 * <p>任何一步都<b>不</b>打开「应用管理」。
 *
 * <h3>勿扰 dnd.on / dnd.off</h3>
 * <p>Android 8 没有 {@code cmd notification set_dnd}（Android 10 才加），外部直接写 {@code zen_mode} 会被 ZenModeHelper 改回去（推断）。
 * 所以：有「勿扰权限」（NotificationManager#isNotificationPolicyAccessGranted）→ {@code setInterruptionFilter}；
 * 没有 → 先试 root 写 zen_mode 并回读，生效就算成；回读被改回 → 打开「勿扰权限」页让用户授权一次（PERMISSION_DENIED）。
 */
public final class SysActionBackend implements ExecutorBackend {

    private static final String TAG = "SysAction";

    @Override
    public String name() {
        return "sysaction";
    }

    @Override
    public boolean accepts(Capability cap, ExecRequest req) {
        if (cap == null || !"custom".equals(cap.executorType)) return false;
        return FastCaps.SIGN_RECENTS_CLEAR.equals(cap.sign) || FastCaps.SIGN_DND_ON.equals(cap.sign)
                || FastCaps.SIGN_DND_OFF.equals(cap.sign) || FastCaps.SIGN_NOTIF_CLEAR_ALL.equals(cap.sign)
                || FastCaps.SIGN_NFC_TRANSIT.equals(cap.sign);
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        if (FastCaps.SIGN_RECENTS_CLEAR.equals(req.cap.sign)) return BackendOutcome.terminal(clearRecents(req));
        if (FastCaps.SIGN_NOTIF_CLEAR_ALL.equals(req.cap.sign)) return BackendOutcome.terminal(clearNotifications(req));
        if (FastCaps.SIGN_NFC_TRANSIT.equals(req.cap.sign)) return BackendOutcome.terminal(nfcTransit(req));
        return BackendOutcome.terminal(dnd(req, FastCaps.SIGN_DND_ON.equals(req.cap.sign)));
    }

    // ------------------------------------------------------------------ 清空后台

    private ExecutionResult clearRecents(ExecRequest req) {
        long t0 = System.currentTimeMillis();
        ShellExecutor.ShellResponse r = req.shell != null ? req.shell.execute(RecentsClear.DAEMON_TEMPLATE) : null;
        if (r != null && r.isSuccess()) {
            Log.i(TAG, "[FAST] 清空后台：守护模板 rc=0 " + (System.currentTimeMillis() - t0) + "ms");
            return ExecutionResult.success(req.cap, req.args, "后台已清空", ExecutionResult.Verification.UNVERIFIED,
                    "daemon-template " + RecentsClear.summarize(r.output));
        }
        Log.i(TAG, "[FAST] 清空后台：守护模板不可用（" + (r == null ? "无通道" : (r.isDenied ? "DENY " + r.denyReason : "rc=" + r.rc))
                + "）→ 多任务键 + 读屏助手点「全部关闭」");
        if (req.shell != null) req.shell.execute("input keyevent 187");
        String how = null;
        try {
            if (req.context != null && com.onesay.assistant.v2.fastscreen.FastScreen.enabled(req.context)) {
                com.onesay.assistant.v2.fastscreen.FastScreen fs = com.onesay.assistant.v2.fastscreen.FastScreen.get(req.context);
                for (int i = 0; i < 6 && how == null; i++) {   // 最近任务动画约 300–500 ms：最多等 1.2 s
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                    com.onesay.assistant.v2.fastscreen.FsTree tree = fs.tree();
                    com.onesay.assistant.v2.fastscreen.FsTree.Node n = RecentsClear.findClearAll(tree);
                    if (n != null) {
                        boolean ok = fs.click(n.cx(), n.cy()) || fs.tap(n.cx(), n.cy());
                        if (ok) how = "helper-click " + (n.text.length() > 0 ? n.text : n.rid);
                    } else if (RecentsClear.isEmptyRecents(tree)) {
                        how = "already-empty";
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[FAST] 读屏助手兜底异常: " + t);
        }
        if (how != null) {
            Log.i(TAG, "[FAST] 清空后台：" + how + " " + (System.currentTimeMillis() - t0) + "ms");
            return ExecutionResult.success(req.cap, req.args, "already-empty".equals(how) ? "后台本来就是空的" : "后台已清空",
                    ExecutionResult.Verification.UNVERIFIED, how);
        }
        return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "已打开最近任务，点一下「全部关闭」就清空了",
                "daemon template unavailable and helper could not find clear-all", req.cap, req.args);
    }

    // ------------------------------------------------------------------ ★ int/1102 审计：清除所有通知

    /** 降级话术（没有通知使用权；守护 v4.4 拒 cmd notification，不能 root 代开）。 */
    public static final String NOTIF_NEED_ACCESS = "清通知要先给我一次「通知使用权」：在打开的页面里允许 OneSay，再说一遍就行";

    private ExecutionResult clearNotifications(ExecRequest req) {
        if (com.onesay.assistant.v2.events.EventNotificationListener.clearAll()) {
            return ExecutionResult.success(req.cap, req.args, "通知已全部清除（常驻通知保留）", ExecutionResult.Verification.UNVERIFIED,
                    "listener.cancelAllNotifications");
        }
        try {
            if (req.context != null) {
                Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                req.context.startActivity(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[FAST] 打开通知使用权页失败: " + t);
        }
        return ExecutionResult.failed(ErrorCode.PERMISSION_DENIED, NOTIF_NEED_ACCESS,
                "notification listener not connected; daemon v4.4 denies cmd notification", req.cap, req.args);
    }

    // ------------------------------------------------------------------ ★ int/1102 审计：打开 NFC 刷公交卡

    /** 钱包 / 交通卡类应用（按优先级；只打开已安装的第一个）。 */
    public static final String[] WALLET_PKGS = {
            "com.samsung.android.spay",          // Samsung Pay / 三星钱包（交通卡）
            "com.samsung.android.samsungpay.gear",
            "com.unionpay",                      // 云闪付
            "com.huawei.wallet",                 // 华为钱包
            "com.miui.tsmclient",                // 小米智能卡
            "com.finshell.wallet",               // OPPO / 一加钱包
            "com.vivo.wallet",                   // vivo 钱包
            "com.google.android.apps.walletnfcrel"};

    /** 纯函数（JVM 可测）：已装集合里按优先级挑一个钱包包名；没有返回 null。 */
    public static String pickWallet(java.util.Set<String> installed) {
        if (installed == null) return null;
        for (String p : WALLET_PKGS) if (installed.contains(p)) return p;
        return null;
    }

    /** 回复文案（纯函数）。 */
    public static String nfcTransitReply(boolean nfcOk, String walletLabel) {
        String head = nfcOk ? "NFC 已打开" : "NFC 没打开成功（可以在下拉栏点一下 NFC）";
        if (walletLabel != null) return head + "，已打开「" + walletLabel + "」，把手机背面贴近闸机刷卡就行";
        return head + "。没找到钱包 / 交通卡应用；如果交通卡开在系统钱包里，亮屏后直接把手机背面贴近闸机即可";
    }

    private ExecutionResult nfcTransit(ExecRequest req) {
        boolean nfcOk = false;
        if (req.shell != null) {
            ShellExecutor.ShellResponse r = req.shell.execute(ShellPrograms.NFC_ON);
            nfcOk = r != null && r.isSuccess();
        }
        String label = null;
        try {
            Context ctx = req.context;
            if (ctx != null) {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                java.util.Set<String> installed = new java.util.HashSet<String>();
                for (String p : WALLET_PKGS) {
                    try {
                        pm.getPackageInfo(p, 0);
                        installed.add(p);
                    } catch (Throwable ignored) {
                    }
                }
                String pkg = pickWallet(installed);
                Intent li = pkg != null ? pm.getLaunchIntentForPackage(pkg) : null;
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[FAST] 打开钱包失败: " + t);
        }
        String reply = nfcTransitReply(nfcOk, label);
        return nfcOk || label != null
                ? ExecutionResult.success(req.cap, req.args, reply, ExecutionResult.Verification.UNVERIFIED, "nfc=" + nfcOk + " wallet=" + label)
                : ExecutionResult.failed(ErrorCode.EXEC_FAILED, reply, "svc nfc enable failed; no wallet", req.cap, req.args);
    }

    // ------------------------------------------------------------------ 勿扰

    private ExecutionResult dnd(ExecRequest req, boolean on) {
        Context ctx = req.context;
        String okText = on ? "勿扰模式已打开" : "勿扰模式已关闭";
        try {
            NotificationManager nm = ctx != null ? (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE) : null;
            if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
                int now = nm.getCurrentInterruptionFilter();
                boolean verified = on ? now != NotificationManager.INTERRUPTION_FILTER_ALL : now == NotificationManager.INTERRUPTION_FILTER_ALL;
                return ExecutionResult.success(req.cap, req.args, okText,
                        verified ? ExecutionResult.Verification.VERIFIED : ExecutionResult.Verification.UNVERIFIED, "filter=" + now);
            }
            // 没授权：root 写 zen_mode + 回读（守护白名单里有 global:zen_mode）
            if (req.shell != null && ctx != null) {
                req.shell.execute("settings put global zen_mode " + (on ? "1" : "0"));
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                int z = android.provider.Settings.Global.getInt(ctx.getContentResolver(), "zen_mode", -1);
                if (on ? z > 0 : z == 0) {
                    return ExecutionResult.success(req.cap, req.args, okText, ExecutionResult.Verification.VERIFIED, "zen_mode=" + z);
                }
            }
            if (ctx != null) {
                Intent i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
            return ExecutionResult.failed(ErrorCode.PERMISSION_DENIED, "勿扰要先授权一次：在打开的页面里允许我，再说一遍就行",
                    "notification policy access not granted; zen_mode write reverted", req.cap, req.args);
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "勿扰没切成功", String.valueOf(t), req.cap, req.args);
        }
    }
}
