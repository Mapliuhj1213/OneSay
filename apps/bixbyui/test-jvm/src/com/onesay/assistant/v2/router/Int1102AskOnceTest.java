package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.askonce.AskOnce;
import com.onesay.assistant.v2.executor.MailBridge;
import com.onesay.assistant.v2.sched.SchedEngine;
import com.onesay.assistant.v2.sched.SchedStore;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * ★ int/1102 release 轮：用户 2026-11-03 01:16 拍板的「问一次、记住」、亮度百分比、邮件先问哪类，以及无密钥动作话术（noKeyActLine）。
 */
public class Int1102AskOnceTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static TimeZone saved;

    @BeforeClass public static void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
    }

    @AfterClass public static void tearDown() {
        TimeZone.setDefault(saved);
    }

    static long at(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static SchedEngine sched() {
        return new SchedEngine(new SchedStore.Memory(), TZ);
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/" + rel).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------ 提醒：默认几点（问一次）

    @Test public void remindHourAskOnceOnlyFutureChips() {
        // 早上 8:30 说「提醒我今天交报告」：8 点已过，只给 9 / 10 点
        SchedEngine e = sched();
        SchedEngine.Reply a = e.handle("提醒我今天交报告", at(10, 24, 8, 30));
        assertEquals(SchedEngine.Kind.ASK, a.kind);
        assertEquals(Arrays.asList("上午9点", "上午10点"), a.chips);
        // 直接说钟点也算回答（记成那个钟点）
        SchedEngine.Reply b = e.handle("上午十点半", at(10, 24, 8, 31));
        assertEquals(SchedEngine.Kind.CREATED, b.kind);
        assertEquals("630", b.prefVal);
        assertEquals(630, e.settings.remindMinute);
    }

    // 凌晨 2:50 说「提醒我今天交报告」→ 答「凌晨1点」：默认照记，但今天 1 点已过 → 走「今天已过」问一次，不建过去的日程
    @Test public void remindHourAnswerAlreadyPassedTriggersTodayLate() {
        SchedEngine e = sched();
        long now = at(11, 3, 2, 50);
        SchedEngine.Reply a = e.handle("提醒我今天交报告", now);
        assertEquals(SchedEngine.Kind.ASK, a.kind);
        SchedEngine.Reply b = e.handle("凌晨1点", now + 3000L);
        assertEquals(b.text, SchedEngine.Kind.ASK, b.kind);
        assertEquals(SchedEngine.PREF_REMIND_MINUTE, b.prefKey);
        assertEquals("60", b.prefVal);
        assertTrue(b.text, b.text.contains("已经过了") && b.text.contains("以后怎么提醒"));
        assertTrue(b.chips.contains("1小时后"));
        assertEquals("不建过去的日程", 0, e.store().all(false).size());
        SchedEngine.Reply c = e.handle("1小时后", now + 6000L);
        assertEquals(SchedEngine.Kind.CREATED, c.kind);
        assertEquals(SchedEngine.LATE_1H, c.prefVal);
    }

    // 点名微信但没装 → 直接问改用短信 / QQ（不进多步循环白查；这一次，不记默认）
    @Test public void wechatMissingFallback() {
        String q = "给老王发微信说我到了";
        assertArrayEqualsStr(new String[]{"老王", "我到了"}, com.onesay.assistant.v2.askonce.AskOnce.wechatSend(q));
        assertArrayEqualsStr(new String[]{"老王", "我到了"}, com.onesay.assistant.v2.askonce.AskOnce.wechatSend("用微信给老王发消息说我到了"));
        assertNull(com.onesay.assistant.v2.askonce.AskOnce.wechatSend("给老王发消息说微信上见"));
        assertNull(com.onesay.assistant.v2.askonce.AskOnce.wechatSend("打开微信"));
        com.onesay.assistant.v2.askonce.AskOnce.Ask a = com.onesay.assistant.v2.askonce.AskOnce.askWechatMissing(q, "老王", true);
        assertTrue(a.text, a.text.contains("没装微信"));
        assertEquals(Arrays.asList("短信", "QQ"), a.chips);
        com.onesay.assistant.v2.askonce.AskOnce.Answer s = com.onesay.assistant.v2.askonce.AskOnce.answer(a, "短信");
        assertEquals("给老王发短信说我到了", s.resubmit);
        assertNull("只这一次，不记默认", s.prefKey);
        assertEquals("用QQ给老王发消息说我到了", com.onesay.assistant.v2.askonce.AskOnce.answer(a, "QQ").resubmit);
        assertNull("没给的渠道不算回答", com.onesay.assistant.v2.askonce.AskOnce.answer(com.onesay.assistant.v2.askonce.AskOnce.askWechatMissing(q, "老王", false), "QQ"));
    }

    static void assertArrayEqualsStr(String[] a, String[] b) {
        assertNotNull(b);
        assertEquals(Arrays.asList(a), Arrays.asList(b));
    }

    // 「调亮度到50」走 L0 规则时 50 被当成 50/255（20%）→ 一律按百分比
    @Test public void brightnessL0Percent() {
        assertEquals(128L, com.onesay.assistant.v2.router.L0RuleRouter.brightnessPercentToRaw(50));
        assertEquals(255L, com.onesay.assistant.v2.router.L0RuleRouter.brightnessPercentToRaw(100));
        assertEquals(1L, com.onesay.assistant.v2.router.L0RuleRouter.brightnessPercentToRaw(0));
        assertEquals(255L, com.onesay.assistant.v2.router.L0RuleRouter.brightnessPercentToRaw(180));
    }

    @Test public void remindHourRememberedNeverAsks() {
        SchedEngine e = sched();
        e.settings.remindMinute = 8 * 60;
        SchedEngine.Reply r = e.handle("提醒我明天交报告", at(10, 24, 10, 0));
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.contains("明天早上8点"));
        assertNull(r.prefKey);
    }

    // ------------------------------------------------------------------ 提醒：今天已过（问一次）

    @Test public void todayLateAskOnceThenRemember() {
        SchedEngine e = sched();
        e.settings.remindMinute = 9 * 60;
        long now = at(10, 24, 14, 2);
        SchedEngine.Reply a = e.handle("提醒我今天交报告", now);
        assertEquals(SchedEngine.Kind.ASK, a.kind);
        assertEquals(Arrays.asList("1小时后", "今晚8点", "每次问我几点"), a.chips);
        assertTrue(a.text, a.text.contains("以后怎么提醒"));
        SchedEngine.Reply b = e.handle("1小时后", now + 3000L);
        assertEquals(SchedEngine.Kind.CREATED, b.kind);
        assertEquals(SchedEngine.PREF_TODAY_LATE, b.prefKey);
        assertEquals(SchedEngine.LATE_1H, b.prefVal);
        assertTrue(b.text, b.text.contains("下午3点05") || b.text.contains("下午3点5"));
        // 记住后：直接一小时后，不问
        SchedEngine.Reply c = e.handle("提醒我今天交作业", now + 60000L);
        assertEquals(SchedEngine.Kind.CREATED, c.kind);
        assertTrue(c.text, c.text.contains("按你定的习惯"));
    }

    @Test public void todayLateEveningAndAskEach() {
        SchedEngine e = sched();
        e.settings.remindMinute = 9 * 60;
        long now = at(10, 24, 14, 0);
        e.handle("提醒我今天交报告", now);
        SchedEngine.Reply b = e.handle("今晚8点", now + 3000L);
        assertEquals(SchedEngine.LATE_EVENING, b.prefVal);
        assertTrue(b.text, b.text.contains("晚上8点"));

        SchedEngine f = sched();
        f.settings.remindMinute = 9 * 60;
        f.handle("提醒我今天交报告", now);
        SchedEngine.Reply c = f.handle("每次问我几点", now + 3000L);
        assertEquals(SchedEngine.Kind.ASK, c.kind);
        assertEquals(SchedEngine.LATE_ASK, c.prefVal);
        assertTrue(c.text, c.text.contains("今天几点提醒你「交报告」"));
        SchedEngine.Reply d = f.handle("下午五点", now + 6000L);
        assertEquals(SchedEngine.Kind.CREATED, d.kind);
        assertTrue(d.text, d.text.contains("下午5点"));
        // 晚上 9 点以后没有「今晚8点」选项
        SchedEngine g = sched();
        g.settings.remindMinute = 9 * 60;
        SchedEngine.Reply late = g.handle("提醒我今天交报告", at(10, 24, 21, 0));
        assertEquals(Arrays.asList("1小时后", "每次问我几点"), late.chips);
    }

    // ------------------------------------------------------------------ 发消息渠道

    @Test public void msgChannel() {
        assertNotNull(AskOnce.msgNoChannel("给老王发消息说我到了"));
        assertNotNull(AskOnce.msgNoChannel("帮我给妈妈发个消息"));
        assertNotNull(AskOnce.msgNoChannel("跟张三发条消息说晚上不回来吃饭"));
        assertNull(AskOnce.msgNoChannel("给老王发短信说我到了"));
        assertNull(AskOnce.msgNoChannel("用qq给张三发个消息"));
        assertNull(AskOnce.msgNoChannel("给老王打电话"));
        AskOnce.Ask ask = AskOnce.askMsg("给老王发消息说我到了");
        assertEquals(Arrays.asList("微信", "短信", "QQ"), ask.chips);
        AskOnce.Answer w = AskOnce.answer(ask, "微信");
        assertEquals(AskOnce.K_MSG, w.prefKey);
        assertEquals("微信", w.prefVal);
        assertEquals("给老王发微信说我到了", w.resubmit);
        assertEquals("给老王发短信说我到了", AskOnce.answer(ask, "短信").resubmit);
        assertEquals("用QQ给老王发消息说我到了", AskOnce.answer(ask, "QQ").resubmit);
        assertNull("说了别的事 = 放弃追问", AskOnce.answer(ask, "今天天气怎么样"));
        // 改写后的说法走原路由：短信 → sms.send；微信 / QQ → GUI 多步（发送前确认）
        assertEquals("sms.send", FastIntents.route("给老王发短信说我到了").sign);
        assertNull(FastIntents.route("给老王发微信说我到了"));
        assertTrue(com.onesay.assistant.v2.agenttools.GuiFold.imSend("用QQ给老王发消息说我到了"));
    }

    // ------------------------------------------------------------------ 购物方式

    @Test public void shopMode() {
        AskOnce.Ask ask = AskOnce.askShop("在京东买一箱牛奶");
        assertEquals(Arrays.asList("浏览器跑腿", "直接操作App"), ask.chips);
        AskOnce.Answer a = AskOnce.answer(ask, "直接操作App");
        assertEquals(AskOnce.SHOP_GUI, a.prefVal);
        assertEquals("在京东买一箱牛奶", a.resubmit);
        assertEquals(AskOnce.SHOP_BROWSER, AskOnce.answer(ask, "浏览器跑腿").prefVal);
        assertEquals(AskOnce.SHOP_BROWSER, AskOnce.answer(ask, "用网页").prefVal);
        assertNull(AskOnce.answer(ask, "算了"));
        // 跑腿入口按偏好让路（点名「用浏览器」照旧跑腿）
        assertEquals(com.onesay.assistant.v2.errand.ErrandIntent.Kind.SHOP, com.onesay.assistant.v2.errand.ErrandIntent.parse("在京东买一箱牛奶").kind);
    }

    @Test public void shopPrefWiredIntoErrand() throws Exception {
        String s = src("errand/ErrandHooks.java");
        assertTrue(s.contains("AskOnce.SHOP_GUI.equals(com.onesay.assistant.v2.askonce.AskOnceHooks.shopMode(ctx))"));
        assertTrue(s.contains("!ErrandIntent.explicitErrand(query)"));
    }

    // ------------------------------------------------------------------ 邮件先问哪类

    @Test public void mailAskKind() throws Exception {
        assertTrue(AskOnce.mailGeneric("看看我的邮件"));
        assertTrue(AskOnce.mailGeneric("读一下邮件"));
        assertTrue(AskOnce.mailGeneric("邮件"));
        assertFalse(AskOnce.mailGeneric("看看未读邮件"));
        assertFalse(AskOnce.mailGeneric("有新邮件吗"));
        assertFalse(AskOnce.mailGeneric("打开邮箱"));
        AskOnce.Ask ask = AskOnce.askMail("看看我的邮件");
        assertEquals(Arrays.asList("未读", "重要", "最新3封", "某人发的", "搜内容"), ask.chips);
        assertEquals("看看未读邮件", AskOnce.answer(ask, "未读").resubmit);
        assertEquals("看看重要邮件", AskOnce.answer(ask, "重要").resubmit);
        assertEquals("看看最新邮件", AskOnce.answer(ask, "最新3封").resubmit);
        assertEquals("看看张三发的邮件", AskOnce.answer(ask, "张三发的").resubmit);
        assertEquals("搜一下邮件里的发票", AskOnce.answer(ask, "关于发票的").resubmit);
        AskOnce.Answer who = AskOnce.answer(ask, "某人发的");
        assertNotNull(who.next);
        assertEquals(AskOnce.Topic.MAIL_FROM, who.next.topic);
        assertEquals("看看李四发的邮件", AskOnce.answer(AskOnce.followUp(AskOnce.Topic.MAIL_FROM), "李四").resubmit);
        assertEquals("搜一下邮件里的面试", AskOnce.answer(AskOnce.followUp(AskOnce.Topic.MAIL_KW), "面试").resubmit);
        assertNull("邮件的问题不记偏好", AskOnce.answer(ask, "未读").prefKey);
        // 每个回答改写后的说法都由快路径接住（零 LLM）
        for (String q : new String[]{"看看未读邮件", "看看重要邮件", "看看最新邮件", "看看张三发的邮件", "搜一下邮件里的发票", "看看李四发的邮件"}) {
            Invocation f = FastIntents.route(q);
            assertNotNull(q, f);
            assertEquals(q, "mail.inbox.latest", f.sign);
            assertFalse(q + " 不念正文", f.args.getBoolean("body"));
        }
        assertEquals("unread", FastIntents.route("看看未读邮件").args.getString("filter"));
        assertEquals(3, FastIntents.route("看看最新邮件").args.getInt("count"));
        assertFalse(FastIntents.route("看看最新邮件").args.has("filter"));
        assertEquals("李四", FastIntents.route("看看李四发的邮件").args.getString("from"));
        // 「搜一下周杰伦新歌」不是邮件；★ 11-04 ②：没说浏览器也不再拉起网页（走搜索 API），说了才是网页搜索
        assertNull(FastIntents.route("搜一下周杰伦新歌"));
        assertEquals("browser.search", FastIntents.route("打开浏览器搜一下周杰伦新歌").sign);
    }

    @Test public void mailLocalFilter() {
        assertTrue(MailBridge.localMatch("张三 <zs@example.com>", "周报", "张三", ""));
        assertTrue(MailBridge.localMatch("ZS <zs@example.com>", "周报", "zs@", ""));
        assertFalse(MailBridge.localMatch("李四 <ls@example.com>", "周报", "张三", ""));
        assertTrue(MailBridge.localMatch("x", "10月发票已开具", "", "发票"));
        assertEquals("未读邮件", MailBridge.filterLabel("unread", "", ""));
        assertEquals("「张三」发来的邮件", MailBridge.filterLabel("", "张三", ""));
    }

    // ------------------------------------------------------------------ 亮度百分比

    @Test public void brightnessIsPercent() throws Exception {
        for (String q : new String[]{"亮度调到50", "把亮度调到50", "亮度调到50%", "亮度调到百分之五十"}) {
            Invocation b = FastIntents.route(q);
            assertNotNull(q, b);
            assertEquals(q, "settings.brightness.set", b.sign);
            int lv = b.args.getInt("level");
            assertTrue(q + " → " + lv, lv >= 125 && lv <= 130);
        }
        Invocation full = FastIntents.route("亮度调到100");
        assertEquals(255, full.args.getInt("level"));
    }

    // ------------------------------------------------------------------ 没配密钥的动作

    @Test public void noKeyActLine() throws Exception {
        String w = UnifiedRouter.noKeyActLine("给老王发微信说我到了");
        assertTrue(w, w.contains("大模型密钥") && w.contains("发微信"));
        assertFalse("不说没听懂", w.contains("没听懂"));
        assertFalse("不复述原话", w.contains("我到了"));
        assertTrue(UnifiedRouter.noKeyActLine("在淘宝买牛奶").contains("购物"));
        String s = src("router/UnifiedRouter.java");
        int fb = s.indexOf("private Invocation fallback(");
        int nk = s.indexOf("return askReply(noKeyActLine(query), query);", fb);
        assertTrue("兜底里没 key 的动作给缺密钥话术", fb > 0 && nk > fb && nk < s.indexOf("全线未命中任何可用能力", fb));
    }

    // ------------------------------------------------------------------ 插件接线

    @Test public void pluginWired() throws Exception {
        String b = src("kernel/BuiltinPlugins.java");
        assertTrue(b.contains("new com.onesay.assistant.v2.kernel.plugins.AskOncePlugin()"));
        String p = src("kernel/plugins/AskOncePlugin.java");
        assertTrue(p.contains("case TAKEOVER: return 510;"));
        assertTrue(p.contains("AskOnceHooks.claims(ctx, query)"));
        assertTrue(p.contains("AskOnceHooks.interceptCommand(svc, command)"));
        String h = src("sched/SchedHooks.java");
        assertTrue("日程回答记偏好", h.contains("prefs(ctx).edit().putString(r.prefKey, r.prefVal).apply();"));
        assertTrue("日程追问芯片", h.contains("a.put(\"chips\", new org.json.JSONArray(r.chips));"));
    }
}
