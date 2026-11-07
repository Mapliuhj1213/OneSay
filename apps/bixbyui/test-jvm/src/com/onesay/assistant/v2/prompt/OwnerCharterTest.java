package com.onesay.assistant.v2.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.router.PromptLibrary;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 主人守则逐字常驻在 AgentLoop 与主对话（ASK）系统提示词里，位置在人设之后、工具 / 回答规范之前；
 * 现有规则（停在付款前、发消息前确认、密钥不出现）不受影响。
 */
public class OwnerCharterTest {

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void textIsVerbatim() throws Exception {
        String orig = read("test-jvm/fixtures/owner_charter.txt").replaceAll("\\n+$", "");
        assertEquals(orig, OwnerCharter.TEXT);
        for (int i = 1; i <= 10; i++) assertTrue("保留编号 " + i, OwnerCharter.TEXT.contains("\n" + i + ". ") || OwnerCharter.TEXT.startsWith(i + ". "));
        assertTrue(OwnerCharter.TEXT.endsWith("认真、诚实、简洁。"));
        assertEquals(OwnerCharter.HEADER + "\n" + OwnerCharter.TEXT, OwnerCharter.BLOCK);
    }

    @Test public void agentPromptAfterIdentityBeforeTools() throws Exception {
        for (java.util.List<String> mount : Arrays.asList(AgentPromptAssembler.DEFAULT_MOUNT,
                Arrays.asList("web.search", "web.read", "research.spawn", "task.todo", "ask_user", "end_not_done", "app.launch", "probe.apps"))) {
            AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(mount);
            AgentPromptAssembler.Result r = AgentPromptAssembler.assemble(AgentPromptAssemblerTest.real(), m, AgentPromptAssembler.vars(m, mount.size()), true);
            String t = r.text;
            int id = t.indexOf("你是智能手机语音助手的**执行体**"), oc = t.indexOf(OwnerCharter.BLOCK), tiers = t.indexOf("【做法的先后");
            assertTrue(t, id == 0 && oc > id && tiers > oc);
            assertTrue("超预算 " + r.overBudget, r.overBudget.isEmpty());
            assertTrue("不丢段 " + r.dropped + " 总长 " + t.length(), r.dropped.isEmpty() && t.length() <= AgentPromptAssembler.TOTAL_BUDGET);
            assertEquals(OwnerCharter.BLOCK.length(), (int) r.sizes.get("owner_charter"));
        }
    }

    @Test public void askPromptAfterPersonaBeforeRules() {
        String base = PromptLibrary.Defaults.ASK_OUTPUT;
        String p = OwnerCharter.insertAfterPersona(base);
        assertTrue(p.startsWith("你是智能手机语音助手的回答者。用户的问题只需要你**直接给出答案**。\n\n" + OwnerCharter.BLOCK + "\n"));
        assertTrue(p.indexOf(OwnerCharter.BLOCK) < p.indexOf("【回答形态要求】"));
        assertEquals("只加一段", base.length() + OwnerCharter.BLOCK.length() + 2, p.length());
        assertEquals("不重复插", p, OwnerCharter.insertAfterPersona(p));
        assertTrue(p.contains("3. 不使用 Markdown 标题、代码块、项目符号。"));
    }

    @Test public void wiredAndExistingRulesKept() throws Exception {
        String l2 = read("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        assertTrue(l2.contains("OwnerCharter.insertAfterPersona(PromptLibrary.getInstance(mContext).render(PromptLibrary.T_ASK_OUTPUT, vars))"));
        String md = read("app/assets/agent_prompt.md");
        assertTrue(md.indexOf("section: identity") < md.indexOf("section: owner_charter") && md.indexOf("section: owner_charter") < md.indexOf("section: tiers"));
        assertTrue("付款前停 / 确认规则仍在 iron_rules", md.contains("section: iron_rules"));
    }
}
