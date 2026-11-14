package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 生成的脚本（LinkPolicy#buildScript / writeScript / oneLine / body，PcGuard#script）：中文头注释一行一项、换行 / 控制字符 / U+2028
 * 逃不出注释；.ps1 加 UTF-8 BOM（PowerShell 5 认中文）；.bat 以 @echo off + chcp 65001 开头、全文 CRLF、注释用 REM；.sh 带 shebang；
 * 命令正文去 NUL、统一换行；文件名 scripts/时间-英文小写短名.扩展名（最长 32，没有英文就是 task），同一秒重名加 -2、-3。
 */
@RunWith(Parameterized.class)
public class AdvScriptGenTest {

    static final long NOW = 1_763_100_000_000L;
    static final String[] CMDS = {"reg add HKLM\\Software\\中文 /v 名称 /d 值", "echo 你好\nsc stop x", "a\r\nb\rc", "line\u0000nul", "", "  padded  ",
            "emoji 😀", "'quote' \"dq\" `bt` ^caret %PATH% $env:X"};
    static final String[] TITLES = {"sys_exec", "中文标题", "A B!!C", "x".repeat(50), "", "\n\n", "title\nreg add HKLM\\x", "file_write"};
    static final String[] INJECT = {"做什么\nreg add HKLM\\x", "风险\r\n@echo pwned", "a\u2028b\u2029c", "tab\there\u007f"};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("script");
        for (LinkPolicy.ScriptKind k : LinkPolicy.ScriptKind.values()) {
            for (String c : CMDS) r.add("cmd " + k + " [" + c + "]", "build", k, "sys_exec", "说明", "风险", c);
            for (String t : TITLES) r.add("title " + k + " [" + t + "]", "build", k, t, "说明", "风险", "echo x");
            for (String w : INJECT) {
                r.add("what " + k + " [" + w + "]", "build", k, "t", w, "风险", "echo x");
                r.add("risk " + k + " [" + w + "]", "build", k, "t", "说明", w, "echo x");
            }
            r.add("write " + k, "write", k, "写盘", "说明", "风险", "echo 中文");
            r.add("collide " + k, "collide", k, "same", "说明", "风险", "echo 1");
        }
        String[][] dec = {{"efbbbf" + hex("中文 reg"), "中文 reg"}, {"fffe" + hex16le("中文"), "中文"}, {"feff" + hex16be("中文"), "中文"},
                {hex16le("reg add"), "reg add"}, {hex("plain"), "plain"}, {"", ""}, {hex("ab"), "ab"}, {"efbbbf", ""}};
        for (String[] d : dec) r.add("decode [" + d[0] + "]", "decode", null, d[0], d[1], "", "");
        String[][] gen = {{"reg add HKLM\\Software\\中文 /v a /d 1", "要改机器级注册表"}, {"sc stop 服务", "要动服务"}, {"netsh advfirewall reset", "防火墙"},
                {"icacls C:\\数据 /grant x:F", "系统级改动"}, {"bcdedit /set 测试 on", "启动项"}};
        for (String[] g : gen) r.add("guard [" + g[0] + "]", "guard", null, g[0], g[1], "", "");
        r.add("here-string normal", "here", null, "第一行\n第二行", "C:\\Users\\it's\\a.txt", "", "");
        return r.get();
    }

    static String hex(String s) { return toHex(s.getBytes(StandardCharsets.UTF_8)); }
    static String hex16le(String s) { return toHex(s.getBytes(StandardCharsets.UTF_16LE)); }
    static String hex16be(String s) { return toHex(s.getBytes(StandardCharsets.UTF_16BE)); }

    static String toHex(byte[] b) {
        StringBuilder x = new StringBuilder();
        for (byte v : b) x.append(String.format("%02x", v & 0xff));
        return x.toString();
    }

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public LinkPolicy.ScriptKind sk;
    @Parameterized.Parameter(3) public String title;
    @Parameterized.Parameter(4) public String what;
    @Parameterized.Parameter(5) public String risk;
    @Parameterized.Parameter(6) public String command;

    @Test public void script() throws Exception {
        switch (kind) {
            case "build": {
                LinkPolicy.Script s = LinkPolicy.buildScript(sk, title, what, risk, command, NOW);
                check(s, title, what, risk, command);
                break;
            }
            case "write": {
                File ws = new File(AdvSupport.cfg(LinkPolicy.Mode.DEFAULT).workspaceDir);
                LinkPolicy.Script s = LinkPolicy.buildScript(sk, title, what, risk, command, NOW);
                String rel = LinkPolicy.writeScript(ws, s);
                assertEquals(s.relPath, rel);
                byte[] b = Files.readAllBytes(new File(ws, rel).toPath());
                boolean bom = b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF;
                assertEquals(name + " 只有 .ps1 带 BOM", sk == LinkPolicy.ScriptKind.PS1, bom);
                assertEquals(s.content, new String(b, bom ? 3 : 0, b.length - (bom ? 3 : 0), StandardCharsets.UTF_8));
                assertEquals("列得出来", true, LinkPolicy.listScripts(ws).contains(rel.substring("scripts/".length())));
                break;
            }
            case "collide": {
                File ws = new File(AdvSupport.cfg(LinkPolicy.Mode.DEFAULT).workspaceDir);
                LinkPolicy.Script s = LinkPolicy.buildScript(sk, title, what, risk, command, NOW);
                String a = LinkPolicy.writeScript(ws, s), b = LinkPolicy.writeScript(ws, s), c = LinkPolicy.writeScript(ws, s);
                String stem = s.relPath.substring(0, s.relPath.lastIndexOf('.'));
                assertEquals(s.relPath, a);
                assertEquals(stem + "-2" + sk.ext, b);
                assertEquals(stem + "-3" + sk.ext, c);
                break;
            }
            case "decode":
                assertEquals(name, what, LinkPolicy.decodeScript(unhex(title)));
                break;
            case "guard": {
                Config cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
                PcGuard g = new PcGuard(cfg, null);
                String rel = g.script("phone-a", "sys_exec", AdvSupport.m("command", title), "要管理员");
                assertNotNull(rel);
                byte[] b = Files.readAllBytes(new File(cfg.workspaceDir, rel).toPath());
                String s = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
                assertTrue(name + "\n" + s, s.split("\n")[4].startsWith("# 风险：") && s.split("\n")[4].contains(what));
                assertTrue("来自谁", s.contains("# 做什么：来自 phone-a 的远程请求。"));
                assertTrue("中文命令原样", s.endsWith(title + "\n"));
                break;
            }
            case "here": {
                Config cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
                PcGuard g = new PcGuard(cfg, null);
                String rel = g.script("phone-a", "file_write", AdvSupport.m("path", what, "text", title), "写工作区外");
                byte[] b = Files.readAllBytes(new File(cfg.workspaceDir, rel).toPath());
                String s = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
                assertTrue(s, s.contains("$text = @'\n第一行\n第二行\n'@\nSet-Content -LiteralPath 'C:\\Users\\it''s\\a.txt' -Value $text -Encoding UTF8"));
                break;
            }
            default:
                throw new AssertionError(kind);
        }
    }

    static void check(LinkPolicy.Script s, String title, String what, String risk, String command) {
        LinkPolicy.ScriptKind k = s.kind;
        String nl = k == LinkPolicy.ScriptKind.BAT ? "\r\n" : "\n";
        String c = k == LinkPolicy.ScriptKind.BAT ? "REM " : "# ";
        assertTrue(s.relPath, s.relPath.matches("scripts/\\d{8}-\\d{6}-[a-z0-9-]{1,32}\\" + k.ext));
        String slug = s.relPath.substring("scripts/".length() + 16, s.relPath.length() - k.ext.length());
        assertFalse("短名不以 - 开头 / 结尾", slug.startsWith("-") || slug.endsWith("-"));
        if (!title.matches("(?s).*[A-Za-z0-9].*")) assertEquals("没有英文就是 task", "task", slug);
        if (k == LinkPolicy.ScriptKind.BAT) {
            assertTrue(s.content.startsWith("@echo off\r\nchcp 65001 >nul\r\n"));
            assertFalse(".bat 里没有单独的 LF", s.content.replace("\r\n", "").contains("\n"));
            assertFalse(".bat 里没有单独的 CR", s.content.replace("\r\n", "").contains("\r"));
        } else {
            assertFalse("不是 .bat 就没有 CR", s.content.contains("\r"));
            if (k == LinkPolicy.ScriptKind.SH) assertTrue(s.content.startsWith("#!/system/bin/sh\n"));
        }
        String[] lines = s.content.split(nl, -1);
        int i = k == LinkPolicy.ScriptKind.PS1 ? 0 : k == LinkPolicy.ScriptKind.SH ? 1 : 2;
        String[] head = {"由 OneSay 远程指令生成，没有执行。确认无误后需要你自己执行。", "生成时间：", "事项：" + LinkPolicy.oneLine(title), "做什么：" + LinkPolicy.oneLine(what),
                "风险：" + LinkPolicy.oneLine(risk), "怎么执行：", "------"};
        for (String h : head) {
            String line = lines[i++];
            assertTrue("头注释每行都是注释：" + line, line.startsWith(c));
            assertTrue(line + " 应以 " + h + " 开头", line.substring(c.length()).startsWith(h));
            if (!h.endsWith("：") && !h.startsWith("---")) assertEquals(c + h, line);
        }
        assertTrue(lines[1 + (k == LinkPolicy.ScriptKind.PS1 ? 0 : k == LinkPolicy.ScriptKind.SH ? 1 : 2)].matches(c + "生成时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        StringBuilder rest = new StringBuilder();
        for (int j = i; j < lines.length; j++) rest.append(j > i ? nl : "").append(lines[j]);
        String body = LinkPolicy.body(command);
        if (k == LinkPolicy.ScriptKind.BAT) body = body.replace("\n", "\r\n");
        assertEquals("命令正文", body + nl, rest.toString());
        assertFalse("正文里没有 NUL", s.content.contains("\u0000"));
    }
}
