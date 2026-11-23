package com.onesay.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [overnight-1119] 给人看的文字里不露内部能力名 / 工具名（ping、caps、find_phone、phone_operate…），换成中文说法；
 * 顺手修几句不通的话（「微信已经打开不了」）。只改给人看的答复 / 旁白 / 步骤结论，不改命令输出原文。
 */
public final class Humanize {
    private Humanize() {}

    /** 手机能力名 → 中文（和手机端控制台的开关名一致）。 */
    public static final Map<String, String> PHONE = new LinkedHashMap<>();
    /** 电脑对话的工具名 → 中文。 */
    public static final Map<String, String> TOOLS = new LinkedHashMap<>();

    static {
        PHONE.put("ping", "在线检测");
        PHONE.put("caps", "能力清单");
        PHONE.put("status", "查看电量和状态");
        PHONE.put("find_phone", "找手机（响铃）");
        PHONE.put("open_url", "打开网址");
        PHONE.put("open_app", "打开 App");
        PHONE.put("screen_on", "点亮屏幕");
        PHONE.put("volume_set", "调媒体音量");
        PHONE.put("clipboard_set", "写手机剪贴板");
        PHONE.put("notify", "在手机上弹通知");
        PHONE.put("flashlight", "手电筒");
        PHONE.put("setting_set", "改手机设置");
        PHONE.put("file_write", "写文件");
        PHONE.put("file_list", "看工作区文件");
        PHONE.put("uninstall_app", "卸载 App");
        PHONE.put("script_list", "看工作区脚本");
        PHONE.put("script_run", "运行工作区脚本");
        PHONE.put("sys_exec", "系统命令");
        PHONE.put("operate", "按目标操作手机");
        PHONE.put("app_list", "看手机上装了哪些 App"); // [rel-1123-b]
        TOOLS.put("phone_status", "查手机状态");
        TOOLS.put("phone_open_app", "在手机上打开 App");
        TOOLS.put("phone_operate", "按目标操作手机");
        TOOLS.put("phone_send_file", "把文件发到手机");
        TOOLS.put("run_command", "执行命令");
        TOOLS.put("open_target", "打开程序 / 网站");
        TOOLS.put("write_file", "写文件");
        TOOLS.put("show_file", "显示文件");
        TOOLS.put("app_control", "操作 OneSay 自己（换页 / 改设置）"); // [fix-1123-a]
        TOOLS.put("android_ui", "读 / 点安卓界面");
        TOOLS.put("ask_choice", "让你选");
        TOOLS.put("screen_look", "截屏");
        TOOLS.put("net_download", "下载文件");
        TOOLS.put("mcp_call", "调用 MCP 工具");
        TOOLS.put("wf_call", "工作流 / 技能"); // [workflow-1120]
        TOOLS.put("phone_shortcuts", "看手机上的快捷指令");
        TOOLS.put("phone_run_shortcut", "在手机上运行快捷指令");
        TOOLS.put("phone_apps", "看手机上装了哪些 App"); // [rel-1123-b]
    }

    /** [rel-1123-b] 手机回传的目标结果状态 → 中文（接在「手机任务」后面）。 */
    public static String goalState(String st) {
        switch (st == null ? "" : st) {
            case "running": return "还在做";
            case "done": return "做完了";
            case "denied": return "被拒绝了";
            case "stopped": return "停掉了";
            case "timeout": return "做太久停了";
            case "interrupted": return "被打断了";
            case "unknown": return "没回结果";
            default: return "没做成";
        }
    }

    /** [rel-1123-b] 一句话说手机回传的结果（电脑任务卡 / 给模型的工具结果）。text = 手机自己说的话。 */
    public static String goalLine(String st, String text) {
        String t = text == null ? "" : text.trim();
        switch (st == null ? "" : st) {
            case "done": return "手机做完了" + (t.isEmpty() ? "" : "：" + t);
            case "denied": return "手机没做" + (t.isEmpty() ? "（在手机上被拒绝了）" : "（" + t + "）");
            case "stopped": return "手机上停掉了" + (t.isEmpty() ? "" : "：" + t);
            case "timeout": return "手机做太久，停了" + (t.isEmpty() ? "" : "：" + t);
            case "interrupted": return "手机上被新指令打断了" + (t.isEmpty() ? "" : "：" + t);
            case "unknown": return "手机没回结果" + (t.isEmpty() ? "" : "：" + t);
            default: return "手机没做成" + (t.isEmpty() ? "（手机没说原因）" : "：" + t);
        }
    }

    /** 一个手机能力名的中文；不认识的返回「其他功能」（不把 id 原样给人看）。 */
    public static String phoneCap(String id) {
        if (id == null) return "";
        String z = PHONE.get(id.trim());
        return z == null ? "其他功能" : z;
    }

    /** 一串手机能力名 → 中文列表（去掉在线检测 / 能力清单这两项内部用的，去重）。 */
    public static List<String> phoneCaps(List<?> ids) {
        List<String> out = new ArrayList<>();
        if (ids != null) for (Object o : ids) {
            String id = String.valueOf(o);
            if (id.equals("ping") || id.equals("caps")) continue;
            String z = phoneCap(id);
            if (!out.contains(z)) out.add(z);
        }
        return out;
    }

    static final Pattern UNDERSCORE_ID;
    static final Pattern SHORT_ID = Pattern.compile("(?<=[「`（(、，,：:])(ping|caps|status|notify|operate)(?=[」`）)、，,。；;]|$)");

    static {
        List<String> ids = new ArrayList<>(TOOLS.keySet());
        for (String k : PHONE.keySet()) if (k.contains("_")) ids.add(k);
        ids.sort((a, b) -> b.length() - a.length());
        UNDERSCORE_ID = Pattern.compile("`?(?<![A-Za-z0-9_])(" + String.join("|", ids) + ")(?![A-Za-z0-9_])`?");
    }

    /** 改给人看的一段话。 */
    public static String text(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        Matcher m = UNDERSCORE_ID.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String id = m.group(1);
            String z = TOOLS.containsKey(id) ? TOOLS.get(id) : PHONE.getOrDefault(id, id);
            m.appendReplacement(b, Matcher.quoteReplacement("「" + z + "」"));
        }
        m.appendTail(b);
        String x = b.toString().replace("「「", "「").replace("」」", "」");
        Matcher n = SHORT_ID.matcher(x);
        b = new StringBuffer();
        while (n.find()) n.appendReplacement(b, Matcher.quoteReplacement(PHONE.get(n.group(1))));
        n.appendTail(b);
        x = b.toString();
        // 病句：「微信已经打开不了」→「微信现在打不开」；「已经做不了」→「现在做不了」
        x = x.replaceAll("已经打开不了", "现在打不开").replaceAll("已经(做|执行|操作|发送|完成)不了", "现在$1不了");
        return x;
    }

    /** 手机按目标操作的现状：on = 开着；off = 手机支持但开关没开；old = 手机上的 OneSay 版本太旧，没有这项；unknown = 没查到。 */
    public static String operateState(List<?> caps, List<?> known) {
        if (caps != null && caps.contains("operate")) return "on";
        if (known != null && known.contains("operate")) return "off";
        if (known != null && !known.isEmpty()) return "old";
        return "unknown";
    }

    public static final String OPERATE_PATH = "手机 OneSay「控制台 › 电脑联动 › 电脑可以让手机做的事」";

    /** 按目标操作不可用时，给人看的下一步。 */
    public static String operateHint(String state) {
        switch (state) {
            case "off": return "手机支持「按目标操作手机」，但开关没开。下一步：在" + OPERATE_PATH + "里打开「按目标操作手机」，再让我做一次。";
            case "old": return "手机上的 OneSay 版本太旧，还不支持「按目标操作手机」（只能打开 App / 网址这类单项）。下一步：给手机装新版 OneSay 手机包（11-19 以后的），装好后在"
                    + OPERATE_PATH + "里打开「按目标操作手机」。";
            case "unknown": return "「按目标操作手机」现在用不了：要么手机上这一项没开（在" + OPERATE_PATH + "里打开），要么手机上的 OneSay 版本太旧（要装新版手机包）。";
            default: return "";
        }
    }

    /** 手机回的失败文字 → 人话 + 下一步（区分开关没开 / 版本太旧）。 */
    public static String phoneFailure(String phoneText, String cmd) {
        String t = phoneText == null ? "" : phoneText;
        if (t.contains("手机不支持「") || t.contains("不认识的指令") || t.contains("unknown command")) {
            if ("operate".equals(cmd)) return operateHint("old");
            String z = phoneCap(cmd);
            return "手机上的 OneSay 版本太旧，还不支持「" + z + "」。下一步：给手机装新版 OneSay 手机包。";
        }
        if (t.contains("手机上没开「")) {
            if ("operate".equals(cmd)) return operateHint("off");
            return text(t) + "。下一步：在" + OPERATE_PATH + "里打开这一项，再让我做一次。";
        }
        return text(t);
    }
}
