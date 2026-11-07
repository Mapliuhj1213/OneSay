package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.wake.UtteranceEndpointer.Action;
import com.onesay.assistant.v2.wake.UtteranceEndpointer.Completeness;

import java.util.Arrays;

import org.junit.Test;

/** ★ r2/input：按文字完整度断句（UtteranceEndpointer）的分类、阈值、每帧裁决与段拼接。 */
public class UtteranceEndpointerTest {

    @Test public void incompleteEndings() {
        String[] in = {"帮我给张三发消息然后", "打开微信，", "我想要", "提醒我", "帮我打开", "把客厅的灯和", "导航到", "查一下明天的天气还有",
                "嗯", "如果明天下雨的话", "打开微信然后？", "先把音量调大再", "发给", "我要去"};
        for (String s : in) assertEquals(s, Completeness.INCOMPLETE, UtteranceEndpointer.classify(s));
    }

    @Test public void completeEndings() {
        String[] in = {"明天天气怎么样？", "现在几点了", "打开蓝牙。", "暂停播放", "继续播放。", "关闭手电筒", "你能帮我吗", "下一首", "算了",
                "不要", "调大音量", "帮我打开微信"};
        for (String s : in) assertEquals(s, Completeness.COMPLETE, UtteranceEndpointer.classify(s));
    }

    @Test public void neutralEndings() {
        String[] in = {"给张三发消息说我晚点到。", "明天早上八点叫我起床", "讲个笑话", "", null, "。"};
        for (String s : in) assertEquals(String.valueOf(s), Completeness.NEUTRAL, UtteranceEndpointer.classify(s));
    }

    @Test public void thresholdsFollowCompleteness() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        assertEquals(1300, e.requiredSilenceMs("打开蓝牙。"));
        assertEquals(2000, e.requiredSilenceMs("讲个笑话。"));
        assertEquals(3200, e.requiredSilenceMs("帮我给张三发消息然后"));
        // 目标：句中停顿 1.5–2 s 内不切断（中性 ≥ 1.5 s；没说完 ≥ 2 s）
        assertTrue(e.neutralMs() >= 1500);
        assertTrue(e.incompleteMs() >= 2000);
    }

    @Test public void thresholdsClampedAndOrdered() {
        UtteranceEndpointer e = new UtteranceEndpointer(100, 50, 99999);
        assertEquals(EndpointDetector.FLOOR_MS, e.completeMs());
        assertTrue(e.neutralMs() >= e.completeMs());
        assertEquals(UtteranceEndpointer.MAX_SILENCE_MS, e.incompleteMs());
    }

    private static UtteranceEndpointer.Input rt(long now, long last, String fin, String partial, boolean speaking) {
        UtteranceEndpointer.Input in = new UtteranceEndpointer.Input();
        in.now = now;
        in.firstSpeechAt = 1000;
        in.lastSpeechAt = last;
        in.speechFrames = 10;
        in.minFrames = 4;
        in.rtOwns = true;
        in.rtSpeaking = speaking;
        in.finals = fin;
        in.partial = partial;
        in.rtActivityAt = last;
        return in;
    }

    @Test public void serverSpeakingNeverEndpoints() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        assertEquals(Action.WAIT, e.decide(rt(20000, 1000, "打开蓝牙。", "", true)).action);
    }

    @Test public void commitOnlyAfterRequiredSilence() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        assertEquals(Action.WAIT, e.decide(rt(3000 + 1300, 3000, "打开蓝牙。", "", false)).action);
        assertEquals(Action.COMMIT_TEXT, e.decide(rt(3000 + 1301, 3000, "打开蓝牙。", "", false)).action);
        assertEquals("没说完：2 s 停顿不切", Action.WAIT, e.decide(rt(3000 + 2000, 3000, "给张三发消息然后", "", false)).action);
        assertEquals(Action.COMMIT_TEXT, e.decide(rt(3000 + 3300, 3000, "给张三发消息然后", "", false)).action);
    }

    @Test public void pendingPartialWaitsForFinal() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        assertEquals(Action.WAIT_FOR_FINAL, e.decide(rt(9000, 3000, "", "讲个笑话", false)).action);
        assertEquals(Action.WAIT_FOR_FINAL, e.decide(rt(9000, 3000, "打开微信，", "然后给张三", false)).action);
    }

    @Test public void noSpeechOrTooFewFramesWaits() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        UtteranceEndpointer.Input in = rt(99999, 1000, "打开蓝牙。", "", false);
        in.speechFrames = 2;
        assertEquals(Action.WAIT, e.decide(in).action);
        in = rt(99999, 0, "", "", false);
        in.firstSpeechAt = 0;
        assertEquals(Action.WAIT, e.decide(in).action);
    }

    @Test public void rtActivityExtendsSilence() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        UtteranceEndpointer.Input in = rt(5000, 3000, "打开蓝牙。", "", false);
        in.rtActivityAt = 4000;   // 服务端 1 s 前还出过中间结果（能量门漏判轻声）
        assertEquals(Action.WAIT, e.decide(in).action);
    }

    @Test public void nonStreamUsesDetectorSilence() {
        UtteranceEndpointer e = UtteranceEndpointer.defaults();
        UtteranceEndpointer.Input in = rt(3000 + 1801, 3000, "", "", false);
        in.rtOwns = false;
        in.noTextSilenceMs = 1800;
        assertEquals(Action.FIRE_NONSTREAM, e.decide(in).action);
        in.now = 3000 + 1700;
        assertEquals(Action.WAIT, e.decide(in).action);
    }

    @Test public void ringAppearsAfterDelayAndFillsAtRequired() {
        assertEquals(0f, UtteranceEndpointer.ringProgress(300, 2000), 1e-6);
        assertEquals(0f, UtteranceEndpointer.ringProgress(400, 2000), 1e-6);
        assertEquals(0.5f, UtteranceEndpointer.ringProgress(1200, 2000), 1e-6);
        assertEquals(1f, UtteranceEndpointer.ringProgress(5000, 2000), 1e-6);
    }

    @Test public void joinSegmentsTurnsInnerFullStopsIntoCommas() {
        assertEquals("打开微信，然后给张三发消息。", UtteranceEndpointer.joinSegments(Arrays.asList("打开微信。", "然后给张三发消息。")));
        assertEquals("明天下雨吗？要带伞", UtteranceEndpointer.joinSegments(Arrays.asList("明天下雨吗？", "要带伞")));
        assertEquals("帮我，查一下", UtteranceEndpointer.joinSegments(Arrays.asList("帮我", "  ", null, "查一下")));
        assertEquals("", UtteranceEndpointer.joinSegments(null));
    }

    @Test public void keyFinishPolicy() {
        // 同一次按键第二条通道（去抖窗内）不当结束
        assertFalse(CaptureSessionPolicy.keyPressFinishes(true, false, false, false, 800, 1500, 10, 4, true));
        assertTrue(CaptureSessionPolicy.keyPressFinishes(true, false, false, false, 3000, 1500, 10, 4, false));
        assertTrue("有流式文字即可", CaptureSessionPolicy.keyPressFinishes(true, false, false, false, 3000, 1500, 0, 4, true));
        assertFalse("还没开口 → 照旧（重新唤醒）", CaptureSessionPolicy.keyPressFinishes(true, false, false, false, 3000, 1500, 2, 4, false));
        assertFalse("窗已关", CaptureSessionPolicy.keyPressFinishes(false, false, false, false, 3000, 1500, 10, 4, true));
        assertFalse("已拿到指令", CaptureSessionPolicy.keyPressFinishes(true, true, false, false, 3000, 1500, 10, 4, true));
        assertFalse("已发云端", CaptureSessionPolicy.keyPressFinishes(true, false, false, true, 3000, 1500, 10, 4, true));
        assertTrue(CaptureSessionPolicy.swallowAfterKeyFinish(1000 + 300, 1000, 1500));
        assertFalse(CaptureSessionPolicy.swallowAfterKeyFinish(1000 + 1600, 1000, 1500));
        assertFalse(CaptureSessionPolicy.swallowAfterKeyFinish(5000, 0, 1500));
    }

    @Test public void renewWindowCoversLongestPause() {
        long base = WakeService.RENEW_IF_SPEECH_WITHIN_MS;   // 编译期常量，不触发 WakeService 初始化
        assertEquals(base, CaptureSessionPolicy.renewWithinMs(base, 1300));
        assertEquals(3700L, CaptureSessionPolicy.renewWithinMs(base, 3200));
        // 停顿 3 s（没说完）时窗到点：仍续期；完整句停 3 s：不续
        assertTrue(CaptureSessionPolicy.shouldRenew(10000, 1000, 2100, 10000, 7000,
                CaptureSessionPolicy.renewWithinMs(base, 3200), WakeService.CAPTURE_MAX_MS));
        assertFalse(CaptureSessionPolicy.shouldRenew(10000, 1000, 2100, 10000, 7000,
                CaptureSessionPolicy.renewWithinMs(base, 1300), WakeService.CAPTURE_MAX_MS));
        assertFalse("触顶不续", CaptureSessionPolicy.shouldRenew(31500, 1000, 2100, 31500, 31000, 9999, WakeService.CAPTURE_MAX_MS));
    }

    @Test public void maxDurationRaisedTo30s() {
        assertEquals(30000L, WakeService.CAPTURE_MAX_MS);
        assertEquals(30, com.onesay.assistant.v2.asr.PcmWindowBuffer.CAPTURE_MAX_SECONDS);
    }
}
