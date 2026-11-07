package com.onesay.assistant.v2.diag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [diag] 只读快照：把一组白名单键拼成<b>一条</b>只读 shell 命令（一次 root 往返），再把输出解析回 {@code keyId → 原始值}。
 *
 * <p>命令只由 {@link DiagKnowledge#KEYS} 里的字面量组成（键名经 {@code SAFE_NAME} 校验、探针命令是表内常量），
 * 不含任何外部输入；只有 {@code settings get}、{@code dumpsys … | grep}、{@code pm path}、{@code cmd netpolicy get} 这类读操作。
 * 输出格式：每项一行标记 {@code @@<keyId>}，后面跟它的输出。{@code settings get} 返回 {@code null} = 键不存在。
 */
public final class DiagSnapshot {

    private DiagSnapshot() {}

    public static final String MARK = "@@";

    /** 拼只读命令。不在白名单的 id 直接丢弃（白名单守卫）。 */
    public static String buildReadCommand(Collection<String> keyIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : keyIds) {
            DiagKnowledge.Key k = DiagKnowledge.key(id);
            if (k == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append("echo '").append(MARK).append(k.id).append("'; ");
            if (k.isProbe()) {
                sb.append("(").append(k.probeCmd).append(") 2>/dev/null");
            } else {
                sb.append("settings get ").append(k.ns).append(' ').append(k.key).append(" 2>/dev/null");
            }
        }
        if (sb.length() > 0) sb.append("; true");
        return sb.toString();
    }

    /**
     * 2026-10-25 exec 守护 v4：真正执行的形式——每个键一条单命令（{@code settings get ns key} 或探针的
     * 「只读命令 | 过滤器」），分段标记 {@code @@id} 改为本地 echo，读失败 / 被守护拒绝的那一项输出丢弃（= 原来的
     * {@code 2>/dev/null}，解析出来就是「未读到」）。v4 守护拒含 {@code ;} 的复合脚本（fix-root.md §3），
     * {@link #buildReadCommand} 保留作可读描述与旧单测。输出格式与 {@link #parse} 完全兼容。
     */
    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> buildReadSteps(Collection<String> keyIds) {
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> l = new java.util.ArrayList<com.onesay.assistant.v2.executor.ShellScript.Step>();
        for (String id : keyIds) {
            DiagKnowledge.Key k = DiagKnowledge.key(id);
            if (k == null) continue;
            l.add(com.onesay.assistant.v2.executor.ShellScript.echo(MARK + k.id));
            String c = k.isProbe() ? k.probeCmd : "settings get " + k.ns + " " + k.key;
            l.add(com.onesay.assistant.v2.executor.ShellScript.run(c).quiet());
        }
        return l;
    }

    /** 解析命令输出 → keyId → 原始值（null = 未读到 / 键不存在）。所有请求的 id 都会出现在结果里。 */
    public static Map<String, String> parse(Collection<String> keyIds, String output) {
        Map<String, StringBuilder> raw = new LinkedHashMap<String, StringBuilder>();
        String cur = null;
        if (output != null) {
            String[] lines = output.replace("\r", "").split("\n");
            for (String line : lines) {
                if (line.startsWith(MARK)) {
                    String id = line.substring(MARK.length()).trim();
                    cur = DiagKnowledge.isReadable(id) ? id : null;
                    if (cur != null) raw.put(cur, new StringBuilder());
                    continue;
                }
                if (cur != null) {
                    StringBuilder b = raw.get(cur);
                    if (b.length() > 0) b.append('\n');
                    b.append(line);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String id : keyIds) {
            DiagKnowledge.Key k = DiagKnowledge.key(id);
            if (k == null) continue;
            StringBuilder b = raw.get(id);
            out.put(id, b == null ? null : clean(k, b.toString()));
        }
        return out;
    }

    static String clean(DiagKnowledge.Key k, String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0 || "null".equals(t)) return null;
        if (k.isProbe()) {
            if (k.probeRegex == null) return t;
            Matcher m = Pattern.compile(k.probeRegex).matcher(t);
            return m.find() ? m.group(1) : null;
        }
        // settings get 只有一行；多行说明输出被污染（如报错），取第一行
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl).trim();
        return "null".equals(t) ? null : t;
    }

    // =====================================================================
    // 人话格式
    // =====================================================================

    /** 把原始值格式化成中文（null → 「未读到」）。 */
    public static String human(String keyId, String raw) {
        DiagKnowledge.Key k = DiagKnowledge.key(keyId);
        if (raw == null) return "未读到";
        if (k == null) return raw;
        Double n = DiagKnowledge.num(raw);
        switch (k.fmt) {
            case BOOL01:
                if ("1".equals(raw.trim())) return "开";
                if ("0".equals(raw.trim())) return "关";
                return raw;
            case MS:
                if (n == null) return raw;
                return duration(n.longValue());
            case BRIGHT:
                if (n == null) return raw;
                return n.intValue() + "/255（约 " + Math.round(n * 100 / 255) + "%）";
            case BMODE:
                return "1".equals(raw.trim()) ? "自动" : "0".equals(raw.trim()) ? "手动" : raw;
            case ZEN:
                if ("0".equals(raw)) return "关闭";
                if ("1".equals(raw)) return "仅优先通知";
                if ("2".equals(raw)) return "完全静音";
                if ("3".equals(raw)) return "仅闹钟";
                return raw;
            case RINGER:
                if ("0".equals(raw)) return "静音模式";
                if ("1".equals(raw)) return "振动模式";
                if ("2".equals(raw)) return "响铃模式";
                return raw;
            case LOCMODE:
                if ("0".equals(raw)) return "关闭";
                if ("1".equals(raw)) return "仅 GPS";
                if ("2".equals(raw)) return "省电（仅网络）";
                if ("3".equals(raw)) return "高精度";
                return raw;
            case VOL15:
                return n == null ? raw : n.intValue() + "/15";
            case FONT:
                if (n == null) return raw;
                String name = n < 0.95 ? "偏小" : n < 1.05 ? "标准" : n < 1.2 ? "大号" : n < 1.4 ? "超大" : "特大";
                return name + "（" + trimNum(raw) + "）";
            case WIFI_SLEEP:
                if ("2".equals(raw)) return "始终保持";
                if ("1".equals(raw)) return "仅充电时保持";
                if ("0".equals(raw)) return "休眠时断开";
                return raw;
            case TEMP10:
                return n == null ? raw : String.format(Locale.ROOT, "%.1f ℃", n / 10.0);
            case DBM:
                return n == null ? raw : n.intValue() + " dBm" + (n <= -80 ? "（很弱）" : n <= -70 ? "（偏弱）" : n <= -60 ? "（一般）" : "（好）");
            case PCT:
                return n == null ? raw : n.intValue() + "%";
            case INWL:
                return n == null ? raw : n > 0 ? "在白名单" : "不在白名单";
            case STAYON:
                return stayOn(n, raw);
            default:
                return raw;
        }
    }

    /** ★ 11-04 C：stay_on_while_plugged_in 位掩码 → 人话。 */
    static String stayOn(Double n, String raw) {
        if (n == null) return raw;
        int v = n.intValue();
        if (v == 0) return "关";
        StringBuilder sb = new StringBuilder();
        if ((v & 1) != 0) sb.append("交流电");
        if ((v & 2) != 0) sb.append(sb.length() > 0 ? " / " : "").append("USB");
        if ((v & 4) != 0) sb.append(sb.length() > 0 ? " / " : "").append("无线充电");
        return sb.length() == 0 ? raw : "开（" + sb + "充电时不熄屏）";
    }

    static String duration(long ms) {
        if (ms <= 0) return ms + " 毫秒";
        if (ms >= Integer.MAX_VALUE / 2) return "永不";
        if (ms < 60000) return (ms / 1000) + " 秒";
        if (ms % 60000 == 0) return (ms / 60000) + " 分钟";
        return String.format(Locale.ROOT, "%.1f 分钟", ms / 60000.0);
    }

    private static String trimNum(String raw) {
        Double n = DiagKnowledge.num(raw);
        if (n == null) return raw;
        String s = String.format(Locale.ROOT, "%.2f", n);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s + "0";
        return s;
    }

    /** 快照的日志摘要（只有键 id 与原始值，不含隐私）。 */
    public static String dump(Map<String, String> snap) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> e : snap.entrySet()) parts.add(e.getKey() + "=" + e.getValue());
        return parts.toString();
    }
}
