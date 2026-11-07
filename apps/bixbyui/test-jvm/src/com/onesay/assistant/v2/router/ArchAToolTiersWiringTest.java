package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.agenttools.AgentToolset;
import com.onesay.assistant.v2.agenttools.GuiFold;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.prompt.AgentPromptAssembler;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static com.onesay.assistant.v2.router.AgentLoopTest.*;
import static org.junit.Assert.*;

/** ★ arch/tool-tiers（A 路）：AgentLoop 接线 —— 分档候选、按挂载拼提示词、步后展开 GUI 档、AgentLoop 改动面。 */
public class ArchAToolTiersWiringTest {

    @After public void tearDown() {
        AgentToolset.end();
    }

    @Test public void loopUsesTieredCandidatesAndAssembledPrompt() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(say("好"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm)
                .run("先看一下现在几点了，如果还没到晚上，就顺便把屏幕亮度调到最低", new FakeExec(), 0);
        JSONArray tools = llm.toolsSeen.get(0);
        assertTrue(hasTool(tools, "time__now"));
        assertTrue(hasTool(tools, "settings__brightness__set"));
        String sys = llm.messagesSeen.get(0).getJSONObject(0).getString("content");
        assertTrue(sys.contains("接口 > 查信息 > 深链 > 点界面"));
        assertTrue("工具数按实际挂载", sys.contains("【本次可用工具数】" + tools.length() + " 个"));
        assertTrue(sys.contains("【本轮各档工具】"));
        assertNotNull("开局开了工具层会话", AgentToolset.currentFor("先看一下现在几点了，如果还没到晚上，就顺便把屏幕亮度调到最低"));
    }

    @Test public void failedDeeplinkUnfoldsGuiTierInsideLoop() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(call("store__search__samsung", "{\"keyword\":\"微信\"}"));
        llm.replies.add(say("商店里没找到。"));
        String q = "下载微信";
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run(q, new FakeExec(), 0);
        AgentToolset.Session s = AgentToolset.currentFor(q);
        assertNotNull(s);
        assertEquals("深链失败后 GUI 档在循环里展开", GuiFold.Reason.UPPER_TIERS_FAILED, s.fold());
    }

    @Test public void nudgeTextLivesInAssetAndMatchesJavaFallback() {
        assertEquals(AgentLoop.PROBE_CHAIN_NUDGE, AgentPromptAssembler.text("nudge.probe_chain", "FALLBACK"));
    }

    /** 冲突约定：A 路对 AgentLoop.java 只许有 4 处、全部标 [arch-a]。 */
    @Test public void agentLoopTouchPointsAreMarked() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String src = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/router/AgentLoop.java").toPath()), Charset.forName("UTF-8"));
        assertTrue(src.contains("CapabilityRetriever.getTopKTiered(query, AGENT_TOOL_TOP_K)"));
        assertTrue(src.contains("SoulHooks.withPrefix(com.onesay.assistant.v2.soul.SoulHooks.PATH_AGENT, L2LLMCompiler.buildAgentLoopPrompt(mContext, tools))"));   // device-1102：带路径
        assertTrue(src.contains("AgentPromptAssembler.text(\"nudge.probe_chain\", PROBE_CHAIN_NUDGE)"));
        assertTrue(src.contains("AgentToolset.afterStep(name, cap, ok, false)"));
        int marks = 0;
        for (int i = src.indexOf("[arch-a]"); i >= 0; i = src.indexOf("[arch-a]", i + 1)) marks++;
        assertEquals("4 处改动 + 提示词那行", 4, marks);
        // ★ arch/integrate（合并 B 路后的白名单）：A 路仍只有上面 4 处 [arch-a]；合并只在 A 的「GUI 展开后重新导出」块里
        //   补 1 行常驻工具（标 [integrate]），B 路追加在 system 末尾的规则段已搬进 assets/agent_prompt.md（loop_tools 段）
        int im = 0;
        for (int i = src.indexOf("[integrate]"); i >= 0; i = src.indexOf("[integrate]", i + 1)) im++;
        assertEquals("合并改动只许 1 处 [integrate]", 1, im);
        int unfold = src.indexOf("AgentToolset.afterStep(name, cap, ok, false)");
        int refill = src.indexOf("LoopTools.append(tools, offer);   // [integrate]");
        int notice = src.indexOf("AgentToolset.withGuiUnfolded(observation, tools)");
        assertTrue("常驻工具补回在 GUI 展开块内、观察提示之前", unfold > 0 && refill > unfold && notice > refill);
        int appends = 0;
        for (int i = src.indexOf("LoopTools.append(tools, offer)"); i >= 0; i = src.indexOf("LoopTools.append(tools, offer)", i + 1)) appends++;
        assertEquals("开局 / 探针新接口 / GUI 展开 三处导出都补常驻工具", 3, appends);
        assertFalse("规则段不再写在 Java 里", src.contains("LoopTools.promptSection("));
    }
}
