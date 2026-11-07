package com.onesay.assistant.v2.ui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/** 对话窗开着时不自动开录音（播报完续听只在卡片上，且续听不展开对话窗）。 */
public class ChatFollowUpTest {

    @Test public void noAutoListenInChatWindow() {
        assertFalse(ChatWindowPolicy.autoFollowUp(true));
        assertTrue("卡片上续听照旧", ChatWindowPolicy.autoFollowUp(false));
    }

    @Test public void wiring() throws Exception {
        String ws = InputFocusTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(ws.contains("&& com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.autoFollowUp(chat);"));
        String svc = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int b = svc.indexOf("public void beginFollowUpListening()");
        String body = svc.substring(b, svc.indexOf("\r\n    }\r\n", b));
        assertTrue("续听只换外观", body.contains("mSheetView.switchToVoiceModeQuiet();"));
        assertFalse("续听不走接着说边界", body.contains("mSheetView.switchToVoiceMode();"));
        String view = InputFocusTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        int q = view.indexOf("public void switchToVoiceModeQuiet()");
        assertFalse("续听不冻结 / 不展开", view.substring(q, view.indexOf("public void switchToVoiceMode()", q)).contains("chatBoundary("));
    }
}
