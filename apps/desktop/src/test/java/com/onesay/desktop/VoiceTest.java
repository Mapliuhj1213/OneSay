package com.onesay.desktop;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class VoiceTest {
    // ---- 长按空格判定 ----

    @Test public void shortTapTypesASpace() {
        SpaceGate g = new SpaceGate();
        SpaceGate.Out d = g.spaceDown(false);
        assertTrue(d.swallow); assertTrue(d.startTimer);
        SpaceGate.Out u = g.spaceUp();
        assertTrue("短按松开补打空格", u.injectSpace);
        assertTrue(u.swallow);
        assertFalse(u.talkStart || u.talkEnd);
        assertEquals(SpaceGate.State.IDLE, g.state());
        assertFalse("迟到的计时器不能开始说话", g.timer(g.timerGen()).talkStart);
    }

    @Test public void holdTalksAndRepeatsAreSwallowed() {
        SpaceGate g = new SpaceGate();
        g.spaceDown(false);
        int gen = g.timerGen();
        assertTrue(g.spaceDown(false).swallow); // 自动重复
        assertTrue(g.timer(gen).talkStart);
        assertTrue(g.spaceDown(false).swallow);
        SpaceGate.Out u = g.spaceUp();
        assertTrue(u.talkEnd);
        assertFalse("说完不补空格", u.injectSpace);
        assertEquals(SpaceGate.State.IDLE, g.state());
    }

    @Test public void fastTypingRolloverKeepsOrder() {
        SpaceGate g = new SpaceGate();
        g.spaceDown(false);
        SpaceGate.Out o = g.otherDown(false);
        assertTrue("别的键之前先补空格", o.injectSpace);
        assertFalse("别的键照常", o.swallow);
        assertFalse(g.timer(g.timerGen()).talkStart);
        SpaceGate.Out u = g.spaceUp();
        assertTrue(u.swallow);
        assertFalse("不重复补空格", u.injectSpace);
    }

    @Test public void escCancelsAndModifiersBypass() {
        SpaceGate g = new SpaceGate();
        g.spaceDown(false);
        g.timer(g.timerGen());
        SpaceGate.Out o = g.otherDown(true);
        assertTrue(o.talkCancel);
        assertTrue(o.swallow);
        assertFalse(g.spaceUp().talkEnd);
        SpaceGate b = new SpaceGate();
        SpaceGate.Out d = b.spaceDown(true);
        assertFalse("Ctrl+空格等放行", d.swallow);
        assertFalse(b.spaceUp().swallow);
        assertFalse(b.otherDown(false).injectSpace);
    }

    @Test public void stalePendingTimerIgnoredAfterNewPress() {
        SpaceGate g = new SpaceGate();
        g.spaceDown(false);
        int first = g.timerGen();
        g.spaceUp();
        g.spaceDown(false);
        assertFalse(g.timer(first).talkStart);
        assertTrue(g.timer(g.timerGen()).talkStart);
    }

    /** C# 钩子要和 SpaceGate 一致：至少检查关键常量和分支都在。 */
    @Test public void csharpHookMirrorsGate() throws Exception {
        String cs = new String(VoiceTest.class.getResourceAsStream("/onesay/OneSayHotkey.cs").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(cs.contains("HOLD_MS = " + SpaceGate.HOLD_MS));
        for (String s : new String[]{"state = PENDING", "state = TALKING", "state = ROLLED", "Emit(\"START\")", "Emit(\"END\")", "Emit(\"CANCEL\")", "InjectSpace()", "dwExtraInfo == MARK", "Console.In.ReadLine()"})
            assertTrue("缺少：" + s, cs.contains(s));
    }

    // ---- 一句话 → 指令 ----

    static Intent p(String s) { return Intent.parse(s, Map.of("微信", "C:\\WeChat.exe", "vs code", "C:\\code.exe"), List.of("下班模式", "备份照片")); }

    @Test public void intents() {
        assertEquals("lock", p("锁屏。").cmd);
        assertEquals("screenshot", p("帮我截个图").cmd);
        assertEquals("mute", p("静音").args.get("action"));
        assertEquals("up", p("声音大一点").args.get("action"));
        assertEquals("down", p("小声点").args.get("action"));
        assertEquals("next", p("下一首").args.get("action"));
        assertEquals("play_pause", p("暂停").args.get("action"));
        assertEquals("open_app", p("打开微信").cmd);
        assertEquals("微信", p("打开微信。").args.get("name"));
        assertEquals("vs code", p("打开 VS Code").args.get("name"));
        assertEquals("https://www.bilibili.com", p("打开B站").args.get("url"));
        assertEquals("https://www.bilibili.com", p("打开哔哩哔哩").args.get("url"));
        assertEquals("https://example.com", p("打开 example.com").args.get("url"));
        assertNull(p("打开记事本").cmd);
        assertTrue(p("打开记事本").say.contains("没找到"));
        Intent s = p("执行下班模式");
        assertEquals("shortcut_run", s.cmd);
        assertEquals("正在执行快捷指令", s.say);
        assertEquals("备份照片", p("备份照片").args.get("name"));
        assertNull(p("今天天气怎么样").cmd);
    }

    @Test public void dangerousNeedsExplicitConfirm() {
        assertNull("只说关机不执行", p("关机").cmd);
        assertNull(p("帮我把电脑关了吧关电脑").cmd);
        Intent c = p("确认关机");
        assertEquals("shutdown", c.cmd);
        assertEquals(Boolean.TRUE, c.args.get("confirm"));
        assertEquals("shutdown_abort", p("取消关机").cmd);
        assertNull(p("睡眠").cmd);
        assertEquals("sleep", p("确认睡眠").cmd);
        assertNull("不会把手机指令当电脑指令", p("让手机打开微信").cmd);
    }

    // ---- 识别请求与解析 ----

    @Test public void asrRequestMatchesPhoneChannel() {
        byte[] pcm = new byte[16000 * 2 * 61];
        Map<String, Object> b = Json.obj(Asr.requestBody(pcm, 16000));
        assertEquals("qwen3-asr-flash", b.get("model"));
        String body = Json.stringify(b);
        assertTrue(body.contains("data:audio/pcm;rate=16000;base64,"));
        assertTrue(body.contains("\"type\":\"input_audio\""));
        assertTrue("超过 60 秒截尾", body.length() < 16000 * 2 * 60 * 4 / 3 + 2000);
        assertEquals("打开蓝牙。", Asr.parse("{\"choices\":[{\"message\":{\"content\":\"打开蓝牙。\",\"annotations\":[]}}]}"));
        try { Asr.parse("{\"error\":{\"message\":\"Invalid API-key\"}}"); fail(); } catch (IllegalStateException e) { assertTrue(e.getMessage().contains("Invalid")); }
    }

    @Test public void silenceDetection() {
        assertEquals(0.0, Recorder.rms(new byte[3200]), 0.001);
        byte[] loud = new byte[3200];
        for (int i = 0; i < loud.length; i += 2) { short s = (short) (i % 4 == 0 ? 8000 : -8000); loud[i] = (byte) s; loud[i + 1] = (byte) (s >> 8); }
        assertEquals(8000, Recorder.rms(loud), 1);
        assertEquals(0.1, Recorder.seconds(new byte[3200]), 0.001);
    }

    // ---- 内置 PowerShell 脚本：写盘带 BOM + 静态语法检查（2026-11-14 hotkey.ps1 在中文 Windows 上报 ParserError） ----

    /**
     * 很小的 PowerShell 词法检查：注释、'…'、"…"（` 转义、"" 转义）、here-string、<# #>，
     * 以及 ( ) [ ] { } 配对。返回 null 表示没问题，否则返回第一处错误。沙箱里没有 pwsh，用它代替真解析器。
     */
    static String psLexCheck(String src) {
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        int line = 1, n = src.length(), i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (c == '<' && i + 1 < n && src.charAt(i + 1) == '#') {
                int e = src.indexOf("#>", i + 2);
                if (e < 0) return "第 " + line + " 行：块注释没结束";
                for (int k = i; k < e; k++) if (src.charAt(k) == '\n') line++;
                i = e + 2; continue;
            }
            if (c == '#') { while (i < n && src.charAt(i) != '\n') i++; continue; }
            if (c == '@' && i + 1 < n && (src.charAt(i + 1) == '"' || src.charAt(i + 1) == '\'')) {
                String end = "\n" + src.charAt(i + 1) + "@";
                int e = src.indexOf(end, i + 2);
                if (e < 0) return "第 " + line + " 行：here-string 没结束";
                for (int k = i; k <= e; k++) if (src.charAt(k) == '\n') line++;
                i = e + end.length(); continue;
            }
            if (c == '\'') {
                int start = line; i++;
                while (true) {
                    if (i >= n) return "第 " + start + " 行：单引号字符串缺少终止符";
                    char d = src.charAt(i);
                    if (d == '\n') line++;
                    if (d == '\'') { if (i + 1 < n && src.charAt(i + 1) == '\'') { i += 2; continue; } i++; break; }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                int start = line; i++;
                while (true) {
                    if (i >= n) return "第 " + start + " 行：双引号字符串缺少终止符";
                    char d = src.charAt(i);
                    if (d == '\n') line++;
                    if (d == '`') { i += 2; continue; }
                    if (d == '"') { if (i + 1 < n && src.charAt(i + 1) == '"') { i += 2; continue; } i++; break; }
                    i++;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') stack.push(new int[]{c, line});
            else if (c == ')' || c == ']' || c == '}') {
                char want = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (stack.isEmpty() || stack.peek()[0] != want) return "第 " + line + " 行：多出来的 " + c;
                stack.pop();
            }
            if (c == '\uFFFD') return "第 " + line + " 行：有解不开的字节（编码不对）";
            i++;
        }
        if (!stack.isEmpty()) return "第 " + stack.peek()[1] + " 行：" + (char) stack.peek()[0] + " 没有配对";
        return null;
    }

    @Test public void builtInPs1ScriptsAreWrittenWithBomAndParse() throws Exception {
        for (String res : new String[]{"/onesay/hotkey.ps1", "/onesay/asr-local.ps1"}) {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ps1t");
            java.nio.file.Path f = dir.resolve("x.ps1");
            java.nio.file.Files.write(f, "旧内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            PsScript.write(res, f);
            byte[] b = java.nio.file.Files.readAllBytes(f);
            assertTrue(res + " 写盘要带 UTF-8 BOM", b.length > 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF);
            assertFalse(res + " 不能有两个 BOM", (b[3] & 0xFF) == 0xEF && (b[4] & 0xFF) == 0xBB);
            String seen = PsScript.asPowerShell51Reads(b);
            assertEquals(res + " PowerShell 5.1 读到的应和源文件一致",
                    new String(PsScript.resource(res), java.nio.charset.StandardCharsets.UTF_8), seen);
            assertNull(res + " 静态语法检查", psLexCheck(seen));
            java.nio.file.Files.delete(f); java.nio.file.Files.delete(dir);
        }
    }

    @Test public void lexCheckCatchesTheGbkQuoteSwallowRegression() {
        // 11-14 用户电脑上的原样：无 BOM 时 GBK 把「：」的尾字节和后面的 " 拼成一个坏字，字符串不终止
        byte[] raw = "try {\n  [Console]::Out.WriteLine(\"LOG 钩子出错：\" + $_.Exception.Message)\n} catch { exit 1 }\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNotNull("无 BOM 按 GBK 读应查出错", psLexCheck(PsScript.asPowerShell51Reads(raw)));
        assertNull("带 BOM 就按 UTF-8 读，没问题", psLexCheck(PsScript.asPowerShell51Reads(PsScript.withBom(raw))));
        assertArrayEquals("withBom 幂等", PsScript.withBom(raw), PsScript.withBom(PsScript.withBom(raw)));
        assertNotNull(psLexCheck("if ($a) { 'x' "));
        assertNotNull(psLexCheck("'abc"));
        assertNull(psLexCheck("<# 注释 \" #>\n$a = @\"\nx \" y\n\"@\n'it''s' + \"a`\"b\" # ( 注释\n"));
    }
}
