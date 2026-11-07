package com.onesay.assistant.v2.ui.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/** [R13] 新界面开关 + wakeui.off kill-switch + 旧界面兜底（D6=A：新界面默认开，classic 保留一版）。 */
public class WakeUiSwitchTest {

    static String src(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(WakeUiSwitchTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, rel);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void defaultIsNewAndOnlyExplicitClassicIsOld() {
        assertTrue("没设置过 → 新界面", WakeUiSettings.decideNew(null, false, null));
        assertTrue(WakeUiSettings.decideNew(null, false, "new"));
        assertTrue("写错 → 新界面", WakeUiSettings.decideNew(null, false, "neww"));
        assertTrue(WakeUiSettings.decideNew(null, false, ""));
        assertFalse(WakeUiSettings.decideNew(null, false, "classic"));
        assertFalse(WakeUiSettings.decideNew(null, false, " CLASSIC "));
    }

    @Test public void killFileWinsOverPrefButNotOverDebugOverride() {
        assertFalse("wakeui.off → 旧界面", WakeUiSettings.decideNew(null, true, "new"));
        assertFalse(WakeUiSettings.decideNew(null, true, null));
        assertTrue("调试覆盖优先", WakeUiSettings.decideNew(Boolean.TRUE, true, "classic"));
        assertFalse(WakeUiSettings.decideNew(Boolean.FALSE, false, "new"));
    }

    @Test public void killFileLocations() throws Exception {
        assertEquals("wakeui.off", WakeUiSettings.KILL);
        File ext = Files.createTempDirectory("ext").toFile();
        File tmp = Files.createTempDirectory("tmp").toFile();
        assertFalse(FeatureSwitch.killFilePresent(ext, tmp.getPath(), WakeUiSettings.KILL));
        File a = new File(ext, "wakeui.off");
        assertTrue(a.createNewFile());
        assertTrue("外部 files 目录", FeatureSwitch.killFilePresent(ext, tmp.getPath(), WakeUiSettings.KILL));
        assertTrue(a.delete());
        File b = new File(tmp, "bixby_wakeui.off");
        assertTrue(b.createNewFile());
        assertTrue("/data/local/tmp/bixby_wakeui.off", FeatureSwitch.killFilePresent(ext, tmp.getPath(), WakeUiSettings.KILL));
        assertTrue(b.delete());
        assertFalse("删掉即恢复", FeatureSwitch.killFilePresent(ext, tmp.getPath(), WakeUiSettings.KILL));
    }

    @Test public void morphNeedsNewUiPrefAndAnimators() {
        assertTrue(WakeUiSettings.decideMorph(true, true, true));
        assertFalse("旧界面没有形变", WakeUiSettings.decideMorph(false, true, true));
        assertFalse(WakeUiSettings.decideMorph(true, false, true));
        assertFalse("系统动画缩放 = 0 → 瞬时（不做形变）", WakeUiSettings.decideMorph(true, true, false));
    }

    @Test public void classicFallbackStillWired() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("每次挂载按开关选界面", fss.contains("WakeUiSettings.useNew(this)"));
        assertTrue("旧界面水光窗原样保留", fss.contains("mRippleView = new WakeRippleView(this);") && fss.contains("mRippleView.startRipple();"));
        assertTrue("新界面不挂水光", fss.contains("if (mUseV2) {") && fss.contains("ensureGlowAttached();"));
        assertTrue("旧界面强退路径不变", fss.contains("hideSheet(true); // 外部请求 hide，立即强制收起"));
        assertTrue(new File(new File(src0()), "app/src/com/onesay/assistant/v2/ui/WakeRippleView.java").exists());
        String view = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue("旧 updateState 徽标分支保留（classic）", view.contains("badge.contains(\"聆听\")"));
        assertTrue("新界面走状态机", view.contains("WakeStage.classifyBadge(badge)") && view.contains("if (mV2) {"));
        assertTrue("旧面板外观可恢复", view.contains("applyClassicFrame();"));
        String chips = src("app/src/com/onesay/assistant/v2/ui/ActionChipsView.java");
        assertTrue(chips.contains("if (sV2Style)"));
    }

    @Test public void killSwitchesAndGuardsUntouched() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("CRLF 保持", fss.contains("\r\n") && !fss.replace("\r\n", "").contains("\n"));
        assertTrue("外部点击保护期：旧 1200 / 新 500 ms（D9）", fss.contains("OUTSIDE_TAP_GUARD_MS = 1200L") && fss.contains("OUTSIDE_TAP_GUARD_V2_MS = 500L"));
        assertTrue("长任务点外部仍收进岛", fss.contains("S1Hooks.onOutsideTap(FloatingSheetService.this)"));
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue("R12 暂停闸门还在", ws.contains("AssistantPause.blocks(AssistantPause.isPaused("));
        String island = src("app/src/com/onesay/assistant/v2/island/IslandView.java");
        assertTrue("岛外触摸透传（touchable region）还在", island.contains("TOUCHABLE_INSETS_REGION"));
        String gate = src("app/src/com/onesay/assistant/v2/wake/EnergyGate.java");
        assertTrue("音量出口只写 volatile", gate.contains("VoiceLevel.publish(rms, mNoiseFloor, now)"));
    }

    static String src0() throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(WakeUiSwitchTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/AndroidManifest.xml").exists()) return dir.getPath();
            dir = dir.getParentFile();
        }
        return ".";
    }
}
