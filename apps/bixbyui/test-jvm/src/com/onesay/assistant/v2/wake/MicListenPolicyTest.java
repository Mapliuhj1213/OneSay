package com.onesay.assistant.v2.wake;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

/** mic-1118：唤醒词监听时机 —— 默认不让麦克风指示灯整天亮着。 */
public class MicListenPolicyTest {

    private static final long NOW = 10_000_000L;

    private static MicListenPolicy.State st(boolean on, boolean locked, boolean charging, long unlockedAt) {
        return new MicListenPolicy.State(on, locked, charging, unlockedAt, NOW);
    }

    private static boolean listen(String mode, MicListenPolicy.State s) {
        return MicListenPolicy.decide(mode, 10, false, s).listen;
    }

    @Test public void defaultModeIsUnlockedOnly() {
        assertEquals(MicListenPolicy.MODE_UNLOCKED, MicListenPolicy.DEFAULT_MODE);
        assertEquals(MicListenPolicy.MODE_UNLOCKED, MicListenPolicy.normalizeMode(null));
        assertEquals(MicListenPolicy.MODE_UNLOCKED, MicListenPolicy.normalizeMode("bogus"));
        assertFalse("默认模式息屏不能听", MicListenPolicy.listensWhileScreenOff(MicListenPolicy.DEFAULT_MODE));
    }

    @Test public void unlockedMode() {
        String m = MicListenPolicy.MODE_UNLOCKED;
        assertTrue(listen(m, st(true, false, false, NOW - 1)));
        assertFalse("锁屏亮着也不听", listen(m, st(true, true, false, NOW - 1)));
        assertFalse("息屏不听", listen(m, st(false, true, false, NOW - 1)));
        assertFalse("息屏未锁（延迟锁定）也不听", listen(m, st(false, false, false, NOW - 1)));
        assertFalse("充电也不例外", listen(m, st(false, true, true, NOW - 1)));
    }

    @Test public void afterUnlockMode() {
        String m = MicListenPolicy.MODE_AFTER_UNLOCK;
        MicListenPolicy.Decision d = MicListenPolicy.decide(m, 10, false, st(true, false, false, NOW - 60_000));
        assertTrue(d.listen);
        assertEquals("到点再判", NOW - 60_000 + 10 * 60_000L, d.recheckAt);
        assertFalse(MicListenPolicy.decide(m, 10, false, st(true, false, false, NOW - 10 * 60_000L)).listen);
        assertFalse(MicListenPolicy.decide(m, 10, false, st(true, false, false, Long.MIN_VALUE)).listen);
        assertFalse(MicListenPolicy.decide(m, 10, false, st(false, false, false, NOW - 1)).listen);
        assertFalse(MicListenPolicy.decide(m, 10, false, st(true, true, false, NOW - 1)).listen);
        // 分钟数夹在 1..240
        assertEquals(NOW - 1 + 60_000L, MicListenPolicy.decide(m, 0, false, st(true, false, false, NOW - 1)).recheckAt);
        assertEquals(NOW - 1 + 240 * 60_000L, MicListenPolicy.decide(m, 9999, false, st(true, false, false, NOW - 1)).recheckAt);
    }

    @Test public void chargingMode() {
        String m = MicListenPolicy.MODE_CHARGING;
        assertTrue("充电息屏也听", listen(m, st(false, true, true, Long.MIN_VALUE)));
        assertFalse(listen(m, st(true, false, false, NOW - 1)));
        assertTrue(MicListenPolicy.listensWhileScreenOff(m));
    }

    @Test public void alwaysAndOff() {
        assertTrue(listen(MicListenPolicy.MODE_ALWAYS, st(false, true, false, Long.MIN_VALUE)));
        assertFalse(listen(MicListenPolicy.MODE_OFF, st(true, false, true, NOW - 1)));
        assertEquals(-1, MicListenPolicy.decide(MicListenPolicy.MODE_ALWAYS, 10, false, st(true, false, false, 0)).recheckAt);
    }

    @Test public void quickOffWinsOverEveryMode() {
        for (String[] r : MicListenPolicy.MODES) {
            MicListenPolicy.Decision d = MicListenPolicy.decide(r[0], 10, true, st(true, false, true, NOW - 1));
            assertFalse(r[0], d.listen);
            assertTrue(d.reason.contains("快捷开关"));
        }
    }

    @Test public void captureAndEnrollStillOpenMic() {
        MicListenPolicy.Decision off = MicListenPolicy.decide(MicListenPolicy.MODE_OFF, 10, false, st(true, false, false, NOW - 1));
        assertFalse(MicListenPolicy.micOpen(off, false, false));
        assertTrue("按键叫出来的那一句照常收", MicListenPolicy.micOpen(off, true, false));
        assertTrue("声纹注册照常收", MicListenPolicy.micOpen(off, false, true));
        assertFalse("没判过 = 按旧行为（WakeService 里 null 视为开）", MicListenPolicy.micOpen(null, false, false));
    }

    @Test public void userTextHasNoVendorNames() {
        StringBuilder all = new StringBuilder();
        for (String[] r : MicListenPolicy.MODES) all.append(r[1]).append(r[2]);
        all.append(MicListenPolicy.statusText(MicListenPolicy.decide(null, 10, false, st(false, true, false, 0))));
        String t = all.toString();
        assertFalse(t.contains("Bixby"));
        assertFalse(t.contains("三星"));
        assertFalse(t.contains("小布"));
    }

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    /** 接线：读循环和暂停等待都看监听门；捕获窗打开会叫醒等待；屏幕 / 充电 / 设置变化会重判；快捷开关已声明。 */
    @Test public void wiring() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(ws.contains("liveOwnMicActive()) || listenGateClosed()) {"));
        assertTrue(ws.contains("|| listenGateClosed())) {"));
        assertTrue(ws.contains("wakePauseWaiter();   // ★ mic-1118"));
        assertTrue(ws.contains("Intent.ACTION_POWER_CONNECTED"));
        assertTrue(ws.contains("reevalListen(\"screen:\""));
        assertTrue(ws.contains("registerOnSharedPreferenceChangeListener(mListenPrefsListener)"));
        assertTrue(ws.contains("unregisterOnSharedPreferenceChangeListener(mListenPrefsListener)"));
        String mf = src("app/AndroidManifest.xml");
        assertTrue(mf.contains("com.onesay.assistant.v2.entry.MicListenTileService"));
        String vp = src("app/src/com/onesay/assistant/v2/ui/console/VoicePage.java");
        assertTrue(vp.contains("buildMicListen(col)"));
    }
}
