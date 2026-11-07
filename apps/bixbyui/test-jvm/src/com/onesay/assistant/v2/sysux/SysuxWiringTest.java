package com.onesay.assistant.v2.sysux;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ r2/sysux：接线守卫（源码扫描）。 */
public class SysuxWiringTest {

    private static final String WS = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
    private static final String FS = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
    private static final String FV = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
    private static final String TTS = src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
    private static final String SRH = src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
    private static final String MF = src("app/AndroidManifest.xml");
    private static final String TU = src("app/src/com/onesay/assistant/v2/wake/WakeTuning.java");

    static String src(String rel) {
        try {
            java.io.File f = new java.io.File(System.getProperty("bixby.root", "."), rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String body(String src, String sig) {
        int i = src.indexOf(sig);
        assertTrue("找不到 " + sig, i >= 0);
        int depth = 0;
        int j = src.indexOf('{', i);
        for (int k = j; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(i, k + 1);
        }
        throw new AssertionError("括号不配对: " + sig);
    }

    @Test public void voiceCommandKeepsFocus() {
        String h = body(WS, "private void handleVoiceCommand(final String command)");
        assertFalse("拿到指令不再立刻归还焦点", h.contains("abandonAudioFocus()"));
        assertTrue(h.contains("focusCommand()"));
        assertTrue(h.contains("sPendingVoiceCommand = command"));
        assertTrue(h.contains("ConversationTurnPolicy.isDismissPhrase(command)"));
        assertFalse("不再有 MAY_DUCK + null listener 的旧收音焦点", WS.contains("requestAudioFocus(null"));
        assertTrue(WS.contains("AUDIOFOCUS_GAIN_TRANSIENT"));
        String arm = body(WS, "private void armCaptureWindow(long now, long wantWindowMs)");
        assertTrue(arm.contains("mPromptPlan = planFor(mLastWakeSource)"));
        assertTrue(arm.contains("focusListen()"));
    }

    @Test public void preRollGeneralizedButPrivacyChecked() {
        String a = body(WS, "private void armKeyPreRoll()");
        assertTrue(a.contains("plan.preRoll"));
        String e = body(WS, "private void emitKeyPreRoll(short[] ctx, int ctxLen)");
        assertTrue(e.contains("WakePromptPolicy.preRollPrivacyOk(f.startAt, mLastWakeAt, f.wakeAt"));
    }

    @Test public void followUpAndBargeInWired() {
        assertTrue(WS.contains("requestFollowUpInternal(\"followup-tts\")"));
        assertTrue(WS.contains("requestFollowUpInternal(\"followup-bargein\")"));
        String loop = body(WS, "private void micLoop()");
        assertTrue(loop.contains("feedBargeIn(buf, read, now)"));
        String miss = body(WS, "private void notifyCaptureMiss(FloatingSheetService fs, int gen)");
        assertTrue("续听窗没人说：安静收起，不报没听清", miss.indexOf("mFollowUpActive") < miss.indexOf("showTerminalPrompt"));
        assertTrue(WS.contains("TtsSpeaker.setSpeechListener("));
    }

    @Test public void ttsMinimalInterface() {
        assertTrue(TTS.contains("public interface SpeechListener"));
        assertTrue(TTS.contains("setOnUtteranceProgressListener"));
        assertTrue(TTS.contains("ConversationTurnPolicy.shouldSpeak(force, sVoiceTurn"));
        assertTrue(FS.contains("TtsSpeaker.setVoiceTurn(voiceTurn)"));
        assertTrue(FS.contains("WakeService.claimVoiceTurn(command)"));
    }

    @Test public void backHomeRecents() {
        assertTrue(FS.contains("Intent.ACTION_CLOSE_SYSTEM_DIALOGS"));
        assertTrue(FS.contains("PanelSystemKeyPolicy.collapseOnCloseSystemDialogs(reason)"));
        assertTrue(FS.contains("unregisterReceiver(mSysDialogsReceiver)"));
        String mk = body(FS, "private void makeSheetFocusableForResult(final int attempt)");
        assertTrue("识屏完成后才可获焦", mk.contains("ScreenContextCapture.wakeCaptureBusy()") && mk.contains("ScreenContextCapture.inFlight()"));
        assertTrue(FV.contains("FloatingSheetService.onResultShown("));
        assertTrue(SRH.contains("setBusy(true)"));
        assertTrue(FS.contains("WakeService.onPanelGone(\"进岛\")"));
        assertTrue(FS.contains("WakeService.onPanelGone(\"离屏\")"));
    }

    @Test public void tuningMigrationWritesInstantOnce() {
        String m = body(TU, "public void migrateSysux()");
        assertTrue(m.contains("putInt(K_SHORT_CHIME, 1)"));
        assertTrue(m.contains("K_SYSUX_CHIME_MIGRATED"));
        int r14 = WS.indexOf("mTuning.migrateR14()");
        int sx = WS.indexOf("mTuning.migrateSysux()");
        assertTrue("R14.1 写 0 之后再迁成 1", r14 > 0 && sx > r14);
    }
}
