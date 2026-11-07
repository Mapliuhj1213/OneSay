package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.agent.WebTools;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.search.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 午：AgentLoop 同轮多个查信息调用并行取回；观测顺序 / 证据门槛 / 思考中事件不变。 */
public class AgentLoopParallelWebTest {

    static JSONObject calls(String[][] wireArgs) throws Exception {
        JSONArray tc = new JSONArray();
        int i = 0;
        for (String[] wa : wireArgs) {
            JSONObject fn = new JSONObject();
            fn.put("name", wa[0]);
            fn.put("arguments", wa[1]);
            JSONObject c = new JSONObject();
            c.put("id", "p" + (i++) + "_" + System.nanoTime());
            c.put("type", "function");
            c.put("function", fn);
            tc.put(c);
        }
        JSONObject m = new JSONObject();
        m.put("role", "assistant");
        m.put("content", "");
        m.put("tool_calls", tc);
        return m;
    }

    static final class SlowWeb implements WebTools.Backend {
        final java.util.concurrent.atomic.AtomicInteger searches = new java.util.concurrent.atomic.AtomicInteger(), reads = new java.util.concurrent.atomic.AtomicInteger();
        @Override public String searchProvider() { return "fake"; }
        @Override public List<SearchResult> search(String q, int max) {
            searches.incrementAndGet();
            nap(400);
            return new ArrayList<SearchResult>(Arrays.asList(new SearchResult("摩托车行情 " + q, "https://www.58moto.com/a", "二手 0.7–0.9 万")));
        }
        @Override public boolean readReady() { return true; }
        @Override public WebTools.Page read(String url, String q) {
            reads.incrementAndGet();
            nap(url.contains("slow") ? 5000 : 400);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 50; i++) b.append("铃木UY125 二手 7000 元。");
            return new WebTools.Page(url, b.toString(), null);
        }
        static void nap(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    static final class PhaseHost extends AgentLoopArchBTest.Host implements AgentLoop.PhaseEvents {
        final List<Boolean> phases = new ArrayList<Boolean>();
        @Override public void onThinking(boolean on) { phases.add(on); }
    }

    @Test public void searchesAndReadsOfOneRoundRunInParallel() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(calls(new String[][]{{"web__search", "{\"query\":\"二手摩托车 价格\"}"}, {"web__search", "{\"query\":\"二手摩托车 行情\"}"}}));
        llm.replies.add(calls(new String[][]{{"web__read", "{\"url\":\"https://www.58moto.com/a\"}"}, {"web__read", "{\"url\":\"https://post.smzdm.com/p/1\"}"},
                {"web__read", "{\"url\":\"https://slow.example.org/x\"}"}}));
        llm.replies.add(AgentLoopTest.say("二手摩托车：通勤踏板 0.7–0.9 万（来源：摩托范、什么值得买）。"));
        SlowWeb web = new SlowWeb();
        PhaseHost host = new PhaseHost();
        long t0 = System.currentTimeMillis();
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).setWebBackend(web).run("帮我调研一下二手摩托车价格", host, 0);
        long el = System.currentTimeMillis() - t0;
        assertTrue(r.finished);
        assertEquals(2, web.searches.get());
        assertEquals(3, web.reads.get());
        // 串行要 0.4×2 + 0.4×2 + 5 = 6.6 s；并行：搜索 0.4 + 读页（够 2 站后 1.5 s 不等慢页）≈ 2.3 s
        StringBuilder dbg = new StringBuilder();
        for (AgentLoop.Step st : r.steps) dbg.append(st.tool).append(" ").append(st.ok).append(" | ");
        assertTrue("并行 + 够了不等：实际 " + el + " ms " + dbg + " llmCalls=" + llm.toolsSeen.size(), el < 5000L);   // 串行要 ≥ 6.6 s
        assertEquals("观测按调用顺序回灌", "web.search", r.steps.get(0).tool);
        assertEquals("web.read", r.steps.get(2).tool);
        assertTrue("慢页如实写超时", r.steps.get(4).observation.contains("读取超时"));
        assertTrue("每轮等模型都有 思考中 进 / 出", host.phases.size() >= 6 && host.phases.get(0) && !host.phases.get(1));
    }
}
