package com.onesay.assistant.v2.platform;

/**
 * 设备能力（refactor/oss，2026-11-06）：功能注册表 {@link FeatureRegistry} 里每个功能声明自己需要哪些能力，
 * {@link DeviceCapabilityProbe} 在启动 / 打开控制台时探测，{@link FeatureAvailability} 据此给出「可用 / 置灰 + 原因」。
 *
 * <p>每项带一句「缺了它时」给用户看的原因（如「需要 root」「仅三星」），以及没有它时的替代路径（无障碍、Shizuku 等）。
 */
public enum DeviceCap {
    /** 设备已 root 且本 App 拿到了 su（以 root 执行守护是否在线为准）。 */
    ROOT("root", "需要 root", "无障碍服务（读屏 / 点按）或 Shizuku（ADB 级 shell：settings / cmd / input）"),
    /** root 执行守护（tools/kdaemon/exec_daemon，文件 IPC + HMAC）在线。 */
    EXEC_DAEMON("root 执行守护", "需要 root 执行守护（tools/kdaemon）", "Shizuku 的 ADB 级 shell 可覆盖大部分 settings / cmd / am 命令"),
    /** Bixby 键劫持守护（tools/kdaemon/hijack_daemon）在跑。 */
    KEY_DAEMON("Bixby 键守护", "需要 root 按键守护", "无障碍服务的按键过滤（音量键组合等），或悬浮球 / 设为默认数字助理"),
    /** 机身有 Bixby 键。 */
    BIXBY_KEY("Bixby 键", "需要 Bixby 键（仅三星部分机型）", "其他按键（无障碍按键过滤）、语音唤醒、默认数字助理手势、悬浮球"),
    /** 三星设备。 */
    SAMSUNG("三星设备", "仅三星", "对应的 AOSP / 厂商通用实现"),
    /** 装了三星浏览器 com.sec.android.app.sbrowser。 */
    SAMSUNG_BROWSER("三星浏览器", "需要三星浏览器", "内置 WebView（桌面 UA）或 Chrome"),
    /** 装了三星时钟 com.sec.android.app.clockpackage。 */
    SAMSUNG_CLOCK("三星时钟", "需要三星时钟", "AlarmClock 标准 Intent（各厂商时钟都支持）"),
    /** 装了三星应用商店 com.sec.android.app.samsungapps。 */
    GALAXY_STORE("三星应用商店", "需要三星应用商店", "market:// 通用商店链接或厂商商店"),
    /** 装了三星 Bixby 系统组件（唤醒提示音等资源取自这里）。 */
    BIXBY_SYSTEM("三星 Bixby 组件", "需要三星 Bixby 系统组件", "App 自带提示音"),
    /** 装了 Shizuku（是否已授权需在 Shizuku 里确认）。 */
    SHIZUKU("Shizuku", "需要 Shizuku", "root 或 无障碍服务"),
    /** 本 App 的无障碍服务已在系统设置里打开。 */
    ACCESSIBILITY("无障碍服务", "需要打开无障碍服务", "root 读屏（uiautomator dump）"),
    /** 已授予悬浮窗权限。 */
    OVERLAY("悬浮窗权限", "需要悬浮窗权限", "通知栏 / 普通 Activity"),
    /** 已授予麦克风权限。 */
    MIC("麦克风权限", "需要麦克风权限", "打字输入"),
    /** 已授予通知使用权。 */
    NOTIFICATION_LISTENER("通知使用权", "需要通知使用权", "无"),
    /** 通知权限（Android 13+ 运行时权限 POST_NOTIFICATIONS；更低版本安装即有）。前台服务通知 / 提醒通知要它才显示。 */
    POST_NOTIFICATIONS("通知权限", "需要通知权限（Android 13+）", "灵动岛 / 悬浮窗内提示"),
    /** 精确闹钟（Android 12+ SCHEDULE_EXACT_ALARM；Android 14 起新装默认不授予）。更低版本无需授权。 */
    EXACT_ALARM("精确闹钟", "需要「闹钟和提醒」权限（Android 12+）", "非精确闹钟（setWindow，可能晚几分钟）· 系统时钟 App 定闹钟"),
    /** 本 App 是默认数字助理。 */
    DEFAULT_ASSISTANT("默认数字助理", "需要设为默认数字助理", "按键 / 语音 / 悬浮球唤醒"),
    /** Android 8.0（API 26）及以上。 */
    API_26("Android 8.0+", "需要 Android 8.0 及以上", "无"),
    /** Android 11（API 30）及以上。 */
    API_30("Android 11+", "需要 Android 11 及以上", "无"),
    /** Android 14（API 34）及以上：前台服务类型强制、精确闹钟默认拒绝、后台启动 Activity 更严（只作信息展示 / 移植对照）。 */
    API_34("Android 14+", "需要 Android 14 及以上", "无"),
    /** CPU 支持 arm64-v8a（仓库只带 arm64 的本地库）。 */
    ARM64("arm64 CPU", "需要 arm64 CPU", "补齐对应 ABI 的 sherpa-onnx 库");

    /** 给人看的名字。 */
    public final String title;
    /** 缺了这项能力时，置灰开关下面写的原因。 */
    public final String missingReason;
    /** 没有这项能力时的替代路径（报告 / 开发者页用）。 */
    public final String alternative;

    /**
     * 用户自己在系统设置里就能补上的能力（权限、无障碍、默认助理）。缺这类能力时开关<b>不置灰</b>，
     * 只在副标题提示去授权；缺 root / 三星 / 系统版本这类补不上的能力才置灰。
     */
    public boolean grantable() {
        switch (this) {
            case ACCESSIBILITY:
            case OVERLAY:
            case MIC:
            case NOTIFICATION_LISTENER:
            case DEFAULT_ASSISTANT:
            case POST_NOTIFICATIONS:
            case EXACT_ALARM:
                return true;
            default:
                return false;
        }
    }

    DeviceCap(String title, String missingReason, String alternative) {
        this.title = title;
        this.missingReason = missingReason;
        this.alternative = alternative;
    }
}
