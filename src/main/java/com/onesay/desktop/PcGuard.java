package com.onesay.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class PcGuard {

    public static final long CONFIRM_MS = 30_000;
    public static final int ALLOW = 1, DENY = 0, TIMEOUT = -1;

    /** 电脑上弹确认（界面对话框 + 右下角通知）。 */
    public interface Confirmer {
        int ask(String from, String cmd, String detail, long timeoutMs);
    }

    /** 界面轮询用的待确认列表。 */
    public static final class Confirms implements Confirmer {
        private final Map<Integer, Object[]> pending = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);
        volatile Consumer<String> onNew = s -> { };

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            int id = seq.getAndIncrement();
            CountDownLatch l = new CountDownLatch(1);
            int[] res = {TIMEOUT};
            Map<String, Object> info = new TreeMap<>();
            info.put("id", (long) id);
            info.put("from", from);
            info.put("cmd", cmd);
            info.put("detail", detail);
            info.put("deadline", System.currentTimeMillis() + timeoutMs);
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
            Object[] p = pending.remove(id);
            if (p == null) return false;
            ((int[]) p[1])[0] = allow ? ALLOW : DENY;
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
                int r = c == null ? TIMEOUT : c.ask(from, cmd, detail, CONFIRM_MS);
                if (r == TIMEOUT) o.reply = result(false, "电脑上 30 秒没人确认，已拒绝", LinkPolicy.R_CONFIRM_TIMEOUT, null);
                else if (r != ALLOW) o.reply = result(false, "电脑上拒绝了", LinkPolicy.R_CONFIRM_DENIED, null);
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
            case "sys_exec": return "执行命令：" + str(a, "command");
            case "uninstall_app": return "卸载 " + (str(a, "name").isEmpty() ? str(a, "package") : str(a, "name"));
            case "volume": return "调音量（" + str(a, "action") + "）";
            case "shutdown": return "关机";
            case "sleep": return "睡眠";
            default: return why;
        }
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
