package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** NumberParser：口语中文数字 → 数值（BUG-025 / BUG-026 回归）。 */
public class NumberParserTest {

    private static void eq(long expected, String in) {
        assertEquals("parse(\"" + in + "\")", Long.valueOf(expected), NumberParser.parse(in));
    }

    @Test public void arabicDigits() {
        eq(50, "50");
        eq(50, "调到50");
        eq(30, "设成30秒");
        eq(80, "设为80");
    }

    @Test public void chineseDigits() {
        eq(8, "八");
        eq(12, "十二");
        eq(30, "三十");
        eq(120, "一百二十");
        eq(2, "两");
        eq(20000, "两万");
        eq(40, "四十分钟");
    }

    @Test public void notANumber() {
        assertNull(NumberParser.parse(null));
        assertNull(NumberParser.parse(""));
        assertNull(NumberParser.parse("   "));
        assertNull(NumberParser.parse("蓝牙"));
    }
}
