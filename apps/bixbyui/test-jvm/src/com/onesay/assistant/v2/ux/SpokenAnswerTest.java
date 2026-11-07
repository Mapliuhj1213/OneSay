package com.onesay.assistant.v2.ux;

import com.onesay.assistant.v2.util.SpeechText;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * [r2/uxcompare] TTS 话术精简：问答只播核心一两句（≤40 字），细节留在卡片上。
 * 对标：小爱"佛山今天多云，23 到 28 度"、Siri「Keep responses as succinct as possible」。
 */
public class SpokenAnswerTest {

    @Test public void answerSpeaksCoreOnly() {
        String weather = "根据检索结果，佛山今天多云，23 到 28 度。东北风 2 级，湿度 70%，紫外线中等，"
                + "早晚稍凉，出门可以带件薄外套。明天转小雨。";
        String s = SpeechText.forAnswer(weather);
        assertEquals("佛山今天多云，23 到 28 度。", s);
        assertTrue(s.length() <= SpeechText.ANSWER_MAX);
    }

    @Test public void shortAnswerUnchanged() {
        assertEquals("现在是下午三点。", SpeechText.forAnswer("好的，现在是下午三点。"));
        assertEquals("当前电量 76%。", SpeechText.forAnswer("当前电量 76%。"));
        assertEquals("", SpeechText.forAnswer(null));
    }

    @Test public void listAnswerKeepsHeadlineAndFirstItems() {
        String s = SpeechText.forAnswer("明天上午有两件事\n09:30 组会\n11:00 取快递\n下午没有安排。");
        assertTrue(s, s.startsWith("明天上午有两件事"));
        assertTrue(s.length() <= SpeechText.ANSWER_MAX + 1);
    }

    @Test public void confirmationsStillUseDefaultBudget() {
        // 执行回执（speakConfirmation）仍按 60 字；问答收紧到 40 字
        assertEquals(60, SpeechText.DEFAULT_MAX);
        assertEquals(40, SpeechText.ANSWER_MAX);
        assertEquals(40, SpeechText.FAILURE_MAX);
    }
}
