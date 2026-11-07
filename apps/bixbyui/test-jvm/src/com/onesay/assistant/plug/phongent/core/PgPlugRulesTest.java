package com.onesay.assistant.plug.phongent.core;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** 插件规范守卫：core 纯净、改写文件带许可头、OneSay 侧接线、总开关默认关、关着零副作用。 */
public class PgPlugRulesTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), Charset.forName("UTF-8"));
    }

    static final String CORE = "app/src/com/onesay/assistant/plug/phongent/core/";

    @Test public void coreDependsOnlyOnJavaAndOrgJson() throws Exception {
        File[] files = new File(root(), CORE).listFiles();
        assertNotNull(files);
        int n = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            n++;
            String s = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
            for (String line : s.split("\n")) {
                String l = line.trim();
                if (l.startsWith("import ")) {
                    assertTrue(f.getName() + ": " + l, l.startsWith("import java.") || l.startsWith("import org.json.")
                            || l.startsWith("import static java."));
                }
            }
            assertFalse(f.getName(), s.contains("android.") && s.contains("import android"));
            assertFalse(f.getName() + " 引用了 OneSay 内部类", s.contains("com.onesay.assistant.v2"));
        }
        assertTrue(n >= 18);
    }

    @Test public void derivedFilesCarryApacheHeaderAndChangeNotice() throws Exception {
        String[] derived = {CORE + "PgHttp.java", CORE + "PgAuth.java", CORE + "PgTasks.java", CORE + "PgMessages.java", CORE + "PgState.java",
                CORE + "PgApi.java", CORE + "PgServer.java", CORE + "PgVideo.java", CORE + "PgScreen.java",
                "app/src/com/onesay/assistant/v2/phongent/PgScreenSource.java", "app/src/com/onesay/assistant/v2/phongent/PhongentService.java"};
        for (String d : derived) {
            String s = read(d);
            assertTrue(d, s.contains("Licensed under the Apache License, Version 2.0"));
            assertTrue(d, s.contains("改动声明（OneSay"));
            assertTrue(d, s.contains("phongent"));
        }
        String notice = read("NOTICE");
        assertTrue(notice.contains("Copyright 2026 wangzan101"));
        assertTrue(notice.contains("https://github.com/mylea1/phongent"));
        assertTrue(notice.contains("Apache License"));
        assertEquals(notice, read("app/assets/NOTICE.txt"));
        for (String d : derived) assertTrue("NOTICE 列出 " + d, notice.contains(d));
        String about = read("app/src/com/onesay/assistant/v2/ui/console/AboutPage.java");
        assertTrue(about.contains("\"Mika\"") && about.contains("\"phongent\""));
        assertFalse(read("app/src/com/onesay/assistant/v2/phone/PhoneAgent.java").contains("没有拷贝源码"));
        assertFalse(read("app/src/com/onesay/assistant/v2/phone/UiTree.java").contains("没有拷贝源码"));
    }

    @Test public void hostWiringAndDefaultOff() throws Exception {
        String settings = read("app/src/com/onesay/assistant/v2/phongent/PgSettings.java");
        assertTrue("总开关默认关", settings.contains("new FeatureSwitch(PREF, \"enabled\", false, \"phongent.off\")"));
        String man = read("app/AndroidManifest.xml");
        int i = man.indexOf("com.onesay.assistant.v2.phongent.PhongentService");
        assertTrue(i > 0);
        assertTrue("服务不导出", man.substring(i, i + 120).contains("android:exported=\"false\""));
        assertFalse("不注册接收器", man.contains("com.onesay.assistant.v2.phongent.") && man.matches("(?s).*<receiver[^>]*phongent.*"));
        String host = read("app/src/com/onesay/assistant/v2/phongent/PgOneSayHost.java");
        assertTrue("生产不放回环", host.contains("return false;   // 生产不放回环"));
        assertTrue("按钮发给不导出的服务", host.contains("PendingIntent.getService("));
        assertFalse(host.contains("registerReceiver"));
        String svc = read("app/src/com/onesay/assistant/v2/phongent/PhongentService.java");
        assertTrue("开关关着不起", svc.contains("if (PgSettings.enabled(app))"));
        assertTrue(svc.contains("if (!PgSettings.enabled(this))"));
        assertFalse(svc.contains("registerReceiver"));
        String nl = read("app/src/com/onesay/assistant/v2/events/EventNotificationListener.java");
        assertTrue(nl.contains("com.onesay.assistant.v2.phongent.PgNotices.onPosted(this, sbn);"));
        String notices = read("app/src/com/onesay/assistant/v2/phongent/PgNotices.java");
        int hook = notices.indexOf("if (sink == null || sbn == null) return;");
        assertTrue("没 attach 时第一行返回", hook > 0 && hook < notices.indexOf("getPackageName()"));
        assertTrue("白名单在读字段之前", notices.indexOf("relayApp(pkg)") < notices.indexOf("EXTRA_TITLE"));
        for (String line : notices.split("\n")) assertFalse("通知原文不进日志：" + line, line.contains("Log."));
        assertTrue(read("app/src/com/onesay/assistant/v2/kernel/BuiltinPlugins.java").contains("new com.onesay.assistant.v2.kernel.plugins.PhongentPlugin()"));
        String exec = host.substring(host.indexOf("public long submit("));
        assertTrue("远程任务走 q: 队列（S3Runner 照走 ExecutionGate + 红卡岛上确认）", exec.contains("JobQueue.OWNER_PREFIX"));
        assertTrue("通知代回 tainted 在入库前定", exec.indexOf("RUN_TPL_TAINTED") < exec.indexOf("addRun(r)"));
    }
}
