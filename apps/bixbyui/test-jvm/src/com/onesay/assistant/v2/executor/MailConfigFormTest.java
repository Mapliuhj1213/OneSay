package com.onesay.assistant.v2.executor;

import org.junit.Test;

import static org.junit.Assert.*;

/** P1-13：邮箱设置页的校验 / 预设 / 授权码保留规则 / 面板入口判定。 */
public class MailConfigFormTest {

    @Test public void qqPresetAndValidSave() {
        assertEquals("imap.qq.com", MailConfigForm.presetHost(MailBridge.PROVIDER_QQ));
        assertNull(MailConfigForm.presetHost(MailBridge.PROVIDER_CUSTOM));
        MailConfigForm.Result r = MailConfigForm.validate(" IMAP.qq.com ", "993", " 12345@qq.com ",
                "abcdefghijklmnop", "", false);
        assertTrue(r.error, r.ok());
        assertEquals("imap.qq.com", r.host);
        assertEquals(993, r.port);
        assertEquals("12345@qq.com", r.user);
        assertEquals("abcdefghijklmnop", r.pass);
        assertNull(r.warning);
        assertTrue(MailConfigForm.credentialHint("qq").contains("授权码"));
    }

    @Test public void spacedAuthCodeIsCompacted() {
        MailConfigForm.Result r = MailConfigForm.validate("imap.qq.com", "993", "a@qq.com",
                "abcd efgh ijkl mnop", "", false);
        assertEquals("abcdefghijklmnop", r.pass);
    }

    @Test public void rejectsBadInput() {
        assertFalse(MailConfigForm.validate("", "993", "a@qq.com", "x", "", false).ok());
        assertFalse("不许带协议", MailConfigForm.validate("imaps://imap.qq.com", "993", "a@qq.com", "x", "", false).ok());
        assertFalse("不许带端口", MailConfigForm.validate("imap.qq.com:993", "993", "a@qq.com", "x", "", false).ok());
        assertFalse(MailConfigForm.validate("imap.qq.com", "abc", "a@qq.com", "x", "", false).ok());
        assertFalse(MailConfigForm.validate("imap.qq.com", "70000", "a@qq.com", "x", "", false).ok());
        assertFalse(MailConfigForm.validate("imap.qq.com", "993", "not-an-email", "x", "", false).ok());
        assertFalse("首次配置必须填授权码", MailConfigForm.validate("imap.qq.com", "993", "a@qq.com", "", "", false).ok());
    }

    @Test public void blankPasswordKeepsStoredButNotWhenHostChanges() {
        MailConfigForm.Result keep = MailConfigForm.validate("imap.qq.com", "993", "a@qq.com", "  ",
                "imap.qq.com", true);
        assertTrue(keep.ok());
        assertNull("留空 = 不修改已存授权码", keep.pass);
        MailConfigForm.Result moved = MailConfigForm.validate("imap.evil.com", "993", "a@qq.com", "",
                "imap.qq.com", true);
        assertFalse("换服务器必须重填授权码（S1）", moved.ok());
        assertTrue(moved.error.contains("重新填写授权码"));
        MailConfigForm.Result port = MailConfigForm.validate("imap.qq.com", "143", "a@qq.com", "x",
                "imap.qq.com", true);
        assertNotNull("非 993 给 SSL 提醒", port.warning);
    }

    @Test public void settingsLinkOfferedOnlyWhenRelevant() {
        assertTrue(MailConfigForm.shouldOfferSettings("x", ErrorCode.NOT_CONFIGURED, "mail.inbox.latest", false));
        assertTrue(MailConfigForm.shouldOfferSettings("x", ErrorCode.AUTH_FAILED, "mail.inbox.latest", true));
        assertFalse(MailConfigForm.shouldOfferSettings("x", ErrorCode.NOT_CONFIGURED, "settings.brightness.set", false));
        assertTrue("AgentLoop 方案提到授权码且确未配置",
                MailConfigForm.shouldOfferSettings("你可以：①开启 IMAP…生成授权码", null, null, false));
        assertFalse("已配置就不再提示", MailConfigForm.shouldOfferSettings("生成授权码", null, null, true));
        assertFalse(MailConfigForm.shouldOfferSettings("电量还有 80%", null, null, false));
        assertTrue("NOT_CONFIGURED 提示指向设置页", MailBridge.NOT_CONFIGURED_HINT.contains("邮箱（IMAP）配置"));
    }
}
