package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.vision.screenref.EntityExtractor;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * [R11 真机复核 2026-10-12] Note 8 上装 R11 release 后模拟长按发现的三处问题（文字取自当晚 uiautomator dump）。
 */
public class QuickNoteR11DeviceTest {

    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-10-12 21:43 CST */
    private static final long NOW = 1791812580000L;
    private static final String QQMAIL = "com.tencent.androidqqmail";
    private static final String LAUNCHER = "com.sec.android.app.launcher";

    private static QuickNoteClassifier.Note cls(String pkg, String... lines) {
        List<String> l = Arrays.asList(lines);
        return QuickNoteClassifier.classify(pkg, l, EntityExtractor.extractFromTexts(l, NOW, CN), NOW);
    }

    // 1. 桌面天气小部件「已更新 10/11 下午11:02」不是日程

    @Test public void weatherWidgetUpdateStampIsNotSchedule() {
        QuickNoteClassifier.Note n = cls(LAUNCHER,
                "天气", "Guangzhou, 当前温度28°, Fair, 天气, 已更新 10/11 下午11:02, 双击可获取更多信息。",
                "28°", "Guangzhou", "已更新 10/11 下午11:02", "刷新",
                "电话", "信息", "Play 商店", "相机");
        assertNotEquals("天气小部件的更新时间不当日程：" + n.title, QuickNoteClassifier.Category.SCHEDULE, n.category);
    }

    @Test public void realScheduleLineStillSchedule() {
        QuickNoteClassifier.Note n = cls(LAUNCHER, "明天下午3点 项目评审会", "会议室 A");
        assertEquals(QuickNoteClassifier.Category.SCHEDULE, n.category);
    }

    @Test public void stampWordsDetected() {
        assertTrue(QuickNoteClassifier.STAMP_WORDS.matcher("已更新 10/11 下午11:02").find());
        assertTrue(QuickNoteClassifier.STAMP_WORDS.matcher("Last updated 9:30").find());
        assertFalse(QuickNoteClassifier.STAMP_WORDS.matcher("明天下午3点开会").find());
    }

    // 2. 读信页主题不取导航按钮

    private static final String[] GOOGLE_PAGE = {
            "返回", "进入上一封邮件", "进入下一封邮件",
            "这是发送给 user0001@gmail.com 的安全提醒的副本。1234509876@qq.com 是该账号的辅助邮箱。",
            "Icon_recovery_x2_20_20",
            "如果不认识此账号，请移除您的电子邮件地址。",
            "“示例应用”已获得对您的已关联 Google 账号的访问权限",
            "ACg8ocXxxx-AbCdEfGhIjKlMnOpQrStUv-WxYzAbCdEfGhIjKlMnOpQ=s96-c",
            "user0001@gmail.com",
            "如果您并未授予访问权限，则应检查此活动，并确保您的账号安全。",
            "查看活动", "您也可以访问以下网址查看安全性活动：", "https://myaccount.google.com/notifications",
            "user0001@gmail.com 的安全提醒",
            "发件人：", "Google", "no-reply@accounts.google.com",
            "收件人：", "1234509876", "1234509876@qq.com",
            "时间：", "2026年10月7日 00:28",
            "隐藏", "删除邮件", "删除", "分享", "回复和转发", "回复转发", "更多操作", "更多"
    };

    @Test public void subjectSkipsNavChrome() {
        String s = MailDigest.subject(Arrays.asList("返回", "进入上一封邮件", "进入下一封邮件",
                "user0001@gmail.com 的安全提醒", "发件人：", "Google"));
        assertEquals("user0001@gmail.com 的安全提醒", s);
    }

    // 3. 邮件里「形似密码」的图片名 / 头像地址不再整屏拒收（整封没有「密码」字样时）

    @Test public void shapeOnlyTokensInMailNotRefused() {
        QuickNoteClassifier.Note n = cls(QQMAIL, GOOGLE_PAGE);
        assertTrue("不再误拒：" + n.reason, n.saved());
        assertEquals(QuickNoteClassifier.Category.MAIL, n.category);
        assertFalse("主题不是导航按钮：" + n.title, n.title.contains("上一封"));
        assertEquals("user0001@gmail.com 的安全提醒", MailDigest.subject(Arrays.asList(GOOGLE_PAGE)));
    }

    @Test public void assetNamesAreNotSubjects() {
        assertTrue(MailDigest.ASSETISH.matcher("Icon_recovery_x2_20_20").matches());
        assertTrue(MailDigest.ASSETISH.matcher("ACg8ocXxxx-AbCdEfGhIjKlMnOpQrStUv-WxYzAbCdEfGhIjKlMnOpQ=s96-c").matches());
        assertFalse(MailDigest.ASSETISH.matcher("Weekly report").matches());
        assertEquals("项目周报", MailDigest.subject(Arrays.asList("返回", "Icon_recovery_x2_20_20", "项目周报", "正文……")));
    }

    @Test public void realPasswordMailStillRefused() {
        assertNotNull(ScreenSensitivity.pageReason(true, Arrays.asList("您的临时密码：", "Ab3k9xQ2", "请登录后修改")));
        assertNotNull("有密码字样时形似密码的串照旧拒",
                ScreenSensitivity.pageReason(true, Arrays.asList("初始 password 如下", "Ab3k9xQ2zz")));
        assertNotNull("非邮件 App 口径不变", ScreenSensitivity.pageReason(false, Arrays.asList("Icon_recovery_x2_20_20")));
        assertNotNull("验证码照旧拒", ScreenSensitivity.pageReason(true, Arrays.asList("您的验证码是 384920")));
    }

    // 4. 锁屏唤醒不读屏（源码口径：ScreenRefHooks.onWake 在 dump 前先查锁屏）

    @Test public void wakeCaptureSkipsKeyguard() throws Exception {
        File f = new File("app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
        if (!f.exists()) f = new File("../app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int lock = src.indexOf("if (keyguardLocked(svc))");
        int cap = src.indexOf("ScreenContextCapture.captureAsync(svc");
        assertTrue(lock > 0 && cap > 0 && lock < cap);
    }
}
