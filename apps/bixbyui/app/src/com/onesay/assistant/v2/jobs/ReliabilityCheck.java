package com.onesay.assistant.v2.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 控制台「定时可靠性」自检卡的探测与判读（lane-a 第 3 项；研究报告 §3.2.3-4）。纯 Java 部分，JVM 可测。
 *
 * <p>四项：Doze 白名单、后台运行 appops、前台服务（WakeService）在跑、闹钟已排；外加最近 7 天准点 / 晚到 / 错过统计。
 * 探测命令是常量 + 包名（包名过 {@link #PKG} 校验），经 root 守护只读执行。三星「休眠应用」名单无法可靠读取，只给文字引导。
 */
public final class ReliabilityCheck {

    private ReliabilityCheck() {}

    static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    public static final String SEP = "---bixby-sep---";

    /**
     * 2026-10-25 exec 守护 v4：真正执行的形式——两条单命令 + 本地分隔符（v4 拒含 ; 的复合脚本，fix-root.md §3）。
     * {@link #probeCmd} 保留作可读描述。
     */
    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> probeSteps(String pkg) {
        if (pkg == null || !PKG.matcher(pkg).matches()) throw new IllegalArgumentException("pkg");
        return java.util.Arrays.asList(
                com.onesay.assistant.v2.executor.ShellScript.run("dumpsys deviceidle whitelist"),
                com.onesay.assistant.v2.executor.ShellScript.echo(SEP),
                com.onesay.assistant.v2.executor.ShellScript.run("cmd appops get " + pkg + " RUN_IN_BACKGROUND"));
    }

    public static String probeCmd(String pkg) {
        if (pkg == null || !PKG.matcher(pkg).matches()) throw new IllegalArgumentException("pkg");
        return "dumpsys deviceidle whitelist; echo " + SEP + "; cmd appops get " + pkg + " RUN_IN_BACKGROUND";
    }

    // 加入 / 还原省电白名单见 PowerWhitelist（第 7 项：先记录原状态、只 debug 变体生效、Android 8 兼容）。

    public static final class Item {
        public final String title;
        /** 1 好 / 0 不好 / -1 读不到。 */
        public final int ok;
        public final String detail;

        Item(String title, int ok, String detail) {
            this.title = title;
            this.ok = ok;
            this.detail = detail;
        }

        @Override public String toString() {
            return title + "=" + ok;
        }
    }

    /** Doze 白名单输出里有没有本包（行形如「user,com.onesay.assistant,10123」或「system-excidle,…」）。 */
    public static int dozeWhitelisted(String out, String pkg) {
        if (out == null) return -1;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length >= 2 && f[1].trim().equals(pkg)) return 1;
        }
        return 0;
    }

    /** appops 输出「RUN_IN_BACKGROUND: allow; time=…」→ 1；ignore / deny → 0；default / 读不出 → -1。 */
    public static int runInBackground(String out) {
        if (out == null) return -1;
        String s = out.toLowerCase(java.util.Locale.ROOT);
        int i = s.indexOf("run_in_background:");
        if (i < 0) return s.contains("no operations") ? -1 : -1;
        String v = s.substring(i + "run_in_background:".length()).trim();
        if (v.startsWith("allow")) return 1;
        if (v.startsWith("ignore") || v.startsWith("deny") || v.startsWith("errored")) return 0;
        return -1;
    }

    public static List<Item> evaluate(String probeOut, String pkg, boolean fgsRunning, long alarmAt, long now, int[] stats7d) {
        List<Item> l = new ArrayList<Item>();
        String doze = null, ops = null;
        if (probeOut != null) {
            int i = probeOut.indexOf(SEP);
            doze = i < 0 ? probeOut : probeOut.substring(0, i);
            ops = i < 0 ? null : probeOut.substring(i + SEP.length());
        }
        int d = dozeWhitelisted(doze, pkg);
        l.add(new Item("Doze 白名单", d, d == 1 ? "已加入" : d == 0 ? "未加入：息屏久了定时可能晚到" : "读不到（root 守护未就绪）"));
        int r = runInBackground(ops);
        l.add(new Item("后台运行（appops）", r, r == 1 ? "允许" : r == 0 ? "被限制" : "系统默认 / 读不到"));
        l.add(new Item("前台服务", fgsRunning ? 1 : 0, fgsRunning ? "语音与按键服务在运行" : "没在运行：事件触发收不到"));
        l.add(new Item("下一次闹钟", alarmAt > now ? 1 : alarmAt <= 0 ? -1 : 0,
                alarmAt > now ? "已排" : alarmAt <= 0 ? "没有待触发的任务" : "闹钟时刻已过但没触发"));
        if (stats7d != null && stats7d.length == 3) {
            int total = stats7d[0] + stats7d[1] + stats7d[2];
            l.add(new Item("最近 7 天", total == 0 ? -1 : stats7d[2] == 0 && stats7d[1] == 0 ? 1 : 0,
                    "准点 " + stats7d[0] + " · 晚到 " + stats7d[1] + " · 错过 " + stats7d[2]));
        }
        return l;
    }
}
