package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * ★ 11-04（用户 01:06「A股行情怎么样」也回了全球 12 个指数；文字说「美股走强」但道指 -0.34%；
 * 「上网去查查现在二手摩托车的市场价格」卡在面板上没进灵动岛）：
 * 行情问句 → 范围、只取范围内指数、地区涨跌事实与文字一致性核对、调研类一开始就上岛。
 */
public class MarketScopeTest {

    @Test public void scopeFromVariousQuestions() {
        assertEquals(Arrays.asList("上证指数", "深证成指", "创业板指", "沪深300", "科创50", "北证50"), MarketQuotes.scopeOf("A股行情怎么样").indices());
        assertEquals("A股", MarketQuotes.scopeOf("a股今天怎么样").label);
        assertEquals("A股", MarketQuotes.scopeOf("今天大盘走势如何").label);
        assertEquals("A股", MarketQuotes.scopeOf("沪深两市行情").label);
        assertEquals(Arrays.asList("创业板指"), MarketQuotes.scopeOf("创业板今天行情").indices());
        assertEquals(Arrays.asList("纳斯达克综合指数"), MarketQuotes.scopeOf("纳指走势怎么样").indices());
        assertEquals(Arrays.asList("恒生科技指数"), MarketQuotes.scopeOf("恒生科技今天怎么样").indices());
        assertEquals(Arrays.asList("恒生指数"), MarketQuotes.scopeOf("恒指行情").indices());
        assertEquals(Arrays.asList("沪深300"), MarketQuotes.scopeOf("沪深300行情").indices());
        assertEquals(Arrays.asList("恒生指数", "恒生科技指数"), MarketQuotes.scopeOf("港股行情").indices());
        assertEquals(Arrays.asList("道琼斯工业指数", "标普500", "纳斯达克综合指数"), MarketQuotes.scopeOf("美股行情怎么样").indices());
        assertEquals(Arrays.asList("道琼斯工业指数", "标普500", "纳斯达克综合指数"), MarketQuotes.scopeOf("美股三大股指").indices());
        assertEquals(Arrays.asList("德国DAX", "英国富时100"), MarketQuotes.scopeOf("欧洲股市行情").indices());
        assertEquals(Arrays.asList("日经225"), MarketQuotes.scopeOf("日股行情").indices());
        assertEquals(Arrays.asList("日经225"), MarketQuotes.scopeOf("日经今天涨了吗").indices());
        MarketQuotes.Scope ah = MarketQuotes.scopeOf("A股和港股行情");
        assertEquals(8, ah.indices().size());
        assertFalse(ah.global);
        MarketQuotes.Scope mix = MarketQuotes.scopeOf("A股和纳指怎么样");
        assertTrue(mix.indices().contains("纳斯达克综合指数") && mix.indices().contains("上证指数") && !mix.indices().contains("道琼斯工业指数"));
        for (String g : new String[]{"今天全球股市行情", "今天股市行情", "股市怎么样", "全球主要股指走势", null}) {
            MarketQuotes.Scope sc = MarketQuotes.scopeOf(g);
            assertTrue(g, sc.global);
            assertEquals(g, 12, sc.indices().size());
            assertFalse("全球默认不带科创50 / 北证50", sc.indices().contains("科创50"));
        }
        assertTrue(MarketQuotes.isStockMarketQuery("创业板今天行情"));
        assertTrue(MarketQuotes.isStockMarketQuery("A股行情怎么样"));
    }

    @Test public void fetchOnlyScopeIndices() throws Exception {
        MarketQuotesTest.FakeSrc src = new MarketQuotesTest.FakeSrc(MarketQuotesTest.fx("tencent-20261103.txt"), null);
        MarketQuotes.Snapshot s = MarketQuotes.fetch(src, MarketQuotes.scopeOf("A股行情怎么样"));
        assertEquals(6, s.quotes.size());
        assertEquals("A股", s.scope.label);
        String u = src.urls.get(0);
        assertTrue(u, u.contains("sh000001") && u.contains("sh000688") && u.contains("bj899050"));
        assertFalse("A股范围不取港美欧日", u.contains("hkHSI") || u.contains("usDJI") || u.contains("gzN225"));
        for (MarketQuotes.Quote q : s.quotes) assertEquals(q.name, "A股", q.region);
        MarketChart.Model m = MarketChart.fromSnapshot(s, MarketQuotesTest.nov3());
        assertEquals(1, m.groups.size());
        assertEquals("A股", m.groups.get(0).region);
        String p = MarketQuotes.promptBlock(s, MarketQuotesTest.nov3());
        assertTrue(p, p.contains("用户只问了「A股」") && p.contains("不要扩展到其他市场"));
        assertFalse(p, p.contains("港股：") || p.contains("美股："));
        MarketQuotes.Snapshot g = MarketQuotes.fetch(new MarketQuotesTest.FakeSrc(MarketQuotesTest.fx("tencent-20261103.txt"), null));
        assertEquals(12, g.quotes.size());
        assertTrue(g.scope.global);
        assertFalse(MarketQuotes.promptBlock(g, MarketQuotesTest.nov3()).contains("用户只问了"));
    }

    static MarketQuotes.Snapshot snap(String[][] rows) {
        MarketQuotes.Snapshot s = new MarketQuotes.Snapshot();
        for (String[] r : rows) {
            MarketQuotes.Quote q = new MarketQuotes.Quote(r[0], r[1]);
            q.price = "100.00"; q.change = "0"; q.pct = r[2];
            q.date = "2026-11-03"; q.time = r.length > 3 ? r[3] : "11:36"; q.zone = r.length > 4 ? r[4] : "美东时间";
            q.source = MarketQuotes.SRC_TENCENT;
            s.quotes.add(q);
        }
        return s;
    }

    static final String[][] US_MIXED = {
            {"道琼斯工业指数", "美股", "-0.34"}, {"标普500", "美股", "0.12"}, {"纳斯达克综合指数", "美股", "0.46"}};
    static final String[][] A_DOWN = {
            {"上证指数", "A股", "-0.82", "15:00", "北京时间"}, {"创业板指", "A股", "-2.73", "15:00", "北京时间"}};

    @Test public void regionWordingMustMatchData() {
        MarketQuotes.Snapshot us = snap(US_MIXED);
        String issue = MarketQuotes.consistencyIssue(us, "美股走强，科技股领涨。");
        assertNotNull("涨跌互现却说走强 → 打回", issue);
        assertTrue(issue, issue.contains("道指 -0.34%") && issue.contains("涨跌互现"));
        assertNull("写分化 = 一致", MarketQuotes.consistencyIssue(us, "美股三大股指涨跌分化，科技股偏强。"));
        assertNull("点名具体指数的分句不按地区核", MarketQuotes.consistencyIssue(us, "美股方面，纳指上涨，道指小幅下跌。"));
        assertNotNull(MarketQuotes.consistencyIssue(us, "美股整体上涨"));
        MarketQuotes.Snapshot a = snap(A_DOWN);
        assertNotNull("全跌却说走强", MarketQuotes.consistencyIssue(a, "今天A股走强，成交放量。"));
        assertNull(MarketQuotes.consistencyIssue(a, "今天A股普跌，成交放量。"));
        assertNull("否定不算", MarketQuotes.consistencyIssue(a, "A股未能走强，全天弱势。"));
        assertNull("没有该地区数据不核", MarketQuotes.consistencyIssue(a, "美股走强。"));
        assertNull(MarketQuotes.consistencyIssue(null, "美股走强"));
        String n = MarketQuotes.rewriteNudge(issue, "今天全球股市行情");
        assertTrue(n, n.startsWith("（内部核对") && n.contains("不要在回答里提及本条") && n.endsWith("今天全球股市行情"));
    }

    @Test public void regionFactsCarryDirectionAndSession() {
        long now = MarketQuotesTest.nov3();   // 2026-11-03 北京时间晚上
        String f = MarketQuotes.regionFacts(snap(US_MIXED), now);
        assertTrue(f, f.contains("美股（盘中，截至 11-03 11:36 美东时间）") && f.contains("道指 -0.34%") && f.contains("涨跌互现"));
        String fa = MarketQuotes.regionFacts(snap(A_DOWN), now);
        assertTrue(fa, fa.contains("A股（已收盘") && fa.contains("全部下跌"));
    }

    @Test public void researchGoesToIslandUpFront() {
        TriageDecider.setWebResearch(true);
        for (String q : new String[]{"上网去查查现在二手摩托车的市场价格", "帮我找二手摩托车价格", "找二手摩托车价格", "A股行情怎么样", "今天全球股市行情"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertTrue(q + " → " + d.rule, d.useAgentLoop);
            assertTrue(q + " → " + d.rule, TriageDecider.isResearch(d));
            assertTrue(q + " 一开始就上灵动岛", TriageDecider.islandUpFront(d));
        }
        TriageDecider.Decision one = TriageDecider.fastPath("打开手电筒", "torch.on", false);
        assertFalse("单步快路径不上岛", TriageDecider.islandUpFront(one));
    }
}
