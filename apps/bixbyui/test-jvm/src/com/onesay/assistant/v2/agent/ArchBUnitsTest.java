package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.island.Subgoal;
import com.onesay.assistant.v2.s1.AskUserBroker;
import com.onesay.assistant.v2.search.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ arch/agent-loop（B 路）：web 工具观察值 / 账本 / ask_user 辅助 / 结局 / 配置 的纯函数单测。 */
public class ArchBUnitsTest {

    @After public void reset() {
        LoopConfig.overrideForTest(null, null, null, null);
        AskUserSupport.clearForTest();
    }

    // ------------------------------------------------------------------ WebTools

    @Test public void domainAndCredibility() {
        assertEquals("gov.cn", WebTools.domainOf("https://www.gov.cn/zhengce/x.html"));
        assertEquals("example.com", WebTools.domainOf("http://user@example.com:8080/a?b#c"));
        assertEquals("高", WebTools.credibility("https://www.miit.gov.cn/a"));
        assertEquals("高", WebTools.credibility("https://cs.stanford.edu/x"));
        assertEquals("中", WebTools.credibility("https://zh.wikipedia.org/wiki/X"));
        assertEquals("中", WebTools.credibility("https://www.ithome.com/0/1.htm"));
        assertEquals("低", WebTools.credibility("https://some-blog.xyz/post"));
        assertEquals("低", WebTools.credibility(""));
        // 伪装：子串命中不算
        assertEquals("低", WebTools.credibility("https://gov.cn.evil.com/"));
    }

    @Test public void normalizeUrlRejectsNonHttp() {
        assertEquals("https://jd.com/x", WebTools.normalizeUrl("jd.com/x"));
        assertEquals("http://a.cn", WebTools.normalizeUrl(" http://a.cn "));
        assertNull(WebTools.normalizeUrl("javascript:alert(1)"));
        assertNull(WebTools.normalizeUrl("file:///sdcard/a"));
        assertNull(WebTools.normalizeUrl("intent://x#Intent;end"));
        assertNull(WebTools.normalizeUrl("localhost"));
        assertNull(WebTools.normalizeUrl(null));
    }

    @Test public void searchObservationCarriesSourcesTrustAndDeepLinkFallback() throws Exception {
        List<SearchResult> rs = new ArrayList<SearchResult>();
        rs.add(new SearchResult("标题一", "https://www.gov.cn/a", "摘要一"));
        rs.add(new SearchResult("标题二", "https://blog.xyz/b", "摘要二"));
        JSONObject o = new JSONObject(WebTools.searchObservation("扫地机器人", "tavily", rs, 5));
        assertTrue(o.getBoolean("ok"));
        assertEquals(WebTools.TRUST_LABEL, o.getString("trust"));
        JSONArray a = o.getJSONArray("results");
        assertEquals(2, a.length());
        assertEquals("https://www.gov.cn/a", a.getJSONObject(0).getString("url"));
        assertEquals("高", a.getJSONObject(0).getString("credibility"));
        assertEquals("低", a.getJSONObject(1).getString("credibility"));
        assertEquals("blog.xyz", a.getJSONObject(1).getString("domain"));

        JSONObject none = new JSONObject(WebTools.searchObservation("扫地 机器人", "bing-html", new ArrayList<SearchResult>(), 5));
        assertFalse(none.getBoolean("ok"));
        assertEquals("NO_RESULTS", none.getString("code"));
        assertTrue(none.getString("deep_link").startsWith("https://www.bing.com/search?q="));
        assertTrue(none.getString("deep_link").contains("%20"));
    }

    @Test public void readObservationTruncatesWithBudgetAndOffset() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5000; i++) b.append((char) ('a' + i % 26));
        WebTools.Page p = new WebTools.Page("https://ithome.com/x", b.toString(), null);
        JSONObject o = new JSONObject(WebTools.readObservation("https://ithome.com/x", p, 0, 12000, 3000, true));
        assertTrue(o.getBoolean("ok"));
        assertEquals("ok", o.getString("verdict"));
        assertEquals(3000, o.getInt("returned_chars"));
        assertEquals(3000, o.getInt("next_offset"));
        assertTrue(o.getBoolean("truncated"));
        assertTrue("正文包不可信标签", o.getString("content").startsWith("<不可信数据>"));
        assertEquals("中", o.getString("credibility"));
        // 续读
        JSONObject o2 = new JSONObject(WebTools.readObservation("https://ithome.com/x", p, 3000, 12000, 3000, true));
        assertEquals(2000, o2.getInt("returned_chars"));
        assertFalse(o2.has("next_offset"));
        // 预算只剩 500
        JSONObject o3 = new JSONObject(WebTools.readObservation("https://ithome.com/x", p, 0, 500, 3000, true));
        assertEquals(500, o3.getInt("returned_chars"));
        assertEquals(500, WebTools.returnedChars(o3.toString()));
        // 预算用完：只给来源
        JSONObject o4 = new JSONObject(WebTools.readObservation("https://ithome.com/x", p, 0, 0, 3000, true));
        assertEquals("", o4.getString("content"));
        assertEquals(0, WebTools.returnedChars(o4.toString()));
    }

    @Test public void readObservationDegradesToDeepLink() throws Exception {
        JSONObject noKey = new JSONObject(WebTools.readObservation("https://a.com/x", null, 0, 1000, 3000, false));
        assertFalse(noKey.getBoolean("ok"));
        assertEquals("READER_UNAVAILABLE", noKey.getString("code"));
        assertEquals("https://a.com/x", noKey.getString("deep_link"));
        JSONObject failed = new JSONObject(WebTools.readObservation("https://a.com/x",
                new WebTools.Page("https://a.com/x", null, "403"), 0, 1000, 3000, true));
        assertEquals("READ_FAILED", failed.getString("code"));
        assertEquals("failed", failed.getString("verdict"));
    }

    @Test public void verdicts() {
        assertEquals("needs_sign_in", WebTools.verdict(new WebTools.Page("u", "请先登录后查看全文", null)));
        assertEquals("refused", WebTools.verdict(new WebTools.Page("u", "请完成安全验证 captcha", null)));
        assertEquals("thin", WebTools.verdict(new WebTools.Page("u", "短短一句话", null)));
        assertEquals("failed", WebTools.verdict(new WebTools.Page("u", "", null)));
    }

    @Test public void collectSourcesFromSearchReadAndResearch() throws Exception {
        List<String> out = new ArrayList<String>();
        List<SearchResult> rs = Arrays.asList(new SearchResult("t", "https://a.cn/1", "s"), new SearchResult("t2", "https://b.cn/2", "s"));
        WebTools.collectSources(WebTools.searchObservation("q", "x", rs, 5), out);
        WebTools.collectSources(WebTools.readObservation("https://a.cn/1", new WebTools.Page("https://a.cn/1", "正文正文正文", null), 0, 100, 100, true), out);
        WebTools.collectSources("{\"ok\":true,\"sources\":[{\"url\":\"https://c.cn\"}]}", out);
        WebTools.collectSources("{\"ok\":false,\"url\":\"https://bad.cn\"}", out);
        assertEquals(Arrays.asList("https://a.cn/1", "https://b.cn/2", "https://c.cn"), out);
    }

    // ------------------------------------------------------------------ TodoLedger

    static JSONObject items(String json, boolean replace) throws Exception {
        return new JSONObject().put("items", new JSONArray(json)).put("replace", replace);
    }

    @Test public void ledgerAddUpdateSingleDoingAndRender() throws Exception {
        TodoLedger l = new TodoLedger();
        assertNull(l.apply(items("[{\"title\":\"查明天天气\",\"state\":\"doing\"},{\"title\":\"设闹钟\"},{\"title\":\"发微信告诉老婆\"}]", false)));
        List<Subgoal> g = l.snapshot();
        assertEquals(3, g.size());
        assertEquals("g1", g.get(0).id);
        assertEquals(Subgoal.State.DOING, g.get(0).state);
        assertEquals(Subgoal.State.PENDING, g.get(1).state);
        // 只给 state 更新；新的 doing 胜出，旧 doing 回 pending
        assertNull(l.apply(items("[{\"id\":\"g1\",\"state\":\"done\"},{\"id\":\"g2\",\"state\":\"doing\"}]", false)));
        g = l.snapshot();
        assertEquals("查明天天气", g.get(0).title);
        assertEquals(Subgoal.State.DONE, g.get(0).state);
        assertEquals(Subgoal.State.DOING, g.get(1).state);
        assertNull(l.apply(items("[{\"id\":\"g3\",\"state\":\"doing\"}]", false)));
        assertEquals(Subgoal.State.PENDING, l.snapshot().get(1).state);
        assertEquals(1, l.count(Subgoal.State.DOING));
        JSONObject r = new JSONObject(l.render());
        assertEquals("1/3", r.getString("progress"));
        assertEquals(3, r.getJSONArray("items").length());
        // 收尾落定
        assertTrue(l.settleDoing(Subgoal.State.FAILED));
        assertEquals(Subgoal.State.FAILED, l.snapshot().get(2).state);
        // replace
        assertNull(l.apply(items("[{\"title\":\"重新规划\"}]", true)));
        assertEquals(1, l.snapshot().size());
        assertEquals("g1", l.snapshot().get(0).id);
    }

    @Test public void ledgerRejectsEmptyAndCapsAtMax() throws Exception {
        TodoLedger l = new TodoLedger();
        assertNotNull(l.apply(new JSONObject()));
        assertNotNull(l.apply(items("[{\"state\":\"doing\"}]", false)));   // 新项没标题
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Subgoal.MAX + 3; i++) sb.append(i > 0 ? "," : "").append("{\"title\":\"t").append(i).append("\"}");
        sb.append("]");
        assertNotNull(l.apply(items(sb.toString(), false)));
        assertEquals(Subgoal.MAX, l.snapshot().size());
    }

    // ------------------------------------------------------------------ AskUserSupport

    @Test public void optionsSanitized() {
        assertEquals(Arrays.asList("A", "B", "C", "D"), AskUserSupport.sanitizeOptions(Arrays.asList(" A", "B", "", "A", "C", "D", "E")));
        assertTrue(AskUserSupport.sanitizeOptions(Arrays.asList("只有一个")).isEmpty());
        assertTrue(AskUserSupport.sanitizeOptions(null).isEmpty());
    }

    @Test public void voiceAnswerMatching() {
        List<String> o = Arrays.asList("京东", "淘宝", "拼多多");
        assertEquals("淘宝", AskUserSupport.matchAnswer("第二个", o));
        assertEquals("淘宝", AskUserSupport.matchAnswer("2", o));
        assertEquals("拼多多", AskUserSupport.matchAnswer("选C", o));
        assertEquals("京东", AskUserSupport.matchAnswer("就京东吧", o));
        assertEquals("拼多多", AskUserSupport.matchAnswer("用拼多多买", o));
        assertEquals("随便哪个都行", AskUserSupport.matchAnswer("随便哪个都行。", o));
        assertEquals("明早七点", AskUserSupport.matchAnswer("明早七点", new ArrayList<String>()));
        assertNull(AskUserSupport.matchAnswer("  ", o));
    }

    @Test public void interceptOnlyWhenQuestionOpen() {
        assertNull(AskUserSupport.interceptVoice("第二个"));
        AskUserSupport.open("ask-9", Arrays.asList("是", "否"));
        String[] a = AskUserSupport.interceptVoice("第二个");
        assertArrayEquals(new String[]{"ask-9", "否"}, a);
        assertNull("取消不当回答", AskUserSupport.interceptVoice("取消"));
    }

    static final class FakeClock implements AskUserSupport.Clock {
        long t;
        Runnable onSleep;
        @Override public long now() { return t; }
        @Override public void sleep(long ms) { t += ms; if (onSleep != null) onSleep.run(); }
    }

    @Test public void awaitReturnsAnswerTimeoutOrCancel() throws Exception {
        final AskUserBroker b = new AskUserBroker();
        // 回答
        b.register("q1");
        AskUserSupport.open("q1", Arrays.asList("x", "y"));
        final FakeClock c = new FakeClock();
        c.onSleep = new Runnable() { @Override public void run() { if (c.t >= 1000) b.answer("q1", "y"); } };
        assertEquals("y", AskUserSupport.await(b, "q1", 60000, null, null, c));
        assertNull("回答后清掉当前问题", AskUserSupport.openId());
        // 超时 → 撤回
        b.register("q2");
        final List<String> withdrawn = new ArrayList<String>();
        FakeClock c2 = new FakeClock();
        assertNull(AskUserSupport.await(b, "q2", 60000, null, new AskUserSupport.Withdraw() {
            @Override public void withdraw(String id) { withdrawn.add(id); b.expire(id); }
        }, c2));
        assertEquals(Arrays.asList("q2"), withdrawn);
        assertTrue(c2.t >= 60000);
        assertFalse("超时后再回填不收", b.answer("q2", "late"));
        // 取消
        b.register("q3");
        final boolean[] cancel = {false};
        final FakeClock c3 = new FakeClock();
        c3.onSleep = new Runnable() { @Override public void run() { if (c3.t >= 500) cancel[0] = true; } };
        assertNull(AskUserSupport.await(b, "q3", 60000, new AskUserSupport.Cancel() {
            @Override public boolean isCancelled() { return cancel[0]; }
        }, null, c3));
        assertTrue(c3.t < 60000);
        assertEquals(0, b.size());
    }

    // ------------------------------------------------------------------ Outcome / LoopConfig

    @Test public void endSubtypeMapping() {
        assertEquals(Outcome.NEEDS_USER, Outcome.ofEndSubtype("login_required"));
        assertEquals(Outcome.NEEDS_USER, Outcome.ofEndSubtype("captcha"));
        assertEquals(Outcome.NEEDS_USER, Outcome.ofEndSubtype("no_answer"));
        assertEquals(Outcome.NEEDS_USER, Outcome.ofEndSubtype("login_incomplete"));   // Mika 原名也认
        assertEquals(Outcome.BLOCKED, Outcome.ofEndSubtype("blocked"));
        assertEquals(Outcome.BLOCKED, Outcome.ofEndSubtype("device_unavailable"));
        assertEquals(Outcome.NOT_DONE, Outcome.ofEndSubtype("route_failed"));
        assertEquals(Outcome.NOT_DONE, Outcome.ofEndSubtype("胡写"));
        assertEquals("route_failed", Outcome.normalizeSubtype("胡写"));
        assertEquals("login_required", Outcome.normalizeSubtype("login_incomplete"));
        assertEquals("needs_user", Outcome.NEEDS_USER.wire());
    }

    @Test public void configDefaultsAndClamp() {
        assertEquals(1500, LoopConfig.maxTokensPerRound(null));
        assertEquals(8 * 1500, LoopConfig.unattendedTotalTokens(null));
        assertTrue(LoopConfig.webToolsEnabled(null));
        assertTrue(LoopConfig.researchInLoop(null));
        assertEquals(60, LoopConfig.askTimeoutSec(null));
        LoopConfig.overrideForTest(50, null, null, null);
        assertEquals(LoopConfig.MIN_MAX_TOKENS_ROUND, LoopConfig.maxTokensPerRound(null));
        LoopConfig.overrideForTest(99999, null, false, false);
        assertEquals(LoopConfig.MAX_MAX_TOKENS_ROUND, LoopConfig.maxTokensPerRound(null));
        assertFalse(LoopConfig.webToolsEnabled(null));
        assertFalse(LoopConfig.researchInLoop(null));
    }

    // ------------------------------------------------------------------ LoopTools

    @Test public void toolSchemasAndOffer() throws Exception {
        JSONArray t = new JSONArray();
        LoopTools.append(t, new LoopTools.Offer(true, true));
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < t.length(); i++) names.add(t.getJSONObject(i).getJSONObject("function").getString("name"));
        assertEquals(Arrays.asList("web__search", "web__read", "research__spawn", "task__todo", "ask_user", "end_not_done"), names);
        // 再追加不重复
        LoopTools.append(t, new LoopTools.Offer(true, true));
        assertEquals(6, t.length());
        JSONArray t2 = new JSONArray();
        LoopTools.append(t2, new LoopTools.Offer(false, false));
        assertEquals(2, t2.length());   // task.todo + end_not_done 常驻
        JSONObject ask = t.getJSONObject(4).getJSONObject("function").getJSONObject("parameters");
        assertEquals(4, ask.getJSONObject("properties").getJSONObject("options").getInt("maxItems"));
        assertTrue(LoopTools.isWebSign("research.spawn"));
        assertTrue(LoopTools.isControlSign("ask_user"));
        assertFalse(LoopTools.isLoopSign("phone.operate"));
        assertEquals(com.onesay.assistant.v2.capability.SideEffect.NONE, LoopTools.describe("web.read").sideEffect);
        // ★ arch/integrate：规则段搬进 assets/agent_prompt.md（loop_tools 段），按当轮挂载的常驻工具逐行取舍
        JSONArray webOnly = new JSONArray();
        LoopTools.append(webOnly, new LoopTools.Offer(true, false));
        String p = com.onesay.assistant.v2.prompt.AgentPromptAssembler.build(null, webOnly);
        assertTrue(p.contains("【查信息、任务清单与问人（常驻工具）】"));
        assertTrue(p.contains("先用 web.search"));
        assertFalse("没提供 ask_user 就不提", p.contains("用 ask_user 问一句"));
        JSONArray askOnly = new JSONArray();
        LoopTools.append(askOnly, new LoopTools.Offer(false, true));
        String p2 = com.onesay.assistant.v2.prompt.AgentPromptAssembler.build(null, askOnly);
        assertTrue(p2.contains("用 ask_user 问一句"));
        assertFalse("没提供 web.* 就不提搜索", p2.contains("先用 web.search"));
    }
}
