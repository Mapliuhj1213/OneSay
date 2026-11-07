package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 系统与硬件（电量/存储/重启…，末尾调用设置项扩张）。
 *
 * <p>从 {@code BuiltinCapabilities.registerSystemAndHardware} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class SystemCaps {

    private SystemCaps() {}

    public static void register(CapabilityRegistry reg) {
        // 手电筒
        reg.register(new Capability.Builder("torch.on", "打开手电筒照明")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("echo 1 > /sys/class/leds/torch-sec1/brightness")
                .check("cat /sys/class/leds/torch-sec1/brightness", "1")
                // 补「闪光灯」与「开/关在后」的说法，走 L0 直出
                .say("打开手电筒", "开手电筒", "手电筒打开", "照亮一下",
                        "打开手电", "开手电", "手电筒开", "打开闪光灯", "开闪光灯", "闪光灯打开", "闪光灯开")
                .reply("手电筒已打开")
                .build());

        reg.register(new Capability.Builder("torch.off", "关闭手电筒照明")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("echo 0 > /sys/class/leds/torch-sec1/brightness")
                .check("cat /sys/class/leds/torch-sec1/brightness", "0")
                .say("关闭手电筒", "关掉手电筒", "关手电筒",
                        "关闭手电", "关手电", "手电筒关", "手电筒关闭", "手电筒关掉",
                        "关闭闪光灯", "关掉闪光灯", "关闪光灯", "闪光灯关闭", "闪光灯关掉", "闪光灯关")
                .reply("手电筒已关闭")
                .build());

        // 「手电筒」光名词此前 L0 不命中（rule#7-toggle-noun, l0=-），
        //   退化进 AgentLoop。光名词 / 「切换」= 翻转当前状态（与 torch.on/off 同一个 sysfs 节点）。
        //   回读输出 on/off（不用 0/1：replyMap 是 contains 匹配，"10" 之类会同时含 0 和 1）。
        reg.register(new Capability.Builder("torch.toggle", "切换手电筒（开着就关、关着就开）")
                .domain("settings")
                .verbs("设置", "打开", "关闭", "切换")
                .entities("setting")
                .examples("手电筒")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("if [ \"$(cat /sys/class/leds/torch-sec1/brightness)\" = \"0\" ]; "
                        + "then echo 1 > /sys/class/leds/torch-sec1/brightness; "
                        + "else echo 0 > /sys/class/leds/torch-sec1/brightness; fi")
                .check("if [ \"$(cat /sys/class/leds/torch-sec1/brightness)\" = \"0\" ]; then echo off; else echo on; fi",
                        "on|off")
                .say("手电筒", "手电", "闪光灯", "切换手电筒", "切换闪光灯", "手电筒切换", "开关手电筒")
                .addReplyMapping("off", "手电筒已关闭")
                .addReplyMapping("on", "手电筒已打开")
                .reply("手电筒已切换")
                .build());

        // 息屏与锁屏
        reg.register(new Capability.Builder("screen.lock", "立即熄灭屏幕并锁定")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("input keyevent 26")
                .say("锁屏", "熄灭屏幕", "关闭屏幕", "息屏")
                .reply("屏幕已锁定")
                .build());

        // 截屏（新增）
        // 用户实测「打开相机后截一张图」没有完全执行 —— 排查后发现两件事：
        //   ① 复合句被 L0 贪婪模板吞成单步（已在 UnifiedRouter/IntentClassifier 修掉）；
        //   ② 就算拆出第二步，**能力表里根本没有"截屏"** —— L2 计划通道有一条铁律是
        //      "没有对应能力的步骤直接丢弃"，于是第二步被静默扔掉，用户只看到相机被打开。
        // 这里把它补上，让计划能真正跑完两步。
        // 走 root 守护执行；截图落盘到相册目录，用户在系统相册里能直接看到。
        reg.register(new Capability.Builder("ui.screenshot", "截取当前屏幕并保存到相册")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                // ★ 为什么要套 timeout（2026-10-02 实测）：
                //   `screencap` 在这个 root 守护上下文里**写完文件后不退出** ——
                //   图片能正常落盘（pull 回读确认是完整可解码的锁屏实拍），
                //   但进程一直挂着，直到守护自己的 10s timeout 才被杀；而 App 侧
                //   的等待上限是 12s，两者贴得太近，于是 App 先超时、报
                //   CHANNEL_TIMEOUT"执行失败"——功能做成了、回执却是错的，比失败还坑。
                //   现在显式套 6s（< 守护 10s < App 12s），并用 `; true` 抹掉
                //   timeout 杀掉子进程带回的非零码，保证用户拿到的是"已截屏"。
                //   6s 足够：实测整张 1080x2220 PNG 落盘远快于此。
                // ★ 为什么要把三个 fd 全部断开（2026-10-02 实测）：
                //   守护执行命令的方式是 `out=$(timeout -k 2 10 sh -c "$cmd" 2>&1)` ——
                //   它靠**命令替换**捕获输出，而命令替换会一直等到**所有写端关闭**才返回。
                //   `screencap` 会留下持有 stdout 的子进程，于是图片早已写完落盘、
                //   守护却卡在等管道关闭上，直到外层 10+2 秒 timeout 才脱身 ——
                //   恰好撞上 App 侧 12 秒上限，用户收到的是"执行超时/失败"。
                //   把 stdout/stderr 指向 /dev/null 之后，管道不再被继承，立即返回。
                .cmd("timeout 6 screencap -p /sdcard/Pictures/bixbyui" + com.onesay.assistant.v2.BuildVariant.PUBLIC_FILE_TAG + "_shot.png "
                        + ">/dev/null 2>&1 </dev/null; true")
                .say("截屏", "截图", "截一张图", "截个图", "截个屏", "截一张屏幕", "屏幕截图", "截屏一下")
                .reply("已截屏，图片已保存到相册")
                .build());

        // 拨打电话（高危敏感，防止误触拨出扣费）
        // 副作用改 PUBLIC_COMMS（原 DEVICE_CONTROL）；关键词改成通话自己的（原样抄了「设置 / 打开 / 调整设置」，
        //   能力召回时会被「打开设置」类句子带出来）；号码分级与红卡见 executor/CommsRisk、ConsentPolicy.RED_SIGNS
        reg.register(new Capability.Builder("phone.call", "拨打指定电话号码（敏感操作）")
                .domain("settings")
                .verbs("打电话", "拨打", "呼叫")
                .entities("phone")
                .examples("打电话给10086")
                .category("sys")
                .sideEffect(SideEffect.PUBLIC_COMMS)
                .schema(new ParamsSchema().addString("number", true, ""))
                .risk(2, true)
                .executor("intent")
                .cmd("am start -a android.intent.action.CALL -d tel:{number}")
                .say("打电话给{n}", "呼叫{n}", "拨打号码{n}")
                .reply("正在请求呼叫 {number}")
                .build());

        // 发送短信（高危敏感）
        // 同上；SENDTO 只打开短信编辑页（还要你点发送）→ 黄卡（CommsRisk）
        reg.register(new Capability.Builder("sms.send", "向指定联系人发送短信")
                .domain("settings")
                .verbs("发短信", "发信息")
                .entities("sms")
                .examples("发短信给10086")
                .category("sys")
                .sideEffect(SideEffect.PUBLIC_COMMS)
                .schema(new ParamsSchema().addString("number", true, "").addString("text", true, ""))
                .risk(2, true)
                .executor("intent")
                .cmd("am start -a android.intent.action.SENDTO -d smsto:{number} --es sms_body '{text}'")
                .say("发短信给{n}", "发信息给{n}")
                .reply("正在准备发送短信")
                .build());

        // 设备重启（最高危破坏性指令，严格 100% 确认拦截）
        reg.register(new Capability.Builder("sys.reboot", "重启手机系统（高危操作）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor("shell")
                .cmd("reboot")
                .say("重启手机", "重新启动设备", "重启系统")
                .reply("正在重启手机系统...")
                .build());

        // 关机（最高危破坏性指令）
        reg.register(new Capability.Builder("sys.poweroff", "关闭手机电源（高危操作）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor("shell")
                .cmd("reboot -p")
                .say("关机", "关闭手机", "切断电源")
                .reply("手机即将关机...")
                .build());

        // 恢复出厂设置（小溪实测在这一项直接无提示直达系统危险页，我们必须 100% 拦截确认）
        reg.register(new Capability.Builder("sys.wipe", "恢复出厂设置（极度危险）")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("sys")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor("intent")
                .cmd("am start -a android.settings.PRIVACY_SETTINGS")
                .say("恢复出厂设置", "抹掉所有数据", "重置手机")
                .reply("恢复出厂设置具有不可逆的破坏性，请谨慎确认")
                .build());

        SettingsExpansionCaps.register(reg);
    }
}
