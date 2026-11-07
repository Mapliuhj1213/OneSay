package com.onesay.assistant.v2.ui.live;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** ★ ui-K2 相机对话自录回声：三层防线（采集源 / 打断门槛 / 文本相似度）。 */
public class LiveEchoPolicyTest {

    // ------------------------------------------------------------------ ③ 文本层

    @Test public void lcsRatioOrSixCharRunIsEcho() {
        String spoken = "这是一台佳能单反相机，型号看不清，镜头大概是 24 到 70 毫米。";
        assertTrue("连续 ≥6 字", LiveEchoPolicy.isEcho("型号看不清镜头大概", spoken));
        assertTrue("最长公共子串 ≥60%（4/5）", LiveEchoPolicy.isEcho("佳能单反吗", spoken));
        assertTrue("去标点后包含", LiveEchoPolicy.isEcho("相机，型号看不清。", spoken));
        assertFalse("真问题", LiveEchoPolicy.isEcho("这个多少钱", spoken));
        assertFalse("换话题", LiveEchoPolicy.isEcho("帮我翻译一下上面的字", spoken));
        assertFalse("1–2 个字不按比例判（真人「停」「好」）", LiveEchoPolicy.isEcho("相机", spoken));
        assertFalse("没有朗读", LiveEchoPolicy.isEcho("型号看不清镜头大概", ""));
    }

    @Test public void lcsAndRatioHelpers() {
        assertEquals(4, LiveEchoPolicy.lcs("abcdx", "zzabcdzz"));
        assertEquals(0, LiveEchoPolicy.lcs("", "abc"));
        assertEquals(0.8, LiveEchoPolicy.ratio("佳能单反吗", "这是一台佳能单反相机"), 1e-9);
    }

    @Test public void policyDropsFinalEchoBySimilarityDuringSpeechAndTail() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "这是一瓶农夫山泉矿泉水，550毫升，保质期十二个月。");
        assertEquals("朗读中：连续 6 字回声", LiveTurnPolicy.Action.DROP_ECHO, p.onFinal("毫升保质期十二个月", 500));
        p.onSpeechDone(1000);
        assertEquals("刚结束：60% 相似", LiveTurnPolicy.Action.DROP_ECHO, p.onFinal("农夫山泉吧", 1500));
        assertEquals("真话照常开一轮", LiveTurnPolicy.Action.START_TURN, p.onFinal("能帮我查查价格吗", 1600));
    }

    // ------------------------------------------------------------------ ② 打断门槛

    @Test public void guardWindowCoversSpeechAndEightHundredMsTail() {
        assertTrue(LiveEchoPolicy.inGuard(true, 0, 0));
        assertTrue(LiveEchoPolicy.inGuard(false, 1000, 1800));
        assertFalse(LiveEchoPolicy.inGuard(false, 1000, 1801));
        assertFalse("还没播过", LiveEchoPolicy.inGuard(false, 0, 100));
    }

    @Test public void partialBargeNeedsThreeCharsAndNotEcho() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "这是一台佳能单反相机，型号看不清。");
        assertEquals("两个字不够（门槛期）", LiveTurnPolicy.Action.NONE, p.onPartial("等等"));
        assertEquals("回声（公共子串）不打断", LiveTurnPolicy.Action.NONE, p.onPartial("单反相机型号"));
        assertEquals(LiveTurnPolicy.State.SPEAKING, p.state());
        assertEquals("真人「等一下」仍能打断", LiveTurnPolicy.Action.BARGE_IN, p.onPartial("等一下"));
    }

    @Test public void shortFragmentRightAfterSpeechIsDropped() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("看看", 0);
        p.onAnswer(p.gen(), "看看", "一盆绿萝。");
        p.onSpeechDone(1000);
        assertEquals("800 ms 内 1–2 字碎片按回声丢", LiveTurnPolicy.Action.DROP_ECHO, p.onFinal("嗯好", 1500));
        assertEquals("过了 800 ms 正常", LiveTurnPolicy.Action.START_TURN, p.onFinal("好的", 1900));
    }

    @Test public void guardEnergyDetectorIsStricterButCatchesRealVoice() {
        assertTrue(LiveEchoPolicy.GUARD_ABS_MIN > LiveTurnPolicy.BARGE_ABS_MIN);
        assertTrue(LiveEchoPolicy.GUARD_ECHO_RATIO > LiveTurnPolicy.BARGE_ECHO_RATIO);
        assertTrue(LiveEchoPolicy.GUARD_MIN_FRAMES > LiveTurnPolicy.BARGE_MIN_FRAMES);
        com.onesay.assistant.v2.wake.BargeInDetector b = new com.onesay.assistant.v2.wake.BargeInDetector(LiveEchoPolicy.GUARD_ABS_MIN, 4f,
                LiveEchoPolicy.GUARD_ECHO_RATIO, LiveEchoPolicy.GUARD_MIN_FRAMES, LiveTurnPolicy.BARGE_CALIBRATE_MS);
        b.start(0, 120f);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 100) assertFalse("标定期", b.offer(t, 1500f));
        for (int i = 0; i < 30; i++, t += 100) assertFalse("回声起伏（≈2× 基线）不打断", b.offer(t, 1500f + (i % 5) * 400f));
        com.onesay.assistant.v2.wake.BargeInDetector c = new com.onesay.assistant.v2.wake.BargeInDetector(LiveEchoPolicy.GUARD_ABS_MIN, 4f,
                LiveEchoPolicy.GUARD_ECHO_RATIO, LiveEchoPolicy.GUARD_MIN_FRAMES, LiveTurnPolicy.BARGE_CALIBRATE_MS);
        c.start(0, 120f);
        t = 0;
        for (int i = 0; i < 8; i++, t += 100) c.offer(t, 1500f);
        boolean fired = false;
        for (int i = 0; i < 5; i++, t += 100) fired |= c.offer(t, 14000f);
        assertFalse("5 帧（原门槛 4 帧就打断）：门槛期还不打断", fired);
        assertTrue("真人持续开口第 6 帧打断", c.offer(t, 14000f));
    }

    // ------------------------------------------------------------------ ① 采集层

    @Test public void wakeMicYieldsForLiveOwnMicOnly() {
        assertTrue("相机对话自己开麦 → 唤醒那一路停录", com.onesay.assistant.v2.wake.ScreenOffPolicy.micShouldPause(false, true, false, false, true));
        assertFalse("声纹注册例外", com.onesay.assistant.v2.wake.ScreenOffPolicy.micShouldPause(false, false, true, false, true));
        assertFalse("旧旁路（借麦）不停录", com.onesay.assistant.v2.wake.ScreenOffPolicy.micShouldPause(true, true, false, false, false));
        assertTrue("普通暂停照旧", com.onesay.assistant.v2.wake.ScreenOffPolicy.micShouldPause(true, false, false, false, false));
    }

    @Test public void liveMicSourceIsVoiceCommunicationWithAecNsAndCommRoute() throws Exception {
        String conv = read("app/src/com/onesay/assistant/v2/ui/live/LiveConversation.java");
        assertTrue(conv.contains("MediaRecorder.AudioSource.VOICE_COMMUNICATION"));
        assertTrue(conv.contains("AcousticEchoCanceler.isAvailable()"));
        assertTrue(conv.contains("NoiseSuppressor.isAvailable()"));
        assertTrue(conv.contains("WakeService.setLiveMicTap(this, true)"));
        assertTrue(conv.contains("MODE_IN_COMMUNICATION"));
        assertTrue(conv.contains("TtsSpeaker.setCommRoute(true)"));
        assertTrue(conv.contains("TtsSpeaker.setCommRoute(false)"));
        assertEquals(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION, com.onesay.assistant.v2.ui.TtsSpeaker.playbackUsage(true));
        assertEquals(android.media.AudioAttributes.USAGE_ASSISTANT, com.onesay.assistant.v2.ui.TtsSpeaker.playbackUsage(false));
        String wake = read("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue("普通唤醒录音音源不变", wake.contains("MediaRecorder.AudioSource.VOICE_RECOGNITION"));
        String pcm = read("app/src/com/onesay/assistant/v2/tts/PcmStreamPlayer.java");
        assertTrue(pcm.contains("TtsSpeaker.playbackUsage(com.onesay.assistant.v2.ui.TtsSpeaker.commRoute())"));
    }

    private static String read(String rel) throws Exception {
        String r = System.getProperty("bixby.root");
        return new String(Files.readAllBytes(new File(r == null ? "." : r, rel).toPath()), StandardCharsets.UTF_8);
    }
}
