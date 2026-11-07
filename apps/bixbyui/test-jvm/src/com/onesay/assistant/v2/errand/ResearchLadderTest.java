package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** ★ lane C3：升级链 Tavily search → extract → WebView → 三星浏览器。 */
public class ResearchLadderTest {

    static String words(int n) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) sb.append("这是一段有内容的资料文字。");
        return sb.substring(0, n);
    }

    static final class FakeSearch implements ResearchLadder.Searcher {
        final List<SearchResult> out;
        int calls;

        FakeSearch(SearchResult... r) {
            out = Arrays.asList(r);
        }

        @Override public List<SearchResult> search(String q) {
            calls++;
            return out;
        }
    }

    static final class FakeExtract implements ResearchLadder.Extractor {
        final List<TavilyExtractProvider.Page> out;
        List<String> asked;
        int calls;

        FakeExtract(TavilyExtractProvider.Page... p) {
            out = Arrays.asList(p);
        }

        @Override public boolean ready() {
            return true;
        }

        @Override public List<TavilyExtractProvider.Page> extract(List<String> urls, String q) {
            calls++;
            asked = new ArrayList<String>(urls);
            return out;
        }
    }

    static final class FakeBrowser implements ResearchLadder.Browser {
        final BrowseResult r;
        String startUrl;
        int calls;

        FakeBrowser(BrowseResult r) {
            this.r = r;
        }

        @Override public boolean available() {
            return true;
        }

        @Override public BrowseResult browse(ErrandIntent intent, String start, ResearchLadder.Progress p) {
            calls++;
            startUrl = start;
            return r;
        }
    }

    static final class FakeExternal implements ResearchLadder.External {
        String opened;
        boolean desktop;

        @Override public boolean available() {
            return true;
        }

        @Override public boolean open(String url) {
            opened = url;
            return true;
        }

        @Override public boolean openedDesktop() {
            return desktop;
        }
    }

    static final ResearchLadder.Summarizer ECHO = new ResearchLadder.Summarizer() {
        @Override public String answer(ErrandIntent intent, String material) {
            return "回答。基于 " + material.length() + " 字";
        }
    };

    @Test
    public void search_enough_returns_without_extract_or_browser() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", words(200)), new SearchResult("B", "https://b.com", words(200)));
        FakeExtract e = new FakeExtract();
        FakeBrowser b = new FakeBrowser(BrowseResult.failed("x"));
        ResearchLadder.Outcome o = new ResearchLadder(s, e, b, null, ECHO).run(ErrandIntent.parse("去网上查一下 Note 8 换电池"), null);
        assertTrue(o.ok);
        assertEquals(ResearchLadder.Tier.SEARCH, o.tier);
        assertEquals(0, e.calls);
        assertEquals(0, b.calls);
        assertEquals(2, o.sources.size());
        assertTrue(o.answer.startsWith("回答"));
    }

    @Test
    public void thin_search_escalates_to_extract() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", "短"), new SearchResult("B", "https://b.com", words(60)));
        FakeExtract e = new FakeExtract(new TavilyExtractProvider.Page("https://a.com", words(900), null));
        FakeBrowser b = new FakeBrowser(BrowseResult.failed("x"));
        ResearchLadder.Outcome o = new ResearchLadder(s, e, b, null, ECHO).run(ErrandIntent.parse("去网上查一下 Note 8 换电池"), null);
        assertTrue(o.ok);
        assertEquals(ResearchLadder.Tier.EXTRACT, o.tier);
        assertEquals(Arrays.asList("https://a.com", "https://b.com"), e.asked);
        assertEquals(0, b.calls);
        assertEquals(Arrays.asList(ResearchLadder.Tier.SEARCH, ResearchLadder.Tier.EXTRACT), o.tried);
    }

    @Test
    public void deep_research_needs_more_than_two_snippets() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", words(400)), new SearchResult("B", "https://b.com", words(400)));
        assertFalse(ResearchLadder.searchEnough(ErrandIntent.parse("帮我调研一下国产扫地机器人"), s.out));
        assertTrue(ResearchLadder.searchEnough(ErrandIntent.parse("去网上查一下扫地机器人"), s.out));
    }

    @Test
    public void given_url_is_extracted_first_and_must_itself_be_usable() {
        ErrandIntent in = ErrandIntent.parse("帮我看看这个帖子 https://www.v2ex.com/t/1 讲了什么");
        FakeSearch s = new FakeSearch(new SearchResult("X", "https://x.com", words(400)), new SearchResult("Y", "https://y.com", words(400)));
        // 搜索再多也不够：给了链接就要读那一页
        assertFalse(ResearchLadder.searchEnough(in, s.out));
        FakeExtract e = new FakeExtract(new TavilyExtractProvider.Page("https://www.v2ex.com/t/1", words(500), null));
        ResearchLadder.Outcome o = new ResearchLadder(s, e, null, null, ECHO).run(in, null);
        assertEquals("https://www.v2ex.com/t/1", e.asked.get(0));
        assertEquals(ResearchLadder.Tier.EXTRACT, o.tier);
        assertTrue(o.ok);
    }

    @Test
    public void login_wall_in_extract_escalates_to_webview_with_pc_start_url() {
        ErrandIntent in = ErrandIntent.parse("帮我看看这个帖子 https://www.zhihu.com/question/1 讲了什么");
        FakeExtract e = new FakeExtract(new TavilyExtractProvider.Page("https://www.zhihu.com/question/1", "请先登录后查看完整内容 " + words(250), null));
        List<String[]> src = new ArrayList<String[]>();
        src.add(new String[]{"知乎问题", "https://www.zhihu.com/question/1"});
        FakeBrowser b = new FakeBrowser(new BrowseResult(BrowseResult.Status.DONE, "页面说了 X。", "https://www.zhihu.com/question/1", null, src, 4));
        ResearchLadder.Outcome o = new ResearchLadder(new FakeSearch(), e, b, null, ECHO).run(in, null);
        assertEquals(1, b.calls);
        assertEquals("https://www.zhihu.com/question/1", b.startUrl);
        assertEquals(ResearchLadder.Tier.WEBVIEW, o.tier);
        assertTrue(o.ok);
        assertEquals("页面说了 X。", o.answer);
    }

    @Test
    public void shop_skips_search_and_goes_straight_to_webview_site_search() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", words(500)), new SearchResult("B", "https://b.com", words(500)),
                new SearchResult("C", "https://c.com", words(500)));
        FakeExtract e = new FakeExtract();
        FakeBrowser b = new FakeBrowser(new BrowseResult(BrowseResult.Status.CHECKOUT, "已加购", "https://trade.jd.com/x", "已到结算页", null, 9));
        ResearchLadder.Outcome o = new ResearchLadder(s, e, b, null, ECHO).run(ErrandIntent.parse("在京东上买 USB-C 数据线"), null);
        assertEquals(0, s.calls);
        assertEquals(0, e.calls);
        assertTrue(b.startUrl, b.startUrl.startsWith("https://search.jd.com/Search?"));
        assertEquals(BrowseResult.Status.CHECKOUT, o.browseStatus);
        assertEquals("https://trade.jd.com/x", o.handoffUrl);
        assertFalse(o.ok);
    }

    @Test
    public void takeover_is_not_sent_to_samsung() {
        FakeExternal x = new FakeExternal();
        FakeBrowser b = new FakeBrowser(new BrowseResult(BrowseResult.Status.TAKEOVER, null, "https://passport.jd.com/login", "需要登录", null, 2));
        ResearchLadder.Outcome o = new ResearchLadder(null, null, b, x, ECHO).run(ErrandIntent.parse("在京东上买 牙刷"), null);
        assertNull(x.opened);
        assertEquals(BrowseResult.Status.TAKEOVER, o.browseStatus);
        assertEquals("https://passport.jd.com/login", o.handoffUrl);
    }

    @Test
    public void blocked_webview_falls_back_to_samsung_last() {
        FakeExternal x = new FakeExternal();
        FakeBrowser b = new FakeBrowser(BrowseResult.blocked("https://m.example.com/p", "站点拒绝 WebView"));
        ErrandIntent in = ErrandIntent.parse("读一下 example.com/p");
        ResearchLadder.Outcome o = new ResearchLadder(new FakeSearch(), new FakeExtract(), b, x, ECHO).run(in, null);
        assertEquals("https://m.example.com/p", x.opened);
        assertEquals(ResearchLadder.Tier.SAMSUNG, o.tier);
        assertEquals(Arrays.asList(ResearchLadder.Tier.EXTRACT, ResearchLadder.Tier.WEBVIEW, ResearchLadder.Tier.SAMSUNG), o.tried);
        assertEquals("https://m.example.com/p", o.handoffUrl);
    }

    /** ★ LC-4：Intent 没带桌面参数（默认配置）→ 文案不能说「桌面版」。 */
    @Test
    public void samsung_note_plain_without_desktop_extras() {
        FakeExternal x = new FakeExternal();
        FakeBrowser b = new FakeBrowser(BrowseResult.blocked("https://m.example.com/p", "站点拒绝 WebView"));
        ResearchLadder.Outcome o = new ResearchLadder(new FakeSearch(), new FakeExtract(), b, x, ECHO).run(ErrandIntent.parse("读一下 example.com/p"), null);
        assertEquals(ResearchLadder.Tier.SAMSUNG, o.tier);
        assertEquals("已在三星浏览器打开，请你接着看", o.note);
        assertFalse(o.note.contains("桌面版"));
    }

    /** ★ LC-4：Intent 确实带了桌面参数 → 才说「桌面版」。 */
    @Test
    public void samsung_note_desktop_only_when_intent_carries_it() {
        FakeExternal x = new FakeExternal();
        x.desktop = true;
        FakeBrowser b = new FakeBrowser(BrowseResult.blocked("https://m.example.com/p", "站点拒绝 WebView"));
        ResearchLadder.Outcome o = new ResearchLadder(new FakeSearch(), new FakeExtract(), b, x, ECHO).run(ErrandIntent.parse("读一下 example.com/p"), null);
        assertEquals("已在三星浏览器（桌面版）打开，请你接着看", o.note);
    }

    @Test
    public void nothing_available_returns_partial_never_invents() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", words(50)));
        ResearchLadder.Outcome o = new ResearchLadder(s, null, null, null, null).run(ErrandIntent.parse("去网上查一下 X"), null);
        assertTrue(o.ok);   // 有一条就给一条
        assertTrue(o.answer, o.answer.startsWith("找到这些资料"));
        ResearchLadder.Outcome none = new ResearchLadder(new FakeSearch(), null, null, null, null).run(ErrandIntent.parse("去网上查一下 X"), null);
        assertFalse(none.ok);
        assertEquals("没有拿到可用的资料", none.note);
    }

    @Test
    public void cancel_stops_before_next_tier() {
        FakeSearch s = new FakeSearch(new SearchResult("A", "https://a.com", "短"));
        FakeExtract e = new FakeExtract();
        ResearchLadder.Outcome o = new ResearchLadder(s, e, null, null, ECHO).run(ErrandIntent.parse("去网上查一下 X"), new ResearchLadder.Progress() {
            @Override public void step(String text) {
            }

            @Override public boolean cancelled() {
                return true;
            }
        });
        assertEquals(0, e.calls);
        assertEquals("已停止", o.note);
    }

    @Test
    public void blocked_detection() {
        assertTrue(ResearchLadder.looksBlocked("Just a moment... Checking your browser"));
        assertTrue(ResearchLadder.looksBlocked("请完成安全验证"));
        assertTrue(ResearchLadder.looksBlocked(""));
        assertFalse(ResearchLadder.looksBlocked(words(400)));
        // 长文里出现「登录」不算拦截
        assertFalse(ResearchLadder.looksBlocked(words(2000) + "请先登录"));
    }

    @Test
    public void material_is_numbered_and_capped() {
        List<ResearchLadder.Source> src = new ArrayList<ResearchLadder.Source>();
        for (int i = 0; i < 10; i++) src.add(new ResearchLadder.Source("T" + i, "https://s" + i + ".com", words(2000), ResearchLadder.Tier.EXTRACT));
        String m = ResearchLadder.material(src);
        assertTrue(m.startsWith("[1] T0"));
        assertTrue(m.contains("[2] T1"));
        assertTrue(m.length() <= ResearchLadder.MATERIAL_MAX + 200);
        assertEquals("", ResearchLadder.material(Collections.<ResearchLadder.Source>emptyList()));
    }
}
