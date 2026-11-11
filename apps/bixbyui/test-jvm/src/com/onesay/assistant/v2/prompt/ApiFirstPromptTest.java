package com.onesay.assistant.v2.prompt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ★ feat/api-first：提示词 —— 挂了 route.open 才出现它的规则行；可点追问短语规则总在；预算不超。 */
public class ApiFirstPromptTest {

    private static AgentPromptAssembler.Result assemble(List<String> mount) {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(mount);
        return AgentPromptAssembler.assemble(AgentPromptAssemblerTest.real(), m, AgentPromptAssembler.vars(m, mount.size()), true);
    }

    @Test public void routeOpenLineOnlyWhenMounted() {
        List<String> with = new ArrayList<String>(AgentPromptAssemblerTest.EXPANDED);
        with.add("route.open");
        AgentPromptAssembler.Result r = assemble(with);
        assertTrue(r.text.contains("route.open 会按这个顺序自己选路"));
        assertTrue(r.text.contains("需要你在铁路12306里确认车次并付款"));
        assertTrue("超预算段 " + r.overBudget, r.overBudget.isEmpty());
        assertTrue(r.dropped.isEmpty());
        assertTrue(r.text.length() <= AgentPromptAssembler.TOTAL_BUDGET);
        assertFalse(assemble(AgentPromptAssemblerTest.EXPANDED).text.contains("route.open"));
    }

    @Test public void followupRuleAlwaysPresent() {
        for (List<String> mount : Arrays.asList(AgentPromptAssemblerTest.FOLDED, AgentPromptAssemblerTest.EXPANDED, AgentPromptAssemblerTest.API_ONLY)) {
            String p = assemble(mount).text;
            assertTrue(p.contains("给 2–3 个可点短语，写成 [[用户会直接说的话]]"));
            assertTrue(p.contains("没有明确选项就不给"));
        }
    }
}
