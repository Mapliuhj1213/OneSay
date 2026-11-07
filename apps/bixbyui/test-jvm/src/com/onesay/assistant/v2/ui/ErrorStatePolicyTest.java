package com.onesay.assistant.v2.ui;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** 「没听清」错误态 → 短暂显示 → 中性态 → 自动收起；S1 确认卡规则不被破坏。 */
public class ErrorStatePolicyTest {

    static ErrorStatePolicy.Plan p(ErrorStatePolicy.Source s, boolean onScreen, boolean confirm, boolean task, boolean text) {
        return ErrorStatePolicy.plan(s, onScreen, confirm, task, text);
    }

    @Test public void newWakeNoSpeechShowsBrieflyThenHides() {
        ErrorStatePolicy.Plan pl = p(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, true, false, false, false);
        assertTrue(pl.apply);
        assertTrue("清掉残留推荐芯片", pl.clearSuggestions);
        assertTrue("2~3 秒", pl.neutralAfterMs >= 2000 && pl.neutralAfterMs <= 3000);
        assertEquals(pl.neutralAfterMs, pl.hideAfterMs);
        assertTrue(pl.clearTranscriptOnNeutral);
    }

    @Test public void asrFailureAndBadInjectionBehaveTheSame() {
        for (ErrorStatePolicy.Source s : new ErrorStatePolicy.Source[]{
                ErrorStatePolicy.Source.ASR_FAILED, ErrorStatePolicy.Source.INJECTED_QUERY_FAILED}) {
            ErrorStatePolicy.Plan pl = p(s, true, false, false, false);
            assertTrue(s + "", pl.clearSuggestions);
            assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.hideAfterMs);
        }
    }

    @Test public void continueSessionKeepsConversationLonger() {
        ErrorStatePolicy.Plan pl = p(ErrorStatePolicy.Source.NO_SPEECH_CONTINUE, true, false, false, false);
        assertTrue(pl.clearSuggestions);
        assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.neutralAfterMs);
        assertTrue("先回中性态，再收起", pl.hideAfterMs > pl.neutralAfterMs);
        assertFalse("保留上一轮问答", pl.clearTranscriptOnNeutral);
    }

    @Test public void neverPopsUpAHiddenPanel() {
        for (ErrorStatePolicy.Source s : ErrorStatePolicy.Source.values()) {
            ErrorStatePolicy.Plan pl = p(s, false, false, false, false);
            assertFalse(pl.apply);
            assertEquals(-1, pl.hideAfterMs);
        }
    }

    /** S1 不变量：确认卡未决时绝不清、不复位、不自动收起。 */
    @Test public void pendingConfirmationIsNeverAutoHidden() {
        for (ErrorStatePolicy.Source s : ErrorStatePolicy.Source.values()) {
            for (boolean task : new boolean[]{false, true}) {
                for (boolean text : new boolean[]{false, true}) {
                    ErrorStatePolicy.Plan pl = p(s, true, true, task, text);
                    assertEquals(s + "", -1, pl.hideAfterMs);
                    assertEquals(-1, pl.neutralAfterMs);
                    assertFalse(pl.clearSuggestions);
                }
            }
        }
        assertFalse("到点复核：确认卡出现则作废收起", ErrorStatePolicy.stillHide(true, true, false, false));
        assertFalse(ErrorStatePolicy.stillNeutral(true, true, false));
    }

    @Test public void runningTaskOwnsThePanel() {
        ErrorStatePolicy.Plan pl = p(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, true, false, true, false);
        assertEquals(-1, pl.hideAfterMs);
        assertFalse(ErrorStatePolicy.stillHide(true, false, true, false));
    }

    @Test public void textModeOnlyResetsStatusLine() {
        ErrorStatePolicy.Plan pl = p(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, true, false, false, true);
        assertEquals(-1, pl.hideAfterMs);
        assertEquals(ErrorStatePolicy.ERROR_VISIBLE_MS, pl.neutralAfterMs);
        assertFalse("文字模式的芯片是有用的", pl.clearSuggestions);
        assertFalse(ErrorStatePolicy.stillHide(true, false, false, true));
        assertTrue(ErrorStatePolicy.stillHide(true, false, false, false));
        assertFalse("已离屏不再收", ErrorStatePolicy.stillHide(false, false, false, false));
    }

    /** 宿主接线守卫：两个终态入口都走策略，且 cancelAutoHide 会一并取消错误态回调；FSS 保持 CRLF。 */
    @Test public void hostWiring() throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        String s = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
        assertTrue("CRLF", s.contains("\r\n"));
        assertTrue(s.contains("applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE)"));
        assertTrue(s.contains("applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.NO_SPEECH_CONTINUE)"));
        assertTrue(s.contains("applyErrorPolicy(com.onesay.assistant.v2.ui.ErrorStatePolicy.Source.INJECTED_QUERY_FAILED)"));
        int c = s.indexOf("private void cancelAutoHide()");
        String body = s.substring(c, s.indexOf("}", c));
        assertTrue(body.contains("mErrorNeutralRunnable") && body.contains("mErrorHideRunnable"));
        assertTrue("错误态下不能残留「正在听你说」副标题", s.contains("markTerminalState(Color.parseColor(\"#FF9F0A\"), \"没听清\", \"本次唤醒已结束\")"));
    }
}
