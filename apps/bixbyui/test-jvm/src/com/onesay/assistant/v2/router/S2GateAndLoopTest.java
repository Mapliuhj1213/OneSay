package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.s2.S2Capabilities;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * [s2] 门禁与 AgentLoop 口径：
 * <ul>
 *   <li>写入/删一条/习惯：未污染直接放行（不弹卡）；「忘掉所有记录」= 红卡（无「始终允许」）；</li>
 *   <li>memory.search：只读、未污染放行；读完按 tainted（PRIVATE_DATA_READ，与读屏/读信同口径）；已污染时要确认；</li>
 *   <li>memory.search 不能被当成技能写进 L1；S2 能力都不在注册表里；</li>
 *   <li>分诊：新句子标签正确，回忆问句 = ASK 永不进 AgentLoop。</li>
 * </ul>
 */
public class S2GateAndLoopTest {

    @Test public void gateDecisions() throws Exception {
        String[] allow = {S2Capabilities.SIGN_NOTE_ADD, S2Capabilities.SIGN_NOTE_DELETE, S2Capabilities.SIGN_HABIT_SET,
                S2Capabilities.SIGN_HABIT_RESET, S2Capabilities.SIGN_SEARCH, S2Capabilities.SIGN_NAV_APP,
                S2Capabilities.SIGN_LEDGER_ADD, S2Capabilities.SIGN_LEDGER_DELETE};
        for (String s : allow) {
            Capability c = S2Capabilities.get(s);
            assertNotNull(s, c);
            assertTrue(s, ExecutionGate.evaluate(c, new JSONObject(), false).allowed());
            assertNull(s + " 不进注册表", CapabilityRegistry.getInstance().describe(s));
        }
        Capability clear = S2Capabilities.get(S2Capabilities.SIGN_CLEAR);
        ExecutionGate.Decision d = ExecutionGate.evaluate(clear, new JSONObject().put("scope", "all"), false);
        assertTrue(d.needsConfirm());
        assertEquals("红卡：不可逆、没有「始终允许」", ConsentPolicy.TIER_RED, d.tier);
        ExecutionGate.reject(d.token);
        // 本机只读（检索记忆）在污点下也放行、绝不弹卡（用户：只读动作不得弹确认卡）；读完照旧置污点
        Capability search = S2Capabilities.get(S2Capabilities.SIGN_SEARCH);
        ExecutionGate.Decision t = ExecutionGate.evaluate(search, new JSONObject(), true);
        assertTrue(t.allowed());
        assertTrue(AgentLoop.readsUntrustedContent(search));
        assertFalse(AgentLoop.readsUntrustedContent(S2Capabilities.get(S2Capabilities.SIGN_NOTE_ADD)));
        assertTrue(S2Capabilities.isLocal(search));
        assertFalse("导航走 shell", S2Capabilities.isLocal(S2Capabilities.get(S2Capabilities.SIGN_NAV_APP)));
        assertEquals("memory__search", CapabilityRegistry.signToToolName(S2Capabilities.SIGN_SEARCH));
    }

    @Test public void navTemplateEscapesAddressAndPackage() throws Exception {
        JSONObject a = new JSONObject().put("address", "公司'; rm -rf / & $(id)").put("pkg", "com.baidu.BaiduMap");
        String cmd = com.onesay.assistant.v2.executor.ShellCommand.fill(S2Capabilities.get(S2Capabilities.SIGN_NAV_APP).cmdTemplate,
                com.onesay.assistant.v2.executor.ShellCommand.slotValues(a, null, null));
        assertFalse(cmd, cmd.contains("$(id)"));
        assertFalse(cmd, cmd.contains("rm -rf / &"));
        assertTrue(cmd, cmd.contains("geo:0,0?q=%E5%85%AC%E5%8F%B8"));
        assertTrue(cmd, cmd.contains("-p com.baidu.BaiduMap 2>&1"));
        // 包名只来自白名单；即便被篡改也逃不出引号
        JSONObject bad = new JSONObject().put("address", "x").put("pkg", "x; reboot");
        String c2 = com.onesay.assistant.v2.executor.ShellCommand.fill(S2Capabilities.get(S2Capabilities.SIGN_NAV_APP).cmdTemplate,
                com.onesay.assistant.v2.executor.ShellCommand.slotValues(bad, null, null));
        assertFalse(c2, c2.contains("-p x; reboot"));
    }

    @Test public void memorySearchNeverBecomesSkill() {
        List<AgentLoop.Step> steps = new ArrayList<AgentLoop.Step>();
        steps.add(new AgentLoop.Step("memory.search", new JSONObject(), "{}", true, false, true));
        assertNull(AgentLoop.deterministicSingleStep(new AgentLoop.Result("B2-17", steps, true, null)));
        List<AgentLoop.Step> other = new ArrayList<AgentLoop.Step>();
        other.add(new AgentLoop.Step("battery.level", new JSONObject(), "{}", true, false, true));
        assertNotNull("其它单步照旧", AgentLoop.deterministicSingleStep(new AgentLoop.Result("80%", other, true, null)));
    }

    /** 新句子的分诊标签（同时并入 TriageDeciderTest.EXTRA 回归集）。 */
    static final String[][] S2_SENTENCES = {
            {"记一下车停在B2-17", "DO_ACT"},
            {"记住护照放在书房抽屉", "DO_ACT"},
            {"帮我记一下明天天气不好要带伞", "DO_ACT"},
            {"我车停哪了", "ASK"},
            {"我把护照放哪了", "ASK"},
            {"上次记的停车位置", "ASK"},
            {"我记了什么", "ASK"},
            {"删掉停车那条", "DO_ACT"},
            {"忘掉所有记录", "DO_ACT"},
            {"清空记忆", "DO_ACT"},
            {"以后打车都用滴滴", "DO_ACT"},
            {"别默认用滴滴了", "DO_ACT"},
            {"打车", "DO_ACT"},
            {"用高德打车", "DO_ACT"},
    };

    @Test public void triageLabelsAndRecallNeverLoops() {
        for (String[] c : S2_SENTENCES) {
            TriageDecider.Decision d = TriageDecider.decide(c[0], TriageDecider.Signals.offline());
            assertEquals(c[0] + " (" + d.rule + ")", c[1], d.label.name());
            if ("ASK".equals(c[1])) assertFalse(c[0], d.useAgentLoop);
        }
        // 写入是单步动作（S2 在 shouldUseAgentLoop 里接管，不进循环）
        assertFalse(TriageDecider.decide("记一下车停在B2-17", TriageDecider.Signals.offline()).multiStep);
        // 记账默认关：分诊不新增记账规则（「午饭花了35」维持原判）
        TriageDecider.Decision ledger = TriageDecider.decide("午饭花了35", TriageDecider.Signals.offline());
        assertFalse(ledger.rule.startsWith("6m") || ledger.rule.startsWith("3m"));
    }
}
