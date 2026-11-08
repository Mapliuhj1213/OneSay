package com.onesay.assistant.v2.platform;

import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

/**
 * 执行档位（纯 Java，JVM 可测）：普通 → Shizuku 特权（中间档）→ root。
 *
 * <ol>
 *   <li>用户选了 su 直连且可用 → 交给它（root 档）；</li>
 *   <li>选了 root 执行守护且在线 → 守护（root 档，原通道）；</li>
 *   <li>「Shizuku 特权」打开且 Shizuku 在跑、已授权 → 白名单里的命令交给 Shizuku；白名单外的<b>直接拒绝</b>
 *       （不悄悄退回守护通道去等 1.5 s 超时，也不让 Shizuku 越权执行）；</li>
 *   <li>都不是 → 原守护通道（没有 root 时它会报离线，行为与之前一致）。</li>
 * </ol>
 */
public final class ExecTierRouter {
    private ExecTierRouter() {}

    public enum Kind { DAEMON, BACKEND, DENY }

    public static final class Route {
        public final Kind kind;
        public final ExecBackend backend;
        public final String denyReason;

        Route(Kind kind, ExecBackend backend, String denyReason) {
            this.kind = kind;
            this.backend = backend;
            this.denyReason = denyReason;
        }

        @Override public String toString() {
            return kind + (backend != null ? ":" + backend.id() : "") + (denyReason != null ? ":" + denyReason : "");
        }
    }

    static final Route DAEMON = new Route(Kind.DAEMON, null, null);

    /**
     * @param selectedReady   用户选中、且此刻可用的后端（{@code ExecBackends.ready()}），可空
     * @param shizuku         Shizuku 插件后端，可空
     * @param shizukuEnabled  「Shizuku 特权」开关
     */
    public static Route route(String command, ExecBackend selectedReady, ShizukuExecBackend shizuku, boolean shizukuEnabled) {
        if (selectedReady != null && !"root".equals(selectedReady.id()) && !ShizukuExecBackend.ID.equals(selectedReady.id())) {
            return new Route(Kind.BACKEND, selectedReady, null);
        }
        if (selectedReady != null && "root".equals(selectedReady.id())) return DAEMON;
        boolean on = shizukuEnabled || (selectedReady != null && ShizukuExecBackend.ID.equals(selectedReady.id()));
        if (on && shizuku != null && shizuku.available() == ExecBackend.Tri.YES) {
            ShizukuWhitelist.Verdict v = shizuku.check(command);
            if (v != null && v.allowed) return new Route(Kind.BACKEND, shizuku, null);
            return new Route(Kind.DENY, null, "SHIZUKU_" + (v == null ? "NO_WHITELIST" : v.reason));
        }
        return DAEMON;
    }

    /** 控制台用：此刻的特权档位（root 优先，其次 Shizuku）。 */
    public static RootOps.Level tier(Boolean rootOk, Boolean shizukuReady) {
        if (Boolean.TRUE.equals(rootOk)) return RootOps.Level.ROOT;
        if (Boolean.TRUE.equals(shizukuReady)) return RootOps.Level.SHELL;
        return RootOps.Level.NONE;
    }
}
