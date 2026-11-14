package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 手机让电脑打开网址（LinkPolicy#checkUrl 的说明 + decide 里 OPEN_URL 一段）：
 * 只许 http / https；空 / 太长 / 控制字符 / 反斜杠 / 零宽 / 双向控制符 / 端口不对 = 格式不对（不开）；IP 字面量（含整数、十六进制、简写、IPv6）、
 * 内网主机、带账号密码、混合文字的仿冒域名 = 有风险（电脑上问你，「从不询问」拒；手机上 IP / 内网放行、其余问）；在信任列表（含子域）= 直接开；
 * 其余：分类档问你（pc.url），按需 / 从不只提醒。只读档一律 denied_by_level。
 */
@RunWith(Parameterized.class)
public class AdvUrlTest {

    static final String MAL = "MAL", OK_T = "OK_T", OK_U = "OK_U";
    static final String IP = LinkPolicy.UrlCheck.RISK_IP, PRIV = LinkPolicy.UrlCheck.RISK_PRIVATE, CRED = LinkPolicy.UrlCheck.RISK_CREDENTIALS,
            LOOK = LinkPolicy.UrlCheck.RISK_LOOKALIKE;

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    /** {网址, 信任列表, 期望, 期望主机（null = 不查）}。 */
    static List<Object[]> urls() {
        List<Object[]> u = new ArrayList<>();
        String none = "";
        String[] mal = {"", "   ", "example.com", "javascript:alert(1)", "JavaScript:alert(1)", "file:///C:/Windows/win.ini", "data:text/html,<b>x</b>",
                "ftp://example.com/", "vbscript:msgbox(1)", "ms-settings:privacy", "intent://x#Intent;end", "http:/example.com", "http:example.com", "https://",
                "https:///path", "http://exa mple.com/", "http://example.com/\u0000x", "http://example.com\\@evil.com/", "http://example.com/a\\b",
                "http://ex\u200bample.com/", "http://example.com/\u202e", "http://\ufeffexample.com/", "https://example.com:abc/", "https://example.com:123456/",
                "http://[::1/", "http://example.com/\tx", "http://.../", "http://exa\u007fmple.com/", "http://" + repeat('a', 64) + ".com/",
                "https://example.com/" + repeat('x', LinkPolicy.URL_MAX), "http://example.com/\r\nSet-Cookie:x", "\u202ehttps://example.com/"};
        for (String s : mal) u.add(new Object[]{s, none, MAL, null});
        String[][] ip = {{"http://127.0.0.1/", "127.0.0.1"}, {"http://2130706433/", "2130706433"}, {"http://0x7f000001/", "0x7f000001"},
                {"http://127.1/", "127.1"}, {"http://0177.0.0.1/", "0177.0.0.1"}, {"http://[::1]/", "[::1]"}, {"http://[::ffff:127.0.0.1]/", "[::ffff:127.0.0.1]"},
                {"http://192.168.1.1:8080/admin", "192.168.1.1"}, {"http://10.0.0.1", "10.0.0.1"}, {"http://8.8.8.8/", "8.8.8.8"}, {"http://1.2.3.4./", "1.2.3.4"},
                {"HTTP://172.16.0.1/", "172.16.0.1"}};
        for (String[] s : ip) u.add(new Object[]{s[0], none, IP, s[1]});
        String[][] priv = {{"http://localhost/", "localhost"}, {"http://LOCALHOST:3000/", "localhost"}, {"http://localhost./", "localhost"},
                {"http://router/", "router"}, {"http://nas.local/", "nas.local"}, {"http://x.lan/", "x.lan"}, {"http://db.internal/", "db.internal"},
                {"http://pi.home.arpa/", "pi.home.arpa"}, {"http://intranet/", "intranet"}, {"http://srv.corp/", "srv.corp"}, {"http://box.home/", "box.home"},
                {"http://foo.localhost/", "foo.localhost"}};
        for (String[] s : priv) u.add(new Object[]{s[0], none, PRIV, s[1]});
        for (String s : new String[]{"http://user:pass@example.com/", "https://a@example.com", "https://example.com:80@evil.com/", "https://@example.com/",
                "https://trusted.com@evil.com/"})
            u.add(new Object[]{s, "trusted.com", CRED, null});
        for (String s : new String[]{"https://аpple.com/", "https://раураl.com/", "https://xn--80ak6aa92e.com/", "https://gооgle.com/", "https://αpple.com/",
                "https://ѕсоре.com/", "https://аpple.example.com/"})
            u.add(new Object[]{s, "example.com", LOOK, null});
        String[][] okU = {{"https://example.com/", "example.com"}, {"HTTPS://EXAMPLE.COM/", "example.com"}, {"https://example.com:8443/x?y=1#z", "example.com"},
                {"https://例子.测试/", "xn--fsqu00a.xn--0zwm56d"}, {"https://münchen.de/", "xn--mnchen-3ya.de"}, {"https://日本語.jp/", "xn--wgv71a119e.jp"},
                {"http://example.com./", "example.com"}, {"https://example.com/a%20b", "example.com"}, {"https://sub.domain.example.org/a/b/c?x=1&y=2", "sub.domain.example.org"},
                {"http://xn--fsqu00a.xn--0zwm56d/", "xn--fsqu00a.xn--0zwm56d"}, {"https://example.com:/", "example.com"}, {"http://example.com/\u0000", "example.com"}, {"\thttps://example.com/\t", "example.com"},
                {"https://example.com.evil.com/", "example.com.evil.com"}, {"https://evilexample.com/", "evilexample.com"}};
        for (String[] s : okU) u.add(new Object[]{s[0], "trusted.com", OK_U, s[1]});
        String[][] okT = {{"https://example.com/", "example.com"}, {"https://www.example.com/x", "example.com"}, {"https://EXAMPLE.com", "example.com"},
                {"https://example.com./", "example.com"}, {"https://a.b.example.com:444/", "example.com"}, {"http://192.168.1.1/", "192.168.1.1"},
                {"http://nas.local/", "nas.local"}, {"https://例子.测试/", "例子.测试"}, {"https://x.例子.测试/", "*.例子.测试"}, {"http://[::1]/", "[::1]"}};
        for (String[] s : okT) u.add(new Object[]{s[0], s[1], OK_T, null});
        return u;
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("url");
        List<Object[]> us = urls();
        for (Object[] u : us) r.add("check " + u[0] + " trusted=" + u[1], "check", u[0], u[1], u[2], u[3], null);
        r.add("check null", "check", null, "", MAL, null, null);
        // 判定：挑每种情况的代表 × 9 个档位
        int k = 0;
        for (Object[] u : us) {
            if (k++ % 3 != 0) continue;
            for (LinkPolicy.Mode m : AdvSupport.allModes()) r.add("pc " + u[0] + " trusted=" + u[1] + " @" + m, "pc", u[0], u[1], u[2], null, m);
            r.add("phone " + u[0] + " trusted=" + u[1], "phone", u[0], u[1], u[2], null, null);
        }
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String url;
    @Parameterized.Parameter(3) public String trusted;
    @Parameterized.Parameter(4) public String expect;
    @Parameterized.Parameter(5) public String host;
    @Parameterized.Parameter(6) public LinkPolicy.Mode mode;

    @Test public void url() {
        List<String> tl = trusted.isEmpty() ? Collections.<String>emptyList() : LinkPolicy.parseDomains(trusted);
        LinkPolicy.UrlCheck u = LinkPolicy.checkUrl(url, tl);
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.url = u;
        switch (kind) {
            case "check":
                if (MAL.equals(expect)) {
                    assertTrue(name + " 应是格式不对：" + u.why, u.malformed);
                    assertFalse(u.ok);
                    assertEquals("", u.risk);
                } else if (OK_T.equals(expect) || OK_U.equals(expect)) {
                    assertTrue(name + " 应通过：" + u.why, u.ok);
                    assertEquals(name, OK_T.equals(expect), u.trusted);
                    assertFalse(u.malformed);
                } else {
                    assertFalse(name, u.ok);
                    assertFalse(name + " 是风险不是坏输入", u.malformed);
                    assertEquals(name + "：" + u.why, expect, u.risk);
                    assertFalse(u.trusted);
                }
                if (host != null) assertEquals(name, host, u.host);
                break;
            case "pc": {
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, mode, LinkPolicy.Cat.OPEN_URL, f);
                boolean never = mode.approval == LinkPolicy.Approval.NEVER;
                if (mode.sandbox == LinkPolicy.Sandbox.READ_ONLY) {
                    assertEquals(name, LinkPolicy.Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason);
                } else if (MAL.equals(expect)) {
                    assertEquals(name, LinkPolicy.Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_UNTRUSTED_URL, d.reason);
                } else if (OK_T.equals(expect)) {
                    assertEquals(name, LinkPolicy.Verdict.ALLOW, d.verdict);
                    assertFalse("信任的网址不提醒", d.headsUp);
                } else if (OK_U.equals(expect)) {
                    if (mode.approval == LinkPolicy.Approval.UNTRUSTED) {
                        assertEquals(name, LinkPolicy.Verdict.CONFIRM, d.verdict);
                        assertEquals("pc.url", d.kind);
                    } else {
                        assertEquals(name, LinkPolicy.Verdict.ALLOW, d.verdict);
                        assertTrue("不在信任列表：放行但提醒", d.headsUp);
                    }
                } else if (never) {
                    assertEquals(name, LinkPolicy.Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_UNTRUSTED_URL, d.reason);
                } else {
                    assertEquals(name, LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertEquals("pc.url.risky", d.kind);
                    assertTrue(d.rememberable());
                }
                break;
            }
            case "phone": {
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
                if (MAL.equals(expect)) assertEquals(name, LinkPolicy.Verdict.DENY, d.verdict);
                else if (CRED.equals(expect) || LOOK.equals(expect)) {
                    assertEquals(name, LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertEquals("phone.url.risky", d.kind);
                } else if (f.url != null && LinkPolicy.UrlCheck.RISK_IP.equals(f.url.risk) && LinkPolicy.encodedIp(f.url.host)) {
                    // [sync-1114] 按用户 11-14 原则（写法可疑的一律弹确认）和开源版 f46e99f 的 AdvUrlCheckTest 同步改期望：编码过的 IP（2130706433、0177.0.0.1…）
                    // 手机端从「放行」改成「弹不能记住的确认卡」。只会更严；规范四段 IP / IPv6 / 内网照旧放行（下一支）。
                    assertEquals(name + "（编码 IP 改成确认）", LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertFalse(name, d.rememberable());
                    assertEquals(name, LinkPolicy.R_SUSPICIOUS_FORM, d.reason);
                } else assertEquals(name + "（手机上 IP / 内网不拦）", LinkPolicy.Verdict.ALLOW, d.verdict);
                break;
            }
            default:
                throw new AssertionError(kind);
        }
    }
}
