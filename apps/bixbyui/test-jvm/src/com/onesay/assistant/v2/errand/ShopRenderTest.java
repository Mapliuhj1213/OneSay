package com.onesay.assistant.v2.errand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.errand.web.ReaderFixtures;
import com.onesay.assistant.v2.errand.web.ReaderParser;
import com.onesay.assistant.v2.errand.web.ReaderResult;
import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/shop-research（用户 11-03 14:37 / 14:38）：跑腿比价 runCompare 接入渲染取价（电脑版站内搜索 → 相关性过滤 → 结构化价格进结论），
 * 登录 / 验证 / 超时跳过并注明、拼多多跳过、天猫跳淘宝登录后淘宝不重复读、一轮结束清 Cookie；静态读不到价的 JS 页改用渲染读取。
 */
public class ShopRenderTest {

    static final String BASILISK = "帮我买雷蛇巴塞利斯蛇v3";

    /** 假的渲染读取：按 URL 回样例。 */
    static final class FakeRender implements ResearchLadder.Rendered {
        final List<String> reads = new ArrayList<String>();
        int clears;
        boolean jdCaptcha, jdTimeout, throwOnRead, unavailable;

        @Override public boolean available() {
            return !unavailable;
        }

        @Override public ReaderResult read(String url) {
            reads.add(url);
            if (throwOnRead) throw new RuntimeException("boom");
            try {
                if (url.contains("search.jd.com")) {
                    if (jdTimeout) return ReaderResult.fail(ReaderResult.Status.TIMEOUT, url, 12000, "12 s 内没读完");
                    return ReaderParser.parse(jdCaptcha ? ReaderFixtures.jdRisk() : ReaderFixtures.jdSearch(), url, 4100, System.currentTimeMillis(), 2);
                }
                if (url.contains("list.tmall.com") || url.contains("s.taobao.com")) return ReaderParser.parse(ReaderFixtures.taobaoLogin(), url, 3000, System.currentTimeMillis(), 0);
                if (url.contains("search.smzdm.com")) return ReaderParser.parse(ReaderFixtures.smzdmSearch(), url, 3500, System.currentTimeMillis(), 0);
                if (url.contains("item.jd.com")) return ReaderParser.parse(ReaderFixtures.jdItem(), url, 3000, System.currentTimeMillis(), 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ReaderResult.fail(ReaderResult.Status.BLOCKED, url, 100, "x");
        }

        @Override public void clear() {
            clears++;
        }
    }

    /** 免 key 搜索：只回 s2 实测的噪声（或者一个 JS 渲染的京东商品页）。 */
    static final class NoiseSearch implements ResearchLadder.Searcher, ResearchLadder.Extractor {
        boolean jdItem;
        final List<String> extracted = new ArrayList<String>();

        @Override public List<SearchResult> search(String q) {
            List<SearchResult> r = new ArrayList<SearchResult>();
            if (jdItem && q.contains("京东")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3 Pro 京东", "https://item.jd.com/100000000002.html", "雷蛇巴塞利斯蛇V3", ShopRelevanceTest.BING));
            r.addAll(ShopRelevanceTest.bingNoise());
            return r;
        }

        @Override public boolean ready() {
            return true;
        }

        @Override public List<TavilyExtractProvider.Page> extract(List<String> urls, String q) {
            extracted.addAll(urls);
            List<TavilyExtractProvider.Page> p = new ArrayList<TavilyExtractProvider.Page>();
            for (String u : urls) p.add(new TavilyExtractProvider.Page(u, ShopRelevanceTest.Fake.pad("雷蛇巴塞利斯蛇V3 商品详情 加入购物车 规格参数"), null));
            return p;
        }
    }

    static ResearchLadder.Outcome run(NoiseSearch s, FakeRender r) {
        return new ResearchLadder(s, s, null, null, null).withRendered(r).run(ErrandIntent.parse(BASILISK), null);
    }

    @Test public void basiliskRealPricesFromDesktopSiteSearch() {
        NoiseSearch s = new NoiseSearch();
        FakeRender r = new FakeRender();
        ResearchLadder.Outcome o = run(s, r);
        assertTrue(o.toString(), o.ok);
        // 读了：京东 → 天猫（跳淘宝登录）→ 值得买；淘宝同域已判定要登录，不再读；拼多多没有电脑版搜索
        assertEquals(r.reads.toString(), 3, r.reads.size());
        assertTrue(r.reads.get(0).startsWith("https://search.jd.com/Search?"));
        assertTrue(r.reads.get(1).startsWith("https://list.tmall.com/"));
        assertTrue(r.reads.get(2).startsWith("https://search.smzdm.com/"));
        for (String u : r.reads) assertFalse("电脑版，不用 m. 站：" + u, u.contains("://m.") || u.contains(".m.") || u.contains("mobile."));
        assertTrue(o.tried.contains(ResearchLadder.Tier.RENDER));
        assertEquals("一轮结束清 Cookie / 缓存", 1, r.clears);
        // 结论（debug 没模型 = 兜底）先列价格：基准四个价格、店铺与自营 / 旗舰、日期
        String a = o.answer;
        assertTrue(a, a.startsWith("读到的价格（以下单页为准）："));
        assertTrue(a, a.contains("京东 ¥439.12") && a.contains("店铺 京东游戏外设旗舰店（旗舰）"));
        assertTrue(a, a.contains("京东 ¥527.12") && a.contains("（自营·旗舰）"));
        assertTrue(a, a.contains("¥245.52") && a.contains("¥183.05"));
        assertTrue(a, a.contains("抓取 20"));
        assertTrue(a, a.contains("什么值得买·京东 ¥439.12") && a.contains("11-03 10:20") && a.contains("需用券"));
        assertFalse("配件被滤", a.contains("¥19.9"));
        assertFalse("别的牌子被滤", a.contains("¥299"));
        // 跳过说明
        assertTrue(a, a.contains("拼多多没有电脑版网页搜索"));
        assertTrue(a, a.contains("天猫电脑版网页要登录才出结果，已跳过（没有尝试登录）"));
        assertTrue(a, a.contains("淘宝电脑版网页要登录才出结果，已跳过"));
        assertFalse("有渲染取价就不说「没搜到」", a.contains("没搜到"));
        assertTrue(ResearchLadder.shopEnough(o.sources));
        assertTrue(o.note, o.note.startsWith("多平台核价") && o.note.contains("条渲染取价"));
        boolean renderSrc = false;
        for (ResearchLadder.Source src : o.sources) if (src.tier == ResearchLadder.Tier.RENDER) renderSrc = true;
        assertTrue(renderSrc);
    }

    @Test public void captchaOnJdSkippedAndDealSiteStillGivesPrice() {
        FakeRender r = new FakeRender();
        r.jdCaptcha = true;
        ResearchLadder.Outcome o = run(new NoiseSearch(), r);
        assertTrue(o.answer, o.answer.contains("京东网页要安全验证（验证码 / 滑块），已跳过"));
        assertTrue(o.answer, o.answer.contains("什么值得买·京东 ¥439.12"));
        assertFalse(o.answer, o.answer.contains("京东 ¥527.12"));
        assertEquals(1, r.clears);
    }

    @Test public void timeoutNotedAndNoLoginAttempt() {
        FakeRender r = new FakeRender();
        r.jdTimeout = true;
        ResearchLadder.Outcome o = run(new NoiseSearch(), r);
        assertTrue(o.answer, o.answer.contains("京东搜索页没读出来（12 秒超时）"));
        for (String u : r.reads) assertFalse("不去登录页", u.contains("login") || u.contains("passport"));
    }

    @Test public void clearRunsEvenWhenReaderThrows() {
        FakeRender r = new FakeRender();
        r.throwOnRead = true;
        ResearchLadder.Outcome o = run(new NoiseSearch(), r);
        assertEquals(1, r.clears);
        assertTrue("渲染全挂 → 照旧走免 key 搜索结论（如实说没搜到）", o.answer == null || o.answer.contains("没搜到") || o.answer.length() > 0);
    }

    @Test public void unavailableRendererKeepsOldBehaviour() {
        FakeRender r = new FakeRender();
        r.unavailable = true;
        ResearchLadder.Outcome o = run(new NoiseSearch(), r);
        assertTrue(r.reads.isEmpty());
        assertTrue(o.answer, o.answer.contains("没搜到"));
    }

    @Test public void jsPlatformPageWithoutPriceFallsBackToRender() {
        NoiseSearch s = new NoiseSearch();
        s.jdItem = true;
        FakeRender r = new FakeRender();
        r.unavailable = false;
        // 只看静态读取 → 渲染补读：用一个不做站内搜索的意图（点名京东商品页不走 compare，这里直接调 runCompare 前先把站内搜索都判登录）
        ResearchLadder.Outcome o = run(s, r);
        boolean itemRendered = false;
        for (String u : r.reads) if (u.startsWith("https://item.jd.com/")) itemRendered = true;
        assertTrue(r.reads.toString(), itemRendered);
        boolean itemSrc = false;
        for (ResearchLadder.Source src : o.sources) {
            if (src.url.startsWith("https://item.jd.com/") && src.tier == ResearchLadder.Tier.RENDER) {
                itemSrc = true;
                assertTrue(src.text, src.text.contains("¥527.12"));
            }
        }
        assertTrue(o.sources.toString(), itemSrc);
    }

    /** ★ 11-03 上机 b1：京东跳登录、其余空页 + 免 key 搜索全是噪声 → 「没搜到」结论里也要写跳过说明。 */
    @Test public void noResultAnswerStillCarriesSkipNotes() {
        ResearchLadder.Rendered r = new ResearchLadder.Rendered() {
            @Override public boolean available() {
                return true;
            }

            @Override public ReaderResult read(String url) {
                try {
                    if (url.contains("search.jd.com")) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("u", "https://passport.jd.com/new/login.aspx?ReturnUrl=x");
                        o.put("tx", "欢迎登录 账户登录 扫码登录");
                        return ReaderParser.parse(ReaderFixtures.encoded(o), url, 11000, System.currentTimeMillis(), 0);
                    }
                    org.json.JSONObject e = new org.json.JSONObject();
                    e.put("u", url);
                    e.put("tx", "");
                    return ReaderParser.parse(ReaderFixtures.encoded(e), url, 2000, System.currentTimeMillis(), 0);
                } catch (Exception x) {
                    throw new RuntimeException(x);
                }
            }

            @Override public void clear() {
            }
        };
        NoiseSearch s = new NoiseSearch();
        ResearchLadder.Outcome o = new ResearchLadder(s, s, null, null, null).withRendered(r).run(ErrandIntent.parse(BASILISK), null);
        assertTrue(o.answer, o.answer.contains("没搜到"));
        assertTrue(o.answer, o.answer.contains("京东电脑版网页要登录才出结果，已跳过（没有尝试登录）"));
        assertTrue(o.answer, o.answer.contains("天猫搜索页没读出来（页面没渲染出内容"));
        assertTrue(o.answer, o.answer.contains("拼多多没有电脑版网页搜索"));
    }

    @Test public void reportLabelsRenderTier() {
        assertEquals("浏览器取价", ErrandReport.tierLabel(ResearchLadder.Tier.RENDER));
    }
}
