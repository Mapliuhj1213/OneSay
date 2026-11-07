package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 音量与媒体。
 *
 * <p>从 {@code BuiltinCapabilities.registerVolume} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class VolumeCaps {

    private VolumeCaps() {}

    /** 媒体音量步进 2，夹在 [0, 上限]；{op} 为 + 或 -。取不到上限时按 15（Android 8 媒体流默认）。 */
    static final String VOL_STEP_CMD =
            "o=$(media volume --stream 3 --get 2>&1); v=$(echo \"$o\" | grep -o 'volume is [0-9]*' | grep -o '[0-9]*$'); "
            + "m=$(echo \"$o\" | grep -o '\\.\\.[0-9]*' | tr -d '.'); [ -z \"$m\" ] && m=15; [ -z \"$v\" ] && exit 1; "
            + "n=$((v {op} 2)); [ $n -gt $m ] && n=$m; [ $n -lt 0 ] && n=0; media volume --stream 3 --set $n >/dev/null 2>&1; echo $n";

    public static void register(CapabilityRegistry reg) {
        // 媒体音量
        reg.register(new Capability.Builder("volume.media.set", "设置媒体播放音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("level", true, 0, 15, "8"))
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 3 --set {level}")
                .check("media volume --stream 3 --get", "volume is {level}")
                .say("媒体音量{n}", "媒体音量调到{n}", "音乐音量调到{n}", "声音调到{n}", "把声音设为{n}")
                .reply("媒体音量已调至 {level}")
                .build());

        reg.register(new Capability.Builder("volume.media.mute", "静音媒体音乐播放")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 3 --set 0")
                .check("media volume --stream 3 --get", "volume is 0")
                .say("媒体静音", "静音媒体", "关闭媒体声音")
                .reply("媒体播放已静音")
                .build());

        reg.register(new Capability.Builder("volume.media.get", "查询当前媒体音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("media volume --stream 3 --get 2>&1 | grep -o 'volume is [0-9]*' | tr -d '\\r\\n'")
                .say("媒体音量多少", "当前声音多大")
                .reply("当前媒体音量为 {out}")
                .build());

        // 「再大声一点」原先没有相对调节能力 → 联网进 AgentLoop。
        //   现在 L0 离线直出：读当前媒体音量与上限（Android 8 输出 "volume is 7 in range [0..15]"），步进 2，夹在 [0, 上限]。
        reg.register(new Capability.Builder("volume.media.up", "调大媒体播放音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd(VOL_STEP_CMD.replace("{op}", "+"))
                .check("media volume --stream 3 --get", "")
                .say("大声一点", "大声点", "大点声", "声音大一点", "声音大点", "声音调大", "声音调大点", "调大声音",
                        "音量调高", "音量调高点", "调高音量", "音量大一点", "音量大点", "音量调大", "调大音量", "音量加大", "加大音量", "声音调大一点", "音量调高一点", "音量调大一点")
                .reply("媒体音量已调大")
                .build());

        reg.register(new Capability.Builder("volume.media.down", "调小媒体播放音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd(VOL_STEP_CMD.replace("{op}", "-"))
                .check("media volume --stream 3 --get", "")
                .say("小声一点", "小声点", "小点声", "声音小一点", "声音小点", "声音调小", "声音调小点", "调小声音",
                        "音量调低", "音量调低点", "调低音量", "音量小一点", "音量小点", "音量调小", "调小音量", "音量减小", "减小音量", "声音调小一点", "音量调低一点", "音量调小一点")
                .reply("媒体音量已调小")
                .build());

        // 铃声音量
        reg.register(new Capability.Builder("volume.ring.set", "设置电话来电铃声音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("level", true, 0, 15, "8"))
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 2 --set {level}")
                .check("media volume --stream 2 --get", "volume is {level}")
                .say("铃声音量{n}", "来电音量调到{n}", "铃声音量调到{n}")
                .reply("来电铃声音量已调至 {level}")
                .build());

        reg.register(new Capability.Builder("volume.ring.mute", "静音电话来电铃声")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 2 --set 0")
                .check("media volume --stream 2 --get", "volume is 0")
                .say("静音铃声", "来电静音", "关闭铃声")
                .reply("来电铃声已静音")
                .build());

        reg.register(new Capability.Builder("volume.ring.get", "查询来电铃声音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("media volume --stream 2 --get 2>&1 | grep -o 'volume is [0-9]*' | tr -d '\\r\\n'")
                .say("铃声音量多少", "来电音量多大")
                .reply("当前来电铃声音量为 {out}")
                .build());

        // 闹钟音量
        reg.register(new Capability.Builder("volume.alarm.set", "设置闹钟提示音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .schema(new ParamsSchema().addInteger("level", true, 0, 15, "10"))
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 4 --set {level}")
                .check("media volume --stream 4 --get", "volume is {level}")
                .say("闹钟音量{n}", "闹钟音量调到{n}")
                .reply("闹钟音量已调至 {level}")
                .build());

        reg.register(new Capability.Builder("volume.alarm.state", "查询闹钟提示音量")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(0, false)
                .executor("shell")
                .cmd("media volume --stream 4 --get 2>&1 | grep -o 'volume is [0-9]*' | tr -d '\\r\\n'")
                .say("闹钟音量多少", "闹钟声音多大")
                .reply("当前闹钟音量为 {out}")
                .build());

        // 通知音量
        reg.register(new Capability.Builder("volume.notif.mute", "静音系统消息通知")
                .domain("settings")
                .verbs("设置", "打开")
                .entities("setting")
                .examples("调整设置")
                .category("media")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor("shell")
                .cmd("media volume --stream 5 --set 0")
                .check("media volume --stream 5 --get", "volume is 0")
                .say("静音通知", "通知静音", "关掉消息声音")
                .reply("系统通知已静音")
                .build());
    }
}
