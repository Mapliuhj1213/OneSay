package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONArray;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ 11-03（用户 19:45「今天全球股市行情」）：
 * ① 主要指数走结构化行情源（腾讯 qt.gtimg.cn，新浪 hq.sinajs.cn 补缺），数字逐字进结论槽位，拿不到写「未查到」；
 * ② 核对 / 打回提示作为内部指令注入，终答开头不回应内部提示（代码过滤开场句）。
 * fixture 是 11-03 19:5x 沙箱直连两个源抓的真实响应（GBK 转 UTF-8 存盘）。
 */
public class MarketQuotesTest {

    static String fx(String name) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/market/" + name).toPath()), "UTF-8");
    }

    /** 假源：按 URL 前缀给 fixture，记下请求过的 URL 与 Referer。 */
    static final class FakeSrc implements MarketQuotes.Fetcher {
        final String tencent, sina;
        final List<String> urls = new ArrayList<String>();
        final List<String> referers = new ArrayList<String>();
        FakeSrc(String tencent, String sina) { this.tencent = tencent; this.sina = sina; }
        @Override public String get(String url, String referer) throws Exception {
            urls.add(url); referers.add(referer);
            if (url.startsWith("https://qt.gtimg.cn/")) { if (tencent == null) throw new java.io.IOException("down"); return tencent; }
            if (url.startsWith("https://hq.sinajs.cn/")) { if (sina == null) throw new java.io.IOException("down"); return sina; }
            return null;
        }
    }

    static long nov3() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.clear(); c.set(2026, Calendar.NOVEMBER, 3, 19, 55, 0);
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------------ ① 识别 / 代码表

    @Test public void stockMarketQueryRecognized() {
        for (String q : new String[]{"今天全球股市行情", "美股今天怎么样", "今天A股大盘行情", "港股收盘行情"}) assertTrue(q, MarketQuotes.isStockMarketQuery(q));
        for (String q : new String[]{"最近金价走势", "二手奢侈品行情与客单价", "茅台股价多少", "现在二手iPhone 15什么价"}) assertFalse(q, MarketQuotes.isStockMarketQuery(q));
    }

    @Test public void fixedIndexSetAndCodes() {
        assertEquals(12, MarketQuotes.size());
        String u = MarketQuotes.tencentUrl();
        for (String c : new String[]{"sh000001", "sz399001", "sz399006", "sh000300", "hkHSI", "hkHSTECH", "usDJI", "usINX", "usIXIC", "gzN225", "gzGDAXI", "gzFTSE"}) {
            assertTrue(c, u.contains(c));
        }
        assertTrue(u.startsWith("https://qt.gtimg.cn/q="));
    }

    // ------------------------------------------------------------------ ① 解析

    @Test public void tencentParsesAllTwelveVerbatim() throws Exception {
        String t = fx("tencent-20261103.txt");
        FakeSrc src = new FakeSrc(t, null);
        MarketQuotes.Snapshot s = MarketQuotes.fetch(src);
        assertEquals(s.describe(), 12, s.foundCount());
        assertEquals("全拿到就不调新浪", 1, src.urls.size());
        MarketQuotes.Quote sh = s.get("上证指数");
        assertEquals("3608.10", sh.price); assertEquals("-29.93", sh.change); assertEquals("-0.82", sh.pct);
        assertEquals("2026-11-03", sh.date); assertEquals("16:15", sh.time); assertEquals("北京时间", sh.zone);
        MarketQuotes.Quote hsi = s.get("恒生指数");
        assertEquals("23525.970", hsi.price); assertEquals("132.650", hsi.change); assertEquals("0.57", hsi.pct);
        MarketQuotes.Quote dji = s.get("道琼斯工业指数");
        assertEquals("51983.98", dji.price); assertEquals("美东时间", dji.zone); assertEquals("2026-11-02", dji.date);
        MarketQuotes.Quote n225 = s.get("日经225");
        assertEquals("63081.64", n225.price); assertEquals("-208.47", n225.change); assertEquals("2026-11-02", n225.date);
        assertEquals("25821.95", s.get("德国DAX").price);
        assertEquals("10697.32", s.get("英国富时100").price);
        // 每个数字都能在源里原样找到
        for (MarketQuotes.Quote q : s.quotes) {
            assertTrue(q.name + " price", t.contains("~" + q.price + "~"));
            assertTrue(q.name + " pct", t.contains("~" + q.pct + "~"));
            assertTrue(q.name + " change", t.contains("~" + q.change + "~"));
        }
    }

    @Test public void sinaFillsMissingWithRefererAndVerbatimNumbers() throws Exception {
        String sina = fx("sina-20261103.txt");
        FakeSrc src = new FakeSrc(null, sina);   // 腾讯挂了
        MarketQuotes.Snapshot s = MarketQuotes.fetch(src);
        assertEquals(s.describe(), 12, s.foundCount());
        assertEquals(2, src.urls.size());
        assertEquals("新浪必须带 Referer", MarketQuotes.SINA_REFERER, src.referers.get(1));
        assertTrue(src.urls.get(1).contains("s_sh000001") && src.urls.get(1).contains("gb_$dji") && src.urls.get(1).contains("znb_UKX"));
        MarketQuotes.Quote sh = s.get("上证指数");
        assertEquals("3608.0976", sh.price); assertEquals("-29.9334", sh.change); assertEquals("-0.82", sh.pct);
        assertNotNull("完整版给时间", sh.date);
        MarketQuotes.Quote hst = s.get("恒生科技指数");
        assertEquals("3867.730", hst.price); assertEquals("28.320", hst.change); assertEquals("0.740", hst.pct);
        assertEquals("2026-11-03", hst.date); assertEquals("16:08", hst.time);
        MarketQuotes.Quote spx = s.get("标普500");
        assertEquals("7541.3999", spx.price); assertEquals("-38.6700", spx.change); assertEquals("-0.51", spx.pct);
        assertEquals("25821.9492", s.get("德国DAX").price);
        for (MarketQuotes.Quote q : s.quotes) {
            assertEquals(MarketQuotes.SRC_SINA, q.source);
            assertTrue(q.name, sina.contains(q.price) && sina.contains(q.change) && sina.contains(q.pct));
        }
    }

    @Test public void partialTencentThenSinaOnlyForMissing() throws Exception {
        // 腾讯只回了 A 股 4 个（其余 v_pv_none_match），新浪只补另外 8 个
        StringBuilder t = new StringBuilder();
        for (String line : fx("tencent-20261103.txt").split("\n")) if (line.startsWith("v_sh") || line.startsWith("v_sz")) t.append(line).append('\n');
        t.append("v_pv_none_match=\"1\";\n");
        FakeSrc src = new FakeSrc(t.toString(), fx("sina-20261103.txt"));
        MarketQuotes.Snapshot s = MarketQuotes.fetch(src);
        assertEquals(12, s.foundCount());
        assertEquals(MarketQuotes.SRC_TENCENT, s.get("沪深300").source);
        assertEquals(MarketQuotes.SRC_SINA, s.get("恒生指数").source);
        assertFalse("A 股已拿到，不再向新浪要", src.urls.get(1).contains("sh000001"));
        String r = s.render(nov3());
        assertTrue(r, r.contains(MarketQuotes.SRC_TENCENT) && r.contains(MarketQuotes.SRC_SINA));
    }

    @Test public void bothDownWritesNotFoundNeverInvents() {
        MarketQuotes.Snapshot s = MarketQuotes.fetch(new FakeSrc(null, null));
        assertEquals(0, s.foundCount());
        assertFalse(s.any());
        String r = s.render(nov3());
        int n = 0; int i = -1;
        while ((i = r.indexOf("未查到", i + 1)) >= 0) n++;
        assertEquals(r, 12, n);
        assertFalse(r, r.matches("(?s).*\\d+\\.\\d+.*"));
        // 垃圾 / 空字段也是未查到
        MarketQuotes.Snapshot g = MarketQuotes.fetch(new FakeSrc("v_sh000001=\"1~上证指数~000001~~~\";\nv_gzN225=\"N225~日经~x~abc~1~2~\";", "var hq_str_s_sh000001=\"\";"));
        assertEquals(0, g.foundCount());
    }

    // ------------------------------------------------------------------ ① 槽位

    @Test public void slotRenderGroupsRegionsWithTimeAndStaleMark() throws Exception {
        MarketQuotes.Snapshot s = MarketQuotes.fetch(new FakeSrc(fx("tencent-20261103.txt"), null));
        String r = s.render(nov3());
        assertTrue(r, r.startsWith("主要指数（结构化行情源：" + MarketQuotes.SRC_TENCENT + "）："));
        for (String must : new String[]{"\nA股：上证指数 3608.10，-29.93（-0.82%），11-03 16:15 北京时间",
                "深证成指 10876.99", "创业板指 2391.65，-67.05（-2.73%）", "沪深300 4008.87",
                "\n港股：恒生指数 23525.97，+132.65（+0.57%），11-03 18:31 北京时间", "恒生科技指数 3867.73，+28.32（+0.74%）",
                "\n美股：道琼斯工业指数 51983.98，+213.67（+0.41%），11-02 16:50 美东时间", "标普500 7541.40，-38.67（-0.51%）", "纳斯达克综合指数 25164.36",
                "\n日欧：日经225 63081.64，-208.47（-0.33%），11-02 14:30 北京时间，最近一个交易日", "德国DAX 25821.95，-159.14（-0.61%），11-03 19:50 北京时间", "英国富时100 10697.32"}) {
            assertTrue("缺「" + must + "」\n" + r, r.contains(must));
        }
        assertFalse("美东时间的前一日是正常的最近收盘，不另标", r.contains("美东时间，最近一个交易日"));
        String fin = MarketQuotes.withSlots(s, "板块：AI 硬件走弱（来源 eastmoney.com）。", nov3());
        assertTrue(fin.startsWith(r + "\n\n板块"));
        assertEquals(r, MarketQuotes.withSlots(s, "  ", nov3()));
        assertEquals("x", MarketQuotes.withSlots(null, "x", nov3()));
        String p = MarketQuotes.promptBlock(s, nov3());
        assertTrue(p.contains("不要再列这些指数") && p.contains("未查到") && p.contains("板块") && p.contains(r + "\n地区涨跌事实"));
    }

    @Test public void structuredSourceCountsAsOneRead() throws Exception {
        MarketQuotes.Snapshot s = MarketQuotes.fetch(new FakeSrc(fx("tencent-20261103.txt"), null));
        ResearchGate.Evidence ev = new ResearchGate.Evidence();
        s.addEvidence(ev);
        assertEquals(1, ev.reads);
        assertTrue(ev.readHosts.contains("gtimg.cn"));
        ResearchGate.Evidence ev2 = new ResearchGate.Evidence();
        MarketQuotes.fetch(new FakeSrc(null, null)).addEvidence(ev2);
        assertEquals("没拿到不算", 0, ev2.reads);
    }

    // ------------------------------------------------------------------ ② 内部指令 / 开场过滤

    @Test public void nudgeWrappedAsInternalInstruction() {
        String w = ResearchGate.internalNudge(ResearchGate.RESEARCH_DATA_NUDGE, "今天全球股市行情");
        assertTrue(w.startsWith(ResearchGate.INTERNAL_HEAD));
        assertTrue(w.contains(ResearchGate.RESEARCH_DATA_NUDGE));
        assertTrue(w.contains("直接回答用户的原问题「今天全球股市行情」"));
        assertTrue(w.contains("不要提及核对") && w.contains("「补上…」"));
        assertEquals("不重复包", w, ResearchGate.internalNudge(w, "今天全球股市行情"));
        assertNull(ResearchGate.internalNudge(null, "x"));
    }

    @Test public void metaOpeningStripped() {
        String body = "美股：三大指数涨跌互现；A 股：创业板指跌 2.73%。";
        assertEquals(body, ResearchGate.stripMetaOpening("补上具体个股的收盘价：\n" + body));
        assertEquals(body, ResearchGate.stripMetaOpening("好的，补上具体个股的收盘价。" + body));
        assertEquals(body, ResearchGate.stripMetaOpening("## 以下是补充后的完整回答：\n\n" + body));
        assertEquals(body, ResearchGate.stripMetaOpening("好的，" + body));
        assertEquals(body, ResearchGate.stripMetaOpening("根据提醒，我重新整理了数据。\n" + body));
        assertEquals(body, ResearchGate.stripMetaOpening("收到！已补充代表个股与日期：" + body));
        // 正常开头不动
        assertEquals(body, ResearchGate.stripMetaOpening(body));
        String normal = "今天全球股市分化：美股小幅收涨，欧股走弱。补充一点：港股科技股反弹。";
        assertEquals(normal, ResearchGate.stripMetaOpening(normal));
        String longFirst = "补充说明一下今天全球股市整体走势，美股三大指数涨跌互现，欧股普遍收跌，亚太市场分化明显，A 股三大指数全线下跌，创业板跌幅最大，成交额较上一交易日明显放大。";
        assertEquals("超过 60 字的首句是正文，不去", longFirst, ResearchGate.stripMetaOpening(longFirst));
        assertEquals("全是开场就原样返回", "补上具体个股的收盘价：", ResearchGate.stripMetaOpening("补上具体个股的收盘价："));
        assertNull(ResearchGate.stripMetaOpening(null));
    }

    // ------------------------------------------------------------------ 接线：AgentLoop 端到端（假模型 / 假搜索 / 假行情源）

    @Test public void agentLoopInjectsQuotesWrapsNudgeAndStripsOpening() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("今天全球股市分化。"));   // 只有结构化源一个站 → 证据提醒
        llm.replies.add(AgentLoopArchBTest.call("web__search", "{\"query\":\"今天 全球股市 板块\"}"));
        llm.replies.add(AgentLoopArchBTest.call("web__read", "{\"url\":\"https://www.ithome.com/0/1.htm\"}"));
        llm.replies.add(AgentLoopTest.say("补上具体个股的收盘价：\n板块：AI 硬件走弱、保险走强（11-03，来源 ithome.com）。"));
        AgentLoop loop = new AgentLoop(CapabilityRegistry.getInstance(), llm).setWebBackend(new AgentLoopArchBTest.FakeWeb())
                .setMarketFetcher(new FakeSrc(fx("tencent-20261103.txt"), null));
        AgentLoop.Result r = loop.run("今天全球股市行情", new AgentLoopArchBTest.Host(), 0);
        assertTrue(r.reason, r.finished);
        String first = lastUser(llm.messagesSeen.get(0));
        assertTrue("开局带结构化数据", first.contains("系统数据") && first.contains("上证指数 3608.10"));
        String nudge = lastUser(llm.messagesSeen.get(1));
        assertTrue("提醒是内部指令：" + nudge, nudge.startsWith(ResearchGate.INTERNAL_HEAD) && nudge.contains("今天全球股市行情"));
        // ★ 11-04（用户 00:13）：指数改为界面画图，结论只留图注 + 板块与原因；图表模型里的数字 = 结构化源
        assertTrue("★ 11-04：不再加内部图注：" + r.answer, r.answer.startsWith("板块：AI 硬件走弱") && !r.answer.contains("结构化"));
        assertFalse(r.answer, r.answer.contains("3608.10"));
        MarketChart.Model cm = MarketChart.match(r.answer, System.currentTimeMillis());
        assertNotNull(cm);
        assertTrue(cm.describe(), cm.describe().contains("上证指数=3608.10,-0.82%") && cm.describe().contains("富时100=10697.32"));
        assertFalse("不回应内部提示：" + r.answer, r.answer.contains("补上"));
        assertTrue(r.answer, r.answer.contains("板块：AI 硬件走弱"));
    }

    @Test public void agentLoopRewritesOnceWhenRegionWordingContradictsData() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(AgentLoopArchBTest.call("web__search", "{\"query\":\"今天 美股 板块\"}"));
        llm.replies.add(AgentLoopArchBTest.call("web__read", "{\"url\":\"https://www.ithome.com/0/1.htm\"}"));
        llm.replies.add(AgentLoopTest.say("美股走强，科技股领涨（11-03，来源 ithome.com）。"));
        llm.replies.add(AgentLoopTest.say("美股三大股指涨跌分化，科技股偏强（11-03，来源 ithome.com）。"));
        AgentLoop loop = new AgentLoop(CapabilityRegistry.getInstance(), llm).setWebBackend(new AgentLoopArchBTest.FakeWeb())
                .setMarketFetcher(new FakeSrc(fx("tencent-20261103.txt"), null));
        AgentLoop.Result r = loop.run("今天全球股市行情", new AgentLoopArchBTest.Host(), 0);
        assertTrue(r.reason, r.finished);
        assertTrue("开局带地区涨跌事实", lastUser(llm.messagesSeen.get(0)).contains("地区涨跌事实"));
        String nudge = lastUser(llm.messagesSeen.get(3));
        assertTrue(nudge, nudge.startsWith("（内部核对") && nudge.contains("美股") && nudge.contains("涨跌互现"));
        assertEquals("美股三大股指涨跌分化，科技股偏强（11-03，来源 ithome.com）。", r.answer);
        assertFalse(r.answer.contains("内部核对"));
    }

    @Test public void noMarketFetcherKeepsOldBehaviour() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("最近金价走势：国际金价 4000 美元 / 盎司，国内 900 元 / 克，涨 1%（来源 sina.com.cn、eastmoney.com）。"));
        AgentLoop loop = new AgentLoop(CapabilityRegistry.getInstance(), llm).setWebBackend(new AgentLoopArchBTest.FakeWeb())
                .setMarketFetcher(new FakeSrc(fx("tencent-20261103.txt"), null));
        AgentLoop.Result r = loop.run("最近金价走势", new AgentLoopArchBTest.Host(), 0);
        assertFalse("非股市问题不取指数", lastUser(llm.messagesSeen.get(0)).contains("系统数据"));
        assertFalse(r.answer == null ? "" : r.answer, (r.answer == null ? "" : r.answer).startsWith("主要指数"));
    }

    static String lastUser(JSONArray msgs) throws Exception {
        for (int i = msgs.length() - 1; i >= 0; i--) {
            if ("user".equals(msgs.getJSONObject(i).optString("role"))) return msgs.getJSONObject(i).optString("content");
        }
        return "";
    }
}
