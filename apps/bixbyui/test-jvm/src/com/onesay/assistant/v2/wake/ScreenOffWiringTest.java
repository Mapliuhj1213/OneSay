package com.onesay.assistant.v2.wake;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ExecutionGateLockedProbe;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ r2/screenoff-wake：接线守卫（源码扫描）+ ExecutionGate 锁屏拒绝。 */
public class ScreenOffWiringTest {

    private static final String WS = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
    private static final String FS = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
    private static final String MF = KeyPreRollTest.src("app/AndroidManifest.xml");

    @After public void clearProbe() {
        ScreenOffPolicy.setKeyguardProbe(null);
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

    @Test public void wakeLockNoLongerAcquiredForeverInOnCreate() {
        String onCreate = body(WS, "public void onCreate()");
        assertFalse("onCreate 不再无条件 acquireWakeLock()", onCreate.contains("acquireWakeLock()"));
        assertTrue(onCreate.contains("initScreenState()"));
        assertTrue(onCreate.contains("updateWakeLock(\"onCreate\")"));
        assertFalse("旧的永不释放实现已删除", WS.contains("private void acquireWakeLock()"));
        String upd = body(WS, "private void updateWakeLock(String why)");
        assertTrue(upd.contains("ScreenOffPolicy.wantPartialWakeLock(mPausedCached, screenOn, mMicState.recording())"));
        assertTrue(upd.contains("setReferenceCounted(false)"));
        assertTrue(upd.contains("\"bixbyui:wakelock\""));
    }

    @Test public void screenBroadcastsRegisteredDynamicallyAndUnregistered() {
        String init = body(WS, "private void initScreenState()");
        assertTrue(init.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(init.contains("Intent.ACTION_SCREEN_ON"));
        assertTrue(init.contains("Intent.ACTION_USER_PRESENT"));
        assertTrue(init.contains("registerReceiver(mScreenReceiver"));
        assertTrue(init.contains("setKeyguardProbe("));
        String destroy = body(WS, "public void onDestroy()");
        assertTrue(destroy.contains("unregisterReceiver(mScreenReceiver)"));
        assertTrue(destroy.contains("unregisterOnSharedPreferenceChangeListener(mPauseListener)"));
        assertTrue(destroy.contains("setKeyguardProbe(null)"));
        assertFalse("Manifest 里不能静态注册 SCREEN_OFF（Android 8 收不到，且不该）", MF.contains("android.intent.action.SCREEN_OFF"));
    }

    @Test public void micLoopPausesRecordingAndGatesKws() {
        String loop = body(WS, "private void micLoop()");
        int pause = loop.indexOf("ScreenOffPolicy.micShouldPause(");
        int read = loop.indexOf("mAudioRecord.read(");
        assertTrue("暂停判定在 read 之前", pause > 0 && pause < read);
        assertTrue(loop.contains("pauseMicUntilResumed()"));
        assertTrue(loop.contains("mScreenPolicy.onFrame(now, buf, read)"));
        assertTrue(loop.contains("replayPrerollIntoKws(buf, read, now)"));
        assertTrue(loop.contains("feedKwsChunk(buf, read, now)"));
        assertFalse("KWS 喂入只剩 feedKwsChunk 一处", loop.contains("mKwsStream.acceptWaveform"));
        // 门在 KWS 会话暂停判定之内：唤醒会话期间仍完全不跑（R14.1 行为不变）
        int kwsPausedIf = loop.indexOf("if (!kwsPaused && mKws != null && mKwsStream != null)");
        assertTrue(kwsPausedIf > 0 && kwsPausedIf < loop.indexOf("mScreenPolicy.onFrame"));
        String pm = body(WS, "private boolean pauseMicUntilResumed()");
        assertTrue(pm.contains(".stop()"));
        assertTrue(pm.contains("updateWakeLock(\"mic-paused\")"));
        assertTrue(pm.contains("startRecording()"));
        assertTrue(pm.contains("updateWakeLock(\"mic-resumed\")"));
        assertTrue("恢复后旧音频不进预滚", pm.contains("mWakeRollingBuf.clear()"));
    }

    @Test public void prerollReplayResetsStreamFirst() {
        String r = body(WS, "private void replayPrerollIntoKws(");
        assertTrue(r.indexOf("mKws.reset(mKwsStream)") < r.indexOf("snapshotLast("));
        assertTrue(r.contains("if (feedKwsChunk(chunk, n, now)) break;"));
        String f = body(WS, "private boolean feedKwsChunk(");
        assertTrue(f.contains("mKwsStream.acceptWaveform(fBuf, 16000)"));
        assertTrue(f.contains("hit = true;"));
    }

    @Test public void enrollmentAndConsoleWakeThePauseWaiter() {
        assertTrue(body(WS, "private boolean beginVpRecording()").contains("wakePauseWaiter()"));
        assertTrue(body(WS, "public static void refreshPauseNotification()").contains("onPauseChanged(\"console\")"));
    }

    @Test public void acceptedWakeTurnsScreenOnBeforeShowingPanel() {
        String rw = body(WS, "private synchronized void requestWakeInternal(String source, boolean conversation)");
        int paused = rw.indexOf("pausedBlocks(source)");
        int wake = rw.indexOf("wakeScreenForWake(source)");
        int show = rw.lastIndexOf("mMainHandler.post(mShowListeningRunnable)");
        assertTrue("暂停 / 简报闸门之后才亮屏", paused > 0 && wake > paused);
        assertTrue("先亮屏再出面板", wake < show);
        String w = body(WS, "private void wakeScreenForWake(String source)");
        assertTrue(w.contains("PowerManager.SCREEN_BRIGHT_WAKE_LOCK"));
        assertTrue(w.contains("PowerManager.ACQUIRE_CAUSES_WAKEUP"));
        assertTrue("亮屏锁必须带超时", w.contains("mScreenWakeLock.acquire(cfg.screenWakeMs)"));
        assertTrue(w.contains("KeyguardHostActivity.show("));
        assertFalse("纯 App 侧：不经 exec 守护", w.contains("ExecProtocol") || w.contains("keyevent"));
    }

    @Test public void panelWindowsShowWhenLockedAndHostIsOpaqueAndPrivate() {
        assertTrue(body(FS, "private void addSheetWindow(String why)").contains("FLAG_SHOW_WHEN_LOCKED"));
        assertTrue(FS.contains("mRippleParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED"));
        assertTrue(body(FS, "private void removeSheetWindow(String why)").contains("KeyguardHostActivity.finishIfShowing(\"panel-removed\")"));
        int a = MF.indexOf("com.onesay.assistant.v2.ui.KeyguardHostActivity");
        assertTrue(a > 0);
        String decl = MF.substring(a, MF.indexOf("/>", a));
        assertTrue("托底不导出", decl.contains("android:exported=\"false\""));
        assertFalse("托底必须不透明（透明窗不会让锁屏进入被遮挡态，还会露出下面的 App）", decl.contains("Translucent"));
        String host = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/ui/KeyguardHostActivity.java");
        assertTrue(host.contains("FLAG_SHOW_WHEN_LOCKED"));
        assertFalse("托底绝不解除锁屏", host.contains("FLAG_DISMISS_KEYGUARD") || host.contains("requestDismissKeyguard"));
        assertTrue(host.contains("Color.BLACK"));
    }

    @Test public void executionGateDeniesSideEffectsWhileLocked() throws Exception {
        Capability torch = new Capability.Builder("t.torch", "手电").risk(0, false).sideEffect(SideEffect.DEVICE_CONTROL).cmd("x").build();
        Capability send = new Capability.Builder("t.send", "发信").risk(2, true).sideEffect(SideEffect.PUBLIC_COMMS).cmd("x").build();
        Capability ask = new Capability.Builder("t.ask", "问答").risk(0, false).cmd("x").build();
        // 未锁：与改动前一致
        assertFalse(ExecutionGateLockedProbe.evaluateDenied(torch));
        assertNull(ExecutionGateLockedProbe.authorizeLockedDetail(torch));
        ScreenOffPolicy.setKeyguardProbe(new ScreenOffPolicy.KeyguardProbe() {
            @Override public boolean locked() { return true; }
        });
        assertTrue("锁着改设置要先解锁", ExecutionGateLockedProbe.evaluateDenied(torch));
        assertTrue("锁着发信要先解锁（排在始终允许之前）", ExecutionGateLockedProbe.evaluateDenied(send));
        assertEquals("KeyguardLocked", ExecutionGateLockedProbe.authorizeLockedDetail(send));
        assertEquals("KeyguardLocked", ExecutionGateLockedProbe.legacyLockedDetail(torch));
        assertFalse("无副作用照常放行", ExecutionGateLockedProbe.evaluateDenied(ask));
        assertNull(ExecutionGateLockedProbe.authorizeLockedDetail(ask));
    }
}
