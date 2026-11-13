package com.onesay.assistant.v2.agenttools;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.router.CapabilityRetriever;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ arch/tool-tiers（A 路）：GUI 档（phone.operate）折叠 / 展开条件。 */
public class GuiFoldTest {

    @After public void tearDown() {
        AgentToolset.end();
    }

    @Test public void explicitUi() {
        assertTrue(GuiFold.explicitUi("帮我点一下右上角那个按钮"));
        assertTrue(GuiFold.explicitUi("往下滑一下"));
        assertTrue(GuiFold.explicitUi("替我操作一下这个页面"));
        assertTrue(GuiFold.explicitUi("长按那条消息"));
        assertFalse("「几点」不是点界面", GuiFold.explicitUi("现在几点了"));
        assertFalse(GuiFold.explicitUi("明天一点钟叫我"));
        assertFalse(GuiFold.explicitUi("把亮度调到最低"));
        assertFalse(GuiFold.explicitUi(null));
    }

    @Test public void thirdPartyAppScope() {
        assertEquals("小红书", GuiFold.thirdPartyAppScope("在小红书发一条笔记"));
        assertEquals("小红书", GuiFold.thirdPartyAppScope("在小红书上发一条笔记"));
        assertEquals("微信", GuiFold.thirdPartyAppScope("用微信给妈妈发消息说我晚点到"));
        assertEquals("淘宝", GuiFold.thirdPartyAppScope("去淘宝买个耳机"));
        assertEquals("美团", GuiFold.thirdPartyAppScope("打开美团里的外卖订单看一下"));
        assertNull("设置有接口", GuiFold.thirdPartyAppScope("在设置里打开wifi"));
        assertNull("地图有深链", GuiFold.thirdPartyAppScope("在地图里搜一下附近的酒店"));
        assertNull("浏览器 / 查资料走查信息档", GuiFold.thirdPartyAppScope("打开浏览器搜一下天气"));
        assertNull("「现在」里的「在」不算", GuiFold.thirdPartyAppScope("现在给我发一下今天的日程"));
        assertNull("地点不是 App", GuiFold.thirdPartyAppScope("在家里把空调打开"));
        assertNull(GuiFold.thirdPartyAppScope("把亮度调到最低"));
        assertNull(GuiFold.thirdPartyAppScope(null));
    }

    @Test public void triageGuiNeedsDoLabel() {
        assertTrue(GuiFold.triageGui("在小红书发一条笔记", "DO_ACT"));
        assertTrue(GuiFold.triageGui("在小红书发一条笔记", "MIXED"));
        assertTrue("没给分诊按办事处理", GuiFold.triageGui("在小红书发一条笔记", null));
        assertFalse("问答类不算", GuiFold.triageGui("在小红书发一条笔记", "ASK"));
        assertFalse(GuiFold.triageGui("把亮度调到最低", "DO_ACT"));
    }

    @Test public void initialDecision() {
        assertEquals(GuiFold.Reason.FOLDED, GuiFold.initial("把亮度调到最低", "DO_ACT", true));
        assertEquals(GuiFold.Reason.EXPLICIT_UI, GuiFold.initial("帮我点一下登录", "DO_ACT", true));
        assertEquals(GuiFold.Reason.TRIAGE_GUI, GuiFold.initial("在小红书发一条笔记", "DO_ACT", true));
        assertEquals(GuiFold.Reason.NO_UPPER_TIER, GuiFold.initial("帮我把这个弄好", "DO_ACT", false));
        assertFalse(GuiFold.Reason.FOLDED.expanded);
        assertTrue(GuiFold.Reason.NO_UPPER_TIER.expanded);
    }

    static Capability cap(String sign, String executor) {
        return new Capability.Builder(sign, sign).schema(new ParamsSchema()).executor(executor).build();
    }

    @Test public void sessionStartsFoldedWhenStrongUpperTierExists() {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        AgentToolset.Session s = AgentToolset.current();
        assertNotNull(s);
        assertEquals("把屏幕亮度调到最低", s.query);
        assertFalse("有强相关的接口 → 折叠", s.guiExpanded());
        assertEquals(GuiFold.Reason.FOLDED, s.fold());
    }

    @Test public void deeplinkFailureUnfoldsOnce() {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        assertFalse(AgentToolset.current().guiExpanded());
        Capability link = CapabilityRegistry.getInstance().describe("store.search.market");
        assertTrue("深链失败一次就展开", AgentToolset.afterStep("store__search__samsung", link, false, false));
        assertEquals(GuiFold.Reason.UPPER_TIERS_FAILED, AgentToolset.current().fold());
        assertFalse("已经展开，不再要求重新导出", AgentToolset.afterStep("store__search__samsung", link, false, false));
    }

    @Test public void apiFailuresUnfoldAfterTwo() {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        Capability api = CapabilityRegistry.getInstance().describe("settings.brightness.set");
        assertFalse(AgentToolset.afterStep("settings__brightness__set", api, false, false));
        assertFalse("成功不计", AgentToolset.afterStep("settings__brightness__set", api, true, false));
        assertFalse("被用户拒绝的确认卡不计", AgentToolset.afterStep("settings__brightness__set", api, false, true));
        assertFalse("探针失败不计", AgentToolset.afterStep("probe__apps", cap("probe.apps", "probe"), false, false));
        assertFalse("未注册工具不计", AgentToolset.afterStep("nope__tool", null, false, false));
        assertTrue("第二次接口失败 → 展开", AgentToolset.afterStep("settings__brightness__set", api, false, false));
    }

    @Test public void entryCallUnfolds() {
        CapabilityRetriever.getTopKTiered("把屏幕亮度调到最低", 40);
        Capability entry = new Capability.Builder(ToolTiers.SIGN_GUI_ENTRY, "入口").schema(new ParamsSchema()).executor("custom").build();
        assertTrue(AgentToolset.afterStep("gui__unfold", entry, true, false));
        assertEquals(GuiFold.Reason.ENTRY_CALLED, AgentToolset.current().fold());
    }

    @Test public void triageHintIsConsumedOnlyForSameQuery() {
        AgentToolset.prepare("在小红书发一条笔记", "ASK");
        CapabilityRetriever.getTopKTiered("在小红书发一条笔记", 40);
        assertEquals("ASK", AgentToolset.current().triageLabel);
        assertFalse("问答类分诊 → 不按 GUI 展开", AgentToolset.current().fold() == GuiFold.Reason.TRIAGE_GUI);

        AgentToolset.prepare("别的话", "DO_ACT");
        CapabilityRetriever.getTopKTiered("在小红书发一条笔记", 40);
        assertNull("不同句子的分诊不串用", AgentToolset.current().triageLabel);
        assertTrue(AgentToolset.current().guiExpanded());

        CapabilityRetriever.getTopKTiered("在小红书发一条笔记", 40);
        assertNull("分诊结论只用一次", AgentToolset.current().triageLabel);
    }

    @Test public void currentForChecksQuery() {
        CapabilityRetriever.getTopKTiered("下载微信", 40);
        assertNotNull(AgentToolset.currentFor("下载微信"));
        assertNull(AgentToolset.currentFor("别的任务"));
        AgentToolset.end();
        assertNull(AgentToolset.current());
    }

    @Test public void metaTool() {
        assertTrue(AgentToolset.isMetaTool("gui.unfold"));
        assertTrue(AgentToolset.isMetaTool("gui__unfold"));
        assertFalse(AgentToolset.isMetaTool("phone.operate"));
    }

    @Test public void unfoldedNoteOnlyWhenOperateMounted() throws Exception {
        org.json.JSONArray none = new org.json.JSONArray();
        assertEquals("{\"ok\":true}", AgentToolset.withGuiUnfolded("{\"ok\":true}", none));
        org.json.JSONArray with = new org.json.JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"phone__operate\"}}]");
        String o = AgentToolset.withGuiUnfolded("{\"ok\":true}", with);
        assertTrue(o.contains("gui_tool_added"));
        assertTrue(o.contains("phone__operate"));
    }
}
