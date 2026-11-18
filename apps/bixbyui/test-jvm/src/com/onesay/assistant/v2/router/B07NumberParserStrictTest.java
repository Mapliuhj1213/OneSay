package com.onesay.assistant.v2.router;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B07：口语数字严格解析（t20k router.numberparser 18 条）。 */
public class B07NumberParserStrictTest {

    private static void eq(String s, Long want) {
        assertEquals(s, want, NumberParser.parse(s));
    }

    @Test public void colloquial() {
        eq("一百二", 120L); eq("二百五", 250L); eq("三千五", 3500L); eq("三万五", 35000L); eq("一亿", 100000000L);
        eq("一百零二", 102L); eq("一万二千三百四十五", 12345L); eq("两百", 200L); eq("壹佰贰拾", 120L);
        eq("百", 100L); eq("千", 1000L); eq("万", 10000L); eq("一二三", 123L); eq("零五", 5L); eq("〇八", 8L);
        eq("十一", 11L); eq("三十", 30L); eq("五十秒", 50L); eq("八点", 8L); eq("设成三十", 30L); eq("２０", 20L); eq("20 ", 20L);
        eq("-5", -5L); eq("负五", -5L);
    }

    @Test public void looseOnlyStripsFillers() {
        assertEquals(Long.valueOf(5), NumberParser.parseLoose("5哈"));
        assertEquals(Long.valueOf(7), NumberParser.parseLoose("喂7"));
        assertEquals(Long.valueOf(50), NumberParser.parseLoose("50％"));
        assertNull(NumberParser.parseLoose("跟刚才一样"));
        assertNull(NumberParser.parseLoose("一半"));
        assertNull(NumberParser.parse("⑤"));
    }

    @Test public void invalid() {
        for (String s : new String[]{"1.5", "一点五", "半", "一半", "几", "", "abc", "99999999999999999999", "九千九百九十九万九千九百九十九亿",
                "十十", "二十几", "三四", "五六十", "1分30", "跟刚才一样"}) {
            assertNull(s, NumberParser.parse(s));
        }
    }
}
