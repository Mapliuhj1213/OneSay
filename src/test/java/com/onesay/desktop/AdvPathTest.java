package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 工作区路径边界（LinkPolicy#resolveInside 说明：拒绝空、绝对路径、盘符、UNC、设备路径、..、NUL、冒号（NTFS 备用流）、
 * Windows 保留名、尾空格 / 尾点、&lt;&gt;"|?*、控制字符、超过 512；符号链接跳出工作区也拒，悬空链接也拒）+ .git / .codex / .agents 只读
 * （protectedWorkspacePath）+ file_write 端到端的类别（工作区内直接写 / 只读目录先问 / 工作区外先问 / UNC 断网拒）。
 */
@RunWith(Parameterized.class)
public class AdvPathTest {

    static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    static final String[] REJECT = {"", " ", "..", "../x", "a/../b", "a\\..\\b", "...", "a/.../b", "....", "C:\\x", "C:x", "c:/x", "\\\\server\\share\\x",
            "//server/x", "\\\\?\\C:\\x", "\\\\.\\pipe\\x", "/etc/passwd", "\\x", "~/x", "~", "~x", "a:stream", "a.txt::$DATA", "a.txt:Zone.Identifier",
            "CON", "con.txt", "NUL", "nul.tar.gz", "COM1", "lpt9.log", "CONIN$", "conout$.txt", "aux", "PRN", "a/CON/b", "a/aux.c", "com0", "LPT0",
            "a /b", "trail.", "dir./x", "dir /x", "x<y", "x>y", "x|y", "x?y", "x*y", "x\"y", "x\u0001y", "x\u001fy", "x\u0000y", "a/b\u0007c", "a\u0001/b",
            rep("a", 513), "a/..", "./..", "a\\..", "..\\x", "a/b/../../..", "x/./../../y", "http://x/y", "file:a", ". ", "a/ /b"};
    static final String[][] ACCEPT = {{"a.txt", "a.txt"}, {"dir/a.txt", "dir/a.txt"}, {"dir\\a.txt", "dir/a.txt"}, {"./a.txt", "a.txt"}, {"a/./b", "a/b"},
            {"a//b", "a/b"}, {"a/", "a"}, {" a.txt ", "a.txt"}, {"progra~1/x", "progra~1/x"}, {"文件/中文.txt", "文件/中文.txt"}, {"a..b.txt", "a..b.txt"},
            {".hidden", ".hidden"}, {"..hidden", "..hidden"}, {"con1.txt", "con1.txt"}, {"console.log", "console.log"}, {"nul1", "nul1"}, {"COM10", "COM10"},
            {"a b/c d.txt", "a b/c d.txt"}, {"x~1", "x~1"}, {"émoji😀.txt", "émoji😀.txt"}, {"UPPER.TXT", "UPPER.TXT"}, {rep("d/", 40) + "f", rep("d/", 40) + "f"},
            {"scripts/run.ps1", "scripts/run.ps1"}, {"a\\b/c\\d", "a/b/c/d"}, {"in/x", "real/x"}, {"in", "real"}, {".git/config", ".git/config"}, {"x\u0001", "x"}, {"a/b\u0007", "a/b"}};
    static final String[] LINK_REJECT = {"out", "out/x", "dang", "dang/x", "chain/x", "chain", "in/../out/x", "out/../in"};
    static final Object[][] PROTECT = {{".git/config", true}, {".GIT/HEAD", true}, {"x/.git/y", true}, {".git", true}, {".git ", true}, {".codex", true},
            {".Agents/x", true}, {"a\\.git\\hooks", true}, {".gitignore", false}, {".git2", false}, {"git", false}, {"x.git", false},
            {".github/workflows/x", false}, {"my.codex", false}, {".agents2", false}, {"", false}, {".CODEX/config.toml", true}, {"sub/.Codex", true},
            {"./.git/x", true}, {"a/b/c/.agents", true}};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("path");
        for (String s : REJECT) r.add("reject [" + s + "]", "reject", s, null);
        for (String[] s : ACCEPT) r.add("accept [" + s[0] + "]", "accept", s[0], s[1]);
        for (String s : LINK_REJECT) r.add("link [" + s + "]", "reject", s, null);
        for (Object[] p : PROTECT) r.add("protect [" + p[0] + "]", "protect", p[0], p[1]);
        r.add("protect [null]", "protect", null, Boolean.FALSE);
        for (String s : new String[]{"out/pwn.txt", "dang", "../pwn.txt", "chain/pwn2.txt"}) r.add("write-out [" + s + "]", "write-reject", s, null);
        for (String s : new String[]{"w/a.txt", "w/深/层/b.txt", "in/c.txt"}) r.add("write-in [" + s + "]", "write-ok", s, null);
        // file_write 端到端：{路径, 期望}
        String[][] e2e = {{"notes/a.txt", "allow"}, {".git/config", "ask:pc.workspace.protected"}, {".codex/x.toml", "ask:pc.workspace.protected"},
                {"C:\\x\\a.txt", "ask:pc.outside_write"}, {"\\\\evil\\share\\a", "deny:" + LinkPolicy.R_PLUGIN_BLOCKED}, {"\\\\?\\C:\\x", "ask:pc.outside_write"},
                {"../x", "ask:pc.outside_write"}, {"CON", "ask:pc.outside_write"}, {"a.txt:ads", "ask:pc.outside_write"}, {"out/x", "ask:pc.outside_write"},
                {"//evil/share/x", "deny:" + LinkPolicy.R_PLUGIN_BLOCKED}, {"x/desktop.properties", "deny:" + LinkPolicy.R_SELF_GRANT_BLOCKED}};
        for (String[] e : e2e) r.add("e2e [" + e[0] + "]", "e2e", e[0], e[1]);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String rel;
    @Parameterized.Parameter(3) public Object want;

    static File root, outside;
    static Config cfg;
    static PcGuard guard;

    @BeforeClass public static void setUp() throws Exception {
        cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
        guard = new PcGuard(cfg, null);
        root = new File(cfg.workspaceDir).getCanonicalFile();
        outside = Files.createTempDirectory("advout").toFile().getCanonicalFile();
        new File(root, "real").mkdirs();
        Path ws = root.toPath();
        Files.createSymbolicLink(ws.resolve("out"), outside.toPath());
        Files.createSymbolicLink(ws.resolve("dang"), Paths.get("/nonexistent-adv-1114/x"));
        Files.createSymbolicLink(ws.resolve("in"), ws.resolve("real"));
        Files.createSymbolicLink(ws.resolve("chain"), ws.resolve("out"));
    }

    @Test public void path() throws Exception {
        switch (kind) {
            case "reject":
                assertNull(name, LinkPolicy.resolveInside(root, rel));
                break;
            case "accept": {
                File f = LinkPolicy.resolveInside(root, rel);
                assertNotNull(name, f);
                assertEquals(name, new File(root, (String) want).getCanonicalPath(), f.getPath());
                assertTrue("在工作区里", f.getPath().startsWith(root.getPath() + File.separator));
                break;
            }
            case "protect":
                assertEquals(name, want, LinkPolicy.protectedWorkspacePath(rel));
                break;
            case "write-reject": {
                String[] before = outside.list();
                assertNull(name, LinkPolicy.writeInside(root, rel, "pwn".getBytes(StandardCharsets.UTF_8)));
                assertEquals("工作区外什么都没写", before.length, outside.list().length);
                assertFalse(new File(root.getParentFile(), "pwn.txt").exists());
                break;
            }
            case "write-ok": {
                byte[] data = ("内容-" + rel).getBytes(StandardCharsets.UTF_8);
                File f = LinkPolicy.writeInside(root, rel, data);
                assertNotNull(name, f);
                assertEquals(new String(data, StandardCharsets.UTF_8), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                break;
            }
            case "e2e": {
                AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
                guard.confirmer = st;
                PcGuard.Outcome o = guard.check("phone-a", "file_write", AdvSupport.m("path", rel, "text", "x"));
                String w = (String) want;
                if (w.equals("allow")) {
                    assertNull(name, o.reply);
                    assertEquals(LinkPolicy.Cat.WORKSPACE_WRITE, o.cat);
                    assertEquals(0, st.asked.size());
                    Actions.Result res = guard.builtin("file_write", o.args);
                    assertTrue(res.text, res.ok);
                    assertEquals("x", new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8));
                } else if (w.startsWith("ask:")) {
                    assertEquals(name, 1, st.asked.size());
                    assertEquals(name, w.substring(4), st.kinds.get(0));
                    assertEquals(LinkPolicy.R_CONFIRM_DENIED, AdvSupport.reason(o));
                    if (w.endsWith("outside_write")) assertEquals(name, LinkPolicy.Cat.OUTSIDE_WRITE, o.cat);
                } else {
                    assertEquals(name, w.substring(5), AdvSupport.reason(o));
                    assertEquals(0, st.asked.size());
                }
                break;
            }
            default:
                throw new AssertionError(kind);
        }
    }
}
