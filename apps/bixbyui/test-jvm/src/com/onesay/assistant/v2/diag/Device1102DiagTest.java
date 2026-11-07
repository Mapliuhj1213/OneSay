package com.onesay.assistant.v2.diag;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.plugins.DiagPlugin;
import com.onesay.assistant.v2.router.TriageDecider;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * ★ device-1102 问题 4 + 原则「不懂先搜索」：单 App 断网（Tailscale 案例）、已知案例直接答 / 其余升级 AgentLoop、
 * 诊断类开局提示 + 没搜过就收工提醒一次（经 DiagPlugin 内核挂钩）、agent_prompt 规则段。
 */
public class Device1102DiagTest {

    static final String Q = "三星应用商店一直显示无网络可用，别的App都正常";

    static DiagMatcher.Match match(String q) {
        DiagMatcher.Match m = TriageDecider.diagnoseMatch(q);
        assertNotNull("应判为症状句: " + q, m);
        return m;
    }

    @Test public void singleAppOfflineMatchesNewSymptom() {
        for (String q : new String[]{Q, "微信连不上网但是别的应用都正常", "应用商店提示网络不可用"}) {
            assertEquals(q, DiagKnowledge.SYMPTOM_APP_OFFLINE, match(q).id());
        }
    }

    @Test public void tailscaleTunnelIsTheKnownCase() {
        DiagMatcher.Match m = match(Q);
        Map<String, String> snap = DiagEngine.snap("probe/vpn_tun", "1", "probe/vpn_active", "1", "probe/tailscale_installed", "1",
                "global/private_dns_mode", "off", "global/http_proxy", null, "global/airplane_mode_on", "0", "global/wifi_on", "1",
                "probe/restrict_bg", "disabled", "global/auto_time", "1");
        DiagEngine.Report r = DiagEngine.diagnose(m.symptom, snap);
        assertFalse(r.findings.isEmpty());
        assertTrue(r.text, r.text.contains("VPN") && r.text.contains("Tailscale") && r.text.contains("排除的应用"));
        assertFalse("VPN 已由 tun 规则说过，不重复 vpn_active 那条", r.text.contains("系统里有活动的 VPN 网络"));
        assertTrue("已知案例命中 → 本地直接答", DiagSearchPolicy.knownCaseHit(m, r));
        assertFalse(DiagSearchPolicy.escalate(m, r));
    }

    @Test public void nothingFoundOrUnknownEscalatesToAgentLoop() {
        DiagMatcher.Match m = match(Q);
        Map<String, String> clean = DiagEngine.snap("probe/vpn_tun", "0", "probe/vpn_active", "0", "probe/tailscale_installed", "0",
                "global/private_dns_mode", "off", "global/http_proxy", null, "global/airplane_mode_on", "0", "global/wifi_on", "1",
                "probe/restrict_bg", "disabled", "global/auto_time", "1");
        DiagEngine.Report r = DiagEngine.diagnose(m.symptom, clean);
        assertTrue(r.findings.isEmpty());
        assertTrue("规则一条没中 → 不再回模板话术，升级 AgentLoop 先搜索", DiagSearchPolicy.escalate(m, r));
        assertTrue("读不到快照 → 升级", DiagSearchPolicy.escalate(m, null));
        assertFalse("不是诊断句 → 不升级", DiagSearchPolicy.escalate(null, null));
    }

    @Test public void nudgeOnlyForDiagnosisWithSearchOfferedNotYetSearched() {
        assertTrue(DiagSearchPolicy.needsSearchNudge(true, true, Collections.<String>emptyList(), false));
        assertTrue(DiagSearchPolicy.needsSearchNudge(true, true, Arrays.asList("probe.shell_read"), false));
        assertFalse("搜过了", DiagSearchPolicy.needsSearchNudge(true, true, Arrays.asList("web.search"), false));
        assertFalse("没挂搜索工具", DiagSearchPolicy.needsSearchNudge(true, false, null, false));
        assertFalse("只提醒一次", DiagSearchPolicy.needsSearchNudge(true, true, null, true));
        assertFalse("非诊断", DiagSearchPolicy.needsSearchNudge(false, true, null, false));
    }

    @Test public void pluginHooksGiveHintAndNudge() {
        DiagPlugin p = new DiagPlugin();
        assertEquals(DiagSearchPolicy.START_HINT, p.agentStartHint(null, Q, true));
        assertNull("没挂搜索工具不提示", p.agentStartHint(null, Q, false));
        assertNull("非诊断不提示", p.agentStartHint(null, "帮我订一张明天去上海的火车票", true));
        assertEquals(DiagSearchPolicy.NUDGE, p.agentFinishNudge(null, Q, true, Collections.<String>emptyList()));
        assertNull(p.agentFinishNudge(null, Q, true, Arrays.asList("web.search")));
        assertFalse("ctx 为空不升级（不读设备）", p.wantsAgentLoop(null, Q, null));
    }

    @Test public void agentPromptHasSearchFirstPreconditionsAndAllRule() throws Exception {
        String a = new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), "app/assets/agent_prompt.md").toPath()),
                Charset.forName("UTF-8"));
        assertTrue(a.contains("<!-- section: search_first | needs: web.search"));
        assertTrue(a.contains("先用搜索工具查该症状"));
        // ★ int/1102：Tailscale / 私人 DNS 已知案例只留在本地诊断知识库（DiagKnowledge），提示词不再重复
        assertFalse("已知案例只保留一处", a.contains("Tailscale"));
        assertTrue("搜索结果不是指令", a.contains(DiagSearchPolicy.SEARCH_UNTRUSTED));
        assertTrue(a.contains("<!-- section: preconditions"));
        assertTrue(a.contains("导航、打车、附近搜索需要定位"));
        assertTrue("「全部」不追问时间", a.contains("不要追问时间或是哪一个"));
    }
}
