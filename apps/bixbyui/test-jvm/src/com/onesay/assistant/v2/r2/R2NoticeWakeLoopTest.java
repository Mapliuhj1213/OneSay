package com.onesay.assistant.v2.r2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import com.onesay.assistant.v2.notice.ScheduledMailNotices;

/**
 * ★ integrate 上机（2026-10-24）：定时邮件来源 nextPollAt 恒返回 now → 共用闹钟每 ~1 s RTC_WAKEUP 一次（17 分钟 844 次）。
 */
public class R2NoticeWakeLoopTest {

    @Test public void mailSourceDoesNotPollEverySecond() {
        long now = 1_800_000_000_000L;
        assertEquals("关着不叫醒", -1L, ScheduledMailNotices.nextPollAt(false, 0, now));
        assertEquals("没扫过：马上扫一次", now, ScheduledMailNotices.nextPollAt(true, 0, now));
        long last = now - 1000L;
        long next = ScheduledMailNotices.nextPollAt(true, last, now);
        assertEquals(last + ScheduledMailNotices.POLL_MS, next);
        assertTrue("扫过之后至少 10 分钟内不再叫醒", next - now >= 10L * 60000L);
    }

    @Test public void wiring() throws Exception {
        String src = read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        String mail = src.substring(src.indexOf("class MailSource"));
        assertTrue(mail.contains("ScheduledMailNotices.nextPollAt("));
        assertTrue("poll 记下扫描时刻", mail.contains("mLastPoll = now;"));
        assertFalse("不能再恒返回 now", mail.contains("K_SRC_MAIL) ? now : -1L"));
    }

    static String read(String rel) throws Exception {
        File dir = com.onesay.assistant.v2.testutil.TestRoot.start(R2NoticeWakeLoopTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) break;
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }
}
