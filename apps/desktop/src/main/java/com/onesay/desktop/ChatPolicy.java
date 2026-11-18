package com.onesay.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [approvals-1118] 电脑端「对话」（本机，人就坐在电脑前）的审批放宽：三个预设、按命令前缀「以后都允许」、打开网址的数据外发判定。
 *
 * <p>思路参考 openai/codex（Apache-2.0）：预设三档对应 {@code utils/approval-presets}（read-only / auto / full-access），
 * 前缀规则对应 execpolicy 的 {@code prefix_rule}（用户在审批卡上选「以后别再问以 … 开头的命令」），太宽的前缀不收
 * 对应 {@code core/src/exec_policy.rs} 的 {@code BANNED_PREFIX_SUGGESTIONS}。代码是这里自己写的，没有复制 Codex 的源码。
 *
 * <p>只管本机对话（{@link PcGuard#LOCAL}）；手机远程指令那套两轴（{@link LinkPolicy.Mode}）不受影响。
 * 硬规则（付款最后一下、自授权、不提权 / 要管理员只生成脚本、编码命令、改 Defender / 防火墙）根本走不到确认，所以记住什么都绕不过。
 */
public final class ChatPolicy {
    private ChatPolicy() {}

    // ------------------------------------------------------------------ 预设

    public enum Preset {
        READ_ONLY("read_only", "只读"), DEFAULT("default", "默认"), FULL("full", "完全访问");
        public final String id, zh;

        Preset(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        /** 认不出的一律当「默认」。 */
        public static Preset of(String s) {
            if (s != null) for (Preset p : values()) if (p.id.equalsIgnoreCase(s.trim()) || p.zh.equals(s.trim())) return p;
            return DEFAULT;
        }
    }

    /** 预设 → 判定用的两轴：只读 / 默认 = 工作区可写 · 按需（只读另外把放行收紧成确认）；完全访问 = 完全访问 · 从不。 */
    public static LinkPolicy.Mode mode(Preset p) {
        if (p == Preset.FULL) return new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER);
        return new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.ON_REQUEST);
    }

    /** 写给模型的一句：现在是哪个预设、哪些会弹确认。 */
    public static String policyLine(Preset p) {
        switch (p == null ? Preset.DEFAULT : p) {
            case READ_ONLY:
                return "当前权限预设：只读。查询直接执行；任何改动（写文件、装软件、打开程序或网址、联网下载）都会弹确认框由用户决定。"
                        + "要改动时照常发起工具调用，被拒绝就停下说明。";
            case FULL:
                return "当前权限预设：完全访问。除硬规则外不再弹确认，操作会直接执行；删除、卸载这类不可逆的事先确认用户的意思再做。";
            default:
                return "当前权限预设：默认。查询、打开普通 http(s) 网址、打开已安装的程序、在桌面 / 下载 / 文档 / OneSay 工作区 / 临时目录里新建或写新文件都直接执行；"
                        + "覆盖或删除已有文件、联网下载、安装软件、动这些目录以外的地方会弹确认框（用户可以选「以后以这个开头的命令不用问」）。"
                        + "要用户点头的步骤直接发起工具调用，不必先在回复里请示；被拒绝就停下说明，不要换写法绕过。";
        }
    }

    /** 预设变了之后告诉模型的一句（内部提示，不进对话记录）。 */
    public static String changedNote(Preset from, Preset to) {
        return "（OneSay 内部提示）用户刚把权限预设从「" + (from == null ? Preset.DEFAULT : from).zh + "」改成「" + to.zh + "」。" + policyLine(to);
    }

    // ------------------------------------------------------------------ 前缀规则（「以后以 … 开头的命令不用问」）

    /** 作为前缀太宽的（等于放行任意代码 / 任意命令）：不收，也不建议。小写，去掉 .exe。 */
    static final Set<String> BANNED_HEADS = Set.of(
            "powershell", "pwsh", "cmd", "bash", "sh", "wsl", "python", "python3", "py", "pythonw", "node", "nodejs", "deno", "bun",
            "perl", "ruby", "php", "lua", "java", "javaw", "wscript", "cscript", "mshta", "rundll32", "regsvr32", "msiexec", "msbuild",
            "installutil", "regasm", "regsvcs", "cmstp", "forfiles", "pcalua", "conhost", "explorer", "start", "start-process", "saps",
            "invoke-expression", "iex", "invoke-command", "icm", "invoke-item", "ii", "start-job", "start-threadjob", "add-type",
            "new-object", "&", ".", "env", "runas", "sudo", "schtasks", "at", "sc", "reg", "net", "netsh", "wmic", "bcdedit",
            "set-executionpolicy", "set-itemproperty", "new-itemproperty", "set-mppreference", "add-mppreference", "icacls", "takeown",
            "cipher", "format", "diskpart", "vssadmin", "wbadmin",
            // 下载类按「联网域名」记（net:主机），不按前缀：前缀会放行下载到任何位置
            "invoke-webrequest", "iwr", "invoke-restmethod", "irm", "curl", "wget", "start-bitstransfer", "bitsadmin", "certutil");

    /** 删除类：Codex 的提示词同样禁止给删除命令记规则。 */
    static final Set<String> DELETE_HEADS = Set.of("remove-item", "ri", "rm", "del", "erase", "rd", "rmdir", "clear-content", "clc",
            "clear-item", "cli", "remove-itemproperty", "rp", "uninstall-package", "remove-appxpackage", "stop-process", "kill", "taskkill");

    /** 多子命令的程序：前缀至少两段（「winget install」可以，「winget」不行）。 */
    static final Set<String> MULTI_HEADS = Set.of("winget", "git", "adb", "npm", "npx", "pnpm", "yarn", "pip", "pip3", "choco", "scoop",
            "dotnet", "cargo", "go", "docker", "kubectl", "gh", "conda", "uv", "fastboot", "scrcpy", "ffmpeg", "yt-dlp");

    /** 子命令本身就是「跑任意东西」的：不收。 */
    static final Set<String> BANNED_PAIRS = Set.of("adb shell", "adb exec-out", "npm run", "npm exec", "npx", "pnpm exec", "pnpm dlx", "yarn dlx",
            "dotnet run", "dotnet exec", "cargo run", "go run", "docker run", "docker exec", "kubectl exec", "conda run", "uv run", "git -c", "git config");

    /** 规则匹配时整条命令里不能有的写法（Codex：带重定向、子表达式、变量、通配符的命令不按规则匹配）。 */
    private static final Pattern RULE_FORBIDDEN = Pattern.compile("[`$<>{}()@*?\\[\\]\\r\\n]|&|%|\\|\\|");

    /** 一个词（小写、去掉 .exe）。 */
    static String norm(String tok) {
        String t = tok == null ? "" : tok.trim();
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) t = t.substring(1, t.length() - 1);
        t = t.toLowerCase(Locale.ROOT);
        if (t.endsWith(".exe")) t = t.substring(0, t.length() - 4);
        return t;
    }

    /** 按空白切词（引号里的空白不切，引号留在词里）。引号没配对返回 null。 */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                cur.append(c);
                if (c == q) q = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                q = c;
                cur.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (q != 0) return null;
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 引号外的 sep 切段。 */
    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; cur.append(c); continue; }
            if (c == '\'' || c == '"') { q = c; cur.append(c); continue; }
            if (c == sep) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** 这个前缀能不能当「以后都允许」：非空、不超过 6 段、开头不是解释器 / 删除类、多子命令程序至少两段且不是「跑任意东西」的子命令。 */
    public static boolean allowedPrefix(List<String> prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.size() > 6) return false;
        for (String p : prefix) {
            if (p == null || p.isBlank() || p.length() > 200) return false;
            if (RULE_FORBIDDEN.matcher(p).find() || p.contains(";") || p.contains("|")) return false;
        }
        String head = norm(prefix.get(0));
        String base = head.replaceAll(".*[\\\\/]", "");
        if (BANNED_HEADS.contains(base) || DELETE_HEADS.contains(base) || BANNED_HEADS.contains(head)) return false;
        if (base.startsWith("-")) return false;
        if (MULTI_HEADS.contains(base)) {
            if (prefix.size() < 2) return false;
            String sub = norm(prefix.get(1));
            if (sub.startsWith("-") || BANNED_PAIRS.contains(base + " " + sub)) return false;
        }
        if (BANNED_PAIRS.contains(base)) return false;
        return true;
    }

    /** 给确认卡建议的前缀：单条命令（不含 ; |），开头 + （多子命令程序再带子命令）。认不准返回 null（卡上就不给「以后不用问」）。 */
    public static List<String> suggest(String command) {
        if (command == null) return null;
        String s = command.trim();
        if (s.isEmpty() || s.length() > 2000 || RULE_FORBIDDEN.matcher(s).find() || s.contains(";") || s.contains("|")) return null;
        List<String> w = words(s);
        if (w == null || w.isEmpty()) return null;
        List<String> p = new ArrayList<>();
        p.add(norm(w.get(0)));
        String base = p.get(0).replaceAll(".*[\\\\/]", "");
        if (MULTI_HEADS.contains(base)) {
            if (w.size() < 2) return null;
            p.add(norm(w.get(1)));
        }
        return allowedPrefix(p) ? p : null;
    }

    /** 命令里引号外有没有 ; 或 |（这类命令只在每一段都能单独判定时才按规则放行）。 */
    static boolean chained(String s) {
        return splitTop(s, ';').size() > 1 || splitTop(s, '|').size() > 1;
    }

    /**
     * 这条命令被已记住的某条前缀规则放行吗：按引号外的 ; 切段，每段要么明显只读（{@link LocalReadOnly}），要么开头几个词和某条规则一致；
     * 至少一段是规则放行的。整条命令有变量 / 子表达式 / 重定向 / 通配符 / 花括号就不按规则匹配。返回命中的规则，没命中返回 null。
     */
    public static List<String> matches(String command, List<List<String>> rules) {
        if (command == null || rules == null || rules.isEmpty()) return null;
        String s = command.trim();
        if (s.isEmpty() || s.length() > 2000 || RULE_FORBIDDEN.matcher(LocalReadOnly.literalsBlanked(s)).find()) return null;
        if (RULE_FORBIDDEN.matcher(s).find()) return null; // 引号里的 $ / 反引号也不行（双引号会展开）
        List<String> hit = null;
        for (String seg : splitTop(s, ';')) {
            String t = seg.trim();
            if (t.isEmpty()) return null;
            List<String> pipe = splitTop(t, '|');
            List<String> r = matchOne(pipe.get(0).trim(), rules);
            boolean restRead = true;
            for (int i = 1; i < pipe.size(); i++) if (!LocalReadOnly.ok(pipe.get(i).trim())) { restRead = false; break; }
            if (r != null && restRead) { hit = r; continue; }
            if (LocalReadOnly.ok(t)) continue;
            return null;
        }
        return hit;
    }

    static List<String> matchOne(String cmd, List<List<String>> rules) {
        List<String> w = words(cmd);
        if (w == null || w.isEmpty()) return null;
        for (List<String> r : rules) {
            if (r == null || r.isEmpty() || r.size() > w.size() || !allowedPrefix(r)) continue;
            boolean ok = true;
            for (int i = 0; i < r.size() && ok; i++) ok = norm(w.get(i)).equals(norm(r.get(i)));
            if (ok) return r;
        }
        return null;
    }

    /** 配置里存的规则（JSON 数组的数组）→ 列表；坏的、太宽的丢掉。 */
    @SuppressWarnings("unchecked")
    public static List<List<String>> parseRules(String json) {
        List<List<String>> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Object v;
        try {
            v = Json.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(v instanceof List)) return out;
        for (Object o : (List<Object>) v) {
            if (!(o instanceof List)) continue;
            List<String> r = new ArrayList<>();
            for (Object x : (List<Object>) o) r.add(String.valueOf(x));
            r = normRule(r);
            if (r != null && !out.contains(r)) out.add(r);
            if (out.size() >= 200) break;
        }
        return out;
    }

    public static String formatRules(List<List<String>> rules) {
        List<Object> l = new ArrayList<>();
        for (List<String> r : rules) l.add(new ArrayList<Object>(r));
        return Json.stringify(l);
    }

    /** 规范化一条规则（小写、去 .exe、去引号）；不能当规则的返回 null。 */
    static List<String> normRule(List<String> r) {
        if (r == null) return null;
        List<String> n = new ArrayList<>();
        for (String x : r) n.add(norm(x));
        return allowedPrefix(n) ? Collections.unmodifiableList(n) : null;
    }

    public static String ruleText(List<String> r) {
        return String.join(" ", r);
    }

    // ------------------------------------------------------------------ 确认卡上「以后不用问」的键

    /** 键：prefix:["winget","install"] / url:example.com（信任域名）/ net:example.com（命令可以联网的域名）。 */
    public static String prefixKey(List<String> p) {
        return "prefix:" + Json.stringify(new ArrayList<Object>(p));
    }

    public static String urlKey(String host) { return "url:" + host; }

    public static String netKey(String host) { return "net:" + host; }

    /** 键能不能用（远程塞进来的乱写的不收）。 */
    public static boolean validKey(String k) {
        if (k == null) return false;
        if (k.startsWith("prefix:")) return keyPrefix(k) != null;
        if (k.startsWith("url:") || k.startsWith("net:")) return keyHost(k) != null;
        return false;
    }

    public static List<String> keyPrefix(String k) {
        if (k == null || !k.startsWith("prefix:")) return null;
        List<List<String>> l = parseRules("[" + k.substring(7) + "]");
        return l.size() == 1 ? l.get(0) : null;
    }

    public static String keyHost(String k) {
        if (k == null || k.length() < 5) return null;
        String h = LinkPolicy.normDomain(k.substring(4));
        if (h == null || h.isEmpty() || !h.matches("[a-z0-9.-]+") || !h.contains(".") || LinkPolicy.isIpLiteral(h)) return null;
        return h;
    }

    public static String keyZh(String k) {
        List<String> p = keyPrefix(k);
        if (p != null) return "以后以「" + ruleText(p) + "」开头的命令不用问";
        String h = keyHost(k);
        if (h != null && k.startsWith("url:")) return "以后打开 " + h + " 的网址不用问（加进信任的网址域名，手机让电脑打开也算）";
        if (h != null) return "以后命令访问 " + h + " 不用问（加进命令可以联网的域名）";
        return k;
    }

    // ------------------------------------------------------------------ 打开网址

    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9+/=_%-]{64,}");

    /**
     * 网址看起来在夹带数据（查询串 / 片段太长，或有一长串像编码数据的字符）：本机对话里这种网址照旧问。
     * Codex 把「打开网址」整个当危险（浏览器跑在沙箱外，网址可以外发数据）；这里按用户的意思放开普通网址，只留这一道。
     */
    public static String dataLike(String url) {
        if (url == null) return "";
        String u = url.trim();
        int q = u.indexOf('?'), h = u.indexOf('#');
        int cut = q < 0 ? h : h < 0 ? q : Math.min(q, h);
        String tail = cut < 0 ? "" : u.substring(cut);
        if (tail.length() > 300) return "网址后面的参数很长（" + tail.length() + " 字符），可能夹带数据";
        if (BLOB.matcher(u.substring(Math.min(u.length(), u.indexOf("//") < 0 ? 0 : u.indexOf("//") + 2))).find()) return "网址里有一长串像编码数据的字符，可能夹带数据";
        return "";
    }

    /** 测试 / 设置页：规则表给人看的样子。 */
    public static List<Object> view(List<List<String>> rules) {
        List<Object> out = new ArrayList<>();
        for (List<String> r : rules) {
            Map<String, Object> m = new java.util.TreeMap<>();
            m.put("key", prefixKey(r));
            m.put("text", ruleText(r));
            out.add(m);
        }
        return out;
    }
}
