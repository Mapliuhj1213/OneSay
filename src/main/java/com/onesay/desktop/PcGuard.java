package com.onesay.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * link-v2 · 电脑作为被控端：手机（或任何配对设备）发来的每条指令先过这里 —— 执行级别（{@link LinkPolicy}，电脑用严格矩阵）
 * + 网址 / 剪贴板信任检查 + 工作区沙箱。结论：直接执行 / 先在电脑上确认（30 秒不点算拒绝）/ 拒绝 / 只在工作区 scripts/ 生成脚本。
 *
 * <p>电脑端已有的能力（截图、音量、关机、快捷指令…）和以后新增的都必须在 {@link LinkPolicy#pcCat} 里登记类别，没登记的一律拒绝。
 * A 类（注册表、网络配置、服务 / 驱动）与工作区以外的写入：任何级别都只生成 .ps1 脚本，永远不执行；给自己授权同样只生成脚本。
 *
 * <p>快捷指令（用户在电脑上定义的、快捷指令文件夹里的 .ps1 / .bat / .cmd）单独一类：按「文件名 id + 内容 SHA-256」批准。第一次触发 / 内容改过
 * 在电脑上问「允许 / 拒绝」并可选「以后直接执行」；批准表存在配置里，界面能看、能撤销；内容碰严格规则或文件在远程可写的工作区里的不能批准。
 */
public final class PcGuard {

    public static final long CONFIRM_MS = 30_000;
    public static final int ALLOW = 1, DENY = 0, TIMEOUT = -1, ALWAYS = 2;
    /** 快捷指令文件太大就不认（正常的快捷指令只有几 KB）。 */
    public static final long SHORTCUT_MAX = 256 * 1024;

    /** 电脑上弹确认（界面对话框 + 右下角通知）。 */
    public interface Confirmer {
        int ask(String from, String cmd, String detail, long timeoutMs);

        /** offerAlways：多给一个「以后直接执行」（快捷指令用），选了返回 {@link #ALWAYS}。 */
        default int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            return ask(from, cmd, detail, timeoutMs);
        }
    }

    /** 界面轮询用的待确认列表。 */
    public static final class Confirms implements Confirmer {
        private final Map<Integer, Object[]> pending = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);
        volatile Consumer<String> onNew = s -> { };

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            return ask(from, cmd, detail, timeoutMs, false);
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            int id = seq.getAndIncrement();
            CountDownLatch l = new CountDownLatch(1);
            int[] res = {TIMEOUT};
            Map<String, Object> info = new TreeMap<>();
            info.put("id", (long) id);
            info.put("from", from);
            info.put("cmd", cmd);
            info.put("detail", detail);
            info.put("deadline", System.currentTimeMillis() + timeoutMs);
            info.put("always", offerAlways);
            pending.put(id, new Object[]{l, res, info});
            try { onNew.accept(detail); } catch (Exception ignored) { }
            try {
                l.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending.remove(id);
            }
            return res[0];
        }

        public boolean resolve(int id, boolean allow) {
            return resolve(id, allow, false);
        }

        /** always 只在这条请求给了「以后直接执行」时才算数，否则当「允许一次」。 */
        @SuppressWarnings("unchecked")
        public boolean resolve(int id, boolean allow, boolean always) {
            Object[] p = pending.remove(id);
            if (p == null) return false;
            boolean offered = Boolean.TRUE.equals(((Map<String, Object>) p[2]).get("always"));
            ((int[]) p[1])[0] = allow ? (always && offered ? ALWAYS : ALLOW) : DENY;
            ((CountDownLatch) p[0]).countDown();
            return true;
        }

        @SuppressWarnings("unchecked")
        public List<Object> list() {
            List<Object> out = new ArrayList<>();
            for (Object[] p : pending.values()) out.add(new TreeMap<>((Map<String, Object>) p[2]));
            return out;
        }
    }

    /** 判定结果：reply 非空就直接回它（拒绝 / 脚本 / 确认没过）；否则用 args 执行。 */
    public static final class Outcome {
        public Actions.Result reply;
        public Map<String, Object> args;
        public LinkPolicy.Cat cat;
        public boolean headsUp;
        public String headsText = "";
    }

    private final Config cfg;
    public volatile Confirmer confirmer;
    /** 告诉电脑前的人（生成脚本、完全访问下的提醒）。 */
    public volatile Consumer<String> notify = s -> { };

    public PcGuard(Config cfg, Confirmer confirmer) {
        this.cfg = cfg;
        this.confirmer = confirmer;
    }

    public File workspace() {
        return cfg.workspace().toFile();
    }

    public static LinkPolicy.Cat category(String cmd, Map<String, Object> a, File ws) {
        if ("sys_exec".equals(cmd)) return LinkPolicy.classifyShell(LinkPolicy.Target.PC, str(a, "command"));
        if ("file_write".equals(cmd)) return ws != null && LinkPolicy.resolveInside(ws, str(a, "path")) != null ? LinkPolicy.Cat.WORKSPACE_WRITE : LinkPolicy.Cat.OUTSIDE_WRITE;
        return LinkPolicy.pcCat(cmd);
    }

    public Outcome check(String from, String cmd, Map<String, Object> a0) {
        Outcome o = new Outcome();
        Map<String, Object> a = new TreeMap<>();
        if (a0 != null) for (Map.Entry<String, Object> e : a0.entrySet()) if (!e.getKey().startsWith("_")) a.put(e.getKey(), e.getValue());
        o.args = a;
        File ws = workspace();
        LinkPolicy.Cat cat = category(cmd, a, ws);
        o.cat = cat;
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        if ("open_url".equals(cmd)) f.url = LinkPolicy.checkUrl(str(a, "url"), LinkPolicy.parseDomains(cfg.trusted));
        if ("clipboard_set".equals(cmd)) f.clip = LinkPolicy.checkClip(a.get("text") == null ? "" : String.valueOf(a.get("text")));
        if ("open_app".equals(cmd) || "uninstall_app".equals(cmd) || "app_close".equals(cmd)) {
            String name = str(a, "name"), pkg = str(a, "package"), path = str(a, "path");
            f.appBlocked = LinkPolicy.blockedApp(pkg, name) || LinkPolicy.blockedApp(path.replaceAll(".*[\\\\/]", "").replaceAll("(?i)\\.exe$", ""), name);
            String low = (name + " " + path).toLowerCase(java.util.Locale.ROOT);
            f.appSystem = low.contains("\\windows\\") || low.contains("explorer") || low.contains("taskmgr") || low.contains("regedit") || low.contains("onesay");
        }
        Shortcut sc = null;
        if (cat == LinkPolicy.Cat.SHORTCUT) {
            sc = shortcut(str(a, "name"));
            f = shortcutFacts(sc);
            if (sc != null) a.put("_sha256", f.shortcutHash); // 执行前再核对一次，防判定后文件被换掉
        }
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, cfg.level(), cat, f);
        switch (d.verdict) {
            case DENY:
                o.reply = result(false, d.text, d.reason, null);
                return o;
            case SCRIPT: {
                String rel = script(from, cmd, a, d.text);
                Map<String, Object> x = new TreeMap<>();
                if (rel != null) x.put("path", rel);
                o.reply = result(false, rel == null ? "要生成脚本，但写不进电脑工作区" : "已生成脚本，需你自己执行：" + rel, LinkPolicy.R_SCRIPT_GENERATED, x);
                return o;
            }
            case CONFIRM: {
                String detail = from + " 请求：" + detail(cmd, a, d.text);
                Confirmer c = confirmer;
                boolean offer = sc != null;
                int r = c == null ? TIMEOUT : c.ask(from, cmd, detail, CONFIRM_MS, offer);
                if (offer) {
                    // 快捷指令：没批准下来时原因码是 shortcut_needs_approval / shortcut_changed，确认结果另放 confirm_result
                    Map<String, Object> x = new TreeMap<>();
                    x.put("shortcut", sc.id);
                    if (r == ALWAYS) {
                        approve(sc.id, f.shortcutHash);
                        try { notify.accept("已批准快捷指令「" + sc.name + "」以后从手机直接执行（可在快捷指令页撤销）"); } catch (Exception ignored) { }
                    } else if (r == TIMEOUT) {
                        x.put("confirm_result", LinkPolicy.R_CONFIRM_TIMEOUT);
                        o.reply = result(false, "电脑上 30 秒没人确认，没执行快捷指令「" + sc.name + "」", d.reason, x);
                    } else if (r != ALLOW) {
                        x.put("confirm_result", LinkPolicy.R_CONFIRM_DENIED);
                        o.reply = result(false, "电脑上拒绝了快捷指令「" + sc.name + "」", d.reason, x);
                    }
                    return o;
                }
                if (r == TIMEOUT) o.reply = result(false, "电脑上 30 秒没人确认，已拒绝", LinkPolicy.R_CONFIRM_TIMEOUT, null);
                else if (r != ALLOW && r != ALWAYS) o.reply = result(false, "电脑上拒绝了", LinkPolicy.R_CONFIRM_DENIED, null);
                return o;
            }
            default:
                if (d.headsUp) {
                    o.headsUp = true;
                    o.headsText = d.text;
                    try { notify.accept(from + "：" + d.text); } catch (Exception ignored) { }
                }
                return o;
        }
    }

    /** 确认框里给人看的那句话：带上确切的动作（网址域名、命令全文、文件路径）。 */
    static String detail(String cmd, Map<String, Object> a, String why) {
        switch (cmd) {
            case "script_run": return "运行工作区脚本 scripts/" + str(a, "name");
            case "shortcut_run": return "运行快捷指令「" + str(a, "name") + "」（" + why + "）";
            case "sys_exec": return "执行命令：" + str(a, "command");
            case "uninstall_app": return "卸载 " + (str(a, "name").isEmpty() ? str(a, "package") : str(a, "name"));
            case "volume": return "调音量（" + str(a, "action") + "）";
            case "shutdown": return "关机";
            case "sleep": return "睡眠";
            default: return why;
        }
    }

    // ------------------------------------------------------------------ 快捷指令

    /** 一个快捷指令：文件名、稳定 id（文件名小写）、路径、触发这一刻的内容。 */
    public static final class Shortcut {
        public final String name, id;
        public final Path path;
        public final byte[] bytes;

        Shortcut(Path path, byte[] bytes) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.id = LinkPolicy.shortcutId(name);
            this.bytes = bytes;
        }
    }

    /** 快捷指令文件夹里名字对应的文件（和 {@link DesktopActions#shortcutRun} 用同一个查找）；没有返回 null。 */
    public Shortcut shortcut(String name) {
        Path p = DesktopActions.shortcutPath(cfg.scriptsDir, name);
        return p == null ? null : load(p);
    }

    static Shortcut load(Path p) {
        try {
            if (Files.size(p) > SHORTCUT_MAX) return new Shortcut(p, null);
            return new Shortcut(p, Files.readAllBytes(p));
        } catch (Exception e) {
            return null;
        }
    }

    /** 快捷指令列表（文件夹里的 .ps1 / .bat / .cmd，最多 200 个，按名字排序）。 */
    public List<Shortcut> shortcuts() {
        List<Shortcut> out = new ArrayList<>();
        if (cfg.scriptsDir.isEmpty()) return out;
        try (var s = Files.list(Paths.get(cfg.scriptsDir))) {
            s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("(?i).+\\.(ps1|bat|cmd)$")).sorted().limit(200)
                    .forEach(p -> { Shortcut x = load(p); if (x != null) out.add(x); });
        } catch (Exception ignored) { }
        return out;
    }

    /** 判定事实：内容哈希 / 严格规则 / 批准表，再加两条电脑端才知道的：文件太大、文件在远程可写的工作区里（不算你定义的）。 */
    LinkPolicy.Facts shortcutFacts(Shortcut sc) {
        if (sc == null) return LinkPolicy.shortcutFacts(null, null, null, false);
        LinkPolicy.Facts f = LinkPolicy.shortcutFacts(sc.id, sc.bytes == null ? new byte[0] : sc.bytes, approvals(), true);
        String extra = null;
        if (sc.bytes == null) extra = "文件太大（超过 256 KB）";
        else if (insideWorkspace(sc.path)) extra = "文件在手机能写的电脑工作区里（不算你在电脑上定义的快捷指令）";
        if (extra != null && f.shortcutBlocked.isEmpty()) {
            f.shortcutBlocked = extra;
            f.shortcutApproved = false;
        }
        return f;
    }

    boolean insideWorkspace(Path p) {
        try {
            File ws = workspace().getCanonicalFile();
            File x = p.toFile().getCanonicalFile();
            for (File d = x; d != null; d = d.getParentFile()) if (d.equals(ws)) return true;
        } catch (Exception e) {
            return true; // 判断不了就当在里面（不放开）
        }
        return false;
    }

    public synchronized Map<String, String> approvals() {
        return LinkPolicy.parseApprovals(cfg.shortcutApprovals);
    }

    /** 记下「以后直接执行」：id → 内容哈希。内容碰严格规则的不会走到这里（判定直接拒）。 */
    public synchronized void approve(String id, String hash) {
        Map<String, String> m = approvals();
        m.put(LinkPolicy.shortcutId(id), hash);
        cfg.shortcutApprovals = LinkPolicy.formatApprovals(m);
        save();
    }

    /** 撤销批准；没有这条返回 false。 */
    public synchronized boolean revoke(String id) {
        Map<String, String> m = approvals();
        if (m.remove(LinkPolicy.shortcutId(id)) == null) return false;
        cfg.shortcutApprovals = LinkPolicy.formatApprovals(m);
        save();
        return true;
    }

    private void save() {
        try { cfg.save(); } catch (Exception ignored) { } // 测试里配置可能没有文件路径；内存里的批准表照样生效
    }

    /** 界面用：每个快捷指令的状态 approved（以后直接执行）/ changed（改过，要重新批准）/ new（还没批准）/ blocked（不能批准，带原因）。 */
    public List<Object> shortcutView() {
        List<Object> out = new ArrayList<>();
        for (Shortcut sc : shortcuts()) {
            LinkPolicy.Facts f = shortcutFacts(sc);
            Map<String, Object> m = new TreeMap<>();
            m.put("name", sc.name);
            m.put("id", sc.id);
            String st = !f.shortcutBlocked.isEmpty() ? "blocked" : f.shortcutApproved ? "approved" : f.shortcutChanged ? "changed" : "new";
            m.put("status", st);
            m.put("why", f.shortcutBlocked);
            out.add(m);
        }
        return out;
    }

    /** 界面用：批准表（含文件已经没了 / 内容改过的），每条可撤销。 */
    public List<Object> approvalView() {
        Map<String, Shortcut> byId = new TreeMap<>();
        for (Shortcut sc : shortcuts()) byId.put(sc.id, sc);
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String> e : approvals().entrySet()) {
            Shortcut sc = byId.get(e.getKey());
            String st;
            if (sc == null) st = "missing";
            else {
                LinkPolicy.Facts f = shortcutFacts(sc);
                st = !f.shortcutBlocked.isEmpty() ? "blocked" : f.shortcutApproved ? "approved" : "changed";
            }
            Map<String, Object> m = new TreeMap<>();
            m.put("id", e.getKey());
            m.put("name", sc == null ? e.getKey() : sc.name);
            m.put("status", st);
            m.put("hash", e.getValue().substring(0, 12));
            out.add(m);
        }
        return out;
    }

    String script(String from, String cmd, Map<String, Object> a, String why) {
        String command;
        String risk;
        if ("sys_exec".equals(cmd)) {
            command = str(a, "command");
            risk = "系统级改动（注册表 / 网络 / 服务 / 驱动 / 权限）可能让电脑断网、变得不安全或无法启动";
        } else if ("file_write".equals(cmd)) {
            command = "$text = @'\n" + String.valueOf(a.get("text")).replace("\n'@", "\n '@") + "\n'@\nSet-Content -LiteralPath '" + str(a, "path").replace("'", "''") + "' -Value $text -Encoding UTF8";
            risk = "写工作区以外的文件，会覆盖同名文件";
        } else {
            command = "# " + cmd + " " + LinkPolicy.oneLine(String.valueOf(a));
            risk = "未知";
        }
        LinkPolicy.Script s = LinkPolicy.buildScript(LinkPolicy.ScriptKind.PS1, cmd, "来自 " + from + " 的远程请求。" + why, risk, command, System.currentTimeMillis());
        String rel = LinkPolicy.writeScript(workspace(), s);
        if (rel != null) try { notify.accept("已生成脚本，需你自己执行：" + rel); } catch (Exception ignored) { }
        return rel;
    }

    /** 电脑端内建的工作区指令（判定已经过了）。不是这些返回 null，交给 Actions。 */
    public Actions.Result builtin(String cmd, Map<String, Object> a) {
        File ws = workspace();
        try {
            switch (cmd) {
                case "level": {
                    Map<String, Object> d = new TreeMap<>();
                    return Actions.Result.ok("电脑执行级别：" + cfg.level().zh, d);
                }
                case "file_write": {
                    String t = a.get("text") == null ? "" : String.valueOf(a.get("text"));
                    if (t.length() > 256 * 1024) return Actions.Result.fail("文件太大（超过 256 KB）");
                    File f = LinkPolicy.writeInside(ws, str(a, "path"), t.getBytes(StandardCharsets.UTF_8));
                    return f == null ? Actions.Result.fail("只能写工作区里的文件") : Actions.Result.ok("已写入电脑工作区 " + str(a, "path"));
                }
                case "script_list": {
                    Map<String, Object> d = new TreeMap<>();
                    d.put("scripts", new ArrayList<Object>(LinkPolicy.listScripts(ws)));
                    return Actions.Result.ok("工作区脚本", d);
                }
                case "script_run": {
                    String n = str(a, "name");
                    File f = n.contains("/") || n.contains("\\") ? null : LinkPolicy.resolveInside(ws, "scripts/" + n);
                    if (f == null || !f.isFile()) return Actions.Result.fail("工作区 scripts/ 里没有这个脚本");
                    String low = n.toLowerCase(java.util.Locale.ROOT);
                    ProcessBuilder pb;
                    if (low.endsWith(".ps1")) pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", f.getPath());
                    else if (low.endsWith(".bat")) pb = new ProcessBuilder("cmd", "/c", f.getPath());
                    else return Actions.Result.fail("电脑上只运行 .ps1 / .bat");
                    pb.directory(ws).redirectErrorStream(true).start();
                    return Actions.Result.ok("已开始运行 " + n);
                }
                case "sys_exec":
                    return Actions.Result.fail("电脑上的系统命令不执行（只生成脚本）");
                default:
                    return null;
            }
        } catch (Exception e) {
            return Actions.Result.fail(cmd + " 出错：" + e.getClass().getSimpleName());
        }
    }

    Actions.Result result(boolean ok, String text, String reason, Map<String, Object> extra) {
        Map<String, Object> d = new TreeMap<>();
        if (extra != null) d.putAll(extra);
        if (reason != null && !reason.isEmpty()) {
            d.put("reason", reason);
            d.put("reason_zh", LinkPolicy.reasonZh(reason));
        }
        return new Actions.Result(ok, text, decorate(d));
    }

    /** 回复里带上电脑的执行级别（手机界面可以显示）。 */
    public Map<String, Object> decorate(Map<String, Object> d0) {
        Map<String, Object> d = d0 == null ? new TreeMap<>() : new TreeMap<>(d0);
        d.put("level", cfg.level().id);
        d.put("level_zh", cfg.level().zh);
        return d;
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
