package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.router.longctx.LongCtx;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ fix/1123-longctx-phone：AgentLoop 接长对话 —— 有历史时本轮消息最后一行是最新一句；纯聊天收工前出口自检打回一次。 */
public class AgentLoopLongCtxTest {

    @Before public void setUp() {
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
        ConversationMemory.getInstance().clear();
    }

    @After public void tearDown() {
        ConversationMemory.getInstance().clear();
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
    }

    static String lastUser(JSONArray ms) {
        return ms.optJSONObject(ms.length() - 1).optString("content");
    }

    @Test public void firstTurnNoTail() {
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run("鸡蛋煮几分钟最好", new AgentLoopTest.FakeExec(), 0);
        assertEquals("没有历史：不自检", 1, llm.messagesSeen.size());
        assertFalse(lastUser(llm.messagesSeen.get(0)).contains(LongCtx.LATEST));
    }

    @Test public void tailAndRecheck() {
        ConversationMemory.getInstance().beginTurn("天空为什么是蓝色的");
        ConversationMemory.getInstance().record("天空为什么是蓝色的", "天空是蓝色，是因为阳光里的蓝光被空气散射得最多。");
        ConversationMemory.getInstance().beginTurn("鸡蛋煮几分钟最好");
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        llm.replies.add(AgentLoopTest.say("水开后煮六分钟是溏心，九分钟全熟。"));
        AgentLoop.Result r = new AgentLoop(CapabilityRegistry.getInstance(), llm).run("鸡蛋煮几分钟最好", new AgentLoopTest.FakeExec(), 0);
        String first = lastUser(llm.messagesSeen.get(0));
        assertTrue(first, first.endsWith(LongCtx.LATEST + "#2 用户：鸡蛋煮几分钟最好"));
        assertTrue(first.contains("已结：#1"));
        assertEquals("打回重答一次", 2, llm.messagesSeen.size());
        String redo = lastUser(llm.messagesSeen.get(1));
        assertTrue(redo, redo.startsWith("【系统提示】上一次回答一开头就把用户的话复述了一遍"));
        assertTrue(redo.endsWith(LongCtx.LATEST + "鸡蛋煮几分钟最好"));
        assertTrue(r.answer, r.answer.contains("六分钟"));
        assertEquals(1, ConversationMemory.getInstance().retries());
    }

    @Test public void onlyOnceAndOffSwitch() {
        ConversationMemory.getInstance().record("天空为什么是蓝色的", "天空是蓝色，是因为散射。");
        AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
        llm.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        llm.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run("鸡蛋煮几分钟最好", new AgentLoopTest.FakeExec(), 0);
        assertEquals("只打回一次", 2, llm.messagesSeen.size());
        LongCtx.setEnabled(false);
        AgentLoopTest.ScriptedLlm llm2 = new AgentLoopTest.ScriptedLlm();
        llm2.replies.add(AgentLoopTest.say("你问的是「鸡蛋煮几分钟最好」。"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm2).run("鸡蛋煮几分钟最好", new AgentLoopTest.FakeExec(), 0);
        assertEquals(1, llm2.messagesSeen.size());
        assertFalse("总开关关：消息原样", lastUser(llm2.messagesSeen.get(0)).contains(LongCtx.LATEST));
    }
}
