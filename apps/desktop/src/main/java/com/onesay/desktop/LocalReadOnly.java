package com.onesay.desktop;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [chat-agent-1118] 本机对话里模型想跑的一条 PowerShell 命令是不是「明显只读」（只查、不改）。
 *
 * <p>只读的直接执行（不弹确认）；认不准的一律按普通命令处理（照旧弹确认）——宁可多问。规则（全部满足才算只读）：
 * <ul>
 *   <li>不含会拼接 / 重定向 / 动态执行 / 调 .NET 方法的写法：{@code ; & > < ` { } [ ] ( ) = % @ ::}、换行、{@code $(}、点号方法调用；</li>
 *   <li>变量只认 {@code $_ / $PSItem / $env:NAME / $HOME / $true / $false / $null}；</li>
 *   <li>按 {@code |} 切成几段，每段第一个词都在只读命令表里（Get-ChildItem、Get-ItemProperty、Select-Object、where.exe…）；</li>
 *   <li>整条命令里任何没加引号的「动词-名词」样子的词都必须在只读表里（防止藏在参数里的 Remove-Item / Invoke-*；引号里的只是文字，不会被执行）；</li>
 *   <li>不碰 Win32_Product（查它会触发 MSI 自检 / 修复，不是纯查询）；</li>
 *   <li>任何一个词是常见的改动类别名（rm、del、ni、mkdir、kill、start、iwr、curl…）就不算只读。</li>
 * </ul>
 * 安全插件（提权 / 自授权 / 编码 / 断网）照旧先过，这里只决定「要不要弹确认」。
 */
final class LocalReadOnly {
    private LocalReadOnly() {}

    /** 只读命令（小写）。只放查询类；会等输入、会改东西的一律不放。 */
    static final Set<String> READ = Set.of(
            "get-childitem", "gci", "ls", "dir", "get-item", "gi", "get-itemproperty", "gp", "get-itempropertyvalue", "gpv",
            "get-content", "gc", "cat", "type", "test-path", "resolve-path", "rvpa", "split-path", "join-path",
            "get-process", "ps", "gps", "get-service", "gsv", "get-command", "gcm", "get-date", "get-location", "gl", "pwd",
            "get-computerinfo", "get-ciminstance", "gcim", "get-wmiobject", "gwmi", "get-package", "get-appxpackage", "get-filehash",
            "get-volume", "get-psdrive", "gdr", "get-netipaddress", "get-hotfix", "get-culture", "get-host", "get-uptime",
            "select-object", "select", "where-object", "where", "?", "sort-object", "sort", "format-table", "ft", "format-list", "fl",
            "format-wide", "fw", "measure-object", "measure", "out-string", "convertto-json", "convertto-csv", "select-string", "sls",
            "group-object", "group", "get-unique", "gu", "where.exe", "whoami", "whoami.exe", "hostname", "hostname.exe");

    /** 常见改动类别名 / 程序：出现就不算只读。 */
    static final Set<String> WRITE_ALIASES = Set.of(
            "rm", "del", "erase", "rd", "rmdir", "ri", "ni", "md", "mkdir", "mv", "move", "mi", "cp", "copy", "cpi", "ren", "rni",
            "kill", "spps", "saps", "start", "ii", "iwr", "irm", "curl", "wget", "sc", "set", "sv", "si", "clc", "cli", "clp",
            "iex", "icm", "r", "ihy", "sajb", "epcsv", "tee", "out-file", "add-content", "ac", "cmd", "powershell", "pwsh",
            "reg", "schtasks", "taskkill", "net", "netsh", "sc.exe", "shutdown", "format", "diskpart", "bcdedit", "msiexec");

    private static final Pattern FORBIDDEN = Pattern.compile("[;&><`{}\\[\\]()=%@\\r\\n]|::|\\$\\(|\\.\\s*[A-Za-z_]\\w*\\s*\\(");
    private static final Pattern VAR = Pattern.compile("\\$(\\{?[A-Za-z_][A-Za-z0-9_:]*\\}?)");
    private static final Pattern VERB_NOUN = Pattern.compile("(?<![\\w-])([A-Za-z]+-[A-Za-z]+)(?![\\w-])");
    private static final Pattern WORD = Pattern.compile("[^\\s'\"|,]+");

    /**
     * Where-Object 后面的简单条件块：{ $_.属性 -like / -match / -eq … '文字' 或数字 [-and / -or 再来一个] }。
     * 只认这种形状（只比较属性和常量，不调用任何东西），先换成占位词再做后面的检查。
     */
    private static final String COND = "\\$_\\.[A-Za-z_]\\w*\\s+-[ci]?(?:like|notlike|match|notmatch|eq|ne|gt|lt|ge|le|contains|notcontains)\\s+(?:'[^'{}$`]*'|\"[^\"{}$`]*\"|\\d+)";
    private static final Pattern WHERE_BLOCK = Pattern.compile(
            "(?i)((?:^|\\|)\\s*(?:where-object|where|\\?)\\s*)\\{\\s*" + COND + "(?:\\s+-(?:and|or)\\s+" + COND + ")*\\s*\\}");

    /** 明显只读返回 true；认不准返回 false（照旧弹确认）。 */
    static boolean ok(String command) {
        if (command == null) return false;
        String s = command.trim();
        if (s.isEmpty() || s.length() > 600) return false;
        s = WHERE_BLOCK.matcher(s).replaceAll("$1OneSayCond");
        if (FORBIDDEN.matcher(literalsBlanked(s)).find()) return false;
        Matcher v = VAR.matcher(s);
        while (v.find()) {
            String n = v.group(1).replace("{", "").replace("}", "").toLowerCase(Locale.ROOT);
            if (!(n.equals("_") || n.equals("psitem") || n.equals("home") || n.equals("true") || n.equals("false") || n.equals("null")
                    || n.matches("env:[a-z_][a-z0-9_]*"))) return false;
        }
        if (s.toLowerCase(Locale.ROOT).contains("win32_product")) return false;
        // 引号里的只是文字（不允许 $( ) 子表达式，所以不会被执行）；没加引号的「动词-名词」必须是只读命令
        Matcher vn = VERB_NOUN.matcher(s);
        while (vn.find()) {
            String w = vn.group(1).toLowerCase(Locale.ROOT);
            int st = vn.start();
            boolean param = st > 0 && s.charAt(st - 1) == '-';
            if (param) continue;
            if (!READ.contains(w)) {
                // 路径 / 文件名里带连字符的（onesay-test、Mu-Mu）：前后紧挨着路径符号或在引号里就不当命令
                if (inQuotes(s, st) || pathy(s, st, vn.end())) continue;
                return false;
            }
        }
        for (String seg : splitPipes(s)) {
            String t = seg.trim();
            if (t.isEmpty()) return false;
            Matcher w = WORD.matcher(t);
            if (!w.find()) return false;
            String head = w.group().toLowerCase(Locale.ROOT);
            if (!READ.contains(head)) return false;
        }
        Matcher w = WORD.matcher(s);
        while (w.find()) {
            String x = w.group().toLowerCase(Locale.ROOT);
            if (WRITE_ALIASES.contains(x) && !inQuotes(s, w.start())) return false;
        }
        return true;
    }

    /**
     * 把纯文字的引号内容换成空格（'C:\Program Files (x86)' 里的括号只是路径）：单引号里 PowerShell 不做任何展开；
     * 双引号里只在没有 $ 和 ` 时才算纯文字，否则原样留着（$( ) 子表达式照旧被拦）。没配对的引号原样留着。
     */
    static String literalsBlanked(String s) {
        StringBuilder b = new StringBuilder(s);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) break;
                String inner = s.substring(i + 1, j);
                if (c == '\'' || (inner.indexOf('$') < 0 && inner.indexOf('`') < 0))
                    for (int k = i + 1; k < j; k++) b.setCharAt(k, ' ');
                i = j + 1;
                continue;
            }
            i++;
        }
        return b.toString();
    }

    /** 按引号外的 | 切段（'MuMu|Nemu' 里的 | 不切）。 */
    static java.util.List<String> splitPipes(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false;
        for (char c : s.toCharArray()) {
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            if (c == '|' && !sq && !dq) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static boolean inQuotes(String s, int pos) {
        boolean sq = false, dq = false;
        for (int i = 0; i < pos && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
        }
        return sq || dq;
    }

    static boolean pathy(String s, int st, int en) {
        char b = st > 0 ? s.charAt(st - 1) : ' ', a = en < s.length() ? s.charAt(en) : ' ';
        return b == '\\' || b == '/' || b == ':' || b == '.' || a == '\\' || a == '/' || a == '.' || a == '*' || b == '*';
    }
}
