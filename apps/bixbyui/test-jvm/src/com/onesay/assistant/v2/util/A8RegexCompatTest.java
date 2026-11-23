package com.onesay.assistant.v2.util;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.Assert.*;

/**
 * ★ a8fix-1118（Note 8 / Android 8.0 真机：一发指令就崩）：
 * Android 7–8 的 ICU 正则不认 {@code \p{IsHan}} 这类 Java 专有脚本 / 区块写法，static 正则在类初始化时抛
 * PatternSyntaxException。这里① 扫全部生产源码的字符串字面量，禁止这些写法回来；② 验证 {@link RegexCompat#HAN}
 * 与 Script=Han 等价（认汉字、不认假名 / 拉丁 / 谚文）；③ 具名分组取值不依赖 API 26 的 Matcher.group(String)；
 * ④ 顶层兜底给友好提示。
 */
public class A8RegexCompatTest {

    // ------------------------------------------------------------------ ① 源码扫描（lint）

    /** 字符串字面量里禁止的正则写法（源码形态，反斜杠已双写）。 */
    static final String[] BANNED = {
            "\\\\p{Is", "\\\\P{Is", "\\\\p{In", "\\\\P{In", "\\\\p{script", "\\\\P{script", "\\\\p{Script", "\\\\P{Script",
            "\\\\p{sc=", "\\\\P{sc=", "\\\\p{blk", "\\\\p{block", "\\\\p{Block", "\\\\p{java", "\\\\P{java", "(?U)"};

    /** 代码里（非注释）禁止：API 26+ 的具名分组取值、Android 不支持的 flag。 */
    static final Pattern BANNED_CODE = Pattern.compile(
            "\\b(?:m|mm|matcher|\\w+Matcher)\\s*\\.\\s*(?:group|start|end)\\s*\\(\\s*\"|UNICODE_CHARACTER_CLASS");

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static List<File> sourceRoots() {
        File r = root();
        List<File> out = new ArrayList<File>();
        for (String d : new String[]{"app/src", "app/src-release", "app/variant", "app/config"}) {
            File f = new File(r, d);
            if (f.isDirectory()) out.add(f);
        }
        File libs = new File(r, "../../libs");
        if (libs.isDirectory()) out.add(libs);
        return out;
    }

    static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals("build") || n.equals("test") || n.equals("tests") || n.startsWith(".")) continue;
                collect(f, out);
            } else if (f.getName().endsWith(".java") || f.getName().endsWith(".kt")) {
                out.add(f);
            }
        }
    }

    /** 粗略的 Java 词法：分出「代码（去注释、字符串换成占位）」和「字符串 / 字符字面量的源码原文」。 */
    static String[] split(String src) {
        StringBuilder code = new StringBuilder(), lits = new StringBuilder();
        int n = src.length();
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                code.append('\n');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 1;
                code.append(' ');
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {   // 文本块（.kt / 新 Java）
                int e = src.indexOf("\"\"\"", i + 3);
                int end = e < 0 ? n : e;
                lits.append(src, i + 3, end).append('\n');
                code.append("\"\"");
                i = e < 0 ? n : e + 2;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    if (src.charAt(j) == '\\') j++;
                    j++;
                }
                lits.append(src, i + 1, Math.min(j, n)).append('\n');
                code.append(c).append(c);
                i = j;
            } else {
                code.append(c);
            }
        }
        return new String[]{code.toString(), lits.toString()};
    }

    static List<String> violations(String fileName, String src) {
        List<String> bad = new ArrayList<String>();
        String[] parts = split(src);
        for (String b : BANNED) {
            if (parts[1].contains(b)) bad.add(fileName + " 字符串里用了 " + b.replace("\\\\", "\\") + "…（Android 7–8 ICU 不认）");
        }
        Matcher m = BANNED_CODE.matcher(parts[0]);
        while (m.find()) bad.add(fileName + " 代码里用了 " + m.group() + "（API 26+ 或 Android 不支持）");
        return bad;
    }

    @Test public void noAndroidIncompatibleRegexInProductionSources() throws Exception {
        List<File> files = new ArrayList<File>();
        for (File d : sourceRoots()) collect(d, files);
        assertTrue("应扫到生产源码（bixby.root=" + root() + "）", files.size() > 200);
        List<String> bad = new ArrayList<String>();
        boolean sawL0 = false;
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            if (f.getName().equals("L0RuleRouter.java")) sawL0 = true;
            bad.addAll(violations(f.getPath(), src));
        }
        assertTrue("应扫到 L0RuleRouter.java", sawL0);
        assertTrue(bad.toString(), bad.isEmpty());
    }

    @Test public void scannerCatchesTheOriginalBug() {
        String l0 = "class X { static final Pattern U = Pattern.compile(\n"
                + "  \"^(?:https?://\\\\S+|(?:[\\\\p{L}](?:[\\\\p{L}-]*)?\\\\.)+[A-Za-z\\\\p{IsHan}]{2,})$\"); }";
        assertFalse("原 URL_LIKE（// 在字符串里也不能把它藏起来）", violations("X", l0).isEmpty());
        assertFalse(violations("X", "Pattern.compile(\"[\\\\p{IsHiragana}]\")").isEmpty());
        assertFalse(violations("X", "Pattern.compile(\"\\\\p{script=Han}\")").isEmpty());
        assertFalse(violations("X", "Pattern.compile(\"\\\\p{InCJKUnifiedIdeographs}\")").isEmpty());
        assertFalse(violations("X", "Pattern.compile(\"\\\\p{javaLowerCase}\")").isEmpty());
        assertFalse(violations("X", "String a = matcher.group(\"app\");").isEmpty());
        assertFalse(violations("X", "Pattern.compile(\"x\", Pattern.UNICODE_CHARACTER_CLASS)").isEmpty());
        // 注释里提到、普通写法、构建器的 group(...) 都不算
        assertTrue(violations("X", "/* 真机上 \\p{IsHan} 会崩 */ // \\\\p{IsHan}\nPattern.compile(\"[\\\\p{L}\\\\p{N}\\\\p{Punct}]\");").isEmpty());
        assertTrue(violations("X", "b.dedup(k).group(\"burst:\" + id); m.group(1);").isEmpty());
    }

    // ------------------------------------------------------------------ ② HAN 区间

    static final Pattern HAN_CLASS = Pattern.compile("[" + RegexCompat.HAN + "]");

    @Test public void hanConstantEqualsScriptHanOnThisJvm() {
        Pattern jvm = Pattern.compile("\\p{IsHan}");   // 只在 JVM 单测里当参照
        int han = 0;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            String s = new String(Character.toChars(cp));
            boolean ours = HAN_CLASS.matcher(s).matches();
            boolean ref = jvm.matcher(s).matches();
            if (ref) {
                han++;
                assertTrue(String.format("U+%04X 是汉字，HAN 没收", cp), ours);
                assertTrue(String.format("U+%04X isHan", cp), RegexCompat.isHan(cp));
            } else if (ours) {
                // 只允许：本 JVM 的 Unicode 版本还没分配的码位；或 Unicode 14 起才从 Common 归入 Han 的 U+16FE2–16FE3
                //   （HAN 按 Unicode 15.1 写，新系统的 \p{IsHan} 认它们；下面 ICU4J 的用例按 Unicode 15 精确比对）
                if (cp == 0x16FE2 || cp == 0x16FE3) continue;
                assertEquals(String.format("U+%04X 不是汉字却被 HAN 收了", cp), Character.UNASSIGNED, Character.getType(cp));
            }
        }
        assertTrue("汉字总数应 > 9 万：" + han, han > 90000);
    }

    @Test public void hanMatchesHanNotKanaLatinHangul() {
        for (String s : new String[]{"中", "国", "龘", "㐀", "䶵", "鿕", "豈", "〇", "々", "𠀀", "𪚥", "𫝀", "𰀀"}) {
            assertTrue(s, HAN_CLASS.matcher(s).matches());
            assertTrue(s, RegexCompat.containsHan(s));
        }
        for (String s : new String[]{"あ", "ア", "ｱ", "ー", "ゝ", "ヽ", "한", "ㄅ", "a", "Z", "é", "Ж", "1", "１", "Ａ", "、", "。", "〆", "・", " ", "😀", "\u3000"}) {
            assertFalse(s, HAN_CLASS.matcher(s).matches());
            assertFalse(s, RegexCompat.isHan(s.codePointAt(0)));
        }
        assertFalse(RegexCompat.containsHan("みんなのKana"));
        assertFalse(RegexCompat.containsHan(null));
        assertTrue("增补平面（代理对）按码位判断", RegexCompat.containsHan("x\uD840\uDC00"));
        // 窄区间只收 BMP 常用汉字
        Pattern core = Pattern.compile("[" + RegexCompat.HAN_CORE + "]");
        assertTrue(core.matcher("中").matches());
        assertTrue(core.matcher("㐀").matches());
        assertFalse(core.matcher("あ").matches());
        assertFalse(core.matcher("a").matches());
    }

    /** ICU4J 的 UnicodeSet 与 Android 的 ICU 正则共用集合语法：HAN 能被 ICU 解析，且与 [:Script=Han:] 一致（差集只许是未分配码位）。 */
    @Test public void hanConstantParsesInIcuAndEqualsHanScript() throws Exception {
        Class<?> us;
        try {
            us = Class.forName("com.ibm.icu.text.UnicodeSet");
        } catch (ClassNotFoundException e) {
            return;   // 测试 classpath 上没有 ICU4J（run_unit_tests.sh 有）
        }
        Object ours = us.getConstructor(String.class).newInstance("[" + RegexCompat.HAN + "]");
        Object han = us.getConstructor(String.class).newInstance("[:Script=Han:]");
        assertTrue("ICU 认 HAN 且覆盖全部 Script=Han", (Boolean) us.getMethod("containsAll", us).invoke(ours, han));
        Object diff = us.getConstructor(us).newInstance(ours);
        us.getMethod("removeAll", us).invoke(diff, han);
        Object unassigned = us.getConstructor(String.class).newInstance("[:Cn:]");
        assertTrue("多出来的只能是未分配码位", (Boolean) us.getMethod("containsAll", us).invoke(unassigned, diff));
    }

    // ------------------------------------------------------------------ ③ 具名分组 / 安全编译

    @Test public void namedGroupWithoutApi26() {
        String[] regexes = {
                "^打开(?<app>.+?)$",
                "^定(?<n>.+?)点(?<m>.+?)分的闹钟$",
                "^(a)(?:b)(?<url>.+?)(?=c)c(?<content>.+?)$",
                "^[(](?<n>.+?)\\((?<m>x)\\)$",
                "^\\Q((\\E(?<app>.+?)(?<=y)y$",
                "^[]()](?<app>\\w+)$",
        };
        String[] inputs = {"打开微信", "定7点30分的闹钟", "abwww.qq.comc发了", "(5(x)", "((wechatyy", "(abc"};
        for (int i = 0; i < regexes.length; i++) {
            Pattern p = Pattern.compile(regexes[i]);
            Matcher m = p.matcher(inputs[i]);
            assertTrue(regexes[i], m.matches());
            for (String name : new String[]{"app", "n", "m", "url", "content"}) {
                if (!regexes[i].contains("(?<" + name + ">")) {
                    assertEquals(-1, RegexCompat.groupIndex(regexes[i], name));
                    continue;
                }
                assertEquals(regexes[i] + " <" + name + ">", m.group(name), RegexCompat.group(m, name));
            }
        }
        try {
            Matcher m = Pattern.compile("^(?<app>.+)$").matcher("x");
            assertTrue(m.matches());
            RegexCompat.group(m, "nope");
            fail();
        } catch (IllegalArgumentException expected) { /* 与 JDK 一致 */ }
    }

    @Test public void compileNeverThrows() {
        Pattern p = RegexCompat.compile("[\\p{IsNoSuchScript}]", 0, "^abc$");
        assertTrue(p.matcher("abc").matches());
        Pattern q = RegexCompat.compile("[\\p{IsNoSuchScript}]", 0, "(");
        assertFalse(q.matcher("").matches());
        assertFalse(q.matcher("abc").find());
        assertSame(RegexCompat.NEVER, q);
        assertTrue(RegexCompat.compile("^a+$", 0, null).matcher("aa").matches());
    }

    // ------------------------------------------------------------------ ④ 顶层兜底

    @Test public void commandGuardKeepsAppAlive() {
        PatternSyntaxException pse = new PatternSyntaxException("U_ILLEGAL_ARGUMENT_ERROR", "[\\p{IsHan}]", -1);
        assertTrue(RegexCompat.isRegexCompatFailure(pse));
        assertTrue(RegexCompat.isRegexCompatFailure(new ExceptionInInitializerError(pse)));
        assertTrue(RegexCompat.isRegexCompatFailure(new NoClassDefFoundError("L0RuleRouter")));
        assertTrue(RegexCompat.isRegexCompatFailure(new RuntimeException(new IllegalStateException(pse))));
        assertFalse(RegexCompat.isRegexCompatFailure(new NullPointerException()));
        assertFalse(RegexCompat.isRegexCompatFailure(null));

        assertEquals(CommandGuard.MSG_COMPAT, CommandGuard.userMessage(new ExceptionInInitializerError(pse)));
        assertEquals(CommandGuard.MSG_GENERIC, CommandGuard.userMessage(new IllegalStateException("x")));
        for (String msg : new String[]{CommandGuard.MSG_COMPAT, CommandGuard.MSG_GENERIC}) {
            for (String w : new String[]{"Bixby", "bixby", "三星", "小布", "Exception", "\\p", "null"}) assertFalse(msg, msg.contains(w));
        }
        assertTrue(CommandGuard.logLine("RouteStream", pse).contains("U_ILLEGAL_ARGUMENT_ERROR"));

        final Throwable[] got = {null};
        final String[] said = {null};
        CommandGuard.Failure f = new CommandGuard.Failure() {
            @Override public void onFailure(Throwable t, String userMessage) { got[0] = t; said[0] = userMessage; }
        };
        CommandGuard.wrap("t", new Runnable() {
            @Override public void run() { Pattern.compile("[\\p{IsNoSuchScript}]"); }
        }, f).run();   // 不抛
        assertTrue(got[0] instanceof PatternSyntaxException);
        assertEquals(CommandGuard.MSG_COMPAT, said[0]);

        got[0] = null;
        CommandGuard.wrap("t", new Runnable() {
            @Override public void run() { throw new ExceptionInInitializerError(new PatternSyntaxException("d", "r", 0)); }
        }, null).run();   // 没有回调也不抛
        CommandGuard.wrap("t", new Runnable() {
            @Override public void run() { throw new IllegalStateException("boom"); }
        }, f).run();
        assertEquals(CommandGuard.MSG_GENERIC, said[0]);
    }
}
