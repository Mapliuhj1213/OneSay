package com.onesay.assistant.v2.wake;

import org.junit.Test;

import static org.junit.Assert.*;

/** D1：预录开关与调参（默认开；参数有上下界）。 */
public class KeyPreRollTuningTest {

    @Test public void switchDefaultsOn() {
        assertEquals("key_preroll", WakeTuning.K_KEY_PREROLL);
        assertEquals("默认开", 1, WakeTuning.DEF_KEY_PREROLL);
        assertEquals(300, WakeTuning.DEF_PREROLL_MS);
    }

    @Test public void tuningDefaultsMatchGateDefaults() {
        assertEquals(35, WakeTuning.DEF_PREROLL_LEAK_PCT);
        assertEquals("D-1（10-23）起默认 4", 4, WakeTuning.DEF_PREROLL_MIN_FRAMES);
        assertEquals(2, WakeTuning.DEF_PREROLL_HANG);
        assertEquals(220, WakeTuning.DEF_PREROLL_ABS_MIN);
        assertEquals(180, WakeTuning.DEF_PREROLL_FLOOR_PCT);
        assertEquals(0, WakeTuning.DEF_PREROLL_ATTEN_PCT);
        ChimeEchoGate g = ChimeEchoGate.fromParams(false, 35, WakeTuning.DEF_PREROLL_MIN_FRAMES, 2, 220, 180, 0);
        assertEquals(ChimeEchoGate.DEF_LEAK, g.leak(), 1e-6);
        assertEquals(4, g.minFrames());
    }

    @Test public void paramsAreClamped() {
        assertEquals("连续帧下限 2：调成 1 / 0 也不会让孤立瞬态放行", 2, ChimeEchoGate.fromParams(false, 35, 1, 2, 220, 180, 0).minFrames());
        assertEquals(2, ChimeEchoGate.fromParams(false, 35, -9, 2, 220, 180, 0).minFrames());
        assertEquals(10, ChimeEchoGate.fromParams(false, 35, 99, 2, 220, 180, 0).minFrames());
        assertEquals(0.05f, ChimeEchoGate.fromParams(false, 0, 3, 2, 220, 180, 0).leak(), 1e-6);
        assertEquals(2.0f, ChimeEchoGate.fromParams(false, 900, 3, 2, 220, 180, 0).leak(), 1e-6);
    }

    @Test public void shortChimeModeUsesShortChimeMask() throws Exception {
        // 短提示音模式下，短提示音单独出现照样整段丢弃；原版掩码挡不住 1320 Hz 那一段
        double[] x = LaneDAudio.noise(4800, 60, 1);
        LaneDAudio.mixInto(x, 60 * 16, LaneDAudio.echo(LaneDAudio.shortChime16k(), 2800, 0.5, 0.1, 1), 1);
        short[] pcm = LaneDAudio.toPcm(x);
        assertFalse(ChimeEchoGate.fromParams(true, 35, 3, 2, 220, 180, 0).process(pcm, pcm.length, 75).kept());
        assertTrue("掩码选错就会把提示音当人声", ChimeEchoGate.fromParams(false, 35, 3, 2, 220, 180, 0).process(pcm, pcm.length, 75).kept());
    }

    @Test public void consoleHasTheSwitch() {
        String voice = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/ui/console/VoicePage.java");
        assertTrue(voice.contains("WakeTuning.K_KEY_PREROLL"));
        assertTrue(voice.contains("tuning.keyPreRoll()"));
    }

    @Test public void preRollMsOnlyShortens() {
        String wt = KeyPreRollTest.src("app/src/com/onesay/assistant/v2/wake/WakeTuning.java");
        assertTrue("preroll_ms 上限 = R14 按键隐私起点", wt.contains("Math.min(v, DEF_PREROLL_MS)"));
    }
}
