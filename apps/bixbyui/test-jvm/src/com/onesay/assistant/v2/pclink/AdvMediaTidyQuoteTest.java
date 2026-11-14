package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [2026-11-14 已修，原「已知缺陷 P2」] PcMedia.tidy 从分享文字里取链接时，结束符只排除了 ，。！？、；）】」 和 ASCII 引号，中文分享里常见的
 * 弯引号 ” ’、书名号 》、』 不在里面 —— 链接被带上尾巴（https://a.com/v”），而 checkUrl 不拦非 ASCII，于是电脑端拿错的地址去下载。
 * 期望（注释：「一段文字里的第一个 http(s) 链接」）：这些标点不算链接的一部分。
 */
@RunWith(Parameterized.class)
public class AdvMediaTidyQuoteTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"“https://a.com/v”"}, {"‘https://a.com/v’"}, {"《https://a.com/v》"}, {"『https://a.com/v』"}, {"〈https://a.com/v〉"}});
    }

    private final String text;

    public AdvMediaTidyQuoteTest(String text) {
        this.text = text;
    }

    @Test public void stripsCjkQuotes() {
        assertEquals("https://a.com/v", PcMedia.tidy(text));
    }
}
