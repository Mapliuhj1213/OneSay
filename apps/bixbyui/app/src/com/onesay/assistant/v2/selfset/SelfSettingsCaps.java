package com.onesay.assistant.v2.selfset;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;

/**
 * [settings-diag-1124] 「自身设置」能力（纯 Java）。注册进能力表（{@link #register}，和 FastCaps 一样不进内置快照），
 * 普通聊天的单步编译和 AgentLoop 都检索得到；执行走 executor="selfset" → {@code SelfSettingsBackend}（App 内读写，不走 shell）。
 *
 * <table>
 *   <tr><th>签名</th><th>副作用 / 风险</th><th>说明</th></tr>
 *   <tr><td>{@link #SIGN_READ}</td><td>NONE / 0</td><td>读 OneSay 自己的设置清单（可只读一项）：值、缺的前提、能不能在聊天里改</td></tr>
 *   <tr><td>{@link #SIGN_SET}</td><td>DEVICE_CONTROL / 1</td><td>改普通项（应用内开关 / 多选）；碰到敏感项后端拒绝并让改用 set_guarded</td></tr>
 *   <tr><td>{@link #SIGN_SET_GUARDED}</td><td>DEVICE_CONTROL / 2 + confirm</td><td>改敏感项（外部唤起、自动执行、Shizuku 特权、隐私）：黄卡确认，不给「始终允许」</td></tr>
 *   <tr><td>{@link #SIGN_OPEN}</td><td>NONE / 0</td><td>打开这项设置所在的页面，或系统权限页（只给跳转，不替用户改系统权限）</td></tr>
 * </table>
 */
public final class SelfSettingsCaps {

    private SelfSettingsCaps() {}

    public static final String EXECUTOR = "selfset";
    public static final String PREFIX = "self.settings.";
    public static final String SIGN_READ = "self.settings.read";
    public static final String SIGN_SET = "self.settings.set";
    public static final String SIGN_SET_GUARDED = "self.settings.set_guarded";
    public static final String SIGN_OPEN = "self.settings.open";

    public static final String ARG_SETTING = "setting";
    public static final String ARG_VALUE = "value";

    private static volatile Capability sRead, sSet, sGuarded, sOpen;

    public static Capability read() {
        if (sRead == null) {
            sRead = new Capability.Builder(SIGN_READ, "读 OneSay 自己的设置清单：每项开没开、缺哪些权限或条件、能不能在聊天里改、在哪一页。"
                    + "用户问 OneSay 某个功能为什么用不了 / 开着没有 / 怎么开时先读它；setting 留空 = 全部")
                    .domain("selfset").category("settings").verbs("查看", "诊断").entities("OneSay设置")
                    .keywords("OneSay设置", "你的设置", "自己的设置", "唤醒词", "按键唤醒", "快捷开关", "默认助理", "灵动岛", "外部唤起", "设置诊断")
                    .examples("为什么唤醒词用不了", "快捷开关开着吗", "检查一下你的设置")
                    .sideEffect(SideEffect.NONE).risk(0, false).executor(EXECUTOR)
                    .schema(new ParamsSchema().addString(ARG_SETTING, false, ""))
                    .reply("好的").build();
        }
        return sRead;
    }

    public static Capability set() {
        if (sSet == null) {
            sSet = new Capability.Builder(SIGN_SET, "改 OneSay 自己的一项普通设置（应用内开关 / 多选，例如快捷开关、按键唤醒、灵动岛、思考模式）。"
                    + "setting 填清单里的 setting 或中文名，value 填 开 / 关 或选项名。敏感项（needs_confirm）改用 self.settings.set_guarded；系统权限不能改")
                    .domain("selfset").category("settings").verbs("打开", "关闭", "设置").entities("OneSay设置")
                    .keywords("OneSay设置", "快捷开关", "按键唤醒", "唤醒词", "灵动岛")
                    .sideEffect(SideEffect.DEVICE_CONTROL).risk(1, false).executor(EXECUTOR)
                    .schema(new ParamsSchema().addString(ARG_SETTING, true, null).addString(ARG_VALUE, true, null))
                    .reply("好了").build();
        }
        return sSet;
    }

    public static Capability guarded() {
        if (sGuarded == null) sGuarded = guarded("改 OneSay 的一项要你确认的设置（外部 App 唤起 / 替你操作 / Shizuku 特权 / 隐私）");
        return sGuarded;
    }

    /** 带「改前 → 改后」标题的敏感项能力（确认卡标题就是它）。签名不变，令牌只认签名 + 参数。 */
    public static Capability guarded(String summary) {
        return new Capability.Builder(SIGN_SET_GUARDED, summary)
                .domain("selfset").category("settings").verbs("打开", "关闭", "设置").entities("OneSay设置")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(2, true).executor(EXECUTOR)
                .schema(new ParamsSchema().addString(ARG_SETTING, true, null).addString(ARG_VALUE, true, null))
                .reply("好了").build();
    }

    public static Capability open() {
        if (sOpen == null) {
            sOpen = new Capability.Builder(SIGN_OPEN, "打开 OneSay 某项设置所在的页面；系统权限（麦克风、悬浮窗、无障碍、默认助理…）打开对应的系统设置页让用户自己开")
                    .domain("selfset").category("settings").verbs("打开").entities("OneSay设置")
                    .sideEffect(SideEffect.NONE).risk(0, false).executor(EXECUTOR)
                    .schema(new ParamsSchema().addString(ARG_SETTING, true, null))
                    .reply("已打开").build();
        }
        return sOpen;
    }

    /** 注册进能力表（CapabilityRegistry.getInstance 里调；不进内置快照）。 */
    public static void register(CapabilityRegistry reg) {
        reg.register(read());
        reg.register(set());
        reg.register(guarded());
        reg.register(open());
    }

    public static boolean isLocal(Capability cap) {
        return cap != null && EXECUTOR.equals(cap.executorType) && cap.sign != null && cap.sign.startsWith(PREFIX);
    }

    public static boolean isSelfSign(String sign) {
        return sign != null && sign.startsWith(PREFIX);
    }

    public static JSONObject args(String itemId, Object value) {
        JSONObject a = new JSONObject();
        try {
            a.put(ARG_SETTING, itemId);
            if (value != null) a.put(ARG_VALUE, value instanceof Boolean ? (((Boolean) value) ? "开" : "关") : String.valueOf(value));
        } catch (Exception ignored) {
        }
        return a;
    }

    /** 确认卡上的明细行（不露内部 id）：「设置：外部 App 唤起 / 改成：开」。不是本类能力返回 null。 */
    public static String cardLines(Capability cap, JSONObject args) {
        if (cap == null || !isSelfSign(cap.sign) || args == null) return null;
        SelfCatalog.Item it = SelfCatalog.find(args.optString(ARG_SETTING, ""));
        String name = it != null ? it.title : args.optString(ARG_SETTING, "");
        StringBuilder b = new StringBuilder("设置：").append(name);
        if (args.has(ARG_VALUE) && it != null) {
            Object w = SelfSettingsEngine.normalizeValue(it, args.opt(ARG_VALUE));
            b.append("\n改成：").append(w == null ? args.optString(ARG_VALUE) : it.valueText(w));
        }
        if (it != null && it.sensitive) b.append("\n这一项属于外部唤起 / 自动执行 / 隐私类，改之前先问你。");
        return b.toString();
    }
}
