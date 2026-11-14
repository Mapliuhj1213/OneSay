package com.onesay.assistant.v2.util;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [logcat-1114] 第三轮真机 P2：用户原话里的手机号 / 邮箱 / 验证码（含全角写法）原样进了 logcat
 * （L0RuleRouter、UnifiedRouter、FloatingSheetService、AgentLoop、PhoneAgent「结束」行）。
 * 现在这些类的日志都先过 {@link SafeLog}（本地 Redactor 脱敏），LogText 的 debug 分支也先脱敏。正例：各种写法都换成占位符；反例：普通日志一字不改。
 */
public class SafeLogTest {

    static final String ROOT = System.getProperty("bixby.root", ".");

    /** 原话里的个人信息：半角 / 全角 / 零宽拆开 / 全角空格拆开 / 全角 ＠。 */
    static final String[][] PII = {
            {"给 13800138000 发短信", "13800138000"},
            {"给 １３８００１３８０００ 发短信", "１３８００１３８０００"},
            {"号码 138\u200b0013\u200b8000 记一下", "138\u200b0013\u200b8000"},
            {"号码 138\u30000013\u30008000 记一下", "138\u30000013\u30008000"},
            {"号码 +86 138-0013-8000", "138-0013-8000"},
            {"发到 zhang.san@example.com 吧", "zhang.san@example.com"},
            {"发到 ｚｈａｎｇ＠ｅｘａｍｐｌｅ．ｃｏｍ 吧", "ｚｈａｎｇ＠ｅｘａｍｐｌｅ．ｃｏｍ"},
            {"发到 zhang＠example.com 吧", "zhang＠example.com"},
            {"验证码是 482913，帮我填上", "482913"},
            {"验证码：４８２９１３，帮我填上", "４８２９１３"},
            {"Your verification code is 739104.", "739104"},
    };

    /** JVM 替身 android.util.Log 的捕获口（编译时 classpath 上是 android.jar 的 stub，没有这个字段，所以用反射）。 */
    static void capture(List<String> into) throws Exception {
        Class.forName("android.util.Log").getField("sCapture").set(null, into);
    }

    @After public void tearDown() throws Exception {
        capture(null);
    }

    @Test public void cleanRedactsAllForms() {
        for (String[] c : PII) {
            String out = SafeLog.clean(c[0]);
            assertFalse(c[0] + " → " + out, out.contains(c[1]));
            assertTrue(c[0] + " → " + out, out.matches("(?s).*\\[(手机号|邮箱|验证码)#1\\].*"));
            // 折叠后也不剩连续 4 位以上的原数字
            String digits = out.replaceAll("[^0-9０-９]", "");
            assertTrue(c[0] + " → " + out, digits.length() <= 2);
        }
        // 幂等：再脱一次不变
        String once = SafeLog.clean("给 １３８００１３８０００ 发短信，验证码 123456");
        assertEquals(once, SafeLog.clean(once));
    }

    @Test public void cleanLeavesOrdinaryLogsAlone() {
        String[] same = {"[TRIAGE] DO l0=alarm.set cache=false askLocal=false 语句=‹12字›", "[AGENT-LOOP] 第 3 轮收工，答复=‹40字›",
                "[PHONE] 结束 DONE 模型 4 次 动作 7 个 15230ms：‹9字›", "[COLD] 面板服务就绪（ready，创建后 812ms）", "版本 2.1.3 构建 20261114",
                "订单号 2026111400012345", "[STREAM-UI] 增量上屏 120 字", "", "提醒我 10 分钟后喝水"};
        for (String s : same) assertEquals(s, SafeLog.clean(s));
        assertEquals("null", SafeLog.clean(null));
    }

    @Test public void logCallsAreRedactedBeforeLogcat() throws Exception {
        List<String> cap = new ArrayList<String>();
        capture(cap);
        SafeLog.i("UnifiedRouter", "[TRIAGE] DO l0=- 语句=给 １３８００１３８０００ 发短信");
        SafeLog.w("PhoneAgent", "[PHONE] 结束 DONE：已发到 ｚｈａｎｇ＠ｅｘａｍｐｌｅ．ｃｏｍ");
        SafeLog.e("AgentLoop", "失败", new IllegalStateException("验证码 ４８２９１３ 填不进去"));
        SafeLog.d("FloatingSheetService", "[COLD] 补发排队指令: 打给 13800138000");
        SafeLog.v("L0RuleRouter", "普通一行");
        assertEquals(5, cap.size());
        String all = String.join("\n", cap);
        for (String raw : new String[]{"１３８００１３８０００", "ｚｈａｎｇ＠", "４８２９１３", "13800138000"}) assertFalse(all, all.contains(raw));
        assertTrue(all, cap.get(0).startsWith("I/UnifiedRouter: ") && cap.get(0).contains("[手机号#1]"));
        assertTrue(all, cap.get(1).startsWith("W/PhoneAgent: ") && cap.get(1).contains("[邮箱#1]"));
        assertTrue("堆栈也在，且脱敏：" + cap.get(2), cap.get(2).contains("IllegalStateException") && cap.get(2).contains("[验证码#1]") && cap.get(2).contains("\tat "));
        assertEquals("V/L0RuleRouter: 普通一行", cap.get(4));
    }

    @Test public void logTextDebugBranchRedacts() {
        String dbg = LogText.debugText("给 １３８００１３８０００ 发短信\n验证码 482913", LogText.DEBUG_MAX);
        assertFalse(dbg, dbg.contains("１３８") || dbg.contains("482913") || dbg.contains("\n"));
        assertTrue(dbg, dbg.contains("[手机号#1]") && dbg.contains("[验证码#1]"));
        assertEquals("截断照旧", "abc…(+3)", LogText.debugText("abcdef", 3));
        assertEquals("提醒我喝水", LogText.debugText("提醒我喝水", LogText.DEBUG_MAX));
        // release 口径（JVM 按 release 编译）照旧只给长度
        assertEquals("‹5字›", LogText.u("１３８００"));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, "app/src/com/onesay/assistant/v2/" + rel + ".java").toPath()), StandardCharsets.UTF_8);
    }

    /** 这几个类里不再直接调 android.util.Log 的 v/d/i/w/e（都走 SafeLog）；真机报的几行原话再包一层 LogText。 */
    @Test public void reportedClassesOnlyUseSafeLog() throws Exception {
        String[] files = {"router/L0RuleRouter", "router/UnifiedRouter", "ui/FloatingSheetService", "router/AgentLoop", "phone/PhoneAgent",
                "phone/PhoneHooks", "phone/FastScreenDriver", "phone/LlmPhonePlanner"};
        Pattern rawLog = Pattern.compile("(?<![\\w.])(android\\.util\\.)?Log\\.(v|d|i|w|e|wtf|println)\\(");
        StringBuilder bad = new StringBuilder();
        int safe = 0;
        for (String f : files) {
            String s = src(f);
            Matcher m = rawLog.matcher(s);
            while (m.find()) bad.append(f).append(" @").append(m.start()).append('\n');
            Matcher n = Pattern.compile("SafeLog\\.[vdiwe]\\(").matcher(s);
            while (n.find()) safe++;
        }
        assertEquals("直接写 logcat 的调用：\n" + bad, 0, bad.length());
        assertTrue(safe > 250);
        String ur = src("router/UnifiedRouter");
        assertFalse(ur.contains("\" 语句=\" + query"));
        assertEquals(3, count(ur, "\" 语句=\" + com.onesay.assistant.v2.util.LogText.u(query)"));
        String ph = src("phone/PhoneHooks");
        assertTrue(ph.contains("+ com.onesay.assistant.v2.util.LogText.u(res.answer.length() > 0 ? res.answer : res.reason));"));
        assertTrue(ph.contains("\"[PHONE] 开始：\" + com.onesay.assistant.v2.util.LogText.u(goal)"));
        assertTrue(src("router/L0RuleRouter").contains("\", 参数=\" + com.onesay.assistant.v2.util.LogText.args(best.args)"));
    }

    static int count(String s, String sub) {
        int n = 0, i = -1;
        while ((i = s.indexOf(sub, i + 1)) >= 0) n++;
        return n;
    }
}
