package com.onesay.assistant.v2.ui.chat;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * 输入框被动获焦 → 误进打字态（停收音 + 弹键盘）；收键盘被当成发送。
 * 唤醒后没录上 / 点麦克风弹键盘 / 要点好几次才进录音 / 「收键盘就发出去了」同一根因。
 */
public class InputFocusTest {

    @Test public void passiveFocusNeverEntersTyping() {
        assertFalse("排布切换 / 别的输入框被藏时系统交来的焦点", ChatWindowPolicy.typingOnFocus(true, false));
        assertTrue("用户按下了输入框", ChatWindowPolicy.typingOnFocus(true, true));
        assertFalse("失焦", ChatWindowPolicy.typingOnFocus(false, true));
    }

    @Test public void onlyImeSendSubmits() {
        assertEquals("与 EditorInfo.IME_ACTION_SEND 同值", android.view.inputmethod.EditorInfo.IME_ACTION_SEND, ChatWindowPolicy.IME_ACTION_SEND);
        assertTrue(ChatWindowPolicy.isSendAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND));
        assertFalse("完成", ChatWindowPolicy.isSendAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE));
        assertFalse("回车 / 未指定（IME_NULL）", ChatWindowPolicy.isSendAction(android.view.inputmethod.EditorInfo.IME_NULL));
        assertFalse("前往", ChatWindowPolicy.isSendAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO));
        assertFalse("搜索", ChatWindowPolicy.isSendAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH));
    }

    @Test public void bottomBarWiring() throws Exception {
        String bar = src("app/src/com/onesay/assistant/v2/ui/chat/ChatBottomBar.java");
        assertTrue("键盘动作只认发送", bar.contains("if (ChatWindowPolicy.isSendAction(actionId))"));
        assertFalse("不再把「完成」当发送", bar.contains("IME_ACTION_DONE"));
        assertFalse("不再把回车当发送", bar.contains("KEYCODE_ENTER"));
        assertTrue("获焦先过意图闸门", bar.contains("ChatWindowPolicy.typingOnFocus(true, mTypingIntent)"));
        assertTrue("按下输入框才记意图", bar.contains("if (a == android.view.MotionEvent.ACTION_DOWN) mTypingIntent = true;"));
        assertTrue("点麦克风清掉打字意图", bar.contains("mTypingIntent = false;   // 点麦克风 = 只录音"));
        int foc = bar.indexOf("setOnFocusChangeListener");
        int end = bar.indexOf("setOnTouchListener", foc);
        assertFalse("失焦回调里没有提交", bar.substring(foc, end).contains("submit()"));
        int pg = bar.indexOf("if (!ChatWindowPolicy.typingOnFocus(true, mTypingIntent))");
        assertFalse("被动获焦不清焦点（真机：清了系统又交回来，20–90 ms 一次循环）", bar.substring(pg, bar.indexOf("mMenu.setVisibility(GONE);", pg)).contains("clearFocus"));
        String view = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertFalse("让位 / 打字态判断不再只看 hasFocus", view.contains("mChat.input().hasFocus()"));
        assertTrue(view.contains("mChat.bar().typingFocus()"));
        assertTrue("宿主要打字时显式放行", view.contains("if (bar) mChat.bar().focusForTyping();"));
        String ctl = src("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java");
        assertTrue("铅笔改问句显式放行", ctl.contains("mBar.focusForTyping();   // 铅笔改问句"));
    }

    static String src(String rel) throws Exception {
        String r = System.getProperty("bixby.root");
        return new String(Files.readAllBytes(new File(r == null ? "." : r, rel).toPath()), StandardCharsets.UTF_8);
    }
}
