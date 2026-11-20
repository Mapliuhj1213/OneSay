package com.onesay.desktop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [overnight-1119] 按「目标有没有达成」判定成败，不只看退出码 / 模型有没有说完话。
 *
 * <p>用户 11-19 手测的两处：
 * <ul>
 *   <li>任务被挡（手机上「按目标操作手机」没开）却显示绿色「完成」→ 任务级判定：完成 / 有警告 / 没做成 / 需要你操作；</li>
 *   <li>「adb connect」回「already connected to 127.0.0.1:16384」、退出码 1，被标红 → 步骤级判定：成功 / 有警告（黄）/ 失败（红）。</li>
 * </ul>
 */
public final class Outcomes {
    private Outcomes() {}

    public enum Level { OK, WARN, FAIL }

    /** 一步命令的判定结果：级别 + 一句给人看的说明（OK 时可能为空）。 */
    public static final class Judged {
        public final Level level;
        public final String note;

        Judged(Level level, String note) {
            this.level = level;
            this.note = note == null ? "" : note;
        }
    }

    static final Pattern ADB_CONNECT = Pattern.compile("(?i)\\badb(\\.exe)?['\"]?\\s+(-s\\s+\\S+\\s+)?connect\\b");
    static final Pattern ADB_CONNECTED = Pattern.compile("(?i)\\b(already connected to|connected to)\\s+\\S+");
    static final Pattern ADB_CONNECT_FAIL = Pattern.compile("(?i)failed to connect|cannot connect|unable to connect|no such host|connection refused|无法连接");
    static final Pattern ROBOCOPY = Pattern.compile("(?i)^\\s*robocopy\\b");
    /** PowerShell 的错误记录（中英文）。 */
    static final Pattern PS_ERROR = Pattern.compile("(?m)(CategoryInfo\\s*:|FullyQualifiedErrorId\\s*:|^At line:\\d+|^所在位置 行:\\d+|^\\+ CategoryInfo|无法将.{1,60}项识别为|is not recognized as the name of)");
    static final Pattern ERROR_WORD = Pattern.compile("(?i)(\\berror\\b|\\bfailed\\b|\\bfailure\\b|exception|cannot|could not|not found|denied|not recognized|失败|错误|无法|找不到|拒绝访问|不是内部或外部命令)");
    static final Pattern EXISTS = Pattern.compile("(?i)already exists|已存在|resourceexists");

    /**
     * 一条命令执行完怎么算：
     * <ul>
     *   <li>adb connect：输出里有「connected to / already connected to」就是连上了（不管退出码）；有 failed to connect 才是失败；</li>
     *   <li>robocopy：退出码 0–7 都是成功（8 起才是失败）；</li>
     *   <li>退出码 0 但输出里有 PowerShell 错误记录：中间有一条报错但整体跑完了 → 有警告；</li>
     *   <li>退出码非 0、一步里串了多条命令：报错出现在最后（最后几行有错误字样）→ 失败；只在中间 → 有警告（后面的步骤还是跑完了）；</li>
     *   <li>「已存在」这类：有警告；其余非 0 → 失败。</li>
     * </ul>
     */
    public static Judged judgeExec(String command, int exit, String output) {
        String cmd = command == null ? "" : command;
        String out = output == null ? "" : output;
        if (ADB_CONNECT.matcher(cmd).find()) {
            boolean connected = ADB_CONNECTED.matcher(out).find() && !ADB_CONNECT_FAIL.matcher(lastLines(out, 2)).find();
            if (connected && exit != 0) {
                boolean multi = multi(cmd);
                // [preview-1120 A-06] 多条命令时只看最后一行：最后一行是报错（su: not found …）就不能因为前面连上了而算成功
                if (!multi || !ERROR_WORD.matcher(lastLines(out, 1)).find() || ADB_CONNECTED.matcher(lastLines(out, 1)).find())
                    return new Judged(Level.OK, "adb 已经连上了（「already connected」也算成功，退出码 " + exit + " 不代表失败）");
            }
            // [preview-1120 AUDIT A-06] 以前这里不看退出码：「adb connect …; adb shell su -c …」里 adb 连上了、后面那条失败（退出码非 0、
            // 最后几行是报错）也判成绿色「完成」。现在只有退出码 0 才直接算成功，其余交给下面的通用判定（多条命令 / 最后一行有没有报错）。
            if (connected && exit == 0) return new Judged(Level.OK, "");
        }
        if (ROBOCOPY.matcher(cmd).find() && !multi(cmd) && exit >= 0 && exit < 8) return new Judged(Level.OK, exit == 0 ? "" : "robocopy 退出码 " + exit + " 表示复制成功");
        if (exit == 0) {
            if (PS_ERROR.matcher(out).find()) return new Judged(Level.WARN, "命令跑完了，但中间有一条报错（不影响整体，详见输出）");
            return new Judged(Level.OK, "");
        }
        if (EXISTS.matcher(out).find() && !ERROR_WORD.matcher(out.replaceAll("(?i)already exists|已存在|resourceexists", "")).find())
            return new Judged(Level.WARN, "要建的东西已经存在（不算失败）");
        if (multi(cmd)) {
            String tail = lastLines(out, 1);
            if (!tail.isBlank() && !ERROR_WORD.matcher(tail).find() && !PS_ERROR.matcher(tail).find())
                return new Judged(Level.WARN, "这一步里有一条命令返回了非零退出码（" + exit + "），但后面的命令照常跑完了");
        }
        return new Judged(Level.FAIL, "");
    }

    /** 命令里串了多条（; && || 换行）。只是粗看，引号里的分号也会算 —— 只影响「失败」还是「有警告」，不影响执不执行。 */
    static boolean multi(String cmd) {
        return cmd != null && (cmd.contains(";") || cmd.contains("&&") || cmd.contains("||") || cmd.trim().contains("\n"));
    }

    static String lastLines(String s, int n) {
        if (s == null) return "";
        List<String> l = new java.util.ArrayList<>();
        for (String x : s.trim().split("\\R")) if (!x.isBlank()) l.add(x);
        StringBuilder b = new StringBuilder();
        int start = Math.max(0, l.size() - n);
        for (int i = start; i < l.size(); i++) b.append(l.get(i)).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------------ 任务级

    /** 任务卡的结束状态（界面颜色）：done 绿「完成」、warn 黄「有警告」、failed 红「没做成」、needs_user 橙「需要你操作」。 */
    public static final String DONE = "done", WARN = "warn", FAILED = "failed", NEEDS_USER = "needs_user";

    /** 模型在答复开头 / 结尾标的结果（系统提示要求它标）：【完成】【有警告】【没做成】【需要你操作】。 */
    static final Pattern TAG = Pattern.compile("^\\s*[【\\[]\\s*(完成|有警告|部分完成|没做成|失败|需要你操作|需要你处理)\\s*[】\\]]\\s*|\\s*[【\\[]\\s*(完成|有警告|部分完成|没做成|失败|需要你操作|需要你处理)\\s*[】\\]]\\s*$");

    static final Pattern NEEDS_USER_TEXT = Pattern.compile("(开关(还)?没(有)?(开|打开)|没(有)?(开启|打开)这一项|需要你(先|自己|在|去|手动|点|确认|打开|开启|授权|登录|操作)|请你(先|在|去|手动|点|打开|开启|授权|登录)|请(先)?在手机上|麻烦你|等你(确认|点|补充|选)|你来(补充|选)|手动(点|打开|开启|授权)|你选(一个|哪个))");
    static final Pattern FAILED_TEXT = Pattern.compile("(没(能|有)?(做成|完成|成功|办成|装上|下载成功|打开)|失败了|做不了|无法完成|没法完成|没办法(完成|做)|不支持|版本太旧|版本过旧|被(拒绝|挡)|拒绝了|出错了|报错了)");
    static final Pattern SUCCESS_TEXT = Pattern.compile("(已经?(完成|做好|装好|打开|下载好|发送|发出|关闭|开启|设置好)|成功(了|地)?|搞定|做完了)");

    /** 任务结果：最终状态 + 去掉标记后的答复。 */
    public static final class TaskEnd {
        public final String state, text;

        TaskEnd(String state, String text) {
            this.state = state;
            this.text = text;
        }
    }

    /**
     * 模型说完话收工（status=done）时按目标判定：先看它标的结果；没标就看答复里有没有「没做成 / 需要你操作」的说法；
     * 再看最后一步是不是失败、有没有黄色步骤。其他结束原因（拒绝、没人批准、卡住、出错…）原样返回。
     */
    @SuppressWarnings("unchecked")
    public static TaskEnd judgeTask(String status, String answer, List<Object> steps) {
        String text = answer == null ? "" : answer;
        if (!"done".equals(status)) return new TaskEnd(status, text);
        String tag = null;
        Matcher m = TAG.matcher(text);
        if (m.find()) {
            tag = m.group(1) != null ? m.group(1) : m.group(2);
            text = (text.substring(0, m.start()) + text.substring(m.end())).trim();
        }
        if (tag != null) {
            switch (tag) {
                case "完成": return new TaskEnd(lastStepFailed(steps) ? WARN : DONE, text);
                case "有警告": case "部分完成": return new TaskEnd(WARN, text);
                case "没做成": case "失败": return new TaskEnd(FAILED, text);
                default: return new TaskEnd(NEEDS_USER, text);
            }
        }
        if (NEEDS_USER_TEXT.matcher(text).find()) return new TaskEnd(NEEDS_USER, text);
        if (FAILED_TEXT.matcher(text).find() && !SUCCESS_TEXT.matcher(firstSentence(text)).find()) return new TaskEnd(FAILED, text);
        if (lastStepFailed(steps)) return new TaskEnd(SUCCESS_TEXT.matcher(text).find() ? WARN : FAILED, text);
        if (anyStep(steps, "warn")) return new TaskEnd(WARN, text);
        return new TaskEnd(DONE, text);
    }

    static String firstSentence(String s) {
        String[] p = s.split("[。！!\\n]", 2);
        return p.length == 0 ? "" : p[0];
    }

    @SuppressWarnings("unchecked")
    static boolean lastStepFailed(List<Object> steps) {
        if (steps == null) return false;
        for (int i = steps.size() - 1; i >= 0; i--) {
            Object o = steps.get(i);
            if (!(o instanceof Map)) continue;
            Map<String, Object> x = (Map<String, Object>) o;
            if (!"step".equals(x.get("kind"))) continue;
            return "fail".equals(x.get("state"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static boolean anyStep(List<Object> steps, String state) {
        if (steps == null) return false;
        for (Object o : steps) if (o instanceof Map && "step".equals(((Map<String, Object>) o).get("kind")) && state.equals(((Map<String, Object>) o).get("state"))) return true;
        return false;
    }

    /** 做完了的状态（「继续」不接着它）：完成、有警告。 */
    public static boolean finished(String state) {
        return DONE.equals(state) || WARN.equals(state);
    }

    /** 任务卡状态的中文（通知标题用）。 */
    public static String zh(String state) {
        switch (state == null ? "" : state.toLowerCase(Locale.ROOT)) {
            case DONE: return "完成";
            case WARN: return "完成（有警告）";
            case FAILED: return "没做成";
            case NEEDS_USER: return "需要你操作";
            case "denied": return "你拒绝了";
            case "timeout": return "没人批准";
            case "stopped": return "已停止";
            case "time_limit": return "到时长上限";
            case "loop": return "卡住了";
            case "no_progress": return "没有进展";
            case "error": return "出错";
            default: return state == null ? "" : state;
        }
    }
}
