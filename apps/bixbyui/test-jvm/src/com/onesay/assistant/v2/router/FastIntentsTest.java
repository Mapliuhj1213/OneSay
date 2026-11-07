package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.caps.FastCaps;
import com.onesay.assistant.v2.executor.ExecProtocol;
import com.onesay.assistant.v2.executor.backend.RecentsClear;
import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** ★ fix/triage-fast：快路径模板 / 多意图拆分 / 清空后台 / 接线。 */
public class FastIntentsTest {

    static String sign(String q) {
        FastIntents.Hit h = FastIntents.match(q);
        return h == null ? null : h.sign;
    }

    @Test public void lockAndRecentsAndNav() {
        for (String q : new String[]{"锁屏", "帮我锁屏", "锁定屏幕", "把屏幕锁了", "锁一下屏吧", "息屏", "熄屏", "黑屏", "关掉屏幕", "锁上手机"}) {
            assertEquals(q, "screen.lock", sign(q));
        }
        for (String q : new String[]{"清空后台", "清空后台应用", "帮我清理后台", "关闭后台应用", "把后台都关了", "杀掉后台", "一键清理",
                "清理内存", "关闭所有应用", "清除最近任务", "关掉所有后台程序", "后台全部清掉", "释放内存"}) {
            assertEquals(q, "sys.recents.clear", sign(q));
        }
        for (String q : new String[]{"返回桌面", "回到桌面", "回桌面", "回到主屏幕", "返回主页", "帮我回到桌面"}) assertEquals(q, "nav.home", sign(q));
        for (String q : new String[]{"返回", "返回上一页", "后退"}) assertEquals(q, "nav.back", sign(q));
        for (String q : new String[]{"打开最近任务", "多任务", "看看后台"}) assertEquals(q, "nav.recents", sign(q));
    }

    @Test public void mediaVolumeBrightness() {
        assertEquals("media.pause", sign("暂停音乐"));
        assertEquals("media.play", sign("继续播放"));
        assertEquals("media.next", sign("切歌"));
        assertEquals("media.prev", sign("上一曲"));
        assertEquals("volume.media.up", sign("音量调大一点"));
        assertEquals("volume.media.down", sign("太吵了"));
        assertEquals("volume.ring.mute", sign("静音"));
        FastIntents.Hit h = FastIntents.match("音量调到最大");
        assertEquals("volume.media.set", h.sign);
        assertEquals(15, h.args.optInt("level"));
        assertEquals(8, FastIntents.match("把音量调到一半").args.optInt("level"));
        assertEquals(5, FastIntents.match("音量调到百分之三十").args.optInt("level"));
        assertEquals(255, FastIntents.match("亮度调到最大").args.optInt("level"));
        assertEquals(77, FastIntents.match("亮度调到百分之三十").args.optInt("level"));
        assertEquals("settings.brightness.down", sign("屏幕太亮了"));
        assertEquals("volume.media.mute", sign("音量调到0"));
    }

    @Test public void togglesTimersAlarms() {
        assertEquals("dnd.on", sign("开一下勿扰"));
        assertEquals("dnd.off", sign("把免打扰关了"));
        assertEquals("connectivity.wifi.on", sign("打开WiFi"));
        assertEquals("connectivity.bt.off", sign("蓝牙关掉"));
        assertEquals("connectivity.airplane.on", sign("开飞行模式"));
        assertEquals("connectivity.gps.on", sign("打开GPS"));
        assertEquals("settings.page.hotspot", sign("开热点"));
        assertEquals("torch.on", sign("把手电筒打开"));
        assertEquals(600, FastIntents.match("10分钟后提醒我").args.optInt("seconds"));
        assertEquals(1800, FastIntents.match("半小时后提醒我").args.optInt("seconds"));
        assertEquals(300, FastIntents.match("倒计时5分钟").args.optInt("seconds"));
        FastIntents.Hit a = FastIntents.match("明天早上6点半叫我起床");
        assertEquals("clock.alarm.set", a.sign);
        assertEquals(6, a.args.optInt("hour"));
        assertEquals(30, a.args.optInt("minute"));
        a = FastIntents.match("下午3点15叫我");
        assertEquals(15, a.args.optInt("hour"));
        assertEquals(15, a.args.optInt("minute"));
        assertEquals(22, FastIntents.match("设个晚上十点的闹钟").args.optInt("hour"));
        FastIntents.Hit sms = FastIntents.match("给张三发短信说我等一下到");
        assertEquals("sms.send", sms.sign);
        assertEquals("张三", sms.args.optString("number"));
        assertEquals("我等一下到", sms.args.optString("text"));
        assertEquals("phone.call", sign("给老婆打个电话"));
    }

    /** 不该抢的：状态问句、带内容的播放、方法问句、复合句、需要选人的动作、S3 提醒。（★ int/1102：导航、重复闹钟已有模板，移出） */
    @Test public void negativesStayOut() {
        for (String q : new String[]{"蓝牙开了吗", "wifi开着吗", "勿扰是不是开着", "播放周杰伦的歌", "锁屏密码怎么改", "怎么清空后台",
                "截个图然后发给张三", "打开wifi和蓝牙", "明天下午三点提醒我开会", "后台为什么这么多",
                "音量调到跟刚才一样", "返回什么意思", "桌面壁纸换一下", "我想听歌", "记一下我车停在B2"}) {
            assertNull(q + " → " + FastIntents.match(q), FastIntents.match(q));
        }
    }

    @Test public void switchOffRestoresOldBehaviour() {
        FastIntents.setEnabled(false);
        try {
            assertNull(FastIntents.match("锁屏"));
            assertNull(CompoundSplitter.split("打开wifi和蓝牙", null));
        } finally {
            FastIntents.setEnabled(true);
        }
    }

    // ------------------------------------------------------------------ 多意图拆分

    @Test public void compoundPlan() {
        Invocation p = CompoundSplitter.split("把亮度调低、开勿扰、10分钟后提醒我", null);
        assertNotNull(p);
        assertTrue(p.isPlan());
        assertEquals(3, p.steps.size());
        assertEquals("settings.brightness.down", p.steps.get(0).sign);
        assertEquals("dnd.on", p.steps.get(1).sign);
        assertEquals("clock.timer.set", p.steps.get(2).sign);
        assertEquals(600, p.steps.get(2).args.optInt("seconds"));
        assertEquals(Invocation.RouteSource.L0_RULE, p.source);
        assertEquals("亮度调低 · 开勿扰 · 10分钟后提醒我", CompoundSplitter.planHeadline(p));
        Invocation w = CompoundSplitter.split("打开wifi和蓝牙", null);
        assertEquals("connectivity.bt.on", w.steps.get(1).sign);   // 共享动词
        assertEquals(2, CompoundSplitter.split("关掉wifi然后打开蓝牙", null).steps.size());
        assertNull(CompoundSplitter.split("截个图然后发给张三", null));
        assertNull(CompoundSplitter.split("锁屏", null));
        assertNull(CompoundSplitter.split("张三和李四", null));
    }

    @Test public void compoundUsesL0ResolverForNonFastClauses() {
        final CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        final L0RuleRouter l0 = L0RuleRouter.forRegistry(reg);
        Invocation p = CompoundSplitter.split("打开省电模式，然后打开wifi设置", new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String c) { return l0.route(c); }
        });
        assertNotNull(p);
        assertEquals("settings.page.wifi", p.steps.get(1).sign);
    }

    @Test public void seedSubgoalsAndPinnedSigns() {
        List<com.onesay.assistant.v2.island.Subgoal> g = CompoundSplitter.seedSubgoals("截个图然后发给张三");
        assertEquals(2, g.size());
        assertEquals("截个图", g.get(0).title);
        assertEquals(com.onesay.assistant.v2.island.Subgoal.State.DOING, g.get(0).state);
        assertTrue(CompoundSplitter.seedSubgoals("锁屏").isEmpty());
        Set<String> s = CompoundSplitter.fastSigns("截个图然后发给张三，再回到桌面");
        assertTrue(s.toString(), s.contains("ui.screenshot") && s.contains("nav.home"));
    }

    // ------------------------------------------------------------------ 能力 / 清空后台 / 接线

    @Test public void fastCapsAreSafeAndRegistered() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        FastCaps.register(reg);
        for (String s : new String[]{"nav.home", "nav.back", "nav.recents", "media.play", "media.pause", "media.next", "media.prev",
                "sys.recents.clear", "dnd.on", "dnd.off", "camera.open", "settings.page.hotspot"}) {
            Capability c = reg.describe(s);
            assertNotNull(s, c);
            assertFalse(s + " 不需确认", c.confirm);
            assertTrue(s, c.risk <= 1);
        }
        assertEquals("input keyevent 3", reg.describe("nav.home").cmdTemplate);
        assertEquals("custom", reg.describe("sys.recents.clear").executorType);
        // 快路径产出的每个签名都必须在能力表里（否则面板报未知能力）
        for (String q : new String[]{"锁屏", "清空后台", "回桌面", "返回", "多任务", "截屏", "暂停", "继续播放", "下一首", "上一首", "静音",
                "音量调大", "音量调到最大", "亮度调到50", "太亮了", "开勿扰", "开热点", "拍照", "重启", "关机", "10分钟后提醒我",
                "设个7点的闹钟", "给妈妈打个电话", "发短信给10086说你好", "存储还剩多少", "音量多少", "打开自动亮度", "打开省电模式"}) {
            FastIntents.Hit h = FastIntents.match(q);
            assertNotNull(q, h);
            assertNotNull(q + " → " + h.sign, reg.describe(h.sign));
        }
    }

    @Test public void recentsClearFindsSamsungButton() throws Exception {
        FsTree t = new FsTree();
        FsTree.Node a = new FsTree.Node();
        a.text = "微信";
        FsTree.Node b = new FsTree.Node();
        b.text = "全部关闭";
        b.l = 400; b.t = 2600; b.r = 1040; b.b = 2700;
        t.nodes.add(a);
        t.nodes.add(b);
        assertSame(b, RecentsClear.findClearAll(t));
        FsTree t2 = new FsTree();
        FsTree.Node c = new FsTree.Node();
        c.rid = "com.android.systemui:id/clear_all";
        t2.nodes.add(c);
        assertSame(c, RecentsClear.findClearAll(t2));
        FsTree t3 = new FsTree();
        FsTree.Node d = new FsTree.Node();
        d.text = "没有最近使用的应用";
        t3.nodes.add(d);
        assertNull(RecentsClear.findClearAll(t3));
        assertTrue(RecentsClear.isEmptyRecents(t3));
        assertTrue(ExecProtocol.isFixedTemplate(RecentsClear.DAEMON_TEMPLATE));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void wiring() throws Exception {
        String ur = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue("triage 先走第 0 层", ur.contains("FastPathTriage.triage(query"));
        assertTrue("分诊用核心短语再试 L0", ur.contains("FastPathTriage.l0WithCore(query"));
        assertTrue("级联交付零 LLM 计划", ur.contains("if (tr.plan != null)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/capability/CapabilityRegistry.java").contains("FastCaps.register(sInstance)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java").contains("new com.onesay.assistant.v2.executor.backend.SysActionBackend()"));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/CapabilityRetriever.java").contains("CompoundSplitter.fastSigns(query)"));
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fss.contains("CompoundSplitter.seedSubgoals(command)"));
        assertTrue(fss.contains("CompoundSplitter.planHeadline(plan)"));
        String d = src("tools/kdaemon/exec_daemon.v4.sh");
        assertTrue(d.contains("'bixby-clear-recents')") && d.contains("clear_recents()"));
        assertFalse("清空后台绝不打开应用管理", src("app/src/com/onesay/assistant/v2/executor/backend/SysActionBackend.java").contains("APPLICATION_SETTINGS"));
        assertTrue(src("app/assets/agent_prompt.md").contains("绝不为这些去打开「应用管理」"));
        assertTrue(src("app/AndroidManifest.xml").contains("android.permission.ACCESS_NOTIFICATION_POLICY"));
    }

    @Test public void triageDecisionForFastHitNeverLoops() {
        TriageDecider.Decision d = TriageDecider.fastPath("帮我锁屏", "screen.lock", false);
        assertEquals(TriageDecider.Label.DO_ACT, d.label);
        assertFalse(d.useAgentLoop);
        assertFalse(TriageDecider.islandUpFront(d));
        assertEquals(TriageDecider.Label.DO_READ, TriageDecider.fastPath("音量多少", "volume.media.get", false).label);
        TriageDecider.Decision p = TriageDecider.fastPath("打开wifi和蓝牙", Invocation.SIGN_PLAN, true);
        assertFalse(p.useAgentLoop);
        assertEquals("0c-compound", p.rule);
    }
}
