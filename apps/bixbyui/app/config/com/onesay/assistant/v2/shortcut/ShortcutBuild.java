package com.onesay.assistant.v2.shortcut;

/**
 * 快捷指令的构建开关（随仓库的开源默认值）。
 *
 * <p>和 {@code LocalDefaults} 一样由 {@code build_linux.sh} 按顺序挑一份编进 APK：
 * {@code $LOCAL_CONFIG/com/onesay/assistant/v2/shortcut/ShortcutBuild.java} → 仓库根目录 {@code local-config/…}（未跟踪）→ 这一份。
 * 模板见 {@code local-config.example/com/onesay/assistant/v2/shortcut/ShortcutBuild.java}。字段必须是编译期常量。
 */
public final class ShortcutBuild {

    private ShortcutBuild() {}

    /**
     * 上机版专属动作（需要 root / 特权：勿扰、音量、亮度等系统开关，root 广播的简报播报）是否编进可用清单。
     * <b>开源构建默认 false</b>：这些动作在动作清单里置灰（「上机版专属」），已存的快捷指令走到这一步按「不可用」处理。
     * 自用上机版在 local-config 里改成 true；即使为 true，仍要能力探测（root + 执行守护）通过才可用。
     */
    public static final boolean PRIVILEGED_ACTIONS = false;
}
