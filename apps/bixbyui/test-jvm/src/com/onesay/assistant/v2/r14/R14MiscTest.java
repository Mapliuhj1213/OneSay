package com.onesay.assistant.v2.r14;

import com.onesay.assistant.v2.ui.wake.WakeStage;
import com.onesay.assistant.v2.wake.CaptureSessionPolicy;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;


import static org.junit.Assert.*;

/** R14 #3/#4/#5 及其它：纯函数 + 源码护栏。 */
public class R14MiscTest {

    // ------------------------------------------------------------ 隐私起点 / 缓冲上限 / 岛

    @Test
    public void captureFrom_keyWakeAtGuard_voiceWakeUnchanged() {
        assertEquals("按键唤醒：硬门限 300 ms（提示音期间开口也收）", 300L, CaptureSessionPolicy.captureFromOffsetMs(true, true, 300, 450, 1100));
        assertEquals(300L, CaptureSessionPolicy.captureFromOffsetMs(true, false, 300, 450, 1100));
        assertEquals("r2/sysux 按下即听：语音唤醒短提示音也从硬门限起（之前由预录补）", 300L, CaptureSessionPolicy.captureFromOffsetMs(false, true, 300, 450, 1100));
        assertEquals("语音唤醒长应答音照旧", 1100L, CaptureSessionPolicy.captureFromOffsetMs(false, false, 300, 450, 1100));
        assertTrue("永远不低于硬门限", CaptureSessionPolicy.captureFromOffsetMs(false, true, 300, 100, 1100) >= 300);
    }

    @Test
    public void bufferCapAlignedWithCaptureCap() {
        assertEquals("r2/input：整段上限 20 → 30 s", 30, com.onesay.assistant.v2.asr.PcmWindowBuffer.CAPTURE_MAX_SECONDS);
        assertEquals(com.onesay.assistant.v2.wake.WakeService.CAPTURE_MAX_MS, com.onesay.assistant.v2.asr.PcmWindowBuffer.CAPTURE_MAX_SECONDS * 1000L);
        assertEquals("声纹录制上限不变", 12, com.onesay.assistant.v2.asr.PcmWindowBuffer.MAX_SECONDS);
    }

    @Test
    public void shortTasksDoNotMorphToIsland() {
        assertEquals(6000L, com.onesay.assistant.v2.island.IslandModel.AUTO_COLLAPSE_MS);
    }

    @Test
    public void r14Defaults_originalChimeAndAdaptiveEndpoint() {
        assertEquals("r2/sysux（用户 10-29 拍板）：默认按下即听（R14.1 曾为 0）", 1, com.onesay.assistant.v2.wake.WakeTuning.DEF_SHORT_CHIME);
        assertEquals(1, com.onesay.assistant.v2.wake.WakeTuning.DEF_ADAPTIVE_EP);
        assertEquals(450L, com.onesay.assistant.v2.wake.WakeTuning.SHORT_CHIME_CAPTURE_FROM_MS);
    }

    @Test
    public void r141_migrationResetsShortChimeOnce_andNoLongerForcesIt() throws Exception {
        String wt = src("app/src/com/onesay/assistant/v2/wake/WakeTuning.java");
        int a = wt.indexOf("public void migrateR14()");
        String body = wt.substring(a, wt.indexOf("public void setFlag(", a));
        assertFalse("R14 迁移不再写 short_chime=1", body.contains("putInt(K_SHORT_CHIME, 1)"));
        assertTrue("R14.1 迁移把 short_chime 复位成 0（只一次）", body.contains("putInt(K_SHORT_CHIME, 0).putInt(K_R141_CHIME_RESET, 1)"));
        // 按键唤醒的隐私起点仍是 300 ms（不随长提示音变回 1100）
        assertEquals(300L, com.onesay.assistant.v2.wake.CaptureSessionPolicy.captureFromOffsetMs(true, false, 300, 450, 1100));
        assertEquals(1100L, com.onesay.assistant.v2.wake.CaptureSessionPolicy.captureFromOffsetMs(false, false, 300, 450, 1100));
    }

    @Test
    public void r141_kwsPausedDuringWakeSession() throws Exception {
        long now = 100_000;
        assertTrue("捕获窗开着 → 停", com.onesay.assistant.v2.wake.CaptureSessionPolicy.kwsPaused(now, now + 5000, 0));
        assertTrue("关窗后尾巴里 → 停", com.onesay.assistant.v2.wake.CaptureSessionPolicy.kwsPaused(now, 0, now + 1));
        assertFalse("会话外 → 跑", com.onesay.assistant.v2.wake.CaptureSessionPolicy.kwsPaused(now, 0, now));
        assertFalse(com.onesay.assistant.v2.wake.CaptureSessionPolicy.kwsPaused(now, now - 1, now - 1));
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue("KWS 块受暂停判据控制", ws.contains("if (!kwsPaused && mKws != null && mKwsStream != null) {"));
        assertTrue("恢复时 reset 流", ws.contains("mKws.reset(mKwsStream); } catch (Throwable ignore) {}"));
        // [ci-1114] 「按下就开始暂停」随 厂商键守护的 down 事件一起移除（开源版按键只有 PRESS），断言删去
        String fv = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue("新界面卡片不走 alpha 离屏层", fv.contains("public boolean hasOverlappingRendering() {\n        return !mV2;"));
        String fs = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("完成音的 prepare 不在主线程", fs.contains("Threads.start(\"AckDone\""));
    }

    // ------------------------------------------------------------ #4 图标钮

    @Test
    public void iconRowVisibility() {
        assertEquals(android.view.View.VISIBLE, WakeStage.iconRowVisibility(WakeStage.Stage.LISTENING));
        assertEquals(android.view.View.VISIBLE, WakeStage.iconRowVisibility(WakeStage.Stage.RESULT));
        assertEquals(android.view.View.VISIBLE, WakeStage.iconRowVisibility(WakeStage.Stage.ERROR));
        assertEquals("思考 / 作答占位不可点（卡片高度不跳）", android.view.View.INVISIBLE, WakeStage.iconRowVisibility(WakeStage.Stage.THINKING));
        assertEquals(android.view.View.INVISIBLE, WakeStage.iconRowVisibility(WakeStage.Stage.ANSWERING));
        for (WakeStage.Stage s : new WakeStage.Stage[] {WakeStage.Stage.TASK, WakeStage.Stage.CONFIRM, WakeStage.Stage.TEXT,
                WakeStage.Stage.HANDOFF, WakeStage.Stage.ISLAND, WakeStage.Stage.IDLE}) {
            assertEquals(s.name(), android.view.View.GONE, WakeStage.iconRowVisibility(s));
        }
        assertEquals(android.view.View.GONE, WakeStage.iconRowVisibility(null));
    }

    // ------------------------------------------------------------ 源码护栏（不能在 JVM 里实例化的 Android 类）

    static String src(String rel) throws Exception {
        File dir = com.onesay.assistant.v2.testutil.TestRoot.start(R14MiscTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) break;
            dir = dir.getParentFile();
        }
        return new String(java.nio.file.Files.readAllBytes(new File(dir, rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test
    public void guard_v2CardNotFocusableBeforeScreenRefHook_andFocusableOnlyForText() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int pre = fss.indexOf("prepareWakeWindowFlags();");
        int hook = fss.indexOf("com.onesay.assistant.v2.kernel.FeatureKernel.get().onWake(this);");
        assertTrue("先定 flag，再跑识屏挂钩", pre > 0 && hook > pre);
        assertTrue(fss.contains("if (mUseV2) mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;"));
        assertTrue(fss.contains("makeSheetFocusableForText();"));
        assertTrue("CRLF 保持", fss.contains("\r\n") && !fss.replace("\r\n", "").contains("\n"));
        String hooks = src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
        assertTrue("dump 推迟到入场动画之后", hooks.contains("}, DUMP_DEFER_MS);"));
        String cap = src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenContextCapture.java");
        assertTrue("唤醒 dump 降优先级", cap.contains("nice -n 10") && cap.contains("captureNow(app, true)"));
    }

    @Test
    public void guard_wakeServiceStreamingAndChimeAudio() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int sup = ws.indexOf("if (isSuppressed) {");
        int cont = ws.indexOf("continue;", sup);
        String block = ws.substring(sup, cont);
        // ★ D1（lane-d）：两处收音改走 appendCaptureChunk（先接按键预录，再 append + feedRealtime 本块），语义不变
        int app = ws.indexOf("private void appendCaptureChunk(short[] buf, int read) {");
        String appBody = ws.substring(app, ws.indexOf("\n    }\n", app));
        assertTrue("提示音期间捕获窗内的帧进缓冲 + 流式", block.contains("appendCaptureChunk(buf, read)")
                && appBody.contains("mPcmBuffer.append(buf, read)") && appBody.contains("feedRealtime(buf, read)"));
        assertFalse("提示音期间不喂能量门", block.contains("processFrame"));
        // [ci-1114] 「按下就预建流式」同上：没有 down 事件了，断言删去
        assertTrue("开窗绑定流式", ws.contains("bindRealtime(mCaptureGen)"));
        assertTrue("开窗冻结底噪", ws.contains("mEnergyGate.armCapture(mArmAt)"));
        assertTrue("续期要人声帧够数", ws.contains("RealtimeAsrPolicy.renewAllowed("));
        assertTrue("ICU 预热", ws.contains("PinyinUtil.prewarm()"));
        assertTrue("KWS 硬门语义不变", ws.contains("mEnergyGate.hardRecent(now)"));
        String router = src("app/src/com/onesay/assistant/v2/asr/AsrRouter.java");
        assertTrue("热词不在调用方线程上枚举 PackageManager", router.contains("AsrHotwords.get(mContext)") && !router.contains("? AsrHotwords.build(mContext) : hotwords;\n        mExec"));
        String http = src("app/src/com/onesay/assistant/v2/net/HttpClient.java");
        assertTrue("成功路径不 disconnect", http.contains("if (conn != null && (!clean || !KEEP_ALIVE)) conn.disconnect();"));
    }

    @Test
    public void guard_iconButtonsHaveContentDescriptions() throws Exception {
        String b = src("app/src/com/onesay/assistant/v2/ui/wake/CardIconButton.java");
        // ★ 综合包：多了 🎤（KIND_MIC → MicButtonPolicy.description：续说 / 停止收音），⌨ ◎ 的朗读文案不变
        // ★ 11-04 相机对话：朗读名收进 descriptionFor（多了 KIND_CAMERA「相机对话」），⌨ 🎤 ◎ 的文案不变
        assertTrue(b.contains("setContentDescription(descriptionFor(kind));"));
        assertTrue(b.contains("case KIND_KEYBOARD: return \"文字输入\";"));
        assertTrue(b.contains("case KIND_MIC: return MicButtonPolicy.description(false);"));
        assertTrue(b.contains("case KIND_CAMERA: return \"相机对话\";"));
        assertTrue(b.contains("default: return \"识屏\";"));
        String v = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(v.contains("attachV2IconRow();"));
        assertTrue(v.contains("mListener.onScreenModeRequested();"));
    }
}
