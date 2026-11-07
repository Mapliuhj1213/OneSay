package com.onesay.assistant.v2.search;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * [r2/uxcompare] 追问保留上下文：省略式追问拼回完整检索词。
 * 对标：小爱「那明天呢」继承城市槽位、天猫精灵前置意图、小艺连续对话。
 */
public class FollowUpQueryTest {

    @Test public void weatherDateFollowUpReplacesDate() {
        assertEquals("佛山明天天气怎么样", FollowUpQuery.resolve("那明天呢", "佛山今天天气怎么样"));
        assertEquals("佛山后天天气怎么样", FollowUpQuery.resolve("后天呢？", "佛山今天天气怎么样"));
        assertEquals("佛山周六天气怎么样", FollowUpQuery.resolve("那周六呢", "佛山今天天气怎么样"));
        // 上一问没日期 → 加在前面
        assertEquals("明天 佛山天气怎么样", FollowUpQuery.resolve("那明天呢", "佛山天气怎么样"));
        // 只有「那」开头、没有「呢」也认日期
        assertEquals("佛山明天会下雨吗", FollowUpQuery.resolve("那明天", "佛山今天会下雨吗"));
    }

    @Test public void weatherPlaceFollowUpReplacesPlace() {
        assertEquals("上海今天天气怎么样", FollowUpQuery.resolve("那上海呢", "佛山今天天气怎么样"));
        assertEquals("广州今天天气怎么样", FollowUpQuery.resolve("那广州的天气呢", "佛山今天天气怎么样"));
        // 上一问没地名（靠常用城市补）→ 新地名加在前面，WeatherQuery 看到有地名就不再补常用城市
        String s = FollowUpQuery.resolve("那上海呢", "今天天气怎么样");
        assertEquals("上海 今天天气怎么样", s);
        assertTrue(WeatherQuery.hasPlace(s, "佛山"));
        // 不在城市表里的 2~4 字地名也认
        assertEquals("婺源今天天气怎么样", FollowUpQuery.resolve("那婺源呢", "佛山今天天气怎么样"));
    }

    @Test public void weatherOtherFollowUpAppends() {
        assertEquals("佛山今天天气怎么样 穿什么", FollowUpQuery.resolve("那穿什么呢", "佛山今天天气怎么样"));
    }

    @Test public void chainResolvesThroughHistory() {
        assertEquals("佛山后天天气怎么样",
                FollowUpQuery.forSearch("后天呢", Arrays.asList("佛山今天天气怎么样", "那明天呢")));
        assertEquals("深圳后天天气怎么样",
                FollowUpQuery.forSearch("后天呢", Arrays.asList("佛山今天天气怎么样", "那深圳呢")));
        // 中间换了话题 → 从新话题起算
        assertEquals("以太坊 比特币现在多少钱",
                FollowUpQuery.forSearch("那以太坊呢", Arrays.asList("佛山今天天气怎么样", "比特币现在多少钱")));
    }

    @Test public void nonWeatherFollowUpPrependsCore() {
        assertEquals("以太坊 比特币现在多少钱", FollowUpQuery.resolve("那以太坊呢", "比特币现在多少钱"));
    }

    @Test public void untouchedWhenNotAFollowUp() {
        // 完整问句、没有历史、指代（那个…）、动词开头 → 原样
        assertEquals("北京明天天气怎么样", FollowUpQuery.resolve("北京明天天气怎么样", "佛山今天天气怎么样"));
        assertEquals("那明天呢", FollowUpQuery.resolve("那明天呢", ""));
        assertEquals("那明天呢", FollowUpQuery.forSearch("那明天呢", Collections.<String>emptyList()));
        assertEquals("那个文件在哪", FollowUpQuery.resolve("那个文件在哪", "佛山今天天气怎么样"));
        assertEquals("那打开微信", FollowUpQuery.resolve("那打开微信", "佛山今天天气怎么样"));
        assertEquals("为什么天空是蓝色的", FollowUpQuery.resolve("为什么天空是蓝色的", "佛山今天天气怎么样"));
        // 太长的不是省略式追问
        assertEquals("那你能不能顺便告诉我明天要不要带伞呢",
                FollowUpQuery.resolve("那你能不能顺便告诉我明天要不要带伞呢", "佛山今天天气怎么样"));
        // 历史里的回执伪问题不当上一问
        assertEquals("那明天呢", FollowUpQuery.resolve("那明天呢", "[圈选识屏回执] xx"));
        assertNull(FollowUpQuery.resolve(null, "佛山今天天气怎么样"));
    }

    @Test public void wiredIntoBothAskChannels() throws Exception {
        File f = new File("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        if (!f.exists()) f = new File("../app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int n = 0, i = -1;
        while ((i = src.indexOf("searchForPrompt(searchQueryFor(query))", i + 1)) >= 0) n++;
        assertEquals("ask() 与 askStream() 两处检索都走追问补全", 2, n);
        assertTrue(src.contains("FollowUpQuery.forSearch(query, qs)"));
        // 交给模型的仍是原话
        assertTrue(src.contains("+ \"【用户问题】\" + query;"));
    }
}
