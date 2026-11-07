package com.onesay.assistant.vendor.samsung;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ lane C3-4：三星浏览器兜底 Intent（只开链接，不做 GUI）。 */
public class SamsungBrowserTest {

    @Test
    public void spec_only_http_and_carries_tab_reuse_extras() {
        SamsungBrowser.Spec s = SamsungBrowser.spec("https://m.example.com/p?a=1", SamsungBrowser.PKG, "com.onesay.assistant.debug", null);
        assertEquals("android.intent.action.VIEW", s.action);
        assertEquals(SamsungBrowser.PKG, s.pkg);
        assertEquals("com.onesay.assistant.debug", s.extras.get(SamsungBrowser.EXTRA_APP_ID));
        assertEquals(Boolean.TRUE, s.extras.get(SamsungBrowser.EXTRA_NEW_TAB));
        assertEquals("默认不猜桌面模式参数", 2, s.extras.size());
        assertNull(SamsungBrowser.spec("intent://x#Intent;end", SamsungBrowser.PKG, "c", null));
        assertNull(SamsungBrowser.spec("javascript:alert(1)", SamsungBrowser.PKG, "c", null));
        assertNull(SamsungBrowser.spec("https://a.com", null, "c", null));
    }

    @Test
    public void desktop_extras_are_configurable_and_sanitized() {
        SamsungBrowser.Spec s = SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", "desktop_mode=true; bad key=1;x=maybe;y=0");
        assertEquals(Boolean.TRUE, s.extras.get("desktop_mode"));
        assertEquals(Boolean.FALSE, s.extras.get("y"));
        assertFalse(s.extras.containsKey("bad key"));
        assertFalse(s.extras.containsKey("x"));
    }

    /** ★ LC-4：Spec.desktop 只在真带了值为 true 的桌面参数时为真；文案跟着它走。 */
    @Test
    public void desktop_flag_follows_actual_intent_extras() {
        assertFalse("默认配置为空 → 不是桌面版", SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", null).desktop);
        assertFalse(SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", "").desktop);
        assertFalse("只有 false 值 / 非法值 → 不算带了桌面参数",
                SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", "desktop_mode=false;bad key=1;x=maybe").desktop);
        assertTrue(SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", "desktop_mode=true").desktop);
        assertEquals("已在三星浏览器打开，请你接着看", SamsungBrowser.handoffNote(false));
        assertEquals("已在三星浏览器（桌面版）打开，请你接着看", SamsungBrowser.handoffNote(true));
    }

    @Test
    public void am_command_for_device_check() {
        List<String> c = SamsungBrowser.amCommand(SamsungBrowser.spec("https://a.com/x", SamsungBrowser.PKG, "com.onesay.assistant", null));
        assertEquals(Arrays.asList("am", "start", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE",
                "-d", "https://a.com/x", "-p", SamsungBrowser.PKG, "--es", SamsungBrowser.EXTRA_APP_ID, "com.onesay.assistant",
                "--ez", SamsungBrowser.EXTRA_NEW_TAB, "true"), c);
        assertTrue(SamsungBrowser.amCommand(null).isEmpty());
    }

    @Test
    public void ladder_uses_samsung_only_after_webview_cannot_open() {
        // 详见 ResearchLadderTest.blocked_webview_falls_back_to_samsung_last / takeover_is_not_sent_to_samsung
        assertEquals("com.sec.android.app.sbrowser", SamsungBrowser.PKGS[0]);
        assertEquals("com.sec.android.app.sbrowser.beta", SamsungBrowser.PKGS[1]);
    }

    @Test
    public void console_toggle_note_follows_desktop_config() {
        // ★ LC-4 规则用到控制台开关说明：没配桌面参数就不写「桌面版」
        assertEquals("只帮你打开链接，不替你点", SamsungBrowser.toggleNote(false));
        assertEquals("只帮你打开链接（桌面版），不替你点", SamsungBrowser.toggleNote(true));
        assertFalse(SamsungBrowser.desktopConfigured((String) null));
        assertFalse(SamsungBrowser.desktopConfigured(""));
        assertFalse(SamsungBrowser.desktopConfigured("desktop_mode=false"));
        assertTrue(SamsungBrowser.desktopConfigured("desktop_mode=true"));
        assertTrue(SamsungBrowser.desktopConfigured("a=0;b=1"));
        assertEquals(SamsungBrowser.spec("https://a.com", SamsungBrowser.PKG, "c", "x=1").desktop, SamsungBrowser.desktopConfigured("x=1"));
    }

    @Test
    public void console_toggle_not_hardcoded() throws Exception {
        String p = com.onesay.assistant.v2.errand.ResearchLadderEscalationTest.src("com/onesay/assistant/v2/kernel/plugins/ErrandPlugin.java");
        assertFalse(p.contains("\"只帮你打开链接（桌面版），不替你点\""));
        assertTrue(p.contains("SamsungBrowser.toggleNote(com.onesay.assistant.vendor.samsung.SamsungBrowser.desktopConfigured(act))"));
    }
}
