package com.onesay.assistant.v2.wake;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ int/1102 唤醒提示音来源：厂商原声优先（按候选顺序）、找不到 / 时长不合理退回方案 A、来源可配、回声门掩码按实际音频重算。 */
public class WakeCueTest {

    @org.junit.Before public void vendorPref() {
        WakeCue.setPref(WakeCue.Source.SYSTEM);   // OneSay 默认是方案 A；这些用例测的是可选的厂商来源
    }

    @After public void reset() {
        WakeCue.setVendor(null, null);
        WakeCue.setPref(WakeCue.Source.SYSTEM);
    }

    /** 假「Bixby_BOS」：44.1 kHz、立体声已下混、前后各 20 ms 静音、中间 300 ms 的 1.5 kHz + 2.2 kHz。 */
    static short[] fakeBos(int rate) {
        int pad = rate / 50, body = rate * 300 / 1000;
        short[] s = new short[pad * 2 + body];
        for (int i = 0; i < body; i++) {
            double t = (double) i / rate;
            s[pad + i] = (short) (8000 * (Math.sin(2 * Math.PI * 1500 * t) + Math.sin(2 * Math.PI * 2200 * t)) * Math.exp(-3.0 * i / body));
        }
        return s;
    }

    @Test public void vendorFirstCandidateWinsAndFallsBackInOrder() {
        final List<String> tried = new ArrayList<String>();
        String used = WakeCue.loadVendor(new WakeCue.Decoder() {
            @Override public WakeCue.Decoded decode(WakeCue.Candidate c) {
                tried.add(c.label());
                if (c.label().endsWith("Bixby_BOS.ogg")) return null;   // 系统文件读不到（非三星 / 被精简）
                if (c.name.equals("toneplay_bos")) return new WakeCue.Decoded(new short[100], 44100);   // 太短（4 ms）→ 跳过
                return new WakeCue.Decoded(fakeBos(44100), 44100);
            }
        });
        assertEquals("com.samsung.android.bixby.agent/raw/voice_bos", used);
        assertEquals(3, tried.size());
        assertEquals(WakeCue.Source.SYSTEM, WakeCue.effective());
        int ms = Earcon.durationMs(Earcon.wakeChime(), Earcon.SAMPLE_RATE);
        assertTrue("去首尾静音后约 300 ms：" + ms, ms >= 290 && ms <= 320);
    }

    @Test public void noVendorFallsBackToA() {
        assertNull(WakeCue.loadVendor(new WakeCue.Decoder() {
            @Override public WakeCue.Decoded decode(WakeCue.Candidate c) { return null; }
        }));
        assertEquals(WakeCue.Source.A, WakeCue.effective());
        assertArrayEquals(Earcon.chimeA(Earcon.SAMPLE_RATE), Earcon.wakeChime());
        assertTrue(WakeCue.describe().contains("退回方案 A"));
        assertNull(WakeCue.loadVendor(null));
        assertEquals(WakeCue.Source.A, WakeCue.effective());
    }

    @Test public void prefSelectsSource() {
        WakeCue.setVendor(WakeCue.resample(WakeCue.trimSilence(fakeBos(44100), WakeCue.SILENCE_THR), 44100, Earcon.SAMPLE_RATE), "/system/media/audio/ui/Bixby_BOS.ogg");
        assertEquals(WakeCue.Source.SYSTEM, WakeCue.parse("vendor"));
        assertEquals(WakeCue.Source.A, WakeCue.parse("A"));
        assertEquals(WakeCue.Source.LEGACY, WakeCue.parse("legacy"));
        assertEquals("乱写 = 默认方案 A（OneSay）", WakeCue.Source.A, WakeCue.parse("xx"));
        WakeCue.setPref(WakeCue.Source.A);
        assertArrayEquals(Earcon.chimeA(Earcon.SAMPLE_RATE), Earcon.wakeChime());
        WakeCue.setPref(WakeCue.Source.LEGACY);
        assertArrayEquals(Earcon.legacyChime(Earcon.SAMPLE_RATE), Earcon.wakeChime());
        WakeCue.setPref(WakeCue.Source.SYSTEM);
        assertEquals(WakeCue.Source.SYSTEM, WakeCue.effective());
        assertTrue(WakeCue.describe().contains("Bixby_BOS"));
    }

    @Test public void echoMaskFollowsActualCue() {
        boolean[] a = ChimeEchoGate.shortChimeMask();
        WakeCue.setVendor(WakeCue.resample(WakeCue.trimSilence(fakeBos(44100), WakeCue.SILENCE_THR), 44100, Earcon.SAMPLE_RATE), "test");
        boolean[] v = ChimeEchoGate.shortChimeMask();
        int bin1500 = (int) Math.round(1500.0 * ChimeEchoGate.N / ChimeEchoGate.SAMPLE_RATE);
        int bin2200 = (int) Math.round(2200.0 * ChimeEchoGate.N / ChimeEchoGate.SAMPLE_RATE);
        int binC6 = (int) Math.round(Earcon.C6 * ChimeEchoGate.N / ChimeEchoGate.SAMPLE_RATE);
        assertTrue("厂商音的频点进掩码", v[bin1500] && v[bin2200]);
        assertFalse("方案 A 的频点不再挡（换来源后重算）", v[binC6]);
        assertTrue(a[binC6]);
        WakeCue.setPref(WakeCue.Source.A);
        assertArrayEquals("切回 A → 掩码回到 A", a, ChimeEchoGate.shortChimeMask());
    }

    @Test public void dspHelpers() {
        short[] st = {100, -100, 300, -300};
        assertArrayEquals(new short[]{0, 0}, WakeCue.downmix(st, 2));
        assertEquals(0, WakeCue.trimSilence(new short[50], 300).length);
        assertEquals(22050, WakeCue.resample(new short[44100], 44100, 22050).length);
    }
}
