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
            return t.isEmpty() ? new Llm.Turn("已确认商店能连上、社区 443 连不上。你可以：1. 换网络试试 2. 用加速器访问社区。", List.of(), Llm.msg("assistant", "x")) : two;
        };
        List<String> calls = new ArrayList<>();
        ChatAgent.Exec ex = (cmd, args) -> { calls.add(cmd); return new Actions.Result(false, "对方点了拒绝", new TreeMap<>(Map.of("reason", LinkPolicy.R_CONFIRM_DENIED))); };
        ChatAgent.Outcome o = a.run(ctx(), b, ex, t -> { });
        assertEquals("denied", o.status);
        assertEquals(1, calls.size());
        assertEquals(List.of(true, false), toolsGiven);
        assertTrue(o.text.startsWith("你在确认框里拒绝了这一步（启动 Steam）"));
        assertTrue(o.text.contains("用加速器访问社区"));
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
}
