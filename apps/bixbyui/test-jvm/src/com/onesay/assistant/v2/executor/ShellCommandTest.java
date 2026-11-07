package com.onesay.assistant.v2.executor;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P0-8：ShellCommand 转义。除字符串断言外，还把填好的命令交给真实 /bin/sh 执行，
 * 用 printf 逐个打印参数，验证"一个槽位 = 一个参数、内容逐字不变、没有命令被注入执行"。
 */
public class ShellCommandTest {

    /** 覆盖用户点名的危险输入：& 空格 引号 中文 ; | $( ) ` 以及模板注入 {x}。 */
    static final String[] NASTY = {
            "https://a.com/x?y=1&z=2",
            "hello world  two spaces",
            "Tom's bag",
            "say \"hi\"",
            "苏州园林 介绍",
            "a; touch /tmp/PWNED_SC",
            "a | cat /etc/passwd",
            "$(touch /tmp/PWNED_SC)",
            "`touch /tmp/PWNED_SC`",
            "${HOME} $HOME",
            "{component} {pkg} {x}",
            "back\\slash",
            "*",
            "~",
            "line1\nline2",
            "",
    };

    private static Map<String, String> v(String k, String val) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k, val);
        return m;
    }

    /** 用 sh 执行命令，返回 printf 打出的每个参数（以 \u0001 分隔）。 */
    private static List<String> shArgs(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        assertEquals("sh 退出码 (" + cmd + ")", 0, p.waitFor());
        String out = new String(bos.toByteArray(), "UTF-8");
        List<String> r = new ArrayList<String>(Arrays.asList(out.split("\u0001", -1)));
        r.remove(r.size() - 1);   // printf 末尾分隔符
        return r;
    }

    @Test public void bareSlotIsExactlyOneLiteralArgument() throws Exception {
        new java.io.File("/tmp/PWNED_SC").delete();
        for (String s : NASTY) {
            String cmd = ShellCommand.fill("printf '%s\\001' {x}", v("x", s));
            List<String> args = shArgs(cmd);
            assertEquals("参数个数: " + cmd, 1, args.size());
            assertEquals("参数内容: " + cmd, ShellCommand.sanitize(s), args.get(0));
        }
        assertFalse("命令注入被执行了！", new java.io.File("/tmp/PWNED_SC").exists());
    }

    @Test public void singleQuotedSlotStaysLiteral() throws Exception {
        for (String s : NASTY) {
            String cmd = ShellCommand.fill("printf '%s\\001' 'prefix:{x}:suffix'", v("x", s));
            List<String> args = shArgs(cmd);
            assertEquals(cmd, 1, args.size());
            assertEquals(cmd, "prefix:" + ShellCommand.sanitize(s) + ":suffix", args.get(0));
        }
        assertFalse(new java.io.File("/tmp/PWNED_SC").exists());
    }

    @Test public void doubleQuotedSlotStaysLiteral() throws Exception {
        for (String s : NASTY) {
            String cmd = ShellCommand.fill("printf '%s\\001' \"<{x}>\"", v("x", s));
            List<String> args = shArgs(cmd);
            assertEquals(cmd, 1, args.size());
            assertEquals(cmd, "<" + ShellCommand.sanitize(s) + ">", args.get(0));
        }
        assertFalse(new java.io.File("/tmp/PWNED_SC").exists());
    }

    @Test public void urlQueryValueIsEncodedAndAmpersandSurvives() throws Exception {
        // 真实模板：am start -a VIEW -d https://www.baidu.com/s?wd={keyword}
        String cmd = ShellCommand.fill("printf '%s\\001' https://www.baidu.com/s?wd={keyword}",
                v("keyword", "苏州 园林&x=1;rm"));
        List<String> args = shArgs(cmd);
        assertEquals(1, args.size());
        assertEquals("https://www.baidu.com/s?wd=%E8%8B%8F%E5%B7%9E%20%E5%9B%AD%E6%9E%97%26x%3D1%3Brm", args.get(0));
        // 单引号里的 market://search?q={keyword} 同样编码
        assertEquals("am start -d 'market://search?q=a%20b%27c'",
                ShellCommand.fill("am start -d 'market://search?q={keyword}'", v("keyword", "a b'c")));
        // 路径段不编码（保持旧行为），但引号内的 ' 仍被正确转义
        assertEquals("am start -d 'samsungapps://SearchResult/微信'",
                ShellCommand.fill("am start -d 'samsungapps://SearchResult/{keyword}'", v("keyword", "微信")));
        // 整个 URL 作为槽位：& 不再被删除，整体加引号
        String full = ShellCommand.fill("am start -a android.intent.action.VIEW -d {url}",
                v("url", "https://a.com/x?y=1&z=2"));
        assertEquals("am start -a android.intent.action.VIEW -d 'https://a.com/x?y=1&z=2'", full);
    }

    @Test public void simpleValuesUnchangedFromLegacy() throws Exception {
        JSONObject a = new JSONObject().put("level", 50);
        Map<String, String> slots = ShellCommand.slotValues(a, null, null);
        assertEquals("settings put system screen_brightness 50",
                ShellCommand.fill("settings put system screen_brightness {level}", slots));
        assertEquals("volume is 50", ShellCommand.fillPlain("volume is {level}", slots));
        JSONObject t = new JSONObject().put("seconds", 30);
        Map<String, String> s2 = ShellCommand.slotValues(t, null, null);
        assertEquals("settings put system screen_off_timeout 30000",
                ShellCommand.fill("settings put system screen_off_timeout {ms}", s2));
        assertEquals("am start -n com.x/.Main",
                ShellCommand.fill("am start -n {component}", ShellCommand.slotValues(null, "com.x/.Main", "com.x")));
        assertEquals("am start -a android.intent.action.DELETE -d package:com.x",
                ShellCommand.fill("am start -a android.intent.action.DELETE -d package:{pkg}",
                        ShellCommand.slotValues(null, "com.x/.Main", "com.x")));
        // 内部类组件名里的 $ 必须被保护
        assertEquals("am start -n 'com.x/.Main$Alias'",
                ShellCommand.fill("am start -n {component}", ShellCommand.slotValues(null, "com.x/.Main$Alias", null)));
    }

    @Test public void defaultsModifiersAndUnknownPlaceholders() {
        assertEquals("x 0 y", ShellCommand.fill("x {m=0} y", new HashMap<String, String>()));
        assertEquals("x 7 y", ShellCommand.fill("x {m=0} y", v("m", "7")));
        assertEquals("keep {unknown} as-is", ShellCommand.fill("keep {unknown} as-is", v("x", "1")));
        assertEquals("'{\"json\":1}'", ShellCommand.fill("'{\"json\":1}'", v("json", "X")));
        assertEquals("--ei h 7", ShellCommand.fill("--ei h {h:i}", v("h", " 7 ")));
        try {
            ShellCommand.fill("--ei h {h:i}", v("h", "7; reboot"));
            fail("整数槽位应拒绝非数字");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals("echo 'abc'", ShellCommand.fill("echo {x:q}", v("x", "abc")));
        assertEquals("echo a%20b", ShellCommand.fill("echo {x:u}", v("x", "a b")));
    }

    @Test public void noSecondPassTemplateInjection() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("a", "{b}");
        m.put("b", "SECRET");
        assertEquals("echo '{b}' SECRET", ShellCommand.fill("echo {a} {b}", m));
    }

    @Test public void newlinesAreFlattened() {
        assertEquals("echo 'a b'", ShellCommand.fill("echo {x}", v("x", "a\nb")));
        assertEquals("echo 'a b'", ShellCommand.fill("echo {x}", v("x", "a\rb")));
        assertEquals("echo ab", ShellCommand.fill("echo {x}", v("x", "a\u0000b")));
    }

    @Test public void legacyEscapeKeepsQuoteSemantics() {
        assertEquals("Tom'\\''s bag", UnifiedExecutorEscape.call("Tom's bag"));
    }

    /** UnifiedExecutor 依赖 Context，单测只调用它的静态兼容方法。 */
    static final class UnifiedExecutorEscape {
        static String call(String s) {
            return UnifiedExecutor.escapeShellValue(s);
        }
    }
}
