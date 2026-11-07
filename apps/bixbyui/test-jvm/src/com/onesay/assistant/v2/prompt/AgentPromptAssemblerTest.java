package com.onesay.assistant.v2.prompt;

import com.onesay.assistant.v2.agenttools.ToolTiers;
import com.onesay.assistant.v2.roles.AppChoiceSpec;
import com.onesay.assistant.v2.router.AgentLoop;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** ★ arch/tool-tiers（A 路）：提示词搬到 assets/agent_prompt.md + 按挂载工具分段拼装 + 每段预算。 */
public class AgentPromptAssemblerTest {

    @After public void tearDown() {
        PromptSource.setOverrideForTest(null);
    }

    static final List<String> FOLDED = AgentPromptAssembler.DEFAULT_MOUNT;
    static final List<String> EXPANDED = Arrays.asList("probe.apps", "probe.app_intents", "probe.shell_read", "app.launch",
            "vision.screen.understand", "settings.brightness.set", "store.search.samsung", "web.search", "web.read",
            "memory.search", ToolTiers.SIGN_GUI_OPERATE);
    static final List<String> API_ONLY = Arrays.asList("settings.brightness.set", "time.now");

    static List<AgentPromptAssembler.Section> real() {
        String text = PromptSource.agentPrompt(null);
        assertNotNull("assets/agent_prompt.md 必须能读到（-Dbixby.root）", text);
        return AgentPromptAssembler.parse(text);
    }

    static AgentPromptAssembler.Result assemble(List<String> mount) {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(mount);
        return AgentPromptAssembler.assemble(real(), m, AgentPromptAssembler.vars(m, mount.size()), true);
    }

    @Test public void assetHasAllSections() {
        List<String> ids = new ArrayList<String>();
        for (AgentPromptAssembler.Section s : real()) {
            ids.add(s.id);
            assertTrue("每段都要写预算: " + s.id, s.budget > 0);
        }
        assertEquals(Arrays.asList("identity", "owner_charter" /* ★ 11-03：主人守则 */, "tiers", "goal", "workflow", "loop_tools" /* ★ arch/integrate：B 路常驻工具段 */,
                "search_first", "preconditions" /* ★ device-1102：不懂先搜索 / 前置条件先满足 */, "ui_discipline", "probe", "info_question",
                "iron_rules", "time", "inventory", "app_choice", "nudge.probe_chain"), ids);
    }

    @Test public void everySectionWithinBudgetForEveryMount() {
        for (List<String> mount : Arrays.asList(FOLDED, EXPANDED, API_ONLY, Collections.<String>emptyList())) {
            AgentPromptAssembler.Result r = assemble(mount);   // strict：漏填占位符会抛
            assertTrue("超预算段 " + r.overBudget + " @ " + mount, r.overBudget.isEmpty());
            assertTrue("不应丢段 " + r.dropped, r.dropped.isEmpty());
            assertTrue("总长 " + r.text.length(), r.text.length() <= AgentPromptAssembler.TOTAL_BUDGET);
            assertFalse("占位符都要替换", r.text.contains("{{"));
            assertFalse("needs 标记不能漏进提示词", r.text.contains("<!--"));
            assertTrue("app_choice 规则在最后", r.text.endsWith(AppChoiceSpec.PROMPT_RULE));
        }
    }

    @Test public void tierRuleAlwaysPresent() {
        for (List<String> mount : Arrays.asList(FOLDED, EXPANDED, API_ONLY)) {
            String p = assemble(mount).text;
            assertTrue(p.contains("接口 > 查信息 > 深链 > 点界面"));
            assertTrue(p.contains("不要为这些去点界面"));
        }
    }

    @Test public void guiLinesFollowMountedTools() {
        String folded = assemble(FOLDED).text;
        assertTrue(folded.contains("先调 gui.unfold 说明原因"));
        assertFalse(folded.contains("点界面用 phone.operate"));
        assertTrue(folded.contains("已收起（需要时先调 gui.unfold）"));

        String expanded = assemble(EXPANDED).text;
        assertTrue(expanded.contains("点界面用 phone.operate"));
        assertFalse(expanded.contains("先调 gui.unfold 说明原因"));
        assertTrue(expanded.contains("phone.operate（只在前三档都不适用时用）"));
    }

    @Test public void infoLineOnlyWhenSearchToolsMounted() {
        assertFalse(assemble(FOLDED).text.contains("搜索 / 读网页工具就在你的工具列表里"));
        assertTrue(assemble(EXPANDED).text.contains("搜索 / 读网页工具就在你的工具列表里"));
        assertTrue(assemble(Arrays.asList("conn.tavily.search")).text.contains("搜索 / 读网页工具就在你的工具列表里"));
    }

    @Test public void sectionsFollowMountedTools() {
        AgentPromptAssembler.Result apiOnly = assemble(API_ONLY);
        assertFalse("没挂探针就不讲探针", apiOnly.sizes.containsKey("probe"));
        assertFalse("没有深链 / 界面工具就不讲界面纪律", apiOnly.sizes.containsKey("ui_discipline"));
        AgentPromptAssembler.Result folded = assemble(FOLDED);
        assertTrue(folded.sizes.containsKey("probe"));
        assertTrue(folded.sizes.containsKey("ui_discipline"));
        assertTrue(folded.text.contains("最多 " + AgentLoop.PROBE_BUDGET_MAX + " 次"));
    }

    @Test public void inventoryListsTiers() {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(EXPANDED);
        String inv = AgentPromptAssembler.tierInventory(m);
        assertTrue(inv, inv.startsWith("接口 "));
        assertTrue(inv, inv.contains("查信息：vision.screen.understand、web.search、web.read、memory.search"));
        assertTrue(inv, inv.contains("深链：app.launch、store.search.samsung"));
        assertTrue(inv, inv.contains("点界面：phone.operate"));
        assertTrue(AgentPromptAssembler.tierInventory(new AgentPromptAssembler.Mounted(API_ONLY)).contains("点界面：本轮不可用"));
    }

    @Test public void missingPlaceholderFailsInStrictMode() {
        List<AgentPromptAssembler.Section> secs = AgentPromptAssembler.parse("<!-- section: a | budget: 100 -->\n你好 {{NOPE}}\n");
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(Collections.<String>emptyList());
        try {
            AgentPromptAssembler.assemble(secs, m, new HashMap<String, String>(), true);
            fail("漏填占位符应当报错");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("NOPE"));
        }
        assertEquals("你好", AgentPromptAssembler.assemble(secs, m, new HashMap<String, String>(), false).text.trim());
    }

    @Test public void budgetCutsAtLineAndOptionalDropsFirst() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append("这是一行十个字的文字\n");
        String text = "<!-- section: keep | budget: 8 -->\n第一行内容\n第二行内容很长很长很长很长很长很长\n"
                + "<!-- section: huge | budget: 9000 -->\n" + big
                + "<!-- section: opt | budget: 9000 | optional -->\n" + big
                + "<!-- section: needy | needs: web.search | budget: 50 -->\n只有挂了搜索才出现\n"
                + "<!-- section: tierneedy | needs: tier:GUI | budget: 50 -->\n有点界面工具才出现\n";
        List<AgentPromptAssembler.Section> secs = AgentPromptAssembler.parse(text);
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(Collections.<String>emptyList());
        AgentPromptAssembler.Result r = AgentPromptAssembler.assemble(secs, m, new HashMap<String, String>(), true);
        assertEquals(r.overBudget.toString(), 1, r.overBudget.size());
        assertTrue(r.overBudget.get(0).startsWith("keep:"));
        assertTrue(r.text.startsWith("第一行内容\n\n"));
        assertEquals("总长超了先丢 optional", Arrays.asList("opt"), r.dropped);
        assertFalse(r.text.contains("只有挂了搜索才出现"));
        assertFalse(r.text.contains("有点界面工具才出现"));
        AgentPromptAssembler.Result r2 = AgentPromptAssembler.assemble(secs,
                new AgentPromptAssembler.Mounted(Arrays.asList("web.search", "phone.operate")), new HashMap<String, String>(), true);
        assertTrue(r2.text.contains("只有挂了搜索才出现"));
        assertTrue(r2.text.contains("有点界面工具才出现"));
    }

    @Test public void lineNeedsNot() {
        String text = "<!-- section: a | budget: 100 -->\n总在\n<!-- needs-not: phone.operate -->没有 GUI 时\n<!-- needs: phone.operate -->有 GUI 时\n";
        List<AgentPromptAssembler.Section> secs = AgentPromptAssembler.parse(text);
        String without = AgentPromptAssembler.assemble(secs, new AgentPromptAssembler.Mounted(Collections.<String>emptyList()),
                new HashMap<String, String>(), true).text;
        String with = AgentPromptAssembler.assemble(secs, new AgentPromptAssembler.Mounted(Arrays.asList("phone.operate")),
                new HashMap<String, String>(), true).text;
        assertEquals("总在\n没有 GUI 时", without);
        assertEquals("总在\n有 GUI 时", with);
    }

    @Test public void buildFromToolsArrayUsesWireNames() throws Exception {
        JSONArray tools = new JSONArray();
        for (String wire : new String[]{"probe__apps", "app__launch", "web__search", "phone__operate"}) {
            JSONObject fn = new JSONObject().put("name", wire).put("description", "x");
            tools.put(new JSONObject().put("type", "function").put("function", fn));
        }
        String p = L2LLMCompiler.buildAgentLoopPrompt(null, tools);
        assertTrue(p.contains("点界面用 phone.operate"));
        assertTrue(p.contains("搜索 / 读网页工具就在你的工具列表里"));
        assertTrue(p.contains("【本次可用工具数】4 个"));
        assertTrue(p.endsWith(AppChoiceSpec.PROMPT_RULE));
    }

    @Test public void legacySignatureKeepsOldPhrases() {
        String p = L2LLMCompiler.buildAgentLoopPrompt(10);
        assertTrue(p.startsWith("你是智能手机语音助手的**执行体**"));
        assertTrue(p.contains("降级链"));
        assertTrue(p.contains("【本次可用工具数】10 个"));
        assertTrue(p.contains("【当前时间】"));
        assertTrue(p.contains("gui.unfold"));
    }

    @Test public void missingAssetFallsBackSafely() {
        PromptSource.setOverrideForTest("");
        String p = L2LLMCompiler.buildAgentLoopPrompt(3);
        assertTrue(p.contains("接口 > 查信息 > 深链 > 点界面"));
        assertTrue(p.endsWith(AppChoiceSpec.PROMPT_RULE));
    }
}
