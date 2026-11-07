package com.onesay.assistant.v2.notice;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 接线 + 隐私扫描：通知监听最先调智能筛选（在事件任务白名单过滤之前）、移除时撤岛、面板服务起来时登记智能来源；
 * 智能来源的日志不带通知正文 / 号码 / 验证码；debug 注入只在 test 包（release 不可达由既有 BuildVariant / 内核调试入口测试覆盖）。
 */
public class IslandSmartWiringTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, "app/src/" + rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void wiring() throws Exception {
        String nl = src("com/onesay/assistant/v2/events/EventNotificationListener.java");
        int hook = nl.indexOf("IslandSmartHooks.onNotificationPosted(");
        assertTrue("通知监听转给智能筛选", hook > 0);
        assertTrue("在事件任务白名单过滤之前（否则只有事件任务白名单里的 App 能上岛）", hook < nl.indexOf("EventHooks.wantsPackage("));
        assertTrue("移除时撤岛", nl.contains("IslandSmartHooks.onNotificationRemoved("));
        String hooks = src("com/onesay/assistant/v2/notice/NoticeHooks.java");
        assertTrue("面板服务起来时登记智能来源", hooks.contains("IslandSmartHooks.start(app)"));
        assertTrue("岛上那行带计时 / 颜色 / 留岛掩码", hooks.contains("n.chronoBase, n.countdown, n.tone, keep"));
    }

    @Test public void logsNeverCarryNotificationContentNumberOrCode() throws Exception {
        String s = src("com/onesay/assistant/v2/notice/IslandSmartHooks.java");
        for (String line : s.split("\n")) {
            if (!line.contains("Log.")) continue;
            for (String bad : new String[]{"in.title", "in.text", "number", "text)", "who", "r.notice.title", "summary", "o.arg"}) {
                assertFalse("日志行带了敏感字段 " + bad + "：" + line.trim(), line.contains(bad));
            }
        }
        String hooks = src("com/onesay/assistant/v2/notice/NoticeHooks.java");
        assertTrue("动作日志走 Outcome.toString（COPY_TEXT 打码）", hooks.contains("\"[NOTICE] 动作 \" + id + \"#\" + index + \" → \" + o)"));
    }

    @Test public void debugInjectionOnlyInTestPackage() throws Exception {
        String d = src("com/onesay/assistant/v2/test/NoticeTestDriver.java");
        for (String c : new String[]{"\"mic\"", "\"cam\"", "\"phone\"", "\"nl\"", "\"nlrm\"", "\"battery\"", "\"headset\"", "\"privprobe\""}) {
            assertTrue("每个新来源都有 debug 注入：" + c, d.contains(c));
        }
        String hooks = src("com/onesay/assistant/v2/notice/IslandSmartHooks.java");
        assertFalse("生产胶水不引用 test 包", hooks.contains("com.onesay.assistant.v2.test."));
        assertFalse("没有发送邮件 / 短信的路径", hooks.contains("sendTextMessage") || hooks.contains("sendDraft") || hooks.contains("SmsManager"));
        assertFalse("不打电话（只接 / 挂 / 静音 / 免提）", hooks.contains("ACTION_CALL") || hooks.contains("placeCall"));
    }
}
