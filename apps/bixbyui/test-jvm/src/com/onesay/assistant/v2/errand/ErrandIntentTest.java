package com.onesay.assistant.v2.errand;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ lane C3：跑腿意图识别。 */
public class ErrandIntentTest {

    private static ErrandIntent p(String s) {
        return ErrandIntent.parse(s);
    }

    @Test
    public void research_triggers() {
        ErrandIntent a = p("帮我调研一下国产扫地机器人");
        assertEquals(ErrandIntent.Kind.RESEARCH, a.kind);
        assertTrue(a.deep);
        assertEquals("国产扫地机器人", a.query);

        ErrandIntent b = p("去网上查一下 Note 8 怎么换电池");
        assertEquals(ErrandIntent.Kind.RESEARCH, b.kind);
        assertFalse(b.deep);
        assertTrue(b.query, b.query.contains("Note 8"));
        assertTrue(b.query, b.query.contains("换电池"));

        assertEquals(ErrandIntent.Kind.RESEARCH, p("对比一下小米15和vivo X200的续航").kind);
    }

    @Test
    public void plain_questions_are_not_errands() {
        // 单点问句仍走 L2 问答（先搜再答），不抢
        assertFalse(p("比特币多少钱").isErrand());
        assertFalse(p("明天天气怎么样").isErrand());
        assertFalse(p("总结一下").isErrand());
        assertFalse(p("打开设置").isErrand());
        assertFalse(p("帮我").isErrand());
        assertFalse(p("调研一下").isErrand());   // 没有对象
    }

    @Test
    public void read_link() {
        ErrandIntent a = p("帮我看看这个帖子 https://www.v2ex.com/t/1214989 讲了什么");
        assertEquals(ErrandIntent.Kind.READ, a.kind);
        assertEquals("https://www.v2ex.com/t/1214989", a.url);

        ErrandIntent b = p("读一下 sspai.com/post/12345");
        assertEquals(ErrandIntent.Kind.READ, b.kind);
        assertEquals("https://sspai.com/post/12345", b.url);
    }

    @Test
    public void shop_triggers_and_sites() {
        ErrandIntent a = p("帮我把 AirPods Pro 2 加入购物车");
        assertEquals(ErrandIntent.Kind.SHOP, a.kind);
        assertSame(ErrandIntent.JD, a.site);   // 没点名 → 京东网页版
        assertEquals("AirPods Pro 2", a.query.replace("把 ", "").trim());

        ErrandIntent b = p("在淘宝上搜一下 USB-C 数据线");
        assertEquals(ErrandIntent.Kind.SHOP, b.kind);
        assertSame(ErrandIntent.TAOBAO, b.site);
        assertTrue(b.query, b.query.contains("USB-C 数据线"));

        ErrandIntent c = p("比价一下戴森吹风机");
        assertEquals(ErrandIntent.Kind.SHOP, c.kind);
        assertTrue(c.query.contains("戴森吹风机"));
    }

    @Test
    public void third_party_app_automation_is_refused() {
        // 用户 10-22：不做第三方 App 自动化 —— 点名了 App 就不接（也不偷换成网页）
        assertFalse(p("在淘宝App里帮我加购物车 数据线").isErrand());
        assertFalse(p("用京东客户端帮我买牙膏").isErrand());
        assertFalse(p("微信里帮我查一下资料").isErrand());
        assertFalse(p("打开美团app帮我比价").isErrand());
    }

    @Test
    public void site_search_url_is_encoded() {
        assertEquals("https://search.jd.com/Search?enc=utf-8&keyword=USB-C%20%E6%95%B0%E6%8D%AE%E7%BA%BF",
                ErrandIntent.JD.searchUrl("USB-C 数据线"));
        assertTrue(ErrandIntent.TAOBAO.searchUrl("a b").startsWith("https://s.taobao.com/search?q=a%20b"));
    }

    /**
     * ★ LC-3： C3-1 的例句要真能进跑腿。「去网上查一下 / 帮我调研一下」→ RESEARCH；
     * 「帮我查一下 X 的最新消息」是单点问句 → NONE（按设计交给 L0 browser.search / L2，不改规则）。
     */
    @Test
    public void lc3_doc_examples_match_implementation() throws Exception {
        assertEquals(ErrandIntent.Kind.RESEARCH, ErrandIntent.parse("去网上查一下三星Note8的最新消息").kind);
        assertEquals(ErrandIntent.Kind.RESEARCH, ErrandIntent.parse("帮我调研一下三星Note8的最新消息").kind);
        assertEquals(ErrandIntent.Kind.NONE, ErrandIntent.parse("帮我查一下三星Note8的最新消息").kind);
        assertEquals(ErrandIntent.Kind.NONE, ErrandIntent.parse("帮我查一下明天天气").kind);
    }
}
