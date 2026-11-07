package com.onesay.assistant.v2.wake;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** OneSay 开源版：本地唤醒词默认关、不内置，用户自定义 token 序列并逐个校验。 */
public class WakeWordsTest {
    static final Set<String> TOK = new HashSet<String>(Arrays.asList("HH", "EY1", "W", "AH1", "N", "S", "n", "ǐ", "h", "ǎo"));

    @Test public void offByDefault() {
        assertFalse(WakeWords.ENABLED_DEFAULT);
    }

    @Test public void emptyIsError() {
        assertNotNull(WakeWords.validate("", TOK));
        assertNotNull(WakeWords.validate("   \n  ", null));
    }

    @Test public void validEnglishPhonemes() {
        assertNull(WakeWords.validate("HH EY1 W AH1 N S EY1 @hey_onesay", TOK));
        assertEquals("HH EY1 W AH1 N S EY1 @HEY_ONESAY\n", WakeWords.keywordsFile("HH EY1 W AH1 N S EY1 @hey_onesay"));
    }

    @Test public void unknownTokenNamed() {
        String e = WakeWords.validate("HH EY1 B IH1 K S B IY0", TOK);
        assertNotNull(e);
        assertTrue(e, e.contains("「B」"));
    }

    @Test public void noCommentsOrWeightsAndAutoName() {
        assertNotNull(WakeWords.validate("# comment", null));
        assertNotNull(WakeWords.validate("HH EY1 :9.0 #0.05", null));
        String f = WakeWords.keywordsFile("n ǐ h ǎo\nHH EY1");
        assertEquals("n ǐ h ǎo @WAKE_1\nHH EY1 @WAKE_2\n", f);
        assertFalse(f.contains("#"));
    }

    @Test public void badNameAndTooMany() {
        assertNotNull(WakeWords.validate("HH EY1 @名字", TOK));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WakeWords.MAX_LINES + 1; i++) sb.append("HH EY1\n");
        assertNotNull(WakeWords.validate(sb.toString(), TOK));
    }

    @Test public void stampChangesWithWords() {
        assertNotEquals(WakeWords.stamp("6", "HH EY1"), WakeWords.stamp("6", "HH EY1 W"));
        assertEquals(WakeWords.stamp("6", "HH EY1"), WakeWords.stamp("6", "  HH   EY1  "));
    }

    @Test public void noBixbyWakeWordShipped() throws Exception {
        java.io.File assets = new java.io.File(System.getProperty("bixby.root", "."), "app/assets");
        assertFalse("内置唤醒词表已移除", new java.io.File(assets, "kws_keywords.txt").exists());
        String readme = new String(java.nio.file.Files.readAllBytes(new java.io.File(assets, "kws_keywords.README.txt").toPath()), "UTF-8");
        assertFalse(readme.toUpperCase().contains("BIXBY"));
        assertFalse(readme.contains("比西比"));
    }
}
