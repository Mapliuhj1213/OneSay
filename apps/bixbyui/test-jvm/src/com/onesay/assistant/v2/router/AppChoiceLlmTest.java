package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * [r2/bike-chooser] 大模型同一次输出里带 app_choice（不额外再调一次）：
 * 编译通道 args.app_choice / 自然语言尾巴 → Invocation 的 args.app_choice；AgentLoop 终稿剥尾巴；
 * polish 截断不会截坏尾巴；三个通道的提示词都追加了规则；Android 层接线存在（源码断言）。
 */
public class AppChoiceLlmTest {

    static final String TAIL = "<app_choice>{\"category\":\"共享单车\",\"store_query\":\"共享单车\",\"apps\":[{\"label\":\"哈啰\",\"pkg\":\"com.jingyao.easybike\"}]}</app_choice>";

    @Test public void tailBecomesArgsField() throws Exception {
        JSONObject args = new JSONObject();
        L2LLMCompiler.putTextAndChoice(args, "可以用哈啰扫码。\n" + TAIL);
        assertEquals("可以用哈啰扫码。", args.getString("text"));
        assertEquals("共享单车", args.getJSONObject("app_choice").getString("store_query"));
        // 编译通道已给 args.app_choice：不被文字尾巴覆盖
        JSONObject given = new JSONObject().put("app_choice", new JSONObject().put("store_query", "单车"));
        L2LLMCompiler.putTextAndChoice(given, "好的" + TAIL);
        assertEquals("单车", given.getJSONObject("app_choice").getString("store_query"));
        // 没有尾巴：只有 text
        JSONObject plain = new JSONObject();
        L2LLMCompiler.putTextAndChoice(plain, "就是一句话");
        assertFalse(plain.has("app_choice"));
    }

    @Test public void polishDoesNotCutTail() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) longText.append("这是一句很长的回答。");
        String out = L2LLMCompiler.polishKeepChoice(longText + TAIL);
        JSONObject args = new JSONObject();
        L2LLMCompiler.putTextAndChoice(args, out);
        assertTrue(args.getString("text").length() < longText.length());
        assertFalse(args.getString("text").contains("app_choice"));
        assertEquals("com.jingyao.easybike", args.getJSONObject("app_choice").getJSONArray("apps").getJSONObject(0).getString("pkg"));
    }

    @Test public void agentLoopResultStripsTail() {
        AgentLoop.Result r = new AgentLoop.Result("手机上没装共享单车 App。" + TAIL, new ArrayList<AgentLoop.Step>(), true, null);
        assertEquals("手机上没装共享单车 App。", r.answer);
        assertNotNull(r.appChoice);
        AgentLoop.Result n = new AgentLoop.Result(null, new ArrayList<AgentLoop.Step>(), false, "x");
        assertNull(n.answer);
        assertNull(n.appChoice);
    }

    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/" + rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    @Test public void promptsAndWiring() throws Exception {
        String l2 = src("router/L2LLMCompiler.java");
        // ★ arch/tool-tiers（A 路）：Agent 提示词搬到 assets/agent_prompt.md，规则经 AgentPromptAssembler 的 {{APP_CHOICE_RULE}}（及兜底）追加
        assertEquals("编译 / 问答两个提示词追加规则", 2, count(l2, "AppChoiceSpec.PROMPT_RULE"));
        String asm = src("prompt/AgentPromptAssembler.java");
        assertEquals("Agent 提示词：占位符取值 + 资源缺失兜底", 2, count(asm, "AppChoiceSpec.PROMPT_RULE"));
        String fss = src("ui/FloatingSheetService.java");
        assertEquals(1, count(fss, "RoleHooks.onAgentAnswer("));
        String hooks = src("roles/RoleHooks.java");
        assertTrue(hooks.contains("showLlmChoice(svc, inv.args.optJSONObject(\"app_choice\")"));
        assertTrue("选择芯片已改成应用网格", hooks.contains("ChoiceGridView v = ensureGrid(svc)"));
        assertTrue(hooks.contains("openStoreSearch(svc, storeQuery)"));
        assertTrue(hooks.contains("hideGrid(svc);   // [r2/bike-chooser]"));
        String ur = src("router/UnifiedRouter.java");
        assertTrue(ur.contains("L2LLMCompiler.putTextAndChoice(args, answer)"));
        // ⑤ C1 回归：Agent 结论带网格 → 收工挂回卡片（新界面默认只进岛，格子画在卡片里看不见）
        assertEquals(1, count(fss, "RoleHooks.agentGridWanted(FloatingSheetService.this, command, fr.appChoice)"));
        assertTrue(fss.contains("fr == null ? \"任务处理失败\" : islandWhy, needsCard);"));   // ★ arch/integrate：原因按结构化结局换说法（OutcomeText）
        String ic = src("s1/IslandController.java");
        assertTrue(ic.contains("WindowFlowPolicy.islandOnlyAfterTask(newUi, needsCard)"));
        assertTrue(ic.contains("mOutcomeFinished && !needsCard"));
    }
}
