package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

/** link-v2 · 电脑作为被控端：严格矩阵、A 类只生成脚本、给自己授权只生成脚本、确认超时、工作区越界、端到端（手机→电脑）原因码。 */
public class PcGuardTest {

    static final LinkPolicy.Verdict A = LinkPolicy.Verdict.ALLOW, C = LinkPolicy.Verdict.CONFIRM, D = LinkPolicy.Verdict.DENY, S = LinkPolicy.Verdict.SCRIPT;

    static final class Stub implements PcGuard.Confirmer {
        int answer = PcGuard.TIMEOUT;
        final List<String> asked = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            asked.add(cmd + "|" + detail);
            assertEquals(30_000L, timeoutMs);
            return answer;
        }
    }

    static Config cfg(String level) throws Exception {
        Config c = new Config(Files.createTempFile("pcg", ".properties"));
        c.deviceId = "pc-g";
        c.pairKey = Protocol.newKey();
        c.pcLevel = level;
        c.workspaceDir = Files.createTempDirectory("pcws").toFile().getCanonicalPath();
        return c;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    static String reason(PcGuard.Outcome o) {
        return o.reply == null ? "" : String.valueOf(o.reply.data.get("reason"));
    }

    @Test public void strictPcMatrix() {
        LinkPolicy.Target t = LinkPolicy.Target.PC;
        Object[][] rows = {
                {LinkPolicy.Cat.QUERY, A, A, A}, {LinkPolicy.Cat.READ_PRIVATE, D, A, A}, {LinkPolicy.Cat.HARMLESS, D, A, A},
                {LinkPolicy.Cat.SIMPLE_SETTING, D, C, C}, {LinkPolicy.Cat.POWER, D, C, C}, {LinkPolicy.Cat.SYSTEM_A, S, S, S},
                {LinkPolicy.Cat.NET_SEC_SETTING, S, S, S}, {LinkPolicy.Cat.SHELL_EXEC, S, S, S}, {LinkPolicy.Cat.OUTSIDE_WRITE, S, S, S},
                {LinkPolicy.Cat.OPEN_APP, D, A, A}, {LinkPolicy.Cat.WORKSPACE_WRITE, D, A, A}, {LinkPolicy.Cat.UNINSTALL_APP, D, C, C},
                {LinkPolicy.Cat.APP_OPERATE, D, D, A}, {LinkPolicy.Cat.RUN_WS_SCRIPT, D, D, C}, {LinkPolicy.Cat.SELF_GRANT, S, S, S},
                {LinkPolicy.Cat.SYSTEM_APP_OP, D, D, D}, {LinkPolicy.Cat.UNKNOWN, D, D, D}};
        for (Object[] r : rows) {
            int i = 1;
            for (LinkPolicy.Level l : LinkPolicy.Level.values()) {
                assertEquals(r[0] + " " + l, r[i++], LinkPolicy.decide(t, l, (LinkPolicy.Cat) r[0], new LinkPolicy.Facts()).verdict);
            }
        }
        // 电脑端所有已有能力都登记了类别（没登记的会被一律拒绝）
        for (String cap : new String[]{"ping", "caps", "info", "screenshot", "clipboard_get", "clipboard_set", "file_get", "file_list", "lock", "media",
                "open_app", "open_url", "shortcut_list", "shortcut_run", "shutdown", "shutdown_abort", "sleep", "volume", "notify", "memory_sync", "echo"})
            assertFalse(cap, LinkPolicy.pcCat(cap) == LinkPolicy.Cat.UNKNOWN);
    }

    @Test public void guardDecisionsAuto() throws Exception {
        Config c = cfg("auto");
        Stub st = new Stub();
        PcGuard g = new PcGuard(c, st);
        assertNull(g.check("phone", "ping", null).reply);
        assertNull(g.check("phone", "screenshot", null).reply);
        assertNull(g.check("phone", "echo", m("text", "x")).reply);
        assertEquals(LinkPolicy.R_UNKNOWN, reason(g.check("phone", "format_c", null)));

        // B 类：音量要电脑上确认；30 秒没人点 = 拒绝
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, reason(g.check("phone", "volume", m("action", "up"))));
        assertTrue(st.asked.get(0), st.asked.get(0).startsWith("volume|phone 请求："));
        st.answer = PcGuard.DENY;
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(g.check("phone", "shutdown", m("confirm", true))));
        st.answer = PcGuard.ALLOW;
        assertNull(g.check("phone", "sleep", m("confirm", true)).reply);

        // 快捷指令 = 运行脚本：自动档不让
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(g.check("phone", "shortcut_run", m("name", "x"))));
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(g.check("phone", "app_close", m("name", "notepad"))));

        // 网址 / 剪贴板信任检查
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, reason(g.check("phone", "open_url", m("url", "http://127.0.0.1:8080/"))));
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, reason(g.check("phone", "open_url", m("url", "file:///C:/Windows/System32/calc.exe"))));
        int n = st.asked.size();
        st.answer = PcGuard.TIMEOUT;
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, reason(g.check("phone", "open_url", m("url", "https://example.org/"))));
        assertTrue("确认框显示域名", st.asked.get(n).contains("example.org"));
        c.trusted = "example.org";
        assertNull(g.check("phone", "open_url", m("url", "https://www.example.org/x")).reply);
        assertEquals(LinkPolicy.R_CLIPBOARD_REJECTED, reason(g.check("phone", "clipboard_set", m("text", "powershell -enc AAAA"))));
        assertEquals(LinkPolicy.R_BLOCKED_APP, reason(g.check("phone", "open_app", m("name", "支付宝"))));
        assertEquals(LinkPolicy.R_BLOCKED_APP, reason(g.check("phone", "open_app", m("name", "x", "path", "C:\\Program Files\\KeePassXC\\KeePassXC.exe"))));
        assertEquals("电脑带来的下划线参数被丢掉", null, g.check("phone", "echo", m("_from", "spoof")).args.get("_from"));
        Map<String, Object> d = g.decorate(null);
        assertEquals("auto", d.get("level"));
    }

    @Test public void categoryAIsScriptOnlyAtEveryLevelAndNeverExecuted() throws Exception {
        String[] cmds = {"reg add HKLM\\Software\\Policies\\X /v Y /t REG_DWORD /d 1 /f", "netsh advfirewall set allprofiles state off",
                "Set-DnsClientServerAddress -InterfaceIndex 12 -ServerAddresses 1.1.1.1", "sc config WinDefend start= disabled",
                "notepad C:\\Windows\\System32\\drivers\\etc\\hosts", "netsh wlan connect name=Cafe", "pnputil /add-driver evil.inf /install"};
        for (LinkPolicy.Level l : LinkPolicy.Level.values()) {
            Config c = cfg(l.id);
            Stub st = new Stub();
            st.answer = PcGuard.ALLOW; // 就算有人点允许也不该被问到
            PcGuard g = new PcGuard(c, st);
            List<String> notes = new ArrayList<>();
            g.notify = notes::add;
            for (String cmd : cmds) {
                PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", cmd));
                assertEquals(l + " " + cmd, LinkPolicy.R_SCRIPT_GENERATED, reason(o));
                assertFalse(o.reply.ok);
                String rel = String.valueOf(o.reply.data.get("path"));
                File f = new File(c.workspaceDir, rel);
                assertTrue(rel, rel.startsWith("scripts/") && rel.endsWith(".ps1") && f.isFile());
                String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                assertTrue("BOM + 中文头注释", body.startsWith("\uFEFF# 由 OneSay 远程指令生成，没有执行"));
                assertTrue(body.contains(cmd));
                assertTrue(o.reply.text.contains("已生成脚本，需你自己执行"));
            }
            assertEquals("A 类从不问、从不执行", 0, st.asked.size());
            assertEquals(cmds.length, notes.size());
            assertNotNull(g.builtin("sys_exec", m("command", "x")));
            assertFalse("builtin 也不执行系统命令", g.builtin("sys_exec", m("command", "x")).ok);
        }
    }

    @Test public void selfGrantRefusedAtAllLevels() throws Exception {
        String[] cmds = {"icacls C:\\Users\\me\\OneSay /grant Everyone:F", "Add-MpPreference -ExclusionPath C:\\Users\\me\\OneSay",
                "net localgroup administrators onesay /add", "takeown /f C:\\Windows\\System32\\x.dll", "pm grant com.onesay.assistant android.permission.READ_SMS",
                "appops set com.onesay.assistant SYSTEM_ALERT_WINDOW allow", "dpm set-active-admin com.onesay/.Admin"};
        for (LinkPolicy.Level l : LinkPolicy.Level.values()) {
            PcGuard g = new PcGuard(cfg(l.id), new Stub());
            for (String cmd : cmds) {
                assertEquals(cmd, LinkPolicy.Cat.SELF_GRANT, LinkPolicy.classifyShell(LinkPolicy.Target.PC, cmd));
                assertEquals(l + " " + cmd, LinkPolicy.R_SCRIPT_GENERATED, reason(g.check("phone", "sys_exec", m("command", cmd))));
            }
        }
    }

    @Test public void fullAccessOnlyAddsWorkspaceScriptsAndThirdPartyApps() throws Exception {
        Config c = cfg("full");
        Stub st = new Stub();
        PcGuard g = new PcGuard(c, st);
        st.answer = PcGuard.TIMEOUT;
        assertEquals("完全访问运行脚本仍每次确认", LinkPolicy.R_CONFIRM_TIMEOUT, reason(g.check("phone", "shortcut_run", m("name", "x"))));
        assertTrue(st.asked.get(0).contains("运行工作区脚本"));
        assertNull(g.check("phone", "app_close", m("name", "notepad")).reply);
        assertEquals(LinkPolicy.R_SYSTEM_APP, reason(g.check("phone", "app_close", m("name", "explorer"))));
        assertEquals("B 类在完全访问也要确认", LinkPolicy.R_CONFIRM_TIMEOUT, reason(g.check("phone", "volume", m("action", "mute"))));
        assertEquals("A 类完全访问也不放开", LinkPolicy.R_SCRIPT_GENERATED, reason(g.check("phone", "sys_exec", m("command", "reg delete HKCU\\X /f"))));
        assertEquals("普通命令电脑上也不执行", LinkPolicy.R_SCRIPT_GENERATED, reason(g.check("phone", "sys_exec", m("command", "dir C:\\"))));
        assertEquals("工作区以外不放开", LinkPolicy.R_SCRIPT_GENERATED, reason(g.check("phone", "file_write", m("path", "C:\\Windows\\x.txt", "text", "x"))));
        List<String> heads = new ArrayList<>();
        g.notify = heads::add;
        assertNull(g.check("phone", "open_url", m("url", "https://example.net/")).reply);
        assertEquals("不在信任列表：提醒一下", 1, heads.size());
    }

    @Test public void readOnly() throws Exception {
        PcGuard g = new PcGuard(cfg("read_only"), new Stub());
        assertNull(g.check("phone", "ping", null).reply);
        assertNull(g.check("phone", "info", null).reply);
        assertNull(g.check("phone", "caps", null).reply);
        for (String c : new String[]{"screenshot", "lock", "media", "open_url", "open_app", "clipboard_set", "file_write"})
            assertEquals(c, LinkPolicy.R_DENIED_BY_LEVEL, reason(g.check("phone", c, m("url", "https://a.com", "name", "x", "text", "x", "path", "a.txt"))));
        assertEquals("read_only", g.decorate(null).get("level"));
    }

    @Test public void workspaceWritesAndContainment() throws Exception {
        Config c = cfg("auto");
        PcGuard g = new PcGuard(c, new Stub());
        assertNull(g.check("phone", "file_write", m("path", "notes/todo.txt", "text", "买牛奶")).reply);
        assertTrue(g.builtin("file_write", m("path", "notes/todo.txt", "text", "买牛奶")).ok);
        assertEquals("买牛奶", Files.readString(Path.of(c.workspaceDir, "notes", "todo.txt")));
        for (String bad : new String[]{"../evil.txt", "notes/../../evil.txt", "C:\\Windows\\win.ini", "C:evil.txt", "\\\\server\\share\\x.txt", "//server/share/x",
                "\\\\?\\C:\\x", "/etc/passwd", "NUL", "a.txt:ads"}) {
            assertEquals(bad, LinkPolicy.Cat.OUTSIDE_WRITE, PcGuard.category("file_write", m("path", bad), new File(c.workspaceDir)));
            assertEquals(bad, LinkPolicy.R_SCRIPT_GENERATED, reason(g.check("phone", "file_write", m("path", bad, "text", "x"))));
            assertFalse(g.builtin("file_write", m("path", bad, "text", "x")).ok);
        }
        assertFalse(new File(new File(c.workspaceDir).getParentFile(), "evil.txt").exists());
        // 符号链接跳出工作区
        Path outside = Files.createTempDirectory("pcout");
        try {
            Files.createSymbolicLink(Path.of(c.workspaceDir, "out"), outside);
            assertEquals(LinkPolicy.Cat.OUTSIDE_WRITE, PcGuard.category("file_write", m("path", "out/x.txt"), new File(c.workspaceDir)));
            assertFalse(g.builtin("file_write", m("path", "out/x.txt", "text", "x")).ok);
            assertFalse(Files.exists(outside.resolve("x.txt")));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // 文件系统不支持符号链接：跳过这一段
        }
        Map<String, Object> ls = g.builtin("script_list", m()).data;
        assertNotNull(ls.get("scripts"));
        assertFalse(g.builtin("script_run", m("name", "../x.ps1")).ok);
        assertFalse(g.builtin("script_run", m("name", "nope.ps1")).ok);
    }

    @Test public void workspaceDefaultPath() throws Exception {
        assertEquals("C:\\Users\\me\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\me", "C:\\ProgramData"));
        assertEquals("C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\王小明", "C:\\ProgramData"));
        Config c = new Config(Files.createTempFile("pcw", ".properties"));
        c.workspaceDir = "";
        assertTrue(c.workspace().toString().replace('\\', '/').endsWith("OneSay/workspace"));
        c.pcLevel = "full";
        c.trusted = "a.com,b.com";
        c.save();
        Config back = Config.load(c.file);
        assertEquals("full", back.pcLevel);
        assertEquals("a.com,b.com", back.trusted);
        assertEquals("默认 自动", "auto", new Config(Files.createTempFile("pcw2", ".properties")).pcLevel);
    }

    @Test public void confirmsRegistryTimeoutIsDeny() throws Exception {
        PcGuard.Confirms cf = new PcGuard.Confirms();
        long t0 = System.currentTimeMillis();
        assertEquals(PcGuard.TIMEOUT, cf.ask("phone", "volume", "调音量", 200));
        assertTrue(System.currentTimeMillis() - t0 >= 150);
        assertEquals(0, cf.list().size());
        Thread t = new Thread(() -> {
            for (int i = 0; i < 100 && cf.list().isEmpty(); i++) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            Map<?, ?> x = (Map<?, ?>) cf.list().get(0);
            assertEquals("调音量", x.get("detail"));
            cf.resolve(((Number) x.get("id")).intValue(), true);
        });
        t.start();
        assertEquals(PcGuard.ALLOW, cf.ask("phone", "volume", "调音量", 5000));
        t.join();
        assertFalse("过期的编号不能再点", cf.resolve(1, true));
    }

    /** 端到端：手机（另一端）经局域网下令，电脑回带原因码的签名结果。 */
    @Test public void endToEndReasonCodes() throws Exception {
        int port = LanTest.freePortPair();
        Config pcCfg = LanTest.pc(port);
        pcCfg.workspaceDir = Files.createTempDirectory("pcws-e2e").toFile().getCanonicalPath();
        StringBuilder log = new StringBuilder();
        Agent agent = new Agent(pcCfg, new Main.TestActions(), s -> { synchronized (log) { log.append(s).append('\n'); } });
        agent.confirms.onNew = d -> { };
        agent.start();
        Thread.sleep(300);
        Path out = Files.createTempDirectory("e2e");
        try {
            Config cli = LanTest.client(pcCfg, "127.0.0.1:" + port);
            assertEquals(0, Cli.send(cli, "pc-lan", "echo", m("text", "hi"), out, 10));
            assertEquals("系统命令只生成脚本 = 没执行", 1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "reg add HKCU\\Software\\X /f"), out, 10));
            File[] sc = new File(pcCfg.workspaceDir, "scripts").listFiles();
            assertNotNull(sc);
            assertEquals(1, sc.length);
            assertEquals("自动档不让跑快捷指令", 1, Cli.send(cli, "pc-lan", "shortcut_run", m("name", "x"), out, 10));
            assertEquals(1, Cli.send(cli, "pc-lan", "open_url", m("url", "http://10.0.0.1/"), out, 10));
            assertEquals(0, Cli.send(cli, "pc-lan", "file_write", m("path", "inbox/a.txt", "text", "来自手机"), out, 10));
            assertEquals("来自手机", Files.readString(Path.of(pcCfg.workspaceDir, "inbox", "a.txt")));
            String l = log.toString();
            assertTrue(l, l.contains("没执行 sys_exec") && l.contains(LinkPolicy.R_SCRIPT_GENERATED));
            assertTrue(l, l.contains("没执行 shortcut_run") && l.contains(LinkPolicy.R_DENIED_BY_LEVEL));
            assertTrue(l, l.contains(LinkPolicy.R_UNTRUSTED_URL));
        } finally {
            agent.stop();
        }
    }

    @Test public void reasonTextForChat() {
        Map<String, Object> d = new TreeMap<>();
        d.put("reason", LinkPolicy.R_CONFIRM_TIMEOUT);
        assertEquals("手机上 30 秒没人确认，已拒绝（对方 30 秒内没确认，已拒绝）", PhoneCmds.withReason(new Actions.Result(false, "手机上 30 秒没人确认，已拒绝", d)));
        d.put("reason", LinkPolicy.R_SCRIPT_GENERATED);
        assertEquals("已生成脚本，需你自己执行：scripts/a.sh", PhoneCmds.withReason(new Actions.Result(false, "已生成脚本，需你自己执行：scripts/a.sh", d)));
        d.put("reason", LinkPolicy.R_UNTRUSTED_URL);
        assertEquals("网址没通过安全检查", PhoneCmds.withReason(new Actions.Result(false, "", d)));
        assertEquals("好", PhoneCmds.withReason(Actions.Result.ok("好")));
        for (String code : new String[]{"denied_by_level", "needs_confirm_timeout", "untrusted_url", "blocked_app", "clipboard_rejected", "script_generated"})
            assertFalse(code, LinkPolicy.reasonZh(code).isEmpty());
        assertEquals(40_000L, PhoneCmds.timeoutMs("open_url"));
        assertEquals(8_000L, PhoneCmds.timeoutMs("status"));
        assertTrue(PhoneCmds.ALL.contains("flashlight") && PhoneCmds.ALL.contains("sys_exec"));
    }
}
