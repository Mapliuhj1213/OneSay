package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.executor.AppNameRules;
import com.onesay.assistant.v2.executor.IntentExecutor;
import com.onesay.assistant.v2.island.IslandGeometry;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * [R11] 真机 QQ 邮箱反馈：长按不出岛（被误判全屏）、按住 ≥2 s 松手像唤醒助手、邮件页被误拒（疑似密码 / 证件号码）、
 * 邮件待办提醒、岛的 Apple 式动效参数、「打开Bixby设置」走了 AgentLoop。
 */
public class QuickNoteR11Test {

    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-10-10 10:40 CST */
    private static final long NOW = 1791600000000L;
    private static final long MIN = 60L * 1000L;
    private static final String QQMAIL = "com.tencent.androidqqmail";

    private static QuickNoteClassifier.Note cls(String pkg, String... lines) {
        List<String> l = Arrays.asList(lines);
        return QuickNoteClassifier.classify(pkg, l, EntityExtractor.extractFromTexts(l, NOW, CN), NOW);
    }

    private static String src(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 误拒

    @Test public void googleSecurityMailNotRefused() {
        // 真机 23:12:18 / 23:12:36：谷歌安全提醒邮件被「疑似密码」拒收（退信地址像密码串）
        QuickNoteClassifier.Note n = cls(QQMAIL,
                "安全提醒", "Google", "no-reply@accounts.google.com",
                "<7qwerzxcvasdfg-hjkl0poiuytre.abcdef.xyz123ab45cdefgh.xyz@identity-reachout.bounces.google.com>",
                "发给 user0001@gmail.com, 1234509876@qq.com", "2026年10月7日 00:28",
                "您的 Google 帐号有新的登录活动。如果这不是您本人操作，请检查帐号安全并修改密码。",
                "https://myaccount.google.com/notifications");
        assertTrue("不再误拒：" + n.reason, n.saved());
        assertEquals(QuickNoteClassifier.Category.MAIL, n.category);
        assertFalse("没有待办 / 时间 → 不给提醒", n.mail.actionable() && n.mail.at > 0);
        assertEquals("发信时间是过去的，不当日程", 0L, n.eventAt);
    }

    @Test public void qqMailAddressDigitsNotIdNumber() {
        // 真机 23:12:19 视觉兜底：「账号 1234509876@qq.com」被当成证件号码
        assertNull(ScreenSensitivity.pageReason(true, Arrays.asList("登录账号 1234509876@qq.com", "您好，附件是本周周报")));
        assertNull(ScreenSensitivity.pageReason(false, Arrays.asList("账号 1234509876@qq.com")));
    }

    @Test public void realSensitivePagesStillRefused() {
        assertNotNull("邮件里的验证码", ScreenSensitivity.pageReason(true, Arrays.asList("您的验证码是 482913，5 分钟内有效")));
        assertNotNull("密码表单", ScreenSensitivity.pageReason(true, Arrays.asList("请输入密码")));
        assertNotNull("密码：", ScreenSensitivity.pageReason(true, Arrays.asList("密码：Abc12345")));
        assertNotNull("非邮件 App 照旧：带密码就拒", ScreenSensitivity.pageReason(false, Arrays.asList("如果不是本人操作请修改密码")));
        assertNotNull("银行卡", ScreenSensitivity.pageReason(true, Arrays.asList("银行卡号 6222 0202 0000 1234 567")));
        QuickNoteClassifier.Note otp = cls(QQMAIL, "登录验证", "您的验证码是 482913，5 分钟内有效");
        assertFalse(otp.saved());
        assertEquals(QuickNoteClassifier.Category.REFUSED, otp.category);
    }

    // ------------------------------------------------------------------ 邮件感知

    @Test public void meetingMailGivesReminderAndCalendar() {
        QuickNoteClassifier.Note n = cls(QQMAIL,
                "关于Q4产品评审会的通知", "发件人：王经理", "wang@corp.com", "2026年10月9日 18:20",
                "各位好，", "定于明天下午3点在3楼会议室召开Q4产品评审会，请准时参加。", "会议号：123 456 789");
        assertTrue(n.saved());
        assertEquals(QuickNoteClassifier.Category.MAIL, n.category);
        assertEquals(MailDigest.Kind.MEETING, n.mail.kind);
        assertTrue("将来的时间", n.eventAt > NOW);
        assertTrue(n.tags.contains("邮件") && n.tags.contains("会议"));
        assertTrue(n.title, n.title.startsWith("邮件 会议"));
        assertTrue(n.summary, n.summary.contains("评审"));
        QuickNoteEntry e = new QuickNoteBook(new InMemoryQuickNoteStore(), CN).toEntry(n, NOW, QuickNoteEntry.SRC_LONG);
        List<QuickNoteActions.Action> card = QuickNoteActions.card(e, CN, NOW);
        assertEquals(3, card.size());
        assertEquals(QuickNoteActions.REMIND, card.get(0).type);
        assertTrue(card.get(0).label, card.get(0).label.startsWith("提醒 10/11 14:50"));
        assertEquals(QuickNoteActions.CALENDAR, card.get(1).type);
        assertEquals(QuickNoteActions.UNDO, card.get(2).type);
        assertEquals("提醒 = 会前 10 分钟", e.eventAt - 10 * MIN, Long.parseLong(card.get(0).arg.split("\\|")[0]));
    }

    @Test public void deadlineMailRemindsOneHourBefore() {
        QuickNoteClassifier.Note n = cls(QQMAIL,
                "【提醒】报销材料提交", "财务部", "finance@corp.com",
                "请于10月15日18:00前提交本月报销单及发票原件，逾期顺延至下月。");
        assertEquals(MailDigest.Kind.DEADLINE, n.mail.kind);
        assertTrue(n.eventAt > NOW);
        long r = MailDigest.remindAt(MailDigest.Kind.DEADLINE, n.eventAt, false, NOW, CN);
        assertEquals(n.eventAt - 60 * MIN, r);
    }

    @Test public void todoWithoutTimeGivesAddTodo() {
        QuickNoteClassifier.Note n = cls(QQMAIL, "合同确认", "李律师", "li@law.cn", "麻烦您尽快确认附件中的合同条款并回复。");
        assertEquals(MailDigest.Kind.TODO, n.mail.kind);
        QuickNoteEntry e = new QuickNoteBook(new InMemoryQuickNoteStore(), CN).toEntry(n, NOW, QuickNoteEntry.SRC_LONG);
        List<QuickNoteActions.Action> card = QuickNoteActions.card(e, CN, NOW);
        assertEquals(QuickNoteActions.TODO, card.get(0).type);
        assertEquals("加待办", card.get(0).label);
    }

    @Test public void plainMailNoReminder() {
        QuickNoteClassifier.Note n = cls(QQMAIL, "周末读书会照片", "张三", "zhang@qq.com", "照片放在附件里了，大家看看。");
        assertEquals(QuickNoteClassifier.Category.MAIL, n.category);
        assertFalse(n.mail.actionable());
        QuickNoteEntry e = new QuickNoteBook(new InMemoryQuickNoteStore(), CN).toEntry(n, NOW, QuickNoteEntry.SRC_LONG);
        for (QuickNoteActions.Action a : QuickNoteActions.card(e, CN, NOW)) {
            assertNotEquals(QuickNoteActions.REMIND, a.type);
            assertNotEquals(QuickNoteActions.TODO, a.type);
        }
    }

    @Test public void gmailIsMailAppAndNonMailUnchanged() {
        assertTrue(MailDigest.isMailApp("com.google.android.gm"));
        assertTrue(MailDigest.isMailApp(QQMAIL));
        assertFalse(MailDigest.isMailApp("com.tencent.mm"));
        QuickNoteClassifier.Note n = cls("com.android.chrome", "定于明天下午3点召开评审会");
        assertEquals("非邮件 App 照旧是日程", QuickNoteClassifier.Category.SCHEDULE, n.category);
    }

    @Test public void statusBarClockIsNotADeadline() {
        // 真机 23:12:31：视觉兜底把状态栏「下午 11:12」抄进来 → 被记成「日程 下午 11:12」
        long now = NOW;   // 10:40
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("ah:mm", java.util.Locale.CHINA);
        f.setTimeZone(CN);
        String clock = f.format(new java.util.Date(now)).replace("上午", "上午 ").replace("下午", "下午 ");
        QuickNoteClassifier.Note n = cls(QQMAIL, clock, "周报", "zhang@qq.com", "请查收附件。");
        assertEquals(QuickNoteClassifier.Category.MAIL, n.category);
        assertEquals("状态栏时钟不当时间", 0L, n.eventAt);
    }

    @Test public void launcherClockIsNotSchedule() {
        // R10-3 / 真机 23:11:37：桌面长按被记成「日程 下午11:02」（桌面时钟小部件）
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("ah:mm", java.util.Locale.CHINA);
        f.setTimeZone(CN);
        QuickNoteClassifier.Note n = cls("com.sec.android.app.launcher", f.format(new java.util.Date(NOW)), "微信", "相机", "设置", "QQ邮箱");
        assertNotEquals(QuickNoteClassifier.Category.SCHEDULE, n.category);
        QuickNoteClassifier.Note real = cls("com.android.chrome", "讲座将于明天下午3点开始", "地点：图书馆报告厅");
        assertEquals("真正的日程不受影响", QuickNoteClassifier.Category.SCHEDULE, real.category);
    }

    @Test public void remindAtRules() {
        long at = NOW + 3 * 3600_000L;
        assertEquals(at - 10 * MIN, MailDigest.remindAt(MailDigest.Kind.MEETING, at, false, NOW, CN));
        assertEquals(at, MailDigest.remindAt(MailDigest.Kind.TODO, at, false, NOW, CN));
        assertEquals("提醒点已过、事件未到 → 到点提醒", NOW + 5 * MIN, MailDigest.remindAt(MailDigest.Kind.DEADLINE, NOW + 5 * MIN, false, NOW, CN));
        assertEquals("都过了 → 不提醒", 0L, MailDigest.remindAt(MailDigest.Kind.MEETING, NOW - MIN, false, NOW, CN));
    }

    @Test public void longSummaryCardStaysLonger() {
        assertEquals(IslandModel.NOTE_CARD_LONG_MS, IslandModel.noteCardMs("王经理 · 关于Q4产品评审会的通知 · 定于明天下午3点在3楼会议室召开Q4产品评审会", 3));
        assertEquals(IslandModel.NOTE_CARD_MS, IslandModel.noteCardMs("取件码 5-2-1234", 3));
    }

    // ------------------------------------------------------------------ 按住 ≥2 s

    @Test public void holdAfterScreenNoteIsAbsorbed() {
        // 真机：长按 800 ms 开始记屏幕，按住到 2.1–3.5 s 才松手 → 以前开收音窗 + 面板（像唤醒助手）
        assertEquals(QuickNoteHooks.HoldAction.ABSORB, QuickNoteHooks.holdAction(NOW - 2700, false, NOW));
        assertEquals("锁屏时没读屏 → 随口记", QuickNoteHooks.HoldAction.SUIKOUJI, QuickNoteHooks.holdAction(NOW - 2700, true, NOW));
        assertEquals("很久以前的长按不算同一次", QuickNoteHooks.HoldAction.SUIKOUJI, QuickNoteHooks.holdAction(NOW - 60_000, false, NOW));
        assertEquals(QuickNoteHooks.HoldAction.SUIKOUJI, QuickNoteHooks.holdAction(0, false, NOW));
    }

    @Test public void pluginRoutesHoldThroughDecision() throws Exception {
        String p = src("app/src/com/onesay/assistant/v2/kernel/plugins/QuickNotePlugin.java");
        assertTrue(p.contains("QuickNoteHooks.onHold(ctx)"));
        assertFalse("不再直接开随口记", p.contains("QuickNoteHooks.startHoldSuikouji(ctx);\n            return true;"));
    }

    // ------------------------------------------------------------------ 岛：全屏不藏记一记

    @Test public void noteVisibleInFullscreen() {
        IslandModel m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.configure(true, true);
        m.drainTimers();
        m.setFullscreen(true);
        m.noteStarted("qn1", "记录中", NOW);
        assertEquals("全屏时也显示「记录中」", IslandModel.Kind.COMPACT_NOTE, m.view().kind);
        m.noteResult("qn1", true, "邮件 会议 Q4评审", "会议 10月11日 15:00 · 王经理", "记一记 · 邮件", Arrays.asList("提醒 10/11 14:50", "加日程", "撤销"), NOW);
        assertEquals("全屏时结果卡照样展开", IslandModel.Kind.EXP_NOTE, m.view().kind);
        m.setFullscreen(false);
        m.setFullscreen(true);
        assertEquals("再次进全屏也不收起记一记结果卡", IslandModel.Kind.EXP_NOTE, m.view().kind);
    }

    @Test public void nonNoteStillHiddenInFullscreen() {
        IslandModel m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.configure(true, true);
        m.drainTimers();
        m.taskStarted(1000L, "打开设置");
        m.autoCollapseDue(m.taskSeq());
        m.setFullscreen(true);
        assertEquals("普通后台任务全屏时仍隐藏（v3 规则不变）", IslandModel.Kind.HIDDEN, m.view().kind);
    }

    @Test public void fullscreenVerdictNeedsConfirmation() throws Exception {
        // 真机：探针 insetTop=0 但 dumpsys mTopIsFullscreen=false（QQ 邮箱 / 桌面 / 自家控制台）→ 不算全屏
        assertFalse(com.onesay.assistant.v2.s1.FullscreenPolicy.verdict(null, false, false, true, false));
        assertTrue("root 确认", com.onesay.assistant.v2.s1.FullscreenPolicy.verdict(null, false, true, true, false));
        assertTrue("SYSTEM_UI_FLAG_FULLSCREEN", com.onesay.assistant.v2.s1.FullscreenPolicy.verdict(null, true, false, false, false));
        assertTrue("没 root 才只信 insets", com.onesay.assistant.v2.s1.FullscreenPolicy.verdict(null, false, false, true, true));
        assertFalse("调试覆盖", com.onesay.assistant.v2.s1.FullscreenPolicy.verdict(Boolean.FALSE, true, true, true, true));
    }

    // ------------------------------------------------------------------ 动效

    @Test public void appleMotionSprings() {
        float[] ex = IslandGeometry.springFor(IslandGeometry.Motion.EXPAND);
        float[] co = IslandGeometry.springFor(IslandGeometry.Motion.COLLAPSE);
        float[] ap = IslandGeometry.springFor(IslandGeometry.Motion.APPEAR);
        assertEquals("面板→岛（出现）参数不动", IslandGeometry.SPRING_OMEGA, ap[0], 0.001f);
        assertEquals(IslandGeometry.SPRING_ZETA, ap[1], 0.001f);
        float exResp = (float) (2 * Math.PI / ex[0]), coResp = (float) (2 * Math.PI / co[0]);
        assertTrue("展开 response 0.35–0.5 s", exResp >= 0.35f && exResp <= 0.5f);
        assertTrue("收起 response 0.35–0.5 s", coResp >= 0.35f && coResp <= 0.5f);
        assertTrue("展开轻微回弹", ex[1] < 0.85f && ex[1] > 0.7f);
        // 展开：有一点过冲（可见的弹），但 < 3%，约 0.7 s 内静止
        IslandGeometry.Spring s = new IslandGeometry.Spring(98f, ex[0], ex[1]);
        s.t = 380f;
        float max = 0;
        int frames = 0;
        while (!s.settled(0.3f) && frames < 200) {
            s.step(1f / 60f);
            max = Math.max(max, s.v);
            frames++;
        }
        assertTrue("有回弹 " + max, max > 380.5f);
        assertTrue("回弹 < 3% " + max, max < 380f * 1.03f);
        assertTrue("≈0.8 s 内静止（帧数 " + frames + "）", frames < 50);
        assertEquals(IslandGeometry.Motion.APPEAR, IslandGeometry.motionFor(0, 0, 290, 74));
        assertEquals(IslandGeometry.Motion.EXPAND, IslandGeometry.motionFor(290, 74, 1000, 400));
        assertEquals(IslandGeometry.Motion.COLLAPSE, IslandGeometry.motionFor(1000, 400, 290, 74));
        assertEquals(IslandGeometry.Motion.HIDE, IslandGeometry.motionFor(290, 74, 0, 0));
        assertTrue("新门槛更早淡入", IslandGeometry.CONTENT_GATE_R11 < IslandGeometry.CONTENT_GATE);
    }

    @Test public void fixedWindowCoversExpandedCard() {
        float d = 2.625f;
        int[] w = IslandGeometry.fixedWindow(1080, 2220, 68, d);
        assertEquals(0, w[0]);
        assertEquals(1080, w[2]);
        assertTrue("上沿在岛上方", w[1] <= 68 - 15);
        float maxH = IslandGeometry.expandedMaxHeight(2220, 68);
        assertTrue("下沿盖住最高的展开卡", w[3] >= 68 + maxH);
    }

    @Test public void islandViewNoPerFrameAllocsOrViewScale() throws Exception {
        String v = src("app/src/com/onesay/assistant/v2/island/IslandView.java");
        assertFalse("不再 View.setScaleX（全宽窗口下会挪位）", v.contains("setScaleX("));
        assertTrue("流光渐变缓存", v.contains("mShimmer.setLocalMatrix"));
        assertTrue("文字排版缓存", v.contains("mLayouts.get(key)"));
        assertTrue("量尺寸缓存", v.contains("mMeasureCache.get(s)"));
        assertTrue("触摸区域", v.contains("TOUCHABLE_INSETS_REGION"));
    }

    // ------------------------------------------------------------------ 打开应用走快路径

    @Test public void openBixbySettingsResolvesLocally() {
        Map<String, String> labels = new HashMap<String, String>();
        Map<String, String> comps = new HashMap<String, String>();
        labels.put(IntentExecutor.normalize("Bixby 视觉"), "com.samsung.android.visionintelligence");
        labels.put(IntentExecutor.normalize("OneSay 控制台"), "com.onesay.assistant");
        labels.put(IntentExecutor.normalize("微信"), "com.tencent.mm");
        labels.put(IntentExecutor.normalize("设置"), "com.android.settings");
        labels.put(IntentExecutor.normalize("WiFi万能钥匙"), "com.snda.wifilocating");
        comps.put("com.samsung.android.visionintelligence", "com.samsung.android.visionintelligence/.Main");
        comps.put("com.onesay.assistant", "com.onesay.assistant/com.onesay.assistant.v2.ui.MainActivity");
        comps.put("com.tencent.mm", "com.tencent.mm/.ui.LauncherUI");
        comps.put("com.android.settings", "com.android.settings/.Settings");
        comps.put("com.snda.wifilocating", "com.snda.wifilocating/.Main");
        String self = "com.onesay.assistant";
        assertEquals("真机原句", "com.onesay.assistant/com.onesay.assistant.v2.ui.MainActivity", IntentExecutor.resolveIn("OneSay设置", labels, comps, self));
        assertEquals("com.onesay.assistant/com.onesay.assistant.v2.ui.MainActivity", IntentExecutor.resolveIn("onesay", labels, comps, self));
        assertEquals("Bixby 视觉 精确标签不受影响", "com.samsung.android.visionintelligence/.Main", IntentExecutor.resolveIn("Bixby视觉", labels, comps, self));
        assertEquals("com.tencent.mm/.ui.LauncherUI", IntentExecutor.resolveIn("微信设置", labels, comps, self));
        assertEquals("「设置」本身还是系统设置", "com.android.settings/.Settings", IntentExecutor.resolveIn("设置", labels, comps, self));
        assertNull("wifi设置 不能被包含匹配到 WiFi万能钥匙", IntentExecutor.resolveIn("wifi设置", labels, comps, self));
        assertNull("蓝牙设置 不是应用", IntentExecutor.resolveIn("蓝牙设置", labels, comps, self));
        assertEquals("bixby", AppNameRules.stripSettingsSuffix("bixby设置"));
        assertEquals("微信", AppNameRules.stripSettingsSuffix("微信的设置"));
        assertNull(AppNameRules.stripSettingsSuffix("设置"));
    }

    @Test public void triageGoesFastWhenL0Hits() {
        com.onesay.assistant.v2.router.TriageDecider.Signals s = new com.onesay.assistant.v2.router.TriageDecider.Signals("app.launch", false, false);
        com.onesay.assistant.v2.router.TriageDecider.Decision d = com.onesay.assistant.v2.router.TriageDecider.decide("打开Bixby设置", s);
        assertEquals(com.onesay.assistant.v2.router.TriageDecider.Label.DO_ACT, d.label);
        assertFalse("L0 接住了 → 不进 AgentLoop（不再显示「复合任务」）", d.useAgentLoop);
    }

    static List<ScreenEntity> ents(String... l) {
        return EntityExtractor.extractFromTexts(Arrays.asList(l), NOW, CN);
    }
}
