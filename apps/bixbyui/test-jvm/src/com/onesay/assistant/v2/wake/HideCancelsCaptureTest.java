package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ACTION_HIDE 收起浮层要作废当前采集会话，上一轮的「没听清」不能迟到落到下一张卡上。
 */
public class HideCancelsCaptureTest {

    @Test public void cancel_on_hide_table() {
        // gotCommand, closed, windowOpen, cloudInFlight
        assertTrue("唤醒后立刻 HIDE：窗还开着", CaptureSessionPolicy.cancelOnHide(false, false, true, false));
        assertTrue("窗已到点、云端还在路上", CaptureSessionPolicy.cancelOnHide(false, false, false, true));
        assertFalse("已拿到指令（随口记 / 长按 / 指令执行中）", CaptureSessionPolicy.cancelOnHide(true, false, true, false));
        assertFalse("本窗已收尾", CaptureSessionPolicy.cancelOnHide(false, true, true, true));
        assertFalse("没有窗、没有在途请求：什么都不动", CaptureSessionPolicy.cancelOnHide(false, false, false, false));
    }

    @Test public void stale_by_session_id() {
        assertFalse(CaptureSessionPolicy.isStale(7, 7));
        assertTrue(CaptureSessionPolicy.isStale(7, 8));
        assertTrue("从未开过窗（-1）", CaptureSessionPolicy.isStale(-1, 0));
    }

    static String src(String rel) throws Exception {
        java.io.File dir = com.onesay.assistant.v2.testutil.TestRoot.start(HideCancelsCaptureTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            java.io.File f = new java.io.File(dir, "app/src/" + rel);
            if (f.exists()) return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        throw new java.io.FileNotFoundException(rel);
    }

    @Test public void wiring_hide_cancels_before_detach() throws Exception {
        String s = src("com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int hide = s.indexOf("} else if (ACTION_HIDE.equals(action)) {");
        int cancel = s.indexOf("WakeService.cancelCaptureFromUi(\"HIDE\");", hide);
        int detach = s.indexOf("hideSheet(true);", hide);
        assertTrue(hide > 0 && cancel > hide && detach > cancel);
    }

    @Test public void wiring_late_results_dropped_by_session_id() throws Exception {
        String w = src("com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(w.contains("CaptureSessionPolicy.cancelOnHide(s.mGotCommand, s.mCaptureClosed, open, s.mCloudInFlight)"));
        // 窗到点回调带会话号
        assertTrue(w.contains("mWindowGen = mCaptureGen;"));
        assertTrue(w.contains("if (com.onesay.assistant.v2.wake.CaptureSessionPolicy.isStale(mWindowGen, mCaptureGen)) {"));
        // 「没听清」带会话号；云端回调用发出时的代际快照
        assertTrue(w.contains("private void notifyCaptureMiss(FloatingSheetService fs, int gen) {"));
        assertTrue(w.contains("if (com.onesay.assistant.v2.wake.CaptureSessionPolicy.isStale(gen, mCaptureGen)) {"));
        assertTrue(w.contains("notifyCaptureMiss(fs, myGen);"));
        assertTrue(w.contains("notifyCaptureMiss(FloatingSheetService.getInstance(), myGen);"));
        // 作废时代际号 +1、撤掉窗到点
        int c = w.indexOf("private static void cancelCaptureFromUiInternal(String why)");
        assertTrue(c > 0);
        assertTrue(w.indexOf("s.mCaptureGen++;", c) > c);
        assertTrue(w.indexOf("s.mMainHandler.removeCallbacks(s.mWindowExpiredRunnable);", c) > c);
    }
}
