package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.exec.EdgeGesture;
import com.onesay.assistant.v2.exec.ExecState;
import com.onesay.assistant.v2.exec.ExecStopGate;
import com.onesay.assistant.v2.exec.RiskyApps;
import com.onesay.assistant.v2.exec.ShieldHoles;
import com.onesay.assistant.v2.island.IslandTouchDodge;
import com.onesay.assistant.v2.pclink.LinkPolicy;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * fix/1123-dexec：代操作执行链路（B6 B7 B8 B9 B10 B19 B29 B32 B38 B39 B40 + 「护罩挡了代操作自己的点按」）。
 */
public class Exec1123dTest {

    static final File ROOT = new File(System.getProperty("bixby.root", "."));

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, "app/src/com/onesay/assistant/v2/" + rel).toPath()), "UTF-8");
    }

    @After public void tearDown() {
        ExecStopGate.reset();
        PhoneAgent.RISK_GATE = true;
        PhoneAgent.JUMP_GATE = true;
        PhoneAgent.STATE_FIRST = true;
    }

    // ------------------------------------------------------------------ 护罩只拦用户手指（B8 / B40 同源）

    @Test public void holesNestAndUnion() {
        ShieldHoles h = new ShieldHoles();
        assertTrue(h.empty());
        assertEquals(1, h.open(100, 100, 164, 164));      // 外层：点按核对
        assertEquals(2, h.open(120, 120, 300, 200));      // 里层：无障碍手势
        assertTrue(Arrays.equals(new int[]{100, 100, 300, 200}, h.rect()));
        assertEquals(1, h.close());                       // 里层关掉：外层的孔还在（以前一关全关 → 补点被吞）
        assertFalse(h.empty());
        assertEquals(0, h.close());
        assertTrue(h.empty());
        assertEquals(0, h.close());                       // 多关不变负
        assertEquals(0, h.open(5, 5, 5, 9));              // 空矩形不开
    }

    @Test public void holeAroundGesturePath() {
        assertTrue(Arrays.equals(new int[]{60, 160, 540, 1140}, ShieldHoles.around(500, 1100, 100, 200, 40)));
    }

    @Test public void everyInjectionPathOpensHole() throws Exception {
        String a11y = src("a11y/A11yBackend.java");
        int g = a11y.indexOf("private boolean gesture(Path p");
        assertTrue(g > 0);
        String body = a11y.substring(g, a11y.indexOf("// ---", g));
        assertTrue("无障碍手势注入前开孔", body.contains("ShieldOverlay.holeForInject") && body.contains("unholeInject"));
        String fs = src("fastscreen/FastScreen.java");
        for (String m : new String[]{"public boolean tap(int x, int y)", "public boolean longPress(int x, int y, int ms)",
                "public boolean swipe(int x1, int y1, int x2, int y2, int ms)", "public String scroll(int x, int y, int dir)"}) {
            int k = fs.indexOf(m);
            assertTrue(m, k > 0);
            assertTrue("助手注入开孔：" + m, fs.substring(k, k + 600).contains("holeForInject"));
        }
        String sh = src("exec/ShieldOverlay.java");
        assertTrue("孔按层数叠加", sh.contains("mHoles.open(") && sh.contains("mHoles.close() == 0"));
        assertTrue("护罩不可获焦：代操作的按键不经过它", sh.contains("FLAG_NOT_FOCUSABLE"));
    }

    // ------------------------------------------------------------------ B8 手势导航

    @Test public void gestureNavMode() {
        assertTrue(EdgeGesture.gestureNav(2));
        assertFalse(EdgeGesture.gestureNav(0));
        assertFalse(EdgeGesture.gestureNav(1));
    }

    @Test public void edgeSwipesAreTakeover() {
        EdgeGesture g = new EdgeGesture(1080, 2400, 60, 80, 100);
        assertEquals(1, g.down(3, 1200));
        assertNull(g.move(50, 1200));
        assertEquals(EdgeGesture.BACK, g.move(140, 1205));
        assertNull("一串触摸只报一次", g.move(400, 1205));
        g.up();
        assertEquals(2, g.down(1077, 900));
        assertEquals(EdgeGesture.BACK, g.move(900, 900));
        g.up();
        assertEquals(3, g.down(540, 2390));
        assertEquals(EdgeGesture.HOME, g.move(540, 2200));
        g.up();
        // 系统手势识别器收走手指（CANCEL）也算
        g.down(2, 800);
        assertEquals(EdgeGesture.BACK, g.cancel());
        g.up();
        // 屏幕中间的误触不算
        assertEquals(0, g.down(540, 1200));
        assertNull(g.move(900, 1200));
        assertNull(g.cancel());
        // 从边缘往外 / 竖着划不算返回
        g.down(3, 1200);
        assertNull(g.move(3, 900));
    }

    @Test public void gestureTakeoverToast() {
        String b = ExecState.navTakeoverToast(EdgeGesture.BACK);
        assertTrue(b, b.contains("再划一次就是返回") && b.contains("继续"));
        assertTrue(ExecState.navTakeoverToast(EdgeGesture.HOME).contains("再上划一次就回桌面"));
        assertTrue("三键文案不变", ExecState.navTakeoverToast("按了返回键").contains("再按一次返回就是返回"));
    }

    @Test public void controllerWiresGestureNav() throws Exception {
        String c = src("exec/ExecModeController.java");
        assertTrue(c.contains("\"navigation_mode\"") && c.contains("setEdgeGesture(new EdgeGesture("));
        String sh = src("exec/ShieldOverlay.java");
        assertTrue(sh.contains("ACTION_CANCEL") && sh.contains("eg.move("));
    }

    // ------------------------------------------------------------------ B9 停止 = 整任务停

    @Test public void stopGate() {
        assertFalse(ExecStopGate.stoppedSince(0));
        ExecStopGate.userStopped("岛上点了停止", 1000);
        assertTrue(ExecStopGate.stoppedSince(900));
        assertTrue(ExecStopGate.stoppedSince(1000));
        assertFalse("之后的新任务不受上一次停止影响", ExecStopGate.stoppedSince(1001));
    }

    @Test public void stopCancelsWholeTaskAndPendingActions() throws Exception {
        String c = src("exec/ExecModeController.java");
        int k = c.indexOf("public void stop(String why)");
        String body = c.substring(k, k + 600);
        assertTrue(body.contains("ExecStopGate.userStopped") && body.contains("dr.abort()"));
        String p = src("kernel/plugins/PhonePlugin.java");
        assertTrue("点界面结果标成整任务取消（AgentLoop 见 CANCELLED 收尾）", p.contains("ExecStopGate.stoppedSince(runT0)") && p.contains("ErrorCode.CANCELLED"));
        String d = src("phone/FastScreenDriver.java");
        for (String op : new String[]{"dead(\"tap\")", "dead(\"injectTap\")", "dead(\"swipe\")", "dead(\"key\")", "dead(\"launch\")", "dead(\"type\")",
                "dead(\"gestureTap\")", "dead(\"clickNode\")"}) {
            assertTrue("停止后作废：" + op, d.contains(op));
        }
        String loop = src("router/AgentLoop.java");
        assertTrue(loop.contains("\"code\\\":\\\"CANCELLED\\\""));
    }

    // ------------------------------------------------------------------ B6 高风险 App 先确认

    @Test public void riskyAppKinds() {
        assertEquals("聊天", RiskyApps.kind("com.tencent.mm"));
        assertEquals("聊天", RiskyApps.kind("com.tencent.mobileqq"));
        assertEquals("短信", RiskyApps.kind("com.android.mms"));
        assertEquals("短信", RiskyApps.kind("com.google.android.apps.messaging"));
        assertEquals("电话", RiskyApps.kind("com.android.dialer"));
        assertEquals("支付", RiskyApps.kind("com.eg.android.AlipayGphone"));
        assertEquals("银行", RiskyApps.kind("com.chinamworld.main"));
        assertEquals("银行", RiskyApps.kind("com.example.mobilebank"));
        assertEquals("聊天", RiskyApps.kind("微信"));
        assertEquals("支付", RiskyApps.kind("支付宝"));
        assertNull(RiskyApps.kind("com.android.settings"));
        assertNull(RiskyApps.kind("com.sankuai.meituan"));
        assertNull(RiskyApps.kind("com.onesay.assistant.debug"));
        assertNull(RiskyApps.kind(""));
        assertNull(RiskyApps.kind(null));
        String d = RiskyApps.confirmDetail("微信", "聊天");
        assertTrue(d, d.contains("「微信」") && d.contains("发消息") && d.contains("允许"));
    }

    static FakeScreens.Phone wxPhone() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.launcher", "Home").button("设置"));
        ph.add("wx", FakeScreens.screen("com.tencent.mm", "LauncherUI").button("通讯录"));
        ph.on("*", "launch:com.tencent.mm", "wx");
        ph.taps.put("*|launch:com.tencent.mm", "wx");
        return ph;
    }

    @Test public void riskyStartPkgDeniedNeverLaunches() {
        FakeScreens.Phone ph = wxPhone();
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"x\"}"), h).run("给文件传输助手发个你好", "com.tencent.mm");
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertTrue(r.reason, r.reason.startsWith("用户没有同意"));
        assertEquals(1, h.confirms.size());
        assertFalse("没同意就不拉起", ph.log.contains("launch com.tencent.mm"));
    }

    @Test public void riskyStartPkgAllowedLaunchesOnce() {
        FakeScreens.Phone ph = wxPhone();
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"好了\"}"), h).run("给文件传输助手发个你好", "com.tencent.mm");
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        assertTrue(ph.log.contains("launch com.tencent.mm"));
        assertEquals("同一个 App 本次只问一次", 1, h.confirms.size());
    }

    @Test public void modelLaunchStepNeedsConfirm() {
        FakeScreens.Phone ph = wxPhone();
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"open\",\"app\":\"com.tencent.mm\"}]}", "{\"done\":\"x\"}"), h).run("删掉刚才那条记忆", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertFalse("模型自己要开微信：没同意就不开", ph.log.contains("launch com.tencent.mm"));
        assertTrue(r.reason, r.reason.contains("用户没有同意"));
    }

    @Test public void tappedIntoRiskyAppAsksAndGoesHome() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.launcher", "Home").button("微信"));
        ph.add("wx", FakeScreens.screen("com.tencent.mm", "LauncherUI").button("通讯录"));
        ph.on("home", "微信", "wx");
        ph.on("wx", "<home>", "home");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:微信}}]}", "{\"done\":\"x\"}"), h)
                .run("找长期记忆", null);
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertTrue(ph.log.toString(), ph.log.contains("key home"));
    }

    @Test public void alreadyInRiskyAppAtStartIsNotAsked() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("wx", FakeScreens.screen("com.tencent.mm", "ChattingUI").button("文件传输助手"));
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"好了\"}"), h).run("看看", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("开局用户本来就在里面：不是我们打开的，不问", 0, h.confirms.size());
    }

    // ------------------------------------------------------------------ B39 跳转确认框

    @Test public void jumpDialogDetect() {
        JumpConfirm.Hit h = JumpConfirm.detect(Arrays.asList("“浏览器”想要打开“美团”", "取消", "打开"));
        assertNotNull(h);
        assertEquals("美团", h.target);
        assertEquals(2, h.allowIdx);
        assertEquals(1, h.cancelIdx);
        h = JumpConfirm.detect(Arrays.asList("是否允许打开京东？", "拒绝", "允许"));
        assertNotNull(h);
        assertEquals("京东", h.target);
        assertNull("没有「打开 / 允许」按钮不算", JumpConfirm.detect(Arrays.asList("是否打开美团", "好评")));
        assertNull("普通页面不算", JumpConfirm.detect(Arrays.asList("打开", "设置", "显示")));
        assertTrue(JumpConfirm.isUserTarget("美团", "打开美团点个外卖", null));
        assertFalse(JumpConfirm.isUserTarget("京东", "打开美团点个外卖", null));
        assertFalse("认不出名字不算用户点名", JumpConfirm.isUserTarget("", "打开美团", null));
    }

    static FakeScreens.Phone jumpPhone(String target) {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("dlg", FakeScreens.screen("android", "ResolverActivity").text("是否打开“" + target + "”？").button("取消").button("打开"));
        ph.add("app", FakeScreens.screen("com.x." + target.hashCode(), "Main").button("首页"));
        ph.add("back", FakeScreens.screen("com.android.launcher", "Home").button("设置"));
        ph.on("dlg", "打开", "app");
        ph.on("dlg", "取消", "back");
        ph.taps.put("*|launch:com.sankuai.meituan", "dlg");
        return ph;
    }

    @Test public void jumpToUserTargetIsTappedWithoutAsking() {
        FakeScreens.Phone ph = jumpPhone("美团");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"好了\"}"), h);
        PhoneAgent.Result r = a.run("打开美团看看拼好饭", "com.sankuai.meituan");
        assertEquals(r.trace.toString(), PhoneAgent.Status.DONE, r.status);
        assertTrue(ph.log.toString(), ph.log.contains("tap 打开"));
        assertEquals(0, h.confirms.size());
        assertEquals(1, a.jumpHandled);
    }

    @Test public void jumpToOtherAppAsksFirst() {
        FakeScreens.Phone ph = jumpPhone("京东");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        PhoneAgent.Result r = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"好了\"}"), h).run("打开美团看看拼好饭", "com.sankuai.meituan");
        assertEquals(1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("京东"));
        assertTrue(ph.log.toString(), ph.log.contains("tap 取消"));
        assertFalse(ph.log.contains("tap 打开"));
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
    }

    @Test public void unreadableForeignAfterLaunch() {
        UiTree u = new UiTree();
        u.pkg = "com.android.systemui";
        assertTrue(PhoneAgent.unreadableForeign(u, "com.sankuai.meituan"));
        u.pkg = "com.sankuai.meituan";
        assertFalse(PhoneAgent.unreadableForeign(u, "com.sankuai.meituan"));
        assertFalse(PhoneAgent.unreadableForeign(null, "com.sankuai.meituan"));
        assertTrue(JumpConfirm.MANUAL.contains("手动点一下"));
    }

    // ------------------------------------------------------------------ B40 先看现状

    @Test public void stateFirstNoteAndSkipLaunch() {
        assertTrue(StateFirst.skipLaunch("com.sankuai.meituan", "com.sankuai.meituan"));
        assertFalse(StateFirst.skipLaunch("com.android.launcher", "com.sankuai.meituan"));
        assertFalse(StateFirst.skipLaunch(null, "com.sankuai.meituan"));
        UiTree u = new UiTree();
        u.pkg = "com.sankuai.meituan";
        u.activity = "com.meituan.android.pin.PinHaoFanActivity";
        String n = StateFirst.note(u, true);
        assertTrue(n, n.contains("com.sankuai.meituan") && n.contains("PinHaoFanActivity") && n.contains("没有重新打开")
                && n.contains("跳过") && n.contains("接着做") && n.contains("已经完成"));
        assertEquals("", StateFirst.note(null, false));
    }

    @Test public void stateNoteReachesModelEveryTurn() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("pin", FakeScreens.screen("com.sankuai.meituan", "PinActivity").button("拼好饭").button("下单"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:拼好饭}}]}", "{\"done\":\"好了\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("在美团拼好饭里看看", null);
        assertTrue(pl.users.size() >= 2);
        for (String u : pl.users) assertTrue(u, u.contains("开局现状：前台是 com.sankuai.meituan") && u.contains("从当前页面接着做"));
    }

    @Test public void driverSkipsRelaunchWhenForeground() throws Exception {
        String d = src("phone/FastScreenDriver.java");
        int k = d.indexOf("@Override public boolean launch(String pkg)");
        assertTrue(d.substring(k, k + 500).contains("StateFirst.skipLaunch(fg, pkg)"));
    }

    // ------------------------------------------------------------------ B7 读屏 partial 退回无障碍

    @Test public void observeFallbackRules() {
        assertTrue(ObserveFallback.suspect(UiTree.Q_PARTIAL, "com.android.settings", "com.onesay.assistant.debug"));
        assertTrue(ObserveFallback.suspect(UiTree.Q_OK, "", "com.onesay.assistant.debug"));
        assertTrue("读到的是自家悬浮窗", ObserveFallback.suspect(UiTree.Q_OK, "com.onesay.assistant.debug", "com.onesay.assistant.debug"));
        assertFalse(ObserveFallback.suspect(UiTree.Q_OK, "com.android.settings", "com.onesay.assistant.debug"));
        String self = "com.onesay.assistant.debug";
        assertTrue(ObserveFallback.preferA11y(UiTree.Q_PARTIAL, "com.android.settings", 3, UiTree.Q_OK, "com.android.settings", 28, self));
        assertTrue(ObserveFallback.preferA11y(UiTree.Q_OK, "", 3, UiTree.Q_OK, "com.android.settings", 20, self));
        assertFalse("无障碍更差不换", ObserveFallback.preferA11y(UiTree.Q_OK, "com.android.settings", 20, UiTree.Q_PARTIAL, "com.android.settings", 30, self));
        assertFalse(ObserveFallback.preferA11y(UiTree.Q_PARTIAL, "com.android.settings", 3, null, "", 0, self));
        assertFalse("无障碍读到的是自己，不换", ObserveFallback.preferA11y(UiTree.Q_PARTIAL, "com.android.settings", 3, UiTree.Q_OK, self, 10, self));
    }

    // ------------------------------------------------------------------ B29 读屏工具

    @Test public void screenReadToolInLoop() throws Exception {
        assertTrue(ScreenReadTool.isMine("screen__read_now"));
        assertEquals("screen.read_now", ScreenReadTool.cap().sign);
        assertEquals(com.onesay.assistant.v2.capability.SideEffect.PRIVATE_DATA_READ, ScreenReadTool.cap().sideEffect);
        UiTree u = FakeScreens.screen("com.android.settings", "DisplaySettings").text("亮度").button("深色模式").ui(new UiTree.Ids());
        String t = ScreenReadTool.format(u, 40);
        assertTrue(t, t.contains("com.android.settings") && t.contains("深色模式") && t.contains("亮度"));
        String p = src("kernel/plugins/PhonePlugin.java");
        assertTrue(p.contains("ScreenReadTool.cap()") && p.contains("ScreenReadTool.run(ctx, c, args)"));
    }

    // ------------------------------------------------------------------ B32 计时不走 am

    @Test public void timerViaOwnIntent() throws Exception {
        assertTrue(com.onesay.assistant.v2.executor.backend.TimerIntentBackend.accepts("clock.timer.set", true));
        assertFalse("没有 Context（单测）照旧", com.onesay.assistant.v2.executor.backend.TimerIntentBackend.accepts("clock.timer.set", false));
        assertFalse(com.onesay.assistant.v2.executor.backend.TimerIntentBackend.accepts("clock.alarm.set", true));
        assertEquals("1 分钟", com.onesay.assistant.v2.executor.backend.TimerIntentBackend.human(60));
        assertEquals("1 分 30 秒", com.onesay.assistant.v2.executor.backend.TimerIntentBackend.human(90));
        assertEquals("45 秒", com.onesay.assistant.v2.executor.backend.TimerIntentBackend.human(45));
        String u = src("executor/UnifiedExecutor.java");
        assertTrue("排在 Intent / Shell 兜底之前", u.indexOf("new com.onesay.assistant.v2.executor.backend.TimerIntentBackend()") < u.indexOf("new ShellBackend()"));
        String b = src("executor/backend/TimerIntentBackend.java");
        assertTrue(b.contains("ACTION_SET_TIMER") && !b.contains("\"am "));
    }

    // ------------------------------------------------------------------ B19 远程不能动 OneSay 自己（含 .debug）

    @Test public void selfPackageAnyVariant() {
        assertTrue(LinkPolicy.isSelfPackage("com.onesay.assistant"));
        assertTrue(LinkPolicy.isSelfPackage("com.onesay.assistant.debug"));
        assertTrue(LinkPolicy.isSelfPackage("COM.ONESAY.assistant.preview"));
        assertFalse(LinkPolicy.isSelfPackage("com.onesayx.app"));
        assertFalse(LinkPolicy.isSelfPackage("com.onesay.me"));
        assertFalse(LinkPolicy.isSelfPackage("com.onesay.assistantx"));
        assertFalse(LinkPolicy.isSelfPackage("com.android.settings"));
        String[] bad = {"cmd package uninstall com.onesay.assistant", "pm uninstall --user 0 com.onesay.assistant.debug",
                "pm disable-user com.onesay.assistant.debug", "pm clear com.onesay.assistant", "am force-stop com.onesay.assistant.debug",
                "cmd 'package' uninstall com.onesay.assistant", "p''m uninstall com.onesay.assistant.debug"};
        for (String c : bad) {
            assertEquals(c, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
        }
        assertFalse(LinkPolicy.touchesSelfApp("pm uninstall com.example.game"));
        assertFalse("只是查看自己不算", LinkPolicy.touchesSelfApp("dumpsys package com.onesay.assistant"));
        assertEquals(LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, "pm uninstall com.example.game"));
    }

    @Test public void appFactsUseSelfRule() throws Exception {
        assertTrue(src("pclink/PcRxAndroid.java").contains("LinkPolicy.isSelfPackage(pkg)"));
    }

    // ------------------------------------------------------------------ B38 灵动岛执行中可点

    @Test public void islandBelowStatusBarWhileExec() {
        assertTrue(IslandTouchDodge.needBelowStatusBar(31, true, false));
        assertTrue(IslandTouchDodge.needBelowStatusBar(35, true, false));
        assertFalse("不执行不挪", IslandTouchDodge.needBelowStatusBar(35, false, false));
        assertFalse("Android 8 不挪", IslandTouchDodge.needBelowStatusBar(26, true, false));
        assertFalse("全屏不挪", IslandTouchDodge.needBelowStatusBar(35, true, true));
        assertEquals(96, IslandTouchDodge.windowTop(10, 96, true));
        assertEquals(120, IslandTouchDodge.windowTop(120, 96, true));
        assertEquals(10, IslandTouchDodge.windowTop(10, 96, false));
        assertEquals(10, IslandTouchDodge.windowTop(10, 0, true));
        assertTrue(IslandTouchDodge.overlaps(new int[]{500, 100, 564, 164}, new int[]{400, 96, 680, 180}));
        assertFalse(IslandTouchDodge.overlaps(new int[]{500, 900, 564, 964}, new int[]{400, 96, 680, 180}));
        assertFalse(IslandTouchDodge.overlaps(new int[]{500, 100, 564, 164}, new int[]{0, 0, 0, 0}));
    }

    @Test public void islandWired() throws Exception {
        String c = src("s1/IslandController.java");
        assertTrue(c.contains("IslandTouchDodge.windowTop(") && c.contains("mExecActive = true") && c.contains("public void yieldTouch("));
        String sh = src("exec/ShieldOverlay.java");
        assertTrue("代操作点到岛的位置：岛先让开", sh.contains("isl.yieldTouch(true)") && sh.contains("isl.yieldTouch(false)"));
    }

    // ------------------------------------------------------------------ B10 「删掉刚才那条」走本地记忆

    @Test public void memoryDeleteNotDeixis() throws Exception {
        com.onesay.assistant.v2.s2.S2Engine.Flags all = new com.onesay.assistant.v2.s2.S2Engine.Flags(true, false, true);
        for (String q : new String[]{"删掉刚才那条记忆", "删掉刚才那条", "把刚才那条删了"}) {
            assertTrue(q, com.onesay.assistant.v2.s2.S2Engine.bypassScreenRef(q, all));
        }
        assertFalse(com.onesay.assistant.v2.s2.S2Engine.bypassScreenRef("复制这个", all));
        String h = src("vision/screenref/ScreenRefHooks.java");
        int k = h.indexOf("if (!DeixisResolver.hasDeixis(command)) return false;");
        int m = h.indexOf("S2Engine.bypassScreenRef(command", k);
        int take = h.indexOf("[DEIXIS] 指代命中，接管", k);
        assertTrue("识屏接管前先放行记忆管理句式", k > 0 && m > k && m < take);
    }

    @Test public void memoryDeleteHandledLocally() {
        com.onesay.assistant.v2.s2.InMemoryMemoryStore st = new com.onesay.assistant.v2.s2.InMemoryMemoryStore();
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        com.onesay.assistant.v2.s2.S2Engine en = new com.onesay.assistant.v2.s2.S2Engine(st, st, tz);
        com.onesay.assistant.v2.s2.S2Engine.Flags f = new com.onesay.assistant.v2.s2.S2Engine.Flags(true, false, false);
        long now = 1795000000000L;
        com.onesay.assistant.v2.s2.S2Engine.Result w = en.handle("记一下车停在 B2-17", now, f);
        assertNotNull(w);
        en.execute(w.sign, w.args, now);
        for (String q : new String[]{"删掉刚才那条记忆", "删掉刚才那条"}) {
            com.onesay.assistant.v2.s2.S2Engine.Result r = en.handle(q, now + 1000, f);
            assertNotNull(q, r);
            assertEquals(q, com.onesay.assistant.v2.s2.S2Engine.Type.NOTE_DELETE, r.type);
        }
    }
}
