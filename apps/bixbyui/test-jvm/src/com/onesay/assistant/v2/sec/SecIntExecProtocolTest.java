package com.onesay.assistant.v2.sec;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.executor.ExecProtocol;
import com.onesay.assistant.v2.executor.ExecRequestFile;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;
import com.onesay.assistant.v2.executor.ShellScript;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 2026-10-25 渗透测试修复·集成：exec 守护 v4 的 App 侧协议（fix-root.md §4-1/2/4）。
 * 分词与守护同口径、HMAC 与守护 mac_of 同构（端到端：真起一个 v4 守护跑签名请求）、原子写、rc=77 识别、ShellScript 语义。
 */
public class SecIntExecProtocolTest {

    // ------------------------------------------------------------------ 分词

    @Test public void tokenizeMatchesDaemonRules() {
        assertEquals(Arrays.asList("settings", "get", "system", "screen_brightness"), ExecProtocol.tokenize("settings get system screen_brightness"));
        assertEquals(Arrays.asList("dumpsys", "battery", "|", "grep", "-m1", " level:"), ExecProtocol.tokenize("dumpsys battery | grep -m1 ' level:'"));
        assertEquals(Arrays.asList("dumpsys", "activity", "activities", "|", "grep", "-m2", "-E", "mResumedActivity|mFocusedActivity"),
                ExecProtocol.tokenize("dumpsys activity activities 2>/dev/null | grep -m2 -E 'mResumedActivity|mFocusedActivity'"));
        assertEquals(Arrays.asList("am", "start", "-d", "geo:0,0?q=a b"), ExecProtocol.tokenize("am start -d \"geo:0,0?q=a b\""));
        assertEquals("空串参数保留", Arrays.asList("echo", ""), ExecProtocol.tokenize("echo ''"));
        // 元字符 → null（走 legacy，由守护判）
        for (String bad : new String[]{"a; b", "a && b", "a > /x", "a < /x", "echo `id`", "echo $(id)", "echo $HOME", "a & b",
                "(a)", "a \\ b", "echo \"$x\"", "a\nb", "echo 'x", "", "   "}) {
            assertNull(bad, ExecProtocol.tokenize(bad));
        }
        // strip_devnull 只认以空白分隔的完整单词
        assertEquals(Arrays.asList("screencap", "-p", "/a"), ExecProtocol.tokenize("screencap -p /a >/dev/null 2>&1 </dev/null"));
        assertNull(ExecProtocol.tokenize("screencap -p /a 2>/dev/nullx"));
    }

    @Test public void simpleChainSplitting() {
        List<String[]> c = ExecProtocol.splitChain("stat -c %u /a/b && cat /a/b");
        assertEquals(2, c.size());
        assertArrayEquals(new String[]{";", "stat -c %u /a/b"}, c.get(0));
        assertArrayEquals(new String[]{"&&", "cat /a/b"}, c.get(1));
        c = ExecProtocol.splitChain("rm -f /x; screencap -p /x 2>&1; true;");
        assertEquals(3, c.size());
        assertTrue(ExecProtocol.isLocal("true"));
        assertTrue(ExecProtocol.isLocal("echo ---SEP---"));
        assertFalse("echo -n 交给真 echo", ExecProtocol.isLocal("echo -n x"));
        assertArrayEquals(new String[]{"0", "a b"}, ExecProtocol.runLocal("echo 'a' b"));
        assertEquals("引号里的 ; && 不是分隔符", 2, ExecProtocol.splitChain("echo 'a;b&&c' ; id").size());
        for (String no : new String[]{"id", "a || b", "a && b || c", "(a; b)", "a; b > /x", "a && $(b)", "a &", "a && ", "; a",
                "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
                "settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network",
                "echo 'unterminated ; x"}) {
            assertNull(no, ExecProtocol.splitChain(no));
        }
    }

    @Test public void requestBodyFallsBackToLegacyWhenNoKeyOrNotSplittable() {
        assertEquals("id", ExecProtocol.requestBody("id", null, 1L));
        assertEquals("id", ExecProtocol.requestBody("id", "", 1L));
        String tmpl = "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true";
        assertEquals("没密钥时固定模板仍原样明文", tmpl, ExecProtocol.requestBody(tmpl, null, 1L));
        assertEquals("分不开的非模板命令 → 原样明文", "a; b > /x", ExecProtocol.requestBody("a; b > /x", "k", 1L));
        String b = ExecProtocol.requestBody("settings get system font_scale", "k", 1700000000L);
        String[] ls = b.split("\n");
        assertEquals(ExecProtocol.HEADER, ls[0]);
        assertEquals("ts=1700000000", ls[1]);
        assertTrue(ls[2].matches("nonce=[0-9a-f]{32}"));
        assertEquals("act=argv", ls[3]);
        assertEquals(Arrays.asList("a=settings", "a=get", "a=system", "a=font_scale"), Arrays.asList(ls).subList(4, 8));
        assertTrue(ls[8].matches("mac=[0-9a-f]{64}"));
        assertFalse("无末尾换行", b.endsWith("\n"));
        String payload = b.substring(0, b.lastIndexOf("\nmac="));
        assertEquals(ExecProtocol.mac("k", payload), ls[8].substring(4));
        assertNotEquals("nonce 每次不同", ExecProtocol.requestBody("id", "k", 1L), ExecProtocol.requestBody("id", "k", 1L));
    }

    /** 守护 v4.1 G1（fix-root2 / APP-FOLLOWUP）：有密钥时每条固定模板都发签名 act=tmpl，恰好一行 a=<模板全文>。 */
    @Test public void fixedTemplatesGoSignedTmplFrame() {
        List<String> all = new ArrayList<String>(Arrays.asList(ExecProtocol.FIXED_TEMPLATES));
        assertTrue("控制台状态探针也是固定模板", all.contains(com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE));
        assertTrue(all.contains("reboot") && all.contains("reboot -p") && all.contains("echo 1 > /sys/class/leds/torch-sec1/brightness"));
        for (String t : all) {
            String b = ExecProtocol.requestBody(t, "k", 1700000000L);
            String[] ls = b.split("\n", -1);
            assertEquals(t, 6, ls.length);
            assertEquals(ExecProtocol.HEADER, ls[0]);
            assertEquals("ts=1700000000", ls[1]);
            assertTrue(ls[2].matches("nonce=[0-9a-f]{32}"));
            assertEquals("act=tmpl", ls[3]);
            assertEquals("模板全文逐字放在唯一一行 a= 里", "a=" + t, ls[4]);
            String payload = b.substring(0, b.lastIndexOf("\nmac="));
            assertEquals(ExecProtocol.mac("k", payload), ls[5].substring(4));
            assertFalse(b.endsWith("\n"));
            String p2 = ExecProtocol.HEADER + "\nts=7\nnonce=abcd1234\nact=tmpl\na=" + t;
            assertEquals(p2 + "\nmac=" + ExecProtocol.mac("k", p2), ExecProtocol.buildSignedTemplate(t, "k", 7L, "abcd1234"));
        }
        assertNotEquals("nonce 每次不同", ExecProtocol.requestBody("reboot", "k", 1L), ExecProtocol.requestBody("reboot", "k", 1L));
        // 非模板（哪怕只差一个空格）不走 tmpl
        assertTrue(ExecProtocol.requestBody("reboot  -p", "k", 1L).contains("\nact=argv\n"));
    }

    /** 模板类 / 别的 App 私有数据的拒绝：legacy 重发也没用（require_mac 下还会多一次 MAC_REQUIRED），一律不重发。 */
    @Test public void tmplAndOtherAppDenyAreNotRetried() {
        for (String no : new String[]{"TMPL_UNKNOWN", "TMPL_ARGC(2)", "OTHER_APP_DATA(com.tencent.mm)", "ACT_UNKNOWN(tmpl)", "MAC_REQUIRED"}) {
            assertFalse(no, ExecProtocol.isWhitelistDeny(no));
        }
        assertTrue(ExecProtocol.isWhitelistDeny("PROG_NOT_WHITELISTED(sh)"));
    }

    @Test public void macIsNestedHexConstruction() {
        // mac = sha256hex(key + sha256hex(key + payload))，与守护 mac_of 的 printf '%s' | sha256sum 同构
        String key = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";   // gitleaks:allow（测试向量）
        String inner = ExecProtocol.sha256Hex(key + "p");
        assertEquals(ExecProtocol.sha256Hex(key + inner), ExecProtocol.mac(key, "p"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ExecProtocol.sha256Hex(""));
    }

    @Test public void macAgreesWithShellSha256sum() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").canExecute());
        String key = "a1b2c3";
        String payload = "#bixby-exec v4\nts=1\nnonce=abcdefgh12\nact=argv\na=id\na=中文 参数";
        // 逐字照抄守护 mac_of 的做法
        String script = "k=\"$1\"; p=\"$2\"; i=$(printf '%s' \"$k$p\" | sha256sum); i=${i%% *}; o=$(printf '%s' \"$k$i\" | sha256sum); printf '%s' \"${o%% *}\"";
        Process pr = new ProcessBuilder("/bin/sh", "-c", script, "sh", key, payload).redirectErrorStream(true).start();
        String out = new String(readAll(pr.getInputStream()), StandardCharsets.UTF_8).trim();
        pr.waitFor();
        assertEquals(out, ExecProtocol.mac(key, payload));
    }

    @Test public void keyReadLikeShellCat() throws Exception {
        File d = Files.createTempDirectory("bxkey").toFile();
        assertNull(ExecProtocol.readKey(d));
        File k = new File(d, ExecProtocol.KEY_FILE);
        Files.write(k.toPath(), "abc123\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("去末尾换行（同 $(cat)）", "abc123", ExecProtocol.readKey(d));
        k.delete();
        File real = new File(d, "real");
        Files.write(real.toPath(), "evil".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(k.toPath(), real.toPath());
        assertNull("符号链接不认", ExecProtocol.readKey(d));
    }

    // ------------------------------------------------------------------ 原子写 / 收尾

    @Test public void writeRequestIsTmpThenRename() throws Exception {
        File d = Files.createTempDirectory("bxreq").toFile();
        assertTrue(ExecProtocol.writeRequest(d, "id"));
        assertFalse(".tmp 不残留", new File(d, ExecProtocol.REQ_TMP).exists());
        assertEquals("id", new String(Files.readAllBytes(new File(d, ExecProtocol.REQ).toPath()), StandardCharsets.UTF_8));
        // 失败分支收尾按「本次写入的内容」比对：签名请求整段比对
        String body = ExecProtocol.requestBody("id", "k", 1L);
        ExecProtocol.writeRequest(d, body);
        assertEquals(ExecRequestFile.Abandon.REPLACED, ExecRequestFile.abandon(new File(d, ExecProtocol.REQ), "id"));
        assertEquals(ExecRequestFile.Abandon.CLEARED, ExecRequestFile.abandon(new File(d, ExecProtocol.REQ), body));
        new File(d, ExecProtocol.REQ).delete();
        assertEquals("v4 守护取件后文件不存在 = 已取走", ExecRequestFile.Abandon.ALREADY_TAKEN, ExecRequestFile.abandon(new File(d, ExecProtocol.REQ), body));
        String src = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/executor/ShellExecutor.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(src.contains("ExecProtocol.writeRequest(dir, body)"));
        assertFalse("不再直接 new FileOutputStream(req", src.contains("new FileOutputStream(req"));
    }

    // ------------------------------------------------------------------ rc=77

    @Test public void denyCodeRecognized() {
        assertTrue(ExecProtocol.isDenied(77, "DENY PROG_NOT_WHITELISTED(sh)"));
        assertFalse("真实程序退出 77 不算", ExecProtocol.isDenied(77, "some output"));
        assertFalse(ExecProtocol.isDenied(1, "DENY X"));
        assertEquals("SIG_MISMATCH", ExecProtocol.denyReason("DENY SIG_MISMATCH\n"));
        ShellExecutor.ShellResponse r = ShellExecutor.ShellResponse.denied("PROG_NOT_WHITELISTED(sh)");
        assertTrue(r.isDenied);
        assertFalse(r.isSuccess());
        assertEquals(77, r.rc);
        assertTrue(r.output, r.output.contains("root 守护拒绝执行") && r.output.contains("白名单"));
        assertFalse(new ShellExecutor.ShellResponse(1, "x", false, false).isDenied);
        // 只有白名单类拒绝才 legacy 重发；身份 / MAC / raw 关闭不重发
        assertTrue(ExecProtocol.isWhitelistDeny("PROG_NOT_WHITELISTED(sh)"));
        assertTrue(ExecProtocol.isWhitelistDeny("METACHAR(;)"));
        for (String no : new String[]{"MAC_bad", "MAC_replay", "SIG_MISMATCH", "REVOKED_CERT", "RAW_DISABLED", "BAD_OWNER(req=1 dir=2)", ""}) {
            assertFalse(no, ExecProtocol.isWhitelistDeny(no));
        }
    }

    @Test public void shellBackendMapsDenyToDaemonDenied() throws Exception {
        com.onesay.assistant.v2.capability.Capability cap = new com.onesay.assistant.v2.capability.Capability.Builder("t.x", "测试")
                .executor("shell").cmd("id").build();
        ShellRunner sh = new ShellRunner() {
            @Override public ShellExecutor.ShellResponse execute(String c) {
                return ShellExecutor.ShellResponse.denied("PROG_NOT_WHITELISTED(x)");
            }
        };
        com.onesay.assistant.v2.executor.backend.ExecRequest req = new com.onesay.assistant.v2.executor.backend.ExecRequest(
                cap, new org.json.JSONObject(), null, null, "id", null, sh, null);
        com.onesay.assistant.v2.executor.backend.BackendOutcome o = new com.onesay.assistant.v2.executor.backend.ShellBackend().run(req);
        assertTrue(o.isTerminal());
        assertEquals(com.onesay.assistant.v2.executor.ErrorCode.DAEMON_DENIED, o.terminal.code);
        assertFalse(o.terminal.userMessage.contains("返回码"));
    }

    // ------------------------------------------------------------------ ShellScript

    static final class Fake implements ShellRunner {
        final List<String> cmds = new ArrayList<String>();
        final java.util.Map<String, ShellExecutor.ShellResponse> ans = new java.util.HashMap<String, ShellExecutor.ShellResponse>();

        Fake on(String c, int rc, String out) {
            ans.put(c, new ShellExecutor.ShellResponse(rc, out, false, false));
            return this;
        }

        Fake deny(String c) {
            ans.put(c, ShellExecutor.ShellResponse.denied("PROG_NOT_WHITELISTED(x)"));
            return this;
        }

        @Override public ShellExecutor.ShellResponse execute(String c) {
            cmds.add(c);
            ShellExecutor.ShellResponse r = ans.get(c);
            return r != null ? r : new ShellExecutor.ShellResponse(0, "", false, false);
        }
    }

    @Test public void shellScriptSemantics() {
        Fake f = new Fake().on("a", 0, "A").on("b", 1, "B").on("c", 0, "C");
        ShellExecutor.ShellResponse r = ShellScript.exec(f, ShellScript.run("a"), ShellScript.echo("--"), ShellScript.run("b"), ShellScript.run("c"));
        assertEquals("A\n--\nB\nC", r.output);
        assertEquals("rc = 最后一步", 0, r.rc);
        assertEquals(Arrays.asList("a", "b", "c"), f.cmds);
        f = new Fake().on("a", 0, "A").on("b", 1, "B");
        r = ShellScript.exec(f, ShellScript.must("a"), ShellScript.must("b"), ShellScript.run("c"));
        assertEquals("&& 失败即停", Arrays.asList("a", "b"), f.cmds);
        assertEquals(1, r.rc);
        // || 兜底
        f = new Fake().on("x", 1, "err").on("y", 0, "Y");
        r = ShellScript.exec(f, ShellScript.run("x").orElse("y"));
        assertEquals("Y", r.output);
        f = new Fake().on("am1", 0, "Error: Activity not started").on("am2", 0, "Starting");
        r = ShellScript.exec(f, ShellScript.run("am1").orElse("am2"));
        assertEquals("am start 认不出 Intent 时 rc 仍 0，按输出 Error 判", "Starting", r.output);
        f = new Fake().on("cmd appops get p OP", 1, "real error").deny("appops get p OP");
        r = ShellScript.exec(f, ShellScript.run("cmd appops get p OP").orElse("appops get p OP"));
        assertEquals("兜底被拒 → 保留首选的真实输出", "real error", r.output);
        assertFalse(r.isDenied);
        // quiet：失败 / 被拒的输出丢掉
        f = new Fake().deny("q").on("w", 0, "W");
        r = ShellScript.exec(f, ShellScript.echo("@@q"), ShellScript.run("q").quiet(), ShellScript.echo("@@w"), ShellScript.run("w").quiet());
        assertEquals("@@q\n@@w\nW", r.output);
        assertTrue("被拒仍然报出来", r.isDenied);
        // 守护离线立即停
        f = new Fake();
        f.ans.put("d", new ShellExecutor.ShellResponse(-1, "down", false, true));
        r = ShellScript.exec(f, ShellScript.run("d"), ShellScript.run("e"));
        assertTrue(r.isDown);
        assertEquals(Arrays.asList("d"), f.cmds);
        assertEquals(Arrays.asList("a", "b", "c"), ShellScript.commands(Arrays.asList(ShellScript.run("a"), ShellScript.echo("x"), ShellScript.run("b").orElse("c"))));
    }

    @Test public void splitSitesKeepParsersWorking() {
        // PowerWhitelist：拆分后输出仍能被 parse 吃下
        Fake f = new Fake().on("dumpsys deviceidle whitelist", 0, "user,com.onesay.assistant,10234")
                .on("cmd appops get com.onesay.assistant RUN_IN_BACKGROUND", 0, "RUN_IN_BACKGROUND: ignore; time=+1d")
                .on("cmd appops get com.onesay.assistant RUN_ANY_IN_BACKGROUND", 0, "No operations.");
        ShellExecutor.ShellResponse r = ShellScript.exec(f, com.onesay.assistant.v2.jobs.PowerWhitelist.probeSteps("com.onesay.assistant", 28));
        com.onesay.assistant.v2.jobs.PowerWhitelist.Original o = com.onesay.assistant.v2.jobs.PowerWhitelist.parse(r.output, "com.onesay.assistant", 28, 1L);
        assertNotNull(r.output, o);
        assertTrue(o.dozeUser);
        assertEquals("ignore", o.modes.get("RUN_IN_BACKGROUND"));
        assertEquals("default", o.modes.get("RUN_ANY_IN_BACKGROUND"));
        assertEquals(Arrays.asList("cmd appops set com.onesay.assistant RUN_IN_BACKGROUND allow", "appops set com.onesay.assistant RUN_IN_BACKGROUND allow",
                        "cmd appops set com.onesay.assistant RUN_ANY_IN_BACKGROUND allow", "appops set com.onesay.assistant RUN_ANY_IN_BACKGROUND allow"),
                ShellScript.commands(com.onesay.assistant.v2.jobs.PowerWhitelist.applySteps(o)));
        try {
            com.onesay.assistant.v2.jobs.PowerWhitelist.probeSteps("com.x; reboot", 28);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        // ReliabilityCheck
        f = new Fake().on("dumpsys deviceidle whitelist", 0, "user,com.onesay.assistant,1").on("cmd appops get com.onesay.assistant RUN_IN_BACKGROUND", 0, "RUN_IN_BACKGROUND: allow");
        r = ShellScript.exec(f, com.onesay.assistant.v2.jobs.ReliabilityCheck.probeSteps("com.onesay.assistant"));
        List<com.onesay.assistant.v2.jobs.ReliabilityCheck.Item> items = com.onesay.assistant.v2.jobs.ReliabilityCheck.evaluate(r.output, "com.onesay.assistant", true, 2L, 1L, null);
        assertEquals(1, items.get(0).ok);
        assertEquals(1, items.get(1).ok);
        // 诊断快照：按 @@id 分段，读不到 / 被拒的项为空
        List<String> keys = Arrays.asList("system/screen_off_timeout", "probe/battery_level", "global/zen_mode");
        f = new Fake().on("settings get system screen_off_timeout", 0, "30000")
                .on("dumpsys battery | grep -m1 ' level:'", 0, "  level: 57").deny("settings get global zen_mode");
        r = ShellScript.exec(f, com.onesay.assistant.v2.diag.DiagSnapshot.buildReadSteps(keys));
        java.util.Map<String, String> m = com.onesay.assistant.v2.diag.DiagSnapshot.parse(keys, r.output);
        assertEquals("30000", m.get("system/screen_off_timeout"));
        assertEquals("57", m.get("probe/battery_level"));
        assertNull("被拒不能当成值", m.get("global/zen_mode"));
        for (String c : f.cmds) assertNull("每步都是单条命令: " + c, c.contains(";") ? c : null);
        // 城市库
        assertEquals(Arrays.asList("cp '/data/data/com.sec.android.daemonapp/databases/WeatherClock' '/d/WeatherClock'",
                        "cp '/data/data/com.sec.android.daemonapp/databases/WeatherClock-wal' '/d/WeatherClock-wal'"),
                ShellScript.commands(com.onesay.assistant.v2.city.HomeCityPolicy.copySteps("/d/")));
    }

    @Test public void shellProgramsForArithmeticTemplates() throws Exception {
        java.util.Map<String, String> none = new java.util.HashMap<String, String>();
        // 亮度 +40，夹到 255
        Fake f = new Fake().on("settings get system screen_brightness", 0, "230");
        ShellExecutor.ShellResponse r = com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("settings.brightness.up").run(f, none);
        assertEquals("settings put system screen_brightness 255", f.cmds.get(1));
        assertEquals("255", r.output);
        f = new Fake().on("settings get system screen_brightness", 0, "20");
        com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("settings.brightness.down").run(f, none);
        assertEquals("settings put system screen_brightness 1", f.cmds.get(1));
        // 音量 ±2，按「..15」上限
        f = new Fake().on("media volume --stream 3 --get", 0, "[v] will get volume\n[v] volume is 14 in range [0..15]");
        r = com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("volume.media.up").run(f, none);
        assertEquals("media volume --stream 3 --set 15", f.cmds.get(1));
        assertEquals("15", r.output);
        f = new Fake().on("media volume --stream 3 --get", 0, "error");
        r = com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("volume.media.down").run(f, none);
        assertEquals("读不到当前值 → rc 1、不 set", 1, r.rc);
        assertEquals(1, f.cmds.size());
        // 手电筒：写入走守护固定模板
        f = new Fake().on("cat /sys/class/leds/torch-sec1/brightness", 0, "0\n");
        com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("torch.toggle").run(f, none);
        assertEquals("echo 1 > /sys/class/leds/torch-sec1/brightness", f.cmds.get(1));
        assertTrue(ExecProtocol.isFixedTemplate(f.cmds.get(1)));
        f = new Fake().on("cat /sys/class/leds/torch-sec1/brightness", 0, "255");
        assertEquals("on", com.onesay.assistant.v2.executor.backend.ShellPrograms.checkFor("torch.toggle").run(f, none).output);
        // 定位
        f = new Fake().on("settings get secure location_providers_allowed", 0, "network,gps");
        assertEquals("3", com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("connectivity.gps.state").run(f, none).output);
        f = new Fake().on("settings get secure location_providers_allowed", 0, "");
        assertEquals("0", com.onesay.assistant.v2.executor.backend.ShellPrograms.checkFor("connectivity.gps.off").run(f, none).output);
        // NFC：dumpsys nfc 被拒 → 缺省文案（同原脚本的 || echo）
        f = new Fake().deny("dumpsys nfc | grep -m1 mState");
        assertEquals("mState=on", com.onesay.assistant.v2.executor.backend.ShellPrograms.checkFor("connectivity.nfc.on").run(f, none).output);
        f = new Fake().on("svc nfc enable", 1, "x");
        assertEquals("原脚本「; true」", 0, com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("connectivity.nfc.on").run(f, none).rc);
        // 商店
        f = new Fake().on("dumpsys account | grep -c com.google", 0, "2").on("pm list packages | grep -c com.sec.android.app.samsungapps", 1, "0");
        assertEquals("google_accounts=2\nplay=可能可用\nsamsung=未安装",
                com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("store.status.check").run(f, none).output);
        String ue = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("check 也走 ShellPrograms", ue.contains("ShellPrograms.checkFor(capability.sign)"));
    }

    @Test public void shellProgramsReplaceCompoundTemplates() throws Exception {
        java.util.Map<String, String> slots = new java.util.HashMap<String, String>();
        slots.put("address", "北京 东路");
        // 浏览器：resolve 出组件 → am start -n
        Fake f = new Fake().on(com.onesay.assistant.v2.executor.backend.ShellPrograms.RESOLVE_BROWSER, 0,
                "priority=0 preferredOrder=0\ncom.sec.android.app.sbrowser/.SBrowserMainActivity\n");
        com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("browser.open.default").run(f, slots);
        assertEquals("am start -n com.sec.android.app.sbrowser/.SBrowserMainActivity", f.cmds.get(1));
        f = new Fake().on(com.onesay.assistant.v2.executor.backend.ShellPrograms.RESOLVE_BROWSER, 0, "No activity found; rm -rf /");
        com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("browser.open.default").run(f, slots);
        assertEquals("认不出组件（或输出被污染）→ 回落 VIEW", com.onesay.assistant.v2.executor.backend.ShellPrograms.BROWSER_FALLBACK, f.cmds.get(1));
        // 打车：高德被拒 / 报 Error → geo
        f = new Fake();
        String amap = com.onesay.assistant.v2.executor.ShellCommand.fill(com.onesay.assistant.v2.executor.backend.ShellPrograms.TAXI_AMAP, slots);
        f.deny(amap);
        ShellExecutor.ShellResponse r = com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("s1.taxi.to").run(f, slots);
        assertEquals(2, f.cmds.size());
        assertTrue(f.cmds.get(1), f.cmds.get(1).startsWith("am start -a android.intent.action.VIEW -d 'geo:0,0?q="));
        assertTrue("地址按 URL 查询值编码", f.cmds.get(1).contains("%E5%8C%97%E4%BA%AC%20"));
        assertEquals(0, r.rc);
        assertFalse(r.isDenied);
        // 导航：geo 成功就不兜底
        f = new Fake().on(com.onesay.assistant.v2.executor.ShellCommand.fill(com.onesay.assistant.v2.executor.backend.ShellPrograms.NAV_GEO, slots), 0, "Starting: Intent");
        com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("screen.nav.to").run(f, slots);
        // ★ device-1102：导航先读一次定位开关（开着就不动），再 geo
        assertEquals(2, f.cmds.size());
        assertEquals(com.onesay.assistant.v2.executor.backend.ShellPrograms.LOC_MODE_GET, f.cmds.get(0));
        assertNull(com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign("app.open"));
        String sb = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."),
                "app/src/com/onesay/assistant/v2/executor/backend/ShellBackend.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(sb.contains("ShellPrograms.forSign(req.cap.sign)"));
    }

    // ------------------------------------------------------------------ 端到端：真起 v4 守护

    static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    /** 同 ShellExecutor 的 parseResultFile：第一行 rc，其余为输出。返回 {rc, out}。 */
    static String[] waitRes(File res, long ms) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            if (res.isFile() && res.length() > 0) {
                Thread.sleep(30);
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(res), "UTF-8"));
                try {
                    String first = r.readLine();
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(l);
                    }
                    if (first != null && first.trim().matches("-?\\d+")) return new String[]{first.trim(), sb.toString()};
                } finally {
                    r.close();
                }
            }
            Thread.sleep(30);
        }
        return null;
    }

    static String[] roundTrip(File dir, String body) throws Exception {
        File res = new File(dir, ".bixby_res");
        res.delete();
        ExecProtocol.writeRequest(dir, body);
        return waitRes(res, 8000);
    }

    @Test public void endToEndAgainstRealV4Daemon() throws Exception {
        File daemon = new File(System.getProperty("bixby.root", "."), "tools/kdaemon/exec_daemon.v4.sh");
        Assume.assumeTrue(new File("/bin/sh").canExecute() && daemon.isFile());
        String uid = new String(readAll(new ProcessBuilder("id", "-u").start().getInputStream()), StandardCharsets.UTF_8).trim();
        Assume.assumeFalse("以 root 跑测试时 files 目录属主是 root，守护按设计会拒（FILESDIR_OWNED_BY_ROOT）", "0".equals(uid));
        File w = Files.createTempDirectory("bxv4e2e").toFile();
        File rel = new File(w, "rel");
        File sec = new File(w, "sec");
        assertTrue(rel.mkdirs() && sec.mkdirs());
        assertTrue(new File(sec, "provision_key").createNewFile());   // 受控握手：身份校验通过才交付密钥
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", daemon.getAbsolutePath());
        pb.environment().put("BIXBY_EXEC_DIR", w.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(w, "daemon.out"));
        Process p = pb.start();
        try {
            // ① 还没有密钥：legacy 明文仍被接受（warn 模式），同时守护交付密钥
            String[] r = roundTrip(rel, "id");
            assertNotNull("守护没回执（日志：" + new String(Files.readAllBytes(new File(w, "exec.log").toPath()), StandardCharsets.UTF_8) + "）", r);
            assertEquals("0", r[0]);
            String key = ExecProtocol.readKey(rel);
            assertNotNull("provision_key 开着应交付 files/.bixby_key", key);
            assertEquals(64, key.length());
            // ② 签名请求：通过
            String body = ExecProtocol.requestBody("echo hello v4", key, System.currentTimeMillis() / 1000L);
            assertTrue(body.startsWith(ExecProtocol.HEADER));
            r = roundTrip(rel, body);
            assertEquals("0", r[0]);
            assertEquals("hello v4", r[1]);
            // ③ 原样重放：MAC_replay
            r = roundTrip(rel, body);
            assertEquals("77", r[0]);
            assertTrue(r[1], ExecProtocol.isDenied(77, r[1]));
            assertEquals("MAC_replay", ExecProtocol.denyReason(r[1]));
            // ④ 篡改参数：MAC_bad
            String forged = ExecProtocol.requestBody("echo hi", key, System.currentTimeMillis() / 1000L).replace("a=hi", "a=pwn");
            r = roundTrip(rel, forged);
            assertEquals("MAC_bad", ExecProtocol.denyReason(r[1]));
            // ⑤ 签名正确但不在白名单：PROG_NOT_WHITELISTED（白名单类 → ShellExecutor 会按 legacy 重发一次）
            r = roundTrip(rel, ExecProtocol.requestBody("sh -c id", key, System.currentTimeMillis() / 1000L));
            assertEquals("77", r[0]);
            assertTrue(r[1], ExecProtocol.denyReason(r[1]).startsWith("PROG_NOT_WHITELISTED"));
            assertTrue(ExecProtocol.isWhitelistDeny(ExecProtocol.denyReason(r[1])));
            // ⑥ 受控管道走结构化：参数里的 | 是字面量，不会被当成管道
            r = roundTrip(rel, ExecProtocol.requestBody("echo 'a|b' | grep -c b", key, System.currentTimeMillis() / 1000L));
            assertEquals("0", r[0]);
            assertEquals("1", r[1]);
            // ⑦ require_mac 打开后 legacy 明文被拒，签名请求照常
            assertTrue(new File(sec, "require_mac").createNewFile());
            r = roundTrip(rel, "id");
            assertEquals("MAC_REQUIRED", ExecProtocol.denyReason(r[1]));
            r = roundTrip(rel, ExecProtocol.requestBody("id", key, System.currentTimeMillis() / 1000L));
            assertEquals("0", r[0]);
            // ⑧ v4.1 G1/G7：require_mac 下固定模板走签名 act=tmpl 照常执行（控制台状态探针走守护内建只读实现）
            r = roundTrip(rel, ExecProtocol.requestBody(com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE, key, System.currentTimeMillis() / 1000L));
            assertNotNull(r);
            assertEquals(r[1], "0", r[0]);
            assertTrue(r[1], r[1].endsWith("END") && (r[1].startsWith("DEAD") || r[1].startsWith("ALIVE")));
            //    同一条探针按明文发 → MAC_REQUIRED（即补丁前 App 的行为）
            r = roundTrip(rel, com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE);
            assertEquals("MAC_REQUIRED", ExecProtocol.denyReason(r[1]));
            // ⑨ 签名正确但不是模板的 act=tmpl → TMPL_UNKNOWN，且 App 不会 legacy 重发
            r = roundTrip(rel, ExecProtocol.buildSignedTemplate("id", key, System.currentTimeMillis() / 1000L, ExecProtocol.newNonce()));
            assertEquals("77", r[0]);
            assertEquals("TMPL_UNKNOWN", ExecProtocol.denyReason(r[1]));
            assertFalse(ExecProtocol.isWhitelistDeny(ExecProtocol.denyReason(r[1])));
            String log = new String(Files.readAllBytes(new File(w, "exec.log").toPath()), StandardCharsets.UTF_8);
            assertTrue(log, log.contains("key provisioned"));
        } finally {
            p.destroy();
            p.waitFor();
            // 守护后台子 shell 可能还在：按 pidfile 再杀一次
            try {
                String pid = new String(Files.readAllBytes(new File(sec, "exec.pid").toPath()), StandardCharsets.UTF_8).trim();
                new ProcessBuilder("kill", pid).start().waitFor();
            } catch (Throwable ignored) {
            }
        }
    }
}
