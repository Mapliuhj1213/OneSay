package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * [chat-agent-1118] 电脑端「对话」不执行任务（A/B 实验 OneSay-A：回「我无法直接操作电脑」+ 手动步骤；「继续。」被当成播放 / 暂停）。
 * 覆盖：任务循环（工具调用 → 执行 → 结果回填 → 收工）、推活打回、拒绝 / 超时即停、重复 / 步数上限、停止；
 * 「继续」接着任务走、长句不被固定指令劫持；系统提示；只读命令判定；本机对话走同一条安全链（只读直接做、改动要确认、联网改确认、硬规则不变）。
 */
public class ChatAgent1118Test {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
    }

    static Path tmp() throws Exception { return Files.createTempDirectory("ca1118"); }

    static Llm.Turn call(String id, String name, String argsJson) {
        Map<String, Object> echo = Llm.msg("assistant", "");
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), echo);
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    /** 按顺序吐出几轮回复的假模型，记下每轮收到的消息。 */
    static final class Script implements ChatAgent.Brain {
        final List<Llm.Turn> turns;
        final List<List<Map<String, Object>>> seen = new ArrayList<>();
        int i = 0;

        Script(Llm.Turn... t) { turns = new ArrayList<>(List.of(t)); }

        @Override public Llm.Turn next(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
            seen.add(new ArrayList<>(messages));
            assertFalse("带着工具", tools.isEmpty());
            return turns.get(Math.min(i++, turns.size() - 1));
        }
    }

    static class Rec implements ChatAgent.Exec {
        final List<String> calls = new ArrayList<>();
        Actions.Result next = Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "onesay-test", "exit", 0L)));

        @Override public Actions.Result run(String cmd, Map<String, Object> args) {
            calls.add(cmd + " " + args);
            return next;
        }
    }

    static List<Map<String, Object>> ctx(String user) {
        return new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", user)));
    }

    // ------------------------------------------------------------------ 循环

    @Test public void toolCallIsExecutedAndResultFedBack() {
        Script b = new Script(call("c1", "run_command", "{\"command\":\"New-Item -ItemType Directory $env:USERPROFILE\\\\Desktop\\\\onesay-test\"}"),
                say("建好了，文件夹是空的。"));
        Rec ex = new Rec();
        List<String> steps = new ArrayList<>();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("在桌面新建一个文件夹叫 onesay-test"), b, ex, steps::add);
        assertEquals("done", o.status);
        assertEquals(1, o.steps);
        assertEquals("建好了，文件夹是空的。", o.text);
        assertEquals(1, ex.calls.size());
        assertTrue(ex.calls.get(0).startsWith("sys_exec "));
        assertTrue(ex.calls.get(0).contains("onesay-test"));
        // 第二轮模型看到了 tool 结果（带 tool_call_id、ok、输出）
        List<Map<String, Object>> second = b.seen.get(1);
        Map<String, Object> last = second.get(second.size() - 1);
        assertEquals("tool", last.get("role"));
        assertEquals("c1", last.get("tool_call_id"));
        assertTrue(String.valueOf(last.get("content")).contains("\"ok\":true"));
        assertTrue(String.valueOf(last.get("content")).contains("onesay-test"));
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).startsWith("▶ 执行命令："));
    }

    @Test public void puntingFirstReplyIsSentBackOnce() {
        Script b = new Script(say("我无法直接操作电脑，下载、安装到 MuMu 并打开这类流程需要你手动完成。"),
                call("c1", "run_command", "{\"command\":\"Get-ChildItem $HOME\"}"), say("好了"));
        Rec ex = new Rec();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("帮我下载一个游戏装到模拟器里"), b, ex, t -> { });
        assertEquals("done", o.status);
        assertEquals("打回后真的去执行了", 1, ex.calls.size());
        String nudge = String.valueOf(b.seen.get(1).get(b.seen.get(1).size() - 1).get("content"));
        assertTrue(nudge.contains("能用工具直接在这台电脑上操作"));
        // 只打回一次：再推就照实回
        Script b2 = new Script(say("我无法直接操作电脑"), say("我无法直接操作电脑，需要你手动"));
        ChatAgent.Outcome o2 = new ChatAgent().run(ctx("x"), b2, new Rec(), t -> { });
        assertEquals("done", o2.status);
        assertEquals(2, b2.seen.size());
    }

    @Test public void deniedConfirmStopsWithoutRetrying() {
        Script b = new Script(call("c1", "run_command", "{\"command\":\"Remove-Item x\"}"), call("c2", "run_command", "{\"command\":\"del x\"}"));
        Rec ex = new Rec();
        ex.next = new Actions.Result(false, "电脑上拒绝了", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED)));
        ChatAgent.Outcome o = new ChatAgent().run(ctx("删掉 x"), b, ex, t -> { });
        assertEquals("denied", o.status);
        assertEquals("拒绝后不换写法绕过", 1, ex.calls.size());
        assertEquals(1, b.seen.size());
        assertTrue(o.text.contains("拒绝"));
    }

    @Test public void confirmTimeoutStopsAndSaysHowToContinue() {
        Script b = new Script(call("c1", "run_command", "{\"command\":\"New-Item a\"}"));
        Rec ex = new Rec();
        ex.next = new Actions.Result(false, "没人确认", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_TIMEOUT)));
        ChatAgent.Outcome o = new ChatAgent().run(ctx("x"), b, ex, t -> { });
        assertEquals("timeout", o.status);
        assertTrue(o.text.contains("继续"));
    }

    @Test public void identicalStepRepeatedStopsAtThree() {
        Script b = new Script(call("c", "run_command", "{\"command\":\"Get-Process\"}"));
        Rec ex = new Rec();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("x"), b, ex, t -> { });
        assertEquals("loop", o.status);
        assertEquals(ChatAgent.MAX_REPEAT - 1, ex.calls.size());
    }

    @Test public void stepLimitAsksToContinue() {
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> call("c" + n.get(), "run_command", "{\"command\":\"Get-Date -Format " + n.incrementAndGet() + "\"}");
        Rec ex = new Rec();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("x"), b, ex, t -> { });
        assertEquals("max_steps", o.status);
        assertEquals(ChatAgent.MAX_STEPS, ex.calls.size());
        assertTrue(o.text.contains("继续"));
    }

    @Test public void cancelStops() {
        ChatAgent a = new ChatAgent();
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> call("c", "run_command", "{\"command\":\"Get-Date -Format " + n.incrementAndGet() + "\"}");
        Rec ex = new Rec() {
            @Override public Actions.Result run(String cmd, Map<String, Object> args) {
                Actions.Result r = super.run(cmd, args);
                if (calls.size() == 2) a.cancel.set(true);
                return r;
            }
        };
        ChatAgent.Outcome o = a.run(ctx("x"), b, ex, t -> { });
        assertEquals("stopped", o.status);
        assertEquals(2, ex.calls.size());
    }

    @Test public void unknownToolAndBadArgsAreReportedToModel() {
        Script b = new Script(call("c1", "format_disk", "{}"), call("c2", "run_command", "not json"), say("算了"));
        Rec ex = new Rec();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("x"), b, ex, t -> { });
        assertEquals("done", o.status);
        assertTrue(ex.calls.isEmpty());
        assertTrue(String.valueOf(b.seen.get(1).get(b.seen.get(1).size() - 1).get("content")).contains("不认识的工具"));
    }

    @Test public void toolMappingAndTimeoutClamp() {
        Object[] m = ChatAgent.map(new Llm.ToolCall("1", "run_command", "{\"command\":\"Get-Date\",\"timeout_sec\":5000}"));
        assertEquals("sys_exec", m[0]);
        assertEquals(900L, ((Map<?, ?>) m[1]).get("timeout_sec"));
        assertEquals("open_url", ChatAgent.map(new Llm.ToolCall("1", "open_url", "{\"url\":\"https://example.com\"}"))[0]);
        assertEquals("file_write", ChatAgent.map(new Llm.ToolCall("1", "write_file", "{\"path\":\"a.txt\",\"text\":\"hi\"}"))[0]);
        assertNull(ChatAgent.map(new Llm.ToolCall("1", "run_command", "{\"command\":\"  \"}")));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> t : ChatAgent.tools()) names.add(String.valueOf(((Map<?, ?>) t.get("function")).get("name")));
        assertEquals(List.of("run_command", "open_url", "write_file"), names);
    }

    @Test public void parseTurnReadsToolCalls() throws Exception {
        Map<String, Object> o = Json.obj("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"Get-Date\\\"}\"}}]}}]}");
        Llm.Turn t = Llm.parseTurn(o);
        assertNull(t.content);
        assertEquals(1, t.calls.size());
        assertEquals("call_1", t.calls.get(0).id);
        assertEquals("run_command", t.calls.get(0).name);
        assertTrue(t.calls.get(0).arguments.contains("Get-Date"));
        assertNotNull("原样带回 tool_calls", t.message.get("tool_calls"));
        assertEquals("你好", Llm.parseTurn(Json.obj("{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}")).content);
    }

    // ------------------------------------------------------------------ 提示词 / 路由

    @Test public void systemPromptNoLongerSaysItCannotOperateThePc() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Chat c = new Chat(new Config(tmp().resolve("c.properties")), s, new Main.TestActions(), List::of, x -> { });
        assertTrue("没接执行能力时如实说", c.systemPrompt().contains("没有执行工具"));
        c.pcExec = (cmd, a) -> Actions.Result.ok("x");
        String p = c.systemPrompt();
        assertFalse(p.contains("不能直接操作电脑"));
        assertTrue(p.contains("run_command"));
        assertTrue(p.contains("不要只给教程"));
        assertTrue(p.contains("确认框"));
        assertTrue(p.contains("继续"));
        for (String bad : new String[] {"Bixby", "bixby", "三星", "小布"}) assertFalse(p.contains(bad));
    }

    static Chat chat(Store s, Config c, ChatAgent.Brain b, ChatAgent.Exec ex) {
        Chat ch = new Chat(c, s, new Main.TestActions(), List::of, x -> { });
        ch.brain = b;
        ch.pcExec = ex;
        return ch;
    }

    @Test public void continueAfterTaskGoesToModelNotMediaKey() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Script b = new Script(say("我先看一下 MuMu 装在哪。"), call("c1", "run_command", "{\"command\":\"Get-ChildItem $HOME\"}"), say("找到了。"));
        Rec ex = new Rec();
        Chat ch = chat(s, new Config(tmp().resolve("c.properties")), b, ex);
        ch.send("帮我下载一个单机安卓游戏 Mindustry，在电脑上的 MuMu 模拟器里本地安装并打开到主菜单。", "text");
        Map<String, Object> r = ch.send("继续。", "text");
        assertNotEquals("cmd", r.get("via"));
        assertNotEquals("播放/暂停", r.get("text"));
        assertEquals(1, ex.calls.size());
        // 「继续」那轮模型看到了原来的目标
        StringBuilder all = new StringBuilder();
        for (Map<String, Object> m : b.seen.get(1)) all.append(m.get("content")).append('|');
        assertTrue(all.toString().contains("Mindustry"));
        assertTrue(all.toString().endsWith("继续。|"));
        // 任务步骤写进了对话（via=step），下一次「继续」能看到做到哪了
        boolean step = false;
        for (Map<String, Object> m : s.list("chat")) if ("step".equals(m.get("via"))) step = true;
        assertTrue(step);
        List<Map<String, Object>> c2 = ch.context();
        StringBuilder all2 = new StringBuilder();
        for (Map<String, Object> m : c2) all2.append(m.get("content")).append('|');
        assertTrue(all2.toString().contains("▶ 执行命令：Get-ChildItem"));
    }

    @Test public void continueWithoutTaskStillControlsMedia() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Script b = new Script(say("不该叫到我"));
        Chat ch = chat(s, new Config(tmp().resolve("c.properties")), b, new Rec());
        Map<String, Object> r = ch.send("继续", "text");
        assertEquals("没有任务时「继续」还是固定指令（媒体键，测试替身不支持 media）", "cmd", r.get("via"));
        assertEquals(0, b.seen.size());
        // 上一条是固定指令（刚暂停了音乐）：「继续」还是媒体键
        Map<String, Object> r2 = ch.send("继续播放", "text");
        assertEquals("cmd", r2.get("via"));
    }

    @Test public void continuationWords() {
        for (String w : new String[] {"继续", "继续。", "继续吧", "接着做", "好的，继续", "可以继续", "下一步", "continue", "go on"})
            assertTrue(w, Intent.isContinuation(w));
        for (String w : new String[] {"继续播放", "暂停", "继续下载那个文件到D盘", "你好"}) assertFalse(w, Intent.isContinuation(w));
        // 规则版旧行为不变（没有模型时）
        assertEquals("media", Intent.parse("继续", Map.of(), List.of()).cmd);
    }

    @Test public void longTaskIsNotHijackedByKeywordRules() throws Exception {
        String task = "帮我截图然后把截图存到桌面上的 onesay-test 文件夹里";
        assertEquals("规则版（没有模型）旧行为不变", "screenshot", Intent.parse(task, Map.of(), List.of()).cmd);
        assertNull("能执行任务时长句交给模型", Intent.parse(task, Map.of(), List.of(), true).cmd);
        assertEquals("短句照旧", "screenshot", Intent.parse("截图", Map.of(), List.of(), true).cmd);
        assertEquals("lock", Intent.parse("锁屏", Map.of(), List.of(), true).cmd);
        assertEquals("关机口令照旧", "shutdown", Intent.parse("确认关机", Map.of(), List.of(), true).cmd);
        assertEquals("长句里的手机指令照旧", "notify", Intent.parse("在手机上提醒我：明天下午三点去取快递", Map.of(), List.of(), true).phoneCmd);
        assertNull(Intent.parse("在桌面新建一个文件夹叫 onesay-test 并告诉我里面有什么", Map.of(), List.of(), true).cmd);

        Store s = new Store(tmp().resolve("state.json"));
        Script b = new Script(say("好的"));
        Chat ch = chat(s, new Config(tmp().resolve("c.properties")), b, new Rec());
        Map<String, Object> r = ch.send("把音量调大一点然后打开B站帮我搜一下 Mindustry 教程", "text");
        assertNotEquals("cmd", r.get("via"));
        assertEquals(1, b.seen.size());
    }

    @Test public void modelErrorOnFirstTurnIsAnErrorMessage() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Chat ch = chat(s, new Config(tmp().resolve("c.properties")), (m, t) -> { throw new Exception("大模型超时"); }, new Rec());
        Map<String, Object> r = ch.send("帮我查一下电脑上装的 MuMu 是什么版本", "text");
        assertEquals("error", r.get("via"));
        assertEquals("大模型超时", r.get("text"));
    }

    // ------------------------------------------------------------------ 只读判定

    @Test public void readOnlyCommands() {
        String[] ok = {
                "Get-ChildItem \"$env:USERPROFILE\\Desktop\\onesay-test\" -Force",
                "Get-ItemProperty HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\* | ? DisplayName -like '*MuMu*' | select DisplayName,DisplayVersion",
                "Test-Path C:\\Program Files\\Netease",
                "gci $HOME\\Downloads | sort LastWriteTime | select -Last 5",
                "Get-Process | where Name -like 'MuMu*' | ft Name,Id",
                "Get-Content 'C:\\a\\Remove-Item.txt'",
                "where.exe adb",
                "Get-ChildItem C:\\x\\onesay-test",
        };
        for (String c : ok) assertTrue(c, LocalReadOnly.ok(c));
        String[] no = {
                "Remove-Item C:\\x", "New-Item -ItemType Directory x", "Get-ChildItem x; Remove-Item x", "Get-ChildItem x | Remove-Item",
                "(Get-Item x).Delete()", "Get-ChildItem $(Remove-Item x)", "rm x", "Get-ChildItem x | % { del $_ }", "Start-Process notepad",
                "Get-CimInstance Win32_Product", "iwr https://example.com -OutFile a.apk", "Get-Content x > y", "[IO.File]::Delete('x')",
                "$a = 1", "Get-ChildItem $profile", "adb shell rm -rf /sdcard", "Get-ChildItem x -Exclude y | Invoke-Item", "cmd /c dir",
                "Get-Date `\n; Stop-Computer", "", "Get-Content x -Encoding utf8 | Set-Content y",
        };
        for (String c : no) assertFalse(c, LocalReadOnly.ok(c));
    }

    // ------------------------------------------------------------------ 本机对话走同一条安全链

    static final class Stub implements PcGuard.Confirmer {
        int answer = PcGuard.ALLOW;
        final List<String> asked = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            asked.add(from + "|" + cmd + "|" + detail);
            return answer;
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            kinds.add(rememberKind);
            return ask(from, cmd, detail, timeoutMs);
        }
    }

    static Config cfg() throws Exception {
        Config c = new Config(Files.createTempFile("ca", ".properties"));
        c.deviceId = "pc-ca";
        c.pairKey = Protocol.newKey();
        c.workspaceDir = Files.createTempDirectory("caws").toFile().getCanonicalPath();
        return c;
    }

    static PcGuard.Outcome localCheck(PcGuard g, String command) {
        PcGuard.LOCAL.set(Boolean.TRUE);
        try {
            return g.check(ChatAgent.FROM, "sys_exec", new TreeMap<>(Map.of("command", command)));
        } finally {
            PcGuard.LOCAL.set(Boolean.FALSE);
        }
    }

    @Test public void localReadOnlyRunsWithoutConfirmButWritesAsk() throws Exception {
        Stub st = new Stub();
        PcGuard g = new PcGuard(cfg(), st);
        PcGuard.Outcome r = localCheck(g, "Get-ChildItem $HOME\\Desktop");
        assertNull(r.reply);
        assertNotNull(r.pass);
        assertTrue("只读不弹确认", st.asked.isEmpty());
        PcGuard.Outcome w = localCheck(g, "New-Item -ItemType Directory $HOME\\Desktop\\onesay-test");
        assertNotNull("确认后放行", w.pass);
        assertEquals(1, st.asked.size());
        assertTrue(st.asked.get(0).startsWith(ChatAgent.FROM + "|sys_exec|"));
        assertTrue("卡上写完整命令", st.asked.get(0).contains("New-Item -ItemType Directory"));
        // 远程（手机）发同一条只读命令：不走本机快速通道，照旧确认
        Stub st2 = new Stub();
        PcGuard g2 = new PcGuard(cfg(), st2);
        g2.check("phone-x", "sys_exec", new TreeMap<>(Map.of("command", "Get-ChildItem $HOME\\Desktop")));
        assertEquals(1, st2.asked.size());
    }

    @Test public void localDeniedConfirmIsReported() throws Exception {
        Stub st = new Stub();
        st.answer = PcGuard.DENY;
        PcGuard.Outcome o = localCheck(new PcGuard(cfg(), st), "Remove-Item $HOME\\Desktop\\onesay-test");
        assertNotNull(o.reply);
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, o.reply.data.get("reason"));
    }

    @Test public void localDownloadAsksEachTimeInsteadOfHardBlock() throws Exception {
        String dl = "Invoke-WebRequest https://github.com/Anuken/Mindustry/releases/download/v146/Mindustry.apk -OutFile $HOME\\Downloads\\m.apk";
        // 手机远程：沙箱断网照旧拦（不变）
        Stub rs = new Stub();
        PcGuard.Outcome remote = new PcGuard(cfg(), rs).check("phone-x", "sys_exec", new TreeMap<>(Map.of("command", dl)));
        assertNotNull(remote.reply);
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, remote.reply.data.get("reason"));
        // 本机对话：改成确认，卡上写「要联网」，不给「以后这类不用问」
        Stub st = new Stub();
        PcGuard.Outcome o = localCheck(new PcGuard(cfg(), st), dl);
        assertNotNull(o.pass);
        assertEquals(1, st.asked.size());
        assertTrue(st.asked.get(0).contains("联网"));
        assertTrue("联网这类不能记住", st.kinds.isEmpty() || st.kinds.get(0).isEmpty());
        // 审批「从不询问」：照旧拒绝
        Config never = cfg();
        never.pcApproval = "never";
        Stub sn = new Stub();
        PcGuard.Outcome n = localCheck(new PcGuard(never, sn), dl);
        assertNotNull(n.reply);
        assertTrue(sn.asked.isEmpty());
    }

    @Test public void localHardRulesStillHold() throws Exception {
        Stub st = new Stub();
        PcGuard g = new PcGuard(cfg(), st);
        PcGuard.Outcome up = localCheck(g, "Start-Process powershell -Verb RunAs");
        assertNotNull(up.reply);
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, up.reply.data.get("reason"));
        PcGuard.Outcome adm = localCheck(g, "Set-ItemProperty HKLM:\\SOFTWARE\\X -Name a -Value 1");
        assertNotNull(adm.reply);
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, adm.reply.data.get("reason"));
        PcGuard.Outcome enc = localCheck(g, "powershell -enc ZQBjAGgAbwAgADEA");
        assertNotNull(enc.reply);
        PcGuard.Outcome ro = localCheck(g, "Get-ChildItem \\\\evil-host\\share");
        assertTrue("UNC 共享（联网）不算只读直接做", ro.reply != null || !st.asked.isEmpty());
        assertTrue("硬规则不弹确认", st.asked.size() <= 1);
    }

    @Test public void runLocalGoesThroughGuardAndHandlesAdminSession() throws Exception {
        Config c = cfg();
        Agent a = new Agent(c, new Main.TestActions(), x -> { });
        Stub st = new Stub();
        a.guard.confirmer = st;
        // 读工作区（READ_PRIVATE）直接做
        Actions.Result r = a.runLocal("file_list", new TreeMap<>());
        assertNotNull(r);
        assertTrue(st.asked.isEmpty());
        // 写工作区里的文件：直接写
        Actions.Result w = a.runLocal("file_write", new TreeMap<>(Map.of("path", "notes/a.txt", "text", "hi")));
        assertTrue(w.text, w.ok);
        assertEquals("hi", Files.readString(Path.of(c.workspaceDir, "notes", "a.txt"), StandardCharsets.UTF_8));
        // OneSay 是管理员时（整个登录会话就是管理员）：只读照常直接做；改动类每一步确认，卡上写明带管理员权限，且不能记住
        SafeExec.reset(() -> true, null);
        Stub st2 = new Stub();
        a.guard.confirmer = st2;
        Actions.Result e = a.runLocal("sys_exec", new TreeMap<>(Map.of("command", "Get-ChildItem $HOME")));
        assertNotEquals("不再一律拒绝", LinkPolicy.R_PLUGIN_BLOCKED, e.data == null ? null : e.data.get("reason"));
        assertTrue("只读不弹确认", st2.asked.isEmpty());
        st2.answer = PcGuard.DENY;
        Actions.Result w2 = a.runLocal("file_write", new TreeMap<>(Map.of("path", "notes/b.txt", "text", "hi")));
        assertFalse(w2.ok);
        assertEquals(1, st2.asked.size());
        assertTrue(st2.asked.get(0).contains("管理员权限"));
        assertTrue("管理员下不能记住", st2.kinds.isEmpty() || st2.kinds.get(0).isEmpty());
        Actions.Result up = a.runLocal("sys_exec", new TreeMap<>(Map.of("command", "Start-Process cmd -Verb RunAs")));
        assertEquals("提权照旧拦", LinkPolicy.R_PLUGIN_BLOCKED, up.data.get("reason"));
        // 手机远程照旧：管理员时降不了权就拒（不变）
        SafeExec.REMOTE.set(Boolean.TRUE);
        try {
            assertTrue(SafeExec.elevated());
            try { SafeExec.check(new ProcessBuilder("whoami")); fail(); } catch (SafeExec.Refused ok) { }
        } finally { SafeExec.REMOTE.set(Boolean.FALSE); }
        assertFalse("线程标记复位", PcGuard.LOCAL.get());
        assertFalse(SafeExec.REMOTE.get());
    }

    @Test public void localConfirmIsNotSentToPhone() throws Exception {
        Agent a = new Agent(cfg(), new Main.TestActions(), x -> { });
        PcGuard.PhoneOffer o = new PcGuard.PhoneOffer(1, ChatAgent.FROM, "sys_exec", "d", "tok", false, "", System.currentTimeMillis() + 1000);
        assertFalse(a.offerToPhone(o));
    }

    @Test public void execLocalCapturesOutputAndTimesOut() throws Exception {
        if (SafeExec.WINDOWS) return;
        PcGuard g = new PcGuard(cfg(), new Stub());
        SafeExec.reset(() -> false, null);
        Actions.Result r = g.execLocal("echo onesay-test", g.workspace(), 30);
        assertTrue(r.ok);
        assertEquals("onesay-test", r.data.get("output"));
        Actions.Result big = g.execLocal("yes x | head -c 200000", g.workspace(), 30);
        assertTrue("大输出不卡住", big.ok);
        assertTrue(String.valueOf(big.data.get("output")).contains("截断"));
        long t0 = System.currentTimeMillis();
        Actions.Result slow = g.execLocal("sleep 30", g.workspace(), 5);
        assertFalse(slow.ok);
        assertTrue(slow.text.contains("超过 5 秒"));
        assertTrue(System.currentTimeMillis() - t0 < 15000);
        Actions.Result bad = g.execLocal("exit 3", g.workspace(), 10);
        assertFalse(bad.ok);
        assertEquals(3L, bad.data.get("exit"));
    }

    // ------------------------------------------------------------------ 界面

    @Test public void uiShowsLocalConfirmTitleStepsAndStop() throws Exception {
        String html;
        try (java.io.InputStream in = App.class.getResourceAsStream("/onesay/ui/index.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(html.contains("OneSay 要在这台电脑上执行操作，需要你确认"));
        assertTrue(html.contains("手机请求，需要你确认"));
        assertTrue(html.contains("chat_stop"));
        assertTrue(html.contains("x.via==='step'"));
        String src = Files.readString(Path.of("src/main/java/com/onesay/desktop/ChatAgent.java"), StandardCharsets.UTF_8);
        for (String bad : new String[] {"Bixby", "bixby", "三星", "小布"}) assertFalse(src.contains(bad));
    }

    // ------------------------------------------------------------------ DeepSeek 思考模式 / 重试 / 参数错误回灌

    static final String R1 = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"先查日期\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"Get-Date\\\"}\"}}]}}]}";

    @Test public void secondToolRoundRequestContainsPreviousReasoningContent() throws Exception {
        Llm.Turn t1 = Llm.parseTurn(Json.obj(R1));
        assertEquals("整条 assistant 消息保留 reasoning_content", "先查日期", t1.message.get("reasoning_content"));
        Script b = new Script(t1, say("今天是 11 月 18 日"));
        new ChatAgent().run(ctx("今天几号"), b, new Rec(), x -> { });
        List<Map<String, Object>> second = b.seen.get(1);
        Map<String, Object> asst = second.get(second.size() - 2);
        assertEquals("assistant", asst.get("role"));
        assertEquals("先查日期", asst.get("reasoning_content"));
        assertNotNull(asst.get("tool_calls"));
        // 序列化成请求体也带着
        String body = Json.stringify(Llm.requestBody("deepseek-chat", second, ChatAgent.tools(), 0.3, false));
        assertTrue(body.contains("\"reasoning_content\":\"先查日期\""));
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""));
        assertFalse("默认不改思考设置", body.contains("\"thinking\""));
        assertTrue(Json.stringify(Llm.requestBody("m", second, null, 0.3, true)).contains("\"thinking\":{\"type\":\"disabled\"}"));
    }

    @Test public void reasoning400IsNotToolsUnsupported() {
        assertEquals(Llm.Bad400.REASONING, Llm.classify400("{\"error\":{\"message\":\"The reasoning_content in the thinking mode must be passed back to the API.\"}}"));
        assertEquals(Llm.Bad400.CONTEXT, Llm.classify400("{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens\"}}"));
        assertEquals(Llm.Bad400.TOOLS, Llm.classify400("{\"error\":\"tools is not supported by this model\"}"));
        assertEquals(Llm.Bad400.OTHER, Llm.classify400("{\"error\":\"bad request\"}"));
        assertEquals(Llm.Bad400.OTHER, Llm.classify400(null));
    }

    @Test public void emptyReplyIsTransient() {
        try {
            Llm.parseTurn(Json.obj("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
            fail();
        } catch (Llm.Transient e) {
            assertTrue(e.getMessage().contains("空回复"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test public void transientModelErrorsAreRetriedOthersNot() {
        AtomicInteger n = new AtomicInteger();
        ChatAgent a = new ChatAgent();
        a.backoffMs = 1;
        ChatAgent.Outcome o = a.run(ctx("x"), (m, t) -> { if (n.incrementAndGet() <= 2) throw new Llm.Transient("大模型超时"); return say("好了"); }, new Rec(), x -> { });
        assertEquals("done", o.status);
        assertEquals(3, n.get());
        AtomicInteger k = new AtomicInteger();
        ChatAgent.Outcome o2 = a.run(ctx("x"), (m, t) -> { k.incrementAndGet(); throw new Llm.Transient("大模型服务暂时出错 503"); }, new Rec(), x -> { });
        assertEquals("error", o2.status);
        assertEquals(1 + 2, k.get());
        AtomicInteger j = new AtomicInteger();
        ChatAgent.Outcome o3 = a.run(ctx("x"), (m, t) -> { j.incrementAndGet(); throw new Exception("大模型 Key 无效或没权限（401）"); }, new Rec(), x -> { });
        assertEquals("error", o3.status);
        assertEquals("Key 错不重试", 1, j.get());
    }

    @Test public void invalidArgsAreFedBackSoModelCanFixThem() {
        Script b = new Script(call("c1", "run_command", "{\"cmd\":\"Get-Date\"}"), call("c2", "run_command", "{oops"), call("c3", "run_command", "{\"command\":\"Get-Date\"}"), say("好了"));
        Rec ex = new Rec();
        ChatAgent.Outcome o = new ChatAgent().run(ctx("x"), b, ex, t -> { });
        assertEquals("done", o.status);
        assertEquals("改对后执行了", 1, ex.calls.size());
        String m1 = String.valueOf(b.seen.get(1).get(b.seen.get(1).size() - 1).get("content"));
        assertTrue(m1.contains("INVALID_ARGS") && m1.contains("command"));
        String m2 = String.valueOf(b.seen.get(2).get(b.seen.get(2).size() - 1).get("content"));
        assertTrue(m2.contains("不是合法 JSON"));
    }

    @Test public void promptIsBuiltFromToolsAndPolicy() {
        String p = ChatAgent.systemPrompt("now", "C:\\ws", "C:\\Users\\a", "", LinkPolicy.Mode.DEFAULT);
        for (Map<String, Object> t : ChatAgent.tools()) assertTrue(p.contains(String.valueOf(((Map<?, ?>) t.get("function")).get("name"))));
        assertTrue(p.contains("工作区可写"));
        assertTrue(p.contains("不是不做的理由"));
        assertTrue(p.contains("确认框"));
        String never = ChatAgent.policyLine(new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.NEVER));
        assertTrue(never.contains("直接拒绝"));
        assertFalse(never.contains("弹确认框交给用户"));
        assertTrue(ChatAgent.policyLine(new LinkPolicy.Mode(LinkPolicy.Sandbox.READ_ONLY, LinkPolicy.Approval.UNTRUSTED)).contains("只能查询"));
    }
}
