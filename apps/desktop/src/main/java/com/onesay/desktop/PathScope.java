package com.onesay.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * [approvals-1118] 本机对话：按路径判定「只在自己地盘里新建 / 写」的命令，直接执行不弹确认（Codex 工作区沙箱的替代品）。
 *
 * <p>为什么不是真沙箱：见 work/research/approval-codex-1118.md §3（JDK 17 调不了受限令牌；这台电脑整个会话就是管理员；
 * Codex 式写限制要改用户目录 ACL、断网要改防火墙，都碰硬规则）。所以这里是<b>静态判定</b>：只认很窄的写法，认不准就交回去照旧确认。
 *
 * <p>地盘（根目录）：
 * <ul>
 *   <li>草稿区：OneSay 工作区、%TEMP% —— 里面新建、覆盖、删除都直接做（根目录本身不能删）；</li>
 *   <li>用户区：桌面、下载、文档（含 OneDrive 下的同名目录）—— <b>新建</b>直接做；覆盖 / 删除 / 移走已有的东西只在它是
 *   这次运行里对话自己新建的（{@link Env#created}）时直接做，否则照旧确认（别人的文件不静默改）。</li>
 * </ul>
 * 永远不算：文档\WindowsPowerShell、文档\PowerShell（配置文件 = 每次开 PowerShell 都会执行）、工作区的 .git / .codex / .agents、
 * UNC / 设备路径、通配符、8.3 短名、NTFS 备用流、结尾带点或空格的名字、经联接点 / 符号链接跳出地盘的路径。
 *
 * <p>认得的命令（每条用引号外的 ; 串，可以和只读命令混着）：New-Item / md / mkdir（目录或文件，-Value 只许字面量）、
 * Set-Content / Add-Content / Out-File（-Value 字面量，或前面接一个字面量 / 只读管道）、Copy-Item、Move-Item、Rename-Item、
 * Remove-Item（不许 -Force：Codex 也把强制删除当危险）、Expand-Archive、Compress-Archive、Invoke-WebRequest（GET，-OutFile）、
 * curl.exe -o。联网的另外记下主机名（{@link Plan#hosts}），由调用方按「命令可以联网的域名」决定问不问。
 */
final class PathScope {
    private PathScope() {}

    /** 判定用到的环境；测试里全部可以换。路径都是 Windows 写法（盘符 + 反斜杠）。 */
    static final class Env {
        String userProfile = "", temp = "", workspace = "";
        /** 桌面 / 下载 / 文档（没给就按 userProfile 推，外加 OneDrive 下的）。 */
        List<String> userRoots = new ArrayList<>();
        Predicate<String> exists = p -> false;
        Predicate<String> isDir = p -> false;
        /** 解析联接点 / 符号链接后的真实路径（Windows 上用 toRealPath；测试里原样返回）。 */
        UnaryOperator<String> real = p -> p;
        /** 这次运行里对话自己新建的（规范化、小写）。 */
        Set<String> created = Set.of();
        /** 环境变量（$env:NAME 展开用，键小写）。 */
        Map<String, String> envVars = new HashMap<>();

        List<String> roots() {
            List<String> r = new ArrayList<>();
            for (String x : scratch()) r.add(x);
            for (String x : users()) r.add(x);
            return r;
        }

        List<String> scratch() {
            List<String> r = new ArrayList<>();
            for (String x : new String[] {workspace, temp}) {
                String n = abs(x);
                if (n != null && depth(n) >= 2) r.add(n);
            }
            return r;
        }

        List<String> users() {
            List<String> r = new ArrayList<>();
            List<String> src = new ArrayList<>(userRoots);
            String up = abs(userProfile);
            if (src.isEmpty() && up != null) {
                for (String d : new String[] {"Desktop", "Downloads", "Documents", "OneDrive\\Desktop", "OneDrive\\Documents", "OneDrive\\桌面", "OneDrive\\文档"})
                    src.add(up + "\\" + d);
            }
            for (String x : src) {
                String n = abs(x);
                if (n != null && depth(n) >= 2) r.add(n);
            }
            return r;
        }
    }

    /** 判定结果。ok = 整条命令都在地盘里、认得出；creates = 执行成功后要记成「对话自己建的」的路径；hosts = 要联网的主机。 */
    static final class Plan {
        boolean ok;
        String why = "";
        boolean wrote;
        final List<String> creates = new ArrayList<>();
        final List<String> hosts = new ArrayList<>();
        final List<String> targets = new ArrayList<>();

        static Plan no(String why) {
            Plan p = new Plan();
            p.why = why;
            return p;
        }
    }

    // ------------------------------------------------------------------ 路径

    /** 已经是绝对路径（X:\…）的规范化：/ → \，去掉 . 和 ..，去掉结尾的 \。不合法返回 null。 */
    static String abs(String raw) {
        if (raw == null) return null;
        String p = raw.trim().replace('/', '\\');
        if (p.length() < 3 || !Character.isLetter(p.charAt(0)) || p.charAt(1) != ':' || p.charAt(2) != '\\') return null;
        if (p.indexOf(':', 2) >= 0) return null; // 备用流 a.txt:x
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == '*' || c == '?' || c == '<' || c == '>' || c == '|' || c == '"' || c == '[' || c == ']' || c == '`' || c == '$') return null;
        }
        List<String> parts = new ArrayList<>();
        for (String seg : p.substring(3).split("\\\\+")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
                continue;
            }
            if (seg.endsWith(".") || seg.endsWith(" ") || seg.startsWith(" ")) return null; // Windows 会悄悄去掉，名字对不上
            if (seg.matches(".*~\\d.*")) return null; // 8.3 短名
            if (seg.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?")) return null;
            parts.add(seg);
        }
        StringBuilder b = new StringBuilder().append(Character.toUpperCase(p.charAt(0))).append(":\\");
        b.append(String.join("\\", parts));
        return b.toString();
    }

    static int depth(String absPath) {
        String r = absPath.substring(3);
        return r.isEmpty() ? 0 : r.split("\\\\").length;
    }

    static String low(String p) { return p.toLowerCase(Locale.ROOT); }

    static boolean under(String p, String root) {
        String a = low(p), r = low(root);
        return a.equals(r) || a.startsWith(r.endsWith("\\") ? r : r + "\\");
    }

    static String parent(String p) {
        int k = p.lastIndexOf('\\');
        if (k <= 2) return p.substring(0, 3);
        return p.substring(0, k);
    }

    static String leaf(String p) {
        int k = p.lastIndexOf('\\');
        return k < 0 ? p : p.substring(k + 1);
    }

    /**
     * 命令里的一个路径词 → 规范化的绝对路径：去引号（单引号原样，双引号里只许 $env:NAME / $HOME）、展开 ~ / $HOME / $env:X、
     * 相对路径按工作区（命令的工作目录）算。认不准返回 null。
     */
    static String path(String tok, Env env) {
        String t = value(tok, env);
        if (t == null || t.isEmpty()) return null;
        if (t.startsWith("~\\") || t.startsWith("~/") || t.equals("~")) t = env.userProfile + t.substring(1);
        t = t.replace('/', '\\');
        if (t.startsWith("\\")) return null; // UNC / 设备路径 / 盘符相对
        if (!(t.length() >= 2 && Character.isLetter(t.charAt(0)) && t.charAt(1) == ':')) {
            String ws = abs(env.workspace);
            if (ws == null) return null;
            t = ws + "\\" + t;
        } else if (t.length() == 2 || t.charAt(2) != '\\') return null; // C:foo（盘符相对）
        return abs(t);
    }

    /** 一个参数值：去引号、展开允许的变量。有别的变量 / 子表达式 / 反引号返回 null。 */
    static String value(String tok, Env env) {
        if (tok == null) return null;
        String t = tok;
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') return t.substring(1, t.length() - 1).replace("''", "'");
        if (t.indexOf('\'') >= 0) return null;
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') t = t.substring(1, t.length() - 1);
        if (t.indexOf('"') >= 0 || t.indexOf('`') >= 0 || t.contains("$(") || t.contains("@(") || t.indexOf(',') >= 0) return null;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c != '$') { b.append(c); i++; continue; }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\G\\$(?:\\{env:([A-Za-z_][A-Za-z0-9_]*)\\}|env:([A-Za-z_][A-Za-z0-9_]*)|(HOME)\\b)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
            if (!m.find(i)) return null;
            String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : "userprofile";
            String v = env.envVars.get(name.toLowerCase(Locale.ROOT));
            if (v == null && name.equalsIgnoreCase("userprofile")) v = env.userProfile;
            if (v == null && (name.equalsIgnoreCase("temp") || name.equalsIgnoreCase("tmp"))) v = env.temp;
            if (v == null || v.isEmpty()) return null;
            b.append(v);
            i = m.end();
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 地盘

    static boolean protectedPath(String p, Env env) {
        String l = low(p);
        for (String u : env.users()) {
            String ul = low(u);
            if (ul.endsWith("\\documents") || ul.endsWith("\\文档")) {
                if (under(l, ul + "\\windowspowershell") || under(l, ul + "\\powershell")) return true;
            }
        }
        String ws = abs(env.workspace);
        if (ws != null) for (String d : new String[] {".git", ".codex", ".agents"}) if (under(l, low(ws) + "\\" + d)) return true;
        return l.contains("\\start menu\\programs\\startup") || l.endsWith("\\desktop.ini");
    }

    static String rootOf(String p, List<String> roots) {
        for (String r : roots) if (under(p, r)) return r;
        return null;
    }

    /** 真实路径（联接点 / 符号链接）也得在同一类地盘里，否则当不在地盘里。 */
    static boolean realStays(String p, Env env, List<String> roots) {
        String rp;
        try {
            rp = abs(env.real.apply(p));
        } catch (RuntimeException e) {
            return false;
        }
        return rp != null && rootOf(rp, roots) != null && !protectedPath(rp, env);
    }

    static boolean inScratch(String p, Env env) {
        List<String> s = env.scratch();
        return rootOf(p, s) != null && realStays(p, env, s);
    }

    static boolean inAny(String p, Env env) {
        List<String> r = env.roots();
        return rootOf(p, r) != null && realStays(p, env, r);
    }

    static boolean isRoot(String p, Env env) {
        for (String r : env.roots()) if (low(r).equals(low(p))) return true;
        // 根的上级（C:\Users\me、C:\Users\me\OneDrive）也不能动
        for (String r : env.roots()) if (under(r, p)) return true;
        return false;
    }

    static boolean sessionMade(String p, Env env) {
        String l = low(p);
        for (String c : env.created) if (under(l, c)) return true;
        return false;
    }

    /** 能改 / 删已有的：草稿区里的，或这次对话自己建的；根和受保护的不行。 */
    static boolean modifiable(String p, Env env) {
        if (p == null || isRoot(p, env) || protectedPath(p, env) || !inAny(p, env)) return false;
        return inScratch(p, env) || sessionMade(p, env);
    }

    /** 能新建：在地盘里、不是根、不受保护、现在不存在。 */
    static boolean creatable(String p, Env env) {
        return p != null && !isRoot(p, env) && !protectedPath(p, env) && inAny(p, env) && !env.exists.test(p);
    }

    /** 写一个目标：新建，或改能改的。新建的记进 plan.creates。 */
    static String write(String p, Env env, Plan plan) {
        if (p == null) return "路径认不准";
        if (creatable(p, env)) {
            plan.creates.add(p);
            plan.targets.add(p);
            return null;
        }
        if (env.exists.test(p) && modifiable(p, env)) {
            plan.targets.add(p);
            return null;
        }
        if (!inAny(p, env) || protectedPath(p, env) || isRoot(p, env)) return "不在桌面 / 下载 / 文档 / 工作区 / 临时目录里（" + p + "）";
        return "要改动已有的「" + p + "」（不是这次对话建的）";
    }

    // ------------------------------------------------------------------ 命令

    /** 一个 cmdlet 的参数表：带值的（别名 → 正名）、开关、按位置的。 */
    static final class Spec {
        final Map<String, String> named = new HashMap<>();
        final Set<String> switches;
        final List<String> positional;

        Spec(String named, String switches, String positional) {
            for (String kv : named.split(",")) {
                if (kv.isBlank()) continue;
                String[] x = kv.split("=");
                this.named.put(x[0].trim(), x.length > 1 ? x[1].trim() : x[0].trim());
            }
            this.switches = switches.isBlank() ? Set.of() : Set.of(switches.split(","));
            this.positional = positional.isBlank() ? List.of() : List.of(positional.split(","));
        }
    }

    static final Spec NEW_ITEM = new Spec("path,literalpath=path,name,itemtype,type=itemtype,value", "force", "path");
    static final Spec SET_CONTENT = new Spec("path,literalpath=path,value,encoding", "force,nonewline,noclobber", "path,value");
    static final Spec OUT_FILE = new Spec("filepath,path=filepath,literalpath=filepath,encoding,width", "force,nonewline,noclobber,append", "filepath,encoding");
    static final Spec COPY = new Spec("path,literalpath=path,destination", "recurse,force,container", "path,destination");
    static final Spec MOVE = new Spec("path,literalpath=path,destination", "", "path,destination");
    static final Spec RENAME = new Spec("path,literalpath=path,newname", "", "path,newname");
    static final Spec REMOVE = new Spec("path,literalpath=path", "recurse,r=recurse", "path");
    static final Spec EXPAND = new Spec("path,literalpath=path,destinationpath", "force", "path,destinationpath");
    static final Spec COMPRESS = new Spec("path,literalpath=path,destinationpath,compressionlevel", "force,update", "path,destinationpath");
    static final Spec IWR = new Spec("uri,outfile,method,timeoutsec,maximumredirection,useragent", "usebasicparsing", "uri");

    static Spec spec(String head) {
        switch (head) {
            case "new-item": case "ni": case "md": case "mkdir": return NEW_ITEM;
            case "set-content": case "add-content": case "ac": return SET_CONTENT;
            case "out-file": return OUT_FILE;
            case "copy-item": case "cpi": case "copy": case "cp": return COPY;
            case "move-item": case "mi": case "move": case "mv": return MOVE;
            case "rename-item": case "rni": case "ren": return RENAME;
            case "remove-item": case "ri": case "rm": case "del": case "erase": case "rd": case "rmdir": return REMOVE;
            case "expand-archive": return EXPAND;
            case "compress-archive": return COMPRESS;
            case "invoke-webrequest": case "iwr": case "curl": case "wget": return IWR;
            default: return null;
        }
    }

    /** 按参数表把词解析成 名 → 值（开关的值是 "true"）。认不出的参数 / 多出来的位置参数返回 null。 */
    static Map<String, String> parse(List<String> w, Spec sp) {
        Map<String, String> out = new HashMap<>();
        int pos = 0;
        for (int i = 1; i < w.size(); i++) {
            String t = w.get(i);
            if (t.startsWith("-") && t.length() > 1 && Character.isLetter(t.charAt(1))) {
                String name0 = t.substring(1).toLowerCase(Locale.ROOT);
                String inline = null;
                int c = name0.indexOf(':');
                if (c >= 0) { inline = t.substring(t.indexOf(':') + 1); name0 = name0.substring(0, c); }
                final String name = name0;
                if (sp.switches.contains(name) || sp.switches.stream().anyMatch(s -> s.startsWith(name + "="))) {
                    String canon = name;
                    for (String s : sp.switches) if (s.startsWith(name + "=")) canon = s.substring(name.length() + 1);
                    if (inline != null && !inline.equalsIgnoreCase("$true")) return null;
                    out.put(canon, "true");
                    continue;
                }
                String canon = sp.named.get(name);
                if (canon == null) return null;
                String v = inline != null ? inline : (i + 1 < w.size() ? w.get(++i) : null);
                if (v == null || out.containsKey(canon)) return null;
                out.put(canon, v);
                continue;
            }
            while (pos < sp.positional.size() && out.containsKey(sp.positional.get(pos))) pos++;
            if (pos >= sp.positional.size()) return null;
            out.put(sp.positional.get(pos++), t);
        }
        return out;
    }

    /** 整条命令里不许的写法（引号里的纯文字不算）。 */
    private static final java.util.regex.Pattern BAD = java.util.regex.Pattern.compile("[`{}<>&%@()\\[\\]\\r\\n]|\\$\\(|::");

    /** 判定一条命令。只要有一处认不准就返回 ok=false（调用方照旧确认）。 */
    static Plan analyze(String command, Env env) {
        if (command == null || command.isBlank() || command.length() > 2000) return Plan.no("空 / 太长");
        String s = command.trim();
        if (BAD.matcher(LocalReadOnly.literalsBlanked(s)).find()) return Plan.no("有子表达式 / 重定向 / 脚本块");
        Plan plan = new Plan();
        boolean any = false;
        for (String seg0 : ChatPolicy.splitTop(s, ';')) {
            String seg = seg0.trim();
            if (seg.isEmpty()) return Plan.no("空的一段");
            if (LocalReadOnly.ok(seg)) continue;
            List<String> pipe = ChatPolicy.splitTop(seg, '|');
            String last = pipe.get(pipe.size() - 1).trim();
            if (pipe.size() > 1) {
                // 只认「字面量 / 只读管道 | Set-Content / Add-Content / Out-File 路径」
                String before = seg.substring(0, seg.length() - pipe.get(pipe.size() - 1).length() - 1).trim();
                List<String> bw = ChatPolicy.words(before);
                boolean literal = bw != null && bw.size() == 1 && (bw.get(0).matches("'[^']*'") || bw.get(0).matches("\"[^\"$`]*\""));
                if (!literal && !LocalReadOnly.ok(before)) return Plan.no("管道前面不是字面量 / 只读命令");
                List<String> lw = ChatPolicy.words(last);
                String h = lw == null || lw.isEmpty() ? "" : ChatPolicy.norm(lw.get(0));
                if (!(h.equals("set-content") || h.equals("add-content") || h.equals("ac") || h.equals("out-file"))) return Plan.no("管道后面不是写文件");
                String why = one(lw, env, plan, true);
                if (why != null) return Plan.no(why);
                any = true;
                continue;
            }
            List<String> w = ChatPolicy.words(seg);
            if (w == null || w.isEmpty()) return Plan.no("引号没配对");
            String why = one(w, env, plan, false);
            if (why != null) return Plan.no(why);
            any = true;
        }
        if (!any) return Plan.no("没有写入");
        plan.ok = true;
        plan.wrote = !plan.targets.isEmpty();
        return plan;
    }

    /** 一条写入命令。返回 null = 认得、在地盘里；否则原因。 */
    static String one(List<String> w, Env env, Plan plan, boolean piped) {
        String head = ChatPolicy.norm(w.get(0));
        if (head.equals("curl") && w.get(0).toLowerCase(Locale.ROOT).endsWith(".exe")) return curlExe(w, env, plan);
        Spec sp = spec(head);
        if (sp == null) return "不认得的命令 " + w.get(0);
        Map<String, String> a = parse(w, sp);
        if (a == null) return "参数认不准";
        switch (head) {
            case "new-item": case "ni": case "md": case "mkdir": {
                String type = a.containsKey("itemtype") ? value(a.get("itemtype"), env) : (head.equals("md") || head.equals("mkdir") ? "directory" : "file");
                if (type == null) return "类型认不准";
                type = type.toLowerCase(Locale.ROOT);
                if ((head.equals("md") || head.equals("mkdir")) && !type.equals("directory")) return "类型认不准";
                if (!type.equals("directory") && !type.equals("file")) return "只认目录 / 文件（不建链接）";
                if (a.containsKey("value") && (type.equals("directory") || value(a.get("value"), env) == null || a.get("value").contains("$"))) return "内容不是字面量";
                String base = a.containsKey("path") ? path(a.get("path"), env) : abs(env.workspace);
                if (base == null) return "路径认不准";
                String target = base;
                if (a.containsKey("name")) {
                    String n = value(a.get("name"), env);
                    if (n == null || n.isEmpty() || n.contains("$")) return "名字认不准";
                    target = abs(base + "\\" + n);
                }
                if (target == null) return "路径认不准";
                if (type.equals("directory") && env.exists.test(target) && env.isDir.test(target) && inAny(target, env) && !protectedPath(target, env)) return null; // 已有的目录：什么都不变
                return write(target, env, plan);
            }
            case "set-content": case "add-content": case "ac": case "out-file": {
                String key = head.equals("out-file") ? "filepath" : "path";
                if (!a.containsKey(key)) return "没有路径";
                if (head.equals("out-file") ? false : !piped && !a.containsKey("value")) return "没有内容";
                if (a.containsKey("value") && (a.get("value").contains("$") || value(a.get("value"), env) == null)) return "内容不是字面量";
                if (a.containsKey("encoding") && !a.get("encoding").matches("(?i)[a-z0-9]+")) return "编码认不准";
                return write(path(a.get(key), env), env, plan);
            }
            case "copy-item": case "cpi": case "copy": case "cp":
            case "move-item": case "mi": case "move": case "mv": {
                boolean move = head.startsWith("m");
                String src = a.containsKey("path") ? path(a.get("path"), env) : null;
                if (src == null) return "来源路径认不准";
                if (move && !modifiable(src, env)) return "要移走的「" + src + "」不是工作区 / 临时目录里的，也不是这次对话建的";
                if (!a.containsKey("destination")) return "没有目标";
                String dst = path(a.get("destination"), env);
                if (dst == null) return "目标路径认不准";
                String target = env.exists.test(dst) && env.isDir.test(dst) ? abs(dst + "\\" + leaf(src)) : dst;
                return write(target, env, plan);
            }
            case "rename-item": case "rni": case "ren": {
                String src = a.containsKey("path") ? path(a.get("path"), env) : null;
                String nn = a.containsKey("newname") ? value(a.get("newname"), env) : null;
                if (src == null || nn == null || nn.isEmpty() || nn.contains("\\") || nn.contains("/") || nn.contains("$")) return "改名参数认不准";
                if (!modifiable(src, env)) return "要改名的「" + src + "」不是工作区 / 临时目录里的，也不是这次对话建的";
                String target = abs(parent(src) + "\\" + nn);
                if (target == null || !creatable(target, env)) return "新名字已经有了或不在地盘里";
                plan.creates.add(target);
                plan.targets.add(target);
                return null;
            }
            case "remove-item": case "ri": case "rm": case "del": case "erase": case "rd": case "rmdir": {
                String p = a.containsKey("path") ? path(a.get("path"), env) : null;
                if (p == null) return "路径认不准";
                if (!modifiable(p, env)) return "要删的「" + p + "」不是工作区 / 临时目录里的，也不是这次对话建的";
                plan.targets.add(p);
                return null;
            }
            case "expand-archive": {
                String src = a.containsKey("path") ? path(a.get("path"), env) : null;
                if (src == null) return "压缩包路径认不准";
                String dst = a.containsKey("destinationpath") ? path(a.get("destinationpath"), env) : null;
                if (dst == null) {
                    String ws = abs(env.workspace);
                    String base = leaf(src).replaceAll("(?i)\\.zip$", "");
                    dst = ws == null ? null : abs(ws + "\\" + base);
                }
                if (dst == null) return "目标认不准";
                if (env.exists.test(dst) && !a.containsKey("force") && inAny(dst, env) && !protectedPath(dst, env) && !isRoot(dst, env)) {
                    plan.targets.add(dst); // 不带 -Force 不会覆盖已有文件（遇到同名就报错）
                    return null;
                }
                if (env.exists.test(dst) && !a.containsKey("force") && isRoot(dst, env) && rootOf(dst, env.roots()) != null && !protectedPath(dst, env)) {
                    plan.targets.add(dst); // 解到桌面 / 下载本身：只会新增文件
                    return null;
                }
                return write(dst, env, plan);
            }
            case "compress-archive": {
                if (!a.containsKey("path") || path(a.get("path"), env) == null) return "来源路径认不准";
                if (!a.containsKey("destinationpath")) return "没有目标";
                if (a.containsKey("compressionlevel") && !a.get("compressionlevel").matches("(?i)optimal|fastest|nocompression")) return "压缩级别认不准";
                return write(path(a.get("destinationpath"), env), env, plan);
            }
            case "invoke-webrequest": case "iwr": case "curl": case "wget": {
                String host = host(a.get("uri"), env);
                if (host == null) return "网址认不准（只认普通的 http(s) 网址）";
                if (a.containsKey("method") && !a.get("method").matches("(?i)'?(get|head)'?")) return "只认 GET / HEAD";
                if (a.containsKey("useragent") && value(a.get("useragent"), env) == null) return "参数认不准";
                for (String k : new String[] {"timeoutsec", "maximumredirection"}) if (a.containsKey(k) && !a.get(k).matches("\\d{1,4}")) return "参数认不准";
                if (!plan.hosts.contains(host)) plan.hosts.add(host);
                if (a.containsKey("outfile")) return write(path(a.get("outfile"), env), env, plan);
                return null;
            }
            default:
                return "不认得的命令";
        }
    }

    /** curl.exe：网址 + -o 路径 + 几个无害开关。 */
    static String curlExe(List<String> w, Env env, Plan plan) {
        String url = null, out = null;
        for (int i = 1; i < w.size(); i++) {
            String t = w.get(i);
            if (t.equals("-o") || t.equals("--output")) {
                if (i + 1 >= w.size() || out != null) return "参数认不准";
                out = w.get(++i);
            } else if (t.matches("-[LsSf]+") || t.matches("--(location|silent|show-error|fail)")) {
                continue;
            } else if (t.equals("--retry") && i + 1 < w.size() && w.get(i + 1).matches("\\d{1,2}")) {
                i++;
            } else if (!t.startsWith("-") && url == null) {
                url = t;
            } else return "curl 参数认不准（" + t + "）";
        }
        String host = host(url, env);
        if (host == null) return "网址认不准";
        if (!plan.hosts.contains(host)) plan.hosts.add(host);
        if (out == null) return null;
        return write(path(out, env), env, plan);
    }

    /** 普通 http(s) 网址的主机名（IP / 内网 / 带账号 / 仿冒 / 夹带数据的都不认）。 */
    static String host(String tok, Env env) {
        if (tok == null) return null;
        String u = value(tok, env);
        if (u == null || u.contains("$")) return null;
        LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, List.of());
        if (!c.ok || c.host == null || c.host.isEmpty() || !ChatPolicy.dataLike(u).isEmpty()) return null;
        return c.host;
    }

    /** write_file 工具写工作区以外的一个文件。 */
    static Plan writeFile(String rawPath, Env env) {
        Plan plan = new Plan();
        String p = abs(rawPath);
        if (p == null) return Plan.no("路径认不准");
        String why = write(p, env, plan);
        if (why != null) return Plan.no(why);
        plan.ok = true;
        plan.wrote = true;
        return plan;
    }
}
