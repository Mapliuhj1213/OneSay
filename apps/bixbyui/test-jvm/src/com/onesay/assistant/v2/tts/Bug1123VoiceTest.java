package com.onesay.assistant.v2.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.util.SpeechText;

import org.junit.Test;

import java.io.File;

/**
 * ★ fix/1123-bug2：「我在」/「朗读完整回答」开了没效果。
 *
 * <p>复现（修前）：
 * <ul>
 *   <li>「我在」只认预录的 wake_ack1/2.wav，OneSay 不随包带 → 开关开着也一声不响；</li>
 *   <li>「朗读完整回答」开着，问答仍按 40 字截（只念第一句），听着和关着一样；云端语音没配好时又没有系统朗读兜底 → 整轮没声。</li>
 * </ul>
 */
public class Bug1123VoiceTest {

    static final String LONG = "佛山今天多云，最高二十八度，最低二十三度。下午三点以后有阵雨，出门记得带伞。明天转晴，气温回升到三十度左右，"
            + "紫外线比较强，注意防晒。后天又有一股冷空气过来，最低气温会掉到十八度，早晚温差大，注意加衣服。";

    @Test public void repro_wakeAckAudioNotShipped() {
        File assets = new File(System.getProperty("bixby.root", "."), "app/assets");
        assertTrue("assets 目录在", new File(assets, VoiceTemplate.DONE.asset).exists());
        assertFalse("OneSay 没带「我在」的预录音频（修前就因为这个一声不响）", new File(assets, VoiceTemplate.WAKE.asset).exists());
        assertFalse(new File(assets, VoiceTemplate.WAKE_2.asset).exists());
    }

    @Test public void repro_fullReadUsedToBeCutAt40() {
        String cut = SpeechText.forSpeech(SpeechText.stripLeadFiller(LONG), SpeechText.ANSWER_MAX);
        assertTrue("修前：开了也只念第一句（≤40 字）", cut.length() <= SpeechText.ANSWER_MAX);
    }

    @Test public void fullReadReadsTheWholeAnswer() {
        int max = VoiceTemplate.speechMax(SpeechText.ANSWER_MAX, false, true, true);
        assertEquals(VoiceTemplate.FULL_READ_MAX, max);
        String full = SpeechText.forSpeech(SpeechText.stripLeadFiller(LONG), max);
        assertTrue(full, full.length() > 80);
        assertTrue(full, full.contains("注意加衣服"));
        // 没开 / 主动播报 / 非回答：上限不变
        assertEquals(SpeechText.ANSWER_MAX, VoiceTemplate.speechMax(SpeechText.ANSWER_MAX, false, false, true));
        assertEquals(80, VoiceTemplate.speechMax(80, true, true, true));
        assertEquals(SpeechText.DEFAULT_MAX, VoiceTemplate.speechMax(SpeechText.DEFAULT_MAX, false, true, false));
        assertEquals("本来就更长的上限不缩", 500, VoiceTemplate.speechMax(500, false, true, true));
        // 开着就不走模板（整句念）
        assertFalse(VoiceTemplate.useTemplate(false, true));
        assertTrue(VoiceTemplate.useTemplate(false, false));
    }

    @Test public void wakeAckSource() {
        assertEquals(VoiceTemplate.CueSource.ASSET, VoiceTemplate.cueSource(true, true));
        assertEquals(VoiceTemplate.CueSource.ASSET, VoiceTemplate.cueSource(true, false));
        assertEquals("没音频 → 云端念", VoiceTemplate.CueSource.CLOUD, VoiceTemplate.cueSource(false, true));
        assertEquals(VoiceTemplate.CueSource.NONE, VoiceTemplate.cueSource(false, false));
        assertEquals("我在", VoiceTemplate.WAKE.text);
    }

    @Test public void wiring() {
        String ws = KernelWiring.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertFalse("不再直接播缺失的 wav", ws.contains("playAssetSync(alt ? \"wake_ack2.wav\" : \"wake_ack1.wav\")"));
        assertTrue(ws.contains("playWakeAckSync(alt)"));
        assertTrue("云端念走一次性提示，不进回答状态机", ws.contains("TtsSpeaker.get(this).speakCue(t,"));
        assertTrue("长按掐断也停云端", ws.contains("stopCueCloud()"));
        String tts = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        assertTrue(tts.contains("VoiceTemplate.speechMax(max, force, fullRead, tpl != null)"));
        int fb = tts.indexOf("private void speakSystemLocked");
        assertTrue("整句念不出来退回模板", fb > 0 && tts.indexOf("playTemplateLocked(fb)", fb) > fb);
        // 云端提示不报回答进度（不 fireStart / fireDone）
        int a = tts.indexOf("private boolean speakCueCloudLocked");
        int b = tts.indexOf("/**", a);
        String body = tts.substring(a, b);
        assertFalse(body.contains("fireStart") || body.contains("fireDone"));
    }
}
