package com.onesay.assistant.v2.entry.keywake;

import com.onesay.plugin.api.entry.EntryTrigger;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** 按键唤醒：双击电源键判定（熄屏 / 亮屏广播对）、音量键长按、原始按键双击、默认值与动作路由。 */
public class KeyWakeTest {

    @Test public void defaults_powerDoubleAndVoice() {
        assertEquals(KeyWake.Trigger.POWER_DOUBLE, KeyWake.parseTrigger(null));
        assertEquals(KeyWake.Trigger.POWER_DOUBLE, KeyWake.parseTrigger("garbage"));
        assertEquals(KeyWake.Action.VOICE, KeyWake.parseAction(null));
        assertEquals(KeyWake.Action.VOICE, KeyWake.parseAction("bad"));
        assertEquals(600, KeyWake.DEFAULT_WINDOW_MS);
        assertEquals(KeyWake.Trigger.VOLUME_UP_LONG, KeyWake.parseTrigger("volume_up_long"));
        assertEquals(KeyWake.Action.SCREEN, KeyWake.parseAction("SCREEN"));
    }

    @Test public void actionRouting() {
        assertEquals(EntryTrigger.Mode.VOICE, KeyWake.modeFor(KeyWake.Action.VOICE));
        assertEquals(EntryTrigger.Mode.SCREEN, KeyWake.modeFor(KeyWake.Action.SCREEN));
        assertEquals(EntryTrigger.Mode.TEXT, KeyWake.modeFor(KeyWake.Action.TEXT));
        assertEquals(EntryTrigger.Mode.VOICE, KeyWake.modeFor(null));
        assertEquals("识屏", KeyWake.SCREEN_COMMAND);
    }

    @Test public void triggerNeeds() {
        assertTrue(KeyWake.needsScreenEvents(KeyWake.Trigger.POWER_DOUBLE));
        assertFalse(KeyWake.needsKeyFilter(KeyWake.Trigger.POWER_DOUBLE));
        assertTrue(KeyWake.needsKeyFilter(KeyWake.Trigger.VOLUME_DOWN_LONG));
        assertFalse(KeyWake.needsScreenEvents(KeyWake.Trigger.RAW_INPUT));
        assertFalse(KeyWake.needsKeyFilter(KeyWake.Trigger.RAW_INPUT));
    }

    @Test public void windowClamped() {
        assertEquals(600, KeyWake.clampWindow(0));
        assertEquals(300, KeyWake.clampWindow(50));
        assertEquals(1200, KeyWake.clampWindow(5000));
        assertEquals(800, KeyWake.clampWindow(800));
    }

    @Test public void powerDouble_screenOnPhone_offThenOnWithinWindow() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        assertFalse(d.onScreen(false, 10_000, false));
        assertTrue("灭→亮 400 ms 内 = 双击", d.onScreen(true, 10_400, false));
    }

    @Test public void powerDouble_screenOffPhone_onThenOff() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        assertFalse(d.onScreen(true, 1000, false));
        assertTrue(d.onScreen(false, 1500, false));
    }

    @Test public void powerDouble_tooSlow_isTwoSinglePresses() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        assertFalse(d.onScreen(false, 1000, false));
        assertFalse("超过时间窗", d.onScreen(true, 1601, false));
        assertTrue("但第二次可以作为新一对的开头", d.onScreen(false, 1900, false));
    }

    @Test public void powerDouble_exactWindowEdge() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        d.onScreen(false, 0, false);
        assertTrue(d.onScreen(true, 600, false));
    }

    @Test public void powerDouble_suppressedProximityOrTimeoutNeverPairs() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        assertFalse(d.onScreen(false, 1000, true));   // 超时 / 距离传感器熄屏
        assertFalse("前一次被压制 → 不配对", d.onScreen(true, 1200, false));
        assertFalse(d.onScreen(false, 1300, true));
        assertFalse(d.onScreen(true, 1400, false));
    }

    @Test public void powerDouble_sameDirectionDoesNotPair() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        d.onScreen(true, 1000, false);
        assertFalse(d.onScreen(true, 1100, false));
    }

    @Test public void powerDouble_tripleFiresOnceThenCooldown() {
        PowerDoubleDetector d = new PowerDoubleDetector(600);
        assertFalse(d.onScreen(false, 1000, false));
        assertTrue(d.onScreen(true, 1300, false));
        assertFalse("第三下在冷却里", d.onScreen(false, 1500, false));
        assertFalse(d.onScreen(true, 1800, false));
        assertFalse(d.onScreen(false, 3000, false));
        assertTrue("冷却过后正常", d.onScreen(true, 3300, false));
    }

    @Test public void powerDouble_tunableWindow() {
        PowerDoubleDetector d = new PowerDoubleDetector(400);
        d.onScreen(false, 0, false);
        assertFalse(d.onScreen(true, 500, false));
        d.setWindow(800);
        d.onScreen(false, 2000, false);
        assertTrue(d.onScreen(true, 2700, false));
    }

    @Test public void likelyTimeout() {
        assertTrue(KeyWake.likelyTimeout(29_000, 30_000));
        assertFalse(KeyWake.likelyTimeout(5_000, 30_000));
        assertFalse("拿不到超时设置 = 不判定", KeyWake.likelyTimeout(29_000, -1));
        assertFalse("没有界面事件记录 = 不判定", KeyWake.likelyTimeout(Long.MAX_VALUE, 30_000));
    }

    @Test public void longPress() {
        LongPressDetector lp = new LongPressDetector(600);
        assertEquals(LongPressDetector.Result.NONE, lp.onUp(100));
        lp.onDown(1000);
        lp.onDown(1100);   // 自动重复
        assertEquals(LongPressDetector.Result.LONG, lp.onUp(1700));
        lp.onDown(2000);
        assertEquals(LongPressDetector.Result.SHORT, lp.onUp(2200));
        assertFalse(lp.pressed());
    }

    @Test public void rawDoubleTap() {
        DoubleTap t = new DoubleTap(600);
        assertFalse(t.onPress(1000));
        assertTrue(t.onPress(1350));
        assertFalse("三连按只算一次", t.onPress(1500));
        assertFalse(t.onPress(2500));
        assertFalse(t.onPress(3200));
    }

    @Test public void descriptionWarnsAboutCameraAndHasNoVendorName() {
        assertTrue(KeyWake.DESCRIPTION.contains("相机"));
        assertEquals("按键唤醒", KeyWake.LABEL);
        assertFalse(KeyWake.DESCRIPTION.toLowerCase().contains("bixby"));
    }

    @Test public void a11yServiceShippedInReleaseManifestWithKeyFilter() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String m = new String(Files.readAllBytes(Paths.get(root, "app/AndroidManifest.xml")), "UTF-8");
        int a = m.indexOf("OneSayA11yService"), dbg = m.indexOf("DEBUG-ONLY-BEGIN");
        assertTrue("无障碍服务在 release Manifest（DEBUG-ONLY 段之前）", a > 0 && (dbg < 0 || a < dbg));
        String x = new String(Files.readAllBytes(Paths.get(root, "app/res/xml/onesay_a11y_service.xml")), "UTF-8");
        assertTrue(x.contains("canRequestFilterKeyEvents=\"true\""));
    }
}
