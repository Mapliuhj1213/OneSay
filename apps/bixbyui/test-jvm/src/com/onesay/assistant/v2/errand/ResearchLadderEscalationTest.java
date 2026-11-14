package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.junit.Test;

import java.util.Arrays;

import static com.onesay.assistant.v2.errand.ResearchLadderTest.*;
import static org.junit.Assert.*;

/**
 * ★ lane C3-1 补检：Tavily（search → extract）不够用时，确实依次升级到内置 WebView、再到默认浏览器；
 * 并守住 ErrandHooks 里真把后两档接进了升级链（C3-1 提交时只接了前两档，C3-2 / C3-4 补上）。
 */
public class ResearchLadderEscalationTest {

    static final class OffBrowser implements ResearchLadder.Browser {
        int calls;

        @Override public boolean available() {
            return false;
        }

        @Override public BrowseResult browse(ErrandIntent intent, String start, ResearchLadder.Progress p) {
            calls++;
            return BrowseResult.failed("不该被调用");
        }
    }

    public static String src(String rel) throws Exception {
        java.io.File dir = com.onesay.assistant.v2.testutil.TestRoot.start(ResearchLadderEscalationTest.class);
        for (int i = 0; i < 6 && dir != null; i++) {
            java.io.File f = new java.io.File(dir, "app/src/" + rel);
            if (f.exists()) return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        throw new java.io.FileNotFoundException(rel);
    }

    @Test
    public void thin_search_and_thin_extract_escalate_to_webview() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com/x", "短"));
        FakeExtract e = new FakeExtract(new TavilyExtractProvider.Page("https://a.com/x", "太短", null));
        FakeBrowser b = new FakeBrowser(new BrowseResult(BrowseResult.Status.DONE, "网页里说 Y。", "https://a.com/x", null, null, 3));
        FakeExternal x = new FakeExternal();
        ResearchLadder.Outcome o = new ResearchLadder(s, e, b, x, ECHO).run(ErrandIntent.parse("去网上查一下 X"), null);
        assertEquals(1, s.calls);
        assertEquals(1, e.calls);
        assertEquals(1, b.calls);
        assertNull("WebView 完成就不再开默认浏览器", x.opened);
        assertEquals(ResearchLadder.Tier.WEBVIEW, o.tier);
        assertEquals(Arrays.asList(ResearchLadder.Tier.SEARCH, ResearchLadder.Tier.EXTRACT, ResearchLadder.Tier.WEBVIEW), o.tried);
        assertTrue(o.ok);
    }

    @Test
    public void tavily_unavailable_still_reaches_webview_via_bing_start_url() {
        // 没有 Tavily Key：search 返回空、extract 未就绪
        FakeBrowser b = new FakeBrowser(new BrowseResult(BrowseResult.Status.DONE, "答案。", null, null, null, 2));
        ResearchLadder.Outcome o = new ResearchLadder(new FakeSearch(), null, b, new FakeExternal(), ECHO)
                .run(ErrandIntent.parse("去网上查一下 X"), null);
        assertEquals(1, b.calls);
        assertTrue(b.startUrl, b.startUrl.startsWith("https://cn.bing.com/search?q="));
        assertEquals(ResearchLadder.Tier.WEBVIEW, o.tier);
    }

    @Test
    public void webview_failed_falls_back_to_browser() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com/x", "短"));
        FakeExtract e = new FakeExtract(new TavilyExtractProvider.Page("https://a.com/x", "太短", null));
        FakeBrowser b = new FakeBrowser(BrowseResult.failed("模型不可用"));
        FakeExternal x = new FakeExternal();
        ResearchLadder.Outcome o = new ResearchLadder(s, e, b, x, ECHO).run(ErrandIntent.parse("去网上查一下 X"), null);
        assertNotNull(x.opened);
        assertEquals(ResearchLadder.Tier.BROWSER, o.tier);
        assertEquals(Arrays.asList(ResearchLadder.Tier.SEARCH, ResearchLadder.Tier.EXTRACT, ResearchLadder.Tier.WEBVIEW, ResearchLadder.Tier.BROWSER), o.tried);
        assertEquals(x.opened, o.handoffUrl);
        assertFalse(o.ok);
    }

    @Test
    public void webview_unavailable_skips_straight_to_browser() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com/x", "短"));
        FakeExternal x = new FakeExternal();
        OffBrowser off = new OffBrowser();
        ResearchLadder.Outcome o = new ResearchLadder(s, null, off, x, ECHO).run(ErrandIntent.parse("去网上查一下 X"), null);
        assertEquals(0, off.calls);
        assertNotNull(x.opened);
        assertEquals(Arrays.asList(ResearchLadder.Tier.SEARCH, ResearchLadder.Tier.BROWSER), o.tried);
    }

    /** 接线守卫：真机的升级链四档都接上了（WebView 由开关 + 模型可用决定，默认浏览器由开关决定）。 */
    @Test
    public void hooks_wire_all_four_tiers() throws Exception {
        String h = src("com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(h.contains("new ResearchLadder(s, e, browser(ctx, id, ch, onIsland, cancel), external(ctx), summarizer(ctx))"));
        assertTrue(h.contains("ErrandSettings.WEBVIEW.on(ctx) && planner.ready()"));
        assertTrue(h.contains("ErrandSettings.BROWSER.on(ctx) && DefaultBrowser.available(ctx)"));
        assertTrue("交给系统解析的默认浏览器", h.contains("DefaultBrowser.launch(ctx, url)"));
    }
}
