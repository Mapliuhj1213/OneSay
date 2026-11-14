package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 快捷指令按「文件名 id + 内容 SHA-256」批准（PcGuard 类说明、LinkPolicy#shortcutFacts）：
 * 内容改了任何一个字节（空格、换行风格、BOM、编码、大小写）都要重新问；改名 = 新快捷指令；碰了严格规则的不能批准（就算批准表里有）；
 * 注释里写到的词不算；批准 / 撤销 / 只允许一次 / 超时 / 记住类型的端到端；名字越界、文件在工作区里、文件太大都不认。
 */
@RunWith(Parameterized.class)
public class AdvShortcutTest {

    static final String BASE = "Write-Host 备份完成\r\nCopy-Item a.txt b.txt\r\n";

    static byte[] u8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] cat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static Object[][] variants() {
        byte[] b = u8(BASE);
        byte[] flip = b.clone();
        flip[0] ^= 0x20;
        return new Object[][]{{"trailing-space", u8(BASE + " ")}, {"no-final-crlf", u8(BASE.substring(0, BASE.length() - 2))}, {"lf", u8(BASE.replace("\r\n", "\n"))},
                {"upper", u8(BASE.toUpperCase())}, {"bom", cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, b)}, {"utf16", BASE.getBytes(StandardCharsets.UTF_16LE)},
                {"comment", u8(BASE + "# 改了注释")}, {"flip", flip}, {"truncated", Arrays.copyOf(b, b.length - 1)}, {"nul", u8(BASE + "\u0000")},
                {"fullwidth-space", u8(BASE.replace(" ", "\u3000"))}, {"empty", new byte[0]}};
    }

    static final String[][] BLOCKED = {{"x.ps1", BASE + "reg add HKLM\\Software\\x /f\r\n"}, {"x.ps1", BASE + "Start-Process cmd -Verb RunAs\r\n"},
            {"x.ps1", BASE + "Add-MpPreference -ExclusionPath C:\\\r\n"}, {"x.ps1", BASE + "powershell -enc " + AdvSupport.b64ps("Get-Date") + "\r\n"},
            {"x.bat", "@echo off\r\necho hi & reg add HKLM\\x\r\n"}, {"x.cmd", "sc stop wuauserv\r\n"}, {"x.ps1", "Set-Content desktop.properties x\r\n"},
            {"x.ps1", "New-Item HKCU:\\Software\\Classes\\ms-settings\\shell\\open\\command -Force\r\n"}};
    static final String[][] COMMENT_ONLY = {{"c.ps1", "# reg add HKLM\\x\r\nWrite-Host hi\r\n"}, {"c.ps1", "<# runas\r\nbcdedit #>\r\nWrite-Host hi\r\n"},
            {"c.bat", "REM reg add HKLM\\x\r\necho hi\r\n"}, {"c.bat", ":: bcdedit /set x\r\necho hi\r\n"}, {"c.bat", "@rem sc stop x\r\necho hi\r\n"},
            {"c.ps1", "Write-Host 'a' # reg add HKLM\\x\r\n"}, {"c.cmd", "  rem netsh advfirewall set allprofiles state off\r\ndir\r\n"}};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("shortcut");
        for (Object[] v : variants())
            for (LinkPolicy.Mode m : AdvSupport.allModes()) r.add("changed " + v[0] + " @" + m, "changed", v[1], m, null);
        for (LinkPolicy.Mode m : AdvSupport.allModes()) r.add("same @" + m, "same", u8(BASE), m, null);
        for (String up : new String[]{"upper-hash", "lower-hash"}) r.add("hash " + up, "hashcase", u8(BASE), LinkPolicy.Mode.DEFAULT, up);
        String[][] ids = {{"backup2.ps1", "new"}, {"Backup.PS1", "approved"}, {"backup.bat", "new"}, {"BACKUP.ps1", "approved"}, {"sub/backup.ps1", "approved"},
                {"backup.ps1 ", "approved"}};
        for (String[] i : ids) r.add("id " + i[0], "id", u8(BASE), LinkPolicy.Mode.DEFAULT, i[0] + "|" + i[1]);
        for (String[] b : BLOCKED) r.add("blocked " + b[0] + " " + AdvSupport.label(b[1]), "blocked", u8(b[1]), LinkPolicy.Mode.DEFAULT, b[0]);
        for (String[] c : COMMENT_ONLY) r.add("comment " + c[0] + " " + AdvSupport.label(c[1]), "comment", u8(c[1]), LinkPolicy.Mode.DEFAULT, c[0]);
        r.add("gbk bat", "blocked", "REM 中文说明\r\nreg add HKLM\\Software\\x\r\n".getBytes(Charset.forName("GBK")), LinkPolicy.Mode.DEFAULT, "g.bat");
        r.add("utf16 no bom", "blocked", "Start-Process cmd -Verb RunAs".getBytes(StandardCharsets.UTF_16LE), LinkPolicy.Mode.DEFAULT, "u.ps1");
        r.add("utf16be bom", "blocked", cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "bcdedit /set x".getBytes(StandardCharsets.UTF_16BE)), LinkPolicy.Mode.DEFAULT, "b.ps1");
        r.add("gbk clean bat", "comment", "echo 你好\r\n".getBytes(Charset.forName("GBK")), LinkPolicy.Mode.DEFAULT, "g2.bat");
        for (String sc : new String[]{"always-then-silent", "change-asks", "allow-once-keeps-old", "reapprove", "revoke", "timeout", "remember-is-once",
                "deny", "no-ext-name", "throwing-confirmer"})
            r.add("e2e " + sc, "e2e", null, LinkPolicy.Mode.DEFAULT, sc);
        for (String n : new String[]{"../x.ps1", "..\\x.ps1", "sub/x.ps1", "x.ps1:ads", "", "nonexist", "x.txt", "x.ps1/", "C:\\x.ps1", "..",
                "x"})
            r.add("name [" + n + "]", "name", null, LinkPolicy.Mode.DEFAULT, n);
        r.add("inside workspace", "inside", null, LinkPolicy.Mode.DEFAULT, null);
        r.add("too big", "big", null, LinkPolicy.Mode.DEFAULT, null);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public byte[] content;
    @Parameterized.Parameter(3) public LinkPolicy.Mode mode;
    @Parameterized.Parameter(4) public String extra;

    static Map<String, String> appr(String id, byte[] b) {
        Map<String, String> m = new TreeMap<>();
        m.put(id, LinkPolicy.sha256Hex(b));
        return m;
    }

    static LinkPolicy.Decision decide(LinkPolicy.Mode m, LinkPolicy.Facts f) {
        return LinkPolicy.decide(LinkPolicy.Target.PC, m, LinkPolicy.Cat.SHORTCUT, f);
    }

    @Test public void shortcut() throws Exception {
        switch (kind) {
            case "changed": {
                LinkPolicy.Facts f = LinkPolicy.shortcutFacts("backup.ps1", content, appr("backup.ps1", u8(BASE)), true);
                assertTrue(name, f.shortcutChanged);
                assertFalse(name, f.shortcutApproved);
                LinkPolicy.Decision d = decide(mode, f);
                if (mode.sandbox == LinkPolicy.Sandbox.READ_ONLY) assertEquals(name, LinkPolicy.R_DENIED_BY_LEVEL, d.reason);
                else if (mode.approval == LinkPolicy.Approval.NEVER) {
                    assertEquals(name, LinkPolicy.Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, d.reason);
                } else {
                    assertEquals(name, LinkPolicy.Verdict.CONFIRM, d.verdict);
                    assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, d.reason);
                    assertFalse("快捷指令不按类型记住", d.rememberable());
                }
                break;
            }
            case "same": {
                LinkPolicy.Facts f = LinkPolicy.shortcutFacts("backup.ps1", content, appr("backup.ps1", u8(BASE)), true);
                assertTrue(f.shortcutApproved);
                LinkPolicy.Decision d = decide(mode, f);
                assertEquals(name, mode.sandbox == LinkPolicy.Sandbox.READ_ONLY ? LinkPolicy.Verdict.DENY : LinkPolicy.Verdict.ALLOW, d.verdict);
                break;
            }
            case "hashcase": {
                Map<String, String> m = new TreeMap<>();
                String h = LinkPolicy.sha256Hex(content);
                m.put("backup.ps1", extra.startsWith("upper") ? h.toUpperCase() : h);
                assertTrue(name, LinkPolicy.shortcutFacts("backup.ps1", content, m, true).shortcutApproved);
                // 存进配置再读回来：哈希统一成小写，照样对得上
                Map<String, String> back = LinkPolicy.parseApprovals(LinkPolicy.formatApprovals(m));
                assertEquals(h, back.get("backup.ps1"));
                break;
            }
            case "id": {
                String[] p = extra.split("\\|");
                LinkPolicy.Facts f = LinkPolicy.shortcutFacts(p[0], content, LinkPolicy.parseApprovals(LinkPolicy.formatApprovals(appr("backup.ps1", content))), true);
                assertEquals(name, p[1].equals("approved"), f.shortcutApproved);
                assertFalse(name, f.shortcutChanged);
                if (p[1].equals("new")) assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, decide(mode, f).reason);
                break;
            }
            case "blocked": {
                LinkPolicy.Facts f = LinkPolicy.shortcutFacts(extra, content, appr(extra, content), true);
                assertFalse(name + " 不能批准", f.shortcutApproved);
                assertFalse(name, f.shortcutBlocked.isEmpty());
                LinkPolicy.Decision d = decide(new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER), f);
                assertEquals(name, LinkPolicy.R_SHORTCUT_BLOCKED, d.reason);
                assertEquals(LinkPolicy.Verdict.DENY, d.verdict);
                break;
            }
            case "comment": {
                LinkPolicy.Facts f = LinkPolicy.shortcutFacts(extra, content, null, true);
                assertEquals(name + " 注释里的词不算", "", f.shortcutBlocked);
                assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, decide(mode, f).reason);
                break;
            }
            case "e2e": e2e(extra); break;
            case "name": {
                Env e = new Env();
                e.write("x.ps1", u8("Write-Host x"));
                e.write("x.txt", u8("Write-Host x"));
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.ALWAYS);
                e.g.confirmer = st;
                PcGuard.Outcome o = e.g.check("phone-a", "shortcut_run", AdvSupport.m("name", extra));
                if (extra.equals("x")) {
                    assertNull(name + " 不带扩展名也能找到 x.ps1", o.reply);
                    assertEquals(1, st.asked.size());
                } else {
                    assertEquals(name, LinkPolicy.R_UNKNOWN, AdvSupport.reason(o));
                    assertEquals(0, st.asked.size());
                }
                break;
            }
            case "inside": {
                Env e = new Env();
                File sd = new File(e.cfg.workspaceDir, "scripts");
                sd.mkdirs();
                e.cfg.scriptsDir = sd.getCanonicalPath();
                e.write("w.ps1", u8("Write-Host x"));
                PcGuard.Outcome o = e.g.check("phone-a", "shortcut_run", AdvSupport.m("name", "w.ps1"));
                assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, AdvSupport.reason(o));
                assertTrue(o.reply.text, o.reply.text.contains("手机能写的电脑工作区"));
                break;
            }
            case "big": {
                Env e = new Env();
                byte[] big = new byte[(int) PcGuard.SHORTCUT_MAX + 1];
                Arrays.fill(big, (byte) 'a');
                e.write("big.ps1", big);
                PcGuard.Outcome o = e.g.check("phone-a", "shortcut_run", AdvSupport.m("name", "big.ps1"));
                assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, AdvSupport.reason(o));
                break;
            }
            default:
                throw new AssertionError(kind);
        }
    }

    /** 端到端：一份临时配置 + 快捷指令文件夹。 */
    static final class Env {
        final Config cfg;
        final PcGuard g;

        Env() throws Exception {
            cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
            g = new PcGuard(cfg, null);
        }

        Path write(String n, byte[] b) throws Exception {
            return Files.write(new File(cfg.scriptsDir, n).toPath(), b);
        }

        PcGuard.Outcome run(AdvSupport.Stub st, String n) {
            g.confirmer = st;
            return g.check("phone-a", "shortcut_run", AdvSupport.m("name", n));
        }
    }

    void e2e(String sc) throws Exception {
        Env e = new Env();
        e.write("backup.ps1", u8(BASE));
        String hash = LinkPolicy.sha256Hex(u8(BASE));
        switch (sc) {
            case "always-then-silent": {
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.ALWAYS);
                assertNull(e.run(st, "backup.ps1").reply);
                assertTrue("给了「以后直接执行」", st.offers.get(0));
                assertEquals(hash, e.g.approvals().get("backup.ps1"));
                AdvSupport.Stub st2 = new AdvSupport.Stub(PcGuard.DENY);
                PcGuard.Outcome o = e.run(st2, "backup.ps1");
                assertNull(o.reply);
                assertEquals(0, st2.asked.size());
                assertEquals("执行前再核对的哈希", hash, o.args.get("_sha256"));
                break;
            }
            case "change-asks": {
                e.g.approve("backup.ps1", hash);
                e.write("backup.ps1", u8(BASE + "Remove-Item b.txt\r\n"));
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                PcGuard.Outcome o = e.run(st, "backup.ps1");
                assertEquals(1, st.asked.size());
                assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, AdvSupport.reason(o));
                assertEquals(LinkPolicy.R_CONFIRM_DENIED, o.reply.data.get("confirm_result"));
                assertEquals("批准表没变", hash, e.g.approvals().get("backup.ps1"));
                break;
            }
            case "allow-once-keeps-old": {
                e.g.approve("backup.ps1", hash);
                e.write("backup.ps1", u8(BASE + "x\r\n"));
                assertNull(e.run(new AdvSupport.Stub(PcGuard.ALLOW), "backup.ps1").reply);
                assertEquals("只允许一次：批准表还是旧哈希", hash, e.g.approvals().get("backup.ps1"));
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                e.run(st, "backup.ps1");
                assertEquals("下次照样问", 1, st.asked.size());
                break;
            }
            case "reapprove": {
                e.g.approve("backup.ps1", hash);
                byte[] nb = u8(BASE + "y\r\n");
                e.write("backup.ps1", nb);
                assertNull(e.run(new AdvSupport.Stub(PcGuard.ALWAYS), "backup.ps1").reply);
                assertEquals(LinkPolicy.sha256Hex(nb), e.g.approvals().get("backup.ps1"));
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                assertNull(e.run(st, "backup.ps1").reply);
                assertEquals(0, st.asked.size());
                break;
            }
            case "revoke": {
                e.g.approve("backup.ps1", hash);
                assertTrue(e.g.revoke("BACKUP.PS1"));
                assertFalse(e.g.revoke("backup.ps1"));
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                PcGuard.Outcome o = e.run(st, "backup.ps1");
                assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, AdvSupport.reason(o));
                assertEquals(1, st.asked.size());
                break;
            }
            case "timeout": {
                PcGuard.Outcome o = e.run(new AdvSupport.Stub(PcGuard.TIMEOUT), "backup.ps1");
                assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, AdvSupport.reason(o));
                assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, o.reply.data.get("confirm_result"));
                assertTrue(e.g.approvals().isEmpty());
                break;
            }
            case "remember-is-once": {
                assertNull(e.run(new AdvSupport.Stub(PcGuard.REMEMBER), "backup.ps1").reply);
                assertEquals("快捷指令没有动作类型：记不住", "", e.cfg.approveKinds);
                assertTrue("也不算「以后直接执行」", e.g.approvals().isEmpty());
                break;
            }
            case "deny": {
                PcGuard.Outcome o = e.run(new AdvSupport.Stub(PcGuard.DENY), "backup.ps1");
                assertEquals(LinkPolicy.R_CONFIRM_DENIED, o.reply.data.get("confirm_result"));
                assertEquals("backup.ps1", o.reply.data.get("shortcut"));
                break;
            }
            case "no-ext-name": {
                e.g.approve("backup.ps1", hash);
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                assertNull(e.run(st, "backup").reply);
                assertEquals(0, st.asked.size());
                break;
            }
            case "throwing-confirmer": {
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.ALWAYS);
                st.throwIt = true;
                PcGuard.Outcome o = e.run(st, "backup.ps1");
                assertNotNull("确认框出错 = 拒绝", o.reply);
                assertEquals(LinkPolicy.R_CONFIRM_DENIED, o.reply.data.get("confirm_result"));
                assertTrue(e.g.approvals().isEmpty());
                break;
            }
            default:
                throw new AssertionError(sc);
        }
    }
}
