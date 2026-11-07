package com.onesay.assistant.v2.ui.widget;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 2026-11-05 用户真机反馈：后处理面板输入框有字时右侧没变「发送」→ 所有输入栏统一：有字 = 发送箭头，空 = 麦克风。 */
public class SendMicPolicyTest {

    @Test public void modeFollowsText() {
        assertEquals(SendMicPolicy.Mode.MIC, SendMicPolicy.mode(null, false, false));
        assertEquals(SendMicPolicy.Mode.MIC, SendMicPolicy.mode("", false, false));
        assertEquals("只有空白仍是麦克风", SendMicPolicy.Mode.MIC, SendMicPolicy.mode("  \n\t", false, false));
        assertEquals(SendMicPolicy.Mode.SEND, SendMicPolicy.mode("这是什么", false, false));
        assertEquals(SendMicPolicy.Mode.SEND, SendMicPolicy.mode(" a ", false, false));
        assertEquals("只带附图也能发", SendMicPolicy.Mode.SEND, SendMicPolicy.mode("", true, false));
        assertEquals("录音中优先", SendMicPolicy.Mode.LISTENING, SendMicPolicy.mode("abc", false, true));
        assertEquals("发送", SendMicPolicy.desc(SendMicPolicy.Mode.SEND));
        assertEquals("语音输入", SendMicPolicy.desc(SendMicPolicy.Mode.MIC));
        assertEquals(SendMicPolicy.Mode.SEND, SendMicPolicy.onTap("x", false, false));
        assertEquals(SendMicPolicy.Mode.MIC, SendMicPolicy.onTap("", false, false));
        assertTrue(SendMicPolicy.hasText("x"));
        assertFalse(SendMicPolicy.hasText(" "));
    }

    /** 两条输入栏都接了 TextWatcher 实时切换、都走同一张表；卡片面板输入条空着点麦克风接到语音。 */
    @Test public void barsWired() throws Exception {
        String pill = read("app/src/com/onesay/assistant/v2/ui/widget/PillSearchBar.java");
        assertTrue(pill.contains("addTextChangedListener") && pill.contains("refreshSendMic()"));
        assertTrue(pill.contains("SendMicPolicy.mode(") && pill.contains("ChatIcons.SEND") && pill.contains("ChatIcons.MIC"));
        assertFalse("不再是固定「发送」文字胶囊", pill.contains("setText(\"发送\")"));
        String chat = read("app/src/com/onesay/assistant/v2/ui/chat/ChatBottomBar.java");
        assertTrue(chat.contains("addTextChangedListener") && chat.contains("refreshSendMic()"));
        assertTrue(chat.contains("SendMicPolicy.mode(") && chat.contains("ChatIcons.SEND"));
        String view = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(view.contains("mSearchBar.setOnMicListener("));
        String icons = read("app/src/com/onesay/assistant/v2/ui/chat/ChatIcons.java");
        assertTrue(icons.contains("case SEND:"));
    }

    static String read(String rel) throws Exception {
        File dir = com.onesay.assistant.v2.testutil.TestRoot.start(SendMicPolicyTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) break;
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(dir, rel).toPath()), StandardCharsets.UTF_8);
    }
}
