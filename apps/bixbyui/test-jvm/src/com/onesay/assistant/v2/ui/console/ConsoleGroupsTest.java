package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 2026-11-05 控制台整理第二步：设置区分组、旧入口全覆盖、开发者默认折叠、状态卡首帧占位。 */
public class ConsoleGroupsTest {

    static final String CON = "app/src/com/onesay/assistant/v2/ui/console/";

    static String src(String rel) {
        return KernelWiring.src(rel);
    }

    // ------------------------------------------------------------------ 分组结构

    @Test public void groupsAreTheAgreedList() {
        List<String> titles = new ArrayList<String>();
        for (String[] g : ConsoleGroups.GROUPS) titles.add(g[1]);
        // [console-align] 2026-11-12 分组表改成两端同一份（docs/CONSOLE.md）；原为 模型 / 功能 / 语音唤醒 / 朗读 / 灵动岛 / 主动建议 / 权限保活 / 隐私
        assertEquals(Arrays.asList("连接", "权限与审批", "模型与密钥", "语音", "替你操作", "功能", "下载", "插件"), titles);
        for (String[] g : ConsoleGroups.GROUPS) {
            assertEquals(g[1], ConsoleGroups.title(g[0]));
            assertTrue("副标题不空 " + g[1], g[2].length() > 0);
        }
        assertEquals("开发者", ConsoleGroups.title(ConsoleGroups.DEV));
    }

    @Test public void homeRendersGroupsInOrderThenAboutThenDeveloperLast() {
        String h = src(CON + "HomePage.java");
        int set = h.indexOf("k.group(col, \"设置\")");
        assertTrue(set > h.indexOf("k.group(col, \"计划\")"));
        int prev = set;
        for (String[] g : ConsoleGroups.GROUPS) {
            int i = h.indexOf("k.linkRow(g1, \"" + g[1] + "\"", set);
            assertTrue("首页设置区有「" + g[1] + "」且顺序对", i > prev);
            prev = i;
        }
        int about = h.indexOf("\"关于\"", prev), dev = h.indexOf("ConsoleGroups.DEV_TITLE", prev);
        assertTrue("关于在分组之后", about > prev);
        assertTrue("开发者在关于之后", dev > about);
        String tail = h.substring(dev, h.indexOf("ConsoleStatus.Snapshot last = StatusProbe.last();"));
        assertFalse("开发者之后不再有分组（放最下）", tail.contains("k.group(col"));
    }

    @Test public void developerCollapsedByDefaultAndHoldsDebugItems() {
        assertFalse(ConsoleGroups.DEV_EXPANDED_BY_DEFAULT);
        String h = src(CON + "HomePage.java");
        assertTrue("首帧按默认折叠", h.contains("setDevExpanded(ConsoleGroups.DEV_EXPANDED_BY_DEFAULT);"));
        assertTrue("折叠 = 子行 GONE", h.contains("mDevBody.setVisibility(on ? View.VISIBLE : View.GONE);"));
        assertTrue("点标题行切换", h.contains("setDevExpanded(!mDevExpanded);"));
        int fill = h.indexOf("private void fillDev()");
        String body = h.substring(fill, h.indexOf("private void setDevExpanded", fill));
        // [console-align]「功能插件」搬到设置区「插件」：开发者里不再有它（原断言要求开发者里有 "功能插件" 与 new AdvancedPage.PluginsPage(c)）
        for (String s : new String[]{"\"诊断与摘要\"", "\"调试工具\"", "new GroupPages.DevFeaturesPage(c)", "new DeveloperPage(c)"}) {
            assertTrue("开发者里有 " + s, body.contains(s));
        }
        assertFalse("[console-align] 插件列表不在开发者里重复", body.contains("new AdvancedPage.PluginsPage(c)"));
        int set = h.indexOf("k.group(col, \"设置\")");
        assertTrue("[console-align] 插件列表在设置区「插件」", h.indexOf("k.linkRow(g1, \"插件\"", set) > set
                && h.indexOf("new AdvancedPage.PluginsPage(c)", set) > h.indexOf("k.linkRow(g1, \"插件\"", set));
        assertTrue("开发者模式变了会重画", h.contains("if (mDevBuiltFor != c.devMode()) fillDev();"));
        assertTrue("开发者模式提示指向新位置", DevMode.hint(0).contains("开发者"));
        assertFalse(DevMode.hint(0).contains("模型与密钥"));
    }

    // ------------------------------------------------------------------ 插件行分到哪一页（不丢、不重复）

    @Test public void featurePluginRouting() {
        assertEquals(ConsoleGroups.DEV, ConsoleGroups.groupOfFeaturePlugin("diag"));
        assertEquals(ConsoleGroups.DEV, ConsoleGroups.groupOfFeaturePlugin("summary"));
        assertEquals(ConsoleGroups.SUGGEST, ConsoleGroups.groupOfFeaturePlugin("suggest"));
        assertEquals("suggest = SuggestPlug.ID", com.onesay.assistant.plug.suggest.core.SuggestPlug.ID, ConsoleGroups.SUGGEST_PLUGINS[0]);
        // [console-align] conn / phongent 进「连接」，errand 进「替你操作」（原断言三者都在「功能」）
        for (String id : new String[]{"planner", "sched", "s3", "callback", "brief", "rain", "以后新加的"}) {
            assertEquals(id, ConsoleGroups.FEATURES, ConsoleGroups.groupOfFeaturePlugin(id));
        }
        for (String id : new String[]{"pclink", "phongent", "conn"}) assertEquals(id, ConsoleGroups.CONNECT, ConsoleGroups.groupOfFeaturePlugin(id));
        for (String id : new String[]{"phone", "apifirst", "errand"}) assertEquals(id, ConsoleGroups.ACT, ConsoleGroups.groupOfFeaturePlugin(id));
        // FeatureKernel.pickedFor：include 优先；否则按 exclude
        assertTrue(FeatureKernel.pickedFor("diag", ConsoleGroups.DEV_PLUGINS, null));
        assertFalse(FeatureKernel.pickedFor("sched", ConsoleGroups.DEV_PLUGINS, null));
        assertFalse(FeatureKernel.pickedFor("diag", null, ConsoleGroups.FEATURES_EXCLUDE));
        assertTrue(FeatureKernel.pickedFor("sched", null, ConsoleGroups.FEATURES_EXCLUDE));
        assertTrue(FeatureKernel.pickedFor("x", null, null));
    }

    /** 每个注册的插件在「功能 / 开发者 / 主动建议」三页里恰好被画一次（PAGE_FEATURES），且分组表里的 id 都是真插件。 */
    @Test public void everyPluginDrawnExactlyOnce() {
        List<String> ids = new ArrayList<String>();
        for (FeaturePlugin p : FeatureKernel.get().plugins()) ids.add(p.id());
        for (String id : ConsoleGroups.FEATURES_EXCLUDE) assertTrue("分组表里的 " + id + " 是注册了的插件", ids.contains(id));
        for (String id : ids) {
            int n = 0;
            if (FeatureKernel.pickedFor(id, null, ConsoleGroups.FEATURES_EXCLUDE)) n++;
            if (FeatureKernel.pickedFor(id, ConsoleGroups.DEV_PLUGINS, null)) n++;
            if (FeatureKernel.pickedFor(id, ConsoleGroups.SUGGEST_PLUGINS, null)) n++;
            if (FeatureKernel.pickedFor(id, ConsoleGroups.CONNECT_PLUGINS, null)) n++;   // [console-align] 新增两页也要计数
            if (FeatureKernel.pickedFor(id, ConsoleGroups.ACT_PLUGINS, null)) n++;
            assertEquals(id + " 只画在一页", 1, n);
        }
        assertTrue(src(CON + "FeaturesPage.java").contains("ConsoleHost.PAGE_FEATURES, feats, null, ConsoleGroups.FEATURES_EXCLUDE"));
        String gp = src(CON + "GroupPages.java");
        assertTrue(gp.contains("ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.DEV_PLUGINS, null"));
        assertTrue(gp.contains("ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.SUGGEST_PLUGINS, null"));
        assertTrue(gp.contains("ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.CONNECT_PLUGINS, null"));
        assertTrue(gp.contains("ConsoleHost.PAGE_FEATURES, g, ConsoleGroups.ACT_PLUGINS, null"));
        // [console-align] 说明文字「搬到隐私」→「搬到权限与审批」（判断内容不变）
        assertTrue("屏幕与剪贴板整页搬到权限与审批", gp.contains("addConsolePage(c, ConsoleHost.PAGE_SCREEN, screen)"));
        assertTrue("旧 v1 卡片仍有地方画", gp.contains("addConsoleCards(c, legacy)"));
    }

    // ------------------------------------------------------------------ 旧入口 → 新位置

    @Test public void movesTableAllResolvable() {
        assertTrue(ConsoleGroups.MOVES.length >= 35);
        for (String[] m : ConsoleGroups.MOVES) {
            assertEquals(Arrays.toString(m), 4, m.length);
            assertTrue("「" + m[0] + "」→「" + m[1] + "」：" + m[2] + " 里找不到 " + m[3],
                    src("app/src/com/onesay/assistant/v2/" + m[2]).contains(m[3]));
        }
    }

    /** 整理前（6a7a6fe）三个设置页 + 首页上的每一个入口标题，整理后都还在控制台某一页的源码里。 */
    @Test public void everyOldEntryStillSomewhere() {
        String[] old = {
                // 首页
                "灵动岛", "关于", "模型与密钥",
                // 功能与权限（本页画的）
                "悬浮窗", "电池优化白名单", "Root 通道", "麦克风", "屏幕与剪贴板", "其他",
                // 语音
                "声纹", "取消录制", "语音问就语音答", "朗读完整回答", "打字问也读出来", "回答后接着听", "显示「正在听 / 识别中」",
                "唤醒手感", "按下即听", "息屏唤醒时说「我在」", "抢话不丢字", "自适应断句", "思考模式", "唤醒提示音",
                // 模型与密钥
                // [console-align]「API 密钥」一行拆成「主模型 › API Key」与「其他模型与密钥 › 语音识别 Key」（都进 API 密钥面板）
                "API Key", "语音识别 Key", "视觉模型", "读数类问题直接回答", "邮箱（IMAP）", "浅色外观", "功能插件",
        };
        String all = src(CON + "HomePage.java") + src(CON + "FeaturesPage.java") + src(CON + "GroupPages.java")
                + src(CON + "VoicePage.java") + src(CON + "AdvancedPage.java") + src(CON + "DeveloperPage.java")
                + src(CON + "AboutPage.java");   // [console-align]「外观」搬到「关于」页
        for (String t : old) assertTrue("旧入口「" + t + "」还找得到", all.contains("\"" + t + "\""));
        // 开发者页本身（调试动作 / 关停文件 / 日志）原样保留
        String dev = src(CON + "DeveloperPage.java");
        for (String t : new String[]{"调试动作", "运行信息", "关停文件（*.off）", "自测与诊断输出"}) assertTrue(t, dev.contains("\"" + t + "\""));
    }

    @Test public void voiceSplitIntoWakeAndRead() {
        String v = src(CON + "VoicePage.java");
        int read = v.indexOf("private void buildRead(");
        assertTrue(read > 0);
        String wakePart = v.substring(0, read);
        int end = v.indexOf("\n    }\n", read);
        String readPart = v.substring(read, end);
        for (String t : new String[]{"语音问就语音答", "朗读完整回答", "打字问也读出来", "回答后接着听", "显示「正在听 / 识别中」"}) {
            assertTrue("朗读页有 " + t, readPart.contains("\"" + t + "\""));
            assertFalse("语音唤醒页没有 " + t, wakePart.contains("\"" + t + "\""));
        }
        for (String t : new String[]{"声纹", "唤醒手感", "唤醒提示音"}) assertTrue("语音唤醒页有 " + t, wakePart.contains("\"" + t + "\""));
        assertFalse("思考模式搬走了", v.contains("k.group(col, \"思考模式\")"));
        assertTrue(src(CON + "AdvancedPage.java").contains("k.group(col, \"思考模式\")"));
        assertTrue("prefs 键不变", readPart.contains("WakeTuning.K_FOLLOWUP") && readPart.contains("WakeTuning.K_LIVE_STATE")
                && wakePart.contains("WakeTuning.K_SHORT_CHIME") && wakePart.contains("WakeTuning.K_ADAPTIVE_EP"));
        assertTrue("提醒条 / 状态页的声纹入口仍进语音唤醒", v.contains("this(c, MODE_WAKE);"));
    }

    // ------------------------------------------------------------------ 状态卡首帧占位（按键 未知 → v9 顶动布局）

    static final String ALIVE_V9 = "ALIVE\nMOD\n# Bixby key takeover daemon - v9 \"x\" (2026-11-01)\nEND\n";

    @Test public void pendingDaemonPillUsesLastVersionGrey() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.lastDaemonVer = "v9";
        ConsoleStatus.Pill p = ConsoleStatus.daemon(s);
        assertEquals("首帧用上次值", "按键 v9", p.label);
        assertEquals("但不写绿（还没确认）", ConsoleStatus.Level.UNKNOWN, p.level);
        s.rootOk = true;   // root 回来了，守护探针还没回来
        assertEquals("按键 v9", ConsoleStatus.daemon(s).label);
        assertEquals(ConsoleStatus.Level.UNKNOWN, ConsoleStatus.daemon(s).level);
        s.daemonProbe = ALIVE_V9;
        s.done = true;
        assertEquals("探完同一个文字 → 胶囊不变宽", "按键 v9", ConsoleStatus.daemon(s).label);
        assertEquals(ConsoleStatus.Level.OK, ConsoleStatus.daemon(s).level);
        assertEquals("v9", ConsoleStatus.daemonVersion(s));
    }

    @Test public void pendingPillsSameLabelsAsHealthy() {
        ConsoleStatus.Snapshot pend = new ConsoleStatus.Snapshot();
        pend.lastDaemonVer = "v9";
        ConsoleStatus.Snapshot ok = new ConsoleStatus.Snapshot();
        ok.wakeRunning = true;
        ok.micReady = true;
        ok.rootOk = true;
        ok.daemonProbe = ALIVE_V9;
        ok.llmConfigured = true;
        ok.done = true;
        List<ConsoleStatus.Pill> a = ConsoleStatus.pills(pend), b = ConsoleStatus.pills(ok);
        for (int i = 0; i < a.size(); i++) {
            assertEquals("占位与正常同文字（同宽）", b.get(i).label, a.get(i).label);
            assertEquals(ConsoleStatus.Level.UNKNOWN, a.get(i).level);
            assertEquals(ConsoleStatus.Level.OK, b.get(i).level);
        }
        assertEquals("检测中", ConsoleStatus.hero(pend).word);
        assertTrue("还在探不出提醒条", ConsoleStatus.banners(pend).isEmpty());
    }

    @Test public void unknownOnlyAfterDoneOrRootFailed() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.lastDaemonVer = "v9";
        s.rootOk = false;
        assertEquals("root 已确定不通 → 按键未知（不拿上次值糊弄）", "按键 未知", ConsoleStatus.daemon(s).label);
        s.rootOk = true;
        s.done = true;
        assertEquals("探完守护探针没回来 → 未知", "按键 未知", ConsoleStatus.daemon(s).label);
        s.daemonProbe = "DEAD\nMOD\nEND";
        assertEquals("按键 未运行", ConsoleStatus.daemon(s).label);
        assertNull("没在运行不存版本", ConsoleStatus.daemonVersion(s));
        ConsoleStatus.Snapshot n = new ConsoleStatus.Snapshot();
        assertEquals("从没探到过 → 只写「按键」", "按键", ConsoleStatus.daemon(n).label);
    }

    @Test public void probeSeedsAndPersistsVersion() {
        String p = src(CON + "StatusProbe.java");
        assertTrue("首帧快照带上次版本", p.contains("public static ConsoleStatus.Snapshot seed(Context ctx)") && p.contains("s.lastDaemonVer = lastDaemonVer(app);"));
        assertTrue("探完存版本", p.contains("saveDaemonVer(app, s);"));
        assertTrue("copy 带上占位字段", p.contains("b.lastDaemonVer = a.lastDaemonVer;"));
        String h = src(CON + "HomePage.java");
        assertTrue("首页首帧用 seed 而不是空快照", h.contains("render(last != null ? last : StatusProbe.seed(c.act));"));
        assertFalse(h.contains("render(last != null ? last : new ConsoleStatus.Snapshot());"));
    }
}
