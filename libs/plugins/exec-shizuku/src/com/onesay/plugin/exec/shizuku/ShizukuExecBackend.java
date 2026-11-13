package com.onesay.plugin.exec.shizuku;

import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecResult;

import java.util.List;

/**
 * 「Shizuku 特权」执行后端插件（可选，默认关）—— 普通权限与 root 之间的中间档。
 *
 * <p>Shizuku（Apache-2.0，github.com/RikkaApps/Shizuku）由用户自己用有线 adb / Android 11+ 无线调试 / root 启动，
 * 给本 App 授权后，本插件以它的身份（shell uid 2000，或 root 启动时 uid 0）只执行 {@link ShizukuWhitelist} 放行的命令。
 *
 * <p>本类是纯 Java：真正碰 Shizuku API 的部分由宿主经 {@link Bridge} 注入（宿主侧用官方库 dev.rikka.shizuku:api /
 * provider）。无参构造 = 没有桥接，{@link #available()} 恒为 {@link Tri#NO}，什么都不执行。
 */
public final class ShizukuExecBackend implements ExecBackend {

    /** 宿主注入的 Shizuku 桥（Android 侧实现；单测注入假桥）。 */
    public interface Bridge {
        /** Shizuku 服务在跑（{@code Shizuku.pingBinder()}）。 */
        boolean running();

        /** 本 App 已获 Shizuku 授权（{@code checkSelfPermission() == GRANTED}）。 */
        boolean granted();

        /** Shizuku 服务进程的 uid：0 = root 启动，2000 = adb / 无线调试启动；不知道返回 -1。 */
        int uid();

        /** 以 argv 直接执行（不经 shell）。 */
        ExecResult exec(List<String> argv, long timeoutMs);
    }

    public static final String ID = "shizuku";
    public static final String REASON_NO_BRIDGE = "本版没有接入 Shizuku（缺少桥接）";
    public static final String REASON_NOT_RUNNING = "Shizuku 没在运行（在 Shizuku App 里用无线调试 / adb / root 启动它）";
    public static final String REASON_NOT_GRANTED = "还没在 Shizuku 里给本 App 授权";
    /** 被白名单拒绝时的回执码（与 root 执行守护的 RC_DENIED 同值，宿主按「被拒绝」处理）。 */
    public static final int RC_DENIED = 77;

    private final Bridge mBridge;
    private final ShizukuWhitelist mWhitelist;

    /** 没有桥接：永远不可用（保持旧行为，见 ExecBackendsTest）。 */
    public ShizukuExecBackend() {
        this(null, null);
    }

    public ShizukuExecBackend(Bridge bridge, ShizukuWhitelist whitelist) {
        mBridge = bridge;
        mWhitelist = whitelist;
    }

    @Override public String id() {
        return ID;
    }

    @Override public String title() {
        return "Shizuku 特权（无线调试 / adb / root 启动）";
    }

    @Override public UidLevel level() {
        return mBridge != null && mBridge.running() && mBridge.uid() == 0 ? UidLevel.ROOT : UidLevel.SHELL;
    }

    @Override public Tri available() {
        if (mBridge == null || mWhitelist == null) return Tri.NO;
        try {
            if (!mBridge.running()) return Tri.NO;
            return mBridge.granted() ? Tri.YES : Tri.NO;
        } catch (Throwable t) {
            return Tri.UNKNOWN;
        }
    }

    @Override public String reason() {
        if (mBridge == null || mWhitelist == null) return REASON_NO_BRIDGE;
        try {
            if (!mBridge.running()) return REASON_NOT_RUNNING;
            if (!mBridge.granted()) return REASON_NOT_GRANTED;
            return null;
        } catch (Throwable t) {
            return "Shizuku 状态读取失败";
        }
    }

    /** 这条命令能不能走本插件（不看 Shizuku 是否在线）。 */
    public boolean permits(String command) {
        return mWhitelist != null && mWhitelist.check(command).allowed;
    }

    public ShizukuWhitelist.Verdict check(String command) {
        return mWhitelist == null ? null : mWhitelist.check(command);
    }

    @Override public ExecResult run(String command, long timeoutMs) {
        if (available() != Tri.YES) {
            String r = reason();
            return ExecResult.down(r == null ? REASON_NO_BRIDGE : r);
        }
        ShizukuWhitelist.Verdict v = mWhitelist.check(command);
        if (!v.allowed) return new ExecResult(RC_DENIED, "DENY SHIZUKU_" + v.reason, false, false);
        try {
            return mBridge.exec(v.argv, timeoutMs);
        } catch (Throwable t) {
            return ExecResult.down("Shizuku 执行失败（" + t.getClass().getSimpleName() + "）");
        }
    }
}
