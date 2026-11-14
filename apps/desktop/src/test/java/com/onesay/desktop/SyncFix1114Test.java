package com.onesay.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/**
 * [sync-1114] 手机个人版（943e4c8）/ 开源版（f46e99f）对同源代码的修复同步到电脑端后的单测。
 * 每个同步点几组用例，输入大多取自两边的 Fix1114 / Adv* 用例，保证电脑端对手机命令的判断、网址可疑判定、链接整理和手机端一致。
 *
 * <p>同步点：① 手机 sys_exec 红线（引号 / 转义 / 续行、cmd package、magisk --sqlite）② 手机命令写法可疑（两版并集）
 * ③ Facts.oddWriting 判定（手机卡不能记住、硬规则照旧、电脑从不询问下拒绝）④ 网址主机可疑（全角 ／ ？ ＃ ＠、%2f、规范化后非法字符）→ RISK_SUSPICIOUS
 * ⑤ 主机里的 Unicode 空白 / 零宽、端口 > 65535 → 坏输入 ⑥ 编码 IP 在手机上弹不能记住的卡 ⑦ 付款最后一下按钮 squash
 * ⑧ 链接整理去中文标点 / 零宽 / RTL ⑨ 链接下载拒收看不见的字符 ⑩ Json 严格数字 / \\u、verify 签不出来回 BAD_SIG。
 */
public class SyncFix1114Test {

    private static final List<String> NONE = Collections.emptyList();

    // ------------------------------------------------------------------ ① 手机 sys_exec 红线

    @Test public void phoneShellRedLineSeesThroughQuotesEscapesAndAliases() {
        String[] grant = {
                "'pm' grant com.x android.permission.CAMERA", "p''m grant com.x android.permission.CAMERA", "p\\m grant com.x android.permission.CAMERA",
                "pm \"grant\" com.x android.permission.CAMERA", "pm gr''ant com.x android.permission.CAMERA", "app''ops set com.x CAMERA allow",
                "d''pm set-device-owner com.x/.D", "pm \\\ngrant com.x android.permission.CAMERA", "\"p\"m grant com.x a.b",
                "cmd package grant com.x android.permission.CAMERA", "cmd  package  revoke com.x a.b", "'cmd' package set-installer com.x y",
                "cmd pack''age grant com.x a.b", "magisk --sqlite \"UPDATE policies SET policy=2\"", "magisk --sqlite 'select * from x'",
                "magisk  policies", "su -c magisk --sqlite x",
        };
        for (String c : grant)
            assertEquals("[" + c + "]", LinkPolicy.Cat.SELF_GRANT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
        // 手机被控端：红线照旧只生成脚本，带上可疑写法也不会降成确认
        for (String c : grant) {
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.oddWriting = LinkPolicy.phoneCmdSuspect(c);
            LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c), f);
            assertEquals(c, LinkPolicy.Verdict.SCRIPT, d.verdict);
            assertEquals(c, LinkPolicy.R_SCRIPT_GENERATED, d.reason);
        }
        // 卸载 / 停用换了写法也按任意命令处理，不会被当成简单设置
        for (String c : new String[]{"pm 'uninstall' com.onesay.assistant", "'pm' disable-user com.x", "pm clear 'com.x'", "p\\m hide com.x"})
            assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
        // 不误伤
        assertEquals(LinkPolicy.Cat.SIMPLE_SETTING, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, "settings put system screen_brightness 100"));
        assertEquals(LinkPolicy.Cat.NET_SEC_SETTING, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, "settings put global airplane_mode_on 1"));
        for (String c : new String[]{"pm list packages", "cmd package list packages", "magisk -v", "dumpsys battery", "echo grant"})
            assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
        // 规范化本身
        assertEquals("pm grant x", LinkPolicy.phoneShellNormalize("cmd package gr\\\nant 'x'"));
        assertEquals("pm grant x", LinkPolicy.unquoteShell("'pm'   \"grant\"\u00a0x"));
    }

    // ------------------------------------------------------------------ ② 手机命令写法可疑

    @Test public void phoneShellSuspiciousMatchesBothPhoneEditions() {
        // 两版都认的
        String[] both = {"p''m list", "echo $HOME", "ls `id`", "ls $(id)", "echo ${X}", "gr'an't x", "ls /sdcard/a\\ b"};
        for (String c : both) {
            assertNotEquals("个人版 [" + c + "]", "", LinkPolicy.phoneShellSuspicious(c));
            assertNotEquals("开源版 [" + c + "]", "", LinkPolicy.oddShellWriting(c));
            assertNotEquals("并集 [" + c + "]", "", LinkPolicy.phoneCmdSuspect(c));
        }
        // 只有个人版认的（eval / base64、引号没配对）、只有开源版认的（命令名加引号、零宽、全角、同形字、不换行空格）：并集都认
        String[] personal = {"eval ls", "echo aGk= | base64 -d | sh", "echo 'abc"};
        for (String c : personal) assertNotEquals(c, "", LinkPolicy.phoneShellSuspicious(c));
        String[] oss = {"'ls' /sdcard", "l\u200bs /sdcard", "ｌｓ /sdcard", "ls /sdc\u0430rd", "ls\u00a0/sdcard"};
        for (String c : oss) assertNotEquals(c, "", LinkPolicy.oddShellWriting(c));
        for (String c : personal) assertNotEquals(c, "", LinkPolicy.phoneCmdSuspect(c));
        for (String c : oss) assertNotEquals(c, "", LinkPolicy.phoneCmdSuspect(c));
        assertTrue(LinkPolicy.phoneCmdSuspect("echo $HOME").startsWith("命令里")); // 个人版的说法优先
        // 正常写法：两版都不问
        String[] normal = {"ls /sdcard", "input keyevent 26", "settings put system screen_brightness 100", "dumpsys battery | grep level",
                "am start -a android.intent.action.VIEW -d 'https://example.com/a?b=1'", "cmd wifi set-wifi-enabled enabled", "echo \"hello world\""};
        for (String c : normal) {
            assertEquals("个人版 [" + c + "]", "", LinkPolicy.phoneShellSuspicious(c));
            assertEquals("开源版 [" + c + "]", "", LinkPolicy.oddShellWriting(c));
            assertEquals("并集 [" + c + "]", "", LinkPolicy.phoneCmdSuspect(c));
        }
    }

    // ------------------------------------------------------------------ ③ oddWriting 判定

    @Test public void oddWritingOnPhoneIsUnrememberableConfirm() {
        String cmd = "echo $HOME";
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.oddWriting = LinkPolicy.phoneCmdSuspect(cmd);
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, cmd), f);
        assertEquals(LinkPolicy.Verdict.CONFIRM, d.verdict);
        assertFalse(d.rememberable());
        assertEquals("", d.kind);
        assertEquals(LinkPolicy.R_SUSPICIOUS_FORM, d.reason);
        assertTrue(d.text, d.text.contains(f.oddWriting));
        // 本来就要确认、可记住的（系统 App）也变成不能记住
        LinkPolicy.Facts g = new LinkPolicy.Facts();
        g.oddWriting = "命令写法可疑：全角字符";
        LinkPolicy.Decision s = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SYSTEM_APP_OP, g);
        assertEquals(LinkPolicy.Verdict.CONFIRM, s.verdict);
        assertFalse(s.rememberable());
        // 硬规则照旧
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_AUTHORIZE, g).verdict);
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.PAY_FINAL, g).verdict);
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.REMOTE_APPROVAL, g).verdict);
        // 正常写法不受影响
        LinkPolicy.Facts n = new LinkPolicy.Facts();
        n.oddWriting = LinkPolicy.phoneCmdSuspect("ls /sdcard");
        assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, n).verdict);
    }

    @Test public void oddWritingOnPcFollowsSuspectRule() {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.oddWriting = "命令写法可疑：同形字";
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, f);
        assertEquals(LinkPolicy.Verdict.CONFIRM, d.verdict);
        assertFalse(d.rememberable());
        assertEquals(LinkPolicy.R_SUSPECT, d.reason);
        assertTrue(d.text.contains("同形字"));
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values()) {
            LinkPolicy.Decision nv = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(sb, LinkPolicy.Approval.NEVER), LinkPolicy.Cat.SHELL_EXEC, f);
            assertEquals(sb + " 从不询问", LinkPolicy.Verdict.DENY, nv.verdict);
        }
        // suspect 和 oddWriting 都有时原因合在一起
        f.suspect = "路径里有 8.3 短名";
        assertTrue(LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, f).text.contains("8.3 短名；命令写法可疑"));
        // 硬规则照旧：自授权拒绝、授权只生成脚本
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_AUTHORIZE, f).verdict);
        assertEquals(LinkPolicy.Verdict.SCRIPT, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_GRANT, f).verdict);
        // 手机回来的新原因码电脑能翻成中文
        assertNotEquals("", LinkPolicy.reasonZh(LinkPolicy.R_SUSPICIOUS_FORM));
    }

    // ------------------------------------------------------------------ ④ 网址主机可疑

    @Test public void oddHostCharsAreSuspicious() {
        List<String> trusted = LinkPolicy.parseDomains("example.com");
        String[] odd = {"https://example.com\uff0fevil.com/", "https://evil.com\uff1f.example.com/", "https://evil.com\uff03.example.com/",
                "https://user\uff20example.com/", "https://evil.com%2f.example.com/", "https://example.com\u2215evil/", "https://a\ufe6bb.com/"};
        for (String u : odd) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, trusted);
            assertFalse(u, c.ok);
            assertFalse(u, c.trusted);
            assertFalse(u + " 不是坏输入而是可疑", c.malformed);
            assertEquals(u, LinkPolicy.UrlCheck.RISK_SUSPICIOUS, c.risk);
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = c;
            for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, ap), LinkPolicy.Cat.OPEN_URL, f);
                if (ap == LinkPolicy.Approval.NEVER) assertEquals(u, LinkPolicy.Verdict.DENY, d.verdict);
                else {
                    assertEquals(u + " " + ap, LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertFalse(u, d.rememberable());
                    assertEquals(LinkPolicy.R_SUSPICIOUS_FORM, d.reason);
                }
            }
            LinkPolicy.Decision p = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
            assertEquals(u, LinkPolicy.Verdict.CONFIRM, p.verdict);
            assertFalse(u, p.rememberable());
            assertEquals(LinkPolicy.R_SUSPICIOUS_FORM, p.reason);
        }
        // 不误伤：普通域名、全角句号（浏览器也当 .）、IPv6 带 zone
        assertTrue(LinkPolicy.checkUrl("https://example.com/a?b=%2f", trusted).trusted);
        LinkPolicy.UrlCheck dot = LinkPolicy.checkUrl("https://example\u3002com/", trusted);
        assertTrue(dot.ok);
        assertEquals("example.com", dot.host);
        assertNotEquals(LinkPolicy.UrlCheck.RISK_SUSPICIOUS, LinkPolicy.checkUrl("http://[fe80::1%25eth0]/", NONE).risk);
        assertTrue(LinkPolicy.checkUrl("https://\u4f8b\u5b50.com/", NONE).ok);
    }

    // ------------------------------------------------------------------ ⑤ 主机里的 Unicode 空白 / 端口

    @Test public void unicodeSpaceInHostAndBigPortAreMalformed() {
        for (String u : new String[]{"https://exa\u00a0mple.com/", "https://example.com\u3000/", "https://exa\u2009mple.com/", "https://exa\u2060mple.com/",
                "https://example.com:65536/", "https://example.com:99999/"}) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, NONE);
            assertTrue(u, c.malformed);
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = c;
            assertEquals(u, LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        }
        assertTrue(LinkPolicy.checkUrl("https://example.com:65535/", NONE).ok);
        assertTrue(LinkPolicy.checkUrl("https://example.com:/", NONE).ok);
        assertFalse("路径里的照旧交给浏览器", LinkPolicy.checkUrl("https://a.com/\u00a0x", NONE).malformed);
    }

    // ------------------------------------------------------------------ ⑥ 编码 IP

    @Test public void encodedIpOnPhoneIsUnrememberableConfirm() {
        for (String h : new String[]{"2130706433", "0x7f000001", "0x7f.1", "127.1", "0177.0.0.1", "017700000001"}) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl("http://" + h + "/", NONE);
            assertEquals(h, LinkPolicy.UrlCheck.RISK_IP, c.risk);
            assertTrue(h, LinkPolicy.encodedIp(c.host));
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = c;
            LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
            assertEquals(h, LinkPolicy.Verdict.CONFIRM, d.verdict);
            assertFalse(h, d.rememberable());
            assertEquals(LinkPolicy.R_SUSPICIOUS_FORM, d.reason);
        }
        for (String h : new String[]{"127.0.0.1", "8.8.8.8", "192.168.1.1", "[::1]", "10.0.0.255"}) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl("http://" + h + "/", NONE);
            assertFalse(h, LinkPolicy.encodedIp(c.host));
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.url = c;
            assertEquals(h + " 规范写法照旧放行", LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        }
        assertFalse(LinkPolicy.encodedIp("example.com"));
        assertFalse(LinkPolicy.encodedIp(null));
    }

    // ------------------------------------------------------------------ ⑦ 付款最后一下

    @Test public void finalPayLabelSquashesVariants() {
        for (String s : new String[]{"確認支付", "P\u0430y Now", "\uff30\uff41\uff59\uff2e\uff4f\uff57", "确认\u200d支付", "立即 支付", "確認轉賬", "PLACE ORDER",
                "Place-Order", "支付￥28.00"})
            assertTrue(s, LinkPolicy.finalPayLabel(s));
        for (String s : new String[]{"加入购物车", "查看订单", "支付方式", "確認收貨", "支付寶", ""})
            assertFalse(s, LinkPolicy.finalPayLabel(s));
        assertEquals("立即支付", SafetyText.squash("立即 · 支付"));
        assertTrue(SafetyText.isInvisible('\u202e'));
    }

    // ------------------------------------------------------------------ ⑧ 链接整理

    @Test public void tidyCutsAtChinesePunctuationInvisibleAndRtl() {
        String want = "https://a.com/v";
        String[] texts = {"看这个“https://a.com/v”", "《https://a.com/v》", "「https://a.com/v」好看", "『https://a.com/v』", "〈https://a.com/v〉",
                "‘https://a.com/v’", "https://a.com/v：快看", "https://a.com/v\u3000后面", "https://a.com/v\u00a0后面", "https://a.com/v\u200b后面",
                "https://a.com/v\u202egnp.4pm", "https://a.com/v\u2066x", "https://a.com/v\ufeff", "https://a.com/v…", "https://a.com/v。", "https://a.com/v?&"};
        for (String t : texts) assertEquals(t, want, MediaArgs.tidy(t));
        // 原来的行为不变：保留正常参数、去跟踪参数和 #
        assertEquals("https://a.com/v?t=10&list=x", MediaArgs.tidy("分享 https://a.com/v?t=10&utm_source=wx&list=x#frag 来自 App"));
        assertNull(MediaArgs.tidy("没有链接"));
        for (String t : texts) assertNull(t, MediaArgs.checkUrl(MediaArgs.tidy(t)));
    }

    // ------------------------------------------------------------------ ⑨ 链接下载拒收看不见的字符

    @Test public void mediaCheckUrlRejectsInvisibleAndDirectionChars() {
        for (String u : new String[]{"https://a.com/v\u200b", "https://a.com/\u202egnp.4pm", "https://a.com/v\u2066", "https://a.com/v\u00ad", "https://a.com/v\u3000x",
                "https://a.com/v\u2028", "https://a.com/v\ufeff", "https://a.com/v\u3164"}) {
            String why = MediaArgs.checkUrl(u);
            assertNotNull(u, why);
            assertTrue(u + " → " + why, why.contains("看不见"));
        }
        assertNull(MediaArgs.checkUrl("https://www.youtube.com/watch?v=abc&t=10"));
        assertNull(MediaArgs.checkUrl("https://b23.tv/AbC%E4%B8%AD"));
    }

    // ------------------------------------------------------------------ ⑩ Json / Protocol

    @Test public void jsonOnlyAcceptsRfcNumbersAndHexEscapes() {
        for (String bad : new String[]{"01", "+1", ".5", "1.", "-", "1e", "1e400", "-1e400", "[01]", "{\"a\":+1}", "\"\\u+123\"", "\"\\u12G4\"", "\"\\u-001\"", "--1"}) {
            try {
                Json.parse(bad);
                fail("应拒绝 " + bad);
            } catch (IllegalArgumentException expected) {
                // 只抛 IAE（收帧循环本来就接这个）
            }
        }
        assertEquals(0L, Json.parse("0"));
        assertEquals(-0.5, (Double) Json.parse("-0.5"), 0);
        assertEquals(1e10, (Double) Json.parse("1e10"), 0);
        assertEquals(1e-3, (Double) Json.parse("1E-3"), 0);
        assertEquals(123456789012L, Json.parse("123456789012"));
        assertEquals("é", Json.parse("\"\\u00e9\""));
        assertEquals("é", Json.parse("\"\\u00E9\""));
        // 往返照常
        Map<String, Object> m = new TreeMap<>();
        m.put("n", 30L);
        m.put("d", 0.25);
        m.put("s", "中文\u0001");
        assertEquals(m, Json.parse(Json.stringify(m)));
    }

    @Test public void verifyReturnsBadSigInsteadOfThrowing() {
        byte[] key = Protocol.newKey();
        Map<String, Object> body = new TreeMap<>();
        body.put("v", 1L);
        body.put("ts", System.currentTimeMillis());
        body.put("nonce", Protocol.nonce());
        body.put("x", Double.POSITIVE_INFINITY);
        body.put("sig", "AAAA");
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(key, "id1", "a", "b", body, System.currentTimeMillis(), null));
        body.put("x", Double.NaN);
        assertEquals(Protocol.Check.BAD_SIG, Protocol.verify(key, "id1", "a", "b", body, System.currentTimeMillis(), new Protocol.ReplayGuard()));
    }
}
