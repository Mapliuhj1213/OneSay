package com.onesay.assistant.v2.richout;

import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.richout.core.DiagramHtml;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;
import com.onesay.assistant.plug.richout.core.RichText;
import com.onesay.assistant.plug.richout.host.RichOutHost;
import com.onesay.assistant.v2.prompt.AgentPromptAssembler;
import com.onesay.assistant.v2.prompt.PromptSource;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * [richout] 宿主适配层里 JVM 能测的部分：开关裁决（变体默认 / 依赖链 / --es plug 解析）、提示词替换、问答终稿（不剥代码围栏、保留 Markdown）、
 * 朗读计划、灵动岛一行、agent_prompt 第 4 条按 plug:richout 取舍、assets 离线库守卫。
 * 测试跑的是 release 变体（run_unit_tests 口径）：没注入开关时插件关 = 改动前口径。
 */
public class RichOutHostTest {

    @After public void tearDown() {
        RichOutHost.setSwitchesForTest(null);
        PromptSource.setOverrideForTest(null);
    }

    static void allOn() {
        RichOutHost.setSwitchesForTest(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                return true;
            }
        });
    }

    static final String CODE_ANSWER = "下面是示例：\n\n```python\nprint('hi')\n```\n\n详见 [文档](https://docs.python.org/3/)。";

    // ------------------------------------------------------------------ 开关

    @Test public void releaseVariantDefaultsOffAndLeavesEverythingAsBefore() {
        assertFalse("release 变体（JVM 单测口径）出厂关", com.onesay.assistant.v2.BuildVariant.IS_DEBUG);
        assertFalse(RichOutHost.on());
        assertFalse(RichOutHost.diagramsOn());
        assertFalse(RichOutHost.readerOn());
        assertEquals(RichOutPlugs.ASK_RULE_PLAIN, RichOutHost.askRule());
        String p = "1. a\n3. " + RichOutPlugs.ASK_RULE_PLAIN + "\n";
        assertSame(p, RichOutHost.patchAskPrompt(p));
        assertEquals(1024, RichOutHost.askMaxTokens(1024));
        assertFalse(RichOutHost.keepFence("```python\nx\n```"));
        assertEquals(CODE_ANSWER, RichOutHost.islandText(CODE_ANSWER));
        assertNull(RichOutHost.speechPlan(CODE_ANSWER));
    }

    @Test public void dependencyChainTurnsChildrenOff() {
        RichOutHost.setSwitchesForTest(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                return !RichOutPlugs.ID.equals(id);
            }
        });
        assertFalse(RichOutHost.on());
        assertFalse("总开关关 → 图也关", RichOutHost.diagramsOn());
        assertFalse("总开关关 → 阅读器也关", RichOutHost.readerOn());
        RichOutHost.setSwitchesForTest(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                return !RichOutPlugs.ID_DIAGRAM.equals(id);
            }
        });
        assertTrue(RichOutHost.on());
        assertFalse(RichOutHost.diagramsOn());
        assertTrue(RichOutHost.readerOn());
    }

    @Test public void debugCommandParsing() {
        List<String[]> c = RichOutHost.parseCommand("list, richout=off ,diagram=on,reader=default,richout.reader=0,mika.guard=off,bogus=on,richout=maybe");
        assertEquals(4, c.size());
        assertArrayEquals(new String[] {"richout", "off"}, c.get(0));
        assertArrayEquals(new String[] {"richout.diagram", "on"}, c.get(1));
        assertArrayEquals(new String[] {"richout.reader", "default"}, c.get(2));
        assertArrayEquals(new String[] {"richout.reader", "off"}, c.get(3));
        assertTrue(RichOutHost.parseCommand(null).isEmpty());
        assertTrue("别的插件族不碰", RichOutHost.parseCommand("guard=off,all=off").isEmpty());
    }

    // ------------------------------------------------------------------ 问答提示词 / 终稿

    @Test public void askPromptRuleSwappedOnlyWhenOn() {
        allOn();
        String tpl = "【回答形态要求】\n3. " + RichOutPlugs.ASK_RULE_PLAIN + "\n4. x\n";
        String out = RichOutHost.patchAskPrompt(tpl);
        assertFalse(out.contains(RichOutPlugs.ASK_RULE_PLAIN));
        assertTrue(out.contains("3. " + RichOutPlugs.ASK_RULE_RICH));
        assertTrue(out.contains("```mermaid") && out.contains("```chart") && out.contains("```svg"));
        assertTrue("尺寸上限写进提示词", out.contains("≤20KB") && out.contains("≤60 行") && out.contains("≤200 个数据点"));
        String legacy = RichOutHost.patchAskPrompt("3. " + RichOutPlugs.LEGACY_RULE_PLAIN + "\n");
        assertTrue(legacy.contains(RichOutPlugs.ASK_RULE_RICH));
        String hot = RichOutHost.patchAskPrompt("被热改过的模板，没有原句\n");
        assertTrue("找不到原句 → 末尾补一条", hot.endsWith(RichOutPlugs.ASK_RULE_RICH + "\n"));
        assertEquals(RichOutPlugs.ASK_MAX_TOKENS, RichOutHost.askMaxTokens(1024));
    }

    static String callStatic(String name, String arg) throws Exception {
        Method m = L2LLMCompiler.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, arg);
    }

    @Test public void askAnswerKeepsMarkdownAndCodeFenceWhenOn() throws Exception {
        String whole = "```python\nfor i in range(3):\n    print(i)\n```";
        // 关：旧行为（剥围栏、去 Markdown）
        String off = callStatic("polishAnswer", callStatic("stripFenceAsk", whole));
        assertFalse(off.contains("```"));
        allOn();
        assertEquals("整段代码块：围栏就是内容", whole, callStatic("stripFenceAsk", whole));
        assertEquals("包 JSON 仍按旧规矩剥", "{\"text\":\"hi\"}", callStatic("stripFenceAsk", "```json\n{\"text\":\"hi\"}\n```"));
        assertEquals("无标签包 JSON 也剥", "{\"a\":1}", callStatic("stripFenceAsk", "```\n{\"a\":1}\n```"));
        String polished = callStatic("polishAnswer", CODE_ANSWER);
        assertTrue(polished.contains("```python\nprint('hi')\n```"));
        assertTrue(polished.contains("[文档](https://docs.python.org/3/)"));
    }

    // ------------------------------------------------------------------ 朗读 / 灵动岛

    @Test public void speechSkipsCodeAndIslandIsOneLine() {
        allOn();
        RichText.Speech sp = RichOutHost.speechPlan(CODE_ANSWER);
        assertNotNull(sp);
        assertFalse("代码不念", sp.spoken.contains("print"));
        assertFalse("网址不念", sp.spoken.contains("https"));
        assertNotNull("有代码 → 只念一句提示", sp.cue);
        assertTrue(sp.spoken.contains(sp.cue));
        String isl = RichOutHost.islandText(CODE_ANSWER);
        assertFalse(isl.contains("```"));
        assertFalse(isl.contains("\n"));
        assertFalse(isl.contains("print('hi')"));
        assertNull("纯文本不走朗读计划", RichOutHost.speechPlan("今天晴，最高 22 度。"));
        assertEquals("纯文本岛上原样", "今天晴，最高 22 度。", RichOutHost.islandText("今天晴，最高 22 度。"));
    }

    // ------------------------------------------------------------------ AgentLoop 提示词

    static String agentPrompt() {
        AgentPromptAssembler.Mounted m = new AgentPromptAssembler.Mounted(AgentPromptAssembler.DEFAULT_MOUNT);
        AgentPromptAssembler.Result r = AgentPromptAssembler.assemble(AgentPromptAssembler.parse(PromptSource.agentPrompt(null)), m,
                AgentPromptAssembler.vars(m, 7), true);
        assertTrue("超预算 " + r.overBudget, r.overBudget.isEmpty());
        assertTrue("丢段 " + r.dropped, r.dropped.isEmpty());
        return r.text;
    }

    @Test public void agentPromptRule4FollowsPlug() {
        String off = agentPrompt();
        assertTrue(off.contains("4. 最终答复适合屏幕阅读：先给结论、短段落，不要 Markdown、不要代码块、不要输出 JSON。"));
        assertFalse(off.contains("```mermaid"));
        assertFalse(off.contains("plug:richout"));
        allOn();
        String on = agentPrompt();
        assertFalse(on.contains("不要 Markdown、不要代码块"));
        assertTrue(on.contains("```mermaid（≤60 行）"));
        assertTrue(on.contains("```chart（Chart.js JSON、≤200 点）"));
        assertTrue(on.contains("[文字](https://…)"));
        assertFalse(on.contains("<!--"));
    }

    // ------------------------------------------------------------------ assets

    static File assets() {
        return new File(System.getProperty("bixby.root", "."), "app/assets");
    }

    @Test public void offlineLibsAreFlatAssetsWithinBudget() throws Exception {
        File dir = assets();
        assertTrue(dir.isDirectory());
        for (File f : dir.listFiles()) assertFalse("assets 只能扁平文件：" + f.getName(), f.isDirectory());
        File mer = new File(dir, DiagramHtml.ASSET_MERMAID), chart = new File(dir, DiagramHtml.ASSET_CHART);
        assertTrue(mer.isFile());
        assertTrue(chart.isFile());
        long total = mer.length() + chart.length();
        assertTrue("两个库合计 ≤ 1.5 MB（现 " + total + "）", total <= 1536L * 1024);
        String mhead = new String(Files.readAllBytes(mer.toPath()), "UTF-8").substring(0, 4000);
        String chead = new String(Files.readAllBytes(chart.toPath()), "UTF-8").substring(0, 400);
        assertTrue("UMD/全局变量形式（沙箱页面用 window.mermaid）", mhead.contains("define.amd"));
        assertTrue(chead.contains("Chart.js v3.9.1"));
        String lic = new String(Files.readAllBytes(new File(dir, "richout_LICENSES.txt").toPath()), "UTF-8");
        assertTrue(lic.contains("mermaid 9.1.7") && lic.contains("Chart.js 3.9.1") && lic.contains("MIT License"));
    }

    @Test public void sandboxOnlyMapsTheTwoLibs() {
        assertEquals(DiagramHtml.ASSET_MERMAID, DiagramHtml.assetFor(DiagramHtml.LIB_MERMAID));
        assertEquals(DiagramHtml.ASSET_CHART, DiagramHtml.assetFor(DiagramHtml.LIB_CHART));
        assertNull(DiagramHtml.assetFor(DiagramHtml.BASE + "lib/../agent_prompt.md"));
        assertNull(DiagramHtml.assetFor("https://richout.invalid/richout_mermaid.min.js"));
        assertNull(DiagramHtml.assetFor("file:///android_asset/richout_chart.umd.js"));
        assertNull(DiagramHtml.assetFor(null));
    }
}
