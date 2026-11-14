package com.onesay.assistant.v2.errand;


import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ lane C3：Tavily extract 请求 / 响应 + 结果卡排版。 */
public class TavilyExtractAndReportTest {

    @Test
    public void request_body_filters_and_caps_urls() throws Exception {
        String b = TavilyExtractProvider.requestBody(Arrays.asList("https://a.com", "javascript:alert(1)", "https://a.com", "ftp://x",
                "http://b.com", "https://c.com", "https://d.com", "https://e.com", "https://f.com"), "问题");
        JSONObject o = new JSONObject(b);
        assertEquals(TavilyExtractProvider.MAX_URLS, o.getJSONArray("urls").length());
        assertEquals("https://a.com", o.getJSONArray("urls").getString(0));
        assertEquals("http://b.com", o.getJSONArray("urls").getString(1));
        assertEquals("问题", o.getString("query"));
        assertEquals(3, o.getInt("chunks_per_source"));
        assertEquals("basic", o.getString("extract_depth"));
        assertFalse(new JSONObject(TavilyExtractProvider.requestBody(Arrays.asList("https://a.com"), null)).has("query"));
    }

    @Test
    public void parse_ok_and_failed_results() {
        String json = "{\"results\":[{\"url\":\"https://a.com\",\"raw_content\":\"正文\",\"images\":[]},{\"url\":\"https://e.com\",\"raw_content\":\"\"}],"
                + "\"failed_results\":[{\"url\":\"https://b.com\",\"error\":\"Failed to retrieve content\"}],\"response_time\":0.5}";
        List<TavilyExtractProvider.Page> p = TavilyExtractProvider.parse(json);
        assertEquals(3, p.size());
        assertTrue(p.get(0).ok());
        assertEquals("正文", p.get(0).content);
        assertFalse(p.get(1).ok());
        assertEquals("https://b.com", p.get(2).url);
        assertEquals("Failed to retrieve content", p.get(2).error);
        assertTrue(TavilyExtractProvider.parse("not json").isEmpty());
        assertTrue(TavilyExtractProvider.parse(null).isEmpty());
    }

    @Test
    public void report_numbers_sources_and_splits_headline() {
        List<ResearchLadder.Source> src = new ArrayList<ResearchLadder.Source>();
        src.add(new ResearchLadder.Source("第一篇", "https://www.a.com/x", "t", ResearchLadder.Tier.SEARCH));
        src.add(new ResearchLadder.Source("", "https://b.cn/y", "t", ResearchLadder.Tier.SEARCH));
        List<ResearchLadder.Tier> tried = Arrays.asList(ResearchLadder.Tier.SEARCH);
        ResearchLadder.Outcome o = newOutcome(true, ResearchLadder.Tier.SEARCH, tried, src, "结论是 A 更好[1]。\n- 续航长 [2]\n- 价格低", null, "");
        ErrandReport.Card c = ErrandReport.card(ErrandIntent.parse("对比一下 A 和 B"), o, false);
        assertEquals("结论是 A 更好", c.headline);
        assertEquals("续航长", c.detail);
        assertEquals("搜索摘要 · 2 个来源", c.meta);
        assertTrue(c.full, c.full.contains("\n1. 第一篇 · www.a.com"));
        assertTrue(c.full, c.full.contains("\n2. b.cn · b.cn"));
        assertTrue(c.full.contains("打开第二个"));
    }

    @Test
    public void report_handoff_and_cancel() {
        ResearchLadder.Outcome o = newOutcome(false, ResearchLadder.Tier.BROWSER, Arrays.asList(ResearchLadder.Tier.WEBVIEW, ResearchLadder.Tier.BROWSER),
                new ArrayList<ResearchLadder.Source>(), "", "https://m.x.com/p", DefaultBrowser.NOTE);
        ErrandReport.Card c = ErrandReport.card(ErrandIntent.parse("读一下 x.com/p"), o, false);
        assertFalse(c.ok);
        assertEquals("m.x.com", c.detail);
        assertTrue(c.headline.startsWith("已在浏览器"));
        assertEquals("已停止跑腿", ErrandReport.card(ErrandIntent.parse("读一下 x.com/p"), o, true).headline);
    }

    /** Outcome 构造器是包内可见的：测试与它同包。 */
    static ResearchLadder.Outcome newOutcome(boolean ok, ResearchLadder.Tier tier, List<ResearchLadder.Tier> tried, List<ResearchLadder.Source> src,
                                             String answer, String handoff, String note) {
        return new ResearchLadder.Outcome(ok, tier, tried, src, answer, handoff, null, note);
    }

    static ResearchLadder.Outcome newOutcomeWithStatus(BrowseResult.Status st, String handoff, String answer) {
        return new ResearchLadder.Outcome(false, ResearchLadder.Tier.WEBVIEW, Arrays.asList(ResearchLadder.Tier.WEBVIEW),
                new ArrayList<ResearchLadder.Source>(), answer, handoff, st, CheckoutGuardNote.NOTE);
    }

    static final class CheckoutGuardNote {
        static final String NOTE = com.onesay.assistant.v2.errand.web.CheckoutGuard.STOP_CHECKOUT;
    }
}
