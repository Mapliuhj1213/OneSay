package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.agent.AskUserSupport;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 ③：一个任务只问一次；2–3 个选项补「你决定」；用户说「你决定 / 随便」→ 按默认接着做。 */
public class AskOnceDefaultTest {

    static AgentLoop loop(AgentLoopArchBTest.ScriptedLlm llm) {
        return new AgentLoop(CapabilityRegistry.getInstance(), llm);
    }

    @Test public void secondAskIsRefusedAndLoopContinues() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("ask_user", "{\"question\":\"去哪个城市？\",\"options\":[\"杭州\",\"上海\"]}"));
        llm.replies.add(AgentLoopTest.call("ask_user", "{\"question\":\"预算多少？\",\"options\":[\"300以内\",\"500以内\"]}"));
        llm.replies.add(AgentLoopTest.say("按 500 元以内先给你列杭州的几家（假设：今晚入住一晚）。"));
        AgentLoopArchBTest.Host host = new AgentLoopArchBTest.Host();
        host.answer = "杭州";
        AgentLoop.Result r = loop(llm).run("帮我订个酒店", host, 0);
        assertEquals("只问了一次", 1, host.asked.size());
        assertTrue(host.asked.get(0), host.asked.get(0).contains("[杭州, 上海, 你决定]"));
        JSONObject second = new JSONObject(r.steps.get(1).observation);
        assertFalse(second.getBoolean("ok"));
        assertEquals("ALREADY_ASKED", second.getString("code"));
        assertTrue(second.getString("note").contains("假设"));
        assertTrue("第二次不问也不结束，接着做", r.finished);
    }

    @Test public void delegatedAnswerMeansProceedWithDefault() throws Exception {
        AgentLoopArchBTest.ScriptedLlm llm = new AgentLoopArchBTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.call("ask_user", "{\"question\":\"要哪种？\",\"options\":[\"有线\",\"无线\",\"都行\"]}"));
        llm.replies.add(AgentLoopTest.say("我按无线版给你比价（你可以再改）。"));
        AgentLoopArchBTest.Host host = new AgentLoopArchBTest.Host();
        host.answer = "你决定";
        AgentLoop.Result r = loop(llm).run("帮我买个鼠标", host, 0);
        assertTrue("已有「都行」不再补「你决定」", host.asked.get(0).contains("[有线, 无线, 都行]"));
        JSONObject o = new JSONObject(r.steps.get(0).observation);
        assertTrue(o.getBoolean("ok"));
        assertTrue(o.getString("note"), o.getString("note").contains("用户让你决定") && o.getString("note").contains("不要再问"));
    }

    @Test public void supportHelpers() {
        assertEquals(1, AskUserSupport.MAX_ASKS_PER_TASK);
        for (String a : new String[]{"你决定", "随便", "都行吧", "好的", "你看着办", "默认"}) assertTrue(a, AskUserSupport.delegated(a));
        for (String a : new String[]{"杭州", "500以内", "随便找个便宜的", null}) assertFalse(String.valueOf(a), AskUserSupport.delegated(a));
        List<String> two = Arrays.asList("京东", "淘宝");
        assertEquals(Arrays.asList("京东", "淘宝", "你决定"), AskUserSupport.withDefault(two));
        List<String> four = Arrays.asList("a", "b", "c", "d");
        assertSame("满 4 个不补", four, AskUserSupport.withDefault(four));
        List<String> one = Arrays.asList("确定");
        assertSame("单选不补", one, AskUserSupport.withDefault(one));
    }
}
