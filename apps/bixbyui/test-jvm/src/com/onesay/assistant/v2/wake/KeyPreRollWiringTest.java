package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * D1：WakeService 接线核对（WakeService 在 JVM 里起不来，按源码核对关键不变量）。
 * 时序 / 隐私 / 回声剔除本身由 KeyPreRollTest、ChimeEchoGateTest、LaneDOfflineWavTest 覆盖。
 */
public class KeyPreRollWiringTest {

    private static final String WS = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");

    private static String body(String startMarker, String endMarker) {
        int a = WS.indexOf(startMarker);
        assertTrue("找不到 " + startMarker, a >= 0);
        int b = WS.indexOf(endMarker, a + startMarker.length());
        assertTrue("找不到 " + endMarker, b > a);
        return WS.substring(a, b);
    }

    @Test public void armedOnlyFromArmCaptureWindowAndOnlyForKeySource() {
        String arm = body("private void armCaptureWindow(long now, long wantWindowMs) {", "private final Runnable mWindowExpiredRunnable");
        assertTrue("开窗时武装预录", arm.contains("armKeyPreRoll();"));
        assertTrue("先定好起点再武装（r2/sysux：起点来自 WakePromptPolicy 计划）", arm.indexOf("mCaptureFrom = now + mPromptPlan.captureFromMs;") > 0
                && arm.indexOf("mCaptureFrom = now + mPromptPlan.captureFromMs;") < arm.indexOf("armKeyPreRoll();"));
        String fn = body("private void armKeyPreRoll() {", "private void appendCaptureChunk(");
        assertTrue("r2/sysux：按下即听 —— 预录推广到所有唤醒来源，由本窗计划决定（开关仍生效）", fn.contains("plan == null || !plan.preRoll"));
        assertTrue("开关关 / 非按键 → 解除", fn.contains("mPreRoll.disarm();"));
        assertTrue("起点基准 = 唤醒时刻", fn.contains("mPreRoll.arm(mLastWakeAt, mCaptureFrom,"));
        assertEquals("只有一处武装", 1, count(WS, "mPreRoll.arm("));
    }

    @Test public void r14CaptureFromUnchanged() {
        // 预录不改 R14 的上传起点，也不改按键隐私起点 300 / 语音 450 / 1100
        assertTrue(WS.contains("private static final long WAKE_LOCAL_GUARD_MS = 300;"));
        assertTrue(WS.contains("public static final long CAPTURE_FROM_OFFSET_MS = 1100;"));
        String gate = body("private boolean tryCloudRecognize(String offlineCandidate, String reason) {", "final byte[] pcm = mPcmBuffer.snapshot();");
        assertTrue("R14 隐私硬闸门原样保留", gate.contains("if (mLastWakeAt > 0 && gap < WAKE_LOCAL_GUARD_MS) {"));
        assertTrue("D1 隐私硬闸门：预录起点不早于唤醒", gate.contains("mPreRollEmittedStartAt < mLastWakeAt"));
    }

    @Test public void micLoopOffersBeforeShieldAndNeverFeedsGateWithPreRoll() {
        String loop = body("private void micLoop() {", "\n    }\n\n");
        int offer = loop.indexOf("mPreRoll.offer(buf, read, now);");
        int shield = loop.indexOf("boolean isSuppressed = mIsPlayingAckSound");
        assertTrue(offer > 0 && shield > 0 && offer < shield);
        String pre = loop.substring(loop.indexOf("if (mPreRoll.armed()) {"), shield);
        assertFalse("预录块之后不 continue（非抑制期照旧喂能量门 / KWS，与 R14 一致）", pre.contains("continue;"));
        assertFalse("预录不喂能量门", pre.contains("mEnergyGate"));
        assertFalse("预录不读常听滚动缓冲（那里有按键之前的音频）", pre.contains("mWakeRollingBuf"));
        assertTrue("窗关了预录作废", pre.contains("now >= mCaptureUntil") && pre.contains("mPreRoll.disarm();"));
        // R14 两处收音都改走 appendCaptureChunk，不再有直接 append
        assertEquals(2, count(loop, "appendCaptureChunk(buf, read);"));
        assertEquals(0, count(loop, "mPcmBuffer.append(buf, read);"));
        assertFalse(WS.contains("mWakeRollingBuf.snapshot") && WS.contains("mPreRoll.offer(mWakeRollingBuf"));
    }

    @Test public void emitOnlyWithPrivacyRecheck() {
        String emit = body("private void emitKeyPreRoll(short[] ctx, int ctxLen) {", "\n    }\n");
        assertTrue("隐私复核：起点不早于唤醒 + 本窗计划允许", emit.contains("WakePromptPolicy.preRollPrivacyOk(f.startAt, mLastWakeAt, f.wakeAt"));
        assertTrue("接上的是剔过回声的 f.pcm", emit.contains("System.arraycopy(f.pcm, off, part, 0, n);"));
        assertTrue(emit.contains("feedRealtime(part, n);") && emit.contains("mPcmBuffer.append(part, n);"));
        String app = body("private void appendCaptureChunk(short[] buf, int read) {", "\n    }\n");
        assertTrue("先接预录再接本块", app.indexOf("emitKeyPreRoll(buf, read);") < app.indexOf("mPcmBuffer.append(buf, read);"));
    }

    @Test public void r14RulesUntouched() {
        // 流式最终结果 / 窗到点仍要求本地能量门至少 3 帧人声；提示音尾巴「嗯」仍当空 turn
        assertEquals(3, com.onesay.assistant.v2.asr.RealtimeAsrPolicy.EXPIRY_MIN_SPEECH_FRAMES);
        assertTrue(WS.contains("if (localFrames < com.onesay.assistant.v2.asr.RealtimeAsrPolicy.EXPIRY_MIN_SPEECH_FRAMES) {"));
        assertTrue(WS.contains("if (WakeWordStripper.isAckSoundUtterance(text)) return;"));
        assertTrue(WakeWordStripper.isAckSoundUtterance("嗯。"));
    }

    @Test public void privacyDocumentedInCode() {
        String kp = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/KeyPreRoll.java");
        assertTrue(kp.contains("预录只在按键之后开始，绝不往前挪"));
        assertTrue(WS.contains("预录只在按键之后开始，绝不往前挪"));
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
