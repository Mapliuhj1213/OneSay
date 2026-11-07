package com.onesay.assistant.v2.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** r2/fixes 第 8 项：page=console:<id> 只认白名单，非法 id 打开首页。 */
public class R2ConsoleDeepLinkTest {

    @Test public void onlyWhitelistedPages() {
        assertEquals("callback.history", ConsoleDeepLink.allowedPage("console:callback.history"));
        // integrate：唤醒卡「去配置」的深链必须在白名单里（否则落到控制台首页）
        assertEquals(com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE, ConsoleDeepLink.allowedPage("console:" + com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE));
        assertNull(ConsoleDeepLink.allowedPage("console:dev.secrets"));
        assertNull(ConsoleDeepLink.allowedPage("console:"));
        assertNull(ConsoleDeepLink.allowedPage("console:../x"));
        assertNull(ConsoleDeepLink.allowedPage("console:callback.history.x"));
        assertNull(ConsoleDeepLink.allowedPage("quicknote"));
        assertNull(ConsoleDeepLink.allowedPage(null));
        assertTrue(ConsoleDeepLink.isConsoleLink("console:whatever"));
        assertEquals("回调历史", ConsoleDeepLink.safeTitle("回调\n历史"));
        assertEquals(40, ConsoleDeepLink.safeTitle(new String(new char[100]).replace('\0', 'a')).length());
    }

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void everyPageTheAppOpensIsWhitelisted() throws Exception {
        String n = src("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        Matcher m = Pattern.compile("NoticeAction\\.Kind\\.OPEN_PAGE, \"([^\"]+)\"").matcher(n);
        int found = 0;
        while (m.find()) {
            found++;
            assertNotNull("岛上提醒会打开 " + m.group(1) + "，必须在白名单里", ConsoleDeepLink.allowedPage("console:" + m.group(1)));
        }
        assertTrue(found > 0);
    }

    @Test public void mainActivityUsesWhitelistAndFallsBackHome() throws Exception {
        String a = src("app/src/com/onesay/assistant/v2/ui/MainActivity.java");
        assertTrue(a.contains("ConsoleDeepLink.allowedPage(page)"));
        assertTrue(a.contains("mConsole.nav.popToRoot();"));
        assertFalse("不再把任意 id 交给 openPage", a.contains("mConsole.openPage(page.substring(8)"));
    }
}
