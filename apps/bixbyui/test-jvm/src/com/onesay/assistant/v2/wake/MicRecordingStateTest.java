package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ r2/sysux R1：PARTIAL_WAKE_LOCK 只在 AudioRecord 真正处于录制状态时持有。 */
public class MicRecordingStateTest {

    private static final String WS = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");

    /** 息屏、没暂停时的最终判定。 */
    private static boolean lock(MicRecordingState m) {
        return ScreenOffPolicy.wantPartialWakeLock(false, false, m.recording());
    }

    @Test public void initiallyNotRecordingNoLock() {
        MicRecordingState m = new MicRecordingState();
        assertFalse(m.recording());
        assertFalse("还没开始录音不持锁", lock(m));
    }

    @Test public void initFailedNeverHoldsLock() {
        MicRecordingState m = new MicRecordingState();
        m.onInitFailed("STATE_UNINITIALIZED");
        assertFalse(m.recording());
        assertFalse("初始化失败（没授权 / 别人占麦）息屏也不持锁", lock(m));
        assertTrue(m.reason().contains("初始化失败"));
    }

    @Test public void startNotConfirmedNoLock() {
        MicRecordingState m = new MicRecordingState();
        assertFalse("startRecording 没进入录制态 → 不变（仍不在录）", m.onStartResult(false, "boot"));
        assertFalse(lock(m));
    }

    @Test public void startConfirmedHoldsLockOnlyWhenScreenOffAndNotPaused() {
        MicRecordingState m = new MicRecordingState();
        assertTrue(m.onStartResult(true, "boot"));
        assertTrue(lock(m));
        assertFalse("亮屏不需要", ScreenOffPolicy.wantPartialWakeLock(false, true, m.recording()));
        assertFalse("暂停不持有", ScreenOffPolicy.wantPartialWakeLock(true, false, m.recording()));
    }

    @Test public void pauseReleasesResumeSuccessReacquires() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        assertTrue(m.onStopped("暂停"));
        assertFalse("暂停后（即便策略上没暂停标记）麦克风停了也不持锁", lock(m));
        assertTrue(m.onStartResult(true, "resume"));
        assertTrue("恢复录音成功后再获取", lock(m));
    }

    @Test public void resumeFailedStaysReleased() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        m.onStopped("暂停");
        assertFalse(m.onStartResult(false, "resume"));
        assertFalse("恢复失败（10 次重试都没进录制态）不持锁", lock(m));
    }

    @Test public void sustainedReadFailuresReleaseThenRecover() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        for (int i = 0; i < MicRecordingState.READ_FAIL_LIMIT - 1; i++) {
            assertFalse("偶发读失败不抖动", m.onRead(-3, true));
        }
        assertTrue(lock(m));
        assertTrue(m.onRead(-3, true));
        assertFalse("连续读失败判定不在录", lock(m));
        assertTrue(m.onRead(320, true));
        assertTrue("读成功且底层在录 → 恢复持锁", lock(m));
    }

    @Test public void readSuccessResetsFailCounter() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        for (int i = 0; i < MicRecordingState.READ_FAIL_LIMIT - 1; i++) m.onRead(0, true);
        m.onRead(320, true);
        for (int i = 0; i < MicRecordingState.READ_FAIL_LIMIT - 1; i++) m.onRead(0, true);
        assertTrue(m.recording());
    }

    @Test public void underlyingStateNotRecordingReleasesImmediately() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        assertTrue(m.onRead(-3, false));
        assertFalse(lock(m));
        assertFalse("底层不在录时读到数据也不算恢复", m.onRead(320, false));
        assertFalse(lock(m));
    }

    @Test public void loopExitReleases() {
        MicRecordingState m = new MicRecordingState();
        m.onStartResult(true, "boot");
        assertTrue(m.onStopped("读循环退出"));
        assertFalse(lock(m));
    }

    @Test public void wakeServiceWiring() {
        assertTrue(WS.contains("mMicState.onInitFailed("));
        assertTrue(WS.contains("mMicState.onStartResult(recOk, \"boot\")"));
        assertTrue(WS.contains("mMicState.onStopped(\"暂停\")"));
        assertTrue(WS.contains("mMicState.onStartResult(ok, \"resume\")"));
        assertTrue(WS.contains("mMicState.onRead(read, stRec)"));
        assertTrue(WS.contains("wantPartialWakeLock(mPausedCached, screenOn, mMicState.recording())"));
        // 暂停时先标记不在录再刷新锁
        int stop = WS.indexOf("mMicState.onStopped(\"暂停\")");
        int upd = WS.indexOf("updateWakeLock(\"mic-paused\")");
        assertTrue(stop > 0 && stop < upd);
    }
}
