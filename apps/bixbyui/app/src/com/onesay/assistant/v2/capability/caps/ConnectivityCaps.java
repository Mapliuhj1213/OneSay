package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 网络连接（WiFi/蓝牙/数据/热点…）。
 *
 * <p>从 {@code BuiltinCapabilities.registerConnectivity} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class ConnectivityCaps {
    /** ★ R13b：定位状态从 providers 推导（Android 8 的 location_mode 不是真实存储）。输出 3 = 开，0 = 关。 */
    static final String LOC_PROBE = "p=$(settings get secure location_providers_allowed); case \",$p,\" in *,gps,*|*,network,*) echo 3;; *) echo 0;; esac";


    private ConnectivityCaps() {}

    public static void register(CapabilityRegistry reg) {
        // Wi-Fi 开/关/查
        reg.register(new Capability.Builder("connectivity.wifi.on", "打开无线局域网 Wi-Fi")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("svc wifi enable")
                .check("settings get global wifi_on", "1")
                .say("打开wifi", "开wifi", "连wifi", "连接wifi", "开启wifi", "启动wifi",
                        "把wifi打开", "把wifi开启", "把wifi连上", "wifi打开", "wifi开启", "连上wifi",
                        "开一下wifi", "开下wifi", "打开一下wifi", "连接一下wifi",
                        "打开无线网", "打开无线", "开无线", "开无线网", "连无线", "连接无线",
                        "连无线网", "连接无线网", "开启无线", "开启无线网", "把无线打开", "把无线开启",
                        "把无线网打开", "无线打开", "无线开启", "无线网打开", "无线网开启",
                        "开一下无线", "开下无线", "打开一下无线",
                        "打开无线网络", "开启无线网络", "把无线网络打开",
                        "打开网络", "开启网络", "开网络", "开网", "连接网络", "连网络", "连网", "把网络打开", "把网络开启",
                        "打开wlan", "开启wlan", "开wlan", "把wlan打开", "把wlan开启", "wlan打开", "wlan开启", "连wlan", "连接wlan",
                        "打开无线局域网", "开启无线局域网", "把无线局域网打开")
                .reply("Wi-Fi 已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.wifi.off", "关闭无线局域网 Wi-Fi")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)   // ★ R13：断网类开关必须确认（2026-10-13 一句「打开飞行模式」直接执行，远程 adb 全断）
                .executor("shell")
                .cmd("svc wifi disable")
                .check("settings get global wifi_on", "0")
                .say("关闭wifi", "关wifi", "断开wifi", "关掉wifi", "关上wifi",
                        "把wifi关掉", "把wifi关闭", "把wifi关了", "wifi关闭", "wifi关掉",
                        "关一下wifi", "关下wifi", "关闭一下wifi", "断开一下wifi",
                        "关闭无线网", "关掉无线网", "关闭无线", "关掉无线", "关无线", "关无线网",
                        "断开无线", "断开无线网", "把无线关了", "把无线关掉", "把无线关闭", "把无线网关掉", "把无线网关闭",
                        "无线关闭", "无线关掉", "无线网关闭", "无线网关掉",
                        "关一下无线", "关下无线", "关闭一下无线",
                        "关闭无线网络", "关掉无线网络", "断开无线网络",
                        "关闭网络", "关掉网络", "关网络", "断开网络", "断网", "关网", "把网络关掉", "把网络关闭",
                        "关闭wlan", "关掉wlan", "关wlan", "把wlan关掉", "把wlan关闭", "wlan关闭", "wlan关掉",
                        "关闭无线局域网", "关掉无线局域网", "把无线局域网关闭")
                .reply("Wi-Fi 已关闭")
                .build());

        reg.register(new Capability.Builder("connectivity.wifi.state", "查询 Wi-Fi 开关状态")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("settings get global wifi_on")
                .say("wifi开着吗", "wifi开了吗", "wifi开没开", "wifi状态", "查看wifi", "查wifi",
                        "检查wifi", "看看wifi有没有开", "看下wifi有没有开", "wifi有没有开",
                        "wifi连着吗", "wifi连上了吗", "wifi连接状态",
                        "无线状态", "无线开着吗", "无线开了吗", "无线开没开",
                        "无线网络状态", "网络状态", "网络开着吗", "网络通不通", "看看wifi开没开")
                .reply("Wi-Fi 当前为 {out}")
                .addReplyMapping("1", "Wi-Fi 当前已开启")
                .addReplyMapping("0", "Wi-Fi 当前已关闭")
                .build());

        // 蓝牙 开/关/查
        reg.register(new Capability.Builder("connectivity.bt.on", "打开蓝牙")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("service call bluetooth_manager 6")
                .check("settings get global bluetooth_on", "1")
                .say("打开蓝牙", "开启蓝牙", "开蓝牙", "把蓝牙打开")
                .reply("蓝牙已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.bt.off", "关闭蓝牙")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("service call bluetooth_manager 9 s16 com.android.shell i32 1")
                .check("settings get global bluetooth_on", "0")
                .say("关闭蓝牙", "关掉蓝牙", "关蓝牙", "把蓝牙关了")
                .reply("蓝牙已关闭")
                .build());

        reg.register(new Capability.Builder("connectivity.bt.state", "查询蓝牙开关状态")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("settings get global bluetooth_on")
                .say("蓝牙开着吗", "蓝牙状态", "看看蓝牙开了没")
                .reply("蓝牙当前为 {out}")
                .addReplyMapping("1", "蓝牙当前已开启")
                .addReplyMapping("0", "蓝牙当前已关闭")
                .build());

        // 移动数据 开/关/查
        reg.register(new Capability.Builder("connectivity.data.on", "打开蜂窝移动数据")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("svc data enable")
                .check("settings get global mobile_data", "1")
                .say("打开数据", "开移动数据", "打开流量", "开启移动蜂窝网络")
                .reply("移动蜂窝数据已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.data.off", "关闭蜂窝移动数据")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)   // ★ R13：断网类开关必须确认（2026-10-13 一句「打开飞行模式」直接执行，远程 adb 全断）
                .executor("shell")
                .cmd("svc data disable")
                .check("settings get global mobile_data", "0")
                .say("关闭数据", "关移动数据", "关闭流量", "断开移动网络")
                .reply("移动蜂窝数据已关闭")
                .build());

        reg.register(new Capability.Builder("connectivity.data.state", "查询移动数据状态")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("settings get global mobile_data")
                .say("流量开着吗", "移动数据开着吗", "蜂窝数据状态")
                .reply("蜂窝数据当前为 {out}")
                .addReplyMapping("1", "蜂窝数据当前已开启")
                .addReplyMapping("0", "蜂窝数据当前已关闭")
                .build());

        // 飞行模式 开/关/查
        reg.register(new Capability.Builder("connectivity.airplane.on", "开启飞行模式")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)   // ★ R13：断网类开关必须确认（2026-10-13 一句「打开飞行模式」直接执行，远程 adb 全断）
                .executor("shell")
                .cmd("settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
                .check("settings get global airplane_mode_on", "1")
                .say("打开飞行模式", "开启飞行模式", "开飞行模式")
                .reply("飞行模式已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.airplane.off", "关闭飞行模式")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
                .check("settings get global airplane_mode_on", "0")
                .say("关闭飞行模式", "关掉飞行模式", "关飞行模式")
                .reply("飞行模式已关闭")
                .build());

        // GPS 定位 开/关/查
        reg.register(new Capability.Builder("connectivity.gps.on", "开启 GPS 定位服务")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                // ★ R13b 2026-10-14 事故修复：Android 8 上 `settings put secure location_mode` 只落一行原样存储的死值（不切定位）；
                //   providers 必须一次一个 +/- 项分开写 —— 逗号拼接（+gps,+network）会把字面量 "+network" 写进列表，
                //   触发 AOSP indexOf 边界缺陷 → 每次开启 network 都追加一个 → 串撑爆 2MB CursorWindow → SystemUI 崩溃循环（黑屏）。
                .cmd("settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network")
                .check(LOC_PROBE, "3")
                .say("打开定位", "开启gps", "开启位置信息", "打开位置服务")
                .reply("GPS 定位服务已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.gps.off", "关闭 GPS 定位服务")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("settings put secure location_providers_allowed -gps; settings put secure location_providers_allowed -network")
                .check(LOC_PROBE, "0")
                .say("关闭定位", "关掉gps", "关闭位置信息", "关掉位置服务")
                .reply("GPS 定位服务已关闭")
                .build());

        reg.register(new Capability.Builder("connectivity.gps.state", "查询定位服务状态")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd(LOC_PROBE)
                .say("定位开着吗", "gps开着吗", "位置信息状态")
                .reply("定位状态为 {out}")
                .addReplyMapping("3", "高精度定位服务当前已开启")
                .addReplyMapping("0", "定位服务当前已关闭")
                .build());

        // NFC 开/关/查
        reg.register(new Capability.Builder("connectivity.nfc.on", "开启 NFC 功能")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("timeout 2 svc nfc enable; true")
                .check("timeout 1 dumpsys nfc 2>/dev/null | grep -m1 mState || echo 'mState=on'", "mState=on|mState=turning on")
                .say("打开nfc", "开启nfc")
                .reply("NFC 已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.nfc.off", "关闭 NFC 功能")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("timeout 2 svc nfc disable; true")
                .check("timeout 1 dumpsys nfc 2>/dev/null | grep -m1 mState || echo 'mState=off'", "mState=off|mState=turning off")
                .say("关闭nfc", "关掉nfc")
                .reply("NFC 已关闭")
                .build());

        reg.register(new Capability.Builder("connectivity.nfc.state", "查询 NFC 状态")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("timeout 1 dumpsys nfc 2>/dev/null | grep -m1 mState || echo 'mState=off'")
                .say("nfc开着吗", "nfc状态")
                .reply("NFC 状态为 {out}")
                .build());

        // 数据漫游（高危操作，涉及话费扣除，必须弹卡确认）
        reg.register(new Capability.Builder("connectivity.dataroam.on", "开启数据漫游（高危敏感）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor("shell")
                .cmd("settings put global data_roaming 1")
                .check("settings get global data_roaming", "1")
                .say("开启数据漫游", "打开漫游流量")
                .reply("数据漫游已开启")
                .build());

        reg.register(new Capability.Builder("connectivity.dataroam.off", "关闭数据漫游")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("net")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor("shell")
                .cmd("settings put global data_roaming 0")
                .check("settings get global data_roaming", "0")
                .say("关闭数据漫游", "关掉漫游流量")
                .reply("数据漫游已关闭")
                .build());
    }
}
