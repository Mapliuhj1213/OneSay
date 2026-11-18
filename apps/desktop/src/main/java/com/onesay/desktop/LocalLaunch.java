package com.onesay.desktop;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [approvals-1118] 本机对话里「打开网址 / 打开已装的程序」的低风险写法：
 * <ul>
 *   <li>run_command 里的 {@code Start-Process https://…}、{@code start https://…}、{@code explorer https://…}、
 *   {@code rundll32 url.dll,FileProtocolHandler https://…} → 改走 open_url（同一套网址检查：只认 http(s)，file: / javascript: / 自定义协议 / UNC 不开）；</li>
 *   <li>{@code Start-Process '已装程序.exe'}（不带参数，路径在 Program Files / 本机程序目录 / 开始菜单里）或 {@code Start-Process 名字} → 改走 open_app；</li>
 *   <li>open_app 按名字找：设置里的程序表 → 开始菜单快捷方式（.lnk 文件名）→ 给的完整路径（必须在安装目录里且存在）。
 *   命令解释器 / 脚本宿主 / 系统管理工具不算「程序」，不开。</li>
 * </ul>
 */
final class LocalLaunch {
    private LocalLaunch() {}

    static final Set<String> NOT_APPS = Set.of("powershell", "pwsh", "cmd", "wscript", "cscript", "mshta", "rundll32", "regsvr32", "regedit", "reg",
            "msiexec", "schtasks", "taskschd", "mmc", "bash", "wsl", "python", "pythonw", "py", "node", "java", "javaw", "conhost", "windows powershell",
            "command prompt", "命令提示符", "注册表编辑器", "windows terminal", "wt", "sc", "netsh", "diskpart", "bcdedit", "certutil", "bitsadmin", "msbuild");

    /** 把 run_command 里的打开写法改成 open_url / open_app；不是这类返回 null（照常当命令）。 */
    static Object[] rewrite(String cmd, Map<String, Object> args) {
        if (!"sys_exec".equals(cmd) || args == null) return null;
        String c = String.valueOf(args.getOrDefault("command", "")).trim();
        if (c.isEmpty() || c.length() > 2000 || ChatPolicy.chained(c)) return null;
        List<String> w = ChatPolicy.words(c);
        if (w == null || w.isEmpty() || w.size() > 4) return null;
        String head = ChatPolicy.norm(w.get(0));
        List<String> rest = new ArrayList<>(w.subList(1, w.size()));
        if (head.equals("rundll32") && !rest.isEmpty() && rest.get(0).equalsIgnoreCase("url.dll,FileProtocolHandler")) rest.remove(0);
        else if (!(head.equals("start-process") || head.equals("start") || head.equals("saps") || head.equals("explorer"))) return null;
        if (!rest.isEmpty() && rest.get(0).equalsIgnoreCase("-FilePath")) rest.remove(0);
        if (rest.size() != 1) return null;
        String target = unquote(rest.get(0));
        if (target == null || target.isEmpty() || target.contains("$") || target.contains("`")) return null;
        Map<String, Object> out = new TreeMap<>();
        if (target.matches("(?i)https?://.*")) {
            out.put("url", target);
            return new Object[] {"open_url", out};
        }
        if (head.equals("explorer") || head.equals("rundll32")) return null;
        if (target.contains(":") || target.contains("\\") || target.contains("/")) {
            if (!target.matches("(?i)[a-z]:\\\\.*\\.(exe|lnk)")) return null; // 自定义协议（bilibili://）、文件、UNC：照常当命令
            out.put("path", target);
            out.put("name", new File(target.replace('\\', '/')).getName().replaceAll("(?i)\\.(exe|lnk)$", ""));
            return new Object[] {"open_app", out};
        }
        if (!target.matches("[\\p{L}\\p{N} ._+-]{1,60}")) return null;
        out.put("name", target.replaceAll("(?i)\\.exe$", ""));
        return new Object[] {"open_app", out};
    }

    static String unquote(String t) {
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) return t.substring(1, t.length() - 1);
        if (t.indexOf('\'') >= 0 || t.indexOf('"') >= 0) return null;
        return t;
    }

    static boolean notApp(String nameOrPath) {
        String n = nameOrPath == null ? "" : nameOrPath.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\.(exe|lnk)$", "");
        return NOT_APPS.contains(n) || n.isEmpty();
    }

    /** 安装目录（打开完整路径的程序时只认这些下面的）。 */
    static List<String> installRoots(Map<String, String> env) {
        List<String> r = new ArrayList<>();
        for (String k : new String[] {"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) if (env.get(k) != null) r.add(env.get(k));
        if (env.get("LOCALAPPDATA") != null) r.add(env.get("LOCALAPPDATA") + "\\Programs");
        r.addAll(startMenus(env));
        return r;
    }

    static List<String> startMenus(Map<String, String> env) {
        List<String> r = new ArrayList<>();
        if (env.get("ProgramData") != null) r.add(env.get("ProgramData") + "\\Microsoft\\Windows\\Start Menu\\Programs");
        if (env.get("APPDATA") != null) r.add(env.get("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs");
        return r;
    }

    /** 找程序：设置里的程序表 → 开始菜单 .lnk（文件名完全一样优先，其次包含）→ 安装目录里的完整路径。找不到返回 null。 */
    static File resolve(String name, String path, Map<String, String> appMap, List<String> startMenus, List<String> installRoots) {
        if (path != null && !path.isEmpty()) {
            if (notApp(path)) return null;
            String low = path.toLowerCase(Locale.ROOT);
            for (String r : installRoots) {
                String rl = r.toLowerCase(Locale.ROOT);
                if (low.startsWith(rl.endsWith("\\") ? rl : rl + "\\") && !low.contains("..")) {
                    File f = new File(path);
                    return f.isFile() ? f : null;
                }
            }
            return null;
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || notApp(n)) return null;
        String mapped = appMap == null ? null : appMap.get(n.toLowerCase(Locale.ROOT));
        if (mapped != null && new File(mapped).isFile()) return new File(mapped);
        File contains = null;
        for (String sm : startMenus) {
            List<File> all = new ArrayList<>();
            collect(new File(sm), all, 0);
            for (File f : all) {
                String base = f.getName().replaceAll("(?i)\\.lnk$", "");
                if (notApp(base) || base.toLowerCase(Locale.ROOT).contains("uninstall") || base.contains("卸载")) continue;
                if (base.equalsIgnoreCase(n)) return f;
                if (contains == null && base.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) contains = f;
            }
        }
        return contains;
    }

    static void collect(File dir, List<File> out, int depth) {
        File[] l = dir.listFiles();
        if (l == null || depth > 4 || out.size() > 3000) return;
        for (File f : l) {
            if (f.isDirectory()) collect(f, out, depth + 1);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk")) out.add(f);
        }
    }
}
