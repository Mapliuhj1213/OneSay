package com.onesay.plugin.api.exec;

/**
 * OneSay 插件接口 · 特权执行后端（纯 Java 7，不依赖 Android，可单独编译 / JVM 测试）。
 *
 * <p>开源版的「特权动作」（系统开关、读屏、存图等需要 shell / root 身份的命令）不再由编译期开关决定，
 * 而是看用户在设置里选中的执行后端此刻是否可用（{@link ExecBackends#ready()}）。
 * 后端是可选插件，默认一个都不选；不选 = 特权动作置灰。
 */
public interface ExecBackend {

    /** 三态：有 / 没有 / 不确定（不确定不等于没有）。 */
    enum Tri { YES, NO, UNKNOWN }

    /** 后端拿到的身份。 */
    enum UidLevel {
        /** uid 2000（shell）：Shizuku（adb / 无线调试启动）。 */
        SHELL,
        /** uid 0（root）。 */
        ROOT
    }

    /** 稳定 id，存进设置（例如 "root"、"root_su"、"shizuku"）。 */
    String id();

    /** 给人看的名字。 */
    String title();

    /** 这个后端（在可用时）以什么身份执行。 */
    UidLevel level();

    /** 此刻能不能用。实现应便宜、可重复调用；不确定返回 {@link Tri#UNKNOWN}。 */
    Tri available();

    /** 不可用 / 不确定时给人看的原因；可用返回 null。 */
    String reason();

    /** 执行一条命令。实现不得把命令或输出里的密钥写进日志。 */
    ExecResult run(String command, long timeoutMs);
}
