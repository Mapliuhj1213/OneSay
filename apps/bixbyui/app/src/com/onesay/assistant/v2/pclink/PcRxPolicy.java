package com.onesay.assistant.v2.pclink;

import java.util.Map;

/**
 * [pclink v2] 电脑 → 手机能下的指令（与电脑端 {@code PhoneCmds} 对应）、每项的名字 / 说明 / 默认开关 / 参数检查。纯 Java。
 *
 * <p>命令集思路参考 Home Assistant 安卓伴侣 App（Apache-2.0）的通知指令：command_screen_on、command_volume_level、
 * command_launch_app、command_activity（打开网址）等，以及用闹钟音频流最大音量「找手机」的做法；代码是自己写的。
 *
 * <p>都必须先配对（指令带配对密钥签名 + 时间窗 + 一次性编号），每项在控制台「电脑联动」页单独有开关。
 * 开源版默认保守：总开关「接受电脑指令」默认关（不常连）；打开后，危险项（打开网址 / 打开 App / 写剪贴板）仍默认关，要用户逐项打开。
 *
 * <p>[link-v2 第二轮] 分档时加的 8 个指令（手电筒、改设置、工作区文件、卸载、脚本、系统命令）按分档之前的语义加回来：
 * 没有执行级别、手机上不弹确认，每项一个开关、挂在「接受电脑指令」总开关下；开源版这 8 项都默认关。红线照旧（{@link PcRx} 里查）：
 * 支付 / 银行 / 钱包类 App 不碰、系统 App 不卸载、网址和剪贴板内容过不了检查不做、给 App 授权只生成脚本。
 */
public final class PcRxPolicy {

    public static final String FIND = "find_phone", OPEN_URL = "open_url", OPEN_APP = "open_app", SCREEN_ON = "screen_on",
            VOLUME = "volume_set", CLIPBOARD = "clipboard_set", NOTIFY = "notify", STATUS = "status";

    /** 控制台显示顺序（原来的 8 项）。 */
    public static final String[] CMDS = {FIND, SCREEN_ON, VOLUME, NOTIFY, STATUS, CLIPBOARD, OPEN_URL, OPEN_APP};

    /** 加回来的 8 项（每项一个开关，和上面一样挂在总开关下）。 */
    public static final String FLASHLIGHT = "flashlight", SETTING = "setting_set", FILE_WRITE = "file_write", FILE_LIST = "file_list",
            UNINSTALL = "uninstall_app", SCRIPT_LIST = "script_list", SCRIPT_RUN = "script_run", SYS_EXEC = "sys_exec";
    public static final String[] EXTRA = {FLASHLIGHT, SETTING, FILE_WRITE, FILE_LIST, UNINSTALL, SCRIPT_LIST, SCRIPT_RUN, SYS_EXEC};

    /** 控制台里全部的逐项开关（原来的 8 项 + 加回来的 8 项）。 */
    public static final String[] ALL = concat(CMDS, EXTRA);
    public static final int FILE_MAX = 256 * 1024, CMD_MAX = 4000;

    static String[] concat(String[] a, String[] b) {
        String[] x = new String[a.length + b.length];
        System.arraycopy(a, 0, x, 0, a.length);
        System.arraycopy(b, 0, x, a.length, b.length);
        return x;
    }

    /** 「接受电脑指令」总开关默认值（开源版关：用户在控制台打开后才保持到电脑的常连）。 */
    public static final boolean MASTER_DEFAULT = false;

    /** 找手机最长响多久（秒）。 */
    public static final int RING_MAX_S = 120, RING_DEFAULT_S = 30;
    public static final int TEXT_MAX = 100000;

    private PcRxPolicy() {}

    public static boolean known(String cmd) {
        for (String c : CMDS) if (c.equals(cmd)) return true;
        return extra(cmd);
    }

    /** 加回来的 8 项之一。 */
    public static boolean extra(String cmd) {
        for (String c : EXTRA) if (c.equals(cmd)) return true;
        return false;
    }

    /** 能让电脑在手机上「动手」的：打开网址、打开 App、改剪贴板；加回来的改设置、写文件、卸载、跑脚本、系统命令。 */
    public static boolean dangerous(String cmd) {
        return OPEN_URL.equals(cmd) || OPEN_APP.equals(cmd) || CLIPBOARD.equals(cmd)
                || SETTING.equals(cmd) || FILE_WRITE.equals(cmd) || UNINSTALL.equals(cmd) || SCRIPT_RUN.equals(cmd) || SYS_EXEC.equals(cmd);
    }

    /** 各项默认开关（开源版：危险项和加回来的 8 项默认关）。 */
    public static boolean defaultOn(String cmd) {
        return known(cmd) && !dangerous(cmd) && !extra(cmd);
    }

    public static String label(String cmd) {
        if (FIND.equals(cmd)) return "找手机（响铃）";
        if (SCREEN_ON.equals(cmd)) return "点亮屏幕";
        if (VOLUME.equals(cmd)) return "调媒体音量";
        if (NOTIFY.equals(cmd)) return "在手机上弹通知";
        if (STATUS.equals(cmd)) return "查看电量和状态";
        if (CLIPBOARD.equals(cmd)) return "写手机剪贴板";
        if (OPEN_URL.equals(cmd)) return "打开网址";
        if (OPEN_APP.equals(cmd)) return "打开 App";
        if (FLASHLIGHT.equals(cmd)) return "手电筒";
        if (SETTING.equals(cmd)) return "改手机设置";
        if (FILE_WRITE.equals(cmd)) return "写文件";
        if (FILE_LIST.equals(cmd)) return "看工作区文件";
        if (UNINSTALL.equals(cmd)) return "卸载 App";
        if (SCRIPT_LIST.equals(cmd)) return "看工作区脚本";
        if (SCRIPT_RUN.equals(cmd)) return "运行工作区脚本";
        if (SYS_EXEC.equals(cmd)) return "系统命令";
        return cmd;
    }

    public static String sub(String cmd) {
        if (FIND.equals(cmd)) return "最大音量响铃，静音模式也响；电脑上再点一次或手机上点通知可停";
        if (SCREEN_ON.equals(cmd)) return "只点亮，不解锁";
        if (VOLUME.equals(cmd)) return "只改媒体音量（0–100）";
        if (NOTIFY.equals(cmd)) return "电脑发来的提醒以通知显示";
        if (STATUS.equals(cmd)) return "电量、是否充电、屏幕、铃声模式、媒体音量";
        if (CLIPBOARD.equals(cmd)) return "电脑把文字放进手机剪贴板（会覆盖原内容；像命令、坏链接、超过 10 KB 的不写，验证码 / 卡号等敏感内容 60 秒后自动清除）";
        if (OPEN_URL.equals(cmd)) return "只许 http / https，带账号密码、内网地址、仿冒域名的不开";
        if (OPEN_APP.equals(cmd)) return "按包名或桌面上的名字打开；支付 / 银行 / 钱包类不打开";
        if (FLASHLIGHT.equals(cmd)) return "打开 / 关闭闪光灯";
        if (SETTING.equals(cmd)) return "亮度、音量、勿扰、铃声、屏幕超时、Wi-Fi、蓝牙；飞行模式、移动数据等走特权通道";
        if (FILE_WRITE.equals(cmd)) return "写进 OneSay 工作区；工作区以外只写共享存储里的普通文件";
        if (FILE_LIST.equals(cmd)) return "列出 OneSay 工作区里的文件";
        if (UNINSTALL.equals(cmd)) return "弹出系统卸载确认，最后由你点；系统 App 和支付 / 银行 / 钱包类不卸";
        if (SCRIPT_LIST.equals(cmd)) return "列出工作区 scripts/ 里的脚本";
        if (SCRIPT_RUN.equals(cmd)) return "运行工作区 scripts/ 里的 .sh（走特权通道）";
        if (SYS_EXEC.equals(cmd)) return "走特权通道执行命令；给 App 授权的只生成脚本，不执行";
        return "";
    }

    /** 参数检查；通过返回 null，否则返回给人看的原因。 */
    public static String check(String cmd, Map<String, Object> a) {
        if (!known(cmd)) return "手机不认识这个指令";
        if (OPEN_URL.equals(cmd)) {
            String u = str(a, "url");
            if (u.length() > 2000 || !(u.startsWith("https://") || u.startsWith("http://")) || PcVoice.normalizeUrl(u) == null) return "只能打开 http / https 网址";
            return null;
        }
        if (OPEN_APP.equals(cmd)) {
            String pkg = str(a, "package"), name = str(a, "name");
            if (pkg.length() == 0 && name.length() == 0) return "没说打开哪个 App";
            if (pkg.length() > 0 && !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return "包名格式不对";
            if (name.length() > 64) return "名字太长";
            return null;
        }
        if (VOLUME.equals(cmd)) {
            Object p = a == null ? null : a.get("percent");
            if (!(p instanceof Number)) return "要给音量 0–100";
            int v = ((Number) p).intValue();
            return v < 0 || v > 100 ? "音量要在 0–100" : null;
        }
        if (CLIPBOARD.equals(cmd)) {
            String t = a == null || a.get("text") == null ? "" : String.valueOf(a.get("text"));
            if (t.length() == 0) return "没有文字";
            return t.length() > TEXT_MAX ? "文字太长（超过 10 万字）" : null;
        }
        if (NOTIFY.equals(cmd)) {
            if (str(a, "title").length() == 0 && str(a, "text").length() == 0) return "通知没有内容";
            return str(a, "title").length() > 200 || str(a, "text").length() > 4000 ? "通知太长" : null;
        }
        if (FIND.equals(cmd)) {
            String act = str(a, "action");
            if (!(act.length() == 0 || "start".equals(act) || "stop".equals(act))) return "action 只能是 start / stop";
            return null;
        }
        if (FLASHLIGHT.equals(cmd)) {
            Object on = a == null ? null : a.get("on");
            return on == null || on instanceof Boolean ? null : "on 只能是 true / false";
        }
        if (SETTING.equals(cmd)) {
            String n = str(a, "name");
            if (n.length() == 0) return "没说改哪个设置";
            if (LinkPolicy.phoneSettingCat(n) == LinkPolicy.Cat.UNKNOWN) return "手机不支持改「" + n + "」";
            Object v = a.get("value");
            if (v == null) return "没给设置值";
            return String.valueOf(v).length() > 200 ? "设置值太长" : null;
        }
        if (FILE_WRITE.equals(cmd)) {
            if (str(a, "path").length() == 0) return "没给文件路径";
            Object t = a.get("text");
            if (t == null) return "没给文件内容";
            return String.valueOf(t).length() > FILE_MAX ? "文件太大（超过 256 KB）" : null;
        }
        if (UNINSTALL.equals(cmd)) {
            String pkg = str(a, "package");
            return pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? null : "要给包名";
        }
        if (SCRIPT_RUN.equals(cmd)) {
            String n = str(a, "name");
            return n.length() == 0 || n.contains("/") || n.contains("\\") || n.startsWith(".") ? "要给 scripts/ 里的脚本文件名" : null;
        }
        if (SYS_EXEC.equals(cmd)) {
            String c = str(a, "command");
            if (c.length() == 0) return "没给命令";
            return c.length() > CMD_MAX ? "命令太长" : null;
        }
        return null;
    }

    /** 找手机响多久：默认 30 秒，最多 120 秒。 */
    public static int ringSeconds(Map<String, Object> a) {
        Object s = a == null ? null : a.get("seconds");
        int v = s instanceof Number ? ((Number) s).intValue() : RING_DEFAULT_S;
        return Math.max(5, Math.min(RING_MAX_S, v));
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
