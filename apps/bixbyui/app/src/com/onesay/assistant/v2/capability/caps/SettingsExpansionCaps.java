package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 设置项扩张（2026-10-03 正交覆盖：读取/写入/三星特有/页面直达）。
 *
 * <p>从 {@code BuiltinCapabilities.registerSettingsExpansion} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class SettingsExpansionCaps {

    private SettingsExpansionCaps() {}

    /**
     * 设置项能力扩张（2026-10-03）。
     *
     * <p>来源：`OneSay-设置项正交覆盖测试-20261003.md` + `settings_api_dict*.csv`。
     * 每条能力的 cmd 均**真机实测验证过**（R=读取实测 / W=写入闭环 写→校验→还原 通过）：
     * 读取 10 条、写入 8 条、三星特有 6 条、页面直达 10 条。
     *
     * <p>分类说明：读取/页面类用 {@link SideEffect#NONE}（零副作用），
     * 写入类用 {@link SideEffect#DEVICE_CONTROL}（可逆设备控制）——
     * 不再沿用"全表 DEVICE_CONTROL"的旧做法。
     *
     * <p>★ 页面直达姿势：统一 `am start -a android.settings.XXX_SETTINGS`
     * （实测 `-n ...Settings$Xxx` 别名直开会被解析回主页，不可用）。
     */
    public static void register(CapabilityRegistry reg) {
        // ================= 一、读取类（10 条，零副作用）=================

        reg.register(new Capability.Builder("connectivity.airplane.state", "查询飞行模式状态")
                .domain("settings").category("net")
                .verbs("查看").entities("setting").examples("飞行模式")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get global airplane_mode_on")
                .say("飞行模式开着吗", "飞行模式开了没", "有没有开飞行模式", "飞行模式状态")
                .reply("飞行模式当前{out}")
                .build());

        reg.register(new Capability.Builder("connectivity.dataroam.state", "查询数据漫游状态")
                .domain("settings").category("net")
                .verbs("查看").entities("setting").examples("漫游")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get global data_roaming")
                .say("漫游开着吗", "数据漫游开了没", "漫游状态")
                .reply("数据漫游当前{out}")
                .build());

        reg.register(new Capability.Builder("settings.rotation.state", "查询自动旋转状态")
                .domain("settings").category("settings")
                .verbs("查看").entities("setting").examples("自动旋转")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get system accelerometer_rotation")
                .say("自动旋转开着吗", "旋转屏幕开着没", "自动旋转状态")
                .reply("自动旋转当前{out}")
                .build());

        reg.register(new Capability.Builder("settings.screentimeout.get", "查询屏幕自动关闭时长")
                .domain("settings").category("settings")
                .verbs("查看").entities("setting").examples("息屏时长")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get system screen_off_timeout")
                .say("息屏时间是多少", "屏幕多久自动关", "自动息屏时间", "屏幕超时时间多少")
                .reply("屏幕自动关闭时间当前为 {out} 毫秒")
                .build());

        reg.register(new Capability.Builder("settings.autobrightness.state", "查询自动亮度状态")
                .domain("settings").category("settings")
                .verbs("查看").entities("setting").examples("自动亮度")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get system screen_brightness_mode")
                .say("自动亮度开着吗", "自动调节亮度开了没", "自动亮度状态")
                .reply("自动亮度当前{out}")
                .build());

        reg.register(new Capability.Builder("settings.font.get", "查询字体大小")
                .domain("settings").category("settings")
                .verbs("查看").entities("setting").examples("字体大小")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get system font_scale")
                .say("字体大小是多少", "字体多大", "字体大小", "当前字体多大")
                .reply("当前字体缩放为 {out}")
                .build());

        reg.register(new Capability.Builder("settings.autotime.state", "查询自动对时状态")
                .domain("settings").category("settings")
                .verbs("查看").entities("setting").examples("自动对时")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get global auto_time")
                .say("自动对时开着吗", "自动校准时间开着没", "网络时间开着吗")
                .reply("自动对时当前{out}")
                .build());

        reg.register(new Capability.Builder("battery.level", "查询当前剩余电量")
                .domain("settings").category("sys")
                .verbs("查看").entities("setting").examples("电量")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("dumpsys battery | grep level")
                .say("电量还剩多少", "还有多少电", "电池还剩多少", "电量多少")
                .reply("当前电量信息：{out}")
                .build());

        reg.register(new Capability.Builder("battery.saver.state", "查询省电模式状态")
                .domain("settings").category("sys")
                .verbs("查看").entities("setting").examples("省电模式")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("settings get global low_power")
                .say("省电模式开着吗", "省电开了没", "有没有开省电", "省电模式状态")
                .reply("省电模式当前{out}")
                .build());

        reg.register(new Capability.Builder("storage.free", "查询剩余存储空间")
                .domain("settings").category("sys")
                .verbs("查看").entities("setting").examples("存储空间")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("dumpsys diskstats | grep -m1 Data-Free")
                .say("还剩多少存储空间", "存储空间够吗", "手机空间还剩多少", "还有多少内存空间")
                .reply("存储空间情况：{out}")
                .build());

        // ================= 二、写入类（8 条，可逆设备控制）=================

        reg.register(new Capability.Builder("settings.autobrightness.on", "打开自动亮度")
                .domain("settings").category("settings")
                .verbs("打开", "开启").entities("setting").examples("自动亮度")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system screen_brightness_mode 1")
                .check("settings get system screen_brightness_mode", "1")
                .say("打开自动亮度", "开启自动调节亮度", "自动亮度打开")
                .reply("自动亮度已打开")
                .build());

        reg.register(new Capability.Builder("settings.autobrightness.off", "关闭自动亮度")
                .domain("settings").category("settings")
                .verbs("关闭", "关掉").entities("setting").examples("自动亮度")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system screen_brightness_mode 0")
                .check("settings get system screen_brightness_mode", "0")
                .say("关闭自动亮度", "关掉自动调节亮度", "自动亮度关闭")
                .reply("自动亮度已关闭")
                .build());

        reg.register(new Capability.Builder("volume.notif.set", "设置通知音量")
                .domain("settings").category("media")
                .verbs("设置", "调到").entities("volume").examples("通知音量")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .schema(new ParamsSchema().addInteger("level", true, 0, 15, "8"))
                .executor("shell")
                .cmd("media volume --stream 5 --set {level}")
                .check("media volume --stream 5 --get", "volume is {level}")
                .say("通知音量{n}", "通知音量调到{n}", "提醒音量{n}", "消息音量{n}")
                .reply("通知音量已调至 {level}")
                .build());

        reg.register(new Capability.Builder("volume.notif.get", "查询通知音量")
                .domain("settings").category("media")
                .verbs("查看").entities("volume").examples("通知音量")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                // ★ 必须 grep 提取，否则 media volume 的多行输出（"[v] will control..."）整段进话术
                .cmd("media volume --stream 5 --get | grep -o 'volume is [0-9]*'")
                .say("通知音量多少", "提醒音量多大", "消息音量是多少", "通知声音多大")
                .reply("通知音量当前 {out}")
                .build());

        reg.register(new Capability.Builder("settings.haptic.on", "打开触摸振动反馈")
                .domain("settings").category("settings")
                .verbs("打开", "开启").entities("setting").examples("触摸振动")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system haptic_feedback_enabled 1")
                .check("settings get system haptic_feedback_enabled", "1")
                .say("打开触摸振动", "开启按键震动", "打开触感反馈")
                .reply("触摸振动已打开")
                .build());

        reg.register(new Capability.Builder("settings.haptic.off", "关闭触摸振动反馈")
                .domain("settings").category("settings")
                .verbs("关闭", "关掉").entities("setting").examples("触摸振动")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system haptic_feedback_enabled 0")
                .check("settings get system haptic_feedback_enabled", "0")
                .say("关闭触摸振动", "关掉按键震动", "关闭触感反馈")
                .reply("触摸振动已关闭")
                .build());

        reg.register(new Capability.Builder("battery.saver.on", "打开省电模式")
                .domain("settings").category("sys")
                .verbs("打开", "开启").entities("setting").examples("省电模式")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put global low_power 1")
                .check("settings get global low_power", "1")
                .say("打开省电模式", "开启省电", "省电模式打开")
                .reply("省电模式已打开")
                .build());

        reg.register(new Capability.Builder("battery.saver.off", "关闭省电模式")
                .domain("settings").category("sys")
                .verbs("关闭", "关掉").entities("setting").examples("省电模式")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put global low_power 0")
                .check("settings get global low_power", "0")
                .say("关闭省电模式", "关掉省电", "省电模式关闭")
                .reply("省电模式已关闭")
                .build());

        // ================= 三、三星特有（6 条）=================

        reg.register(new Capability.Builder("settings.aod.on", "打开息屏提醒")
                .domain("settings").category("settings")
                .verbs("打开", "开启").entities("setting").examples("息屏提醒 AOD")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system aod_mode 1")
                .check("settings get system aod_mode", "1")
                .say("打开息屏提醒", "开启息屏显示", "打开AOD")
                .reply("息屏提醒已打开")
                .build());

        reg.register(new Capability.Builder("settings.aod.off", "关闭息屏提醒")
                .domain("settings").category("settings")
                .verbs("关闭", "关掉").entities("setting").examples("息屏提醒 AOD")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system aod_mode 0")
                .check("settings get system aod_mode", "0")
                .say("关闭息屏提醒", "关掉息屏显示", "关闭AOD")
                .reply("息屏提醒已关闭")
                .build());

        reg.register(new Capability.Builder("settings.edge_lighting.on", "打开侧屏闪光")
                .domain("settings").category("settings")
                .verbs("打开", "开启").entities("setting").examples("侧屏闪光")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system edge_lighting 1")
                .check("settings get system edge_lighting", "1")
                .say("打开侧屏闪光", "开启侧屏灯效", "打开边缘照明")
                .reply("侧屏闪光已打开")
                .build());

        reg.register(new Capability.Builder("settings.edge_lighting.off", "关闭侧屏闪光")
                .domain("settings").category("settings")
                .verbs("关闭", "关掉").entities("setting").examples("侧屏闪光")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system edge_lighting 0")
                .check("settings get system edge_lighting", "0")
                .say("关闭侧屏闪光", "关掉侧屏灯效", "关闭边缘照明")
                .reply("侧屏闪光已关闭")
                .build());

        reg.register(new Capability.Builder("settings.smartcapture.on", "打开智能截屏")
                .domain("settings").category("settings")
                .verbs("打开", "开启").entities("setting").examples("智能截屏")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system enable_smart_capture 1")
                .check("settings get system enable_smart_capture", "1")
                .say("打开智能截屏", "开启手掌滑动截屏", "打开智能捕获")
                .reply("智能截屏已打开")
                .build());

        reg.register(new Capability.Builder("settings.smartcapture.off", "关闭智能截屏")
                .domain("settings").category("settings")
                .verbs("关闭", "关掉").entities("setting").examples("智能截屏")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("shell")
                .cmd("settings put system enable_smart_capture 0")
                .check("settings get system enable_smart_capture", "0")
                .say("关闭智能截屏", "关掉手掌滑动截屏", "关闭智能捕获")
                .reply("智能截屏已关闭")
                .build());

        // ================= 四、页面直达（10 条，零副作用）=================
        // ★ 姿势：am start -a android.settings.XXX_SETTINGS（实测 -n 别名直开不可用）

        reg.register(new Capability.Builder("settings.page.wifi", "打开 WiFi 设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("WLAN设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.WIFI_SETTINGS")
                .say("打开WLAN设置", "打开wifi设置", "打开无线网络设置", "打开网络设置")
                .reply("已为您打开 WiFi 设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.sound", "打开声音设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("声音设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.SOUND_SETTINGS")
                .say("打开声音设置", "打开铃声设置", "打开音效设置", "打开声音和振动")
                .reply("已为您打开声音设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.display", "打开显示设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("显示设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.DISPLAY_SETTINGS")
                .say("打开显示设置", "打开屏幕设置", "打开字体大小设置", "打开显示和亮度")
                .reply("已为您打开显示设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.datetime", "打开日期和时间设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("日期时间设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.DATE_SETTINGS")
                .say("打开日期和时间设置", "打开时间设置", "打开日期设置")
                .reply("已为您打开日期和时间设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.location", "打开定位设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("定位设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.LOCATION_SOURCE_SETTINGS")
                .say("打开定位设置", "打开位置设置", "打开位置服务设置")
                .reply("已为您打开定位设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.storage", "打开存储设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("存储设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.INTERNAL_STORAGE_SETTINGS")
                .say("打开存储设置", "打开存储空间", "打开内存设置")
                .reply("已为您打开存储设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.battery", "打开电池设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("电池设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.BATTERY_SAVER_SETTINGS")
                .say("打开电池设置", "打开电池使用情况", "打开省电设置")
                .reply("已为您打开电池设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.notification", "打开通知设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("通知设置")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.NOTIFICATION_SETTINGS")
                .say("打开通知设置", "打开免打扰设置", "打开勿扰设置", "打开通知管理")
                .reply("已为您打开通知设置页面")
                .build());

        reg.register(new Capability.Builder("settings.page.apps", "打开应用管理页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("应用管理")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.APPLICATION_SETTINGS")
                .say("打开应用管理", "打开应用程序设置", "管理已安装应用")
                .reply("已为您打开应用管理页面")
                .build());

        reg.register(new Capability.Builder("settings.page.accessibility", "打开辅助功能设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("辅助功能")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.ACCESSIBILITY_SETTINGS")
                .say("打开辅助功能", "打开无障碍设置", "打开辅助功能设置")
                .reply("已为您打开辅助功能设置页面")
                .build());

        // ============ 应用商店族（新增）============
        //
        //   用户实测诉求：「打开应用商店下载 xxx 软件」。
        //   本机有**两个**商店：
        //     ① Google Play  com.android.vending        —— 未登录（启动即跳 add-account，无法下载）
        //     ② 三星商店     com.sec.android.app.samsungapps —— 可用（本次实测搜索正常）
        //
        //   ★ 挖接口结论（重要，避免后来人重复踩）：
        //     · `market://search?q=X`  **默认落到 Google Play**（isDefault=true），
        //       而 Play 在本机未登录 → 用户会卡在"正在核对信息…"，什么也下不了。
        //     · `android.intent.action.SEARCH` + extra 打三星 `.SearchResultActivity`：
        //       能被 root 启动，但**无论 extra 键叫什么（query/keyword/search_keyword…）
        //       搜索词都不预填**（实测逐个试过，只有输入框和键盘弹出来）→ 不可用。
        //     · **正解 = 三星商店深链** `samsungapps://SearchResult/<关键词>`：
        //       实测直接进搜索结果页且**关键词已预填**（截图确认搜索"微信"命中）。
        //       商品详情页深链 = `samsungapps://ProductDetail/<包名>`。
        //     · 深链非 exported 的坑不适用：`am start -a VIEW -d 'samsungapps://…'`
        //       由系统解析到导出的 Activity，**root 下无需 -n 也能拉起**（实测通过）。
        //
        //   ★ 设计取向（与用户"两个商店，Play 打不通要走另一个"的意图对齐）：
        //     默认走**三星商店**（本机唯一可用），Play 单独留一条能力供需要时显式调用。
        //     LLM 侧拿到 `store.search.samsung` 即可完成"下载"主诉求
        //     （打开搜索页 = 用户点一下下载按钮即可，这是商店类任务能达到的最深自动化）。

        // ① 三星商店 —— 搜索指定应用（深链，关键词预填）
        //   ★ 参数名必须用 keyword **不能**用 app：
        //     UnifiedExecutor 见到 args["app"] 会强制走"本地应用解析"，
        //     解析不到就直接报"手机上没有找到应用 xxx"（下载目标本来就没装，必然失败）。
        //     用 keyword 才拿到**原始关键词**回填进深链。
        reg.register(new Capability.Builder("store.search.samsung", "在三星应用商店搜索指定应用")
                .domain("settings").category("app")
                .verbs("打开", "下载", "安装", "搜索")
                .entities("app")
                .schema(new ParamsSchema().addString("keyword", true, ""))
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d 'samsungapps://SearchResult/{keyword}'")
                .say("打开应用商店下载{n}", "应用商店搜索{n}", "在应用商店下载{n}",
                        "应用商店下载{n}", "下载{n}", "安装{n}",
                        "在应用商店搜索{n}", "搜索{n}", "下载安装{n}")
                .reply("已在三星应用商店搜索「{keyword}」，点右侧下载即可安装")
                .build());

        // ② 三星商店 —— 商品详情页（已知包名时直达）
        //   ★ say 模板里只能用 L0 认识的槽位名（{app}/{n}/{m}/{url}/{content}），
        //     自定义槽位名（如 {pkg}）会让 buildRegex 把它当正则 `{pkg}` 量词 → 编译报错。
        //     故这里用 {n}，参数名仍是 pkg（cmd 内的 {pkg} 由通用替换处理）。
        reg.register(new Capability.Builder("store.detail.samsung", "在三星应用商店打开指定应用详情页")
                .domain("settings").category("app")
                .verbs("打开", "查看")
                .entities("app")
                .schema(new ParamsSchema().addString("pkg", true, ""))
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d 'samsungapps://ProductDetail/{pkg}'")
                .say("查看应用商店详情{n}", "打开应用商店详情页{n}", "应用详情{n}")
                .reply("已打开「{pkg}」的应用商店详情页")
                .build());

        // ③ Google Play —— 搜索（本机未登录，仅作能力登记；执行会跳登录页）
        //    ★ 故意保留并**不设为默认**：用户明确说"两个商店，Play 打不通要走另一个"。
        //    LLM 若选它，执行结果会暴露 Play 不可用（跳 add-account），便于后续做自动降级。
        reg.register(new Capability.Builder("store.search.play", "在 Google Play 搜索指定应用")
                .domain("settings").category("app")
                .verbs("打开", "下载", "安装", "搜索")
                .entities("app")
                .schema(new ParamsSchema().addString("keyword", true, ""))
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.intent.action.VIEW -d 'market://search?q={keyword}'")
                .say("在谷歌商店搜索{n}", "用谷歌商店下载{n}", "play商店下载{n}",
                        "google play下载{n}", "在play商店搜索{n}", "谷歌play下载{n}")
                .reply("已在 Google Play 搜索「{keyword}」")
                .build());

        // ④ 商店可用性探测（给 LLM/用户一个"哪个商店能用"的事实依据）
        //    判据：Play 登录态看 AccountsDb 是否有 google 账号 + vending 是否可用。
        //    这里只做**只读探测**，不改变任何状态。
        reg.register(new Capability.Builder("store.status.check", "检查本机应用商店可用状态")
                .domain("settings").category("settings")
                .verbs("检查", "查看", "查询")
                .entities("setting")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("shell")
                .cmd("A=$(dumpsys account 2>/dev/null | grep -c 'com.google'); "
                        + "echo \"google_accounts=$A\"; "
                        + "if [ \"$A\" -gt 0 ]; then echo \"play=可能可用\"; else echo \"play=未登录(不可用)\"; fi; "
                        + "if pm list packages 2>/dev/null | grep -q com.sec.android.app.samsungapps; "
                        + "then echo \"samsung=可用\"; else echo \"samsung=未安装\"; fi")
                .say("哪个应用商店能用", "检查应用商店", "应用商店状态")
                .reply("应用商店状态：{out}")
                .build());
    }
}
