package com.onesay.desktop;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * [preview-1120 真机综合测试 2]「装显卡驱动 / Steam 连不上 / 虚拟机全屏」时发现的问题：
 * 只读看 HKLM 卸载表 / hosts 被当成「要管理员」（模型拿不到已装软件，漏掉 VirtualBox）；
 * 同一接口换参数调 18 次、结果一样，原样重复检测抓不到；被拒绝后只剩一句固定话，查到的东西和手动步骤都没给。
 */
public class Preview1120Device2Test {

    static String admin(String cmd) { return LinkPolicy.adminReason(LinkPolicy.normalizeCommand(cmd)); }

    @Test public void readOnlyHklmAndHostsAreNotAdmin() {
        for (String c : new String[] {
                "Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -like '*NVIDIA*' } | Select-Object DisplayName, DisplayVersion | Format-Table -AutoSize",
                "Get-ChildItem HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall | Select-Object -First 5",
                "reg query \"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\" /v ProductName",
                "Get-Content C:\\Windows\\System32\\drivers\\etc\\hosts | Where-Object { $_ -notmatch '^#' }",
                "Select-String -Path C:\\Windows\\System32\\drivers\\etc\\hosts -Pattern steam",
                "Test-Path 'HKLM:\\SOFTWARE\\Oracle\\VirtualBox'; Get-ItemProperty 'HKLM:\\SOFTWARE\\Oracle\\VirtualBox' | Out-String"})
            assertEquals(c, "", admin(c));
        for (String c : new String[] {
                "Set-ItemProperty 'HKLM:\\SOFTWARE\\X' -Name a -Value 1",
                "Get-ItemProperty 'HKLM:\\SOFTWARE\\X'; Remove-Item 'HKLM:\\SOFTWARE\\Y' -Recurse",
                "New-ItemProperty -Path HKLM:\\SOFTWARE\\X -Name a -Value 1",
                "reg add HKLM\\SOFTWARE\\X /v a /d 1 /f",
                "Get-Content C:\\Windows\\System32\\drivers\\etc\\hosts | Set-Content C:\\x.txt",
                "Add-Content C:\\Windows\\System32\\drivers\\etc\\hosts '1.2.3.4 steamcommunity.com'",
                "'1.2.3.4 a.com' >> C:\\Windows\\System32\\drivers\\etc\\hosts",
                "Get-Content C:\\Windows\\System32\\drivers\\etc\\hosts; ac C:\\Windows\\System32\\drivers\\etc\\hosts 'x'",
                "[Microsoft.Win32.Registry]::LocalMachine.CreateSubKey('SOFTWARE\\X')",
                "Get-ItemProperty HKLM:\\SOFTWARE\\X | iex"})
            assertFalse(c, admin(c).isEmpty());
    }

    static Llm.Turn call(String id, String name, String argsJson) {
        Map<String, Object> echo = new TreeMap<>(Llm.msg("assistant", ""));
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), echo);
    }

    static List<Map<String, Object>> ctx() { return new ArrayList<>(List.of(Llm.msg("system", "s"), Llm.msg("user", "查一下最新驱动"))); }

    static ChatAgent guarded() { ChatAgent a = new ChatAgent(); a.progressGuard = true; return a; }

    static String api(int n) { return "{\"command\":\"Invoke-RestMethod -Uri 'https://gfwsl.example.com/services/Lookup.php?pfid=10" + n + "&dch=1' | ConvertTo-Json\",\"description\":\"再查一次\"}"; }

    /** 真机复验：每步换一个站点查（同类计数到不了上限），联网总数到 WEB_HINT 提醒、到 WEB_STOP 停下并收尾。 */
    @Test public void manyDifferentSitesHintThenStop() {
        AtomicInteger n = new AtomicInteger();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> { seen.add(new ArrayList<>(m)); int k = n.incrementAndGet();
            return call("c" + k, "run_command", "{\"command\":\"Invoke-WebRequest -UseBasicParsing -Uri 'https://site" + k + ".example.com/search?q=x'\",\"description\":\"换个站查\"}"); };
        List<String> calls = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, args) -> { calls.add(cmd); return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "页面 " + calls.size(), "exit", 0L))); };
        ChatAgent.Outcome o = guarded().run(ctx(), b, ex, t -> { });
        assertEquals("loop", o.status);
        assertEquals(ChatAgent.WEB_STOP, calls.size());
        assertTrue(o.text, o.text.contains("联网查了"));
        List<Map<String, Object>> hint = seen.get(ChatAgent.WEB_HINT);
        assertTrue(String.valueOf(hint.get(hint.size() - 1).get("content")).contains("别再换站点"));
        List<Map<String, Object>> before = seen.get(ChatAgent.WEB_HINT - 1);
        assertFalse(String.valueOf(before.get(before.size() - 1).get("content")).contains("onesay_note"));
    }

    @Test public void sameFamilySameResultHintsThenStops() {
        AtomicInteger n = new AtomicInteger();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        ChatAgent.Brain b = (m, t) -> { seen.add(new ArrayList<>(m)); return call("c" + n.get(), "run_command", api(n.incrementAndGet())); };
        List<String> calls = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, args) -> { calls.add(cmd); return Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "572.13 | Data Center Driver", "exit", 0L))); };
        ChatAgent.Outcome o = guarded().run(ctx(), b, ex, t -> { });
        assertEquals("loop", o.status);
        assertEquals(ChatAgent.STALE_STOP, calls.size());
        assertTrue(o.text.contains("完全相同的结果"));
        // 第 3 次起结果里带提醒
        List<Map<String, Object>> third = seen.get(ChatAgent.STALE_HINT);
        assertTrue(String.valueOf(third.get(third.size() - 1).get("content")).contains("onesay_note"));
        List<Map<String, Object>> second = seen.get(ChatAgent.STALE_HINT - 1);
        assertFalse(String.valueOf(second.get(second.size() - 1).get("content")).contains("onesay_note"));
    }

    @Test public void sameFamilyWithNewResultsKeepsGoing() {
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> n.get() >= 7 ? new Llm.Turn("查完了", List.of(), Llm.msg("assistant", "查完了")) : call("c" + n.get(), "run_command", api(n.incrementAndGet()));
        ChatAgent.Exec ex = (cmd, args) -> Actions.Result.ok("命令执行完了", new TreeMap<>(Map.of("output", "结果 " + args.get("command"), "exit", 0L)));
        ChatAgent.Outcome o = guarded().run(ctx(), b, ex, t -> { });
        assertEquals("done", o.status);
        assertEquals(7, o.steps);
    }

    @Test public void familyIgnoresQueryAndPrefix() {
        assertEquals(ChatAgent.family("sys_exec", Map.of("command", "$ProgressPreference='SilentlyContinue'; Invoke-RestMethod 'https://a.com/x.php?p=1'")),
                ChatAgent.family("sys_exec", Map.of("command", "Invoke-RestMethod -Uri \"https://a.com/x.php?p=2&q=3\" | ConvertTo-Json")));
        assertNotEquals(ChatAgent.family("sys_exec", Map.of("command", "Invoke-RestMethod https://a.com/x.php")),
                ChatAgent.family("sys_exec", Map.of("command", "Invoke-RestMethod https://a.com/y.php")));
        assertEquals("sys_exec|get-childitem", ChatAgent.family("sys_exec", Map.of("command", "Get-ChildItem C:\\Users -Force")));
    }

    @Test public void deniedStepGetsWrapUpWithoutTools() {
        ChatAgent a = new ChatAgent();
        a.wrapUpOnStop = true;
        List<Boolean> toolsGiven = new ArrayList<>();
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        Llm.Turn two = new Llm.Turn(null, List.of(new Llm.ToolCall("a", "run_command", "{\"command\":\"Start-Process steam.exe\",\"description\":\"启动 Steam\"}"),
                new Llm.ToolCall("b", "run_command", "{\"command\":\"Get-Process steam\",\"description\":\"看进程\"}")), new TreeMap<>(Llm.msg("assistant", "")));
        ChatAgent.Brain b = (m, t) -> {
            toolsGiven.add(!t.isEmpty()); seen.add(new ArrayList<>(m));
            return t.isEmpty() ? new Llm.Turn("【需要你操作】已确认商店能连上、社区 443 连不上。你可以：1. 换网络试试 2. 用加速器访问社区。", List.of(), Llm.msg("assistant", "x")) : two;
        };
        List<String> calls = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, args) -> { calls.add(cmd); return new Actions.Result(false, "对方点了拒绝", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED))); };
        ChatAgent.Outcome o = a.run(ctx(), b, ex, t -> { });
        assertEquals("denied", o.status);
        assertEquals(1, calls.size());
        assertEquals(List.of(true, false), toolsGiven);
        assertTrue(o.text.startsWith("你在确认框里拒绝了这一步（启动 Steam）"));
        assertTrue(o.text.contains("用加速器访问社区"));
        assertFalse("收尾不露结果标记", o.text.contains("【需要你操作】"));
        // 没轮到的第二个调用也有回应（接口要求），标明没执行
        List<Map<String, Object>> last = seen.get(1);
        boolean skipped = false;
        for (Map<String, Object> m : last) if ("b".equals(m.get("tool_call_id")) && String.valueOf(m.get("content")).contains("没执行")) skipped = true;
        assertTrue(skipped);
    }

    @Test public void wrapUpFailureFallsBackAndStopIsNotWrapped() {
        ChatAgent a = new ChatAgent();
        a.wrapUpOnStop = true;
        ChatAgent.Brain b = (m, t) -> { if (t.isEmpty()) throw new RuntimeException("网络错"); return call("c", "run_command", "{\"command\":\"New-Item a\"}"); };
        ChatAgent.Exec ex = (cmd, args) -> new Actions.Result(false, "拒绝", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED)));
        ChatAgent.Outcome o = a.run(ctx(), b, ex, t -> { });
        assertEquals("denied", o.status);
        assertTrue(o.text.endsWith("直接告诉我。"));
    }

    // ------------------------------------------------------------------ [综合任务测试 3] 只问不做打回一次

    static final String L2 = "我可以帮你把行程排出来，但有几个关键信息得先定。\n\n1. 什么时候去？一周具体是哪几天。\n2. 从哪出发、预算大概多少（人均或总预算）。\n"
            + "3. 爸妈腿脚到什么程度：能连续走 20 分钟吗？\n4. 想玩哪一片：昆明+大理+丽江，还是只待一两个地方？";

    @Test public void questionCountReadsNumberedQuestions() {
        assertEquals(4, ChatAgent.questionCount(L2));
        assertTrue(ChatAgent.overAsks(L2));
        assertFalse(ChatAgent.overAsks("初步倾向云南。\n1. 玩几天？\n2. 想不想看雪？"));
        assertFalse("步骤清单不是问题", ChatAgent.overAsks("这样做：\n1. 打开设置\n2. 选声音\n3. 把输出改成扬声器"));
        assertEquals(0, ChatAgent.questionCount(null));
    }

    /** 真机复验（f22c68b）：先用一段话问会场在哪，再列两条——共 3 问、一步没查，以前只数出 2 个没打回。 */
    static final String B6 = "先确认几个关键信息，我才能给出靠谱的住宿建议。\n\n你说的“开会”具体在成都哪里？比如天府新区、高新区还是某个具体园区？成都很大。\n\n"
            + "另外补充两点会更有用：\n1. 会议大概几天、预算多少（比如 300 以内 / 500 左右）\n2. 更看重什么：离会场近、地铁方便、还是吃饭逛街方便\n\n"
            + "如果你暂时不确定会场，我可以先按地铁 1 号线沿线给一版通用方案。";

    @Test public void proseQuestionCountsTowardOverAsking() {
        assertEquals(3, ChatAgent.questionCount(B6));
        assertTrue(ChatAgent.overAsks(B6));
        assertEquals("一句反问 + 无列表只算 1", 1, ChatAgent.questionCount("你想让我帮你安排这两周假期吗？比如查机票、做行程。\n告诉我想去哪儿，我就开始办。"));
        assertFalse("方案里带一句问号不算只问不做", ChatAgent.overAsks("先给你一版方案：\n第 1 天 昆明。\n第 2 天 大理。\n最后问一下：从哪出发？\n1. 能走多远？"));
    }

    @Test public void overAskingFirstReplyIsSentBackOnce() {
        List<List<Map<String, Object>>> seen = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        ChatAgent.Brain b = (m, t) -> { seen.add(new ArrayList<>(m)); return new Llm.Turn(L2, List.of(), Llm.msg("assistant", L2)); };
        ChatAgent.Outcome o = guarded().run(ctx(), b, (c, a) -> null, t -> { });
        assertEquals("done", o.status);
        assertEquals("只打回一次", 2, seen.size());
        assertTrue(String.valueOf(seen.get(1).get(seen.get(1).size() - 1).get("content")).contains("最多问 1–2 个"));
        // 没开防护（直接 new 的 ChatAgent）不打回
        List<Integer> calls = new ArrayList<>();
        new ChatAgent().run(ctx(), (m, t) -> { calls.add(1); return new Llm.Turn(L2, List.of(), Llm.msg("assistant", L2)); }, (c, a) -> null, t -> { });
        assertEquals(1, calls.size());
    }

    @Test public void promptAsksToVerifyTimeSensitiveFactsFirst() {
        String p = ChatAgent.systemPrompt("2026-11-20 周五 18:00", "W", "H", "");
        assertTrue(p.contains("先联网查官方或权威来源"));
        assertTrue(p.contains("已经过去"));
        assertTrue(p.contains("最多问 1–2 个关键问题"));
    }

    @Test public void lockedScreenIsNotShotAndSaysHowToReadInstead() throws Exception {
        Chat ch = DevTools1118Test.chat();
        ch.workspace = java.nio.file.Files.createTempDirectory("ws").toString();
        byte[] png = DevTools1118Test.tinyPng();
        List<String> shot = new ArrayList<>();
        ch.shooter = t -> { shot.add(t); return new Object[] {png, "pc"}; };
        ch.ocr = f -> "";
        ch.screenLocked = () -> true;
        Actions.Result r = ch.screenLook(Map.of("target", "pc"));
        assertFalse(r.ok);
        assertEquals("screen_locked", r.data.get("reason"));
        assertTrue(String.valueOf(r.data.get("output")).contains("Invoke-WebRequest"));
        assertTrue("锁着就不截", shot.isEmpty());
        // 安卓设备不受电脑锁屏影响；没锁照常截
        ch.screenLook(Map.of("target", "emulator-5554"));
        assertEquals(List.of("emulator-5554"), shot);
        ch.screenLocked = () -> false;
        assertTrue(ch.screenLook(Map.of("target", "pc")).ok);
        assertFalse("沙箱（非 Windows）不算锁屏", Chat.windowsLocked());
    }

    @Test public void promptSaysOpenUrlIsNotForReading() {
        assertTrue(ChatAgent.systemPrompt("t", "W", "H", "").contains("不要靠打开后截图来读网页"));
    }
}
