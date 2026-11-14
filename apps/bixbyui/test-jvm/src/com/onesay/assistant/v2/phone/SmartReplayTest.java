package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.fastscreen.FsTree;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * ★ smart-replay：成功路径回放（{@link SmartReplay} + 接进 {@link PhoneAgent} 之后的行为）。
 * 假界面树（{@link FakeScreens}）+ 虚拟时钟（假手机的 clock / 存储注入的 Now）+ 脚本化 planner。
 */
public class SmartReplayTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final int TV = FsTree.F_ENABLED | FsTree.F_VISIBLE;
    static final int SW = FakeScreens.C | FsTree.F_CHECKABLE;

    // ------------------------------------------------------------------ 假界面

    /** 首页：标题、时钟、角标（易变内容）+ 「设置」按钮。 */
    static FakeScreens.B home(String clock, int badge) {
        return FakeScreens.screen("com.demo", "x.Main")
                .node("android.widget.TextView", "首页", "toolbar_title", TV)
                .node("android.widget.TextView", clock, "clock", TV)
                .button("设置", "btn_settings")
                .button("消息 " + badge, "btn_msg")
                .node("android.widget.TextView", "推荐内容 " + clock, "feed_item", TV);
    }

    static FakeScreens.B settings() {
        return FakeScreens.screen("com.demo", "x.Settings")
                .node("android.widget.TextView", "设置", "toolbar_title", TV)
                .node("android.widget.Switch", "深色模式", "dark_mode", SW)
                .node("android.widget.Switch", "自动旋转", "auto_rotate", SW)
                .button("关于", "about");
    }

    /** 改版后的设置页：资源 id 全换了。 */
    static FakeScreens.B settingsRedesigned() {
        return FakeScreens.screen("com.demo", "x.Settings")
                .node("android.widget.TextView", "设置", "v2_header", TV)
                .node("android.widget.Switch", "深色模式", "v2_theme", SW)
                .node("android.widget.Switch", "自动旋转", "v2_rotate", SW)
                .button("关于", "v2_about");
    }

    static FakeScreens.Phone phone(FakeScreens.B home, FakeScreens.B set) {
        FakeScreens.Phone p = new FakeScreens.Phone()
                .add("launcher", FakeScreens.screen("com.launcher", "x.Launcher").text("桌面"))
                .add("home", home)
                .add("set", set);
        p.on("*", "launch:com.demo", "home");
        p.on("home", "设置", "set");
        return p;
    }

    static final String GOAL = "打开设置里的深色模式";
    static final String T1 = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:设置},\"expect\":\"深色模式\"}]}";
    static final String T2 = "{\"steps\":[{\"do\":\"tap\",\"id\":{id:深色模式}}]}";
    static final String DONE = "{\"done\":\"已打开深色模式\"}";

    long now = 1000;

    SmartReplay.Store store(int max) {
        return new SmartReplay.Store(new File(tmp.getRoot(), SmartReplay.FILE), max, new SmartReplay.Now() {
            @Override public long now() {
                return now;
            }
        });
    }

    static PhoneAgent agent(FakeScreens.Phone p, PhoneAgent.Planner s, PhoneAgent.Host h, SmartReplay.Store st) {
        PhoneAgent a = new PhoneAgent(p, s, h);
        a.replay = st;
        return a;
    }

    /** 第一次：模型做成功 → 记下路径。 */
    SmartReplay.Store recorded() {
        SmartReplay.Store st = store(200);
        FakeScreens.Phone p = phone(home("12:30", 3), settings());
        PhoneAgent a = agent(p, new FakeScreens.Script(T1, T2, DONE), new FakeScreens.Host(), st);
        PhoneAgent.Result r = a.run(GOAL, "com.demo");
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(1, a.replayRecorded);
        return st;
    }

    static String key() {
        return SmartReplay.key(GOAL, "com.demo");
    }

    static int count(List<String> log, String s) {
        int c = 0;
        for (String x : log) if (x.equals(s)) c++;
        return c;
    }

    // ------------------------------------------------------------------ 录制

    @Test public void recordsStableFeaturesNotRefNumbersOrCoordinates() throws Exception {
        SmartReplay.Store st = recorded();
        assertEquals(1, st.size());
        SmartReplay.Path p = st.get(key());
        assertNotNull(p);
        assertEquals(2, p.steps.size());
        SmartReplay.RStep s1 = p.steps.get(0), s2 = p.steps.get(1);
        assertEquals("tap", s1.op);
        assertEquals("btn_settings", s1.target.rid);
        assertEquals("设置", s1.target.label);
        assertEquals("Button", s1.target.cls.replace("android.widget.", ""));
        assertEquals("深色模式", s1.expect);
        assertEquals("dark_mode", s2.target.rid);
        assertTrue(s2.target.check);
        assertEquals("com.demo", s1.before.pkg);
        assertEquals("x.Settings", s2.before.act);
        assertFalse(s1.before.tokens.isEmpty());
        assertTrue("相对位置是千分比", s1.target.rx >= 0 && s1.target.rx <= 1000 && s1.target.ry <= 1000);
        String file = SmartReplay.readAll(new File(tmp.getRoot(), SmartReplay.FILE));
        assertFalse("不存编号", file.contains("\"id\":"));
        assertFalse("不存绝对坐标", file.contains("\"cx\"") || file.contains("\"l\":"));
        assertFalse("键是哈希，不存指令原文", file.contains("深色模式里") || file.contains(GOAL));
        assertTrue(file.contains(key()));
    }

    @Test public void keyNormalizesInstruction() {
        assertEquals(SmartReplay.key("打开设置里的深色模式", "com.demo"), SmartReplay.key(" 打开设置里的深色模式。 ", "com.demo"));
        assertEquals(SmartReplay.key("Open Dark Mode!", "p"), SmartReplay.key("open dark mode", "p"));
        assertEquals(SmartReplay.key("打开ＷＩＦＩ", "p"), SmartReplay.key("打开wifi", "p"));
        assertNotEquals(SmartReplay.key(GOAL, "com.demo"), SmartReplay.key(GOAL, "com.other"));
        assertNotEquals(SmartReplay.key(GOAL, "com.demo"), SmartReplay.key("打开设置里的护眼模式", "com.demo"));
    }

    // ------------------------------------------------------------------ 命中

    @Test public void sameInstructionReplaysWithoutAskingTheModelUntilTheEnd() {
        SmartReplay.Store st = recorded();
        FakeScreens.Phone p = phone(home("12:30", 3), settings());
        FakeScreens.Script s = new FakeScreens.Script(DONE);
        PhoneAgent a = agent(p, s, new FakeScreens.Host(), st);
        PhoneAgent.Result r = a.run(GOAL, "com.demo");
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("只在回放完后问一次模型确认结果", 1, r.modelCalls);
        assertTrue(s.users.get(0).contains("【回放】已按上次成功完成这件事的步骤做完 2 步"));
        assertEquals(Arrays.asList("launch com.demo", "tap 设置", "tap 深色模式"), p.log);
        assertEquals(1, a.replayHit);
        assertEquals(0, a.replayFallback);
        assertEquals(2, a.replaySteps);
        assertEquals("回放的任务不再重录", 0, a.replayRecorded);
        assertEquals(1, st.get(key()).hits);
        assertEquals(1, st.totalHits);
    }

    @Test public void replayToleratesClockBadgeAndListContent() {
        SmartReplay.Store st = recorded();
        FakeScreens.B h = home("23:59", 128).node("android.widget.TextView", "又一条推荐", "feed_item", TV)
                .node("android.widget.TextView", "再一条推荐", "feed_item", TV);
        FakeScreens.Phone p = phone(h, settings());
        PhoneAgent a = agent(p, new FakeScreens.Script(DONE), new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayHit);
        assertEquals(Arrays.asList("launch com.demo", "tap 设置", "tap 深色模式"), p.log);
    }

    @Test public void replayWorksWithSettleOn() {
        SmartReplay.Store st = recorded();
        FakeScreens.Phone p = phone(home("12:30", 3), settings());
        p.lagObserves = 2;   // 转场：点完要再读两次屏新页面才出现
        PhoneAgent a = agent(p, new FakeScreens.Script(DONE), new FakeScreens.Host(), st);
        a.settle = StableRead.Params.defaults();
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayHit);
        assertEquals(Arrays.asList("launch com.demo", "tap 设置", "tap 深色模式"), p.log);
    }

    // ------------------------------------------------------------------ 降级 / 作废

    @Test public void fingerprintMismatchFallsBackToModelAndCountsAFailure() {
        SmartReplay.Store st = recorded();
        FakeScreens.Phone p = phone(home("12:30", 3), settingsRedesigned());
        FakeScreens.Script s = new FakeScreens.Script(T2, DONE);
        PhoneAgent a = agent(p, s, new FakeScreens.Host(), st);
        PhoneAgent.Result r = a.run(GOAL, "com.demo");
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(1, a.replayFallback);
        assertEquals(1, a.replaySteps);
        String u = s.users.get(0);
        assertTrue(u, u.contains("回放到第 2 步停下") && u.contains("画面对不上") && u.contains("界面结构对不上"));
        assertTrue("已完成的步骤交给模型", u.contains("已经做完的（不要重复）") && u.contains("1. tap"));
        assertTrue("当前画面交给模型", u.contains("深色模式"));
        assertEquals("模型接着做完", Arrays.asList("launch com.demo", "tap 设置", "tap 深色模式"), p.log);
        assertEquals(1, st.get(key()).fails);
        assertEquals(0, a.replayRecorded);
    }

    @Test public void twoConsecutiveFailuresVoidThePathAndTheNextSuccessRecordsAgain() {
        SmartReplay.Store st = recorded();
        for (int i = 1; i <= 2; i++) {
            PhoneAgent a = agent(phone(home("12:30", 3), settingsRedesigned()), new FakeScreens.Script(T2, DONE), new FakeScreens.Host(), st);
            a.run(GOAL, "com.demo");
            assertEquals(1, a.replayFallback);
            assertEquals(i == 2 ? 1 : 0, a.replayVoided);
        }
        assertEquals(0, st.size());
        assertEquals(1, st.totalVoided);
        PhoneAgent a = agent(phone(home("12:30", 3), settingsRedesigned()), new FakeScreens.Script(T1, T2, DONE), new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayRecorded);
        assertEquals("v2_theme", st.get(key()).steps.get(1).target.rid);
    }

    @Test public void aSuccessfulReplayResetsTheFailureCount() {
        SmartReplay.Store st = recorded();
        agent(phone(home("12:30", 3), settingsRedesigned()), new FakeScreens.Script(T2, DONE), new FakeScreens.Host(), st).run(GOAL, "com.demo");
        assertEquals(1, st.get(key()).fails);
        agent(phone(home("12:30", 3), settings()), new FakeScreens.Script(DONE), new FakeScreens.Host(), st).run(GOAL, "com.demo");
        assertEquals("不是连续失败", 0, st.get(key()).fails);
        agent(phone(home("12:30", 3), settingsRedesigned()), new FakeScreens.Script(T2, DONE), new FakeScreens.Host(), st).run(GOAL, "com.demo");
        assertEquals(1, st.size());
    }

    @Test public void ambiguousNodeFallsBack() {
        SmartReplay.Store st = recorded();
        // 两个同 id 同名的「设置」，都离录制时的位置很远 → 分不清
        FakeScreens.B h = FakeScreens.screen("com.demo", "x.Main")
                .node("android.widget.TextView", "首页", "toolbar_title", TV)
                .node("android.widget.TextView", "12:30", "clock", TV)
                .node("android.widget.TextView", "推荐内容", "feed_item", TV)
                .node("android.widget.TextView", "推荐内容 2", "feed_item", TV)
                .node("android.widget.TextView", "推荐内容 3", "feed_item", TV)
                .button("消息 3", "btn_msg")
                .button("设置", "btn_settings")
                .button("设置", "btn_settings");
        FakeScreens.Phone p = phone(h, settings());
        FakeScreens.Script s = new FakeScreens.Script(DONE);
        PhoneAgent a = agent(p, s, new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayFallback);
        assertEquals(0, a.replaySteps);
        assertTrue(s.users.get(0), s.users.get(0).contains("分不清是哪一个"));
        assertEquals("没点任何东西", Arrays.asList("launch com.demo"), p.log);
    }

    @Test public void missingNodeFallsBack() {
        SmartReplay.Store st = recorded();
        FakeScreens.B h = FakeScreens.screen("com.demo", "x.Main")
                .node("android.widget.TextView", "首页", "toolbar_title", TV)
                .node("android.widget.TextView", "12:30", "clock", TV)
                .button("偏好", "btn_settings")
                .button("消息 3", "btn_msg")
                .node("android.widget.TextView", "推荐内容", "feed_item", TV);
        FakeScreens.Script s = new FakeScreens.Script(DONE);
        PhoneAgent a = agent(phone(h, settings()), s, new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayFallback);
        assertTrue(s.users.get(0).contains("找不到要操作的节点"));
    }

    @Test public void popupStopsReplay() {
        SmartReplay.Store st = recorded();
        FakeScreens.B h = home("12:30", 3).button("我知道了");
        FakeScreens.Phone p = phone(h, settings());
        FakeScreens.Script s = new FakeScreens.Script(DONE);
        PhoneAgent a = agent(p, s, new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(1, a.replayFallback);
        assertTrue(s.users.get(0), s.users.get(0).contains("冒出了「我知道了」弹窗"));
        assertEquals(Arrays.asList("launch com.demo"), p.log);
        // 对话框资源 id 也算
        SmartReplay.Store st2 = st;   // 路径还在（失败 1 次）
        FakeScreens.B h2 = home("12:30", 3).node("android.widget.TextView", "有新版本", "alertTitle", TV);
        FakeScreens.Script s2 = new FakeScreens.Script(DONE);
        PhoneAgent a2 = agent(phone(h2, settings()), s2, new FakeScreens.Host(), st2);
        a2.run(GOAL, "com.demo");
        assertEquals(1, a2.replayFallback);
        assertTrue(s2.users.get(0).contains("冒出了对话框"));
    }

    @Test public void popupThatWasThereWhenRecordingIsNotAPopup() {
        UiTree t = home("12:30", 3).button("取消").ui(new UiTree.Ids());
        SmartReplay.Print rec = SmartReplay.print(t);
        assertNull(SmartReplay.popup(rec, home("12:31", 4).button("取消").ui(new UiTree.Ids())));
        assertNotNull(SmartReplay.popup(SmartReplay.print(home("12:30", 3).ui(new UiTree.Ids())), t));
    }

    @Test public void otherAppInFrontIsAMismatch() {
        SmartReplay.Print rec = SmartReplay.print(home("12:30", 3).ui(new UiTree.Ids()));
        String why = SmartReplay.mismatch(rec, FakeScreens.screen("com.other", "x.Main").text("别的").ui(new UiTree.Ids()));
        assertTrue(why, why.contains("前台 App 不同"));
        why = SmartReplay.mismatch(rec, FakeScreens.screen("com.demo", "x.Detail").text("别的").ui(new UiTree.Ids()));
        assertTrue(why, why.contains("页面不同"));
    }

    // ------------------------------------------------------------------ 回放指纹

    @Test public void replayFingerprintSeesOnlyStructureAndIds() {
        UiTree a = home("12:30", 3).ui(new UiTree.Ids());
        UiTree b = home("08:05", 99).node("android.widget.TextView", "又一条", "feed_item", TV).ui(new UiTree.Ids());
        assertEquals("时间 / 角标 / 列表条数不影响", a.replayFingerprint(), b.replayFingerprint());
        assertNotEquals("稳定指纹（settle）看文字，会变", a.settleFingerprint(), b.settleFingerprint());
        assertNotEquals(a.replayFingerprint(), settings().ui(new UiTree.Ids()).replayFingerprint());
        UiTree c = home("12:30", 3).node("android.widget.TextView", "新模块", "promo_banner", TV).ui(new UiTree.Ids());
        assertNotEquals("多了一个资源 id 就不同", a.replayFingerprint(), c.replayFingerprint());
        assertNull("但相似度够，回放仍认这是同一画面", SmartReplay.mismatch(SmartReplay.print(a), c));
        assertEquals(1.0, SmartReplay.similarity(SmartReplay.print(a).tokens, SmartReplay.print(b).tokens), 1e-9);
    }

    @Test public void locateUsesTitleWhenSummaryChanges() {
        UiTree rec = FakeScreens.screen("com.chat", "x.List").row("小王", "昨天 12:30 好的").row("小李", "在吗").ui(new UiTree.Ids());
        SmartReplay.Target g = SmartReplay.target(rec.findText("小王"), rec);
        UiTree live = FakeScreens.screen("com.chat", "x.List").row("小王", "今天 09:12 收到").row("小李", "在吗").ui(new UiTree.Ids());
        SmartReplay.Found f = SmartReplay.locate(g, live);
        assertNotNull(f.error, f.el);
        assertTrue(f.el.label.startsWith("小王"));
    }

    @Test public void piiLabelsAreStoredAsHashesButStillMatch() {
        UiTree rec = FakeScreens.screen("com.chat", "x.List").button("13812345678", "contact").ui(new UiTree.Ids());
        SmartReplay.Target g = SmartReplay.target(rec.els.get(0), rec);
        assertTrue(g.hashed);
        assertFalse(g.label.contains("1381234"));
        assertNotNull(SmartReplay.locate(g, rec).el);
        assertFalse(g.toString().contains("1381234"));
    }

    // ------------------------------------------------------------------ 安全

    static FakeScreens.Phone chat() {
        FakeScreens.Phone p = new FakeScreens.Phone()
                .add("launcher", FakeScreens.screen("com.launcher", "x.Launcher").text("桌面"))
                .add("chat", FakeScreens.screen("com.chat", "x.Chat").node("android.widget.TextView", "小王", "chat_title", TV)
                        .edit("输入消息", "et_input").button("发送", "btn_send"));
        p.on("*", "launch:com.chat", "chat");
        return p;
    }

    static final String SEND_GOAL = "给小王发好的";
    static final String SEND = "{\"steps\":[{\"do\":\"type\",\"id\":{id:输入消息},\"text\":\"好的\"},{\"do\":\"tap\",\"id\":{id:发送}}]}";

    @Test public void sensitiveStepStillAsksDuringReplay() {
        SmartReplay.Store st = store(200);
        FakeScreens.Host h0 = new FakeScreens.Host();
        agent(chat(), new FakeScreens.Script(SEND, "{\"done\":\"已发送\"}"), h0, st).run(SEND_GOAL, "com.chat");
        assertEquals(1, h0.confirms.size());
        SmartReplay.Path path = st.get(SmartReplay.key(SEND_GOAL, "com.chat"));
        assertNotNull("发消息可以录（回放时照样确认）", path);
        assertNotNull(path.steps.get(1).sens);

        FakeScreens.Host h = new FakeScreens.Host();
        FakeScreens.Phone p = chat();
        PhoneAgent a = agent(p, new FakeScreens.Script("{\"done\":\"已发送\"}"), h, st);
        a.run(SEND_GOAL, "com.chat");
        assertEquals(1, a.replayHit);
        assertEquals("回放到发送照样弹确认", 1, h.confirms.size());
        assertTrue(h.confirms.get(0).contains("发送"));
        assertTrue(p.log.contains("tap 发送"));

        FakeScreens.Host no = new FakeScreens.Host();
        no.confirmAnswer = false;
        FakeScreens.Phone p2 = chat();
        FakeScreens.Script s2 = new FakeScreens.Script("{\"done\":\"没发\"}");
        PhoneAgent a2 = agent(p2, s2, no, st);
        a2.run(SEND_GOAL, "com.chat");
        assertEquals(1, no.confirms.size());
        assertFalse("拒绝了就不点", p2.log.contains("tap 发送"));
        assertEquals(1, a2.replayHandoff);
        assertEquals("用户拒绝不算路径失败", 0, a2.replayFallback);
        assertEquals(0, st.get(SmartReplay.key(SEND_GOAL, "com.chat")).fails);
        assertTrue(s2.users.get(0).contains("被用户拒绝"));
    }

    @Test public void stepJudgedSensitiveWhenRecordedIsConfirmedEvenIfItLooksHarmlessNow() {
        SmartReplay.Store st = recorded();
        st.get(key()).steps.get(1).sens = "这一步是删除 / 清除数据（录制时判定）";
        FakeScreens.Host h = new FakeScreens.Host();
        agent(phone(home("12:30", 3), settings()), new FakeScreens.Script(DONE), h, st).run(GOAL, "com.demo");
        assertEquals(Arrays.asList("这一步是删除 / 清除数据（录制时判定）"), h.confirms);
    }

    @Test public void paymentInstructionIsNeitherRecordedNorReplayed() {
        SmartReplay.Store st = store(200);
        String goal = "帮我付款给商家";
        PhoneAgent a = agent(phone(home("12:30", 3), settings()), new FakeScreens.Script(T1, T2, DONE), new FakeScreens.Host(), st);
        a.run(goal, "com.demo");
        assertEquals(0, st.size());
        assertTrue(a.replayNote, a.replayNote.contains("不录不放"));
        // 就算存储里有（旧版本留下的），也不放
        SmartReplay.Path fake = st.get(key());
        assertNull(fake);
        SmartReplay.Store st2 = recorded();
        SmartReplay.Path p = st2.get(key());
        p.key = SmartReplay.key("转账给小王", "com.demo");
        st2.put(p);
        FakeScreens.Phone ph = phone(home("12:30", 3), settings());
        FakeScreens.Script s = new FakeScreens.Script(DONE);
        agent(ph, s, new FakeScreens.Host(), st2).run("转账给小王", "com.demo");
        assertFalse(s.users.get(0).contains("【回放】"));
        assertEquals(Arrays.asList("launch com.demo"), ph.log);
    }

    @Test public void taskWithAPaymentStepIsNotRecorded() {
        SmartReplay.Store st = store(200);
        FakeScreens.Phone p = new FakeScreens.Phone().add("shop", FakeScreens.screen("com.shop", "x.Cart").text("购物车").button("立即支付"));
        PhoneAgent a = agent(p, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即支付}}]}", "{\"done\":\"好了\"}"),
                new FakeScreens.Host(), st);
        PhoneAgent.Result r = a.run("看看购物车", "");
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals(0, st.size());
        assertTrue(a.replayNote, a.replayNote.contains("付款"));
    }

    @Test public void paymentAppsAreSkipped() {
        assertNotNull(SmartReplay.skipReason("看看余额", "com.eg.android.alipaygphone"));
        assertNotNull(SmartReplay.skipReason("看看账单", "com.icbc"));
        assertNull(SmartReplay.skipReason(GOAL, "com.demo"));
        assertNotNull(SmartReplay.skipReason("给小王转账 100", "com.tencent.mm"));
    }

    @Test public void verificationCodeIsNotStoredAndReplayHandsBackAtThatStep() throws Exception {
        SmartReplay.Store st = store(200);
        FakeScreens.Phone p = new FakeScreens.Phone().add("login", FakeScreens.screen("com.site", "x.Login")
                .node("android.widget.TextView", "登录", "title", TV).edit("请输入验证码", "et_sms_code").button("下一步", "btn_next"));
        String code = "{\"steps\":[{\"do\":\"type\",\"id\":{id:请输入验证码},\"text\":\"482913\"},{\"do\":\"tap\",\"id\":{id:下一步}}]}";
        FakeScreens.Host h = new FakeScreens.Host();
        agent(p, new FakeScreens.Script(code, "{\"done\":\"好了\"}"), h, st).run("用验证码进入网站", "");
        assertEquals("填验证码本来就要确认", 1, h.confirms.size());
        SmartReplay.Path path = st.get(SmartReplay.key("用验证码进入网站", "com.site"));
        assertNotNull(path);
        assertTrue(path.steps.get(0).secret);
        assertEquals("", path.steps.get(0).text);
        assertFalse(SmartReplay.readAll(new File(tmp.getRoot(), SmartReplay.FILE)).contains("482913"));

        FakeScreens.Phone p2 = new FakeScreens.Phone().add("login", FakeScreens.screen("com.site", "x.Login")
                .node("android.widget.TextView", "登录", "title", TV).edit("请输入验证码", "et_sms_code").button("下一步", "btn_next"));
        FakeScreens.Script s2 = new FakeScreens.Script("{\"done\":\"等你给验证码\"}");
        PhoneAgent a2 = agent(p2, s2, new FakeScreens.Host(), st);
        a2.run("用验证码进入网站", "");
        assertNull("回放没替你填", p2.typed);
        assertEquals(1, a2.replayHandoff);
        assertEquals(0, a2.replayFallback);
        assertTrue(s2.users.get(0).contains("没有保存"));
    }

    @Test public void personalDataInPlainFieldsIsNotStored() {
        UiTree t = FakeScreens.screen("com.x", "x.A").edit("搜索", "search_src_text").edit("收货人手机", "et_receiver").ui(new UiTree.Ids());
        UiTree.El search = t.findText("搜索"), phoneField = t.findText("收货人手机");
        assertTrue(SmartReplay.secretInput("13812345678", search));
        assertTrue(SmartReplay.secretInput("a.b@example.com", search));
        assertTrue(SmartReplay.secretInput("110101199003077777", search));
        assertTrue("4 位以上数字（验证码 / 卡号）", SmartReplay.secretInput("8899", search));
        assertTrue("个人信息栏", SmartReplay.secretInput("张三", phoneField));
        assertFalse(SmartReplay.secretInput("北京天气", search));
        assertFalse(SmartReplay.secretInput("咖啡", null));
    }

    @Test public void askingTheUserMidTaskIsNotRecorded() {
        SmartReplay.Store st = store(200);
        PhoneAgent a = agent(phone(home("12:30", 3), settings()), new FakeScreens.Script("{\"ask\":\"要开吗\"}", T1, T2, DONE),
                new FakeScreens.Host(), st);
        a.run(GOAL, "com.demo");
        assertEquals(0, st.size());
        assertTrue(a.replayNote.contains("问过用户"));
    }

    // ------------------------------------------------------------------ 存储

    static SmartReplay.Path path(String k) {
        SmartReplay.Path p = new SmartReplay.Path();
        p.key = k;
        p.pkg = "com.demo";
        SmartReplay.RStep s = new SmartReplay.RStep();
        s.op = "back";
        p.steps.add(s);
        return p;
    }

    @Test public void lruEvictsTheLeastRecentlyUsedAndSurvivesReload() {
        SmartReplay.Store st = store(3);
        for (String k : new String[]{"a", "b", "c"}) {
            now += 10;
            st.put(path(k));
        }
        now += 10;
        assertNotNull(st.get("a"));   // a 变成最近用
        now += 10;
        st.put(path("d"));            // 超出 3 条：淘汰最久没用的 b
        assertEquals(Arrays.asList("c", "a", "d"), st.keys());
        assertFalse(st.has("b"));
        SmartReplay.Store again = store(3);
        assertEquals("重新加载顺序不变", Arrays.asList("c", "a", "d"), again.keys());
        assertEquals(now, again.get("d").lastUsed);
        assertEquals(SmartReplay.MAX_PATHS, 200);
    }

    @Test public void clearDeletesEverything() {
        SmartReplay.Store st = recorded();
        File f = new File(tmp.getRoot(), SmartReplay.FILE);
        assertTrue(f.exists());
        st.clear();
        assertEquals(0, st.size());
        assertFalse(f.exists());
        assertEquals(0, store(200).size());
        assertEquals(0, st.totalRecorded);
        FakeScreens.Script s = new FakeScreens.Script(T1, T2, DONE);
        agent(phone(home("12:30", 3), settings()), s, new FakeScreens.Host(), st).run(GOAL, "com.demo");
        assertEquals("清空后重新由模型做", 3, s.users.size());
    }

    @Test public void corruptFileIsTreatedAsEmpty() throws Exception {
        File f = new File(tmp.getRoot(), SmartReplay.FILE);
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        o.write("{坏的".getBytes("UTF-8"));
        o.close();
        SmartReplay.Store st = store(200);
        assertEquals(0, st.size());
        st.put(path("x"));
        assertEquals(1, store(200).size());
    }

    // ------------------------------------------------------------------ 开关

    @Test public void switchOffIsTheOldBehavior() {
        recorded();   // 存储里有路径，但开关关着（PhoneHooks 不给 store）→ 不录不放
        FakeScreens.Script s1 = new FakeScreens.Script(T1, T2, DONE), s2 = new FakeScreens.Script(T1, T2, DONE);
        FakeScreens.Phone p1 = phone(home("12:30", 3), settings()), p2 = phone(home("12:30", 3), settings());
        PhoneAgent a1 = agent(p1, s1, new FakeScreens.Host(), null);
        PhoneAgent a2 = agent(p2, s2, new FakeScreens.Host(), null);
        PhoneAgent.Result r1 = a1.run(GOAL, "com.demo"), r2 = a2.run(GOAL, "com.demo");
        assertEquals(3, r1.modelCalls);
        assertEquals("关着不回放", 3, r2.modelCalls);
        assertEquals(p1.log, p2.log);
        assertEquals(p1.observes, p2.observes);
        assertEquals(s1.users, s2.users);
        assertEquals("", a2.replayNote);
        for (String t : r2.trace) assertFalse(t, t.startsWith("replay"));
    }

    @Test public void recordingDoesNotChangeTheFirstRun() {
        FakeScreens.Phone p1 = phone(home("12:30", 3), settings()), p2 = phone(home("12:30", 3), settings());
        FakeScreens.Script s1 = new FakeScreens.Script(T1, T2, DONE), s2 = new FakeScreens.Script(T1, T2, DONE);
        agent(p1, s1, new FakeScreens.Host(), null).run(GOAL, "com.demo");
        agent(p2, s2, new FakeScreens.Host(), store(200)).run(GOAL, "com.demo");
        assertEquals("录制不多读屏、不改提示词", p1.observes, p2.observes);
        assertEquals(s1.users, s2.users);
        assertEquals(p1.log, p2.log);
    }

    @Test public void switchDecision() {
        assertFalse("开源版默认关", SmartReplay.DEFAULT_ON);
        assertTrue(SmartReplay.decide(null, false, true));
        assertFalse(SmartReplay.decide(null, true, true));
        assertFalse(SmartReplay.decide(null, false, false));
        assertTrue(SmartReplay.decide(Boolean.TRUE, true, false));
        assertFalse(SmartReplay.decide(Boolean.FALSE, false, true));
    }

    @Test public void legacyModeNeverReplays() {
        SmartReplay.Store st = recorded();
        FakeScreens.Script s = new FakeScreens.Script(T1);
        PhoneAgent a = agent(phone(home("12:30", 3), settings()), s, new FakeScreens.Host(), st);
        a.legacyOneStep = true;
        a.run(GOAL, "com.demo");
        assertEquals(0, a.replayHit);
        assertFalse(s.users.get(0).contains("【回放】"));
    }
}
