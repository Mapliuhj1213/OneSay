package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.errand.SimplePageReader;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ★ fix/research-depth（用户 2026-11-03 12:04：调研普遍太浅）：行情 / 市场 / 走势 / 二手价 / 客单价 / 对比类
 * → 进 AgentLoop 先拆维度检索；收工门槛 ≥2 个独立来源 + ≥1 次 web.read；终答缺数字 / 缺例子 nudge 一次。
 */
public class ResearchDepthTest {

    static final String[] RESEARCH = {"今天全球股市行情", "二手奢侈品行情与客单价", "最近金价走势", "现在二手iPhone 15什么价", "最近美股科技板块涨跌"};

    @Test public void researchSentencesAreRecognized() {
        for (String q : RESEARCH) assertEquals(q, ResearchGate.Kind.RESEARCH, ResearchGate.kindOf(q));
        for (String q : new String[]{"今天天气怎么样", "金价多少", "设置一个明天的提醒", "打开股市App", "你好", "茅台股价多少"}) {
            assertEquals(q, ResearchGate.Kind.NONE, ResearchGate.kindOf(q));
        }
        assertEquals("购物优先", ResearchGate.Kind.SHOP, ResearchGate.kindOf("帮我买雷蛇巴塞利斯蛇v3"));
    }

    @Test public void researchNotDirectAsk() {
        for (String q : RESEARCH) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertEquals(q, "5m-deep-research", d.rule);
            assertTrue(q + " 要进 AgentLoop 先检索，不走纯问答直答", d.useAgentLoop);
        }
        // 单点事实照旧问答
        TriageDecider.Decision w = TriageDecider.decide("今天天气怎么样", TriageDecider.Signals.offline());
        assertFalse(w.useAgentLoop);
    }

    @Test public void researchFinishGate() throws Exception {
        ResearchGate.Evidence ev = new ResearchGate.Evidence();
        ev.onStep("web.search", new JSONObject().put("query", "全球股市"), "{\"results\":[{\"url\":\"https://finance.sina.com.cn/a\"}]}", true);
        String vague = "今天全球股市整体偏强，科技股领涨，投资者情绪回暖。";
        assertEquals(ResearchGate.RESEARCH_EVIDENCE_NUDGE, ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, vague, false, false));
        ev.onStep("web.read", new JSONObject().put("url", "https://finance.sina.com.cn/a"), "{}", true);
        ev.onStep("web.read", new JSONObject().put("url", "https://www.eastmoney.com/b"), "{}", true);
        assertEquals("证据够了但没数字 → 缺数字提醒", ResearchGate.RESEARCH_DATA_NUDGE,
                ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, vague, false, false));
        assertNull("各一次", ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, vague, true, true));
        String good = "美股：道指 +0.5%、纳指 +1.2%；欧股：斯托克50 -0.3%（逆势）；A 股：上证 3950 点 -0.4%，港股恒指 +0.8%。数据 11-03，来源新浪财经、东方财富。";
        assertTrue(ResearchGate.numberCount(good) >= 3);
        assertNull(ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, ev, good, false, false));
        assertTrue(ResearchGate.isEvidenceNudge(ResearchGate.RESEARCH_EVIDENCE_NUDGE));
        assertFalse(ResearchGate.isEvidenceNudge(ResearchGate.RESEARCH_DATA_NUDGE));
    }

    @Test public void startHintAndSkillCarryDimensions() throws Exception {
        String h = ResearchGate.startHint(ResearchGate.Kind.RESEARCH);
        for (String must : new String[]{"拆维度", "美股", "A 股港股", "逆势", "二手", "3–5 个代表款", "客单价", "日期", "来源", "相反走势", "待核实", "web.read"}) {
            assertTrue("开局提示缺「" + must + "」", h.contains(must));
        }
        assertTrue(ResearchGate.startHint(ResearchGate.Kind.SHOP).contains("不问预算"));
        assertNull(ResearchGate.startHint(ResearchGate.Kind.NONE));
        String s = new String(Files.readAllBytes(new File("app/assets/skill_market_research.md").toPath()), StandardCharsets.UTF_8);
        for (String must : new String[]{"拆维度", "强势板块和逆势板块", "3–5 个代表款", "客单价区间", "日期与来源", "走势相反", "待核实", "web.read"}) {
            assertTrue("skill_market_research 缺「" + must + "」", s.contains(must));
        }
        String loop = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/router/AgentLoop.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(loop.contains("ResearchGate.startHint(gateKind)"));
    }

    @Test public void simpleReaderStripsHtml() {
        String html = "<html><head><title>x</title><style>.a{}</style></head><body><script>var p=1;</script><div>京东自营 527.12 元</div><p>天猫&nbsp;849&yen;</p><!-- c --></body></html>";
        String t = SimplePageReader.toText(html);
        assertFalse(t, t.contains("var p") || t.contains(".a{}") || t.contains("<"));
        assertTrue(t, t.contains("京东自营 527.12 元") && t.contains("天猫 849¥"));
    }
}
