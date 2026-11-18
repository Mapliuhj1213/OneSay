package com.onesay.assistant.v2.ui.console;


import java.util.ArrayList;
import java.util.List;

/**
 * [mz21-perm-1118] 控制台「权限配置」页的判定（纯 Java，JVM 可测）。
 *
 * <p>按步骤列出每项权限 / 通道，每项：状态点 + 一句现状 + 一键去配置。
 * <ul>
 *   <li><b>必须项</b>三色：红 = 缺失（{@link ConsoleStatus.Level#BAD}）、黄 = 部分 / 待确认（{@link ConsoleStatus.Level#WARN}，
 *       探测不到的也画黄、写「待确认」）、绿 = 就绪（{@link ConsoleStatus.Level#OK}）；</li>
 *   <li><b>可选项</b>只有两色：灰 = 未开（{@link ConsoleStatus.Level#OFF}，探测不到也算未开）、绿 = 已开。</li>
 * </ul>
 * 首页只放一个汇总入口（{@link #summary}）：必须项里有<b>确定</b>的红 / 黄才出；探测不到（null）不出（「未知不出提醒条」原则不变）。
 */
public final class PermSetup {

    private PermSetup() {}

    public static final String OVERLAY = "overlay", MIC = "mic", EXEC = "exec", LLM_KEY = "llm_key", ASR_KEY = "asr_key", APP_LIST = "app_list",
            A11Y = "a11y", NOTIF = "notif", CLIPBOARD = "clipboard", ASSISTANT = "assistant", BATTERY = "battery", CLOUD_TTS = "cloud_tts";

    /** 执行通道的子状态 → 一键配置要做的事。 */
    public static final String DO_NONE = "none", DO_SHIZUKU_ENABLE = "shizuku_enable", DO_SHIZUKU_GRANT = "shizuku_grant",
            DO_SHIZUKU_START = "shizuku_start", DO_SHIZUKU_INSTALL = "shizuku_install";

    public static final class Item {
        public final String id;
        public final int step;
        public final String title;
        public final boolean required;
        public final ConsoleStatus.Level level;
        /** 现状一句话（显示在标题下）。 */
        public final String status;
        /** 按钮文字；null = 不出按钮（已就绪）。 */
        public final String action;
        /** 首页汇总里用的短问题（只有必须项的红 / 黄才有）。 */
        public final String problem;
        /** 执行通道的细分动作（{@link #DO_NONE} 等）；其它项为 {@link #DO_NONE}。 */
        public final String doWhat;
        /** 探测不到（画黄 / 灰，但首页不报）。 */
        public final boolean unknown;

        Item(String id, int step, String title, boolean required, ConsoleStatus.Level level, String status, String action, String problem,
             String doWhat, boolean unknown) {
            this.id = id;
            this.step = step;
            this.title = title;
            this.required = required;
            this.level = level;
            this.status = status;
            this.action = action;
            this.problem = problem;
            this.doWhat = doWhat == null ? DO_NONE : doWhat;
            this.unknown = unknown;
        }

        @Override public String toString() {
            return id + ":" + level + (unknown ? "?" : "") + "(" + status + ")";
        }
    }

    private static Item ok(String id, int step, String title, String status) {
        return new Item(id, step, title, true, ConsoleStatus.Level.OK, status, null, null, null, false);
    }

    private static Item bad(String id, int step, String title, String status, String action, String problem) {
        return new Item(id, step, title, true, ConsoleStatus.Level.BAD, status, action, problem, null, false);
    }

    private static Item warn(String id, int step, String title, String status, String action, String problem) {
        return new Item(id, step, title, true, ConsoleStatus.Level.WARN, status, action, problem, null, false);
    }

    private static Item pending(String id, int step, String title, String action, boolean done) {
        return new Item(id, step, title, true, ConsoleStatus.Level.WARN, done ? "待确认（没测到）" : "检测中…", action, null, null, true);
    }

    private static Item opt(String id, int step, String title, Boolean on, String onText, String offText, String action) {
        boolean yes = Boolean.TRUE.equals(on);
        return new Item(id, step, title, false, yes ? ConsoleStatus.Level.OK : ConsoleStatus.Level.OFF,
                yes ? onText : (on == null ? "未开（没测到）" : offText), yes ? null : action, null, null, on == null);
    }

    // ------------------------------------------------------------------ 必须项

    public static Item overlay(ConsoleStatus.Snapshot s) {
        String t = "悬浮窗";
        if (s.overlayOk == null) return pending(OVERLAY, 1, t, "去开启", s.done);
        return s.overlayOk ? ok(OVERLAY, 1, t, "已开启：对话面板、灵动岛能显示")
                : bad(OVERLAY, 1, t, "没开：说话后面板显示不出来", "去开启", "悬浮窗未开");
    }

    public static Item mic(ConsoleStatus.Snapshot s) {
        String t = "录音";
        if (s.micPermission == null) return pending(MIC, 2, t, "去授权", s.done);
        return s.micPermission ? ok(MIC, 2, t, "已授权：能听到你说话")
                : bad(MIC, 2, t, "没授权：听不到你说话", "去授权", "没有录音权限");
    }

    /**
     * 执行通道：root 守护或 Shizuku 任一就绪即绿；Shizuku 已装但没在跑 / 没授权 / 插件没开 = 黄；两样都没有 = 红。
     * 「Root 未就绪」这类字样不再出现：没 root 不是问题，没有<b>任何</b>执行通道才是。
     */
    public static Item exec(ConsoleStatus.Snapshot s) {
        String t = "执行通道";
        boolean running = Boolean.TRUE.equals(s.shizukuRunning), granted = Boolean.TRUE.equals(s.shizukuGranted);
        if (Boolean.TRUE.equals(s.rootOk)) return ok(EXEC, 3, t, "root 守护在线");
        if ((running && granted && s.shizukuEnabled) || Boolean.TRUE.equals(s.shizukuOk)) return ok(EXEC, 3, t, "Shizuku 就绪（只跑白名单命令）");
        if (running && granted) {
            return new Item(EXEC, 3, t, true, ConsoleStatus.Level.WARN, "Shizuku 已授权，但「Shizuku 特权」没打开", "打开", "执行通道待启用",
                    DO_SHIZUKU_ENABLE, false);
        }
        if (running) {
            return new Item(EXEC, 3, t, true, ConsoleStatus.Level.WARN, "Shizuku 在运行，还没给本 App 授权", "授权", "Shizuku 未授权",
                    DO_SHIZUKU_GRANT, false);
        }
        if (Boolean.TRUE.equals(s.shizukuInstalled)) {
            return new Item(EXEC, 3, t, true, ConsoleStatus.Level.WARN, "Shizuku 已安装，服务没在运行", "启动 Shizuku", "Shizuku 未运行",
                    DO_SHIZUKU_START, false);
        }
        if (s.rootOk == null) return pending(EXEC, 3, t, "怎么开", s.done);
        // ★ design-1118 开源版：root 是可选的（ConsoleStatus.ROOT_OPTIONAL）—— 两样都没有只是「待配置」（黄），不让首页变红
        return new Item(EXEC, 3, t, true, ConsoleStatus.ROOT_OPTIONAL ? ConsoleStatus.Level.WARN : ConsoleStatus.Level.BAD,
                "没有 root，也没装 Shizuku：开关蓝牙 / Wi-Fi、识屏截图这类用不了", "装 Shizuku",
                ConsoleStatus.ROOT_OPTIONAL ? "执行通道待配置" : "执行通道未就绪", DO_SHIZUKU_INSTALL, false);
    }

    public static Item llmKey(ConsoleStatus.Snapshot s) {
        String t = "主模型 Key";
        if (s.llmConfigured == null) return pending(LLM_KEY, 4, t, "去填写", s.done);
        return s.llmConfigured ? ok(LLM_KEY, 4, t, "已配置")
                : bad(LLM_KEY, 4, t, "没填：复杂问题、多步任务答不了", "去填写", "主模型 Key 未配置");
    }

    public static Item asrKey(ConsoleStatus.Snapshot s) {
        String t = "语音识别 Key";
        if (Boolean.FALSE.equals(s.asrConfigured)) return bad(ASR_KEY, 5, t, "没配：说话听不懂（只能打字）", "去填写", "语音识别未配置");
        if (Boolean.TRUE.equals(s.asrKeyInvalid)) return bad(ASR_KEY, 5, t, "上次鉴权失败，Key 可能失效", "重新填写", "语音识别 Key 失效");
        if (s.asrConfigured == null) return pending(ASR_KEY, 5, t, "去填写", s.done);
        return ok(ASR_KEY, 5, t, "已配置");
    }

    /**
     * 应用列表读取（★ design-1118 开源版：没有个人版的「应用清单」同意流程 —— 只看系统给没给读应用列表：
     * 读到的第三方应用很少 = 部分 ROM（如 Flyme / MIUI）的「获取应用列表」权限没给）。
     */
    public static Item appList(ConsoleStatus.Snapshot s) {
        String t = "应用列表读取";
        if (Boolean.TRUE.equals(s.appListPermMissing)) {
            return bad(APP_LIST, 6, t, "系统没给「获取应用列表」，只读到本 App", "去授权", "系统没给应用列表");
        }
        if (Boolean.TRUE.equals(s.appListLimited)) {
            return warn(APP_LIST, 6, t, "只读到很少几个应用：去应用信息里允许「获取应用列表」", "去授权", "应用列表不全");
        }
        if (s.appListPermMissing == null) return pending(APP_LIST, 6, t, "去授权", s.done);
        return ok(APP_LIST, 6, t, "已读取：打开 App、白名单里挑应用都能用");
    }

    // ------------------------------------------------------------------ 可选项（灰 / 绿）

    public static Item a11y(ConsoleStatus.Snapshot s) {
        return opt(A11Y, 7, "无障碍（读屏 / 代点）", s.a11yOn, "已开启", "没开：控屏只能靠执行通道", "去开启");
    }

    public static Item notif(ConsoleStatus.Snapshot s) {
        return opt(NOTIF, 8, "通知使用权", s.notifListenerOn, "已开启：消息提醒、通知触发的任务可用", "没开：读不到通知", "去开启");
    }

    public static Item clipboard(ConsoleStatus.Snapshot s) {
        return opt(CLIPBOARD, 9, "后台读剪贴板", s.clipboardAllowed, "已允许：电脑联动能同步剪贴板", "没开（需要 Shizuku 授予）", "授予");
    }

    public static Item assistant(ConsoleStatus.Snapshot s) {
        return opt(ASSISTANT, 10, "默认数字助理", s.defaultAssistant, "已设为默认：长按电源 / 手势能唤起", "没设：只能用唤醒词 / 悬浮入口", "去设置");
    }

    public static Item battery(ConsoleStatus.Snapshot s) {
        return opt(BATTERY, 11, "电池优化白名单", s.batteryWhitelisted, "已加入：熄屏久了定时任务也准时", "没加入：熄屏久了定时任务可能晚", "加入");
    }

    public static Item cloudTts(ConsoleStatus.Snapshot s) {
        if (Boolean.TRUE.equals(s.cloudTtsOn) && Boolean.FALSE.equals(s.cloudTtsKey)) {
            return new Item(CLOUD_TTS, 12, "云端朗读", false, ConsoleStatus.Level.OFF, "开着但没有语音识别 Key（两者共用）", "去填写", null, null, false);
        }
        Boolean on = s.cloudTtsOn == null ? null : Boolean.valueOf(s.cloudTtsOn && !Boolean.FALSE.equals(s.cloudTtsKey));
        return opt(CLOUD_TTS, 12, "云端朗读", on, "已开启：回答用云端音色朗读", "没开：用系统朗读", "开启");
    }

    /** 按步骤排好的全部项（无障碍只在包里有无障碍服务时列出）。 */
    public static List<Item> items(ConsoleStatus.Snapshot s) {
        List<Item> l = new ArrayList<Item>();
        l.add(overlay(s));
        l.add(mic(s));
        l.add(exec(s));
        l.add(llmKey(s));
        l.add(asrKey(s));
        l.add(appList(s));
        if (s.a11yAvailable) l.add(a11y(s));
        l.add(notif(s));
        l.add(clipboard(s));
        l.add(assistant(s));
        l.add(battery(s));
        l.add(cloudTts(s));
        return l;
    }

    public static List<Item> required(ConsoleStatus.Snapshot s) {
        List<Item> out = new ArrayList<Item>();
        for (Item i : items(s)) if (i.required) out.add(i);
        return out;
    }

    /** 必须项里确定有问题的（红 / 黄，探测不到的不算）。 */
    public static List<Item> problems(ConsoleStatus.Snapshot s) {
        List<Item> out = new ArrayList<Item>();
        for (Item i : required(s)) if (!i.unknown && (i.level == ConsoleStatus.Level.BAD || i.level == ConsoleStatus.Level.WARN)) out.add(i);
        return out;
    }

    public static boolean anyBad(ConsoleStatus.Snapshot s) {
        for (Item i : problems(s)) if (i.level == ConsoleStatus.Level.BAD) return true;
        return false;
    }

    /** 首页汇总入口：全绿（或只有探测不到的）→ null；否则一条「权限未就绪：…」。 */
    public static ConsoleStatus.Banner summary(ConsoleStatus.Snapshot s) {
        List<Item> p = problems(s);
        if (p.isEmpty()) return null;
        boolean bad = anyBad(s);
        StringBuilder b = new StringBuilder(bad ? "权限未就绪：" : "权限待确认：");
        int shown = 0;
        for (Item i : p) {
            if (shown == 3) break;
            if (shown > 0) b.append("、");
            b.append(i.problem);
            shown++;
        }
        if (p.size() > shown) b.append(" 等 ").append(p.size()).append(" 项");
        return new ConsoleStatus.Banner(b.toString(), "去配置", ConsoleStatus.Banner.ACT_PERM, bad ? ConsoleStatus.Level.BAD : ConsoleStatus.Level.WARN);
    }

    /** 解析 {@code appops get <包> READ_CLIPBOARD} 的输出：显式 allow 才算后台可读（「No operations.」/ default / ignore 都不算）。 */
    public static boolean clipboardAllowed(String appopsOut) {
        if (appopsOut == null) return false;
        for (String line : appopsOut.split("\n")) {
            String t = line.trim();
            if (t.startsWith("READ_CLIPBOARD:")) return t.substring("READ_CLIPBOARD:".length()).trim().startsWith("allow");
        }
        return false;
    }

    /** 页顶一句话：几项就绪 / 共几项必须。 */
    public static String headline(ConsoleStatus.Snapshot s) {
        List<Item> req = required(s);
        int ok = 0;
        for (Item i : req) if (i.level == ConsoleStatus.Level.OK) ok++;
        return ok == req.size() ? "必须项全部就绪" : "必须项 " + ok + "/" + req.size() + " 就绪";
    }
}
