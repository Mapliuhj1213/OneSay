package com.onesay.assistant.v2.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 2026-10-25 安全修复 IPC-3：控制台深链只有本 App 自己发的（带进程内口令）才直达；外部拉敏感页要页内再确认。 */
public class SecIpcDeepLinkTokenTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void tokenIsRandomHexAndCheckedExactly() {
        String t = ConsoleDeepLink.token();
        assertTrue(t.matches("[0-9a-f]{32}"));
        assertTrue(ConsoleDeepLink.tokenOk(t));
        assertFalse(ConsoleDeepLink.tokenOk(null));
        assertFalse(ConsoleDeepLink.tokenOk(""));
        assertFalse(ConsoleDeepLink.tokenOk(t.toUpperCase()));
        assertFalse(ConsoleDeepLink.tokenOk(t.substring(1)));
        assertFalse(ConsoleDeepLink.tokenOk(t + "0"));
    }

    @Test public void decide() {
        String tok = ConsoleDeepLink.token();
        ConsoleDeepLink.Decision d = ConsoleDeepLink.decide("console:asr.key", tok);
        assertEquals(ConsoleDeepLink.Action.OPEN, d.action);
        assertEquals("asr.key", d.id);
        assertEquals(ConsoleDeepLink.Action.OPEN, ConsoleDeepLink.decide("console:callback.history", tok).action);

        d = ConsoleDeepLink.decide("console:asr.key", null);
        assertEquals("外部拉敏感页 → 页内再确认", ConsoleDeepLink.Action.CONFIRM, d.action);
        assertEquals("asr.key", d.id);
        assertEquals(ConsoleDeepLink.Action.CONFIRM, ConsoleDeepLink.decide("console:asr.key", "deadbeef").action);
        d = ConsoleDeepLink.decide("console:callback.history", null);
        assertEquals("外部拉非敏感页 → 首页", ConsoleDeepLink.Action.HOME, d.action);
        assertNull(d.id);

        assertEquals("白名单外：有口令也回首页", ConsoleDeepLink.Action.HOME, ConsoleDeepLink.decide("console:dev.secrets", tok).action);
        assertEquals(ConsoleDeepLink.Action.HOME, ConsoleDeepLink.decide("console:ASR.KEY", tok).action);
        assertEquals(ConsoleDeepLink.Action.HOME, ConsoleDeepLink.decide(null, tok).action);
    }

    @Test public void sensitiveSubsetOfAllowed() {
        for (String s : ConsoleDeepLink.SENSITIVE) assertTrue(s, ConsoleDeepLink.ALLOWED.contains(s));
        assertTrue(ConsoleDeepLink.SENSITIVE.contains(com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE));
    }

    @Test public void wiring() throws Exception {
        String a = src("app/src/com/onesay/assistant/v2/ui/MainActivity.java");
        assertTrue(a.contains("ConsoleDeepLink.decide(page, it.getStringExtra(ConsoleDeepLink.EXTRA_TOKEN))"));
        assertTrue("CONFIRM 走页内再确认", a.contains("ConsoleDeepLink.Action.CONFIRM") && a.contains("mConsole.confirm("));
        assertTrue("用过即弃", a.contains("it.removeExtra(ConsoleDeepLink.EXTRA_TOKEN)"));
        String stamp = "ConsoleDeepLink.EXTRA_TOKEN, com.onesay.assistant.v2.ui.ConsoleDeepLink.token())";
        assertTrue("岛上提醒打开控制台带口令", src("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java").contains(stamp));
        assertTrue("唤醒卡「去配置」带口令", src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").contains(stamp));
    }
}
