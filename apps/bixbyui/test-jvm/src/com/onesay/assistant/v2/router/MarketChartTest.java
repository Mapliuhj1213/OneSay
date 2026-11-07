package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 11-04（用户 00:13「行情类结论要画图展示」）：MarketQuotes 结构化快照 → 图表模型（分地区、点位 / 涨跌幅原字符串、
 * 红涨绿跌、缺值「未查到」不画柱、脚注时间时区与来源），以及结论文字只留图注 + 板块与原因。fixture 同 MarketQuotesTest（11-03 真实响应）。
 */
public class MarketChartTest {

    @After public void clear() {
        MarketChart.clear();
    }

    static MarketQuotes.Snapshot full() throws Exception {
        return MarketQuotes.fetch(new MarketQuotesTest.FakeSrc(MarketQuotesTest.fx("tencent-20261103.txt"), null));
    }

    static MarketChart.Bar bar(MarketChart.Model m, String name) {
        for (MarketChart.Group g : m.groups) for (MarketChart.Bar b : g.bars) if (b.name.equals(name) || b.fullName.equals(name)) return b;
        return null;
    }

    @Test public void mapsSnapshotToRegionGroupsWithSourceStrings() throws Exception {
        MarketQuotes.Snapshot s = full();
        MarketChart.Model m = MarketChart.fromSnapshot(s, MarketQuotesTest.nov3());
        List<String> regions = new ArrayList<String>();
        for (MarketChart.Group g : m.groups) regions.add(g.region);
        assertEquals("「日欧」拆成欧洲与日本，顺序 A股 / 港股 / 美股 / 欧洲 / 日本", java.util.Arrays.asList("A股", "港股", "美股", "欧洲", "日本"), regions);
        assertEquals(12, m.barCount());
        assertEquals(4, m.groups.get(0).bars.size());
        assertEquals("日经225", m.groups.get(4).bars.get(0).name);
        // 每条的点位 / 涨跌幅 = 源里的原字符串（与文字槽位一致）
        for (MarketQuotes.Quote q : s.quotes) {
            MarketChart.Bar b = bar(m, q.name);
            assertNotNull(q.name, b);
            assertTrue(b.found);
            assertEquals(MarketQuotes.fmt2(q.price), b.price);
            assertEquals(MarketQuotes.signed(q.pct) + "%", b.pctText);
            assertEquals(Double.parseDouble(q.pct), b.pct, 1e-9);
        }
        MarketChart.Bar cy = bar(m, "创业板指");
        assertEquals("2391.65  -2.73%", cy.valueText());
        assertEquals("红涨绿跌：跌 = 绿", MarketChart.DOWN, cy.color);
        assertEquals(MarketChart.UP, bar(m, "恒生指数").color);
        assertEquals("+0.57%", bar(m, "恒生指数").pctText);
        assertEquals("柱长满格 = 最大涨跌幅绝对值", 2.73, m.maxAbs, 1e-9);
        // 脚注：时间与时区（非今日标最近一个交易日；美东时间不标）+ 来源
        String foot = m.footer.toString();
        assertTrue(foot, foot.contains("A股 11-03 16:15 北京时间") || foot.contains("A股 11-03"));
        assertTrue(foot, foot.contains("日本 11-02 14:30 北京时间（最近一个交易日）"));
        assertTrue(foot, foot.contains("美股 11-02 16:50 美东时间") && !foot.contains("美东时间（最近一个交易日）"));
        assertTrue(foot, foot.contains("来源：" + MarketQuotes.SRC_TENCENT) && foot.contains("红涨绿跌"));
    }

    @Test public void missingIndicesShowNotFoundNoBar() throws Exception {
        StringBuilder t = new StringBuilder();
        for (String line : MarketQuotesTest.fx("tencent-20261103.txt").split("\n")) if (line.startsWith("v_sh") || line.startsWith("v_sz")) t.append(line).append('\n');
        MarketQuotes.Snapshot s = MarketQuotes.fetch(new MarketQuotesTest.FakeSrc(t.toString(), null));
        MarketChart.Model m = MarketChart.fromSnapshot(s, MarketQuotesTest.nov3());
        assertEquals("缺的也占一行", 12, m.barCount());
        MarketChart.Bar hsi = bar(m, "恒生指数");
        assertFalse(hsi.found);
        assertFalse(hsi.drawBar());
        assertEquals("未查到", hsi.valueText());
        assertEquals(MarketChart.FLAT, hsi.color);
        assertTrue(bar(m, "上证指数").drawBar());
        MarketChart.Model none = MarketChart.fromSnapshot(MarketQuotes.fetch(new MarketQuotesTest.FakeSrc(null, null)), MarketQuotesTest.nov3());
        for (MarketChart.Group g : none.groups) for (MarketChart.Bar b : g.bars) assertFalse(b.name, b.drawBar());
        assertEquals("全缺时最小满格 0.5%", 0.5, none.maxAbs, 1e-9);
        assertTrue(none.footer.toString(), none.footer.get(0).contains("未查到") && none.footer.get(1).contains("均未取到"));
        assertNull(MarketChart.fromSnapshot(null, 0));
    }

    @Test public void colorsUpRedDownGreenFlatGrey() {
        assertEquals(MarketChart.UP, MarketChart.colorOf(0.01));
        assertEquals(MarketChart.DOWN, MarketChart.colorOf(-0.01));
        assertEquals(MarketChart.FLAT, MarketChart.colorOf(0));
        assertEquals(MarketChart.FLAT, MarketChart.colorOf(Double.NaN));
        assertTrue("红", (MarketChart.UP >> 16 & 0xFF) > (MarketChart.UP >> 8 & 0xFF));
        assertTrue("绿", (MarketChart.DOWN >> 8 & 0xFF) > (MarketChart.DOWN >> 16 & 0xFF));
        assertTrue(Double.isNaN(MarketChart.parsePct("--")));
        assertEquals(-0.82, MarketChart.parsePct("-0.82%"), 1e-9);
    }

    @Test public void conclusionTextHasNoInternalWordingAndUiMatch() throws Exception {
        MarketQuotes.Snapshot s = full();
        long now = MarketQuotesTest.nov3();
        String fin = MarketChart.withChart(s, "  AI 硬件走弱，电池与红利走强（财联社，11-03）。", now);
        assertEquals("★ 11-04：不再加「见上图（结构化行情源…）」图注", "AI 硬件走弱，电池与红利走强（财联社，11-03）。", fin);
        assertFalse(fin.contains("结构化") || fin.contains("见上图"));
        assertFalse("文字不再逐个复述指数数字", fin.contains("3608") || fin.contains("2391.65"));
        MarketChart.Model m = MarketChart.match(fin, now + 5000);
        assertNotNull(m);
        assertEquals(12, m.barCount());
        assertNotNull("界面清洗 Markdown / 空白后仍认得", MarketChart.match("**AI 硬件走弱**，电池与红利走强（财联社，11-03）。\n", now));
        assertNull("别的回答不画图", MarketChart.match("今天天气不错", now));
        assertNull("过期不画", MarketChart.match(fin, now + MarketChart.TTL_MS + 1));
        assertEquals("x", MarketChart.withChart(null, "x", now));
        assertEquals(MarketChart.EMPTY_ANSWER, MarketChart.withChart(s, " ", now));
        String p = MarketQuotes.promptBlock(s, now);
        assertTrue(p, p.contains("画成分地区涨跌幅柱状图") && p.contains("不要再列这些指数") && p.contains("地区涨跌事实"));
        assertTrue(m.describe(), m.describe().contains("创业板指=2391.65,-2.73%"));
    }

    // ------------------------------------------------------------------ ★ 11-04：短名 / 2 位小数 / 列宽

    @Test public void shortNamesAndTwoDecimals() throws Exception {
        assertEquals("道指", MarketQuotes.shortName("道琼斯工业指数"));
        assertEquals("纳指", MarketQuotes.shortName("纳斯达克综合指数"));
        assertEquals("标普500", MarketQuotes.shortName("标普500"));
        assertEquals("恒指", MarketQuotes.shortName("恒生指数"));
        assertEquals("恒生科技", MarketQuotes.shortName("恒生科技指数"));
        assertEquals("富时100", MarketQuotes.shortName("英国富时100"));
        assertEquals("不认识的原样", "某指数", MarketQuotes.shortName("某指数"));
        assertEquals("23525.97", MarketQuotes.fmt2("23525.970"));
        assertEquals("3867.73", MarketQuotes.fmt2("3867.730"));
        assertEquals("100.00", MarketQuotes.fmt2("100"));
        assertEquals("1.24", MarketQuotes.fmt2("1.235"));
        assertEquals("--", MarketQuotes.fmt2("--"));
        MarketChart.Model m = MarketChart.fromSnapshot(full(), MarketQuotesTest.nov3());
        for (MarketChart.Group g : m.groups) {
            for (MarketChart.Bar b : g.bars) {
                assertTrue(b.name, b.name.length() <= 5);
                assertTrue(b.valueText(), b.price.matches("\\d+\\.\\d{2}"));
                assertFalse(b.valueText().contains("…"));
            }
        }
        assertEquals("23525.97  +0.57%", bar(m, "恒指").valueText());
        assertEquals("道琼斯工业指数", bar(m, "道指").fullName);
    }

    @Test public void fitScaleShrinksTextInsteadOfEllipsis() {
        assertEquals("放得下不缩", 1f, MarketChart.fitScale(1000, 100, 200, 20, 60), 1e-6);
        float sc = MarketChart.fitScale(300, 120, 200, 20, 60);
        assertTrue(sc < 1f);
        assertEquals("缩后正好放下", 300f, 120 * sc + 200 * sc + 20 + 60, 0.5f);
        assertEquals("下限 0.6", 0.6f, MarketChart.fitScale(100, 300, 300, 20, 60), 1e-6);
    }
}
