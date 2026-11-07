package com.onesay.assistant.v2.util;

import org.junit.Test;

import static org.junit.Assert.*;

/** P1-10：语音播报文本整形。 */
public class SpeechTextTest {

    @Test public void cleansMarkdownEmojiAndSymbols() {
        assertEquals("亮度已调到 120", SpeechText.forSpeech("**亮度**已调到 120 ✓", 60));
        assertEquals("已打开 微信", SpeechText.forSpeech("已打开\n\n微信 😀", 60));
        assertEquals("", SpeechText.forSpeech("  ✓ ✕ ", 60));
        assertEquals("", SpeechText.forSpeech(null, 60));
    }

    @Test public void longTextCutAtSentenceEnd() {
        String t = "苏州园林是中国古典园林的代表。拙政园、留园、网师园和环秀山庄被列入世界遗产名录，"
                + "园林讲究移步换景，建筑、山水、花木融为一体，体现了文人写意山水的审美。";
        String s = SpeechText.forSpeech(t, 60);
        assertEquals("苏州园林是中国古典园林的代表。", s);
        String noStop = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        assertEquals("一二三四五六七八九十等", SpeechText.forSpeech(noStop, 10));
    }
}
