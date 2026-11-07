package com.onesay.assistant.v2.r13;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.net.NetState;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.ui.wake.MorphGeometry;
import com.onesay.assistant.v2.ui.wake.StageWatchdog;
import com.onesay.assistant.v2.ui.wake.WakeCopy;
import com.onesay.assistant.v2.ui.wake.WakeStage;
import com.onesay.assistant.v2.wake.CaptureSessionPolicy;
import com.onesay.assistant.v2.wake.CaptureSessionPolicy.Expiry;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/**
 * R13 真机事故（2026-10-13：飞行模式下「没听清 → 正在想（卡住）→ 唤不醒」+ 卡顿发热）的回归测试。
 * 纯逻辑部分直接测；Android View 侧（光晕窗 / 小球 / 舞台）用源码守卫锁住修复点（JVM 里跑不了 View）。
 */
public class R13RobustnessTest {

    // ---------------------------------------------------------------- 会话模型
    /** 一次唤醒会话的最小模型：WakeStage（界面）+ CaptureSessionPolicy（WakeService 收窗）+ 服务层收尾（detach → RESET）。 */
    static final class Session {
        final WakeStage stage = new WakeStage();
        boolean gotCommand, closed, cloudFired, cloudInFlight;
        int cloudFires;
        final List<WakeStage.Stage> seen = new ArrayList<WakeStage.Stage>();

        void ev(WakeStage.Event e) {
            seen.add(stage.on(e).to);
        }

        void wake() {
            gotCommand = closed = cloudFired = cloudInFlight = false;
            ev(WakeStage.Event.WAKE);
            assertEquals(WakeStage.Stage.LISTENING, stage.stage());
        }

        /** 断句（说完了）。 */
        void endOfSpeech(boolean online) {
            if (!CaptureSessionPolicy.mayFireCloud(online)) {
                closed = true;
                cloudFired = true;
                ev(WakeStage.classifyBadge(WakeCopy.BADGE_OFFLINE));
                return;
            }
            cloudFired = true;
            cloudInFlight = true;
            cloudFires++;
            ev(WakeStage.classifyBadge("识别中"));   // setListeningPrompt("识别中…") → THINK
        }

        void cloudCallback(boolean ok) {
            cloudInFlight = CaptureSessionPolicy.cloudInFlightAfterCallback();
            if (CaptureSessionPolicy.closeOnCloudSettled(ok)) {
                closed = true;
                ev(WakeStage.classifyBadge("没听清"));
            } else {
                gotCommand = true;
                ev(WakeStage.Event.RESULT);
            }
        }

        /** 窗到点（8 s）。返回会不会把面板重新弄成「正在想」。 */
        Expiry windowExpired(boolean online) {
            Expiry ex = CaptureSessionPolicy.onWindowExpired(gotCommand, closed, cloudInFlight, online);
            if (ex == Expiry.CLOUD_PENDING) ev(WakeStage.classifyBadge("云端识别中"));
            if (ex == Expiry.OFFLINE) ev(WakeStage.classifyBadge(WakeCopy.BADGE_OFFLINE));
            if (ex == Expiry.TRY_CLOUD) cloudFires++;
            return ex;
        }

        /** 错误策略 2.5 s 收起 → DISMISS 动画 → detachFromWindowImmediate（RESET）。 */
        void autoHide() {
            ev(WakeStage.Event.DISMISS);
            ev(WakeStage.Event.RESET);
            assertEquals(WakeStage.Stage.IDLE, stage.stage());
        }
    }

    @Test public void offlineWakeThreeTimesInARowNeverSticks() {
        Session s = new Session();
        for (int i = 1; i <= 3; i++) {
            s.wake();
            s.endOfSpeech(false);
            assertEquals("第 " + i + " 次：没网立刻是错误态（没有网络）", WakeStage.Stage.ERROR, s.stage.stage());
            assertEquals(WakeCopy.OFFLINE_LINE, WakeCopy.errorLine(WakeCopy.BADGE_OFFLINE));
            s.autoHide();
            assertEquals("第 " + i + " 次：窗到点不再复活面板", Expiry.IGNORE, s.windowExpired(false));
            assertEquals(WakeStage.Stage.IDLE, s.stage.stage());
            assertFalse("离线从不进「正在想」", s.seen.contains(WakeStage.Stage.THINKING));
        }
        assertEquals("离线不发任何云端请求", 0, s.cloudFires);
    }

    /** 真机序列：云端失败「没听清」→ 2.5 s 收起 → 窗到点。旧实现在这里把面板弹回「正在想」且永不收。 */
    @Test public void cloudFailureThenWindowExpiryDoesNotResurrectThinking() {
        Session s = new Session();
        s.wake();
        s.endOfSpeech(true);
        assertEquals(WakeStage.Stage.THINKING, s.stage.stage());
        s.cloudCallback(false);
        assertEquals(WakeStage.Stage.ERROR, s.stage.stage());
        s.autoHide();
        assertEquals(Expiry.IGNORE, s.windowExpired(true));
        assertEquals(WakeStage.Stage.IDLE, s.stage.stage());
        // 旧判据（发过 && 没拿到命令）在这里为真 = 误判在途；新判据只认 cloudInFlight
        boolean oldInFlight = s.cloudFired && !s.gotCommand;
        assertTrue(oldInFlight);
        assertNotEquals(Expiry.CLOUD_PENDING, CaptureSessionPolicy.onWindowExpired(false, false, s.cloudInFlight, true));
    }

    /** error → retry → retry（有网）：每一轮都从 IDLE 干净地进入 LISTENING，第二轮即使卡在 THINKING，第三轮唤醒也能拉起。 */
    @Test public void errorRetryRetrySessionsAlwaysWake() {
        Session s = new Session();
        s.wake();
        s.endOfSpeech(true);
        s.cloudCallback(false);
        s.autoHide();
        s.wake();
        s.endOfSpeech(true);
        assertEquals(WakeStage.Stage.THINKING, s.stage.stage());   // 第二轮「正在想」（云端在途）
        WakeStage.Transition t = s.stage.on(WakeStage.Event.WAKE);  // 第三轮唤醒直接打断
        assertEquals(WakeStage.Stage.LISTENING, t.to);
        assertTrue(t.has(WakeStage.Effect.GLOW_IGNITE));
        s.cloudCallback(true);
        assertEquals(WakeStage.Stage.RESULT, s.stage.stage());
        assertEquals("一窗最多一次云端", 2, s.cloudFires);
    }

    @Test public void noRetryStormOneCloudCallPerCapture() {
        // 在途时窗到点：只切文案，不再发第二次
        assertEquals(Expiry.CLOUD_PENDING, CaptureSessionPolicy.onWindowExpired(false, false, true, true));
        // 已拿到命令 / 已收尾：什么都不做
        assertEquals(Expiry.IGNORE, CaptureSessionPolicy.onWindowExpired(true, false, false, true));
        assertEquals(Expiry.IGNORE, CaptureSessionPolicy.onWindowExpired(false, true, false, true));
        assertEquals(Expiry.OFFLINE, CaptureSessionPolicy.onWindowExpired(false, false, false, false));
        assertEquals(Expiry.TRY_CLOUD, CaptureSessionPolicy.onWindowExpired(false, false, false, true));
    }

    @Test public void asrTimeoutsAreBounded() {
        assertEquals(6000, CaptureSessionPolicy.clampAsrTimeoutMs(0));
        assertEquals(6000, CaptureSessionPolicy.clampAsrTimeoutMs(-5));
        assertEquals(1000, CaptureSessionPolicy.clampAsrTimeoutMs(200));
        assertEquals(15000, CaptureSessionPolicy.clampAsrTimeoutMs(600000));
        assertEquals(8000, CaptureSessionPolicy.clampAsrTimeoutMs(8000));
    }

    @Test public void netStateDecision() {
        assertTrue("探测失败按有网（不误挡）", NetState.decide(null, false, false, false));
        assertFalse(NetState.decide(null, true, false, false));
        assertFalse(NetState.decide(null, true, true, false));
        assertTrue(NetState.decide(null, true, true, true));
        assertFalse(NetState.decide(Boolean.FALSE, true, true, true));
    }

    // ---------------------------------------------------------------- 看门狗
    @Test public void everyTransientStageHasABoundedStay() {
        for (WakeStage.Stage st : new WakeStage.Stage[] {WakeStage.Stage.LISTENING, WakeStage.Stage.THINKING,
                WakeStage.Stage.ANSWERING, WakeStage.Stage.ERROR, WakeStage.Stage.DISMISSING, WakeStage.Stage.HANDOFF}) {
            assertTrue(st + " 有上限", StageWatchdog.watched(st));
            long dl = StageWatchdog.deadlineMs(st, 0L, 0L);
            assertTrue(st + " 上限 ≤ 40 s", dl > 0 && dl <= 40000L);
            assertEquals(StageWatchdog.Action.NONE, StageWatchdog.verdict(st, dl - 1, 0L, 0L));
            assertNotEquals(StageWatchdog.Action.NONE, StageWatchdog.verdict(st, dl, 0L, 0L));
        }
        assertEquals(StageWatchdog.Action.TIMEOUT_ERROR, StageWatchdog.verdict(WakeStage.Stage.THINKING, 50000L, 0L, 0L));
        assertEquals(StageWatchdog.Action.FORCE_DETACH, StageWatchdog.verdict(WakeStage.Stage.ERROR, 9000L, 0L, 0L));
        // 合法久留的状态不看门
        for (WakeStage.Stage st : new WakeStage.Stage[] {WakeStage.Stage.IDLE, WakeStage.Stage.RESULT, WakeStage.Stage.CONFIRM,
                WakeStage.Stage.TASK, WakeStage.Stage.TEXT, WakeStage.Stage.ISLAND}) {
            assertFalse(st + " 不看门", StageWatchdog.watched(st));
        }
        // 流式增量会续命（按最后一次事件算）
        assertEquals(StageWatchdog.Action.NONE, StageWatchdog.verdict(WakeStage.Stage.ANSWERING, 50000L, 0L, 45000L));
        // 聆听按进入算（转写刷新不续命）
        assertEquals(StageWatchdog.Action.TIMEOUT_ERROR, StageWatchdog.verdict(WakeStage.Stage.LISTENING, 27000L, 0L, 26999L));
    }

    @Test public void offlineAndTimeoutBadgesAreErrors() {
        assertEquals(WakeStage.Event.ERROR, WakeStage.classifyBadge(WakeCopy.BADGE_OFFLINE));
        assertEquals(WakeStage.Event.ERROR, WakeStage.classifyBadge(WakeCopy.BADGE_TIMEOUT));
        assertEquals(WakeCopy.TIMEOUT_LINE, WakeCopy.errorLine(WakeCopy.BADGE_TIMEOUT));
        assertEquals(WakeCopy.NO_SPEECH_LINE, WakeCopy.errorLine("没听清"));
    }

    @Test public void morphStageHasAHardTimeCap() {
        // 岛目标在卡片下面（永远「接触」不到的几何）：舞台也必须在 MAX_STAGE_S 内结束（WakeGlowView 用同一常量收尾）
        MorphGeometry m = MorphGeometry.toIsland(2.625f, new float[] {40, 1500, 1040, 2000}, 90f, 540f, 3000f, 70f, 300f);
        int frames = 0;
        while (!(m.contact() || m.elapsed() > MorphGeometry.MAX_STAGE_S)) {
            m.step(0.016f);
            assertTrue("舞台帧数有界", ++frames < 200);
        }
    }

    // ---------------------------------------------------------------- 危险开关 / 误路由
    static Capability cap(String sign) {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        Capability c = reg.describe(sign);
        assertNotNull(sign, c);
        return c;
    }

    @Test public void connectivityKillersRequireConfirm() throws Exception {
        for (String sign : new String[] {"connectivity.airplane.on", "connectivity.wifi.off", "connectivity.data.off", "sms.send"}) {
            Capability c = cap(sign);
            JSONObject args = "sms.send".equals(sign) ? new JSONObject().put("number", "10086").put("text", "你好") : new JSONObject();
            assertEquals(sign + " 必须弹确认卡", ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(c, args, false).decision);
        }
        assertEquals("飞行模式是红卡（不给始终允许）", ConsentPolicy.TIER_RED, ConsentPolicy.tierOf(cap("connectivity.airplane.on"), false));
        // 恢复联网的反向开关不加卡
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(cap("connectivity.airplane.off"), new JSONObject(), false).decision);
        assertEquals(ToolGate.Decision.ALLOW, ToolGate.check(cap("connectivity.wifi.on"), new JSONObject(), false).decision);
    }

    @Test public void misroutesFromDevice() {
        assertEquals(TriageDecider.Label.DIAGNOSE, TriageDecider.decide("屏幕怎么这么暗", TriageDecider.Signals.offline(), true).label);
        assertEquals(TriageDecider.Label.DO_ACT, TriageDecider.decide("给10086发短信说你好", TriageDecider.Signals.offline(), true).label);
        assertEquals(TriageDecider.Label.DO_ACT, TriageDecider.decide("给妈妈发短信说谢谢", TriageDecider.Signals.offline(), true).label);
        // 不误伤：真正的打招呼 / 知识问句
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("你好", TriageDecider.Signals.offline(), true).label);
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("怎么设置闹钟", TriageDecider.Signals.offline(), true).label);
    }

    // ---------------------------------------------------------------- 源码守卫（Android View 侧的修复点）
    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/" + rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    @Test public void noAudioRecordOpenedByWakeUi() throws Exception {
        for (String f : new String[] {"ui/wake/VoiceLevel.java", "ui/wake/WakeGlowView.java", "ui/wake/OrbView.java",
                "ui/FloatingSheetView.java"}) {
            String s = src(f);
            assertFalse(f + " 不得自开麦克风", s.contains("AudioRecord") || s.contains("MediaRecorder") || s.contains("SpeechRecognizer"));
        }
        String eg = src("wake/EnergyGate.java");
        assertTrue("EnergyGate 只写 volatile 出口", eg.contains("VoiceLevel.publish(rms, mNoiseFloor, now)"));
    }

    @Test public void animatorsStopOnHideOrDetach() throws Exception {
        String g = src("ui/wake/WakeGlowView.java");
        assertTrue("光晕：摘窗即停帧", g.contains("onDetachedFromWindow") && g.contains("removeCallbacks(mInvalidateNext)"));
        assertTrue("光晕：窗口不可见不续帧", g.contains("getWindowVisibility() != VISIBLE"));
        assertTrue("舞台：去程也有时间上限", g.contains("m.contact() || m.elapsed() > MorphGeometry.MAX_STAGE_S"));
        String o = src("ui/wake/OrbView.java");
        assertTrue("小球：摘窗即停帧", o.contains("removeCallbacks(mNext)"));
        assertTrue("小球：不可见不续帧", o.contains("getWindowVisibility() == VISIBLE"));
    }

    @Test public void stageWindowRemovedAfterDismiss() throws Exception {
        String s = src("ui/FloatingSheetService.java");
        int i = s.indexOf("private synchronized void detachFromWindowImmediate()");
        int j = s.indexOf("private synchronized void showSheet()");
        assertTrue(i > 0 && j > i);
        assertTrue("收起时摘光晕窗", s.substring(i, j).contains("detachGlow()"));
        assertTrue("光晕窗复查有上限", s.contains("GLOW_DETACH_MAX_TRIES"));
        assertTrue("状态看门狗已接入", s.contains("armStageWatchdog(t);"));
        assertTrue("识别中提示不复活已收起的面板", s.contains("忽略识别中提示（不复活面板）"));
    }

    @Test public void wakeServiceNoLongerMisjudgesInFlight() throws Exception {
        String s = src("wake/WakeService.java");
        assertFalse("旧的在途误判式已移除", s.contains("boolean cloudInFlight = mCloudFiredForThisCapture && !mGotCommand;"));
        assertTrue(s.contains("closeCaptureAfterCloud(\"云端失败\")"));
        assertTrue(s.contains("closeCaptureAfterCloud(\"云端空结果\")"));
        assertTrue(s.contains("endCaptureOffline(\"能量门断句\")"));
        assertTrue(s.contains("endCaptureOffline(\"指令窗超时\")"));
    }
}
