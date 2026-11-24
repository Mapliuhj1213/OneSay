package com.onesay.assistant.v2.selfset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [settings-diag-1124] OneSay 自己的设置清单（纯 Java，JVM 可测）：模型读自己设置用的「控件树」，但走数据层、不截屏。
 *
 * <p>每一项写清：叫什么、在哪一页、是开关 / 多选 / 系统权限、聊天里能不能改、改之前要不要确认、要先满足什么（{@link Need}）。
 * 值怎么读写由宿主的 {@link SelfStore} 实现（Android 侧 {@code SelfSettingsAndroid}，单测用假的）。
 *
 * <h3>敏感项（改之前一律确认卡）</h3>
 * 外部 App 唤起、替你操作 App（自动执行）、Shizuku 特权、发给云端前隐藏个人信息（隐私）。付款相关的规则不是开关，不在清单里，也永远不能从聊天改。
 *
 * <h3>聊天里不改的</h3>
 * 系统权限（悬浮窗、麦克风、无障碍…）只给跳转按钮；助手总开关（关了就听不到你说「打开」）只说在哪。
 */
public final class SelfCatalog {

    private SelfCatalog() {}

    public enum Kind { TOGGLE, CHOICE, PERMISSION }

    /** 打开的去处：应用内页面（console:*）或系统设置页。 */
    public enum Target {
        NONE(null, null),
        PAGE_WAKE("settings.wake", "唤起方式"),
        PAGE_BASICS("settings.basics", "基础设置"),
        PAGE_PLUGINS("settings.plugins", "插件"),
        PAGE_ADVANCED("settings.advanced", "高级"),
        PAGE_DEVICE("settings.devcaps", "设备能力与执行后端"),
        PAGE_PERM("settings.perm", "权限配置"),
        SYS_OVERLAY(null, "悬浮窗权限设置"),
        SYS_APP_DETAILS(null, "OneSay 的应用信息（权限）"),
        SYS_A11Y(null, "无障碍设置"),
        SYS_NOTIF_LISTENER(null, "通知使用权设置"),
        SYS_APP_NOTIF(null, "OneSay 的通知设置"),
        SYS_BATTERY(null, "电池优化设置"),
        SYS_ASSIST_ROLE(null, "默认助理设置"),
        SYS_EXACT_ALARM(null, "闹钟和提醒权限设置"),
        SHIZUKU_APP(null, "Shizuku");

        /** 应用内页面 id（系统页为 null）。 */
        public final String page;
        public final String zh;

        Target(String page, String zh) {
            this.page = page;
            this.zh = zh;
        }

        public boolean inApp() {
            return page != null;
        }
    }

    /** 前提条件：权限、设备条件、要先填的东西。 */
    public static final class Need {
        public final String id;
        /** 给人看的名字（「麦克风权限」）。 */
        public final String title;
        /** 缺了怎么办（一句大白话）。 */
        public final String how;
        /** 用户自己补得上（权限 / 填写）；false = 这台手机做不到（处理器、没放模型）。 */
        public final boolean fixable;
        public final Target target;

        Need(String id, String title, String how, boolean fixable, Target target) {
            this.id = id;
            this.title = title;
            this.how = how;
            this.fixable = fixable;
            this.target = target;
        }
    }

    /** 一项设置。 */
    public static final class Item {
        public final String id;
        public final String title;
        /** 所在页（给人看，「设置 › 基础设置 › 唤起方式」）。 */
        public final String where;
        public final Target page;
        public final Kind kind;
        public final boolean sensitive;
        public final boolean chatChangeable;
        public final List<String> needs;
        public final List<String> aliases;
        /** 开着时还要知道的一句（可空）：「还要把 OneSay 拖进快捷开关」。 */
        public final String onHint;
        /** CHOICE：取值与显示名。 */
        public final List<String> choices, choiceLabels;
        /** 权限项：对应的前提 id（读它的状态）。 */
        public final String needId;

        Item(String id, String title, String where, Target page, Kind kind, boolean sensitive, boolean chatChangeable,
             String[] needs, String[] aliases, String onHint, String[] choices, String[] choiceLabels, String needId) {
            this.id = id;
            this.title = title;
            this.where = where;
            this.page = page;
            this.kind = kind;
            this.sensitive = sensitive;
            this.chatChangeable = chatChangeable;
            this.needs = list(needs);
            this.aliases = list(aliases);
            this.onHint = onHint;
            this.choices = list(choices);
            this.choiceLabels = list(choiceLabels);
            this.needId = needId;
        }

        public String valueText(Object v) {
            if (v == null) return "读不到";
            if (kind == Kind.CHOICE) {
                int i = choices.indexOf(String.valueOf(v));
                return i >= 0 ? choiceLabels.get(i) : String.valueOf(v);
            }
            if (kind == Kind.PERMISSION) return Boolean.TRUE.equals(v) ? "已开" : "没开";
            return Boolean.TRUE.equals(v) ? "开" : "关";
        }
    }

    private static List<String> list(String[] a) {
        return a == null ? Collections.<String>emptyList() : Collections.unmodifiableList(Arrays.asList(a));
    }

    // ------------------------------------------------------------------ 前提

    public static final String N_MIC = "perm.mic", N_OVERLAY = "perm.overlay", N_A11Y = "perm.a11y", N_NOTIF_LISTENER = "perm.notif_listener",
            N_POST_NOTIF = "perm.post_notif", N_BATTERY = "perm.battery", N_ASSIST = "role.assistant", N_EXACT_ALARM = "perm.exact_alarm",
            N_ARM64 = "dev.arm64", N_WAKE_MODEL = "wake.model", N_WAKE_WORDS = "wake.words", N_SCREEN = "exec.screen", N_SHIZUKU = "shizuku.ready";

    static final Map<String, Need> NEEDS = new LinkedHashMap<String, Need>();

    private static void need(String id, String title, String how, boolean fixable, Target t) {
        NEEDS.put(id, new Need(id, title, how, fixable, t));
    }

    static {
        need(N_MIC, "麦克风权限", "在 OneSay 的应用信息 › 权限里允许麦克风", true, Target.SYS_APP_DETAILS);
        need(N_OVERLAY, "悬浮窗权限", "在系统的悬浮窗（显示在其他应用上层）设置里允许 OneSay", true, Target.SYS_OVERLAY);
        need(N_A11Y, "无障碍服务", "在系统无障碍设置里找到 OneSay 并打开", true, Target.SYS_A11Y);
        need(N_NOTIF_LISTENER, "通知使用权", "在系统的通知使用权设置里打开 OneSay", true, Target.SYS_NOTIF_LISTENER);
        need(N_POST_NOTIF, "通知权限", "在 OneSay 的通知设置里允许通知", true, Target.SYS_APP_NOTIF);
        need(N_BATTERY, "电池优化白名单", "把 OneSay 设成「不优化」，熄屏久了才不会被系统停掉", true, Target.SYS_BATTERY);
        need(N_ASSIST, "默认助理", "在系统的默认应用 › 数字助理里选 OneSay", true, Target.SYS_ASSIST_ROLE);
        need(N_EXACT_ALARM, "闹钟和提醒权限", "在系统的闹钟和提醒设置里允许 OneSay", true, Target.SYS_EXACT_ALARM);
        need(N_ARM64, "64 位处理器", "这台手机的处理器跑不了本地唤醒词，可以改用按键唤醒或默认助理", false, Target.NONE);
        need(N_WAKE_MODEL, "唤醒词模型", "安装包里没带唤醒词模型，要按 README 的说明先放进来；放好之前可以用按键唤醒或默认助理", false, Target.NONE);
        need(N_WAKE_WORDS, "你自己的唤醒词", "在 设置 › 基础设置 › 唤起方式 › 我的唤醒词 里填一条", true, Target.PAGE_WAKE);
        need(N_SCREEN, "读屏通道（无障碍服务或 Shizuku）", "打开 OneSay 的无障碍服务最省事", true, Target.SYS_A11Y);
        need(N_SHIZUKU, "Shizuku 在运行并已授权", "先打开 Shizuku 并启动它，再回来打开这一项（会弹授权框）", true, Target.SHIZUKU_APP);
    }

    public static Need need(String id) {
        return NEEDS.get(id);
    }

    // ------------------------------------------------------------------ 设置项

    static final String W_WAKE = "设置 › 基础设置 › 唤起方式";
    static final String W_BASIC = "设置 › 基础设置";
    static final String W_PLUG = "设置 › 插件";
    static final String W_ADV = "设置 › 高级";
    static final String W_DEV = "设置 › 高级 › 设备能力与执行后端";
    static final String W_SYS = "系统设置（OneSay 改不了，给你跳过去）";

    private static final List<Item> ALL;

    private static Item toggle(String id, String title, String where, Target page, boolean sensitive, String[] needs, String[] aliases, String onHint) {
        return new Item(id, title, where, page, Kind.TOGGLE, sensitive, true, needs, aliases, onHint, null, null, null);
    }

    private static Item perm(String id, String title, String[] aliases) {
        return new Item(id, title, W_SYS, NEEDS.get(id).target, Kind.PERMISSION, false, false, null, aliases, null, null, null, id);
    }

    static {
        List<Item> l = new ArrayList<Item>();
        // ---- 唤起
        l.add(toggle("entry.assistant", "默认助理入口", W_WAKE, Target.PAGE_WAKE, false, new String[]{N_ASSIST},
                new String[]{"默认助理入口", "默认数字助理", "默认助理", "数字助理", "助理手势", "长按电源键", "长按电源"},
                "长按电源键 / 屏幕角落上滑就能叫我"));
        l.add(toggle("entry.qs_tile", "快捷开关", W_WAKE, Target.PAGE_WAKE, false, null,
                new String[]{"快捷开关", "下拉快捷开关", "通知栏开关", "快捷磁贴", "磁贴"},
                "还要在通知栏下拉 › 编辑里，把「OneSay」拖进快捷开关，点一下就开始听"));
        l.add(toggle("entry.widget", "桌面小部件", W_WAKE, Target.PAGE_WAKE, false, null,
                new String[]{"桌面小部件", "小部件", "小组件", "桌面插件"}, "长按桌面 › 小部件 › OneSay 就能摆出来"));
        l.add(toggle("entry.shortcut", "桌面快捷方式", W_WAKE, Target.PAGE_WAKE, false, null,
                new String[]{"桌面快捷方式", "快捷方式"}, "长按 OneSay 图标，选「语音唤起」"));
        l.add(toggle("entry.wake_word", "本地唤醒词", W_WAKE, Target.PAGE_WAKE, false, new String[]{N_MIC, N_ARM64, N_WAKE_MODEL, N_WAKE_WORDS},
                new String[]{"本地唤醒词", "唤醒词", "语音唤醒", "喊名字唤醒"}, "改完唤醒服务会重新加载，过几秒生效"));
        l.add(toggle("entry.key_wake", "按键唤醒", W_WAKE, Target.PAGE_WAKE, false, new String[]{N_A11Y},
                new String[]{"按键唤醒", "双击电源键", "双击电源", "电源键唤醒", "音量键唤醒", "长按音量键"},
                "默认双击电源键唤起；很多手机把双击电源键给了相机，要先在系统设置里关掉"));
        l.add(toggle("entry.intent", "外部 App 唤起", W_WAKE, Target.PAGE_WAKE, true, null,
                new String[]{"外部 App 唤起", "外部唤起", "别的应用唤起", "其他应用唤起", "别的App唤起"},
                "别的 App 也能叫出 OneSay 了，但对方还得先拿到你的授权"));
        // ---- 基础
        l.add(toggle("privacy.redact", "发给云端前隐藏个人信息", W_BASIC, Target.PAGE_BASICS, true, null,
                new String[]{"发给云端前隐藏个人信息", "隐藏个人信息", "个人信息隐藏", "隐私保护", "脱敏"}, null));
        l.add(toggle("reply.voice", "语音问就语音答", W_BASIC + " › 声音与朗读", Target.PAGE_BASICS, false, null,
                new String[]{"语音问就语音答", "语音回答", "语音回复"}, null));
        l.add(toggle("reply.full", "朗读完整回答", W_BASIC + " › 声音与朗读", Target.PAGE_BASICS, false, null,
                new String[]{"朗读完整回答", "读完整回答", "完整朗读"}, null));
        l.add(toggle("wake.type_first", "默认打字唤起", W_ADV, Target.PAGE_ADVANCED, false, null,
                new String[]{"默认打字唤起", "打字唤起", "默认打字"}, "下次唤起直接弹键盘，不先听你说"));
        l.add(new Item("think.mode", "思考模式", W_ADV + " › 模型细项", Target.PAGE_ADVANCED, Kind.CHOICE, false, true, null,
                new String[]{"思考模式", "深度思考"}, null, new String[]{"off", "agent", "all"}, new String[]{"关", "仅多步任务", "全部"}, null));
        l.add(toggle("read.direct", "读数类问题直接回答", W_ADV + " › 模型细项", Target.PAGE_ADVANCED, false, null,
                new String[]{"读数类问题直接回答", "读数直答"}, null));
        // ---- 插件
        l.add(toggle("plug.island", "灵动岛", W_PLUG, Target.PAGE_PLUGINS, false, new String[]{N_OVERLAY},
                new String[]{"灵动岛"}, null));
        l.add(toggle("plug.notice", "灵动岛消息提醒", W_PLUG + " › 灵动岛", Target.PAGE_PLUGINS, false, new String[]{N_OVERLAY, N_NOTIF_LISTENER},
                new String[]{"灵动岛消息提醒", "消息提醒", "通知上岛"}, null));
        l.add(toggle("plug.quicknote", "记一记", W_PLUG, Target.PAGE_PLUGINS, false, new String[]{N_SCREEN},
                new String[]{"记一记", "闪记"}, null));
        l.add(toggle("plug.memory", "记忆", W_PLUG, Target.PAGE_PLUGINS, false, null,
                new String[]{"记忆功能", "记忆库", "记忆"}, null));
        l.add(toggle("plug.diag", "设置诊断", W_PLUG + " › 更多插件", Target.PAGE_PLUGINS, false, null,
                new String[]{"设置诊断"}, null));
        l.add(toggle("plug.phone", "替你操作 App", W_PLUG + " › 让手机替你做", Target.PAGE_PLUGINS, true, new String[]{N_SCREEN},
                new String[]{"替你操作 App", "替你操作", "代操作", "自动操作", "自动执行"}, "付款 / 发送 / 删除这些步骤照样先问你"));
        l.add(toggle("plug.pclink", "电脑联动", W_PLUG + " › 连接", Target.PAGE_PLUGINS, false, null,
                new String[]{"电脑联动", "连电脑", "连接电脑"}, "还要和电脑配对：设置首页「连接电脑」"));
        l.add(toggle("exec.shizuku", "Shizuku 特权", W_DEV, Target.PAGE_DEVICE, true, new String[]{N_SHIZUKU},
                new String[]{"Shizuku 特权", "Shizuku"}, "只执行白名单里的几类命令"));
        l.add(new Item("assist.on", "助手总开关", "设置首页顶上的总开关", Target.NONE, Kind.TOGGLE, false, false, null,
                new String[]{"助手总开关", "总开关", "暂停助手"}, null, null, null, null));
        // ---- 系统权限（只读 + 跳转）
        l.add(perm(N_MIC, "麦克风权限", new String[]{"麦克风权限", "麦克风", "录音权限"}));
        l.add(perm(N_OVERLAY, "悬浮窗权限", new String[]{"悬浮窗权限", "悬浮窗", "显示在其他应用上层"}));
        l.add(perm(N_A11Y, "无障碍服务", new String[]{"无障碍服务", "无障碍", "读屏服务"}));
        l.add(perm(N_NOTIF_LISTENER, "通知使用权", new String[]{"通知使用权", "通知读取"}));
        l.add(perm(N_POST_NOTIF, "通知权限", new String[]{"通知权限"}));
        l.add(perm(N_BATTERY, "电池优化白名单", new String[]{"电池优化白名单", "电池优化", "后台保活", "电池白名单"}));
        l.add(perm(N_ASSIST, "默认助理", new String[]{"设为默认助理", "默认助理设置"}));
        l.add(perm(N_EXACT_ALARM, "闹钟和提醒权限", new String[]{"闹钟和提醒权限", "精确闹钟"}));
        ALL = Collections.unmodifiableList(l);
    }

    public static List<Item> all() {
        return ALL;
    }

    public static Item get(String id) {
        if (id == null) return null;
        for (Item i : ALL) if (i.id.equals(id)) return i;
        return null;
    }

    /**
     * 按 id / 名字 / 别名找（模型传的可能是中文名）。名字与别名去掉空格、忽略大小写比较；都对不上返回 null。
     */
    public static Item find(String key) {
        if (key == null) return null;
        String k = squash(key);
        if (k.length() == 0) return null;
        Item byId = get(key.trim());
        if (byId != null) return byId;
        for (Item i : ALL) {
            if (squash(i.title).equals(k)) return i;
        }
        for (Item i : ALL) {
            for (String a : i.aliases) if (squash(a).equals(k)) return i;
        }
        return null;
    }

    static String squash(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\u3000' || c == '「' || c == '」' || c == '"' || c == '“' || c == '”') continue;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }
}
