package com.onesay.assistant.v2.errand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.ResearchGate;
import com.onesay.assistant.v2.search.BingHtmlSearchProvider;
import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.TavilyExtractProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ★ fix/shop-research（2026-11-03 下午，RESEARCH-TEST-basilisk §7.6-2/3/5）：
 * ① 免 key 必应结果的相关性过滤 + 英文名补搜；② 京东天猫读不到价 → 比价站补读、价格 + 日期 + 券后条件、拼多多要登录跳过；
 * ③ 调研「缺例子」= 代表款（名字 + 价格成对）&lt; 3 提醒一次。
 */
public class ShopRelevanceTest {

    static final String BASILISK = "帮我买雷蛇巴塞利斯蛇v3";
    static final String BING = BingHtmlSearchProvider.NAME;

    /** 上机 s2 实测的必应噪声（只含「雷」字）+ 只有品牌没有型号的。 */
    static List<SearchResult> bingNoise() {
        return new ArrayList<SearchResult>(Arrays.asList(
                new SearchResult("迅雷 - 官方下载", "https://www.xunlei.com/", "迅雷，全球领先的下载加速工具", BING),
                new SearchResult("雷电模拟器-手游电脑版", "https://www.ldmnq.com/", "雷电模拟器是一款安卓模拟器", BING),
                new SearchResult("雷 - 百度百科", "https://baike.baidu.com/item/雷", "雷是一种自然现象，雷声", BING),
                new SearchResult("雷的意思 - 汉语国学", "https://www.hanyuguoxue.com/zidian/zi-38647", "雷 léi 云层放电时发出的响声", BING),
                new SearchResult("扫雷 Minesweeper 在线玩", "https://minesweepergame.net/", "经典扫雷游戏", BING)));
    }

    // ------------------------------------------------------------------ ① 切词与相关性

    @Test public void basiliskTokensBrandSeriesModel() {
        ErrandIntent in = ErrandIntent.parse(BASILISK);
        ProductQuery pq = ProductQuery.parse(in.query);
        List<ProductQuery.Token> k = pq.keyTokens();
        assertEquals(pq.toString(), 3, k.size());
        assertEquals(ProductQuery.Type.BRAND, k.get(0).type);
        assertEquals("雷蛇", k.get(0).text);
        assertEquals(ProductQuery.Type.SERIES, k.get(1).type);
        assertEquals("巴塞利斯蛇", k.get(1).text);
        assertEquals(ProductQuery.Type.MODEL, k.get(2).type);
        assertEquals(2, pq.need());
        assertEquals("Razer Basilisk V3", pq.englishName());
        // 品类 / 版本词不算关键 token
        ProductQuery p2 = ProductQuery.parse("雷蛇炼狱蝰蛇v3pro无线鼠标");
        assertEquals(p2.toString(), 3, p2.keyTokens().size());
        assertEquals("Razer DeathAdder V3 Pro", p2.englishName());
        assertEquals("Logitech G502", ProductQuery.parse("罗技g502").englishName());
        assertEquals("Huawei Mate60", ProductQuery.parse("华为mate60").englishName());
    }

    @Test public void bingNoiseForBasiliskIsDropped() {
        ProductQuery pq = ProductQuery.parse("雷蛇巴塞利斯蛇v3");
        for (SearchResult r : bingNoise()) assertFalse("只含「雷」字：" + r.title, pq.relevant(r));
        assertFalse("只有品牌", pq.relevant("雷蛇官网 Razer 中国", "雷蛇 Razer 游戏外设"));
        assertFalse("V30 不是 V3、只有品牌", pq.relevant("雷蛇 V30 手机壳", "某某 V30"));
        assertTrue(pq.relevant("雷蛇巴塞利斯蛇V3专业版35K 京东", "京东 雷蛇巴塞利斯蛇 V3 专业版 35K 无线鼠标"));
        assertTrue("英文写法也算", pq.relevant("Razer Basilisk V3 X HyperSpeed review", "The Basilisk V3 X is"));
        assertTrue("「v 3」「V3Pro」也算型号", pq.relevant("雷蛇 Basilisk V3Pro 到手价", ""));
        assertTrue("系列 + 型号", pq.relevant("巴塞利斯蛇 v3 评测", ""));
        // 不认识的中文段：整段当关键词
        ProductQuery u = ProductQuery.parse("雷蛇奇美拉x9");
        assertNull("有不认识的中文段 → 不做拼音补搜", u.englishName());
        assertFalse(u.relevant("迅雷 X9 下载", ""));
        assertTrue(u.relevant("雷蛇奇美拉 X9 无线", ""));
        assertNull("原句本来是英文 → 不补搜", ProductQuery.parse("Razer Basilisk V3").englishName());
        assertEquals(Arrays.asList("Razer Basilisk V3 价格", "Razer Basilisk V3 京东", "Razer Basilisk V3 比价"),
                ProductQuery.fallbackQueries("Razer Basilisk V3"));
    }

    // ------------------------------------------------------------------ 假的搜索 / 读网页

    static final class Fake implements ResearchLadder.Searcher, ResearchLadder.Extractor {
        final List<String> queries = new ArrayList<String>();
        final List<String> extracted = new ArrayList<String>();
        boolean englishWorks = true;
        boolean platformJsOnly = false;
        boolean withPdd = false;

        @Override public List<SearchResult> search(String q) {
            queries.add(q);
            List<SearchResult> r = new ArrayList<SearchResult>();
            if (q.startsWith("Razer Basilisk V3")) {
                if (!englishWorks) return bingNoise();
                r.add(new SearchResult("Razer 雷蛇 Basilisk V3 Pro 35K 京东自营", "https://item.jd.com/100088.html", "Razer Basilisk V3 Pro 京东", BING));
                r.add(new SearchResult("Razer Basilisk V3 X HyperSpeed 什么值得买 好价", "https://www.smzdm.com/p/1234/", "Basilisk V3 X 到手 245 元", BING));
                r.addAll(bingNoise());
                return r;
            }
            if (q.contains("什么值得买") || q.contains("慢慢买")) {
                r.add(new SearchResult("雷蛇巴塞利斯蛇V3 Pro 好价 什么值得买", "https://www.smzdm.com/p/5678/", "雷蛇巴塞利斯蛇 V3 好价爆料", BING));
                r.add(new SearchResult("雷蛇巴塞利斯蛇V3 历史价格 慢慢买", "https://tool.manmanbuy.com/history.aspx?u=jd100", "巴塞利斯蛇 V3 价格走势", BING));
                r.addAll(bingNoise());
                return r;
            }
            if (platformJsOnly) {
                if (q.contains("京东")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3 京东", "https://item.jd.com/100123.html", "雷蛇巴塞利斯蛇V3", BING));
                if (q.contains("天猫")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3 天猫旗舰店", "https://detail.tmall.com/item.htm?id=1", "雷蛇巴塞利斯蛇V3", BING));
                if (withPdd && q.contains("拼多多")) r.add(new SearchResult("雷蛇巴塞利斯蛇V3 拼多多百亿补贴", "https://mobile.yangkeduo.com/goods.html?id=9", "巴塞利斯蛇 V3", BING));
            }
            r.addAll(bingNoise());   // 中文查询：s2 实测的噪声
            return r;
        }

        @Override public boolean ready() {
            return true;
        }

        @Override public List<TavilyExtractProvider.Page> extract(List<String> urls, String q) {
            extracted.addAll(urls);
            List<TavilyExtractProvider.Page> p = new ArrayList<TavilyExtractProvider.Page>();
            for (String u : urls) {
                String body;
                if (u.contains("yangkeduo")) body = "请先登录 拼多多 手机号登录";
                else if (u.contains("jd.com") || u.contains("tmall.com")) body = pad("雷蛇巴塞利斯蛇V3 商品详情 加入购物车 规格参数 售后服务");   // JS 渲染：没价
                else if (u.contains("smzdm")) body = pad("2026-11-03 14:02 爆料：京东 雷蛇游戏外设旗舰店 巴塞利斯蛇 V3 专业版 35K 券后 439.12 元，叠加 PLUS 会员。"
                        + "V3 X 极速版 到手 245.52 元。");
                else if (u.contains("manmanbuy")) body = pad("历史价格：巴塞利斯蛇 V3 有线版 11月1日 183.05 元（百亿补贴）。");
                else body = pad("无关页面");
                p.add(new TavilyExtractProvider.Page(u, body, null));
            }
            return p;
        }

        static String pad(String s) {
            StringBuilder sb = new StringBuilder(s);
            while (sb.length() < 400) sb.append("。巴塞利斯蛇系列介绍");
            return sb.toString();
        }
    }

    static ResearchLadder.Outcome run(Fake f) {
        return new ResearchLadder(f, f, null, null, null).run(ErrandIntent.parse(BASILISK), null);
    }

    static boolean noiseIn(ResearchLadder.Outcome o) {
        for (ResearchLadder.Source s : o.sources) {
            if (s.url.contains("xunlei") || s.url.contains("ldmnq") || s.url.contains("hanyuguoxue") || s.url.contains("minesweeper")
                    || s.url.contains("baike.baidu")) return true;
        }
        return false;
    }

    @Test public void basiliskBingNoiseFilteredThenEnglishFallback() {
        Fake f = new Fake();
        ResearchLadder.Outcome o = run(f);
        // 中文 5 个查询全是噪声 → 英文名补搜（价格 / 京东 / 比价）
        assertTrue(f.queries.toString(), f.queries.containsAll(ProductQuery.fallbackQueries("Razer Basilisk V3")));
        assertEquals("中文 5 次 + 英文 3 次（读到价就不再补读比价站）", 8, f.queries.size());
        assertTrue(o.toString(), o.ok);
        assertFalse("噪声一条都不进来源", noiseIn(o));
        for (String u : f.extracted) assertFalse("不读噪声网页：" + u, u.contains("xunlei") || u.contains("minesweeper"));
        assertTrue("比价站先读", f.extracted.get(0).contains("smzdm"));
        assertTrue(o.note, o.note.contains("滤掉") && o.note.contains("条无关结果"));
        assertTrue("结论注明英文补搜", o.answer.contains("英文名「Razer Basilisk V3」补搜"));
        assertTrue("没模型时列价格 + 日期 + 券后", o.answer.contains("439.12元") && o.answer.contains("2026-11-03") && o.answer.contains("券后"));
    }

    @Test public void allFilteredAndNoEnglishHitIsHonest() {
        Fake f = new Fake();
        f.englishWorks = false;
        ResearchLadder.Outcome o = run(f);
        assertFalse(o.ok);
        assertTrue(o.sources.isEmpty());
        assertTrue(o.answer, o.answer.contains("没搜到") && o.answer.contains("没有编造价格") && o.answer.contains("补搜也没有"));
        assertTrue(f.extracted.isEmpty());
        assertTrue(o.note, o.note.startsWith("没搜到这款商品"));
        // 不认识英文名 → 不补搜
        Fake g = new Fake();
        ResearchLadder.Outcome o2 = new ResearchLadder(g, g, null, null, null).run(ErrandIntent.parse("帮我买雷蛇奇美拉x9"), null);
        assertEquals(g.queries.toString(), 5, g.queries.size());
        assertTrue(o2.answer, o2.answer.contains("认不出英文名"));
    }

    @Test public void keyedResultsAreNotFiltered() {
        final List<SearchResult> tav = Arrays.asList(new SearchResult("与商品名不同写法的评测", "https://www.ithome.com/0/1.htm", "售价 599 元"));
        ResearchLadder.Searcher s = new ResearchLadder.Searcher() {
            @Override public List<SearchResult> search(String q) {
                return tav;
            }
        };
        ResearchLadder.Outcome o = new ResearchLadder(s, null, null, null, null).run(ErrandIntent.parse(BASILISK), null);
        assertTrue("Tavily 等有 key 的数据源不做过滤", o.ok && o.sources.size() == 1);
    }

    // ------------------------------------------------------------------ ② 京东天猫 JS 渲染 → 比价站；拼多多要登录

    @Test public void jsPlatformPagesFallBackToDealSitesAndPddSkipped() {
        Fake f = new Fake();
        f.platformJsOnly = true;
        f.withPdd = true;
        ResearchLadder.Outcome o = run(f);
        assertTrue(o.toString(), o.ok);
        assertTrue("平台页读不到价 → 搜比价站", f.queries.toString().contains("什么值得买") && f.queries.toString().contains("慢慢买"));
        assertTrue(f.extracted.toString(), f.extracted.toString().contains("smzdm") && f.extracted.toString().contains("manmanbuy"));
        assertTrue("没走英文补搜（中文有相关结果）", !f.queries.toString().contains("Razer"));
        List<ResearchLadder.PriceNote> ps = ResearchLadder.priceNotes(o.sources);
        assertTrue(ps.toString(), ps.size() >= 3);
        ResearchLadder.PriceNote p0 = ps.get(0);
        assertEquals("smzdm.com", p0.site);
        assertEquals("439.12元", p0.price);
        assertEquals("2026-11-03", p0.date);
        assertTrue(p0.cond, p0.cond.contains("券后") && p0.cond.contains("PLUS"));
        boolean mmb = false;
        for (ResearchLadder.PriceNote p : ps) if (p.price.equals("183.05元")) { mmb = true; assertEquals("11月1日", p.date); assertTrue(p.cond.contains("百亿补贴")); }
        assertTrue(ps.toString(), mmb);
        assertTrue(o.answer, o.answer.contains("脚本渲染") && o.answer.contains("补读了比价站"));
        assertTrue(o.answer, o.answer.contains("拼多多网页要登录，已跳过"));
        for (ResearchLadder.Source s : o.sources) assertFalse("拼多多登录页不当资料", s.tier == ResearchLadder.Tier.EXTRACT && s.url.contains("yangkeduo"));
        assertTrue(ResearchLadder.shopEnough(o.sources));
        String mat = ResearchLadder.shopMaterial(o.sources, ps, Arrays.asList("拼多多网页要登录，已跳过"));
        assertTrue(mat, mat.startsWith("【读到的价格") && mat.contains("smzdm.com 439.12元 · 2026-11-03 · 券后") && mat.contains("【说明"));
    }

    @Test public void dealSitesReadFirst() {
        List<SearchResult> rs = Arrays.asList(
                new SearchResult("a", "https://www.ithome.com/0/1.htm", "x"),
                new SearchResult("b", "https://item.jd.com/1.html", "x"),
                new SearchResult("c", "https://www.example.com/1", "x"),
                new SearchResult("d", "https://www.smzdm.com/p/1/", "x"));
        assertEquals(Arrays.asList("https://www.smzdm.com/p/1/", "https://item.jd.com/1.html", "https://www.ithome.com/0/1.htm",
                "https://www.example.com/1"), ResearchLadder.pickShopUrls(rs));
    }

    @Test public void notesGoBeforeTrailingQuestion() {
        String a = ResearchLadder.withNotes("推荐 V3 Pro。\n要我再查一下 V3 X 吗？", Arrays.asList("拼多多网页要登录，已跳过"));
        assertTrue(a, a.endsWith("要我再查一下 V3 X 吗？"));
        assertTrue(a, a.contains("说明：拼多多网页要登录，已跳过。"));
        assertEquals("x\n\n说明：a；b。", ResearchLadder.withNotes("x", Arrays.asList("a", "b")));
        assertEquals("x", ResearchLadder.withNotes("x", new ArrayList<String>()));
    }

    // ------------------------------------------------------------------ ③ 代表款计数

    @Test public void exampleCountPairsNameWithPrice() {
        String good = "推荐巴塞利斯蛇 V3 Pro 35K。\n- 巴塞利斯蛇 V3 Pro 35K：京东自营 439 元（11-03，券后）[1]\n- V3 X 极速版：245.52 元 [2]\n"
                + "- V3 有线版：拼多多 183.05 元 [3]\n- V3 Pro 35K：天猫旗舰店 527 元";
        assertEquals(3, ResearchGate.exampleCount(good));
        assertEquals("只有平台没有款名", 0, ResearchGate.exampleCount("京东自营 439 元，天猫旗舰店 527 元，拼多多 183 元。"));
        assertEquals("同一款两个平台算一个", 1, ResearchGate.exampleCount("V3 Pro：京东 439 元、天猫 527 元。"));
        assertEquals("同句多款", 3, ResearchGate.exampleCount("爱马仕 Birkin 25 二手约 9 万元，香奈儿 CF 中号 5.2 万元，LV Neverfull 8000 元。"));
        assertEquals(0, ResearchGate.exampleCount("整体价格偏高，波动较大。"));
        assertEquals(0, ResearchGate.exampleCount(null));
    }

    @Test public void examplesNudgeOnceForShopAndPricedResearch() {
        ResearchGate.Evidence ev = new ResearchGate.Evidence();
        ev.reads = 1;
        ev.readHosts.add("smzdm.com");
        String thin = "京东 jd.com 439 元，天猫 tmall.com 527 元（来源 [1][2]）。";
        assertEquals(ResearchGate.EXAMPLES_NUDGE, ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, thin, false, false, false));
        assertNull("只提醒一次", ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, thin, false, false, true));
        assertNull("旧 5 参数口径不数代表款", ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, thin, false, false));
        assertTrue(ResearchGate.isExamplesNudge(ResearchGate.EXAMPLES_NUDGE));
        assertFalse(ResearchGate.isEvidenceNudge(ResearchGate.EXAMPLES_NUDGE));
        String rich = "- V3 Pro 35K：京东 jd.com 439 元\n- V3 X 极速版：245 元\n- V3 有线版：183 元（来源 tmall.com）";
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.SHOP, ev, rich, false, false, false));
        // 行情：二手价只给区间 → 缺例子；给了 3 个代表款 → 过
        String vague = "二手奢侈品客单价 3000 元到 2 万元，整体 5000 元左右，同比下跌 8%（来源 a.com、b.com）。";
        assertEquals(ResearchGate.EXAMPLES_NUDGE, ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, vague, false, false, false));
        String reps = "爱马仕 Birkin 25 二手约 9 万元；香奈儿 CF 中号 5.2 万元；LV Neverfull 8000 元；同比 -8%（来源 a.com、b.com）。";
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, reps, false, false, false));
        // 股市行情没有「款」：不数代表款
        String stocks = "标普 500 涨 0.8%，纳指涨 1.2%，恒指跌 0.5%，上证 3300 点（来源 a.com、b.com）。";
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, stocks, false, false, false));
    }
}
