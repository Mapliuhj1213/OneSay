package com.onesay.assistant.v2.net;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ int/1102 思考模式三档（关 / 仅多步任务 / 全部）：请求体按档位正确；思维链不上屏 / 不朗读 / 不进历史；关档与原行为逐字节一致。
 */
public class ThinkingModeTest {

    @After public void reset() {
        ThinkingMode.setLevel("off");
        LlmModels.setChat("");
    }

    private static JSONObject askBody(ThinkingMode.Purpose p) throws Exception {
        return new JSONObject(com.onesay.assistant.v2.router.L2LLMCompilerAccess.buildBody("SYS", null, "你好", 800, true, p));
    }

    private static JSONObject agentReq() throws Exception {
        JSONObject r = new JSONObject();
        r.put("model", LlmModels.chat());
        r.put("temperature", 0.3);
        r.put("max_tokens", 1500);
        JSONArray m = new JSONArray();
        m.put(new JSONObject().put("role", "system").put("content", "s"));
        m.put(new JSONObject().put("role", "user").put("content", "旧问题"));
        m.put(new JSONObject().put("role", "assistant").put("content", "旧回答"));
        m.put(new JSONObject().put("role", "user").put("content", "帮我做"));
        r.put("messages", m);
        r.put("tools", new JSONArray().put(new JSONObject().put("type", "function")));
        r.put("tool_choice", "required");
        return r;
    }

    @Test public void parseAndLevels() {
        assertEquals(ThinkingMode.Level.OFF, ThinkingMode.parse(null));
        assertEquals(ThinkingMode.Level.OFF, ThinkingMode.parse("乱写"));
        assertEquals(ThinkingMode.Level.AGENT, ThinkingMode.parse("agent"));
        assertEquals(ThinkingMode.Level.AGENT, ThinkingMode.parse("仅多步任务"));
        assertEquals(ThinkingMode.Level.ALL, ThinkingMode.parse("ALL"));
        assertEquals("off", ThinkingMode.key(ThinkingMode.Level.OFF));
        for (ThinkingMode.Level l : ThinkingMode.Level.values()) assertFalse("内部短调用任何档位都不开", ThinkingMode.enabledFor(l, ThinkingMode.Purpose.INTERNAL));
        assertFalse(ThinkingMode.enabledFor(ThinkingMode.Level.OFF, ThinkingMode.Purpose.AGENT));
        assertTrue(ThinkingMode.enabledFor(ThinkingMode.Level.AGENT, ThinkingMode.Purpose.AGENT));
        assertFalse(ThinkingMode.enabledFor(ThinkingMode.Level.AGENT, ThinkingMode.Purpose.ASK));
        assertTrue(ThinkingMode.enabledFor(ThinkingMode.Level.ALL, ThinkingMode.Purpose.ASK));
    }

    @Test public void offIsByteIdenticalToBefore() throws Exception {
        ThinkingMode.setLevel("off");
        String before = com.onesay.assistant.v2.router.L2LLMCompilerAccess.buildBody("SYS", null, "你好", 800, true);   // 原签名（INTERNAL）
        String ask = com.onesay.assistant.v2.router.L2LLMCompilerAccess.buildBody("SYS", null, "你好", 800, true, ThinkingMode.Purpose.ASK);
        assertEquals("关档：问答请求体与原来逐字节一致", before, ask);
        JSONObject o = new JSONObject(ask);
        assertEquals("disabled", o.getJSONObject("thinking").getString("type"));
        assertTrue(o.has("temperature"));
        JSONObject a = agentReq();
        String s0 = a.toString();
        ThinkingMode.apply(a, ThinkingMode.Purpose.AGENT);
        assertEquals("关档：多步任务请求体不动", s0, a.toString());
    }

    @Test public void agentLevelOnlyLoopThinks() throws Exception {
        ThinkingMode.setLevel("agent");
        assertEquals("disabled", askBody(ThinkingMode.Purpose.ASK).getJSONObject("thinking").getString("type"));
        JSONObject a = LlmModels.prepare(ThinkingMode.apply(agentReq(), ThinkingMode.Purpose.AGENT));
        assertEquals("enabled", a.getJSONObject("thinking").getString("type"));
        assertFalse("思考模式不支持 temperature → 剔掉", a.has("temperature"));
        assertEquals("思维链算生成 → 多留预算", 1500 + ThinkingMode.THINK_EXTRA_TOKENS, a.getInt("max_tokens"));
        assertEquals("required 在思考模式 400 → auto", "auto", a.getString("tool_choice"));
        JSONArray m = a.getJSONArray("messages");
        assertEquals("带 tools：旧轮 assistant 补 reasoning_content", "", m.getJSONObject(2).getString("reasoning_content"));
        assertFalse(m.getJSONObject(1).has("reasoning_content"));
    }

    @Test public void allLevelAskThinks() throws Exception {
        ThinkingMode.setLevel("all");
        JSONObject o = askBody(ThinkingMode.Purpose.ASK);
        assertEquals("enabled", o.getJSONObject("thinking").getString("type"));
        assertFalse(o.has("temperature"));
        assertFalse(o.has("tools"));
        assertEquals("disabled", new JSONObject(com.onesay.assistant.v2.router.L2LLMCompilerAccess.buildBody("SYS", null, "x", 100, false))
                .getJSONObject("thinking").getString("type"));
        // 非 DeepSeek V4 系（qwen-vl 等）不动
        JSONObject q = new JSONObject().put("model", "qwen3-vl-plus").put("temperature", 0.1);
        ThinkingMode.apply(q, ThinkingMode.Purpose.ASK);
        assertFalse(q.has("thinking"));
        assertTrue(q.has("temperature"));
    }

    @Test public void reasoningNeverShownSpokenOrRemembered() throws Exception {
        String rLine = "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先想想用户要什么\",\"content\":null}}]}";
        String cLine = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"答案\"}}]}";
        assertNull("流式：思维链增量不上屏", LlmResponse.deltaOf(rLine));
        assertEquals("先想想用户要什么", ThinkingMode.reasoningDeltaOf(rLine));
        assertEquals("答案", LlmResponse.deltaOf(cLine));
        assertNull(ThinkingMode.reasoningDeltaOf(cLine));
        // 非流式：content() 只有答案（朗读 / 上屏 / 进会话历史都用它）
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"reasoning_content\":\"内心独白\",\"content\":\"最终答案\"}}]}";
        LlmResponse r = LlmResponse.parse(200, body);
        assertEquals("最终答案", r.content());
        assertFalse(r.content().contains("内心独白"));
        // AgentLoop：思维链只回传到本任务的消息数组
        JSONObject asst = new JSONObject().put("role", "assistant").put("content", "");
        ThinkingMode.carryReasoning(r.message, asst);
        assertEquals("内心独白", asst.getString("reasoning_content"));
        JSONObject noR = new JSONObject().put("role", "assistant");
        ThinkingMode.carryReasoning(new JSONObject().put("content", "x"), noR);
        assertFalse(noR.has("reasoning_content"));
        // 会话历史只记问句 + 终稿
        com.onesay.assistant.v2.router.ConversationMemory mem = com.onesay.assistant.v2.router.ConversationMemory.getInstance();
        mem.clear();
        mem.record("问", r.content(), false);
        assertFalse(mem.snapshot().get(0).answer.contains("内心独白"));
        mem.clear();
    }
}
