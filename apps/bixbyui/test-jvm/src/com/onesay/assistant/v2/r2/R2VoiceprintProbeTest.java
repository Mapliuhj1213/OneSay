package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.ui.VoiceprintEnrollController;
import com.onesay.assistant.v2.wake.WakeTuning;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * r2/fixes 第 15 项：setvpthr 探针改写 WakeTuning 的 vp_pct（WakeService 真正读的键）；
 * 声纹注册「字比对」明确降级停用，并在界面文案里说明。
 */
public class R2VoiceprintProbeTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/" + rel).toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void setvpthrWritesTheKeyWakeServiceReads() throws Exception {
        String recv = src("test/TestRunReceiver.java");
        int a = recv.indexOf("if (it.hasExtra(\"setvpthr\")) {");
        assertTrue(a > 0);
        String body = recv.substring(a, recv.indexOf("return;", a));
        assertTrue(body.contains("tuning.applyCsv(com.onesay.assistant.v2.wake.WakeTuning.K_VP_PCT + \"=\" + pct);"));
        assertTrue(body.contains("tuning.vpThresholdPct()"));
        assertFalse("不再写没人读的旧键", body.contains("wake_vp_cfg") || body.contains("threshold_pct"));
        // WakeService 读的正是 WakeTuning.vpThresholdPct
        String ws = src("wake/WakeService.java");
        assertTrue(ws.contains("int pct = (mTuning != null) ? mTuning.vpThresholdPct() : WakeTuning.DEF_VP_PCT;"));
        assertEquals("vp_pct", WakeTuning.K_VP_PCT);
        assertTrue(recv.contains("static int clampVpPct(int pct) {"));
    }

    @Test public void textCheckIsExplicitlyDisabled() throws Exception {
        assertFalse(VoiceprintEnrollController.TEXT_CHECK_ENABLED);
        String vp = src("ui/VoiceprintEnrollController.java");
        assertFalse("注册流程不再调用（恒空的）离线识别", vp.contains("recognizeOffline("));
        assertFalse("不再把期望句 / 识别文本写进日志", vp.contains("[VP-CHK]"));
        // 界面说明：引导句和完成提示都带
        assertEquals("只学声音，不核对念的字", VoiceprintEnrollController.TEXT_CHECK_NOTE);
        assertTrue(vp.contains("（不用刻意，正常说话就好；\" + TEXT_CHECK_NOTE + \"）"));
        assertTrue(vp.contains("+ TEXT_CHECK_NOTE + \"；只存在本机，永不上传。"));
        // 比较函数仍在（debug 探针 vpchk 用）
        assertEquals(1f, VoiceprintEnrollController.matchRatio(VoiceprintEnrollController.normCn("打开相机"),
                VoiceprintEnrollController.normCn("打开相机")), 0.001f);
    }
}
