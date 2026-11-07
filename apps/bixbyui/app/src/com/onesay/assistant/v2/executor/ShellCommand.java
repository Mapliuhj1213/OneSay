package com.onesay.assistant.v2.executor;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shell 命令模板填充器（P0-8，纯 Java，JVM 单测覆盖）。
 *
 * <h3>为什么重写</h3>
 * 旧版 {@code UnifiedExecutor.fillTemplate + escapeShellValue}：
 * <ol>
 *   <li>先<b>删掉</b> {@code & ; | $ " ` < > \} 再做单引号转义 —— 带 {@code &} 的网址被拆坏、正文里的标点丢失；</li>
 *   <li>但大部分模板里槽位<b>没有加引号</b>（{@code -d {url}}、{@code ?wd={keyword}}），带空格的关键词被拆成多个参数，
 *       而 {@code '\''} 只在单引号内有效 —— 裸槽位里出现 {@code '} 会让整条命令语法错误；</li>
 *   <li>链式 {@code replace}：值里若含 {@code {other}} 会被二次替换（模板注入）；</li>
 *   <li>URL 查询参数不做编码。</li>
 * </ol>
 *
 * <h3>新规则：按占位符所处的 shell 上下文转义（一次扫描，单遍替换）</h3>
 * <ul>
 *   <li><b>裸上下文</b>（不在引号里）：值只含安全字符 {@code [A-Za-z0-9_./:%@,+=-]} 时原样输出（与旧版字节一致，
 *       如 {@code --set 50}）；否则整体包成单引号 {@code 'v'}，内部 {@code '} → {@code '\''}；空值 → {@code ''}；</li>
 *   <li><b>单引号内</b>（模板写了 {@code '{content}'}）：只把 {@code '} 转成 {@code '\''}，其余字符都是字面量；</li>
 *   <li><b>双引号内</b>：对 {@code \ " $ `} 加反斜杠；</li>
 *   <li><b>URL 查询值</b>（同一个 shell 单词里已出现 {@code ://} 或 scheme 冒号与 {@code ?}，且占位符紧跟 {@code =}）：
 *       先 UTF-8 百分号编码（空格 → {@code %20}），再按上下文转义；</li>
 *   <li>所有值先去掉 {@code \r \n \0}（换行替换为空格）：root 守护按文件读取命令，换行可能被当成第二条命令。</li>
 * </ul>
 *
 * <h3>占位符语法</h3>
 * {@code {name}}、{@code {name=默认值}}（无该参数时用默认值）、{@code {name:mod}}，mod 取值：
 * {@code q} 强制单引号包裹，{@code u} 强制 URL 编码，{@code i} 必须是整数（否则抛 {@link IllegalArgumentException}），
 * {@code raw} 不转义（仅限内部可信值，能力模板<b>不应</b>使用）。
 * 没有对应值也没有默认值的占位符原样保留（与旧版行为一致）。
 *
 * <h3>选项注入（D13，2026-10-22）</h3>
 * 值留在参数里、却被目标命令当成选项（{@code settings put system k --user}）。{@link #slots} 把每个占位符分成
 * 位置参数 / 选项参数 / 嵌在单词里三类；<b>位置参数</b>的值以 {@code -} 开头一律拒绝（抛 {@link IllegalArgumentException}，
 * 执行器转成 SCHEMA_INVALID）。没有改成加 {@code --}：表里的位置参数只出现在 {@code settings put}，而 Android 8 的
 * SettingsService 不认 {@code --}（会当成键或值存进去，且任意位置的 {@code --user} 都按选项处理）。
 * am / pm / cmd 这类基于 ShellCommand 的工具认 {@code --}，但表里没有它们的位置参数槽位。逐模板核对表见 OptionInjectionTest。
 *
 * <p>假设：root 守护以 POSIX sh 语义执行请求文件中的整行命令（{@code sh -c}）。若守护实现不同 的真机核验项。
 */
public final class ShellCommand {

    private ShellCommand() {}

    private enum Ctx { NONE, SINGLE, DOUBLE }

    // ------------------------------------------------------------------ 参数 → 槽位值

    /**
     * 把执行参数整理成槽位值表（含 {component}/{pkg} 与 {seconds}/{ms} 派生值）。
     * {@code seconds} 按整数秒处理，并派生毫秒 {@code ms}（与旧版一致）。
     */
    public static Map<String, String> slotValues(JSONObject args, String component, String pkg) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (args != null) {
            Iterator<?> it = args.keys();
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                Object v = args.opt(k);
                if (v == null || v == JSONObject.NULL) continue;
                m.put(k, String.valueOf(v));
            }
            if (args.has("seconds")) {
                long sec = args.optLong("seconds");
                m.put("seconds", String.valueOf(sec));
                m.put("ms", String.valueOf(sec * 1000));
            }
        }
        if (component != null) m.put("component", component);
        if (pkg != null) m.put("pkg", pkg);
        return m;
    }

    // ------------------------------------------------------------------ 填充

    /** 填充<b>shell 命令</b>模板（按上下文转义）。{@code template == null} 返回 null。 */
    public static String fill(String template, Map<String, String> values) {
        return render(template, values, true);
    }

    /**
     * 填充<b>非 shell</b>文本（如回读期望值 expect：它是拿来和输出做 contains 比较的，不能带引号）。
     * 值原样代入（仍去掉换行），不做任何 shell 转义。
     */
    public static String fillPlain(String template, Map<String, String> values) {
        return render(template, values, false);
    }

    private static String render(String t, Map<String, String> values, boolean shell) {
        if (t == null) return null;
        if (values == null) values = new LinkedHashMap<String, String>();
        StringBuilder out = new StringBuilder(t.length() + 32);
        Ctx ctx = Ctx.NONE;
        int wordStart = 0;          // 当前 shell 单词在 out 中的起点（用于 URL 上下文判断）
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c == '{') {
                int close = t.indexOf('}', i + 1);
                Placeholder ph = close > i ? Placeholder.parse(t.substring(i + 1, close)) : null;
                if (ph != null) {
                    String raw = values.containsKey(ph.name) ? values.get(ph.name) : ph.defaultValue;
                    if (raw != null) {
                        // D13：位置参数不接受以 - 开头的值（目标命令会当成选项；表里唯一的位置参数命令 settings 不认 --，见 slots()）
                        if (shell && sanitize(raw).trim().startsWith("-") && kindAt(t, i) == SlotKind.POSITIONAL) {
                            throw new IllegalArgumentException("参数 " + ph.name + " 是位置参数，值不能以 - 开头（会被当成选项）: " + raw);
                        }
                        out.append(shell ? encode(raw, ph, ctx, out, wordStart) : sanitize(raw));
                        i = close;
                        continue;
                    }
                }
                out.append(c);
                continue;
            }
            if (shell) {
                switch (ctx) {
                    case NONE:
                        if (c == '\\' && i + 1 < n) {
                            out.append(c).append(t.charAt(++i));
                            continue;
                        }
                        if (c == '\'') ctx = Ctx.SINGLE;
                        else if (c == '"') ctx = Ctx.DOUBLE;
                        else if (c == ' ' || c == '\t') {
                            out.append(c);
                            wordStart = out.length();
                            continue;
                        }
                        break;
                    case SINGLE:
                        if (c == '\'') ctx = Ctx.NONE;
                        break;
                    case DOUBLE:
                        if (c == '\\' && i + 1 < n) {
                            out.append(c).append(t.charAt(++i));
                            continue;
                        }
                        if (c == '"') ctx = Ctx.NONE;
                        break;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String encode(String raw, Placeholder ph, Ctx ctx, StringBuilder out, int wordStart) {
        String v = sanitize(raw);
        if ("i".equals(ph.mod)) {
            String s = v.trim();
            try {
                Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("参数 " + ph.name + " 必须是整数: " + raw);
            }
            return s;   // 纯数字，任何上下文都安全
        }
        if ("raw".equals(ph.mod)) return v;
        boolean url = "u".equals(ph.mod) || isUrlQueryValue(out, wordStart);
        if (url) v = urlEncode(v);
        switch (ctx) {
            case SINGLE:
                return escapeInSingleQuotes(v);
            case DOUBLE:
                return escapeInDoubleQuotes(v);
            default:
                if ("q".equals(ph.mod)) return quote(v);
                return isSafeBare(v) ? v : quote(v);
        }
    }

    /**
     * 占位符是否处在 URL 查询值的位置：当前单词里有 scheme 冒号（{@code https://}、{@code market://}）与 {@code ?}，
     * 且占位符前一个字符是 {@code =}。路径段（{@code samsungapps://SearchResult/{keyword}}）不编码，保持旧行为。
     */
    static boolean isUrlQueryValue(StringBuilder out, int wordStart) {
        int len = out.length();
        if (len == 0 || out.charAt(len - 1) != '=') return false;
        String word = out.substring(Math.max(0, Math.min(wordStart, len)));
        int q = word.indexOf('?');
        int colon = word.indexOf(':');
        return q > 0 && colon > 0 && colon < q;
    }

    // ------------------------------------------------------------------ D13：槽位种类（选项注入）

    /**
     * 占位符在目标命令 argv 里的角色（D13，2026-10-22）。
     * <ul>
     *   <li>{@link #POSITIONAL}：独占一个参数，且前面不是「带参数的选项」—— 目标命令会先看它是不是选项
     *       （以 {@code -} 开头就可能被当成选项）；</li>
     *   <li>{@link #OPTION_ARG}：独占一个参数，前面是带参数的选项（{@code -d {url}}、{@code --es key '{v}'}）——
     *       目标命令用 getNextArgRequired() 原样取走，不会当成选项；</li>
     *   <li>{@link #EMBEDDED}：和固定前缀在同一个参数里（{@code tel:{number}}、{@code ?q={keyword}}）—— 到不了参数开头。</li>
     * </ul>
     */
    public enum SlotKind { POSITIONAL, OPTION_ARG, EMBEDDED }

    /** 模板里的一个占位符及其种类。 */
    public static final class Slot {
        public final String name;
        public final SlotKind kind;

        Slot(String name, SlotKind kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override public String toString() {
            return name + ":" + kind;
        }
    }

    /**
     * 带一个参数的选项（am 的 Intent.parseCommandArgs / am start、media volume、content、settings 的并集，按 AOSP 8.1 源码）。
     * 不在这两张表里的 {@code -} 开头单词按「无参开关」处理 —— 它后面的槽位算位置参数（保守）。
     */
    static final java.util.Set<String> OPT_ONE_ARG = new java.util.HashSet<String>(java.util.Arrays.asList(
            "-a", "-d", "-t", "-c", "-n", "-p", "-f", "--esn", "-P", "-R", "--start-profiler", "--sampling",
            "--user", "--receiver-permission", "--stack", "--display", "--stream", "--set", "--adj",
            "--uri", "--projection", "--where", "--sort", "--bind", "--method", "--arg", "--extra"));
    /** 带两个参数（键 + 值）的选项：am 的 extras。 */
    static final java.util.Set<String> OPT_TWO_ARGS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "-e", "--es", "--ei", "--el", "--ef", "--eu", "--ecn", "--eia", "--eial", "--ela", "--elal",
            "--efa", "--efal", "--esa", "--esal", "--ez"));
    /** 这些 shell 关键字之后是一条新命令的开头。 */
    private static final java.util.Set<String> CMD_START = new java.util.HashSet<String>(java.util.Arrays.asList(
            "if", "then", "else", "elif", "do", "while", "until", "!", "{", "("));

    /** 列出模板里每个占位符的种类（按出现顺序；只认合法占位符，与 {@link #fill} 同一套解析）。 */
    public static java.util.List<Slot> slots(String template) {
        java.util.List<Slot> list = new java.util.ArrayList<Slot>();
        if (template == null) return list;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) != '{') continue;
            int close = template.indexOf('}', i + 1);
            Placeholder ph = close > i ? Placeholder.parse(template.substring(i + 1, close)) : null;
            if (ph == null) continue;
            list.add(new Slot(ph.name, kindAt(template, i)));
            i = close;
        }
        return list;
    }

    /** 模板第 pos 个字符（一个占位符的 '{'）处的槽位种类：把前缀按 POSIX sh 规则切词（只为判断位置，不执行）。 */
    static SlotKind kindAt(String t, int pos) {
        java.util.List<String> words = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;   // 当前单词已开始（含只出现了开引号的情况）
        Ctx ctx = Ctx.NONE;
        for (int i = 0; i < pos; i++) {
            char c = t.charAt(i);
            if (ctx == Ctx.SINGLE) {
                if (c == '\'') ctx = Ctx.NONE; else cur.append(c);
                continue;
            }
            if (ctx == Ctx.DOUBLE) {
                if (c == '\\' && i + 1 < pos) { cur.append(t.charAt(++i)); continue; }
                if (c == '"') ctx = Ctx.NONE; else cur.append(c);
                continue;
            }
            if (c == '\\' && i + 1 < pos) { cur.append(t.charAt(++i)); inWord = true; continue; }
            if (c == '\'') { ctx = Ctx.SINGLE; inWord = true; continue; }
            if (c == '"') { ctx = Ctx.DOUBLE; inWord = true; continue; }
            boolean sep = c == ';' || c == '|' || c == '\n' || (c == '&' && !(i > 0 && t.charAt(i - 1) == '>'));
            if (c == ' ' || c == '\t' || sep) {
                if (inWord) {
                    String w = cur.toString();
                    if (CMD_START.contains(w) && words.isEmpty()) {
                        // 关键字后面才是命令本身
                    } else {
                        words.add(w);
                    }
                    if (CMD_START.contains(w)) words.clear();
                }
                cur.setLength(0);
                inWord = false;
                if (sep) words.clear();
                continue;
            }
            cur.append(c);
            inWord = true;
        }
        if (cur.length() > 0) return SlotKind.EMBEDDED;   // 同一个参数里前面已有内容（开引号不算内容）
        int n = words.size();
        if (n >= 1 && OPT_ONE_ARG.contains(words.get(n - 1))) return SlotKind.OPTION_ARG;
        if (n >= 2 && OPT_TWO_ARGS.contains(words.get(n - 2))) return SlotKind.OPTION_ARG;
        return SlotKind.POSITIONAL;
    }

    // ------------------------------------------------------------------ 转义原语（public，供其它执行后端复用）

    /** 去掉 NUL，回车/换行替换为空格。 */
    public static String sanitize(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\0') continue;
            sb.append(c == '\r' || c == '\n' ? ' ' : c);
        }
        return sb.toString();
    }

    /** POSIX 单引号整体引用：{@code it's} → {@code 'it'\''s'}；空串 → {@code ''}。 */
    public static String quote(String v) {
        return "'" + escapeInSingleQuotes(sanitize(v)) + "'";
    }

    /** 已处在单引号内时的转义：{@code '} → {@code '\''}（先闭合、转义引号、再打开）。 */
    public static String escapeInSingleQuotes(String v) {
        return v == null ? "" : v.replace("'", "'\\''");
    }

    /** 已处在双引号内时的转义：{@code \ " $ `} 加反斜杠。 */
    public static String escapeInDoubleQuotes(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' || c == '"' || c == '$' || c == '`') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** UTF-8 百分号编码，空格编码为 {@code %20}（而非 {@code +}）。 */
    public static String urlEncode(String v) {
        if (v == null) return "";
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return v;
        }
    }

    /** 裸上下文中无需引号的值：非空且只含 {@code [A-Za-z0-9_./:%@,+=-]}。 */
    static boolean isSafeBare(String v) {
        if (v == null || v.length() == 0) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '/' || c == ':' || c == '%' || c == '@'
                    || c == ',' || c == '+' || c == '=' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 占位符解析

    private static final class Placeholder {
        final String name;
        final String mod;
        final String defaultValue;

        private Placeholder(String name, String mod, String defaultValue) {
            this.name = name;
            this.mod = mod;
            this.defaultValue = defaultValue;
        }

        /** 解析 "name" / "name:mod" / "name=default"；不合法返回 null（原样保留）。 */
        static Placeholder parse(String body) {
            if (body == null || body.length() == 0 || body.length() > 64) return null;
            String name = body;
            String mod = null;
            String def = null;
            int eq = body.indexOf('=');
            int colon = body.indexOf(':');
            if (eq > 0 && (colon < 0 || eq < colon)) {
                name = body.substring(0, eq);
                def = body.substring(eq + 1);
            } else if (colon > 0) {
                name = body.substring(0, colon);
                mod = body.substring(colon + 1);
                if (!("q".equals(mod) || "u".equals(mod) || "i".equals(mod) || "raw".equals(mod))) return null;
            }
            if (!isIdent(name)) return null;
            return new Placeholder(name, mod, def);
        }

        private static boolean isIdent(String s) {
            if (s.length() == 0) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (i > 0 && c >= '0' && c <= '9');
                if (!ok) return false;
            }
            return true;
        }
    }
}
