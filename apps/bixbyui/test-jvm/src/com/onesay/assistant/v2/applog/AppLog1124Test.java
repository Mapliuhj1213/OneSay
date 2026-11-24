package com.onesay.assistant.v2.applog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

/**
 * [settings-diag-1124] 应用内日志：脱敏、轮转、导出打包内容不含敏感字段、关键链路点的口径。
 */
public class AppLog1124Test {

    private File dir;

    @Before public void setUp() throws Exception {
        dir = Files.createTempDirectory("applog1124").toFile();
        AppLog.setEnabled(true);
        AppLog.init(dir);
        AppLog.clear();
    }

    @After public void tearDown() {
        AppLog.clear();
        AppLog.resetForTest();
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    // ------------------------------------------------------------------ 脱敏

    @Test public void secretsAreMasked() {
        String[] bad = {
                "Authorization: Bearer abcdefghijklmnop1234",
                "api_key=sk-1234567890abcdefXYZ",
                "{\"api_key\":\"AKIAEXAMPLEKEY12345\",\"x\":1}",
                "x-api-key: 0123456789abcdef0123",
                "password=hunter2hunter2",
                "token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
                "调用 sk-proj-aaaaaaaaaaaaaaaaaaaa 失败",
                "cookie=SID=31d4d96e407aad42",
        };
        for (String s : bad) {
            String c = AppLogSanitizer.clean(s);
            assertFalse(s + " → " + c, c.contains("abcdefghijklmnop1234"));
            assertFalse(s + " → " + c, c.contains("sk-1234567890"));
            assertFalse(s + " → " + c, c.contains("AKIAEXAMPLEKEY12345"));
            assertFalse(s + " → " + c, c.contains("0123456789abcdef0123"));
            assertFalse(s + " → " + c, c.contains("hunter2"));
            assertFalse(s + " → " + c, c.contains("eyJhbGci"));
            assertFalse(s + " → " + c, c.contains("sk-proj-aaaa"));
            assertFalse(s + " → " + c, c.contains("31d4d96e407aad42"));
        }
    }

    @Test public void urlsKeepHostOnly() {
        String c = AppLogSanitizer.clean("POST https://user:pw@api.example.com/v1/chat?key=abc123&q=我的地址");
        assertTrue(c, c.contains("https://api.example.com/…"));
        assertFalse(c, c.contains("abc123"));
        assertFalse(c, c.contains("我的地址"));
        assertFalse(c, c.contains("user:pw"));
    }

    @Test public void personalInfoIsRedacted() {
        String c = AppLogSanitizer.clean("给 13812345678 发 验证码 482913，邮箱 a.b@example.com");
        assertFalse(c, c.contains("13812345678"));
        assertFalse(c, c.contains("482913"));
        assertFalse(c, c.contains("a.b@example.com"));
    }

    @Test public void longLinesAreCutAndControlCharsDropped() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3000; i++) b.append('字');
        String c = AppLogSanitizer.clean("a\u0000b\u200bc\n" + b);
        assertTrue(c.startsWith("abc ⏎ "));
        assertTrue(c.length() < AppLogSanitizer.MAX_LINE + 20);
    }

    @Test public void normalTextUntouched() {
        String c = AppLogSanitizer.clean("[ROUTE-1] 分诊=DO 类型=DO L0命中=false 语句=‹12字›");
        assertEquals("[ROUTE-1] 分诊=DO 类型=DO L0命中=false 语句=‹12字›", c);
    }

    // ------------------------------------------------------------------ 写盘 / 轮转

    @Test public void writesAndRotatesWithinCap() throws Exception {
        StringBuilder kb = new StringBuilder();
        for (int i = 0; i < 700; i++) kb.append("日志");
        // 每行约 2.4 KB（UTF-8，单行截到 800 字），写约 7 MB → 必须轮转，最多 KEEP 个文件
        for (int round = 0; round < 32; round++) {
            for (int i = 0; i < 100; i++) AppLog.i("T", "行 " + round + "-" + i + " " + kb);
            AppLog.flush();
        }
        List<File> fs = AppLog.files();
        assertTrue("至少轮转出一个旧文件: " + fs, fs.size() >= 2);
        assertTrue("文件数不超过上限", fs.size() <= AppLog.KEEP);
        for (File f : fs) assertTrue(f.getName() + " " + f.length(), f.length() <= AppLog.MAX_FILE + 64 * 1024);
        assertTrue(AppLog.totalBytes() <= AppLog.KEEP * (AppLog.MAX_FILE + 64 * 1024));
        String all = AppLog.readAll();
        assertTrue("最新的行在", all.contains("行 31-99"));
        assertFalse("最旧的行已被轮转掉", all.contains("行 0-0 "));
    }

    @Test public void clearDeletesEverything() throws Exception {
        AppLog.i("T", "要被清掉的一行");
        AppLog.flush();
        assertTrue(AppLog.totalBytes() > 0);
        assertTrue(AppLog.clear() >= 1);
        assertEquals(0, AppLog.totalBytes());
        assertEquals("", AppLog.readAll());
    }

    @Test public void safeLogTeesIntoAppLogRedacted() throws Exception {
        com.onesay.assistant.v2.util.SafeLog.i("AgentLoop", "[AGENT-LOOP] [RETRY] 打给 13812345678 Bearer zzzzzzzzzzzzzzzz");
        com.onesay.assistant.v2.util.SafeLog.d("AgentLoop", "调试级别不进文件 DEBUGONLY");
        AppLog.flush();
        String all = AppLog.readAll();
        assertTrue(all, all.contains("[RETRY]"));
        assertFalse(all, all.contains("13812345678"));
        assertFalse(all, all.contains("zzzzzzzzzzzzzzzz"));
        assertFalse(all, all.contains("DEBUGONLY"));
    }

    // ------------------------------------------------------------------ 关键链路点

    @Test public void phoneTraceReleaseDropsModelOutput() {
        String line = "model#3 tap  {\"say\":\"帮你在搜索框输入 张三 13812345678\"}";
        String rel = AppLogPoints.traceLine(line, false);
        assertTrue(rel, rel.startsWith("model#3 tap"));
        assertFalse(rel, rel.contains("张三"));
        assertTrue(rel, rel.contains("字›"));
        assertEquals("launch-mismatch com.example.a", AppLogPoints.traceLine("launch-mismatch com.example.a", false));
        assertEquals("app-drift com.x.y", AppLogPoints.traceLine("app-drift com.x.y", false));
    }

    @Test public void execLineListsKeysOnly() throws Exception {
        com.onesay.assistant.v2.capability.Capability cap = new com.onesay.assistant.v2.capability.Capability.Builder("sms.send", "发短信").build();
        org.json.JSONObject a = new org.json.JSONObject().put("number", "13812345678").put("body", "我的秘密");
        AppLogPoints.exec(cap, a, com.onesay.assistant.v2.executor.ExecutionResult.success(cap, a, "ok", false, "x"), true);
        AppLog.flush();
        String all = AppLog.readAll();
        assertTrue(all, all.contains("[CAP] sms.send → 成功"));
        assertFalse(all, all.contains("我的秘密"));
        assertFalse(all, all.contains("13812345678"));
    }

    @Test public void chainPointsAreWired() throws Exception {
        // 各关键点都接了日志（源码守住，防止以后被删）
        assertTrue(src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java").contains("AppLogPoints.exec("));
        String gate = src("app/src/com/onesay/assistant/v2/executor/ExecutionGate.java");
        assertTrue(gate.contains("AppLogPoints.gate(") && gate.contains("AppLogPoints.confirm("));
        assertTrue(src("app/src/com/onesay/assistant/v2/phone/PhoneAgent.java").contains("AppLogPoints.phoneTrace("));
        assertTrue(src("app/src/com/onesay/assistant/v2/net/LlmModels.java").contains("AppLogPoints.vision("));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/ConversationMemory.java").contains("AppLogPoints.session(\"会话到期\""));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/ConversationMemory.java").contains("AppLogPoints.session(\"会话清空\""));
        assertTrue(src("app/src/com/onesay/assistant/v2/errand/ErrandHooks.java").contains("SafeLog.i(TAG, \"[CONTINUE]"));
        assertTrue(src("app/src/com/onesay/assistant/v2/util/SafeLog.java").contains("AppLog.append(level, tag, cleaned)"));
        String man = src("app/AndroidManifest.xml");
        assertTrue(man.contains("com.onesay.assistant.v2.applog.LogShareProvider"));
        assertTrue(man.contains("android:authorities=\"com.onesay.assistant.logshare\""));
        assertTrue("分享 Provider 不导出", man.substring(man.indexOf("LogShareProvider")).substring(0, 300).contains("android:exported=\"false\""));
        assertTrue("debug 包 authority 要改名", src("build_linux.sh").contains("com.onesay.assistant.debug.logshare"));
    }

    @Test public void sessionPointsLogTurnsOnly() throws Exception {
        com.onesay.assistant.v2.router.ConversationMemory m = com.onesay.assistant.v2.router.ConversationMemory.newForTest(new com.onesay.assistant.v2.router.ConversationMemory.Clock() { @Override public long now() { return 1L; } });
        m.clear();
        AppLog.flush();
        assertTrue(AppLog.readAll().contains("[SESSION] 会话清空"));
    }

    // ------------------------------------------------------------------ 导出

    static Map<String, String> unzip(File f) throws Exception {
        Map<String, String> m = new HashMap<String, String>();
        ZipInputStream z = new ZipInputStream(new FileInputStream(f));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                m.put(e.getName(), bo.toString("UTF-8"));
            }
        } finally {
            z.close();
        }
        return m;
    }

    @Test public void exportContainsLogsButNoSensitiveFields() throws Exception {
        AppLog.i("UnifiedRouter", "[ROUTE-1] 分诊=DO 语句=‹9字›");
        AppLog.w("Net", "请求失败 https://api.deepseek.com/v1/chat?k=1 Authorization: Bearer sk-live-0000000000000000");
        AppLog.i("AgentLoop", "收件人 zhangsan@example.com 手机 13912345678 身份证 11010519491231002X");
        // 模拟旧版本直接写进文件、没经过脱敏的一行（导出时要再扫一遍）
        AppLog.flush();
        File cur = new File(dir, "onesay.log");
        java.io.FileOutputStream os = new java.io.FileOutputStream(cur, true);
        os.write("11-24 10:00:00.000 I Old: api_key=RAWKEY1234567890 secret: abcdefabcdef\n".getBytes("UTF-8"));
        os.close();
        String header = "OneSay 运行日志\n手机：Test\n主模型 Key：已配置\nAuthorization: Bearer zzzzzzzzzzzz9999";
        String logcat = "11-24 10:00:01.000  1234  1250 I OkHttp: --> POST https://api.example.com/v1/x?key=SECRETQ\n"
                + "11-24 10:00:01.001  1234  1250 I OkHttp: Authorization: Bearer sk-abcdefghijklmnop\n";
        File exportDir = new File(dir, "export");
        AppLogExport.Result r = AppLogExport.build(exportDir, header, logcat, 1764000000000L);
        assertTrue(r.file.getName(), LogShareProviderNameRule.ok(r.file.getName()));
        assertTrue(r.withLogcat);
        Map<String, String> z = unzip(r.file);
        assertEquals(new java.util.HashSet<String>(Arrays.asList(AppLogExport.LOG_ENTRY, AppLogExport.LOGCAT_ENTRY)), z.keySet());
        String all = z.get(AppLogExport.LOG_ENTRY) + z.get(AppLogExport.LOGCAT_ENTRY);
        assertTrue(all.contains("[ROUTE-1]"));
        assertTrue(all.contains("主模型 Key：已配置"));
        for (String bad : new String[]{"sk-live", "sk-abcdefghijklmnop", "zzzzzzzzzzzz9999", "RAWKEY1234567890", "abcdefabcdef", "SECRETQ",
                "zhangsan@example.com", "13912345678", "11010519491231002X", "/v1/chat"}) {
            assertFalse("导出里不应有 " + bad, all.contains(bad));
        }
        // 再导出一次：旧 zip 被清掉
        AppLogExport.build(exportDir, header, null, 1764000060000L);
        assertEquals(1, exportDir.listFiles().length);
    }

    @Test public void filterOwnKeepsOnlyOurLines() {
        String cat = "11-24 10:00:00.000  1234  1250 I OneSay: 我们的\n"
                + "11-24 10:00:00.001  999  1000 I WeChat: 别人的\n"
                + "11-24 10:00:00.002  1500  1501 I ActivityManager: Process com.onesay.assistant (pid 1234) has died\n";
        String own = AppLogExport.filterOwn(cat, Arrays.asList(1234), "com.onesay.assistant");
        assertTrue(own.contains("我们的"));
        assertTrue(own.contains("has died"));
        assertFalse(own.contains("别人的"));
    }

    /** 和 LogShareProvider.nameOk 同一条规则（Provider 类在 JVM 里不能实例化，规则另测）。 */
    static final class LogShareProviderNameRule {
        static boolean ok(String n) {
            return LogShareProvider.nameOk(n);
        }
    }

    @Test public void shareProviderOnlyServesExportZips() {
        assertTrue(LogShareProvider.nameOk("OneSay-log-20261124-1050.zip"));
        assertFalse(LogShareProvider.nameOk("../onesay.log"));
        assertFalse(LogShareProvider.nameOk("onesay.log"));
        assertFalse(LogShareProvider.nameOk("OneSay-log-20261124-1050.zip/../../x"));
        assertFalse(LogShareProvider.nameOk(null));
    }
}
