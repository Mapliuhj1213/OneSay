package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 打开网址前的信任检查（LinkPolicy.checkUrl）与信任列表：每行一个网址（+ 可选信任列表），期望是
 * ok / trusted / malformed / ip / private / credentials / lookalike 之一（UrlCheck 注释：坏输入 malformed 任何时候都不开；
 * 有风险的按 risk 分类；trusted = 主机是信任域名或其子域）。另外核对两端（电脑 / 手机）对同一网址的处置。
 * 主机名里夹不换行空格 / 端口超过 65535 这几条放在 AdvUrlHostTest（2026-11-14 已修）。
 */
@RunWith(Parameterized.class)
public class AdvUrlCheckTest {

    /** 编码过的 IP 写法（规范四段十进制和 IPv6 以外的）：手机上不再静默放行，改弹不能记住的确认卡。 */
    static final java.util.Set<String> ENCODED_IP = new java.util.HashSet<String>(java.util.Arrays.asList(
            "http://2130706433/", "http://0x7f000001/", "http://0x7f.1/", "http://127.1/", "http://017700000001/"));

    static final String LONG = "https://example.com/" + new String(new char[2100]).replace('\0', 'a');

    @Parameterized.Parameters(name = "{index}: {0} → {1}")
    public static Collection<Object[]> data() {
        Object[][] rows = {
                // ---- ok
                {"https://example.com", "ok", ""}, {"http://example.com/path?q=1#f", "ok", ""}, {"https://example.com:8443/", "ok", ""},
                {"HTTPS://EXAMPLE.COM/", "ok", ""}, {"https://example.com./", "ok", ""}, {"https://例子.测试/", "ok", ""}, {"https://münchen.de/", "ok", ""},
                {"https://xn--mnchen-3ya.de/", "ok", ""}, {"https://a.com/路径", "ok", ""}, {"https://a.com/?q=a&b=c", "ok", ""}, {"https://a.com/%20x", "ok", ""},
                {"https://ｅｘａｍｐｌｅ．ｃｏｍ/", "ok", ""}, {"https://example。com/", "ok", ""}, {"https://россия.рф/", "ok", ""}, {"https://日本語.jp/", "ok", ""},
                {"https://abc日本.jp/", "ok", ""}, {"https://한국.kr/", "ok", ""}, {"https://ελλάδα.gr/", "ok", ""}, {"  https://example.com  ", "ok", ""},
                {"https://example.com:/", "ok", ""}, {"https://a-b.c-d.example/", "ok", ""}, {"https://a.com/\u00a0x", "ok", ""},
                {"https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=30", "ok", ""},
                // ---- malformed
                {"", "malformed", ""}, {"   ", "malformed", ""}, {"ftp://a.com/", "malformed", ""}, {"javascript:alert(1)", "malformed", ""},
                {"file:///etc/passwd", "malformed", ""}, {"data:text/html,<b>x</b>", "malformed", ""}, {"intent://x#Intent;end", "malformed", ""},
                {"content://x/y", "malformed", ""}, {"https:/a.com", "malformed", ""}, {"https:a.com", "malformed", ""}, {"https://", "malformed", ""},
                {"https:///path", "malformed", ""}, {"https://a.com\\@evil.com", "malformed", ""}, {"https://a .com", "malformed", ""},
                {"https://a.com/\nx", "malformed", ""}, {"https://a.com/\u200b", "malformed", ""}, {"https://\u202eexample.com", "malformed", ""},
                {"https://exa\ufeffmple.com", "malformed", ""}, {"https://a.com:abc/", "malformed", ""}, {"https://[::1/", "malformed", ""},
                {"https://a.com\t/", "malformed", ""}, {"https://a.com/\u007f", "malformed", ""}, {LONG, "malformed", ""}, {"example.com", "malformed", ""},
                {"//example.com", "malformed", ""}, {"https://a.com\r\n/", "malformed", ""}, {"vbscript:msgbox", "malformed", ""}, {"https://.", "malformed", ""},
                {"https://...", "malformed", ""}, {"https://" + new String(new char[70]).replace('\0', 'a') + ".com/", "malformed", ""},
                // ---- ip
                {"http://192.168.1.1/", "ip", ""}, {"http://127.0.0.1:8080/", "ip", ""}, {"http://2130706433/", "ip", ""}, {"http://0x7f000001/", "ip", ""},
                {"http://0x7f.1/", "ip", ""}, {"http://127.1/", "ip", ""}, {"http://[::1]/", "ip", ""}, {"http://[fe80::1]:80/", "ip", ""},
                {"http://1.2.3.4/", "ip", ""}, {"http://017700000001/", "ip", ""}, {"https://8.8.8.8/dns", "ip", ""}, {"http://10.0.0.1./", "ip", ""},
                // ---- private
                {"http://localhost/", "private", ""}, {"http://router/", "private", ""}, {"http://nas.local/", "private", ""}, {"http://x.lan/", "private", ""},
                {"http://a.internal/", "private", ""}, {"http://h.home.arpa/", "private", ""}, {"http://LOCALHOST:3000/", "private", ""},
                {"http://intranet/", "private", ""}, {"http://x.corp/", "private", ""}, {"http://box.home/", "private", ""}, {"http://app.localhost/", "private", ""},
                // ---- credentials
                {"https://user:pw@example.com/", "credentials", ""}, {"https://user@example.com/", "credentials", ""}, {"https://example.com@evil.com/", "credentials", ""},
                {"https://@example.com/", "credentials", ""}, {"http://a:b@192.168.1.1/", "credentials", ""}, {"https://user%40x@host.com/", "credentials", ""},
                {"https://a@b@c.com/", "credentials", ""}, {"https://u:p@[::1]/", "credentials", ""},
                // ---- lookalike
                {"https://аpple.com/", "lookalike", ""}, {"https://раураl.com/", "lookalike", ""}, {"https://ѕсоре.com/", "lookalike", ""},
                {"https://αοκ.com/", "lookalike", ""}, {"https://xn--80ak6aa92e.com/", "lookalike", ""}, {"https://gооgle.com/", "lookalike", ""},
                {"https://pаypal.com/", "lookalike", ""}, {"https://microsоft.com/", "lookalike", ""},
                // ---- 信任列表
                {"https://example.com/", "trusted", "example.com"}, {"https://a.b.example.com/", "trusted", "example.com"}, {"https://notexample.com/", "ok", "example.com"},
                {"https://example.com.evil.org/", "ok", "example.com"}, {"http://192.168.1.1/", "trusted", "192.168.1.1"}, {"http://192.168.1.10/", "ip", "192.168.1.1"},
                {"https://x.example.com/", "trusted", "*.example.com"}, {"https://example.com/", "trusted", "https://example.com/"},
                {"https://EXAMPLE.com/", "trusted", "Example.COM"}, {"https://example.com/", "ok", "exa mple.com"}, {"http://nas.local/", "trusted", "nas.local"},
                {"http://1.192.168.1.1/", "ok", "192.168.1.1"}, {"https://例子.测试/", "trusted", "例子.测试"}, {"https://xn--fsqu00a.xn--0zwm56d/", "trusted", "例子.测试"},
                {"https://user:pw@example.com/", "credentials", "example.com"}, {"https://аpple.com/", "lookalike", "аpple.com"},
        };
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (Object[] r : rows) {
            if (!seen.add(r[0] + "|" + r[2])) throw new IllegalStateException("dup " + r[0]);
            out.add(r);
        }
        return out;
    }

    private final String url, want, trust;

    public AdvUrlCheckTest(String url, String want, String trust) {
        this.url = url;
        this.want = want;
        this.trust = trust;
    }

    @Test public void check() {
        List<String> list = trust.length() == 0 ? Collections.<String>emptyList() : LinkPolicy.parseDomains(trust);
        LinkPolicy.UrlCheck u = LinkPolicy.checkUrl(url, list);
        String at = "[" + url + "] trust=" + trust + " → ok=" + u.ok + " malformed=" + u.malformed + " risk=" + u.risk + " trusted=" + u.trusted + " host=" + u.host;
        if (want.equals("ok") || want.equals("trusted")) {
            assertTrue(at, u.ok);
            assertEquals(at, want.equals("trusted"), u.trusted);
            assertEquals(at, "", u.risk);
            assertTrue(at, u.host.matches("[a-z0-9.\\-\\[\\]:]+"));
            assertFalse(at, u.host.endsWith("."));
        } else if (want.equals("malformed")) {
            assertFalse(at, u.ok);
            assertTrue(at, u.malformed);
            assertEquals(at, "", u.risk);
        } else {
            assertFalse(at, u.ok);
            assertFalse(at, u.malformed);
            assertEquals(at, want, u.risk);
        }
        // 两端处置：手机——只读的 IP / 内网放行，带账号 / 仿冒弹卡，坏输入拒；电脑（工作区可写 · 分类）——有风险的弹卡，坏输入拒，信任的直接开
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.url = u;
        LinkPolicy.Decision ph = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
        LinkPolicy.Decision pc = LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
        LinkPolicy.Decision pcNever = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER), LinkPolicy.Cat.OPEN_URL, f);
        if (want.equals("malformed")) {
            assertEquals(at, LinkPolicy.Verdict.DENY, ph.verdict);
            assertEquals(at, LinkPolicy.Verdict.DENY, pc.verdict);
            assertEquals(at, LinkPolicy.Verdict.DENY, pcNever.verdict);
        } else if (want.equals("ip") || want.equals("private")) {
            if (ENCODED_IP.contains(url)) {
                // [2026-11-14 修复 · 用户原则「写法可疑的弹出确认就行」] 编码过的 IP（整数 / 十六进制 / 八进制 / 少于 4 段）：手机上弹确认卡，且不能记住
                assertEquals(at, LinkPolicy.Verdict.CONFIRM, ph.verdict);
                assertFalse(at, ph.rememberable());
            } else {
                assertEquals(at, LinkPolicy.Verdict.ALLOW, ph.verdict);
            }
            assertEquals(at, "pc.url.risky", pc.kind);
            assertEquals(at, LinkPolicy.Verdict.DENY, pcNever.verdict);
        } else if (want.equals("credentials") || want.equals("lookalike")) {
            assertEquals(at, LinkPolicy.Verdict.CONFIRM, ph.verdict);
            assertEquals(at, "phone.url.risky", ph.kind);
            assertEquals(at, "pc.url.risky", pc.kind);
            assertEquals(at, LinkPolicy.Verdict.DENY, pcNever.verdict);
        } else if (want.equals("trusted")) {
            assertEquals(at, LinkPolicy.Verdict.ALLOW, pc.verdict);
            assertFalse(at, pc.headsUp);
            assertEquals(at, LinkPolicy.Verdict.ALLOW, pcNever.verdict);
        } else {
            assertEquals(at, LinkPolicy.Verdict.CONFIRM, pc.verdict);
            assertEquals(at, "pc.url", pc.kind);
            assertEquals(at, LinkPolicy.Verdict.ALLOW, pcNever.verdict);
            assertTrue(at + " 完全访问·从不：不在信任列表要提醒", pcNever.headsUp);
            assertEquals(at, LinkPolicy.Verdict.ALLOW, ph.verdict);
        }
    }
}
