package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * ★ fix/triage-fast：快路径模板要用、能力表里原来没有的系统动作（导航键 / 媒体键 / 清空后台 / 勿扰 / 相机 / 热点页）。
 *
 * <p>不进 {@code BuiltinCapabilities.registerAll}（那份有逐条全字段快照），由
 * {@code CapabilityRegistry.getInstance()} 在内置能力之后单独注册，所以快照不变、Top-K 同分顺序也不变。
 *
 * <p>命令都要过 exec 守护 v4 的白名单：{@code input keyevent <数字>} 单段、{@code am start -a <action>} 都在白名单里；
 * 清空后台 / 勿扰是 executor=custom，由 {@code executor/backend/SysActionBackend} 在 App 侧编排（守护内建模板优先、
 * 读屏助手点「全部关闭」兜底、勿扰走 NotificationManager）。
 */
public final class FastCaps {

    private FastCaps() {}

    public static final String SIGN_RECENTS_CLEAR = "sys.recents.clear";
    public static final String SIGN_DND_ON = "dnd.on";
    public static final String SIGN_DND_OFF = "dnd.off";
    /** ★ int/1102 审计：批量 / 前置类（「清除所有通知」「全部静音」「打开 NFC 刷公交卡」）。 */
    public static final String SIGN_NOTIF_CLEAR_ALL = "notif.clear.all";
    public static final String SIGN_MUTE_ALL = "sound.mute.all";
    public static final String SIGN_NFC_TRANSIT = "nfc.transit";

    public static void register(CapabilityRegistry reg) {
        key(reg, "nav.home", "回到桌面（主页键）", "3", "已回到桌面",
                "回到桌面", "返回桌面", "回桌面", "回到主屏幕", "返回主页", "回主页");
        key(reg, "nav.back", "返回上一页（返回键）", "4", "已返回",
                "返回", "返回上一页", "后退", "返回上一级");
        key(reg, "nav.recents", "打开最近任务界面（多任务键）", "187", "已打开最近任务",
                "打开最近任务", "最近任务", "多任务", "打开多任务");
        key(reg, "media.play", "继续播放媒体（媒体播放键）", "126", "已继续播放",
                "继续播放", "恢复播放");
        key(reg, "media.pause", "暂停正在播放的媒体（媒体暂停键）", "127", "已暂停",
                "暂停", "暂停播放", "暂停音乐", "停止播放");
        key(reg, "media.next", "播放下一首（媒体下一首键）", "87", "已切到下一首",
                "下一首", "下一曲", "切歌", "换一首");
        key(reg, "media.prev", "播放上一首（媒体上一首键）", "88", "已切到上一首",
                "上一首", "上一曲");

        reg.register(new Capability.Builder(SIGN_RECENTS_CLEAR, "清空后台：关掉最近任务里的全部应用（系统级，不打开应用管理）")
                .domain("settings").category("sys")
                .verbs("清空", "清理", "关闭").entities("setting").examples("清空后台")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("custom")
                .say("清空后台", "清理后台", "清空后台应用", "关闭后台应用", "一键清理", "清理内存", "关闭所有应用")
                .reply("后台已清空")
                .build());

        reg.register(new Capability.Builder(SIGN_DND_ON, "打开勿扰模式")
                .domain("settings").category("sys")
                .verbs("打开", "开启").entities("setting").examples("打开勿扰")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("custom")
                .say("打开勿扰", "开启勿扰模式", "打开勿扰模式", "开勿扰", "打开免打扰")
                .reply("勿扰模式已打开")
                .build());
        reg.register(new Capability.Builder(SIGN_DND_OFF, "关闭勿扰模式")
                .domain("settings").category("sys")
                .verbs("关闭", "关掉").entities("setting").examples("关闭勿扰")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("custom")
                .say("关闭勿扰", "关闭勿扰模式", "关掉勿扰", "关勿扰", "关闭免打扰")
                .reply("勿扰模式已关闭")
                .build());

        // ★ int/1102 审计：「清除所有通知」—— 通知使用权（EventNotificationListener）在就 cancelAllNotifications()；
        //   没授权时守护 v4.4 拒 cmd notification（CMD_SVC），不能 root 代开 → 打开「通知使用权」页 + 明确话术（不硬拒、不进 AgentLoop）。
        reg.register(new Capability.Builder(SIGN_NOTIF_CLEAR_ALL, "清除通知栏里所有可清除的通知")
                .domain("settings").category("sys")
                .verbs("清除", "清空").entities("setting").examples("清除所有通知")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false)
                .executor("custom")
                .say("清除所有通知", "清空通知", "清空通知栏", "清除通知")
                .reply("通知已全部清除")
                .build());
        // ★ int/1102 审计：「全部静音」—— 铃声 / 通知 / 系统 / 媒体四路音量置 0（逐条单命令，守护白名单内）；闹钟音量不动。
        reg.register(new Capability.Builder(SIGN_MUTE_ALL, "全部静音：铃声、通知、系统提示音和媒体音量都调到 0（闹钟不动）")
                .domain("settings").category("sys")
                .verbs("静音", "关闭").entities("setting").examples("全部静音")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("shell")
                .cmd("media volume --stream 2 --set 0")
                .say("全部静音", "一键静音", "关掉所有声音")
                .reply("已全部静音（铃声、通知、系统、媒体），闹钟照常响")
                .build());
        // ★ int/1102 审计：「打开 NFC 刷公交卡」—— 先 svc nfc enable，再打开已装的钱包类应用（没装就只开 NFC，并说清楚直接贴闸机即可）。
        reg.register(new Capability.Builder(SIGN_NFC_TRANSIT, "打开 NFC，并打开已安装的钱包 / 交通卡应用准备刷卡")
                .domain("settings").category("sys")
                .verbs("打开").entities("setting").examples("打开nfc刷公交卡")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("custom")
                .say("打开nfc刷公交卡", "刷公交卡", "刷地铁卡")
                .reply("NFC 已打开")
                .build());

        reg.register(new Capability.Builder("camera.open", "打开系统相机拍照界面")
                .domain("app").category("app")
                .verbs("拍照", "打开").entities("camera").examples("拍照")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.media.action.STILL_IMAGE_CAMERA")
                .say("拍照", "拍张照片", "打开相机拍照", "照相")
                .reply("相机已打开，可以拍了")
                .build());

        // Android 8 没有可靠的 shell 开热点命令（cmd wifi 里没有，守护也不放 service call connectivity），
        // 只能打开「连接」页（热点开关在这页），诚实说明需要点一下。
        reg.register(new Capability.Builder("settings.page.hotspot", "打开移动热点所在的连接设置页面")
                .domain("settings").category("settings")
                .verbs("打开").entities("setting").examples("打开热点")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("intent")
                .cmd("am start -a android.settings.WIRELESS_SETTINGS")
                .say("打开热点", "打开移动热点", "开热点", "打开热点设置")
                .reply("已打开连接设置，移动热点开关在这一页")
                .build());
    }

    private static void key(CapabilityRegistry reg, String sign, String summary, String code, String reply, String... say) {
        reg.register(new Capability.Builder(sign, summary)
                .domain("settings").category("sys")
                .verbs("按键", "返回", "播放").entities("setting").examples(say[0])
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("shell")
                .cmd("input keyevent " + code)
                .say(say)
                .reply(reply)
                .build());
    }
}
