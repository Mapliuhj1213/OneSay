package com.onesay.assistant.v2.probe;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.InterfaceKind;
import com.onesay.assistant.v2.capability.MinedCapabilities;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.executor.ShellCommand;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * P1-14 / QQ 邮箱接口深挖。
 *
 * <p>fixture 已换成<b>真机 dump</b>（test-jvm/fixtures/pm_dump_androidqqmail.txt，裁剪说明见文件头）。
 * 真机结论：{@code qqmail} scheme 只由 LaunchWebPush 声明、没有 Host —— {@code qqmail://inbox} / {@code qqmail://compose}
 * 都落到同一主界面，不能当"收件箱/写信"接口；写信真实入口是 {@code mailto:} → LaunchComposeMail。
 * 过滤恶意/噪音条目的用例改用内联的小段合成文本（{@link #SYNTH}），不再混进真机 fixture。
 */
public class AppInterfaceMinerTest {

    static final String PKG = "com.tencent.androidqqmail";
    static final String THIRD = PKG + "/com.tencent.qqmail.launcher.third.";

    static String fixture() throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/pm_dump_androidqqmail.txt");
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    static List<AppInterfaceMiner.Iface> mine() throws Exception {
        return AppInterfaceMiner.mine(fixture(), PKG, null, null, null);
    }

    static Set<String> keys(List<AppInterfaceMiner.Iface> l) {
        Set<String> s = new HashSet<String>();
        for (AppInterfaceMiner.Iface i : l) s.add(i.key());
        return s;
    }

    static AppInterfaceMiner.Iface find(List<AppInterfaceMiner.Iface> l, String uri, String comp) {
        for (AppInterfaceMiner.Iface i : l) {
            if (uri.equals(i.uri) && (comp == null || comp.equals(i.component))) return i;
        }
        return null;
    }

    // ------------------------------------------------------------------ 真机 dump

    @Test public void fixtureIsTheRealDump() throws Exception {
        String d = fixture();
        assertTrue(d.contains("REAL DEVICE DUMP"));
        assertFalse("不再是合成样例", d.contains("SYNTHETIC"));
        assertTrue("qqmail 段逐字保留", d.contains("com.tencent.androidqqmail/com.tencent.qqmail.launcher.third.LaunchWebPush filter 588652e"));
        assertFalse("真机 qqmail filter 没有 Authority:\"inbox\"", d.contains("Authority: \"inbox\""));
    }

    @Test public void qqmailSchemeIsHostlessLauncherLevelOnly() throws Exception {
        List<AppInterfaceMiner.Iface> l = mine();
        AppInterfaceMiner.Iface q = find(l, "qqmail://", THIRD + "LaunchWebPush");
        assertNotNull(AppInterfaceMiner.summary(l), q);
        assertTrue(q.hostless);
        assertTrue("只能拉起主界面", q.launcherOnly);
        assertNull(q.standardScheme);
        assertTrue(q.describe(), q.describe().startsWith("拉起「"));
        assertTrue(q.describe().contains("主界面"));
        assertFalse("不从组件名反推页面语义", q.keywords().contains("收件箱"));
        assertFalse(q.keywords().contains("写邮件"));
        for (AppInterfaceMiner.Iface i : l) {
            if (i.uri == null) continue;
            assertFalse("不编造 qqmail://<host>：" + i.uri, i.uri.startsWith("qqmail://") && i.uri.length() > "qqmail://".length());
        }
        Set<String> k = keys(l);
        assertFalse(k.contains("L|qqmail://inbox"));
        assertFalse(k.contains("L|qqmail://compose"));

        // 注册成工具：显式组件、回复只说主界面、工具名不带 inbox/compose
        String sign = AppInterfaceMiner.signFor("androidqqmail", q, new HashSet<String>());
        assertEquals("androidqqmail.link_qqmail_main", sign);
        Capability c = MinedCapabilities.buildFromIntent(q.toIntentJson(sign, "androidqqmail"));
        assertNotNull(c);
        assertEquals("am start -a android.intent.action.VIEW -d 'qqmail://' -n '" + THIRD + "LaunchWebPush'", c.cmdTemplate);
        assertTrue(c.summary, c.summary.contains("主界面"));
        assertTrue(c.replyTemplate, c.replyTemplate.endsWith("主界面"));
        assertEquals("界面类接口", InterfaceKind.Kind.UI, InterfaceKind.of(c));
    }

    @Test public void mailtoIsTheRealComposeEntry() throws Exception {
        List<AppInterfaceMiner.Iface> l = mine();
        AppInterfaceMiner.Iface mail = find(l, "mailto:", THIRD + "LaunchComposeMail");
        AppInterfaceMiner.Iface note = find(l, "mailto:", THIRD + "LaunchComposeNote");
        assertNotNull(AppInterfaceMiner.summary(l), mail);
        assertNotNull("真机 mailto 同时被 LaunchComposeNote 声明，各出一条", note);
        assertEquals("mailto", mail.standardScheme);
        assertTrue(mail.hostless);
        assertFalse("标准 scheme 有确定语义，不是只拉主界面", mail.launcherOnly);
        assertTrue(mail.describe(), mail.describe().contains("写邮件"));
        assertTrue(mail.keywords().contains("写邮件"));

        Set<String> taken = new HashSet<String>();
        assertEquals("androidqqmail.mailto_compose_mail", AppInterfaceMiner.signFor("androidqqmail", mail, taken));
        assertEquals("androidqqmail.mailto_compose_note", AppInterfaceMiner.signFor("androidqqmail", note, taken));

        Capability c = MinedCapabilities.buildFromIntent(mail.toIntentJson("androidqqmail.mailto_compose_mail", "androidqqmail"));
        assertNotNull(c);
        assertEquals("显式组件（-p 会因两个组件都声明 mailto 而弹选择器）",
                "am start -a android.intent.action.VIEW -d 'mailto:' -n '" + THIRD + "LaunchComposeMail'", c.cmdTemplate);
        assertEquals(c.cmdTemplate, ShellCommand.fill(c.cmdTemplate, new HashMap<String, String>()));

        List<AppInterfaceMiner.Iface> write = AppInterfaceMiner.rank(l, "用QQ邮箱写一封邮件给张三");
        assertEquals("写信目标：mailto → LaunchComposeMail 第一\n" + AppInterfaceMiner.summary(write),
                "L|mailto:|" + THIRD + "LaunchComposeMail", write.get(0).key());
        assertTrue("ComposeMail 排在 ComposeNote 前", write.indexOf(mail) < write.indexOf(note));
    }

    @Test public void shortcutDeepLinksWithRealHostAndPathAreMined() throws Exception {
        List<AppInterfaceMiner.Iface> l = mine();
        Set<String> taken = new HashSet<String>();
        List<String> signs = new ArrayList<String>();
        for (String p : new String[]{"open_inbox", "open_search", "compose_mail", "compose_note"}) {
            AppInterfaceMiner.Iface i = find(l, "qqmail_3dtouch://com.tencent.androidqqmail.touch/" + p, THIRD + "LaunchShortCutActivity");
            assertNotNull(p + "\n" + AppInterfaceMiner.summary(l), i);
            assertFalse("有 host+path 的是真实深链", i.hostless || i.launcherOnly);
            signs.add(AppInterfaceMiner.signFor("androidqqmail", i, taken));
            Capability c = MinedCapabilities.buildFromIntent(i.toIntentJson(signs.get(signs.size() - 1), "androidqqmail"));
            assertNotNull("scheme 含下划线也能构造", c);
            assertEquals("am start -a android.intent.action.VIEW -d 'qqmail_3dtouch://com.tencent.androidqqmail.touch/" + p
                    + "' -p " + PKG, c.cmdTemplate);
        }
        assertEquals("[androidqqmail.link_open_inbox, androidqqmail.link_open_search, "
                + "androidqqmail.link_compose_mail, androidqqmail.link_compose_note]", signs.toString());
        assertTrue(keys(l).contains("L|https://mail.qq.com"));
        assertTrue(keys(l).contains("L|http://mail.qq.com"));
    }

    @Test public void readGoalPrefersInboxEntryOverLauncherLink() throws Exception {
        List<AppInterfaceMiner.Iface> read = AppInterfaceMiner.rank(mine(), "帮我读一下QQ邮箱里的最新邮件");
        assertEquals(AppInterfaceMiner.summary(read),
                "L|qqmail_3dtouch://com.tencent.androidqqmail.touch/open_inbox", read.get(0).key());
        AppInterfaceMiner.Iface q = find(read, "qqmail://", null);
        assertTrue("只能拉主界面的链接不会被当成收件箱排第一", read.indexOf(q) > 0);
    }

    @Test public void realDumpNoiseIsExcluded() throws Exception {
        List<AppInterfaceMiner.Iface> l = mine();
        String all = AppInterfaceMiner.summary(l);
        assertFalse("QQ 互联授权回调", all.contains("tencent100686947"));
        assertFalse("OAuth RedirectUri 回调", all.contains("L|com.tencent.androidqqmail://"));
        assertFalse("企业微信授权", all.contains("WeWorkAuth"));
        assertFalse("带 MIME 类型的私有 action 需附带数据", all.contains("SEND_DATA"));
        assertFalse("推送", all.contains("TPPUSH"));
        assertFalse("小部件", all.contains("androidqqmail.widget"));
        assertFalse("file/content 协议", all.contains("file:") || all.contains("content:"));
        assertFalse("savetomyftn scheme 超长", all.contains("savetomyftn"));
        assertFalse("Receiver/Service 表不挖", all.contains("CALENDAR_TICK") || all.contains("QS_TILE"));
        int prov = 0;
        for (AppInterfaceMiner.Iface i : l) {
            if (i.kind == AppInterfaceMiner.Kind.PROVIDER) { prov++; assertFalse(i.exported); }
        }
        assertEquals("真机 11 个 provider authority（未导出，只列出）", 11, prov);
        List<AppInterfaceMiner.Iface> picked = AppInterfaceMiner.pickForTools(AppInterfaceMiner.rank(l, ""), 40);
        for (AppInterfaceMiner.Iface i : picked) assertNotSame(AppInterfaceMiner.Kind.PROVIDER, i.kind);
    }

    @Test public void allPickedRealInterfacesBuildSafeCapabilities() throws Exception {
        List<AppInterfaceMiner.Iface> ranked = AppInterfaceMiner.rank(mine(), "读一下QQ邮箱的邮件");
        Set<String> taken = new HashSet<String>();
        List<String> signs = new ArrayList<String>();
        for (AppInterfaceMiner.Iface i : AppInterfaceMiner.pickForTools(ranked, 40)) {
            String sign = AppInterfaceMiner.signFor("androidqqmail", i, taken);
            assertFalse("工具名唯一", signs.contains(sign));
            assertTrue(sign, sign.matches("^[a-z0-9_]+\\.[a-z0-9_]+$"));
            signs.add(sign);
            Capability cap = MinedCapabilities.buildFromIntent(i.toIntentJson(sign, "androidqqmail"));
            assertNotNull(sign, cap);
            assertEquals(SideEffect.DEVICE_CONTROL, cap.sideEffect);
            assertEquals(cap.cmdTemplate, ShellCommand.fill(cap.cmdTemplate, new HashMap<String, String>()));
        }
        assertEquals(signs.toString(), 9, signs.size());
    }

    // ------------------------------------------------------------------ 合成：通用规则与恶意条目

    static final String SYNTH =
            "Activity Resolver Table:\n"
            + "  Schemes:\n"
            + "      foo:\n"
            + "        1a2b3c com.x.app/com.x.app.HomeActivity filter 11\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Category: \"android.intent.category.BROWSABLE\"\n"
            + "          Scheme: \"foo\"\n"
            + "        1a2b3d com.x.app/com.x.app.LinkActivity filter 12\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Category: \"android.intent.category.BROWSABLE\"\n"
            + "          Scheme: \"bar\"\n"
            + "          Authority: \"inbox\": -1\n"
            + "          Authority: \"compose\": -1\n"
            + "          Path: \"PatternMatcher{PREFIX: /event}\"\n"
            + "      evil;reboot:\n"
            + "        1a2b3e com.x.app/com.x.app.EvilActivity filter 13\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Scheme: \"evil;reboot\"\n"
            + "          Authority: \"x$(id)\": -1\n"
            + "      wild:\n"
            + "        1a2b3f com.x.app/com.x.app.WildActivity filter 14\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Scheme: \"wild\"\n"
            + "          Authority: \"*.x.com\": -1 WILD\n"
            + "      weixin:\n"
            + "        1a2b40 com.tencent.mm/.plugin.base.stub.WXEntryActivity filter 15\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Scheme: \"weixin\"\n"
            + "          Authority: \"dl\": -1\n"
            + "  Non-Data Actions:\n"
            + "      com.x.app.action.OPEN_INBOX:\n"
            + "        1a2b41 com.x.app/com.x.app.MailListActivity filter 16\n"
            + "          Action: \"com.x.app.action.OPEN_INBOX\"\n"
            + "      com.x.app.action.PUSH_CLICK:\n"
            + "        1a2b42 com.x.app/com.x.app.PushActivity filter 17\n"
            + "          Action: \"com.x.app.action.PUSH_CLICK\"\n"
            + "Receiver Resolver Table:\n"
            + "  Non-Data Actions:\n"
            + "      com.x.app.action.SECRET:\n"
            + "        1a2b43 com.x.app/com.x.app.SecretReceiver filter 18\n"
            + "          Action: \"com.x.app.action.SECRET\"\n";

    @Test public void hostlessFilterNeverYieldsSchemeHostCombos() throws Exception {
        List<AppInterfaceMiner.Iface> l = AppInterfaceMiner.mine(SYNTH, "com.x.app", null, null, null);
        Set<String> k = keys(l);
        assertTrue(AppInterfaceMiner.summary(l), k.contains("L|foo://|com.x.app/com.x.app.HomeActivity"));
        assertFalse("别的 filter 的 host 不能借给 host-less 的 foo", k.contains("L|foo://inbox"));
        assertFalse(k.contains("L|foo://compose"));
        assertFalse(k.contains("L|foo://inbox/event"));
        assertTrue("有 host 的 filter 照常按 host×path 组合", k.contains("L|bar://inbox/event"));
        assertTrue(k.contains("L|bar://compose/event"));
        assertTrue(k.contains("A|com.x.app.action.OPEN_INBOX|com.x.app/com.x.app.MailListActivity"));
    }

    @Test public void rejectsUnsafeForeignAndNoise() throws Exception {
        String all = AppInterfaceMiner.summary(AppInterfaceMiner.mine(SYNTH, "com.x.app", null, null, null));
        assertFalse("shell 元字符 scheme 丢弃", all.contains("evil"));
        assertFalse(all.contains("$("));
        assertFalse("通配 host 丢弃", all.contains("*.x.com"));
        assertFalse("其它包的组件丢弃", all.contains("weixin"));
        assertFalse("推送 action 是噪音", all.contains("PUSH_CLICK"));
        assertFalse("Receiver 表不挖", all.contains("SECRET"));
    }

    @Test public void tamperedPersistedEntryIsRejected() throws Exception {
        JSONObject it = new JSONObject();
        it.put("sign", "androidqqmail.link_x");
        it.put("software", "androidqqmail");
        it.put("kind", "DEEPLINK");
        it.put("pkg", PKG);
        it.put("uri", "qqmail://x'; reboot; '");
        assertNull("落盘被篡改的深链拒绝构造", MinedCapabilities.buildFromIntent(it));
        it.put("uri", "mailto:");
        assertNull("不透明 scheme 只认 hostless 条目", MinedCapabilities.buildFromIntent(it));
        it.put("hostless", true);
        it.put("component", THIRD + "LaunchComposeMail");
        assertNotNull(MinedCapabilities.buildFromIntent(it));
        it.put("uri", "mailto:a@b.c';reboot;'");
        assertNull("不透明 scheme 只允许裸前缀", MinedCapabilities.buildFromIntent(it));
        it.put("uri", "mailto:");
        it.put("component", PKG + "/.A; reboot");
        Capability c = MinedCapabilities.buildFromIntent(it);
        assertTrue("组件不合规时不拼进命令", c == null || !c.cmdTemplate.contains("reboot"));
        it.put("kind", "ACTIVITY");
        assertNull(MinedCapabilities.buildFromIntent(it));
        it.put("kind", "UNKNOWN");
        assertNull(MinedCapabilities.buildFromIntent(it));
    }

    @Test public void labelledActivitiesStillMined() throws Exception {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put(THIRD + "LaunchReadIcs", "日历文件{x}");
        Set<String> acts = new HashSet<String>();
        acts.add(THIRD + "LaunchReadIcs");
        acts.add(THIRD + "NoLabel");
        acts.add("com.evil/.Hijack");
        List<AppInterfaceMiner.Iface> l = AppInterfaceMiner.mine(fixture(), PKG, labels, acts, null);
        Set<String> k = keys(l);
        assertTrue(k.contains("C|" + THIRD + "LaunchReadIcs"));
        assertFalse(k.contains("C|" + THIRD + "NoLabel"));
        assertFalse(k.contains("C|com.evil/.Hijack"));
        for (AppInterfaceMiner.Iface i : l) {
            if (i.kind == AppInterfaceMiner.Kind.ACTIVITY) assertEquals("标签清洗掉模板字符", "日历文件x", i.label);
        }
    }

    @Test public void legacyPersistedBareSchemeEntriesAreDowngraded() throws Exception {
        JSONObject it = new JSONObject();
        it.put("sign", "androidqqmail.link_qqmail");
        it.put("software", "androidqqmail");
        it.put("kind", "DEEPLINK");
        it.put("pkg", PKG);
        it.put("uri", "qqmail://");
        it.put("summary", "打开深链 qqmail://");
        Capability c = MinedCapabilities.buildFromIntent(it);
        assertNotNull(c);
        assertTrue("旧条目摘要改写为主界面：" + c.summary, c.summary.startsWith("拉起「") && c.summary.contains("主界面"));
        it.put("uri", "mailto://");
        assertNull("旧的 mailto:// 是错误 URI，丢弃后按新规则重挖", MinedCapabilities.buildFromIntent(it));
    }

    @Test public void legacyEntriesStillBuildUnchanged() throws Exception {
        JSONObject it = new JSONObject();
        it.put("sign", "mobileqq.main");
        it.put("component", "com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity");
        it.put("software", "mobileqq");
        it.put("summary", "main");
        Capability c = MinedCapabilities.buildFromIntent(it);
        assertEquals("am start -n com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity", c.cmdTemplate);
        assertEquals(SideEffect.NONE, c.sideEffect);
    }
}
