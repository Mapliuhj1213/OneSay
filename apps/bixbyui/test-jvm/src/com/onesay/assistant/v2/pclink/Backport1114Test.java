package com.onesay.assistant.v2.pclink;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * [回补-1114] 电脑端 93bb42a 已有、开源版还缺的修复（三方对比 sync-xcheck 的 35 条不一致里开源版那一侧）。每组都带反例，确认正常写法不被误伤。
 * <ul>
 *   <li>主机名写法可疑（%2f、全角 ／ ？ ＃ ＠ 及变体）：原来判可信直接放行 → 每次确认、不能记住；电脑「从不询问」下拒绝。</li>
 *   <li>{@code cmd package grant/revoke/…} 和 {@code pm grant} 同一条红线；{@code magisk --sqlite} / {@code magisk policies} 也是红线（原正则永远匹配不上）。</li>
 *   <li>链接下载拒收看不见的字符（零宽、RLO、方向隔离符…，原 P2-9 观察）；链接整理把 U+2066–206F 当边界。</li>
 * </ul>
 * 三条硬红线（付款最后一下、远程批准、给 App 授权只生成脚本）不受影响。
 */
public class Backport1114Test {

    static final List<String> ALL_KINDS = Arrays.asList("phone.url.risky", "phone.url", "pc.url", "pc.url.risky", "phone.system_app",
            "phone.blocked_app.open", "pc.shell_exec");

    static LinkPolicy.Facts url(String u) {
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.url = LinkPolicy.checkUrl(u, Arrays.asList("example.com"));
        return f;
    }

    static final String[] ODD_HOSTS = {
            "https://evil.com%2f.example.com/", "https://evil.com%2F.example.com/x", "https://example.com\uff0fevil.com/",
            "https://example.com\uff1fx.example.com/", "https://evil.com\uff03.example.com/", "https://user\uff20example.com/",
            "https://evil.com\u2215.example.com/", "https://evil.com\u2044.example.com/", "https://evil.com\uff3c.example.com/",
    };

    @Test public void oddHostIsSuspiciousNotTrusted() {
        for (String u : ODD_HOSTS) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, Arrays.asList("example.com"));
            assertFalse(u, c.ok);
            assertFalse(u, c.trusted);
            assertFalse(u, c.malformed);
            assertEquals(u, LinkPolicy.UrlCheck.RISK_SUSPICIOUS, c.risk);
            assertTrue(u + " " + c.why, c.why.contains("守卫认不准"));
        }
    }

    @Test public void oddHostPhoneConfirmEveryTimeNotRememberable() {
        for (String u : ODD_HOSTS) {
            LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, url(u));
            assertEquals(u, LinkPolicy.Verdict.CONFIRM, d.verdict);
            assertFalse(u, d.rememberable());
            assertEquals(u, LinkPolicy.R_SUSPICIOUS_FORM, d.reason);
            assertEquals("记住过所有类型也照样问：" + u, LinkPolicy.Verdict.CONFIRM, LinkPolicy.remembered(d, ALL_KINDS).verdict);
        }
    }

    @Test public void oddHostPcConfirmOrDenyUnderNever() {
        for (String u : ODD_HOSTS) {
            for (LinkPolicy.Approval ap : new LinkPolicy.Approval[]{LinkPolicy.Approval.UNTRUSTED, LinkPolicy.Approval.ON_REQUEST}) {
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, ap), LinkPolicy.Cat.OPEN_URL, url(u));
                assertEquals(u + " " + ap, LinkPolicy.Verdict.CONFIRM, d.verdict);
                assertFalse(u + " " + ap, d.rememberable());
                assertEquals(LinkPolicy.Verdict.CONFIRM, LinkPolicy.remembered(d, ALL_KINDS).verdict);
            }
            LinkPolicy.Decision nv = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER), LinkPolicy.Cat.OPEN_URL, url(u));
            assertEquals("从不询问：问不了就拒 " + u, LinkPolicy.Verdict.DENY, nv.verdict);
        }
        assertEquals("写法可疑，守卫认不准，需要你确认", LinkPolicy.reasonZh(LinkPolicy.R_SUSPICIOUS_FORM));
    }

    @Test public void normalHostsNotHit() {
        // 信任列表里的正常主机照样直接放行
        for (String u : new String[]{"https://example.com/a", "https://sub.example.com/", "https://example.com/a%2fb?q=%23x", "https://example.com:8443/x"}) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, Arrays.asList("example.com"));
            assertTrue(u, c.ok);
            assertTrue(u, c.trusted);
            assertEquals(u, LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, url(u)).verdict);
        }
        // IPv6 方括号里的 %25zone 不算可疑；中文域名（IDN）不算可疑
        assertFalse(LinkPolicy.UrlCheck.RISK_SUSPICIOUS.equals(LinkPolicy.checkUrl("http://[fe80::1%25eth0]/", null).risk));
        LinkPolicy.UrlCheck idn = LinkPolicy.checkUrl("https://\u4f8b\u5b50.com/", null);
        assertTrue(idn.why, idn.ok);
        // 半角 @ 照旧是「带账号密码」（可记住的确认，不是这次的可疑写法）
        assertEquals(LinkPolicy.UrlCheck.RISK_CREDENTIALS, LinkPolicy.checkUrl("https://user:pw@example.com/", null).risk);
    }

    @Test public void cmdPackageAndMagiskAreRedLine() {
        String[] red = {"cmd package grant com.x android.permission.CAMERA", "cmd pack''age grant com.x a.b", "cmd package revoke com.x a.b",
                "cmd package set-permission-flags com.x a.b user-set", "cmd package set-installer com.x com.android.vending",
                "magisk --sqlite x", "magisk --sqlite \"select * from policies\"", "magisk policies", "pm grant com.x a.b"};
        for (String c : red) {
            assertEquals(c, LinkPolicy.Cat.SELF_GRANT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
            assertEquals(c, LinkPolicy.Verdict.SCRIPT, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_GRANT, new LinkPolicy.Facts()).verdict);
        }
        for (String c : new String[]{"cmd package list packages", "cmd package resolve-activity com.x", "magisk -v", "pm list packages", "ls /sdcard/policies"})
            assertNotEquals(c, LinkPolicy.Cat.SELF_GRANT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
    }

    /** 端到端：cmd package grant 从电脑发到手机，只生成脚本，不执行；正常的 cmd package list 照常执行。 */
    @Test public void cmdPackageGrantOnlyScriptEndToEnd() {
        AdvOddWritingTest.Rx rx = new AdvOddWritingTest.Rx();
        PcLanClient.Result r = AdvOddWritingTest.exec(rx, "cmd package grant com.x android.permission.CAMERA");
        assertTrue("没有执行：" + rx.ran, rx.ran.isEmpty());
        assertFalse("不是执行结果：" + r.text, "ran".equals(r.text));
        AdvOddWritingTest.Rx rx2 = new AdvOddWritingTest.Rx();
        AdvOddWritingTest.exec(rx2, "cmd package list packages");
        assertEquals(Arrays.asList("cmd package list packages"), rx2.ran);
    }

    @Test public void mediaCheckUrlRejectsInvisible() {
        for (String u : new String[]{"https://a.com/v\u200b", "https://a.com/\u202ex", "https://a.com/v\u2066", "https://a.com/v\u2069x", "https://a.com/v\u3000x",
                "https://a.com/v\u00a0x", "https://a.com/v\u2028", "https://a.com/v\u3164", "https://a.com/v\ufeff"}) {
            String why = PcMedia.checkUrl(u);
            assertNotNull(u, why);
        }
        assertTrue(PcMedia.checkUrl("https://a.com/\u202ex").contains("看不见"));
        for (String u : new String[]{"https://a.com/v", "https://a.com/v?t=1&x=2", "https://www.bilibili.com/video/BV1xx411c7mD?p=2", "https://a.com/%E4%B8%AD"})
            assertNull(u, PcMedia.checkUrl(u));
    }

    @Test public void tidyStopsAtIsolates() {
        assertEquals("https://a.com/v", PcMedia.tidy("https://a.com/v\u2066x"));
        assertEquals("https://a.com/v", PcMedia.tidy("https://a.com/v\u2069后面"));
        assertEquals("https://a.com/v", PcMedia.tidy("看这个“https://a.com/v”"));
        assertEquals("https://a.com/v?t=10", PcMedia.tidy("分享 https://a.com/v?t=10&utm_source=wx#f 来自"));
        assertNull(PcMedia.tidy("没有链接"));
    }
}
