package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 系统设置（亮度/屏幕等）。
 *
 * <p>从 {@code BuiltinCapabilities.registerSettings} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class SettingsCaps {

    private SettingsCaps() {}

    public static void register(CapabilityRegistry reg) {
        // 1. 设置屏幕亮度
        reg.register(new Capability.Builder("settings.brightness.set", "设置屏幕亮度")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("level", true, 1, 255, "120"))
                .risk(1, false)
                .executor("shell")
                .cmd("settings put system screen_brightness {level}")
                .check("settings get system screen_brightness", "{level}")
                .say("亮度调到{n}", "亮度调成{n}", "亮度设为{n}", "屏幕亮度{n}", "调亮度{n}")
                .reply("屏幕亮度已调至 {level}")
                .build());

        // 2. 提高屏幕亮度
        reg.register(new Capability.Builder("settings.brightness.up", "调高屏幕亮度")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("b=$(settings get system screen_brightness); n=$((b + 40)); [ $n -gt 255 ] && n=255; settings put system screen_brightness $n; echo $n")
                .check("settings get system screen_brightness", "")
                .say("调高亮度", "亮度高一点", "亮点", "调亮屏幕", "亮一点",
                        // 「能不能把亮度调高」l0=- → 进了 AgentLoop。补「亮度+动词」语序
                        "亮度调高", "亮度调高点", "亮度调亮", "亮度调亮点", "亮度高点", "亮度调大", "屏幕亮一点", "屏幕调亮", "调亮亮度", "亮度调高一点", "亮度调亮一点")
                .reply("屏幕亮度已调高")
                .build());

        // 3. 降低屏幕亮度
        reg.register(new Capability.Builder("settings.brightness.down", "调低屏幕亮度")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("b=$(settings get system screen_brightness); n=$((b - 40)); [ $n -lt 1 ] && n=1; settings put system screen_brightness $n; echo $n")
                .check("settings get system screen_brightness", "")
                .say("调低亮度", "亮度低一点", "暗一点", "调暗屏幕", "太亮了",
                        "亮度调低", "亮度调低点", "亮度调暗", "亮度调暗点", "亮度低点", "亮度调小", "屏幕暗一点", "屏幕调暗", "调暗亮度", "亮度调低一点", "亮度调暗一点")
                .reply("屏幕亮度已调低")
                .build());

        // 4. 查询当前亮度
        reg.register(new Capability.Builder("settings.brightness.get", "查看当前屏幕亮度")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("settings get system screen_brightness")
                .say("当前亮度是多少", "查看屏幕亮度", "亮度多少")
                .reply("当前屏幕亮度为 {out}")
                .build());

        // 5. 设置息屏超时时间（秒，系统内部以毫秒存储）
        reg.register(new Capability.Builder("settings.screentimeout.set", "设置息屏休眠时间")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("seconds", true, 5, 3600, "30"))
                .risk(1, false)
                .executor("shell")
                .cmd("settings put system screen_off_timeout {ms}")
                .check("settings get system screen_off_timeout", "{ms}")
                .say("息屏时间设为{n}秒", "息屏时间调到{n}秒", "{n}秒后息屏", "息屏休眠{n}秒", "屏幕超时{n}秒")
                .reply("息屏超时时间已设为 {seconds} 秒")
                .build());

        // 6. 开启自动旋转
        reg.register(new Capability.Builder("settings.rotation.on", "开启屏幕自动旋转")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put system accelerometer_rotation 1")
                .check("settings get system accelerometer_rotation", "1")
                .say("开启自动旋转", "打开自动旋转", "开启旋转屏幕", "自动横屏")
                .reply("屏幕自动旋转已开启")
                .build());

        // 7. 关闭自动旋转
        reg.register(new Capability.Builder("settings.rotation.off", "关闭屏幕自动旋转")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put system accelerometer_rotation 0")
                .check("settings get system accelerometer_rotation", "0")
                .say("关闭自动旋转", "锁定竖屏", "关掉旋转屏幕", "锁定屏幕方向")
                .reply("屏幕自动旋转已关闭")
                .build());

        // 8. 开启自动对时
        reg.register(new Capability.Builder("settings.autotime.on", "开启网络自动对时")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put global auto_time 1")
                .check("settings get global auto_time", "1")
                .say("开启自动对时", "打开网络同步时间", "自动校准时间")
                .reply("网络自动对时已开启")
                .build());

        // 9. 关闭自动对时
        reg.register(new Capability.Builder("settings.autotime.off", "关闭网络自动对时")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("settings")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put global auto_time 0")
                .check("settings get global auto_time", "0")
                .say("关闭自动对时", "关掉自动校准时间")
                .reply("网络自动对时已关闭")
                .build());
    }
}
