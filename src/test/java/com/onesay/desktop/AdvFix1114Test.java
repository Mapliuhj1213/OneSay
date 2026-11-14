package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [fix-1114] 修对抗测试（ADV-DESKTOP-1114）和实测（PC-TEST-1114）问题时新加的用例：守住修法本身、守住不误伤（反例）、
 * 守住用户 2026-11-14 12:46 定的原则「这类问题弹出确认就行」——写法可疑、守卫认不准的升级为确认卡，不静默执行，卡上写一句原因，
 * 这类确认不能被「以后这类不用问」记住；三条硬红线（永不提权、管理员只生成脚本、禁止自授权）照旧。
 */
@RunWith(Parameterized.class)
public class AdvFix1114Test {

    interface Check { void run() throws Exception; }

    static final List<String> NONE = Collections.<String>emptyList();
    static final LinkPolicy.Rules RULES = new LinkPolicy.Rules(true, NONE, NONE);
    static final LinkPolicy.Rules NO_ENC = new LinkPolicy.Rules(false, NONE, NONE);

    static LinkPolicy.Rules net(List<String> allow) {
        LinkPolicy.Rules r = new LinkPolicy.Rules(true, allow, NONE);
        r.net = true;
        return r;
    }

    static String code(String c, LinkPolicy.Rules r) {
        LinkPolicy.Hit h = LinkPolicy.inspectCommand(c, r);
        return h == null ? null : h.code;
    }

    static LinkPolicy.Mode mode(LinkPolicy.Sandbox s, LinkPolicy.Approval a) { return new LinkPolicy.Mode(s, a); }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("fix-1114");

        // ---------------- B1 / B16：-EncodedCommand 的任意前缀、短载荷、– — 开头；只在 powershell / pwsh 里认
        String[] pre = {"e", "ec", "en", "enc", "enco", "encod", "encode", "encoded", "encodedc", "encodedco", "encodedcom", "encodedcomm", "encodedcomma", "encodedcomman", "encodedcommand"};
        for (String p : pre) {
            String runas = "powershell -" + p + " " + AdvSupport.b64ps("Start-Process cmd -Verb RunAs");
            r.add("B1 插件关掉编码拦截也解码查提权 -" + p, (Check) () -> assertEquals(runas, LinkPolicy.R_PLUGIN_BLOCKED, code(runas, NO_ENC)));
            String reg = "pwsh -NoProfile -" + p.toUpperCase() + " " + AdvSupport.b64ps("reg add HKLM\\Software\\x /f");
            r.add("B1 解出来的 HKLM 归要管理员 -" + p, (Check) () -> assertEquals(reg, LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, reg)));
        }
        for (String dash : new String[]{"\u2013", "\u2014"}) {
            String c = "powershell " + dash + "enc " + AdvSupport.b64ps("calc");
            r.add("B1 破折号开头 " + c, (Check) () -> assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code(c, RULES)));
        }
        r.add("B1 -ec: 冒号写法", (Check) () -> assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("powershell -ec:" + AdvSupport.b64ps("calc"), RULES)));
        for (String c : new String[]{"powershell -enc YwA=", "powershell -e YwBhAA==", "cmd /c powershell.exe -en YwBhAGwAYwA="})
            r.add("B16 短载荷 " + c, (Check) () -> assertEquals(c, LinkPolicy.R_PLUGIN_BLOCKED, code(c, RULES)));
        // 反例：不是 PowerShell 的 -e、PowerShell 自己别的以 e 开头的参数，不当编码命令
        for (String c : new String[]{"grep -e abcdefghijklmnopqrstuvwxyz0123 notes.txt", "findstr /e abc x.txt", "git commit -e abcdefghijklmnopqrstuvwxyz",
                "powershell -ExecutionPolicy Bypass -File scripts\\x.ps1", "powershell -ea 0 -c Get-ChildItem", "powershell -c Get-Content a.txt -Encoding UTF8",
                "pwsh -c Get-Process -ErrorAction SilentlyContinue", "powershell -NoExit -c dir", "powershell -EnableNetworkIsolation"})
            r.add("B1 反例不误伤 " + c, (Check) () -> assertNull(c, code(c, RULES)));

        // ---------------- B2：取主机先截 # ? \ 再按 @ 切
        List<String> allowEx = Collections.singletonList("example.com");
        for (String u : new String[]{"https://evil.com#@example.com/x", "https://evil.com?@example.com/x", "https://evil.com\\@example.com/x", "http://evil.com#x@example.com",
                "https://example.com@evil.com/x", "https://user:pw@evil.com:8443/x"})
            r.add("B2 真实主机不在白名单 " + u, (Check) () -> assertEquals(u, LinkPolicy.R_PLUGIN_BLOCKED, code("curl -sL " + u, net(allowEx))));
        for (String u : new String[]{"https://example.com/x#@evil.com", "https://example.com/?a=@evil.com", "https://user@example.com/x", "https://sub.example.com:8443/x?y=1#z"})
            r.add("B2 真实主机在白名单 " + u, (Check) () -> assertNull(u, code("curl -sL " + u, net(allowEx))));
        r.add("B2 cleanHost", (Check) () -> {
            assertEquals("evil.com", LinkPolicy.cleanHost("evil.com#@example.com"));
            assertEquals("evil.com", LinkPolicy.cleanHost("evil.com?@example.com"));
            assertEquals("evil.com", LinkPolicy.cleanHost("a:b@evil.com:443"));
            assertEquals("[::1]", LinkPolicy.cleanHost("[::1]"));
        });

        // ---------------- B3：可能联网 → 确认卡写原因；反例不加提示
        for (String c : new String[]{"git fetch https://evil.com/x.git", "ssh user@evil.com", "Resolve-DnsName evil.com", "ping evil.com",
                "[System.Net.Http.HttpClient]::new()", "winget install foo", "pip install requests", "node -e fetch('http://x')",
                "Start-Process msedge https://evil.com/", "rundll32 url.dll,FileProtocolHandler https://evil.com"})
            r.add("B3 可能联网 " + c, (Check) () -> assertTrue(c, LinkPolicy.netMaybeReason(LinkPolicy.normalizeCommand(c), NONE).startsWith("此命令可能联网")));
        for (String c : new String[]{"ping 127.0.0.1", "ping -n 1 localhost", "nslookup localhost", "dir C:\\Users", "Get-ChildItem", "echo hi > a.txt",
                "notepad readme.txt", "git status", "git log -5", "python scripts\\x.py"})
            r.add("B3 不像联网 " + c, (Check) () -> assertEquals(c, "", LinkPolicy.netMaybeReason(LinkPolicy.normalizeCommand(c), NONE)));
        r.add("B3 白名单里的网址不加提示", (Check) () -> assertEquals("", LinkPolicy.netMaybeReason(LinkPolicy.normalizeCommand("Start-Process msedge https://example.com/x"), allowEx)));

        // ---------------- 原则：可疑 → 每次确认、不能记住、从不询问下拒绝；硬红线照旧
        for (LinkPolicy.Mode m : AdvSupport.allModes()) {
            r.add("原则 SHELL_EXEC+可疑 " + m, (Check) () -> {
                LinkPolicy.Facts f = new LinkPolicy.Facts();
                f.suspect = "此命令可能联网（x）";
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, m, LinkPolicy.Cat.SHELL_EXEC, f);
                if (m.sandbox == LinkPolicy.Sandbox.READ_ONLY || m.approval == LinkPolicy.Approval.NEVER) {
                    assertEquals(m + " " + d, LinkPolicy.Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason);
                } else {
                    assertEquals(m + " " + d, LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertEquals("不能记住", "", d.kind);
                    assertFalse(d.rememberable());
                    assertEquals(LinkPolicy.R_SUSPECT, d.reason);
                    assertTrue(d.text, d.text.contains("此命令可能联网"));
                    List<String> all = java.util.Arrays.asList(AdvSupport.ALL_KINDS);
                    assertEquals("记住过所有类型也不跳过", LinkPolicy.Verdict.CONFIRM, LinkPolicy.remembered(d, all).verdict);
                }
            });
            r.add("原则 工作区写+可疑（原本直接写）" + m, (Check) () -> {
                LinkPolicy.Facts f = new LinkPolicy.Facts();
                f.suspect = "路径里有 8.3 短名";
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, m, LinkPolicy.Cat.WORKSPACE_WRITE, f);
                LinkPolicy.Verdict want = m.sandbox == LinkPolicy.Sandbox.READ_ONLY || m.approval == LinkPolicy.Approval.NEVER ? LinkPolicy.Verdict.DENY : LinkPolicy.Verdict.CONFIRM;
                assertEquals(m + " " + d, want, d.verdict);
                assertEquals("", d.kind);
            });
            for (LinkPolicy.Cat hard : new LinkPolicy.Cat[]{LinkPolicy.Cat.SYSTEM_A, LinkPolicy.Cat.SELF_GRANT, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.Cat.PAY_FINAL, LinkPolicy.Cat.REMOTE_APPROVAL})
                r.add("原则 硬红线不受可疑影响 " + hard + " " + m, (Check) () -> {
                    LinkPolicy.Facts f = new LinkPolicy.Facts();
                    LinkPolicy.Decision base = LinkPolicy.decide(LinkPolicy.Target.PC, m, hard, f);
                    f.suspect = "x";
                    LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, m, hard, f);
                    assertEquals(base.toString(), d.toString());
                    assertTrue(d.verdict == LinkPolicy.Verdict.DENY || d.verdict == LinkPolicy.Verdict.SCRIPT);
                });
        }
        r.add("原则 插件命中优先于可疑（提权照旧拒）", (Check) () -> {
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.suspect = "x";
            f.pluginBlocked = "想提权";
            f.pluginReason = LinkPolicy.R_PLUGIN_BLOCKED;
            assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHELL_EXEC, f).verdict);
        });
        r.add("原则 手机作被控端不受影响", (Check) () -> {
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.suspect = "x";
            assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.HARMLESS, f).verdict);
        });

        // ---------------- B4：8.3 短名
        for (String p : new String[]{"GIT~1/hooks/pre-commit", "a\\GIT~2\\config", "CODEX~1/x", "PROGRA~1/x.txt", "x~9.txt"})
            r.add("B4 含~数字的路径段按只读 " + p, (Check) () -> {
                assertTrue(p, LinkPolicy.protectedWorkspacePath(p));
                assertTrue(p, LinkPolicy.shortNameReason(p, true).contains("8.3 短名"));
            });
        for (String p : new String[]{"notes/a~b.txt", "~draft.md", "docs/readme.md", "a/b~/c.txt"})
            r.add("B4 反例 " + p, (Check) () -> {
                assertFalse(p, LinkPolicy.protectedWorkspacePath(p));
                assertEquals(p, "", LinkPolicy.shortNameReason(p, true));
            });
        r.add("B4 记住过「改 .git」也照样问短名路径、不能记住", (Check) () -> {
            Config cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
            PcGuard g = new PcGuard(cfg, null);
            g.remember("pc.workspace.protected");
            AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.REMEMBER);
            g.confirmer = st;
            PcGuard.Outcome o = g.check("phone-a", "file_write", AdvSupport.m("path", "GIT~1/hooks/pre-commit", "text", "x"));
            assertEquals(1, st.asked.size());
            assertEquals("", st.kinds.get(0));
            assertTrue(st.asked.get(0), st.asked.get(0).contains("8.3 短名"));
            assertNotNull("确认后才放行", o.pass);
            assertEquals(Boolean.TRUE, o.args.get("_protected_ok"));
            // 普通 .git 路径：记住过就不问（原行为不变）
            AdvSupport.Stub st2 = new AdvSupport.Stub(PcGuard.DENY);
            g.confirmer = st2;
            assertNull(g.check("phone-a", "file_write", AdvSupport.m("path", ".git/config", "text", "x")).reply);
            assertEquals(0, st2.asked.size());
        });
        r.add("B4 完全访问 · 从不询问：短名路径拒绝（不静默写）", (Check) () -> {
            PcGuard g = new PcGuard(AdvSupport.cfg(mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER)), null);
            AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.ALLOW);
            g.confirmer = st;
            PcGuard.Outcome o = g.check("phone-a", "file_write", AdvSupport.m("path", "GIT~1/config", "text", "x"));
            assertNotNull(o.reply);
            assertEquals(0, st.asked.size());
        });
        r.add("B4 完全访问 · 分类：普通工作区写照旧直接写；短名要问", (Check) () -> {
            PcGuard g = new PcGuard(AdvSupport.cfg(mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.UNTRUSTED)), null);
            AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
            g.confirmer = st;
            assertNull(g.check("phone-a", "file_write", AdvSupport.m("path", "notes/a.txt", "text", "x")).reply);
            assertEquals(0, st.asked.size());
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, AdvSupport.reason(g.check("phone-a", "file_write", AdvSupport.m("path", "docs~1/a.txt", "text", "x"))));
            assertEquals(1, st.asked.size());
        });
        r.add("B4 命令里的短名：要确认、不能记住", (Check) () -> {
            Config cfg = AdvSupport.cfg(mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.UNTRUSTED));
            PcGuard g = new PcGuard(cfg, null);
            g.remember("pc.shell_exec");
            AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
            g.confirmer = st;
            g.check("phone-a", "sys_exec", AdvSupport.m("command", "echo x > GIT~1\\hooks\\pre-commit"));
            assertEquals(1, st.asked.size());
            assertEquals("", st.kinds.get(0));
            assertTrue(st.asked.get(0).contains("⚠"));
            AdvSupport.Stub st2 = new AdvSupport.Stub(PcGuard.DENY);
            g.confirmer = st2;
            assertNull("普通命令：记住过 pc.shell_exec 就不问（原行为）", g.check("phone-a", "sys_exec", AdvSupport.m("command", "echo x > a.txt")).reply);
            assertEquals(0, st2.asked.size());
        });

        // ---------------- B5：.ps1 剥注释按记号规则；认不准按全文
        String[][] keep = {
                {"双引号里的 # 后面是代码", "Write-Output \"a#b\"; reg add HKLM\\Software\\x /f"},
                {"单引号里的 <#", "$a='<#'; Start-Process cmd -Verb RunAs; $b='#>'"},
                {"智能引号里的 <#", "$a=\u2018<#\u2019\nStart-Process cmd -Verb RunAs\n$b=\u2018#>\u2019"},
                {"引号没配对 → 按全文", "Write-Output 'abc\nreg add HKLM\\Software\\x /f # 'x"},
                {"块注释没结束 → 按全文", "<# 说明\nreg add HKLM\\Software\\x /f"},
                {"词中间的 <# → 按全文", "Write-Output x<#\nStart-Process cmd -Verb RunAs\n#>"},
                {"here-string 没结束 → 按全文", "$t=@'\nabc\nreg add HKLM\\Software\\x /f"},
                {"反引号转义的 #", "Write-Output `#; Start-Process cmd -Verb RunAs"}};
        for (String[] k : keep) r.add("B5 看得见 " + k[0], (Check) () -> assertTrue(k[1], LinkPolicy.shortcutBodyBlocked(k[1]).length() > 0));
        String[][] strip = {
                {"行首注释", "# Start-Process cmd -Verb RunAs\nGet-Date"},
                {"行尾注释", "Get-Date # reg add HKLM\\Software\\x"},
                {"块注释", "<# Start-Process -Verb RunAs\nreg add HKLM\\x #>\nGet-Date"},
                {"分号后注释", "Get-Date;# RunAs"},
                {"引号里的 # 不吞后面的注释判断", "Write-Output 'a#b' # RunAs"},
                {"here-string 正常结束后的注释", "$t=@'\nhello\n'@\n# RunAs"}};
        for (String[] k : strip) r.add("B5 注释照样不算 " + k[0], (Check) () -> assertEquals(k[1], "", LinkPolicy.shortcutBodyBlocked(k[1])));
        r.add("B5 .bat 不受影响", (Check) () -> assertEquals("", LinkPolicy.shortcutBodyBlocked("REM runas\r\necho hi\r\n", "x.bat", null)));

        // ---------------- B6–B12 反例（不误伤普通命令）
        for (String c : new String[]{"[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\\x')", "net1 view", "Get-Service spooler", "sc query spooler",
                "schtasks /create /tn x /tr calc /sc daily", "Register-ScheduledTask x -Action $a", "psexecutor.exe", "Get-Process notepad | Stop-Process",
                "kill -name notepad", "wmic process where name='notepad.exe' delete"})
            r.add("B6-B12 反例普通命令 " + c, (Check) () -> assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c)));
        for (String c : new String[]{"sc.exe \\\\pc2 config spooler start= disabled", "Stop-Service -Name spooler", "NET1 START spooler",
                "Register-ScheduledTask x -Action $a -User S-1-5-18", "[Microsoft.Win32.RegistryKey]::OpenRemoteBaseKey('LocalMachine','pc2')"})
            r.add("B6-B8 补充正例 " + c, (Check) () -> assertEquals(c, LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c)));
        for (String c : new String[]{"paexec -s cmd", "PsExec64.exe \\\\pc2 cmd"})
            r.add("B9 补充 " + c, (Check) () -> assertEquals(c, LinkPolicy.R_PLUGIN_BLOCKED, code(c, NO_ENC)));
        for (String c : new String[]{"gps OneSay | spps", "taskkill /im OneSay.exe /f", "Get-CimInstance Win32_Process -Filter \"name='OneSay.exe'\" | Invoke-CimMethod -MethodName Terminate",
                "type %LOCALAPPDATA%\\OneSay\\tools\\tools.json", "del C:\\Users\\me\\AppData\\Local\\OneSay\\tools\\yt-dlp.exe"})
            r.add("B10/B12 补充 " + c, (Check) () -> assertEquals(c, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c)));
        for (String c : new String[]{"dir C:\\Users\\me\\OneSay\\Downloads", "notepad C:\\Users\\me\\OneSay\\workspace\\a.txt", "dir %LOCALAPPDATA%\\Programs"})
            r.add("B12 反例（下载 / 工作区不算自己的配置）" + c, (Check) () -> assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c)));
        r.add("B12 rules() 带上名叫 OneSay 的数据目录", (Check) () -> {
            Path dir = Files.createTempDirectory("advfix").resolve("OneSay");
            Files.createDirectories(dir);
            Config c = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
            Config c2 = new Config(dir.resolve("desktop.properties"));
            c2.workspaceDir = c.workspaceDir;
            List<String> self = new PcGuard(c2, null).rules().selfPaths;
            assertTrue(self.toString(), self.contains(dir.toAbsolutePath().toString() + File.separator));
            assertEquals("临时目录名不叫 OneSay 时只有配置文件本身", 1, new PcGuard(c, null).rules().selfPaths.size());
        });
        for (String p : new String[]{"comic.txt", "com10.txt", "lpt.log", "com\u00b9x.txt"})
            r.add("B11 反例 " + p, (Check) () -> assertNotNull(p, LinkPolicy.resolveInside(Files.createTempDirectory("advfix11").toFile(), p)));
        for (String p : new String[]{"LPT\u00b9", "lpt\u00b3.txt", "sub/COM\u00b2.log"})
            r.add("B11 补充 " + p, (Check) () -> assertNull(p, LinkPolicy.resolveInside(Files.createTempDirectory("advfix11").toFile(), p)));

        // ---------------- B13：here-string 转义
        for (String t : new String[]{"'@", "x\r\n'@\r\ny", "\r'@", "a\n'@ \nb"})
            r.add("B13 补充 ⟨" + AdvSupport.label(t) + "⟩", (Check) () -> {
                Config cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
                String rel = new PcGuard(cfg, null).script("phone-a", "file_write", AdvSupport.m("path", "C:\\x.txt", "text", t), "写工作区外");
                String s = new String(Files.readAllBytes(new File(cfg.workspaceDir, rel).toPath()), StandardCharsets.UTF_8);
                int closers = 0;
                for (String line : s.split("\n")) if (line.equals("'@") || line.equals("'@\r")) closers++;
                assertEquals(s, 1, closers);
                assertFalse("不留单独的 CR", s.replace("\r\n", "").indexOf('\r') >= 0);
            });

        // ---------------- B14：设置文件
        r.add("B14 带 BOM 的 UTF-8 照样读", (Check) () -> {
            byte[] b = ("\uFEFFlan_port=1234\ntrusted_domains=例子.cn\n").getBytes(StandardCharsets.UTF_8);
            Path p = Files.createTempFile("advfix", ".properties");
            Files.write(p, b);
            Config c = Config.load(p);
            assertEquals(1234, c.lanPort);
            assertEquals("例子.cn", c.trusted);
        });
        r.add("B14 GBK 文件里的中文按 GBK 读对", (Check) () -> {
            Path p = Files.createTempFile("advfix", ".properties");
            Files.write(p, "trusted_domains=例子.cn\nlan_port=1234\n".getBytes(java.nio.charset.Charset.forName("GBK")));
            assertEquals("例子.cn", Config.load(p).trusted);
        });
        r.add("B14 坏的 pair_key 不影响别的字段，存盘再读回", (Check) () -> {
            Path p = Files.createTempFile("advfix", ".properties");
            Files.write(p, "pair_key=@@@\ndevice_id=pc-x\nlan_port=1234\n".getBytes(StandardCharsets.UTF_8));
            Config c = Config.load(p);
            assertEquals("pc-x", c.deviceId);
            assertEquals(0, c.pairKey.length);
            c.save();
            assertEquals("pc-x", Config.load(p).deviceId);
        });
        r.add("B14 坏 \\u 当普通文字、好的 \\u 照常解", (Check) () -> {
            Properties pr = Config.readProps("a=\\u00zz\nb=\\u4f8b\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("原样留下（含反斜杠）", "\\u00zz", pr.getProperty("a"));
            assertEquals("例", pr.getProperty("b"));
        });

        // ---------------- B15
        for (String d : new String[]{"ftp://example.com", "file://x", "javascript://x", "ws://example.com"})
            r.add("B15 别的协议 " + d, (Check) () -> assertNull(d, LinkPolicy.normDomain(d)));
        for (String[] d : new String[][]{{"https://Example.com/x", "example.com"}, {"http://a.example.com", "a.example.com"}, {"*.example.com", "example.com"}})
            r.add("B15 反例 " + d[0], (Check) () -> assertEquals(d[1], LinkPolicy.normDomain(d[0])));

        // ---------------- 实测 P1-1：托盘中文（fontconfig 补 UTF-8 序列）
        String SRC = "version=1\nallfonts.chinese-ms936=SimSun\nsequence.allfonts=alphabetic/default,dingbats,symbol\nsequence.allfonts.UTF-8.ja=alphabetic,japanese,dingbats,symbol\n";
        r.add("P1-1 补上 UTF-8 中文序列（宋体）", (Check) () -> {
            String out = AwtFonts.patch(SRC);
            Properties p = new Properties();
            p.load(new StringReader(out));
            assertTrue(p.getProperty("sequence.allfonts.UTF-8.zh.CN").contains("chinese-ms936"));
            assertTrue(p.getProperty("sequence.allfonts.UTF-8").contains("chinese-ms936"));
            assertEquals("原有的不动", "alphabetic,japanese,dingbats,symbol", p.getProperty("sequence.allfonts.UTF-8.ja"));
            assertEquals("1", p.getProperty("version"));
            assertEquals("补过的再补一次不变", out, AwtFonts.patch(out));
        });
        r.add("P1-1 原文已有同名键不重复写", (Check) () -> {
            String src = SRC + "sequence.allfonts.UTF-8.ko=alphabetic,korean\n";
            String out = AwtFonts.patch(src);
            assertEquals(1, out.split("sequence.allfonts.UTF-8.ko=", -1).length - 1);
        });
        r.add("P1-1 只在 Windows + UTF-8 + 没人指定时做", (Check) () -> {
            Path dir = Files.createTempDirectory("advfont");
            Path src = dir.resolve("fontconfig.properties.src");
            Files.write(src, SRC.getBytes(StandardCharsets.ISO_8859_1));
            Path out = dir.resolve("out");
            assertNull(AwtFonts.prepare("Linux", StandardCharsets.UTF_8, null, src, out, false));
            assertNull(AwtFonts.prepare("Windows 11", java.nio.charset.Charset.forName("GBK"), null, src, out, false));
            assertNull(AwtFonts.prepare("Windows 11", StandardCharsets.UTF_8, "C:\\x.properties", src, out, false));
            assertNull(AwtFonts.prepare("Windows 11", StandardCharsets.UTF_8, null, dir.resolve("missing"), out, false));
            Path f = AwtFonts.prepare("Windows 11", StandardCharsets.UTF_8, null, src, out, false);
            assertNotNull(f);
            assertEquals(AwtFonts.patch(SRC), new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
            long t = Files.getLastModifiedTime(f).toMillis();
            assertEquals(f, AwtFonts.prepare("Windows 10", StandardCharsets.UTF_8, "", src, out, false));
            assertEquals("内容一样不重写", t, Files.getLastModifiedTime(f).toMillis());
        });
        r.add("P1-1 Main 第一行就准备字体（在 AWT 之前）", (Check) () -> {
            String m = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/Main.java")), StandardCharsets.UTF_8);
            int main = m.indexOf("public static void main(");
            assertTrue(main > 0);
            String body = m.substring(main, m.indexOf('\n', m.indexOf('\n', main) + 1));
            assertTrue(body, body.contains("AwtFonts.prepare()"));
        });

        // ---------------- 实测 P1-2：仅音频不删同名视频
        r.add("P1-2 仅音频的文件名和视频不同", (Check) () -> {
            assertFalse(MediaArgs.OUT_AUDIO.equals(MediaArgs.OUT_VIDEO));
            List<String> v = MediaArgs.download("yt-dlp", "https://example.com/v", "best", "", "/ff", "/out", null);
            for (String au : new String[]{"m4a", "mp3"}) {
                List<String> a = MediaArgs.download("yt-dlp", "https://example.com/v", "best", au, "/ff", "/out", null);
                assertEquals(MediaArgs.OUT_AUDIO, a.get(a.indexOf("-o") + 1));
                assertTrue(a.contains("-x"));
            }
            assertEquals(MediaArgs.OUT_VIDEO, v.get(v.indexOf("-o") + 1));
            String audioName = MediaArgs.OUT_AUDIO.replace("%(title).80B", "T").replace("%(id)s", "id").replace("%(ext)s", "webm");
            String videoName = MediaArgs.OUT_VIDEO.replace("%(title).80B", "T").replace("%(id)s", "id").replace("%(ext)s", "webm");
            assertFalse("同一个链接、同一个扩展名时也不会同名", audioName.equals(videoName));
        });

        // ---------------- 实测 P2：取消后清 .part
        r.add("P2 取消后清掉这次的半截文件，旧的完整文件不动", (Check) () -> {
            Path out = Files.createTempDirectory("advmedia");
            Media md = new Media(null, null, out, null);
            Media.Task t = new Media.Task(1, "https://example.com/v", "best", "", "");
            Path old = out.resolve("T [id].mp4");
            Files.write(old, new byte[]{1});
            Path f1 = out.resolve("T [id].f137.mp4");
            md.noteDest(t, "[download] Destination: " + old);
            md.noteDest(t, "[download] Destination: " + f1);
            md.noteDest(t, "[download] Destination: /etc/passwd");
            Files.write(Paths.get(f1 + ".part"), new byte[10]);
            Files.write(Paths.get(f1 + ".ytdl"), new byte[1]);
            Files.write(Paths.get(f1 + ".part-Frag3"), new byte[1]);
            Files.write(Paths.get(old + ".part"), new byte[1]);
            Path other = out.resolve("别的视频 [zz].mp4.part");
            Files.write(other, new byte[1]);
            md.cleanupPartial(t);
            assertTrue("开始前就有的完整文件不删", Files.exists(old));
            assertFalse(Files.exists(Paths.get(f1 + ".part")));
            assertFalse(Files.exists(Paths.get(f1 + ".ytdl")));
            assertFalse(Files.exists(Paths.get(f1 + ".part-Frag3")));
            assertFalse(Files.exists(Paths.get(old + ".part")));
            assertTrue("别的任务的半截文件不动", Files.exists(other));
            assertTrue(Files.exists(Paths.get("/etc/passwd")));
        });

        // ---------------- 实测 P1-4：没有可用识别不抢空格
        r.add("P1-4 cannotStart 三种情况", (Check) () -> {
            assertNotNull(Voice.cannotStart(AsrRoute.Pref.AUTO, false, false));
            assertNotNull(Voice.cannotStart(AsrRoute.Pref.LOCAL, true, false));
            assertNotNull(Voice.cannotStart(AsrRoute.Pref.CLOUD, false, true));
            assertNull(Voice.cannotStart(AsrRoute.Pref.AUTO, true, false));
            assertNull(Voice.cannotStart(AsrRoute.Pref.AUTO, false, true));
            assertNull(Voice.cannotStart(AsrRoute.Pref.LOCAL, false, true));
            assertNull(Voice.cannotStart(AsrRoute.Pref.CLOUD, true, false));
            assertTrue(Voice.needsLocalCheck(AsrRoute.Pref.AUTO, false));
            assertTrue(Voice.needsLocalCheck(AsrRoute.Pref.LOCAL, true));
            assertFalse(Voice.needsLocalCheck(AsrRoute.Pref.AUTO, true));
            assertFalse(Voice.needsLocalCheck(AsrRoute.Pref.CLOUD, false));
        });

        // ---------------- 实测 P2：cookies 按当前账户设 ACL
        r.add("P2 cookies 的 ACL 主体是当前账户名", (Check) () -> {
            List<String> n = MediaCookies.userNames();
            assertFalse(n.isEmpty());
            assertTrue(n.get(n.size() - 1).length() > 0);
        });

        // ---------------- 界面（静态检查）
        r.add("UI 不再用浏览器原生 confirm()", (Check) () -> {
            String h = ui();
            assertTrue(h.contains("window.confirm=ask;"));
            assertTrue(h.contains("function ask(msg,okText)"));
            int i = -1, n = 0;
            while ((i = h.indexOf("confirm(", i + 1)) >= 0) {
                String before = h.substring(Math.max(0, i - 7), i);
                if (before.endsWith("await ")) n++;
                else assertTrue("confirm( 只出现在注释里：" + h.substring(Math.max(0, i - 40), i + 20), h.substring(Math.max(0, i - 12), i).contains("浏览器") || h.substring(Math.max(0, i - 12), i).contains("原生"));
            }
            assertEquals("14 处确认都 await 页面自己的框", 14, n);
            assertTrue("默认焦点在取消", h.contains("$('ask-no');if(n)n.focus()"));
        });
        r.add("UI 预览后选格式不清空、显示整理后的链接", (Check) () -> {
            String h = ui();
            assertTrue(h.contains("value=\"${esc(mdUrl)}\""));
            assertTrue(h.contains("整理后的链接"));
            assertFalse("选格式不再 mdInfo=null", h.contains("mdA='';mdInfo=null;render()"));
            assertTrue(h.contains("function mdFmt("));
        });
        r.add("UI 文案：清空对话红字、插件四项、下载工具体积", (Check) () -> {
            String h = ui();
            assertTrue(h.contains("class=\"chip danger\" onclick=\"chatClear()\""));
            assertFalse(h.contains("下面三项"));
            assertFalse(h.contains("这三项关不掉"));
            assertTrue(h.contains("四项"));
            assertFalse(h.contains("约 100 MB"));
            assertTrue(h.contains("github.com/FFmpeg-Builds/FFmpeg-Builds"));
        });
        return r.get();
    }

    static String ui() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/onesay/ui/index.html")), StandardCharsets.UTF_8);
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public Check check;

    @Test public void fix() throws Exception {
        check.run();
    }
}
