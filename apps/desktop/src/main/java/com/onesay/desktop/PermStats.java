package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * [overnight-1119] 「权限审批记录」：按事件类型统计你点了几次「允许」、几次「拒绝」。
 *
 * <p>规则（用户 11-18 / 11-19 两次明确要求）：
 * <ul>
 *   <li>审批不进通知（通知列表和 Windows 通知中心都不进），只记在这里；</li>
 *   <li>计数按单次点击算：确认卡上点一次「允许一次」「允许本次任务」「这次运行都允许」「以后直接执行」= 允许 +1，点一次「拒绝」= 拒绝 +1；
 *       一个任务里批了十张卡就是十次，不按「一组」算；之后因为「允许本次任务」自动放行的步骤不算点击，另记「自动放行」；</li>
 *   <li>没人点（60 秒到 / 窗口被切走）不是点击，另记「没人点」。</li>
 * </ul>
 * 数据存在 state.json 的 perm_stats 里（程序重启不丢），只有次数和最后时间，不存命令原文。
 */
public final class PermStats {
    private PermStats() {}

    public static final String ALLOW = "allow", DENY = "deny", TIMEOUT = "timeout", AUTO = "auto";

    static final Pattern DOWNLOAD = Pattern.compile("(?i)\\b(invoke-webrequest|iwr|wget|curl(\\.exe)?|start-bitstransfer|downloadfile|downloadstring|invoke-restmethod|irm)\\b");
    static final Pattern INSTALL = Pattern.compile("(?i)(\\badb(\\.exe)?\\b[^;|&]*\\binstall(-multiple)?\\b|\\bmsiexec\\b|\\bwinget\\s+install\\b|\\bchoco\\s+install\\b|\\bscoop\\s+install\\b|add-appxpackage|\\bpip\\s+install\\b|\\bnpm\\s+(i|install)\\b|\\.exe\"?\\s+(/s|/silent|/verysilent|/quiet)\\b)");
    static final Pattern DELETE = Pattern.compile("(?i)\\b(remove-item|del|erase|rd|rmdir|rm)\\b");
    static final Pattern ADB = Pattern.compile("(?i)(^|[\\s;&|'\"\\\\/])adb(\\.exe)?\\b");
    static final Pattern START = Pattern.compile("(?i)\\b(start-process|saps|start)\\b");
    static final Pattern MUMU = Pattern.compile("(?i)mumumanager|mumuplayer|nemu");

    /** 事件类型（给人看的中文）。不出现内部能力名。 */
    public static String eventType(String from, String cmd, Map<String, Object> a) {
        String base = baseType(cmd, a);
        boolean remote = from != null && !from.isEmpty() && !ChatAgent.FROM.equals(from);
        return remote ? "手机请求 · " + base : base;
    }

    static String baseType(String cmd, Map<String, Object> a) {
        String c = cmd == null ? "" : cmd;
        switch (c) {
            case "sys_exec": {
                String s = a == null || a.get("command") == null ? "" : String.valueOf(a.get("command"));
                if (INSTALL.matcher(s).find()) return "安装程序 / App";
                if (DOWNLOAD.matcher(s).find()) return "下载文件 / 联网";
                if (DELETE.matcher(s).find()) return "删除文件";
                if (MUMU.matcher(s).find()) return "操作模拟器";
                if (ADB.matcher(s).find()) return "操作安卓设备（adb）";
                if (START.matcher(s).find()) return "启动程序";
                String first = s.trim().split("[\\s;|&(]+", 2)[0].replaceAll("[\"']", "");
                first = first.replaceAll(".*[\\\\/]", "");
                if (first.isEmpty()) return "执行命令";
                return "执行命令（" + (first.length() > 24 ? first.substring(0, 24) : first) + "）";
            }
            case "file_write": return "写文件";
            case "open_url": return "打开网址";
            case "open_app": return "打开程序";
            case "open_target": return "打开程序 / 网站";
            case "media_get": return "下载视频 / 音频";
            case "uninstall_app": return "卸载程序";
            case "shortcut_run": return "运行快捷指令";
            case "script_run": return "运行脚本";
            case "shutdown": return "关机";
            case "sleep": return "睡眠";
            case "lock": return "锁屏";
            case "volume": return "调音量";
            case "clipboard_set": return "写剪贴板";
            case "android_ui": return "点安卓界面";
            case "screen_look": return "截屏";
            default: return "其他操作";
        }
    }

    /** 确认卡的结果（PcGuard 常量）→ 计数字段；不计数返回 null。 */
    public static String field(int r) {
        if (r == PcGuard.ALLOW || r == PcGuard.ALWAYS || r == PcGuard.REMEMBER || r == PcGuard.TASK_ALLOW || r == PcGuard.SESSION_ALLOW) return ALLOW;
        if (r == PcGuard.DENY) return DENY;
        if (r == PcGuard.TIMEOUT) return TIMEOUT;
        return null;
    }

    /** 加一次（就地改 stats）。 */
    @SuppressWarnings("unchecked")
    public static void bump(Map<String, Object> stats, String type, String field, long now) {
        if (stats == null || type == null || field == null) return;
        Object o = stats.get(type);
        Map<String, Object> m = o instanceof Map ? (Map<String, Object>) o : new TreeMap<>();
        Object n = m.get(field);
        m.put(field, (n instanceof Number ? ((Number) n).longValue() : 0L) + 1L);
        m.put("last_at", now);
        stats.put(type, m);
        // 类型太多时丢掉最久没动的（防止无限长）
        while (stats.size() > MAX_TYPES) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            for (Map.Entry<String, Object> e : stats.entrySet()) {
                long at = e.getValue() instanceof Map && ((Map<?, ?>) e.getValue()).get("last_at") instanceof Number ? ((Number) ((Map<?, ?>) e.getValue()).get("last_at")).longValue() : 0L;
                if (at < t) { t = at; oldest = e.getKey(); }
            }
            if (oldest == null) break;
            stats.remove(oldest);
        }
    }

    static final int MAX_TYPES = 80;

    /** 给界面的表：每类一行，按最后时间倒序；附合计。 */
    public static List<Object> view(Map<String, Object> stats) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (stats != null) for (Map.Entry<String, Object> e : stats.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            Map<String, Object> r = new TreeMap<>();
            r.put("type", e.getKey());
            for (String f : new String[] {ALLOW, DENY, TIMEOUT, AUTO}) r.put(f, m.get(f) instanceof Number ? ((Number) m.get(f)).longValue() : 0L);
            r.put("last_at", m.get("last_at") instanceof Number ? ((Number) m.get("last_at")).longValue() : 0L);
            rows.add(r);
        }
        rows.sort((x, y) -> Long.compare((Long) y.get("last_at"), (Long) x.get("last_at")));
        return new ArrayList<Object>(rows);
    }

    /** 一行给人看的：「下载文件 / 联网：允许 3 次 / 拒绝 1 次」。 */
    public static String line(Map<String, Object> row) {
        StringBuilder b = new StringBuilder(String.valueOf(row.get("type"))).append("：允许 ").append(row.get(ALLOW)).append(" 次 / 拒绝 ").append(row.get(DENY)).append(" 次");
        long t = row.get(TIMEOUT) instanceof Number ? ((Number) row.get(TIMEOUT)).longValue() : 0L;
        long a = row.get(AUTO) instanceof Number ? ((Number) row.get(AUTO)).longValue() : 0L;
        if (t > 0) b.append("（没人点 ").append(t).append(" 次）");
        if (a > 0) b.append("（自动放行 ").append(a).append(" 次）");
        return b.toString();
    }

    /** 像审批 / 确认的通知（旧版本写进通知列表的那种）：启动时清掉，以后也不收。 */
    static final Pattern APPROVAL_NOTE = Pattern.compile("需要你确认|等你批准|批准这一步|请求.{0,12}确认|要你确认|确认框|(允许|拒绝)了一条|不用再问|权限[：:]|允许本次任务|这次运行都允许|自动放行|审批");

    public static boolean looksLikeApproval(String title, String text) {
        String s = (title == null ? "" : title) + "\n" + (text == null ? "" : text);
        return APPROVAL_NOTE.matcher(s).find();
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
