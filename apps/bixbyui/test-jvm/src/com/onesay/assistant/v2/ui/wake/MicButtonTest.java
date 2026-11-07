package com.onesay.assistant.v2.ui.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ 综合包：唤醒卡 🎤「续说」——行为、触控尺寸、对比度、接线守卫。 */
public class MicButtonTest {

    @Test public void tapContinuesFromResultOrError() {
        assertEquals(MicButtonPolicy.Tap.CONTINUE, MicButtonPolicy.onTap(WakeStage.Stage.RESULT, false));
        assertEquals(MicButtonPolicy.Tap.CONTINUE, MicButtonPolicy.onTap(WakeStage.Stage.ERROR, true));
    }

    @Test public void tapWhileListeningStopsOrFinishes() {
        assertEquals(MicButtonPolicy.Tap.STOP, MicButtonPolicy.onTap(WakeStage.Stage.LISTENING, false));
        assertEquals(MicButtonPolicy.Tap.FINISH, MicButtonPolicy.onTap(WakeStage.Stage.LISTENING, true));
        assertTrue(MicButtonPolicy.active(WakeStage.Stage.LISTENING));
        assertFalse(MicButtonPolicy.active(WakeStage.Stage.RESULT));
        assertEquals("停止收音", MicButtonPolicy.description(true));
        assertEquals("续说", MicButtonPolicy.description(false));
    }

    @Test public void transitionalStagesIgnored() {
        assertEquals(MicButtonPolicy.Tap.NONE, MicButtonPolicy.onTap(WakeStage.Stage.THINKING, true));
        assertEquals(MicButtonPolicy.Tap.NONE, MicButtonPolicy.onTap(null, true));
    }

    @Test public void touchTargetAndContrast() {
        assertTrue(MicButtonPolicy.TOUCH_DP >= 44);
        int card = WakeCardPainter.FILL_TOP;
        int idleBg = MicButtonPolicy.over(CardIconButton.BG, card);
        assertTrue("空闲字形对卡片 ≥ 3:1（图形元素）", MicButtonPolicy.contrast(CardIconButton.GLYPH, idleBg) >= 3.0);
        assertTrue("收音中字形对苔绿底 ≥ 4.5:1", MicButtonPolicy.contrast(MicButtonPolicy.ACTIVE_GLYPH, MicButtonPolicy.ACTIVE_BG) >= 4.5);
        assertTrue("收音中苔绿底对卡片 ≥ 3:1", MicButtonPolicy.contrast(MicButtonPolicy.ACTIVE_BG, card) >= 3.0);
    }

    static String src(String rel) {
        try {
            java.io.File f = new java.io.File(System.getProperty("bixby.root", "."), rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test public void wiring() {
        String v = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(v.contains("CardIconButton.KIND_MIC"));
        assertTrue("续说 = 不清屏的继续聆听", v.contains("case CONTINUE: switchToVoiceMode(); mListener.onVoiceModeRequested(); break;"));
        assertTrue(v.contains("case STOP: mListener.onStopListening(); break;"));
        assertTrue(v.contains("mV2BtnMic.setActive(com.onesay.assistant.v2.ui.wake.MicButtonPolicy.active("));
        // 真机回归：新界面判断「有上一轮结果」看 mV2Body / 终稿 / 错误行，不能只看旧 mResultCard（否则停止收音会把卡片收掉）
        assertTrue(v.contains("if (mV2) return mV2Body != null || mV2Final || mV2ErrorLine != null;"));
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(s.contains("public void onStopListening()"));
        int i = s.indexOf("public void onVoiceModeRequested()");
        String body = s.substring(i, s.indexOf("public void onTextModeRequested()", i));
        assertTrue("续说走 requestContinueListening（不是新唤醒）", body.contains("WakeService.requestContinueListening()"));
        assertFalse(body.replaceAll("//[^\n]*", "").contains("requestWake("));
    }
}
