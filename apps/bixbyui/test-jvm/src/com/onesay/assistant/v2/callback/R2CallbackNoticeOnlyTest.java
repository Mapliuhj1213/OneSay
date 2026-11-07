package com.onesay.assistant.v2.callback;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * r2/fixes 第 7 项：回调 / 邮件的岛提示只经 NoticeCenter（NoticeHooks.postCallback / postDraftPending）。
 * 提醒总开关或来源关着时不再退回旧的岛完成卡（bgStarted / bgEnded），那条路绕开了去重、免打扰和每日上限。
 */
public class R2CallbackNoticeOnlyTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void callbackAndMailNeverUseLegacyIslandCard() throws Exception {
        for (String f : new String[]{"app/src/com/onesay/assistant/v2/callback/CallbackHub.java", "app/src/com/onesay/assistant/v2/callback/OutboundMailHooks.java"}) {
            String s = src(f);
            assertFalse(f, s.contains(".bgStarted("));
            assertFalse(f, s.contains(".bgEnded("));
            assertFalse(f, s.contains(".removeBg("));
        }
    }

    @Test public void islandChannelGoesThroughNoticeCenterOnly() throws Exception {
        String hub = src("app/src/com/onesay/assistant/v2/callback/CallbackHub.java");
        assertTrue(hub.contains("NoticeHooks.postCallback(app, islandId"));
        assertTrue("关着时只记一笔，不上岛", hub.contains("via.append(\"island-off \")"));
        String mail = src("app/src/com/onesay/assistant/v2/callback/OutboundMailHooks.java");
        assertTrue(mail.contains("NoticeHooks.postDraftPending(app, s.id"));
        assertTrue("起草失败的提示也走 NoticeCenter", mail.contains("NoticeHooks.postCallback(app, \"mail.out.\" + s.id"));
        assertTrue("草稿待确认的通知（打开确认卡的唯一入口）保留", mail.contains("notifyCard(app, s);"));
    }

    @Test public void noticeHooksStillGateOnMasterAndSource() throws Exception {
        String n = src("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
        assertTrue(n.contains("if (!NoticeSettings.master(app) || !NoticeSettings.source(app, NoticeSettings.K_SRC_CALLBACK)) return false;"));
        assertTrue(n.contains("if (!NoticeSettings.master(app) || !NoticeSettings.source(app, NoticeSettings.K_SRC_MAIL)) return false;"));
    }
}
