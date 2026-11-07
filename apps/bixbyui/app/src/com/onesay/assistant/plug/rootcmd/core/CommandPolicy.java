/*
 * [mika.rootcmd 插件] OneSay 自写（非上游代码）。Mika 的 run_command 跑在 Linux 沙箱里；Android 8 上没有沙箱，
 * 这里改成 root shell + 白名单：只读命令直接跑，危险命令一律拒绝，其余要用户确认。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.rootcmd.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** root 命令三分：ALLOW（只读白名单）/ CONFIRM（其余，要用户确认）/ DENY（危险，拒绝）。纯函数。 */
public final class CommandPolicy {
    private CommandPolicy() {
    }

    public enum Verdict { ALLOW, CONFIRM, DENY }

    public static final class Decision {
        public final Verdict verdict;
        public final String reason;

        Decision(Verdict v, String r) {
            verdict = v;
            reason = r;
        }
    }

    public static final int MAX_LEN = 400;

    /** 只读命令（首词）。参数另查危险模式。 */
    static final String[] READ_ONLY = {"getprop", "dumpsys", "ls", "cat", "head", "tail", "grep", "wc", "df", "du", "uptime", "date", "id", "whoami",
            "ps", "top", "free", "uname", "pm", "settings", "cmd", "ip", "ifconfig", "ping", "netstat", "logcat", "stat", "find", "which", "echo",
            "printenv", "env", "getenforce", "wm", "service", "lsof", "sort", "uniq", "cut", "tr", "awk", "sed", "md5sum", "sha256sum", "file", "readlink", "realpath", "basename", "dirname"};

    /** 首词是只读命令时，仍须满足的子命令约束（pm 只能 list/path/dump；settings 只能 get/list；cmd / service / wm 只能查询类）。 */
    static boolean readOnlySub(String head, List<String> args) {
        String a0 = args.isEmpty() ? "" : args.get(0);
        if (head.equals("pm")) return a0.equals("list") || a0.equals("path") || a0.equals("dump");
        if (head.equals("settings")) return a0.equals("get") || a0.equals("list");
        if (head.equals("cmd")) return args.size() >= 2 && (args.get(1).equals("list") || args.get(1).equals("get") || args.get(1).startsWith("dump"));
        if (head.equals("service")) return a0.equals("list") || a0.equals("check");
        if (head.equals("wm")) return a0.equals("size") || a0.equals("density");
        if (head.equals("ping")) return args.contains("-c");
        if (head.equals("top")) return args.contains("-n");
        if (head.equals("logcat")) return args.contains("-d");
        if (head.equals("sed")) return !join(args).contains("-i");
        if (head.equals("find")) return !join(args).contains("-delete") && !join(args).contains("-exec");
        if (head.equals("awk")) return !join(args).contains("system(");
        return true;
    }

    private static final Pattern DANGER = Pattern.compile(
            "(?i)(^|\\s|;|&|\\|)(rm\\s+-[a-z]*r|rm\\s+-[a-z]*f|reboot|shutdown|poweroff|mkfs|mke2fs|dd\\s|format|wipe|factory|recovery|fastboot|"
                    + "setenforce|setprop\\s+(ro|persist|sys)\\.|mount\\s|umount|chmod\\s+[0-7]*7[0-7]*\\s+/|chown\\s+.*\\s/|kill\\s+-9\\s+1\\b|killall|"
                    + "pm\\s+(uninstall|clear|disable|hide|reset-permissions)|settings\\s+(put|delete)\\s+(secure|global)|iptables|ip6tables|"
                    + "svc\\s+power|am\\s+(force-stop\\s+com\\.android\\.systemui|crash)|>\\s*/(system|vendor|dev|data/system)|/dev/block|"
                    + "magisk|su\\s+-c|\\bsu\\b|curl\\s.*\\|\\s*sh|wget\\s.*\\|\\s*sh|base64\\s+-d.*\\|\\s*sh|\\beval\\b|nc\\s+-l|busybox\\s+(rm|dd|mount))");

    public static Decision classify(String cmd) {
        if (cmd == null || cmd.trim().length() == 0) return new Decision(Verdict.DENY, "空命令");
        String c = cmd.trim();
        if (c.length() > MAX_LEN) return new Decision(Verdict.DENY, "命令太长");
        if (c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) return new Decision(Verdict.DENY, "只能一行");
        if (DANGER.matcher(c).find()) return new Decision(Verdict.DENY, "危险命令（删除 / 刷写 / 改系统 / 提权 / 远程脚本）");
        if (c.contains("`") || c.contains("$(")) return new Decision(Verdict.CONFIRM, "含命令替换");
        if (c.contains(">") || c.contains(">>")) return new Decision(Verdict.CONFIRM, "会写文件");
        // 管道 / 串联：每一段都只读才放行
        String[] parts = c.split("\\|\\||&&|;|\\|");
        for (String p : parts) {
            List<String> w = words(p);
            if (w.isEmpty()) return new Decision(Verdict.CONFIRM, "空段");
            String head = w.get(0);
            int k = head.lastIndexOf('/');
            if (k >= 0) head = head.substring(k + 1);
            if (!contains(READ_ONLY, head)) return new Decision(Verdict.CONFIRM, "「" + head + "」不在只读白名单");
            if (!readOnlySub(head, w.subList(1, w.size()))) return new Decision(Verdict.CONFIRM, "「" + head + "」这个用法会改动东西");
        }
        return new Decision(Verdict.ALLOW, "只读");
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<String>();
        for (String w : s.trim().split("\\s+")) if (w.length() > 0) out.add(w.toLowerCase(Locale.ROOT));
        return out;
    }

    static boolean contains(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append(' ');
        return b.toString();
    }

    /** 输出给模型：超过 max 字只留头尾。 */
    public static String clipOutput(String out, int max) {
        if (out == null) return "";
        if (out.length() <= max) return out;
        return out.substring(0, max * 3 / 4) + "\n…（中间省略 " + (out.length() - max) + " 字）…\n" + out.substring(out.length() - max / 4);
    }
}
