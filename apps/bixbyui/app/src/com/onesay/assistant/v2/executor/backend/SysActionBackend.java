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
                || FastCaps.SIGN_NFC_TRANSIT.equals(cap.sign) || isTorch(cap.sign);
    }

    static boolean isTorch(String sign) {
        return "torch.on".equals(sign) || "torch.off".equals(sign) || "torch.toggle".equals(sign);
    }

    /** 手电筒最近一次已知状态（CameraManager.TorchCallback 维护；未知 = null）。 */
    private static volatile Boolean sTorchOn;
    private static volatile boolean sTorchCb;

    /** 通用手电筒：CameraManager.setTorchMode（Android 6+，不需要 root、不碰任何厂商 sysfs 节点）。 */
    private ExecutionResult torch(ExecRequest req) {
        try {
            Context ctx = req.context;
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(c).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) { id = c; break; }
            }
            if (id == null) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "这台手机没有闪光灯", "no flash", req.cap, req.args);
            if (!sTorchCb) {
                sTorchCb = true;
                final String fid = id;
                cm.registerTorchCallback(new android.hardware.camera2.CameraManager.TorchCallback() {
                    @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (fid.equals(cameraId)) sTorchOn = enabled;
                    }
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
            }
            boolean on = "torch.on".equals(req.cap.sign) || ("torch.toggle".equals(req.cap.sign) && !Boolean.TRUE.equals(sTorchOn));
            cm.setTorchMode(id, on);
            sTorchOn = on;
            return ExecutionResult.success(req.cap, req.args, on ? "手电筒已打开" : "手电筒已关闭", ExecutionResult.Verification.UNVERIFIED, "torch=" + on);
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "手电筒没打开成功（相机可能正在被占用）", t.getClass().getSimpleName(), req.cap, req.args);
        }
    }

    @Override
    public BackendOutcome run(ExecRequest req) {
        if (FastCaps.SIGN_RECENTS_CLEAR.equals(req.cap.sign)) return BackendOutcome.terminal(clearRecents(req));
        if (FastCaps.SIGN_NOTIF_CLEAR_ALL.equals(req.cap.sign)) return BackendOutcome.terminal(clearNotifications(req));
        if (FastCaps.SIGN_NFC_TRANSIT.equals(req.cap.sign)) return BackendOutcome.terminal(nfcTransit(req));
        if (isTorch(req.cap.sign)) return BackendOutcome.terminal(torch(req));
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

    /**
     * 钱包 / 交通卡应用：用<b>系统默认的 NFC 付款应用</b>（设置 › NFC › 默认付款应用，{@code Settings.Secure nfc_payment_default_component}），
     * 不写死任何厂商钱包包名。纯函数：组件串 → 包名；空 / 格式不对返回 null。
     */
    public static String pickWallet(String defaultPaymentComponent) {
        if (defaultPaymentComponent == null) return null;
        String c = defaultPaymentComponent.trim();
        int k = c.indexOf('/');
        String pkg = k > 0 ? c.substring(0, k) : c;
        return pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? pkg : null;
    }

    /** 回复文案（纯函数）。 */
    public static String nfcTransitReply(boolean nfcOk, String walletLabel) {
        String head = nfcOk ? "NFC 已打开" : "NFC 没打开成功（可以在下拉栏点一下 NFC）";
        if (walletLabel != null) return head + "，已打开「" + walletLabel + "」，把手机背面贴近闸机刷卡就行";
        return head + "。没找到默认的付款 / 交通卡应用（可以在设置 › NFC › 默认付款应用里选一个）；交通卡开在系统钱包里的话，亮屏后直接把手机背面贴近闸机即可";
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
                String comp = null;
                try {
                    comp = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "nfc_payment_default_component");
                } catch (Throwable ignored) {
                }
                String pkg = pickWallet(comp);
                Intent li = pkg != null ? pm.getLaunchIntentForPackage(pkg) : null;
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                } else if (pkg == null) {
                    // 没设默认付款应用：打开系统「默认付款应用」页让用户选
                    try {
                        ctx.startActivity(new Intent("android.settings.NFC_PAYMENT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable ignored) {
                    }
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
