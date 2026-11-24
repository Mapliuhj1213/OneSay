package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [exec-ground-1124] 魅族 21 Pro 真机日志（只开 Shizuku）：读屏几乎全瞎 → 截图定位；开局不在起点先回首页；
 * 已在前台的 launch 直接成功；SHOT 连发上限；打开 App 时的系统弹窗；坐标点照 target 过敏感闸。
 */
public class Ground1124Test {

    static final File ROOT = new File(System.getProperty("bixby.root", "."));

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, rel).toPath()), "UTF-8");
    }

    @After public void tearDown() {
        PhoneAgent.GROUND = true;
        PhoneAgent.RISK_GATE = true;
        PhoneAgent.JUMP_GATE = true;
    }

    // ------------------------------------------------------------------ 假手机：读不到树、能截图、能按坐标点

    /** 读不到树的一屏（新版微信那种：只有空壳根）。 */
    static FakeScreens.B blind(String pkg, String act) {
        FakeScreens.B b = FakeScreens.screen(pkg, act);
        FsTree.Node r = b.t.nodes.get(0);
        r.cls = "";
        r.l = r.t = r.r = r.b = 0;
        return b;
    }

    static final class GroundPhone implements PhoneAgent.GroundDriver, PhoneAgent.MikaDriver, PhoneAgent.LaunchInfo {
        final FakeScreens.Phone p = new FakeScreens.Phone();
        /** 屏名 → 坐标点后去哪（任意位置都算点中）。 */
        final Map<String, String> xyTo = new HashMap<String, String>();
        final List<String> marks = new ArrayList<String>();
        int shots;
        /** 已在前台的包（launch 跳过）。 */
        String fgPkg;
        boolean skipped;

        GroundPhone add(String name, FakeScreens.B b) {
            p.add(name, b);
            return this;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            return p.observe(ids);
        }

        @Override public boolean tap(UiTree.El e) {
            return p.tap(e);
        }

        @Override public boolean longPress(UiTree.El e, int ms) {
            return p.longPress(e, ms);
        }

        @Override public String type(UiTree.El e, String text) {
            return p.type(e, text);
        }

        @Override public boolean scroll(UiTree.El e, int dir) {
            return p.scroll(e, dir);
        }

        @Override public boolean swipe(int a, int b, int c, int d, int ms) {
            return p.swipe(a, b, c, d, ms);
        }

        @Override public boolean key(String k) {
            return p.key(k);
        }

        @Override public boolean launch(String pkg) {
            skipped = fgPkg != null && fgPkg.equals(p.screens.get(p.cur).t.pkg) && fgPkg.equals(pkg);
            if (skipped) {
                p.log.add("launch-skip " + pkg);
                return true;
            }
            return p.launch(pkg);
        }

        @Override public void waitIdle(int i, int t) {
            p.waitIdle(i, t);
        }

        @Override public String describeScreen() {
            return "一段文字描述";
        }

        @Override public long now() {
            return p.now();
        }

        @Override public void sleep(long ms) {
            p.sleep(ms);
        }

        @Override public boolean tapXY(int x, int y) {
            p.log.add("tapxy " + x + "," + y);
            String to = xyTo.get(p.cur);
            if (to != null) p.cur = to;
            return true;
        }

        @Override public UiTree awaitChange(UiTree before, UiTree.Ids ids) {
            return p.observe(ids);
        }

        @Override public byte[] groundShot(int x, int y) {
            shots++;
            marks.add(x + "," + y);
            return new byte[]{1, 2, 3};
        }

        @Override public String frameSig() {   // 每个屏一张不同的画面
            StringBuilder b = new StringBuilder();
            int h = Math.abs(p.cur.hashCode());
            for (int i = 0; i < 135; i++) b.append(Character.forDigit(((h >> (i % 24)) + i * (h % 7 + 1)) & 15, 16));
            return b.toString();
        }

        @Override public boolean lastLaunchSkipped() {
            return skipped;
        }

        @Override public String lastLaunchNote() {
            return null;
        }
    }

    /** 能看图的脚本 planner：记下每轮有没有带图。 */
    static final class VScript implements PhoneAgent.VisionPlanner {
        final FakeScreens.Script s;
        final List<Boolean> withImage = new ArrayList<Boolean>();

        VScript(String... outs) {
            s = new FakeScreens.Script(outs);
        }

        @Override public String next(String system, String user) {
            withImage.add(false);
            return s.next(system, user);
        }

        @Override public String nextWithImage(String system, String user, byte[] jpeg) {
            withImage.add(jpeg != null);
            return s.next(system, user);
        }
    }

    // ------------------------------------------------------------------ 第 1 / 2 条：读不到树 → 截图定位 + 坐标点

    @Test public void blindTreeSendsScreenshotAndTapsByXy() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));
        ph.add("orders", blind("com.example.shop", "Orders"));
        ph.xyTo.put("home", "orders");
        VScript pl = new VScript(
                "{\"steps\":[{\"do\":\"tap\",\"x\":880,\"y\":950,\"target\":\"底部「订单」\"}],\"note\":\"在哪：首页；差什么：进订单\"}",
                "{\"done\":\"到订单页了\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = new PhoneAgent(ph, pl, h);
        PhoneAgent.Result r = a.run("打开订单页", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertTrue(ph.p.log.toString(), ph.p.log.contains("tapxy 880,950"));
        assertEquals("每一轮都带截图（不是文字描述）", Boolean.TRUE, pl.withImage.get(0));
        assertEquals(Boolean.TRUE, pl.withImage.get(1));
        assertTrue(pl.s.systems.get(0).contains("【截图模式】"));
        assertTrue(pl.s.users.get(0), pl.s.users.get(0).contains("附了第 1 张截图"));
        assertTrue("开局先定位", pl.s.users.get(0).contains("开局先判断"));
        assertTrue("第二张截图上画出上一下点的位置", pl.s.users.get(1).contains("红圈 = 上一下点的位置 (880,950)"));
        assertEquals("880,950", ph.marks.get(1));
        assertTrue(pl.s.users.get(1), pl.s.users.get(1).contains("画面变了"));
        assertEquals(2, a.groundShots);
    }

    @Test public void treeOkStaysOnTree() {
        GroundPhone ph = new GroundPhone();
        ph.add("s", FakeScreens.screen("com.android.settings", "S").button("蓝牙").button("WLAN").button("显示").button("声音"));
        VScript pl = new VScript("{\"done\":\"好\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, new FakeScreens.Host());
        a.run("x", null);
        assertEquals("树够用就不截图", Boolean.FALSE, pl.withImage.get(0));
        assertFalse(pl.s.systems.get(0).contains("【截图模式】"));
        assertEquals(0, ph.shots);
    }

    @Test public void blindWaitUsesFrameNotTextTimeout() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));
        ph.add("orders", blind("com.example.shop", "Orders"));
        ph.xyTo.put("home", "orders");
        VScript pl = new VScript(
                "{\"steps\":[{\"do\":\"tap\",\"x\":880,\"y\":950,\"target\":\"订单\",\"expect\":\"订单\",\"timeout_s\":8}]}",
                "{\"done\":\"好\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        String u = pl.s.users.get(1);
        assertFalse("读不到字不能硬等文字", u.contains("没等到「订单」出现"));
        assertTrue(u, u.contains("按画面判断：画面变了"));
        assertFalse(u, u.contains("\"code\":\"TIMEOUT\""));
    }

    @Test public void blindNoChangeHandsBackInsteadOfBlindTapping() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));   // 点哪都不变
        VScript pl = new VScript(
                "{\"steps\":[{\"do\":\"tap\",\"x\":100,\"y\":100,\"target\":\"搜索\"},{\"do\":\"tap\",\"x\":200,\"y\":200,\"target\":\"第一个\"}]}",
                "{\"done\":\"好\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertTrue(ph.p.log.contains("tapxy 100,100"));
        assertFalse("画面没变就别接着盲点", ph.p.log.contains("tapxy 200,200"));
        assertTrue(pl.s.users.get(1), pl.s.users.get(1).contains("画面没变，可能没点中"));
    }

    @Test public void blindScreensDoNotLookStuckWhenFramesChange() {
        GroundPhone ph = new GroundPhone();
        for (int i = 0; i < 6; i++) {
            ph.add("p" + i, blind("com.example.shop", "Main"));
            ph.xyTo.put("p" + i, "p" + (i + 1));
        }
        ph.add("p6", blind("com.example.shop", "Main"));
        String step = "{\"steps\":[{\"do\":\"tap\",\"x\":500,\"y\":500,\"target\":\"下一个\"}]}";
        FakeScreens.Host h = new FakeScreens.Host();
        new PhoneAgent(ph, new VScript(step, step, step, step, step, "{\"done\":\"好\"}"), h).run("x", null);
        for (String w : h.resumes) assertFalse("画面一直在变不算卡住：" + w, w.startsWith("好像卡住了"));
    }

    // ------------------------------------------------------------------ 初始态不在起点：先回首页

    @Test public void resetAppBacksOutToHomeThenRelaunches() {
        GroundPhone ph = new GroundPhone();
        ph.add("detail", blind("com.example.shop", "Detail"));
        ph.add("list", blind("com.example.shop", "List"));
        ph.add("launcher", FakeScreens.screen("com.android.launcher", "L").button("商城").button("设置").button("相机").button("时钟"));
        ph.add("home", blind("com.example.shop", "Main"));
        ph.p.on("detail", "<back>", "list").on("list", "<back>", "launcher").on("*", "launch:com.example.shop", "home");
        ph.p.taps.put("*|launch:com.example.shop", "home");
        ph.p.cur = "detail";
        VScript pl = new VScript("{\"steps\":[{\"do\":\"reset_app\",\"app\":\"com.example.shop\"}],\"note\":\"在哪：认不出；差什么：先回首页\"}",
                "{\"done\":\"回到首页了\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, new FakeScreens.Host());
        PhoneAgent.Result r = a.run("在商城里找订单", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(1, a.resets);
        assertEquals("home", ph.p.cur);
        assertTrue(ph.p.log.toString(), ph.p.log.contains("key back") && ph.p.log.contains("launch com.example.shop"));
        assertTrue(pl.s.users.get(1), pl.s.users.get(1).contains("已退回首页"));
    }

    @Test public void resetAppLimitedToTwice() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));
        String reset = "{\"steps\":[{\"do\":\"reset_app\"}]}";
        VScript pl = new VScript(reset, reset, reset, "{\"done\":\"好\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, new FakeScreens.Host());
        a.run("x", "com.example.shop");
        assertEquals(ScreenGround.MAX_RESETS, a.resets);
        assertTrue(pl.s.users.get(3), pl.s.users.get(3).contains("不再退"));
    }

    @Test public void startNoteOffersResetEvenWhenTreeIsFine() {
        FakeScreens.Script pl = new FakeScreens.Script("{\"done\":\"好\"}");
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("s", FakeScreens.screen("com.android.settings", "S").button("蓝牙").button("WLAN").button("显示").button("声音"));
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertTrue(pl.users.get(0).contains("reset_app"));
    }

    // ------------------------------------------------------------------ 第 3 条：已在前台的 launch 直接成功

    @Test public void launchOfForegroundAppSucceedsImmediately() {
        GroundPhone ph = new GroundPhone();
        ph.add("bill", blind("com.example.chat", "Bill"));
        ph.fgPkg = "com.example.chat";
        VScript pl = new VScript("{\"steps\":[{\"do\":\"launch\",\"app\":\"com.example.chat\",\"expect_app\":\"com.example.chat\"}]}", "{\"done\":\"好\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        String u = pl.s.users.get(1);
        assertTrue(ph.p.log.contains("launch-skip com.example.chat"));
        assertFalse(u, u.contains("没等到切到"));
        assertFalse(u, u.contains("\"code\":\"TIMEOUT\""));
        assertTrue(u, u.contains("本来就在前台") && u.contains("\"code\":\"OK\""));
    }

    @Test public void launchExpectAppAlreadyMetEvenWithReadableTree() {
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "launch";
        s.expectApp = "com.x";
        UiTree before = FakeScreens.screen("com.x", "A").button("一").ui(new UiTree.Ids());
        assertTrue(PhoneAgent.condMet(s, before, before, false, false));
    }

    // ------------------------------------------------------------------ 第 4 条：SHOT 连发

    @Test public void shotStreakIsCappedAndStops() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));
        String shot = "{\"shot\":true}";
        VScript pl = new VScript(shot, shot, shot, shot, shot, shot, shot);
        PhoneAgent.Result r = new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertEquals(ScreenGround.SHOT_STOP, r.reason);
        assertEquals("第 4 次就停，不会 7 连", 4, pl.withImage.size());
        assertTrue(pl.s.users.get(3), pl.s.users.get(3).contains("别再要截图"));
        for (Boolean b : pl.withImage) assertTrue("每轮附的都是截图本身", b);
    }

    @Test public void shotThenActResetsStreak() {
        GroundPhone ph = new GroundPhone();
        ph.add("home", blind("com.example.shop", "Main"));
        ph.add("next", blind("com.example.shop", "Next"));
        ph.xyTo.put("home", "next");
        String shot = "{\"shot\":true}", tap = "{\"steps\":[{\"do\":\"tap\",\"x\":5,\"y\":5,\"target\":\"返回\"}]}";
        VScript pl = new VScript(shot, shot, tap, shot, shot, "{\"done\":\"好\"}");
        PhoneAgent.Result r = new PhoneAgent(ph, pl, new FakeScreens.Host()).run("x", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
    }

    // ------------------------------------------------------------------ 第 5 条：打开 App 时的系统弹窗

    @Test public void chooserAtLaunchPicksTargetJustOnce() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.launcher", "L").button("一").button("二").button("三").button("四"));
        ph.add("chooser", FakeScreens.screen("android", "ResolverActivity").text("打开方式").button("商城").button("浏览器").button("仅此一次").button("始终"));
        ph.add("app", FakeScreens.screen("com.example.shop", "Main").button("首页").button("订单").button("我的").button("搜索"));
        ph.taps.put("*|launch:com.example.shop", "chooser");
        ph.on("chooser", "商城", "app");
        PhoneAgent a = new PhoneAgent(ph, new FakeScreens.Script("{\"done\":\"好\"}"), new FakeScreens.Host());
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("com.example.shop", "商城");
        a.appLabels = labels;
        PhoneAgent.Result r = a.run("打开商城", "com.example.shop");
        assertTrue(r.trace.toString(), r.trace.toString().contains("launch-dialog chooser"));
        assertTrue(ph.log.contains("tap 商城"));
        assertFalse("不点「始终」", ph.log.contains("tap 始终"));
        assertEquals("app", ph.cur);
    }

    @Test public void unknownSystemDialogAtLaunchAsksUserAndDoesNotTap() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.launcher", "L").button("一").button("二").button("三").button("四"));
        ph.add("dlg", FakeScreens.screen("android", "Alert").text("安全提醒").button("继续").button("取消"));
        ph.taps.put("*|launch:com.example.shop", "dlg");
        FakeScreens.Host h = new FakeScreens.Host();
        FakeScreens.Script pl = new FakeScreens.Script("{\"done\":\"好\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, h);
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("com.example.shop", "商城");
        a.appLabels = labels;
        a.run("打开商城", "com.example.shop");
        assertEquals(1, h.resumes.size());
        assertTrue(h.resumes.get(0), h.resumes.get(0).contains("系统弹出了一个提示") && h.resumes.get(0).contains("安全提醒") && h.resumes.get(0).contains("「商城」"));
        assertFalse("包名不给用户看", h.resumes.get(0).contains("android"));
        assertFalse("没替用户点", ph.log.contains("tap 继续") || ph.log.contains("tap 取消"));
    }

    @Test public void unknownDialogStopEndsTask() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("home", FakeScreens.screen("com.android.launcher", "L").button("一").button("二").button("三").button("四"));
        ph.add("dlg", FakeScreens.screen("android", "Alert").text("安全提醒").button("继续"));
        ph.taps.put("*|launch:com.example.shop", "dlg");
        FakeScreens.Host h = new FakeScreens.Host();
        h.resumeWith = PhoneAgent.Resume.STOP;
        FakeScreens.Script pl = new FakeScreens.Script("{\"done\":\"好\"}");
        PhoneAgent.Result r = new PhoneAgent(ph, pl, h).run("打开商城", "com.example.shop");
        assertEquals(PhoneAgent.Status.STOPPED, r.status);
        assertEquals("没问模型", 0, pl.users.size());
    }

    @Test public void launchDialogClassify() {
        assertTrue(LaunchDialog.systemPkg("android"));
        assertTrue(LaunchDialog.systemPkg("com.android.permissioncontroller"));
        assertFalse(LaunchDialog.systemPkg("com.tencent.mm"));
        assertFalse(LaunchDialog.systemPkg("com.android.launcher"));
        UiTree mine = FakeScreens.screen("com.example.shop", "M").button("x").ui(new UiTree.Ids());
        assertEquals(LaunchDialog.Kind.NONE, LaunchDialog.classify(mine, "com.example.shop", "商城").kind);
    }

    // ------------------------------------------------------------------ 坐标点照 target 过敏感闸（污染不跨任务，但敏感动作仍确认）

    @Test public void xySendOnBlindScreenStillConfirms() {
        GroundPhone ph = new GroundPhone();
        ph.add("chat", blind("com.example.chat", "Chat"));
        ph.add("sent", blind("com.example.chat", "Chat2"));
        ph.xyTo.put("chat", "sent");
        FakeScreens.Host h = new FakeScreens.Host();
        h.confirmAnswer = false;
        VScript pl = new VScript("{\"steps\":[{\"do\":\"tap\",\"x\":930,\"y\":960,\"target\":\"右下角的发送按钮\"}]}", "{\"done\":\"没发\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, h);
        assertFalse(a.tainted);
        a.run("发一句你好", null);
        assertEquals(1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("发送"));
        assertFalse("用户拒绝就不点", ph.p.log.contains("tapxy 930,960"));
    }

    @Test public void xyFinalPayOnBlindScreenMustBeUserTap() {
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        s.x = 500;
        s.y = 900;
        s.target = "底部「确认支付」";
        UiTree empty = blind("com.example.shop", "Pay").ui(new UiTree.Ids());
        assertNotNull(SensitiveGuard.userMustTap(s, empty));
        assertNotNull(SensitiveGuard.check(s, empty));
        s.target = "左上角返回";
        assertNull(SensitiveGuard.check(s, empty));
    }

    // ------------------------------------------------------------------ 纯函数

    @Test public void parseXyWithoutMika() {
        PhonePlan p = PhonePlan.parse("{\"steps\":[{\"do\":\"tap\",\"x\":12,\"y\":1200,\"target\":\"我的\"},{\"do\":\"tap\",\"xy\":[3,4],\"target\":\"a\"},{\"do\":\"reset\"}]}", false, true);
        assertEquals(PhonePlan.Kind.STEPS, p.kind);
        assertEquals(12, p.steps.get(0).x);
        assertEquals(999, p.steps.get(0).y);
        assertEquals("我的", p.steps.get(0).target);
        assertEquals(3, p.steps.get(1).x);
        assertEquals("reset_app", p.steps.get(2).op);
        PhonePlan old = PhonePlan.parse("{\"steps\":[{\"do\":\"tap\",\"x\":12,\"y\":20,\"target\":\"我的\"}]}");
        assertEquals("不开截图模式时和改前一样不收坐标", -1, old.steps.get(0).x);
    }

    @Test public void treeBlindAndWithheld() {
        UiTree w = blind("com.tencent.mm", "L").ui(new UiTree.Ids());
        assertTrue(ScreenGround.treeBlind(w));
        assertTrue(ScreenGround.withheld(w.tree));
        assertTrue(ScreenGround.blindWhy(w).contains("不把界面内容交给读屏"));
        assertTrue(ScreenGround.treeBlind(null));
        UiTree ok = FakeScreens.screen("p", "a").button("一").button("二").ui(new UiTree.Ids());
        assertFalse(ScreenGround.treeBlind(ok));
        assertFalse(ScreenGround.withheld(ok.tree));
    }

    @Test public void frameFingerprint() {
        int[] a = new int[ScreenGround.GRID_COLS * ScreenGround.GRID_ROWS];
        int[] b = a.clone();
        for (int i = 0; i < a.length; i++) a[i] = 200;
        for (int i = 0; i < b.length; i++) b[i] = 200;
        String fa = ScreenGround.frameSig(a, ScreenGround.GRID_COLS, ScreenGround.GRID_ROWS);
        assertEquals((ScreenGround.GRID_ROWS - 1) * ScreenGround.GRID_COLS, fa.length());
        b[ScreenGround.GRID_COLS * 5] = 0;   // 一格变：光标闪一下不算变
        assertFalse(ScreenGround.frameChanged(fa, ScreenGround.frameSig(b, ScreenGround.GRID_COLS, ScreenGround.GRID_ROWS)));
        for (int i = ScreenGround.GRID_COLS * 4; i < ScreenGround.GRID_COLS * 8; i++) b[i] = 20;
        assertTrue(ScreenGround.frameChanged(fa, ScreenGround.frameSig(b, ScreenGround.GRID_COLS, ScreenGround.GRID_ROWS)));
        int[] c = a.clone();
        for (int i = 0; i < ScreenGround.GRID_COLS; i++) c[i] = 0;   // 只有状态栏那一行变（时间、电量）
        assertEquals(fa, ScreenGround.frameSig(c, ScreenGround.GRID_COLS, ScreenGround.GRID_ROWS));
        assertFalse(ScreenGround.frameChanged(null, fa));
        assertTrue(ScreenGround.frameSame(fa, fa));
    }

    @Test public void imageMessagesCarryJpeg() throws Exception {
        byte[] img = new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3, 4, 5};
        org.json.JSONArray m = LlmPhonePlanner.imageMessages("SYS", "看图", img);
        assertEquals("system", m.getJSONObject(0).getString("role"));
        org.json.JSONArray parts = m.getJSONObject(1).getJSONArray("content");
        assertEquals("看图", parts.getJSONObject(0).getString("text"));
        String url = parts.getJSONObject(1).getJSONObject("image_url").getString("url");
        assertEquals("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(img), url);
        for (int n = 0; n < 7; n++) {
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = (byte) (i * 37 + 5);
            assertEquals(java.util.Base64.getEncoder().encodeToString(d), LlmPhonePlanner.b64(d));
        }
        org.json.JSONObject req = new org.json.JSONObject().put("messages", m);
        assertTrue("带图请求会被认出来（换能看图的模型）", com.onesay.assistant.v2.vision.VisionRoute.hasImage(req));
    }

    @Test public void groundPromptsHaveNoBrandOrCodes() {
        String all = ScreenGround.addendum() + ScreenGround.startNote(true) + ScreenGround.shotRefused(true) + ScreenGround.SHOT_STOP
                + LaunchDialog.askText("商城", "安全提醒");
        for (String bad : new String[]{"Bixby", "三星", "DeX", "小布", "Mika", "deepseek", "DeepSeek", "啥", "咋", "呗"})
            assertFalse(bad, (ScreenGround.SHOT_STOP + ScreenGround.shotRefused(true) + LaunchDialog.askText("商城", "x")).contains(bad));
        assertTrue(all.length() > 0);
    }

    // ------------------------------------------------------------------ 第 7 / 8 条

    @Test public void exportZipNamesAreAscii() {
        assertTrue(com.onesay.assistant.v2.applog.AppLogExport.LOG_ENTRY.matches("[A-Za-z0-9._-]+"));
        assertTrue(com.onesay.assistant.v2.applog.AppLogExport.LOGCAT_ENTRY.matches("[A-Za-z0-9._-]+"));
        assertFalse(com.onesay.assistant.v2.applog.AppLogExport.LOG_ENTRY.equals(com.onesay.assistant.v2.applog.AppLogExport.LOGCAT_ENTRY));
    }

    @Test public void wakeServiceChecksMicBeforeAudioRecord() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int boot = s.indexOf("private void bootEngine()");
        assertTrue(boot > 0);
        int perm = s.indexOf("if (!hasRecordPermission())", boot);
        int rec = s.indexOf("new AudioRecord(", boot);
        assertTrue("没权限先返回，不去建录音器", perm > 0 && perm < rec);
        String body = s.substring(perm, rec);
        assertTrue(body.contains("return;"));
        assertFalse("没权限不再抛异常", body.contains("throw "));
    }

    @Test public void manifestShortcutIsNotSwitchedByApi() throws Exception {
        String xml = src("app/res/xml/onesay_shortcuts.xml");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("android:shortcutId=\"([^\"]+)\"").matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(m.group(1), com.onesay.assistant.v2.entry.Entries.manifestShortcut(m.group(1)));
        }
        assertTrue(n >= 1);
        assertFalse(com.onesay.assistant.v2.entry.Entries.manifestShortcut("dynamic_x"));
    }

    @Test public void panelHideLogDoesNotBlameUser() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertFalse(s.contains("用户主动触发取消/关闭 -> 立即强制销毁"));
        assertTrue(s.contains("执行模式让位：自动收起面板"));
    }
}
