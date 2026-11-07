package com.onesay.assistant.v2.kernel.plugins;

import com.onesay.assistant.v2.agenttools.AgentToolset;
import com.onesay.assistant.v2.agenttools.ToolTier;
import com.onesay.assistant.v2.agenttools.ToolTiers;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.CapabilityRetriever;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ arch/tool-tiers（A 路）：phone.operate 默认折叠成入口 gui.unfold；描述不再揽「设置 / 地图 / 浏览器」的活。 */
public class PhonePluginFoldTest {

    @After public void tearDown() {
        AgentToolset.end();
    }

    @Test public void disabledMountsNothing() {
        CapabilityRetriever.getTopKTiered("帮我点一下登录", 40);
        assertNull(PhonePlugin.toolFor(false, "帮我点一下登录"));
    }

    @Test public void foldedByDefaultWhenInterfaceExists() {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        Capability c = PhonePlugin.toolFor(true, "把屏幕亮度调到最低");
        assertEquals("有接口时只给入口", ToolTiers.SIGN_GUI_ENTRY, c.sign);
    }

    @Test public void expandedWhenUserAsksToTap() {
        CapabilityRetriever.getTopKTiered("帮我点一下右上角的登录按钮", 40);
        assertEquals(PhonePlugin.SIGN, PhonePlugin.toolFor(true, "帮我点一下右上角的登录按钮").sign);
    }

    @Test public void expandedWhenTriageSaysGui() {
        AgentToolset.prepare("在小红书发一条笔记", "DO_ACT");
        CapabilityRetriever.getTopKTiered("在小红书发一条笔记", 40);
        assertEquals(PhonePlugin.SIGN, PhonePlugin.toolFor(true, "在小红书发一条笔记").sign);
    }

    @Test public void entryCallSwapsInOperate() throws Exception {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        Capability entry = new PhonePlugin().describeForAgent(null, "gui__unfold");
        assertNotNull(entry);
        ExecutionResult r = new PhonePlugin().executeForAgent(null, entry, new JSONObject("{\"why\":\"没有接口\"}"));
        assertTrue(r.ok);
        assertTrue(AgentToolset.afterStep("gui__unfold", entry, r.ok, false));
        assertEquals("展开后重新导出时挂 phone.operate", PhonePlugin.SIGN, PhonePlugin.toolFor(true, "把屏幕亮度调到最低").sign);
    }

    @Test public void noSessionFallsBackToQueryOnly() {
        AgentToolset.end();
        assertEquals(ToolTiers.SIGN_GUI_ENTRY, PhonePlugin.toolFor(true, "把亮度调到最低").sign);
        assertEquals(PhonePlugin.SIGN, PhonePlugin.toolFor(true, "往下滑一下").sign);
    }

    @Test public void descriptionNoLongerClaimsInterfaceJobs() {
        String d = PhonePlugin.cap().summary;
        assertFalse(d.contains("在设置里查东西"));
        assertFalse(d.contains("在地图里搜地点"));
        assertFalse(d.contains("在浏览器里查资料"));
        assertTrue(d.contains("最后一档"));
        assertEquals(ToolTier.GUI, ToolTiers.of(PhonePlugin.cap()));
        assertEquals(ToolTier.GUI, ToolTiers.of(PhonePlugin.entryCap()));
        assertTrue("入口说明比全文短", PhonePlugin.entryCap().summary.length() < d.length());
    }

    @Test public void describeForAgentKnowsBothNames() {
        PhonePlugin p = new PhonePlugin();
        assertEquals(PhonePlugin.SIGN, p.describeForAgent(null, "phone__operate").sign);
        assertEquals(ToolTiers.SIGN_GUI_ENTRY, p.describeForAgent(null, "gui.unfold").sign);
        assertNull(p.describeForAgent(null, "other__tool"));
        assertNull("插件工具不进注册表", CapabilityRegistry.getInstance().describe("gui.unfold"));
    }
}
