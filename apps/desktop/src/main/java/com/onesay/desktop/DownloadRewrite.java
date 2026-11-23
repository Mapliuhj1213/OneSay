package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [preview-1120] 模型用命令下载文件（Invoke-WebRequest / iwr / curl / wget / WebClient.DownloadFile / Start-BitsTransfer）时，
 * 改走「下载文件」工具：任务卡上才有进度条（百分比、速度、剩余时间）、能取消、下完给 SHA-256。
 *
 * <ul>
 *   <li>{@link #parse}：整条命令就是「把 一个网址 存到 一个路径」→ 认出 url / path，直接改写成下载工具（判定照旧按等价的 Invoke-WebRequest 走）。</li>
 *   <li>{@link #mentions}：命令里有下载，但混着别的语句、用了变量、或者没写存到哪 → 认不全，不猜；先不执行，回一句提示让模型改用下载工具
 *       （最多提示两次，第三次照原命令执行，免得卡死）。</li>
 * </ul>
 * 只看命令文本，不执行任何东西。
 */
public final class DownloadRewrite {
    private DownloadRewrite() { }

    /** 认出的下载：url、存到哪（原样，可能是相对路径，交给下载工具按工作区解析）、超时秒（没写为 null）。 */
    public static final class Dl {
        public final String url, path;
        public final Long timeoutSec;
        Dl(String url, String path, Long timeoutSec) { this.url = url; this.path = path; this.timeoutSec = timeoutSec; }
    }

    static final Pattern MENTION = Pattern.compile("(?i)(\\binvoke-webrequest\\b|\\biwr\\b|\\bcurl(\\.exe)?\\b|\\bwget(\\.exe)?\\b|\\.downloadfile(async)?\\s*\\(|\\bstart-bitstransfer\\b|\\binvoke-restmethod\\b[^\\n]*-outfile)");

    /** 命令里像是在下载文件（有下载命令，并且有落盘：-OutFile / -o / -O / DownloadFile / Bits）。只是用 curl 查个接口不算。 */
    public static boolean mentions(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase(Locale.ROOT);
        if (!MENTION.matcher(c).find()) return false;
        if (c.contains("downloadfile") || c.contains("start-bitstransfer")) return true;
        if (Pattern.compile("(?i)-outfile\\b").matcher(c).find()) return true;
        // curl / wget 落盘写法
        return Pattern.compile("(?i)\\b(curl(\\.exe)?|wget(\\.exe)?)\\b[^;|&\\n]*(\\s-o\\s|\\s-O(\\s|$)|--output\\b|--remote-name\\b|\\s-P\\s|--output-document\\b)").matcher(cmd).find();
    }

    /** 整条命令就是一次下载：返回 url / path；认不全返回 null。 */
    public static Dl parse(String cmd) {
        if (cmd == null) return null;
        String c = cmd.trim();
        while (c.endsWith(";")) c = c.substring(0, c.length() - 1).trim();
        if (c.isEmpty() || c.length() > 2000) return null;
        // 模型常在前面加一句关进度 / 开 TLS1.2 的设置，不影响「这是一次下载」：去掉再认
        c = PREFIX.matcher(c).replaceFirst("").trim();
        // [preview-1120 真机] 模型常写的两种无害包装：调用运算符「& curl.exe …」、结尾合并错误流「… 2>&1」
        c = c.replaceFirst("^&\\s+(?=(?i)(curl|wget|invoke-webrequest|iwr|start-bitstransfer)(\\.exe)?\\s)", "");
        c = c.replaceFirst("\\s+(2|\\*)>&1\\s*$", "").trim();
        // 只认一条语句：有管道 / 分号 / && / 换行 / 子表达式 / 脚本块就不猜
        if (hasTopLevel(c)) return null;
        Matcher wc = WEBCLIENT.matcher(c);
        if (wc.matches()) return make(unq(wc.group(1)), unq(wc.group(2)), null);
        List<String> t = tokens(c);
        if (t == null || t.isEmpty()) return null;
        String head = t.get(0).toLowerCase(Locale.ROOT);
        if (head.equals("invoke-webrequest") || head.equals("iwr") || head.equals("invoke-restmethod") || head.equals("irm")
                || ((head.equals("curl") || head.equals("wget")) && psStyle(t))) return ps(t);
        if (head.equals("start-bitstransfer")) return bits(t);
        if (head.equals("curl") || head.equals("curl.exe")) return curl(t);
        if (head.equals("wget") || head.equals("wget.exe")) return wget(t);
        return null;
    }

    static final Pattern PREFIX = Pattern.compile("(?i)^(\\s*(\\$ProgressPreference\\s*=\\s*['\"]?SilentlyContinue['\"]?|\\[(System\\.)?Net\\.ServicePointManager\\]::SecurityProtocol\\s*=\\s*[\\w\\[\\]:.\\s,+-]{1,120}?)\\s*;)+");

    static final Pattern WEBCLIENT = Pattern.compile("(?i)^\\(\\s*new-object\\s+(?:system\\.)?net\\.webclient\\s*\\)\\s*\\.\\s*downloadfile\\s*\\(\\s*('[^']*'|\"[^\"]*\")\\s*,\\s*('[^']*'|\"[^\"]*\")\\s*\\)$");

    /** curl / wget 在 Windows PowerShell 5.1 是 Invoke-WebRequest 的别名：参数是 -Uri / -OutFile 这类就按 PowerShell 认。 */
    static boolean psStyle(List<String> t) {
        for (String x : t) { String l = x.toLowerCase(Locale.ROOT); if (l.equals("-outfile") || l.equals("-uri") || l.equals("-usebasicparsing")) return true; }
        return false;
    }

    static Dl ps(List<String> t) {
        String url = null, out = null; Long to = null;
        for (int i = 1; i < t.size(); i++) {
            String x = t.get(i), l = x.toLowerCase(Locale.ROOT);
            if (l.equals("-uri") && i + 1 < t.size()) url = t.get(++i);
            else if ((l.equals("-outfile") || l.startsWith("-outf")) && i + 1 < t.size()) out = t.get(++i);
            else if (l.equals("-timeoutsec") && i + 1 < t.size()) to = num(t.get(++i));
            else if (l.equals("-usebasicparsing") || l.equals("-passthru") || l.equals("-verbose")) { }
            else if (l.equals("-erroraction") || l.equals("-ea") || l.equals("-method") || l.equals("-maximumredirection")) {
                if (l.equals("-method") && i + 1 < t.size() && !t.get(i + 1).equalsIgnoreCase("get")) return null;
                i++;
            }
            else if (l.startsWith("-")) return null; // 头、凭据、代理、Body……认不全，不改
            else if (url == null) url = x;
            else return null;
        }
        return make(url, out, to);
    }

    static Dl bits(List<String> t) {
        String url = null, out = null;
        for (int i = 1; i < t.size(); i++) {
            String l = t.get(i).toLowerCase(Locale.ROOT);
            if (l.equals("-source") && i + 1 < t.size()) url = t.get(++i);
            else if (l.equals("-destination") && i + 1 < t.size()) out = t.get(++i);
            else if (l.startsWith("-")) return null;
            else if (url == null) url = t.get(i);
            else if (out == null) out = t.get(i);
            else return null;
        }
        return make(url, out, null);
    }

    static Dl curl(List<String> t) {
        String url = null, out = null; boolean remoteName = false; Long to = null;
        for (int i = 1; i < t.size(); i++) {
            String x = t.get(i);
            switch (x) {
                case "-L": case "--location": case "-s": case "-S": case "-sS": case "-sSL": case "-fsSL": case "-fL": case "-f": case "-sL": case "-Ls":
                case "--silent": case "--show-error": case "--fail": case "-#": case "--progress-bar": case "--retry-connrefused": case "-k": case "--insecure":
                case "--create-dirs": case "--compressed": // [preview-1120 真机]
                    if (x.equals("-k") || x.equals("--insecure")) return null; // 关掉证书校验的不替它改
                    break;
                case "-o": case "--output": if (i + 1 >= t.size()) return null; out = t.get(++i); break;
                case "-O": case "--remote-name": remoteName = true; break;
                case "-m": case "--max-time": if (i + 1 >= t.size()) return null; to = num(t.get(++i)); break;
                case "--retry": i++; break;
                default:
                    if (x.startsWith("-")) {
                        if (x.matches("-[sSLfk#]+") && !x.contains("k")) break;
                        if (x.matches("-[sSLf]*o") && i + 1 < t.size()) { out = t.get(++i); break; } // -Lo file
                        if (x.matches("-[sSLf]*O")) { remoteName = true; break; }
                        return null;
                    }
                    if (url != null) return null;
                    url = x;
            }
        }
        if (out == null && remoteName && url != null) out = remoteFileName(url);
        return make(url, out, to);
    }

    static Dl wget(List<String> t) {
        String url = null, out = null, dir = null;
        for (int i = 1; i < t.size(); i++) {
            String x = t.get(i);
            if (x.equals("-O") || x.equals("--output-document")) { if (i + 1 >= t.size()) return null; out = t.get(++i); }
            else if (x.equals("-P") || x.equals("--directory-prefix")) { if (i + 1 >= t.size()) return null; dir = t.get(++i); }
            else if (x.equals("-q") || x.equals("--quiet") || x.equals("-c") || x.equals("--continue") || x.equals("-nv")) { }
            else if (x.startsWith("-")) return null;
            else if (url == null) url = x;
            else return null;
        }
        if (out == null && url != null) { String n = remoteFileName(url); if (n == null) return null; out = dir == null ? n : dir.replaceAll("[\\\\/]+$", "") + "\\" + n; }
        return make(url, out, null);
    }

    static Dl make(String url, String path, Long to) {
        if (url == null || path == null) return null;
        url = url.trim(); path = expand(path.trim());
        if (path == null || path.isEmpty()) return null;
        String lu = url.toLowerCase(Locale.ROOT);
        if (!(lu.startsWith("http://") || lu.startsWith("https://"))) return null;
        if (url.contains("$") || url.contains("`") || url.matches(".*\\s.*")) return null;
        if (path.equals("-") || path.equalsIgnoreCase("nul") || path.equals("/dev/null")) return null; // 丢弃输出：不是在存文件
        return new Dl(url, path, to);
    }

    /** 路径里只认得 $env:USERPROFILE / $HOME / ~ / %USERPROFILE% 这几种，别的变量不猜（返回 null）。 */
    static String expand(String p) {
        String home = System.getProperty("user.home", "");
        String r = p;
        r = r.replaceAll("(?i)^\\$env:userprofile(?=[\\\\/]|$)", Matcher.quoteReplacement(home));
        r = r.replaceAll("(?i)^\\$home(?=[\\\\/]|$)", Matcher.quoteReplacement(home));
        r = r.replaceAll("(?i)^%userprofile%(?=[\\\\/]|$)", Matcher.quoteReplacement(home));
        r = r.replaceAll("^~(?=[\\\\/]|$)", Matcher.quoteReplacement(home));
        if (r.contains("$") || r.contains("%") || r.contains("`") || r.contains("(")) return null;
        return r;
    }

    static String remoteFileName(String url) {
        String u = url.replaceAll("[?#].*$", "");
        int k = u.lastIndexOf('/');
        if (k < 0 || k == u.length() - 1) return null;
        String n = u.substring(k + 1);
        if (u.substring(0, k).matches("(?i)https?:/?")) return null; // 只有域名
        if (!n.matches("[\\w.()+\\-%]{1,120}")) return null;
        try { n = java.net.URLDecoder.decode(n, java.nio.charset.StandardCharsets.UTF_8); } catch (RuntimeException ignored) { }
        return n.matches("[^\\\\/:*?\"<>|]{1,120}") ? n : null;
    }

    static Long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return null; }
    }

    /** 引号外有 ; | & 换行 $( { → 不是一条简单命令。 */
    static boolean hasTopLevel(String c) {
        char q = 0;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (q != 0) { if (ch == q) q = 0; continue; }
            if (ch == '\'' || ch == '"') { q = ch; continue; }
            if (ch == ';' || ch == '|' || ch == '&' || ch == '\n' || ch == '\r' || ch == '{' || ch == '}') return true;
            if (ch == '$' && i + 1 < c.length() && c.charAt(i + 1) == '(') return true;
        }
        return q != 0;
    }

    /** 按空白切词，认单 / 双引号（PowerShell 和 curl 都这么写）。 */
    static List<String> tokens(String c) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        char q = 0; boolean any = false;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (q != 0) { if (ch == q) q = 0; else b.append(ch); continue; }
            if (ch == '\'' || ch == '"') { q = ch; any = true; continue; }
            if (Character.isWhitespace(ch)) { if (b.length() > 0 || any) { out.add(b.toString()); b.setLength(0); any = false; } continue; }
            b.append(ch);
        }
        if (q != 0) return null;
        if (b.length() > 0 || any) out.add(b.toString());
        return out;
    }

    static String unq(String s) { return s.length() >= 2 ? s.substring(1, s.length() - 1) : s; }

    /** 给模型的提示（认不全时）。 */
    public static String hint() {
        return "没执行：这条命令在下载文件。下载文件请改用 download 工具（url + path）：任务卡上会显示进度条、速度、剩余时间，用户能取消，下完给大小和 SHA-256。"
                + "要先下再处理（解压、安装），就先 download，再用 run_command 做后面的步骤。"
                + "用户点名要用某条下载命令时，用 download 下同一个网址、存到同一个路径就是在做这件事：直接做，不要回头说命令被禁止、也不要再让用户确认一遍。";
    }

    /** 改写成下载工具的参数。 */
    public static Map<String, Object> args(Dl d) {
        Map<String, Object> out = new java.util.TreeMap<>();
        out.put("url", d.url);
        out.put("path", d.path);
        if (d.timeoutSec != null) out.put("timeout_sec", Math.max(30L, Math.min(4 * 3600L, d.timeoutSec)));
        return out;
    }
}
