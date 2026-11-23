package com.onesay.desktop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [chat-agent-1118] 本机对话里模型想跑的一条 PowerShell 命令是不是「明显只读」（只查、不改）。
 *
 * <p>只读的直接执行（不弹确认）；认不准的一律按普通命令处理（照旧弹确认）——宁可多问。规则（全部满足才算只读）：
 * <ul>
 *   <li>可以用引号外的 {@code ;} 串几条，每一条都要单独是只读的；</li>
 *   <li>[approvals-1118] 只认三种花括号 / 圆括号：计算属性 {@code @{n='大小';e={$_.Length/1MB}}}、条件 / 排序用的简单脚本块
 *   {@code { $_.Name -like '*x*' }}（里面只许 {@code $_} 的属性、常量、算术和比较，[math]::Round 这类取整），
 *   以及括住一条只读管道再取属性 {@code (Get-Item x).VersionInfo.FileVersion}（不许接方法调用）；</li>
 *   <li>此外不含会拼接 / 重定向 / 动态执行 / 调 .NET 方法的写法：{@code & > < ` { } [ ] ( ) = % @ ::}、换行、{@code $(}、点号方法调用；</li>
 *   <li>变量只认 {@code $_ / $PSItem / $env:NAME / $HOME / $true / $false / $null}；</li>
 *   <li>按 {@code |} 切成几段，每段第一个词都是只读命令：只读表里的、或任何 {@code Get-*}（Get-Credential / Get-Clipboard / Get-Help 等除外），
 *   以及少数只读的程序写法（ipconfig [/all]、systeminfo、tasklist、winget list|search|show、git status|log|show|diff、程序 --version…）；</li>
 *   <li>整条命令里任何没加引号的「动词-名词」样子的词都必须是只读命令（防止藏在参数里的 Remove-Item / Invoke-*）；</li>
 *   <li>不碰 Win32_Product（查它会触发 MSI 自检 / 修复，不是纯查询）；</li>
 *   <li>任何一个词是常见的改动类别名（rm、del、ni、mkdir、kill、start、iwr、curl…）就不算只读。</li>
 * </ul>
 * 安全插件（提权 / 自授权 / 编码 / 断网）照旧先过，这里只决定「要不要弹确认」。
 * 参考：openai/codex rust-v0.80.0 的 Windows 只读命令表（Apache-2.0，只借思路：只认常量参数、拆管道逐段判定）。
 */
final class LocalReadOnly {
    private LocalReadOnly() {}

    /** 只读命令（小写）。只放查询类；会等输入、会改东西的一律不放。Get-* 另外按 {@link #GET_DENY} 判。 */
    static final Set<String> READ = Set.of(
            "get-childitem", "gci", "ls", "dir", "get-item", "gi", "get-itemproperty", "gp", "get-itempropertyvalue", "gpv",
            "get-content", "gc", "cat", "type", "test-path", "resolve-path", "rvpa", "split-path", "join-path",
            "get-process", "ps", "gps", "get-service", "gsv", "get-command", "gcm", "get-date", "get-location", "gl", "pwd",
            "get-computerinfo", "get-ciminstance", "gcim", "get-wmiobject", "gwmi", "get-package", "get-appxpackage", "get-filehash",
            "get-volume", "get-psdrive", "gdr", "get-netipaddress", "get-hotfix", "get-culture", "get-host", "get-uptime",
            "select-object", "select", "where-object", "where", "?", "sort-object", "sort", "format-table", "ft", "format-list", "fl",
            "format-wide", "fw", "format-custom", "fc", "measure-object", "measure", "out-string", "convertto-json", "convertto-csv",
            "convertto-html", "convertfrom-json", "convertfrom-csv", "convertto-xml", "select-string", "sls",
            "group-object", "group", "get-unique", "gu", "compare-object", "compare", "diff", "write-output", "echo",
            "where.exe", "whoami", "whoami.exe", "hostname", "hostname.exe",
            "systeminfo", "tasklist", "getmac", "driverquery", "netstat", "onesaygrp");

    /** Get-* 里不算只读的：要输入 / 读密码剪贴板 / 会联网开浏览器（Get-Help -Online）。 */
    static final Set<String> GET_DENY = Set.of("get-credential", "get-clipboard", "get-secret", "get-secretinfo", "get-storedcredential",
            "get-help", "get-pfxcertificate", "get-azaccesstoken", "get-msaltoken", "get-windowsupdate", "get-wuinstall", "get-wulist");

    /** 不在只读表里的 Get-* 带了这些参数就不算只读。 */
    static final Pattern GET_RISKY_PARAM = Pattern.compile("(?i)(?<![\\w-])-(install|download|accept\\w*|autoreboot|delete|remove|update|set\\w*|enable|disable|outfile|out|destination|online|force)\\b");

    /** 程序 + 只读的第二个词（null = 不带参数或只带 /all 这类）。 */
    static final Map<String, Set<String>> NATIVE_SUB = Map.of(
            "ipconfig", Set.of("/all"),
            "winget", Set.of("list", "search", "show", "--version", "-v"),
            "git", Set.of("status", "log", "show", "diff", "--version"),
            "adb", Set.of("devices", "version", "--version", "get-state", "shell"),
            "mumumanager", Set.of("info"));

    /** 「程序 --version」：只这一个参数时算只读。 */
    static final Set<String> VERSION_TOOLS = Set.of("java", "python", "python3", "py", "node", "npm", "dotnet", "ffmpeg", "yt-dlp", "scrcpy", "pip", "go", "cargo", "rustc", "gh", "code");

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

    static boolean isRead(String w) {
        if (w == null) return false;
        String x = w.toLowerCase(Locale.ROOT);
        if (READ.contains(x)) return true;
        return x.startsWith("get-") && x.length() > 4 && x.substring(4).matches("[a-z]+") && !GET_DENY.contains(x);
    }

    /** 明显只读返回 true；认不准返回 false（照旧弹确认）。 */
    static boolean ok(String command) {
        return ok(command, 0);
    }

    private static boolean ok(String command, int depth) {
        if (command == null || depth > 3) return false;
        String s = command.trim();
        if (s.isEmpty() || s.length() > 1500) return false;
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('`') >= 0) return false;
        if (literalsBlanked(s).contains("$(") || literalsBlanked(s).contains("@(")) return false;
        s = replaceHashes(s);
        if (s == null) return false;
        s = replaceBlocks(s);
        if (s == null) return false;
        s = replaceGroups(s, depth);
        if (s == null) return false;
        List<String> segs = ChatPolicy.splitTop(s, ';');
        for (String seg : segs) if (!okSegment(callOp(seg.trim()))) return false;
        return true;
    }

    /** 只认这些程序的 & '完整路径\程序.exe' 写法（换成程序名再判）。 */
    static final Set<String> CALL_TOOLS = Set.of("adb", "mumumanager", "git", "winget", "java", "python", "node", "ffmpeg", "yt-dlp", "scrcpy");

    /** [approvals-1118] & 'C:\…\adb.exe' devices → adb devices（只认上面几个程序；别的 & 照旧不算只读）。 */
    static String callOp(String seg) {
        Matcher m = Pattern.compile("^&\\s*(?:'([^'$`]+)'|\"([^\"$`]+)\"|([^\\s'\"$`;|&]+))(.*)$", Pattern.DOTALL).matcher(seg);
        if (!m.matches()) return seg;
        String path = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        String base = path.replace('/', '\\');
        base = base.substring(base.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) base = base.substring(0, base.length() - 4);
        if (!CALL_TOOLS.contains(base)) return seg;
        return base + m.group(4);
    }

    private static boolean okSegment(String s) {
        if (s.isEmpty() || s.length() > 1500) return false;
        if (FORBIDDEN.matcher(literalsBlanked(s)).find()) return false;
        Matcher v = VAR.matcher(s);
        while (v.find()) {
            if (inSingleQuotes(s, v.start())) continue; // 单引号里不展开
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
            if (!isRead(w)) {
                // 路径 / 文件名里带连字符的（onesay-test、Mu-Mu）：前后紧挨着路径符号或在引号里就不当命令
                if (inQuotes(s, st) || pathy(s, st, vn.end())) continue;
                return false;
            }
        }
        for (String seg : splitPipes(s)) {
            String t = seg.trim();
            if (t.isEmpty()) return false;
            List<String> w = ChatPolicy.words(t);
            if (w == null || w.isEmpty()) return false;
            String head = w.get(0).toLowerCase(Locale.ROOT);
            String bare = head.endsWith(".exe") ? head.substring(0, head.length() - 4) : head;
            if (NATIVE_SUB.containsKey(bare)) {
                Set<String> sub = NATIVE_SUB.get(bare);
                if (bare.equals("ipconfig")) {
                    for (int i = 1; i < w.size(); i++) if (!sub.contains(w.get(i).toLowerCase(Locale.ROOT))) return false;
                } else if (w.size() < 2 || !sub.contains(w.get(1).toLowerCase(Locale.ROOT))) return false;
                if (bare.equals("adb") && w.get(1).equalsIgnoreCase("shell") && !adbShellRead(w)) return false;
                if (bare.equals("git")) for (String x : w) if (x.toLowerCase(Locale.ROOT).startsWith("--output") || x.toLowerCase(Locale.ROOT).startsWith("--ext-diff")) return false;
                continue;
            }
            if (VERSION_TOOLS.contains(bare)) {
                if (w.size() == 2 && Set.of("--version", "-version", "-v", "-V", "version").contains(w.get(1))) continue;
                return false;
            }
            if (!isRead(head)) return false;
            if (!READ.contains(head) && GET_RISKY_PARAM.matcher(t).find()) return false; // 第三方模块里会改东西的 Get-*（Get-WindowsUpdate -Install …）
        }
        Matcher w = WORD.matcher(s);
        while (w.find()) {
            String x = w.group().toLowerCase(Locale.ROOT);
            if (WRITE_ALIASES.contains(x) && !inQuotes(s, w.start())) return false;
        }
        return true;
    }

    /** adb [-s 设备] shell 只认读：getprop、pm list packages、pm path、dumpsys package / window、wm size、settings get。 */
    static boolean adbShellRead(List<String> w) {
        StringBuilder b = new StringBuilder();
        for (int i = 2; i < w.size(); i++) b.append(i > 2 ? " " : "").append(w.get(i).toLowerCase(Locale.ROOT));
        String r = b.toString();
        return r.matches("getprop( [a-z0-9._]+)?|pm list packages( -[3fs])*( [a-z0-9._]+)?|pm path [a-z0-9._]+|dumpsys (package [a-z0-9._]+|window( windows| displays)?|activity activities)|wm size|settings get (system|secure|global) [a-z0-9_.]+");
    }

    // ------------------------------------------------------------------ 认得出的花括号 / 圆括号 → 占位词

    /** 引号外从 i 开始（s[i] 是 open）找配对的 close；中间有引号照跳；嵌套同种括号也算。找不到返回 -1。 */
    static int match(String s, int i, char open, char close) {
        int d = 0;
        char q = 0;
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == open) d++;
            else if (c == close && --d == 0) return k;
        }
        return -1;
    }

    private static final Set<String> HASH_KEYS = Set.of("n", "name", "l", "label", "e", "expression", "w", "width", "a", "alignment",
            "formatstring", "ascending", "descending");

    /** @{n='x';e={...}} 计算属性 → 占位；不是这种形状的 @{ → null（不算只读）。 */
    static String replaceHashes(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) return null;
                out.append(s, i, j + 1);
                i = j + 1;
                continue;
            }
            if (c == '@' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                int end = match(s, i + 1, '{', '}');
                if (end < 0) return null;
                if (!hashOk(s.substring(i + 2, end))) return null;
                out.append(" OneSayProp ");
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static boolean hashOk(String body) {
        for (String entry : ChatPolicy.splitTop(blockAware(body), ';')) {
            String e = unBlockAware(entry).trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            if (eq <= 0) return false;
            String k = e.substring(0, eq).trim().replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
            String v = e.substring(eq + 1).trim();
            if (!HASH_KEYS.contains(k)) return false;
            if (v.startsWith("{")) {
                int end = match(v, 0, '{', '}');
                if (end != v.length() - 1 || !safeExpr(v.substring(1, end))) return false;
            } else if (!(v.matches("'[^']*'") || v.matches("\"[^\"$`]*\"") || v.matches("\\d+") || v.matches("(?i)\\$(true|false)") || v.matches("[A-Za-z]+"))) return false;
        }
        return true;
    }

    /** 哈希里脚本块中的 ; 不切：先把块里的 ; 换掉。 */
    private static String blockAware(String s) {
        StringBuilder b = new StringBuilder(s);
        int d = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == '{') d++;
            else if (c == '}') d--;
            else if (c == ';' && d > 0) b.setCharAt(i, '\u0001');
        }
        return b.toString();
    }

    private static String unBlockAware(String s) { return s.replace('\u0001', ';'); }

    /** { 简单表达式 } → 占位；里面不是简单表达式 → null。 */
    static String replaceBlocks(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) return null;
                out.append(s, i, j + 1);
                i = j + 1;
                continue;
            }
            if (c == '{') {
                int end = match(s, i, '{', '}');
                if (end < 0 || !safeExpr(s.substring(i + 1, end))) return null;
                out.append(" OneSayBlk ");
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** ( 只读管道 )[.属性…] → 占位；后面接方法调用、或里面不是只读 → null。$( 和 @( 在前面已经拒了。 */
    static String replaceGroups(String s, int depth) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) return null;
                out.append(s, i, j + 1);
                i = j + 1;
                continue;
            }
            if (c == '(') {
                int end = match(s, i, '(', ')');
                if (end < 0 || !ok(s.substring(i + 1, end), depth + 1)) return null;
                int k = end + 1;
                Matcher m = Pattern.compile("\\G(\\.[A-Za-z_]\\w*)*").matcher(s);
                if (m.find(k)) k = m.end();
                int n = k;
                while (n < s.length() && s.charAt(n) == ' ') n++;
                if (n < s.length() && (s.charAt(n) == '(' || s.charAt(n) == '[' || s.charAt(n) == '.')) return null; // 方法调用 / 下标
                out.append(" OneSayGrp ");
                i = k;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final Pattern EXPR_TOKEN = Pattern.compile("(?i)\\G(?:\\s+"
            + "|\\$(?:_|psitem)(?:\\.[A-Za-z_]\\w*)*(?!\\s*\\()(?![\\w.(\\[])"
            + "|\\$(?:true|false|null)\\b"
            + "|\\d+(?:\\.\\d+)?(?:kb|mb|gb|tb|pb)?\\b"
            + "|-(?:c|i)?(?:eq|ne|gt|lt|ge|le|like|notlike|match|notmatch|contains|notcontains|in|notin|replace|split|join|and|or|not|xor|is|isnot|f)\\b"
            + "|\\[(?:math)\\]::(?:round|floor|ceiling|abs|max|min|truncate)\\s*\\("
            + "|\\[(?:int|long|double|decimal|string|datetime|timespan|bool)\\](?!::)"
            + "|[+\\-*/%,!()]"
            + "|\\u0002+)");

    /** 脚本块里的简单表达式：只许 $_ 的属性、常量、算术、比较、[math]::Round 这类取整。 */
    static boolean safeExpr(String body) {
        if (body == null) return false;
        String b = body;
        // 字面量换成 \u0002（单引号原样；双引号里不能有 $ 和反引号）
        StringBuilder x = new StringBuilder();
        int i = 0;
        while (i < b.length()) {
            char c = b.charAt(i);
            if (c == '\'' || c == '"') {
                int j = b.indexOf(c, i + 1);
                if (j < 0) return false;
                String inner = b.substring(i + 1, j);
                if (c == '"' && (inner.indexOf('$') >= 0 || inner.indexOf('`') >= 0)) return false;
                x.append('\u0002');
                i = j + 1;
                continue;
            }
            x.append(c);
            i++;
        }
        String e = x.toString();
        if (e.trim().isEmpty() || e.length() > 400) return false;
        int depth = 0;
        for (char c : e.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return false;
        }
        if (depth != 0) return false;
        Matcher m = EXPR_TOKEN.matcher(e);
        int pos = 0;
        while (pos < e.length()) {
            if (!m.find(pos) || m.start() != pos || m.end() == pos) return false;
            pos = m.end();
        }
        return true;
    }

    // ------------------------------------------------------------------ 引号

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
        return ChatPolicy.splitTop(s, '|');
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

    static boolean inSingleQuotes(String s, int pos) {
        boolean sq = false, dq = false;
        for (int i = 0; i < pos && i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
        }
        return sq;
    }

    static boolean pathy(String s, int st, int en) {
        char b = st > 0 ? s.charAt(st - 1) : ' ', a = en < s.length() ? s.charAt(en) : ' ';
        return b == '\\' || b == '/' || b == ':' || b == '.' || a == '\\' || a == '/' || a == '.' || a == '*' || b == '*';
    }
}
