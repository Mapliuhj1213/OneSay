package com.onesay.assistant.v2.ui.live;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 相机对话（Gemini Live 式）：输入栏三态、回合状态机（打断 / 回声 / 上下文 / 默认问题）、帧只在内存、岛上文案。 */
public class CameraLiveTest {

    @Before
    public void reset() {
        LiveInputMode.get().resetForTest();
    }

    // ------------------------------------------------------------------ 输入栏三态

    @Test public void cameraRemembersPreviousInputMode() {
        LiveInputMode m = LiveInputMode.get();
        m.set(LiveInputMode.Mode.TEXT);
        assertTrue(m.enterCamera(LiveInputMode.Mode.TEXT));
        assertEquals(LiveInputMode.Mode.CAMERA, m.current());
        assertTrue(m.inCamera());
        assertEquals("退出回到键盘", LiveInputMode.Mode.TEXT, m.exitCamera());
        assertEquals(LiveInputMode.Mode.TEXT, m.current());
        assertTrue(m.enterCamera(LiveInputMode.Mode.VOICE));
        assertEquals("退出回到录音", LiveInputMode.Mode.VOICE, m.exitCamera());
    }

    @Test public void doubleEnterDoesNotOverwriteAndStraySetIgnored() {
        LiveInputMode m = LiveInputMode.get();
        assertTrue(m.enterCamera(LiveInputMode.Mode.TEXT));
        assertFalse("连点第二次不覆盖", m.enterCamera(LiveInputMode.Mode.CAMERA));
        m.set(LiveInputMode.Mode.VOICE);   // 相机中切录音 / 键盘无效
        assertEquals(LiveInputMode.Mode.CAMERA, m.current());
        assertEquals(LiveInputMode.Mode.TEXT, m.exitCamera());
        assertEquals("不在相机里退出 = 原样", LiveInputMode.Mode.TEXT, m.exitCamera());
        assertTrue(m.enterCamera(null));
        assertEquals("来源未知按录音", LiveInputMode.Mode.VOICE, m.exitCamera());
    }

    // ------------------------------------------------------------------ 回合

    @Test public void defaultQuestionWhenNothingMeaningful() {
        assertEquals("这是什么？帮我看看", LiveTurnPolicy.DEFAULT_QUESTION);
        assertEquals(LiveTurnPolicy.DEFAULT_QUESTION, LiveTurnPolicy.questionOrDefault(null));
        assertEquals(LiveTurnPolicy.DEFAULT_QUESTION, LiveTurnPolicy.questionOrDefault("  "));
        assertEquals(LiveTurnPolicy.DEFAULT_QUESTION, LiveTurnPolicy.questionOrDefault("嗯。"));
        assertEquals(LiveTurnPolicy.DEFAULT_QUESTION, LiveTurnPolicy.questionOrDefault("啊啊"));
        assertEquals("这个怎么用", LiveTurnPolicy.questionOrDefault(" 这个怎么用 "));
    }

    @Test public void listenThinkSpeakListen() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        assertEquals(LiveTurnPolicy.State.LISTENING, p.state());
        assertEquals(LiveTurnPolicy.Action.START_TURN, p.onFinal("这是什么", 1000));
        assertEquals(LiveTurnPolicy.State.THINKING, p.state());
        int g = p.gen();
        assertTrue(p.onAnswer(g, "这是什么", "这是一只蓝色马克杯。"));
        assertEquals(LiveTurnPolicy.State.SPEAKING, p.state());
        p.onSpeechDone(3000);
        assertEquals(LiveTurnPolicy.State.LISTENING, p.state());
        assertEquals(1, p.history().size());
    }

    @Test public void bargeInOnRealSpeechButNotOnEcho() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "这是一台佳能单反相机，型号看不清。");
        assertEquals("回声不打断", LiveTurnPolicy.Action.NONE, p.onPartial("佳能单反"));
        assertEquals("语气词不打断", LiveTurnPolicy.Action.NONE, p.onPartial("嗯"));
        assertEquals(LiveTurnPolicy.State.SPEAKING, p.state());
        assertEquals("真开口 → 打断", LiveTurnPolicy.Action.BARGE_IN, p.onPartial("等一下"));
        assertEquals(LiveTurnPolicy.State.LISTENING, p.state());
        assertEquals("已打断不重复", LiveTurnPolicy.Action.NONE, p.onPartial("等一下我问"));
    }

    /** 真机 11-04：回声被转写，播完那一刻「你」那行出现回答原文。播报中 / 尾巴期内像回答的中间结果不上字幕。 */
    @Test public void echoPartialsAreNotShownAsUserText() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        assertTrue(p.showPartial("这是什么", 0));
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "画面全黑，看不到东西，请打开手电筒再拍。");
        assertFalse("播报中不上", p.showPartial("等一下", 100));
        p.onSpeechDone(5000);
        assertFalse("尾巴期回声不上", p.showPartial("画面全黑看不到东西", 6000));
        assertTrue("尾巴期真话照上", p.showPartial("这个怎么用", 6000));
        assertTrue("过了尾巴期照上", p.showPartial("画面全黑", 9000));
        p.setMuted(true);
        assertFalse("静音不上", p.showPartial("这个怎么用", 9500));
    }

    @Test public void energyBargeOnlyWhileSpeakingAndNotMuted() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        assertEquals(LiveTurnPolicy.Action.NONE, p.onEnergyBarge());
        p.onFinal("看看这个", 0);
        p.onAnswer(p.gen(), "看看这个", "一盆绿萝。");
        p.setMuted(true);
        assertEquals("静音中不打断", LiveTurnPolicy.Action.NONE, p.onEnergyBarge());
        p.setMuted(false);
        assertEquals(LiveTurnPolicy.Action.BARGE_IN, p.onEnergyBarge());
    }

    @Test public void echoFinalDroppedWhileSpeakingAndInTail() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "这是一瓶农夫山泉矿泉水，550毫升。");
        assertEquals(LiveTurnPolicy.Action.DROP_ECHO, p.onFinal("农夫山泉矿泉水", 500));
        p.onSpeechDone(1000);
        assertEquals("播完 3 s 内的尾巴也丢", LiveTurnPolicy.Action.DROP_ECHO, p.onFinal("550毫升", 3500));
        assertEquals("过了尾巴期就当真话", LiveTurnPolicy.Action.START_TURN, p.onFinal("550毫升", 4500));
    }

    @Test public void newSentenceWhileThinkingSupersedesOldRequest() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        int g1 = p.gen();
        assertEquals(LiveTurnPolicy.Action.START_TURN, p.onFinal("不对，看左边那个", 100));
        int g2 = p.gen();
        assertNotEquals(g1, g2);
        assertFalse("旧回答作废", p.onAnswer(g1, "这是什么", "旧"));
        assertTrue(p.onAnswer(g2, "不对，看左边那个", "左边是一个充电宝。"));
        assertFalse("旧的失败也不影响", p.onError(g1));
    }

    @Test public void finalWhileSpeakingIsBargePlusNewTurn() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.onFinal("这是什么", 0);
        p.onAnswer(p.gen(), "这是什么", "这是一把螺丝刀。");
        assertEquals(LiveTurnPolicy.Action.START_TURN, p.onFinal("这个怎么用", 300));
        assertEquals(LiveTurnPolicy.State.THINKING, p.state());
    }

    @Test public void mutedDropsSpeechAndErrorsReturnToListening() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        p.setMuted(true);
        assertEquals(LiveTurnPolicy.Action.DROP_MUTED, p.onFinal("这是什么", 0));
        p.setMuted(false);
        p.onFinal("这是什么", 0);
        assertTrue(p.onError(p.gen()));
        assertEquals(LiveTurnPolicy.State.LISTENING, p.state());
    }

    @Test public void historyCarriesContextAndIsCappedAndClearedOnEnd() {
        LiveTurnPolicy p = new LiveTurnPolicy();
        for (int i = 0; i < LiveTurnPolicy.MAX_TURNS + 3; i++) {
            p.onFinal("问" + i, i * 10000L);
            assertTrue(p.onAnswer(p.gen(), "问" + i, "答" + i));
            p.onSpeechDone(i * 10000L + 1);
        }
        List<String[]> h = p.history();
        assertEquals(LiveTurnPolicy.MAX_TURNS, h.size());
        assertEquals("最旧的被挤掉", "问3", h.get(0)[0]);
        assertEquals("答" + (LiveTurnPolicy.MAX_TURNS + 2), h.get(h.size() - 1)[1]);
        p.end();
        assertEquals(LiveTurnPolicy.State.ENDED, p.state());
        assertTrue("挂断清空上下文", p.history().isEmpty());
        assertEquals(LiveTurnPolicy.Action.NONE, p.onFinal("还在吗", 999999));
    }

    @Test public void echoHeuristics() {
        assertTrue(LiveTurnPolicy.isEcho("马克杯", "这是一只蓝色马克杯。"));
        assertTrue(LiveTurnPolicy.isEcho("这是一只蓝色的马克杯", "这是一只蓝色马克杯。"));
        assertFalse(LiveTurnPolicy.isEcho("这个多少钱", "这是一只蓝色马克杯。"));
        assertFalse(LiveTurnPolicy.isEcho("这是什么", ""));
    }

    @Test public void islandLineShowsTimerOrThinking() {
        assertEquals("相机对话中 · 0:00", LiveTurnPolicy.islandLine(false, 400));
        assertEquals("相机对话中 · 1:05", LiveTurnPolicy.islandLine(false, 65_000));
        assertEquals("思考中", LiveTurnPolicy.islandLine(true, 65_000));
        assertEquals("1:00:09", LiveTurnPolicy.clock(3_609_000));
    }

    // ------------------------------------------------------------------ 帧 / 临时文件

    @Test public void frameIsWipedAfterUse() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        LiveFrame f = new LiveFrame(jpeg, 576, 768);
        assertEquals("576x768 7B", f.describe());
        f.wipe();
        assertTrue(f.wiped());
        assertNull(f.bytes());
        for (byte b : jpeg) assertEquals("字节清零", 0, b);
        f.wipe();   // 幂等
        assertEquals(0, f.size());
    }

    /** 帧和音频一律不落盘、不进日志：相机对话源码里没有任何写文件 / 外部存储 / 媒体库的调用，日志不打原话。 */
    @Test public void noDiskWritesAndNoTranscriptLogs() throws Exception {
        String[] files = {"ui/live/CameraLiveActivity.java", "ui/live/LiveConversation.java", "ui/live/LiveFrame.java",
                "vision/LiveVisionClient.java", "vision/LiveVisionPrompt.java"};
        for (String f : files) {
            String s = new String(Files.readAllBytes(new File(root(), "app/src/com/onesay/assistant/v2/" + f).toPath()), StandardCharsets.UTF_8);
            for (String bad : new String[]{"FileOutputStream", "new File(", "getExternal", "MediaStore", "getCacheDir", "getFilesDir",
                    "DIRECTORY_DCIM", "DIRECTORY_PICTURES", "openFileOutput", "ACTION_IMAGE_CAPTURE"}) {
                assertFalse(f + " 不应出现 " + bad, s.contains(bad));
            }
            for (String line : s.split("\n")) {
                if (!line.contains("Log.")) continue;
                assertFalse(f + " 日志不打用户原话: " + line.trim(), line.matches(".*Log\\.[iwde]\\(.*\\+ *(text|q|question|ans|answer|key)\\b *[+)].*"));
            }
        }
    }

    /** 真机 11-04：一臂远正常说话 RMS < 400 被判无声丢整句 → 相机对话会话门限 150；默认会话不变。 */
    @Test public void cameraAsrSessionUsesLowerVoiceGate() {
        com.onesay.assistant.v2.asr.RealtimeAsrSession def = new com.onesay.assistant.v2.asr.RealtimeAsrSession("wss://x", "k", 700, null, 3000, 0, null);
        def.noteEnergyForTest(300, 4800);
        def.noteEnergyForTest(10, 1600);
        assertFalse("默认 400：300 不算有声", def.hasVoicedAudio());
        com.onesay.assistant.v2.asr.RealtimeAsrSession cam = new com.onesay.assistant.v2.asr.RealtimeAsrSession("wss://x", "k", 700, null, 3000, 0, null)
                .voiceRms(LiveTurnPolicy.ASR_VOICE_RMS);
        cam.noteEnergyForTest(300, 4800);
        assertTrue("相机对话 150：300 算有声", cam.hasVoicedAudio());
        com.onesay.assistant.v2.asr.RealtimeAsrSession quiet = new com.onesay.assistant.v2.asr.RealtimeAsrSession("wss://x", "k", 700, null, 3000, 0, null)
                .voiceRms(LiveTurnPolicy.ASR_VOICE_RMS);
        quiet.noteEnergyForTest(80, 16000);
        assertFalse("底噪仍不算", quiet.hasVoicedAudio());
    }

    /** 真机 11-04：release 云端朗读默认关 + 系统 TTS 无中文 → 哑；相机对话走 speakLive（云端不看开关），状态栏留着给灵动岛。 */
    @Test public void speaksViaCloudAndKeepsStatusBarForIsland() throws Exception {
        String conv = read("app/src/com/onesay/assistant/v2/ui/live/LiveConversation.java");
        assertTrue(conv.contains(".speakLive(text, LiveVisionPrompt.SPOKEN_MAX)"));
        assertFalse(conv.contains("speakProactive"));
        String tts = read("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        assertTrue(tts.contains("if (trySpeakCloud(text, true, true)) return;"));
        assertTrue(tts.contains("if (!cfg.isEnabled() && !ignoreSwitch) return false;"));
        String act = read("app/src/com/onesay/assistant/v2/ui/live/CameraLiveActivity.java");
        assertFalse("不能 FLAG_FULLSCREEN（岛会按全屏应用隐藏）", act.contains("LayoutParams.FLAG_FULLSCREEN"));
        assertFalse(act.contains("SYSTEM_UI_FLAG_FULLSCREEN"));
        String mf = read("app/AndroidManifest.xml");
        int i = mf.indexOf("ui.live.CameraLiveActivity");
        assertTrue(i > 0);
        assertFalse(mf.substring(i, mf.indexOf("/>", i)).contains("Fullscreen"));
    }

    /** 真机 11-04：外放播报 0.7 s 就被自己的回声「能量打断」。相机对话参数：出声后标定 800 ms，回声 ×3.5、≥2000、连续 4 帧。 */
    @Test public void cameraBargeDetectorIgnoresOwnEchoButCatchesVoice() {
        com.onesay.assistant.v2.wake.BargeInDetector b = new com.onesay.assistant.v2.wake.BargeInDetector(LiveTurnPolicy.BARGE_ABS_MIN, 4f,
                LiveTurnPolicy.BARGE_ECHO_RATIO, LiveTurnPolicy.BARGE_MIN_FRAMES, LiveTurnPolicy.BARGE_CALIBRATE_MS);
        b.start(0, 120f);
        long t = 0;
        for (int i = 0; i < 8; i++, t += 100) assertFalse("标定期", b.offer(t, 1500f));
        for (int i = 0; i < 30; i++, t += 100) assertFalse("回声起伏不打断", b.offer(t, 1500f + (i % 5) * 400f));
        boolean fired = false;
        for (int i = 0; i < 4; i++, t += 100) fired = b.offer(t, 12000f);
        assertTrue("真开口（远高于回声）打断", fired);
    }

    @Test public void bargeIsArmedOnlyAfterAudibleSpeech() throws Exception {
        String conv = read("app/src/com/onesay/assistant/v2/ui/live/LiveConversation.java");
        assertTrue(conv.contains("TtsSpeaker.lastAudibleAt() >= mSpeakAt"));
        String tts = read("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        assertTrue(tts.contains("sAudibleAt = android.os.SystemClock.elapsedRealtime();"));
    }

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), StandardCharsets.UTF_8);
    }

    private static File root() {
        String r = System.getProperty("bixby.root");
        return new File(r == null ? "." : r);
    }
}
