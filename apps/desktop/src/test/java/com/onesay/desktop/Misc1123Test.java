package com.onesay.desktop;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Test;

/**
 * [fix-1123-misc] ① 管理员身份启动时手机来的命令：照常审批，执行时降成普通用户权限（降不了就拦下、如实说），永不提权规则不放宽；
 * ② 通知 / 文字截断按字符，不切半个字，末尾加省略号。
 */
public class Misc1123Test {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
    }

    // ------------------------------------------------------------------ ① 管理员身份 + 远程命令

    @Test public void remoteElevatedIsWrappedNotRefused() throws Exception {
        ProcessBuilder wrapped = new ProcessBuilder("wrapped");
        SafeExec.reset(() -> true, pb -> wrapped);
        SafeExec.REMOTE.set(Boolean.TRUE);
        assertSame("有降权实现：换成降权包装，不再一律拒", wrapped, SafeExec.check(new ProcessBuilder("powershell.exe", "-Command", "New-Item x")));
        // 本机（不是远程）不包装
        SafeExec.REMOTE.set(Boolean.FALSE);
        ProcessBuilder local = new ProcessBuilder("powershell.exe", "-Command", "dir");
        assertSame(local, SafeExec.check(local));
    }

    @Test public void neverElevateRuleNotLoosened() {
        SafeExec.reset(() -> true, pb -> pb);
        SafeExec.REMOTE.set(Boolean.TRUE);
        for (String[] c : new String[][] {
                {"powershell.exe", "-Command", "Start-Process cmd -Verb RunAs"},
                {"runas", "/user:Administrator", "cmd"},
                {"cmd", "/c", "fodhelper.exe"}}) {
            try {
                SafeExec.check(new ProcessBuilder(c));
                fail("提权写法照旧要拒：" + String.join(" ", c));
            } catch (SafeExec.Refused e) {
                assertTrue(e.getMessage(), e.getMessage().contains("永远不提权"));
            }
        }
    }

    @Test public void noDeElevatorRefusesWithPlainReason() {
        SafeExec.reset(() -> true, null);
        SafeExec.REMOTE.set(Boolean.TRUE);
        try {
            SafeExec.check(new ProcessBuilder("powershell.exe", "-Command", "New-Item x"));
            fail();
        } catch (SafeExec.Refused e) {
            assertEquals(WinDeElevate.FAIL_ZH, e.getMessage());
            assertTrue(e.getMessage().contains("双击"));
        }
        try {
            SafeExec.checkInProcessWrite();
            fail();
        } catch (SafeExec.Refused e) {
            assertTrue(e.getMessage().contains("管理员身份"));
            assertTrue(e.getMessage().contains("没执行"));
        }
        // 降权实现返回 null（做不到）= 拒
        SafeExec.reset(() -> true, pb -> null);
        SafeExec.REMOTE.set(Boolean.TRUE);
        try { SafeExec.check(new ProcessBuilder("cmd", "/c", "echo")); fail(); } catch (SafeExec.Refused e) { assertEquals(WinDeElevate.FAIL_ZH, e.getMessage()); }
    }

    @Test public void defaultDeElevatorRefusesOffWindows() {
        // 生产默认的降权实现：这台测试机不是 Windows → 返回 null → 拒（不会悄悄以 root / 管理员跑）
        assertFalse(SafeExec.WINDOWS);
        SafeExec.reset(() -> true, SafeExec.DEFAULT_DE);
        SafeExec.REMOTE.set(Boolean.TRUE);
        try { SafeExec.check(new ProcessBuilder("sh", "-c", "true")); fail(); } catch (SafeExec.Refused e) { assertEquals(WinDeElevate.FAIL_ZH, e.getMessage()); }
        assertTrue(SafeExec.remoteElevatedNote().contains("拦下"));
    }

    @Test public void remoteNoteWording() {
        assertEquals("不是远程：不写", "", SafeExec.remoteElevatedNote());
        SafeExec.reset(() -> false, pb -> pb);
        SafeExec.REMOTE.set(Boolean.TRUE);
        assertEquals("不是管理员：不写", "", SafeExec.remoteElevatedNote());
        SafeExec.reset(() -> true, pb -> pb);
        SafeExec.REMOTE.set(Boolean.TRUE);
        String n = SafeExec.remoteElevatedNote();
        assertTrue(n, n.contains("管理员身份") && n.contains("降成普通用户权限") && n.contains("降不了就不跑"));
        for (String bad : new String[] {"啥", "咋", "呗", "UAC", "Safer"}) assertFalse(bad, n.contains(bad) || WinDeElevate.FAIL_ZH.contains(bad));
    }

    @Test public void phoneCardSaysItWillDeElevate() throws Exception {
        Approvals1118Test.Card card = new Approvals1118Test.Card();
        PcGuard g = Fix1123cTest.guard(card);
        SafeExec.reset(() -> true, pb -> pb);
        SafeExec.REMOTE.set(Boolean.TRUE);
        PcGuard.Outcome o = Fix1123cTest.peerSh(g, "task-x", Fix1123cTest.MK);
        assertNull("照常走审批、批了就放行", o.reply);
        assertNotNull(o.pass);
        assertEquals(1, card.asked.size());
        assertTrue(card.asked.get(0), card.asked.get(0).contains("降成普通用户权限"));
        assertFalse("远程卡不说「会带管理员权限执行」", card.asked.get(0).contains("会带管理员权限执行"));
        // 没有降权实现：卡上说会被拦下
        SafeExec.reset(() -> true, null);
        SafeExec.REMOTE.set(Boolean.TRUE);
        Fix1123cTest.peerSh(g, "task-y", Fix1123cTest.MK + "2");
        assertTrue(card.asked.get(card.asked.size() - 1).contains("拦下"));
        // 不是管理员：卡上不提
        SafeExec.reset(() -> false, pb -> pb);
        SafeExec.REMOTE.set(Boolean.TRUE);
        Fix1123cTest.peerSh(g, "task-z", Fix1123cTest.MK + "3");
        assertFalse(card.asked.get(card.asked.size() - 1).contains("管理员身份"));
    }

    @Test public void wrapperCarriesCommandOutOfBand() throws Exception {
        File dir = Files.createTempDirectory("de").toFile();
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "New-Item -ItemType Directory -Path \"C:\\Users\\me\\Desktop\\周末 1\"")
                .directory(dir).redirectErrorStream(true);
        pb.environment().put("ONESAY_T", "1");
        ProcessBuilder w = WinDeElevate.wrapFor(pb, "C:\\Windows");
        List<String> c = w.command();
        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", c.get(0));
        assertTrue(c.contains("-EncodedCommand"));
        String script = new String(Base64.getDecoder().decode(c.get(c.size() - 1)), StandardCharsets.UTF_16LE);
        assertEquals(WinDeElevate.SCRIPT, script);
        assertFalse("原命令不拼进脚本", String.join(" ", c).contains("周末"));
        String line = new String(Base64.getDecoder().decode(w.environment().get(WinDeElevate.ENV_CMD)), StandardCharsets.UTF_16LE);
        assertEquals(pb.command(), WinDeElevate.parse(line));
        assertEquals(dir.getAbsolutePath(), new String(Base64.getDecoder().decode(w.environment().get(WinDeElevate.ENV_CWD)), StandardCharsets.UTF_16LE));
        assertEquals(dir, w.directory());
        assertTrue(w.redirectErrorStream());
        assertEquals("1", w.environment().get("ONESAY_T"));
        // 包装进程本身过得了安全插件（它不含 runas / 提权写法）
        assertEquals("", LinkPolicy.elevationReason(LinkPolicy.normalizeCommand(String.join(" ", c))));
    }

    @Test public void scriptChecksIntegrityAndFailsClosed() {
        String s = WinDeElevate.SCRIPT;
        assertTrue(s.contains("SaferComputeTokenFromLevel"));
        assertTrue("普通用户级别", s.contains("0x20000"));
        assertTrue("设成中完整性", s.contains("S-1-16-8192"));
        assertTrue("启动前读回核对", s.contains("il > 0x2000") && s.indexOf("Integrity(tok)") < s.indexOf("CreateProcessAsUser(tok"));
        assertTrue("失败不启动、带标记退出", s.contains("exit " + WinDeElevate.EXIT_FAILED) && s.contains(WinDeElevate.FAIL_MARK));
        assertFalse(s.toLowerCase().contains("runas"));
        assertFalse(s.contains("ShellExecute"));
    }

    @Test public void commandLineQuotingRoundTrips() {
        String[][] cases = {
                {"a", "b c", ""},
                {"powershell.exe", "-Command", "Where-Object { $_.Name -like \"*MuMu*\" }"},
                {"x", "C:\\Program Files\\a\\", "back\\\\slash\"q", "tab\there", "末尾\\"},
                {"cmd", "/c", "echo \"\"\"hi\"\"\""}};
        for (String[] c : cases) {
            String line = WinDeElevate.commandLine(List.of(c));
            assertEquals(line, List.of(c), WinDeElevate.parse(line));
        }
        assertEquals("plain", WinDeElevate.quote("plain"));
        assertEquals("\"\"", WinDeElevate.quote(""));
    }

    @Test public void deElevateFailureReportedAsNotRun() {
        Map<String, Object> d = new TreeMap<>();
        d.put("exit", (long) WinDeElevate.EXIT_FAILED);
        d.put("output", WinDeElevate.FAIL_MARK + ": SaferComputeTokenFromLevel 5");
        Actions.Result r = Agent.deElevateExplain(new Actions.Result(false, "命令失败（20307）", d));
        assertFalse(r.ok);
        assertEquals(WinDeElevate.FAIL_ZH, r.text);
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, r.data.get("reason"));
        // 命令自己失败（退出码一样但没有标记）不改
        Map<String, Object> d2 = new TreeMap<>();
        d2.put("exit", (long) WinDeElevate.EXIT_FAILED);
        d2.put("output", "something");
        Actions.Result r2 = new Actions.Result(false, "命令失败（20307）", d2);
        assertSame(r2, Agent.deElevateExplain(r2));
        Actions.Result ok = Actions.Result.ok("好了", d);
        assertSame(ok, Agent.deElevateExplain(ok));
        assertNull(Agent.deElevateExplain(null));
    }

    // ------------------------------------------------------------------ ② 按字符截

    @Test public void clipNeverSplitsACharacter() {
        String s = "完成😀😀😀结果";
        assertEquals("完成😀…", TextClip.clip(s, 3));
        assertEquals("完成…", TextClip.clip(s, 2));
        assertEquals(s, TextClip.clip(s, 7));
        assertEquals(s, TextClip.clip(s, 100));
        assertEquals("", TextClip.clip(null, 3));
        for (int n = 1; n < 8; n++) {
            String c = TextClip.clip(s, n);
            for (int i = 0; i < c.length(); i++) {
                char ch = c.charAt(i);
                if (Character.isHighSurrogate(ch)) assertTrue(c, i + 1 < c.length() && Character.isLowSurrogate(c.charAt(i + 1)));
                if (Character.isLowSurrogate(ch)) assertTrue(c, i > 0 && Character.isHighSurrogate(c.charAt(i - 1)));
            }
        }
        // 生僻字（扩展 B 区，两个 UTF-16 单位）
        String rare = "𠀀𠀁𠀂";
        assertEquals("𠀀…", TextClip.clip(rare, 1));
        assertEquals("𠀀𠀁…", Notify.clip(rare, 2));
        assertEquals("𠀀…（截断）", ChatAgent.clip(rare, 1));
        // 普通汉字照旧按字截，末尾加省略号
        assertEquals("电脑任务…", Notify.clip("电脑任务没做完", 4));
        assertEquals("abc", Notify.clip("abc", 3));
    }

    @Test public void phoneTaskPushClippedByCharacter() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/App.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("TextClip.clip(Chat.phoneTaskLine(card), 1000)"));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 999; i++) b.append('字');
        b.append("😀😀");
        String c = TextClip.clip(b.toString(), 1000);
        assertTrue(c.endsWith("😀…"));
        assertTrue("手机端收 4000 以内", c.length() <= 4000);
    }
}
