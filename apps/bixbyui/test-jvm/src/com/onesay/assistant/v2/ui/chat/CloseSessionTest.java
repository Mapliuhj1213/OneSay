package com.onesay.assistant.v2.ui.chat;

import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 关窗后再唤醒像第一次一样弹原卡并录音，不再进对话窗停在上一轮回复。
 * 根因：decide() 面板不在屏上时只看会话记忆（离屏保留 5 分钟）→ 关窗 5 分钟内再唤醒一律 EXPAND。
 */
public class CloseSessionTest {

    @Test public void userCloseEndsSessionHandoffDoesNot() {
        assertTrue("拖拽条 / ✕ / 返回键", ChatWindowPolicy.endsSession(CloseReason.USER_CLOSE));
        assertTrue("点外部", ChatWindowPolicy.endsSession(CloseReason.USER_OUTSIDE));
        assertTrue("Home / 最近任务", ChatWindowPolicy.endsSession(CloseReason.USER_HOME));
        assertTrue("续听里说「好了」", ChatWindowPolicy.endsSession(CloseReason.ASSISTANT_DONE));
        assertFalse("到时自动收起：仍按保持期接着上轮", ChatWindowPolicy.endsSession(CloseReason.AUTO_HIDE));
        assertFalse("分享 / 跳转让位", ChatWindowPolicy.endsSession(CloseReason.HANDOFF));
        assertFalse(ChatWindowPolicy.endsSession(null));
    }

    @Test public void wakeAfterUserCloseIsFirstRoundCard() {
        ChatTranscript t = new ChatTranscript();
        t.upsert("今天星期几", "今天星期四。", "L0", 120);
        t.freeze();
        t.upsert("明天呢", "明天星期五。", "L0", 90);
        // 以前：关窗（面板不在屏上）但会话记忆没超时 → EXPAND，进对话窗停在上一轮
        assertEquals(ChatWindowPolicy.Decision.EXPAND,
                ChatWindowPolicy.decide(ChatWindowPolicy.Trigger.WAKE, true, true, false, t.size(), false, true));
        t.clear();   // endSession()
        assertEquals(0, t.size());
        assertEquals("关窗后再唤醒 = 原卡", ChatWindowPolicy.Decision.CARD,
                ChatWindowPolicy.decide(ChatWindowPolicy.Trigger.WAKE, true, true, false, t.size(), false, false));
        t.upsert("几点了", "十点。", "L0", 80);
        assertEquals("新会话里第二次交互才进对话窗", ChatWindowPolicy.Decision.EXPAND,
                ChatWindowPolicy.decide(ChatWindowPolicy.Trigger.CONTINUE_LISTEN, true, true, false, t.size(), true, true));
    }

    @Test public void serviceWiring() throws Exception {
        String svc = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertEquals("FloatingSheetService 保持 CRLF", svc.split("\n", -1).length, svc.split("\r\n", -1).length);
        for (String r : new String[]{"USER_CLOSE", "USER_OUTSIDE", "USER_HOME", "ASSISTANT_DONE"}) {
            assertTrue(r, svc.contains("endSessionOnClose(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.CloseReason." + r + ");"));
        }
        int f = svc.indexOf("private void endSessionOnClose(");
        String body = svc.substring(f, svc.indexOf("\r\n    }\r\n", f));
        assertTrue("清对话窗历史", body.contains("mSheetView.chatEndSession()"));
        assertTrue("清会话记忆", body.contains("m.clear();"));
        int share = svc.indexOf("private void chatShare(");
        assertFalse("分享让位不结束会话", svc.substring(share, svc.indexOf("\r\n    }\r\n", share)).contains("endSessionOnClose"));
        String ctl = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java");
        assertTrue(ctl.contains("public void endSession()") && ctl.contains("mTranscript.clear();"));
    }
}
