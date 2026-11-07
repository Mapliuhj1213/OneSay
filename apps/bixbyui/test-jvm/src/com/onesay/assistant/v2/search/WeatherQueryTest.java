package com.onesay.assistant.v2.search;

import static org.junit.Assert.*;
import org.junit.Test;

public class WeatherQueryTest {
    @Test public void noCityConfiguredKeepsQuery() {
        assertEquals("今天天气怎么样", WeatherQuery.withHomeCity("今天天气怎么样", ""));
        assertEquals("今天天气怎么样", WeatherQuery.withHomeCity("今天天气怎么样", null));
        assertEquals("今天天气怎么样", WeatherQuery.withHomeCity("今天天气怎么样", "   "));
    }
    @Test public void weatherWithoutPlaceGetsCity() {
        assertEquals("测试城 今天天气怎么样", WeatherQuery.withHomeCity("今天天气怎么样", "测试城"));
        assertEquals("测试城 明天要带伞吗", WeatherQuery.withHomeCity("明天要带伞吗", "测试城"));
        assertEquals("测试城 外面冷不冷", WeatherQuery.withHomeCity("外面冷不冷", " 测试城 "));
    }
    @Test public void explicitPlaceIsKept() {
        assertEquals("北京明天下雨吗", WeatherQuery.withHomeCity("北京明天下雨吗", "测试城"));
        assertEquals("佛山市今天气温", WeatherQuery.withHomeCity("佛山市今天气温", "测试城"));
        assertEquals("测试城今天天气", WeatherQuery.withHomeCity("测试城今天天气", "测试城"));
        assertEquals("东京天气", WeatherQuery.withHomeCity("东京天气", "测试城"));
    }
    @Test public void nonWeatherIsKept() {
        assertEquals("比特币多少钱", WeatherQuery.withHomeCity("比特币多少钱", "测试城"));
        assertNull(WeatherQuery.withHomeCity(null, "测试城"));
    }
}
