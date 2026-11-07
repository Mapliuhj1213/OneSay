package com.onesay.assistant.v2.s1;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [island v3] 全屏检测第 3 路（root 兜底）：解析 {@code dumpsys window policy} 的 {@code mTopIsFullscreen=} / {@code mLastSystemUiFlags=}。
 * 字段名在 Note 8（Android 8.0）上需要真机确认；读不到 → 返回 null，宿主关掉这一路。解析是纯函数（JVM 可测）。
 */
public final class FullscreenPolicy {

    private FullscreenPolicy() {}

    static final Pattern TOP_FS = Pattern.compile("mTopIsFullscreen=(true|false)");
    static final Pattern SYSUI = Pattern.compile("mLastSystemUiFlags=0x([0-9a-fA-F]+)");

    /** 解析 dumpsys 输出：TRUE / FALSE；两个字段都没有 → null。 */
    public static Boolean parse(String dump) {
        if (dump == null) return null;
        Matcher m = TOP_FS.matcher(dump);
        if (m.find()) return Boolean.valueOf("true".equals(m.group(1)));
        m = SYSUI.matcher(dump);
        if (m.find()) {
            try {
                long f = Long.parseLong(m.group(1), 16);
                return Boolean.valueOf((f & 0x4L) != 0);   // View.SYSTEM_UI_FLAG_FULLSCREEN
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * ★ R11 全屏判定（纯函数）：调试覆盖 ?: (systemUiVisibility 带 FULLSCREEN) || root 读到 mTopIsFullscreen=true
     * || (root 不可用 && 探针 insets 顶部 = 0)。真机上 insets=0 而状态栏明明在（QQ 邮箱 / 桌面 / 自家控制台），所以 insets 只算「疑似」。
     */
    public static boolean verdict(Boolean override, boolean byVis, boolean byRoot, boolean byInsets, boolean rootUnavailable) {
        if (override != null) return override.booleanValue();
        return byVis || byRoot || (rootUnavailable && byInsets);
    }

    /** 一次 root 兜底读取的结果（审计 B-2：区分「读不到」与「通道正忙」）。 */
    public enum Read { FULLSCREEN, NOT_FULLSCREEN, UNAVAILABLE, BUSY }

    static final String CMD = "dumpsys window policy | grep -E 'mTopIsFullscreen|mLastSystemUiFlags' | head -n 4";

    /** 纯函数：shell 响应 → 读取结果。BUSY = 本轮没发出去（下一轮照常读）；UNAVAILABLE = 宿主关掉这一路。 */
    public static Read interpret(com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r) {
        if (r == null) return Read.UNAVAILABLE;
        if (r.isBusy) return Read.BUSY;
        if (!r.isSuccess()) return Read.UNAVAILABLE;
        Boolean b = parse(r.output);
        if (b == null) return Read.UNAVAILABLE;
        return b.booleanValue() ? Read.FULLSCREEN : Read.NOT_FULLSCREEN;
    }

    /**
     * 后台线程：经 root 守护读一次。生产实现走 {@link com.onesay.assistant.v2.executor.ShellExecutor#executePoll}
     * （拿不到 IPC 锁不排队、最多 1.5 s），不再让轮询占住其他 root 操作最长 12 s。
     */
    static Read read() {
        try {
            com.onesay.assistant.v2.executor.ShellRunner sh = sShell;
            if (sh == null) return Read.UNAVAILABLE;
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r =
                    (sh instanceof com.onesay.assistant.v2.executor.ShellExecutor)
                            ? ((com.onesay.assistant.v2.executor.ShellExecutor) sh).executePoll(CMD)
                            : sh.execute(CMD);
            return interpret(r);
        } catch (Throwable t) {
            return Read.UNAVAILABLE;
        }
    }

    static volatile com.onesay.assistant.v2.executor.ShellRunner sShell;
}
