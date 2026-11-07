package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.quicknote.QuickNoteIntent;
import com.onesay.assistant.v2.quicknote.QuickNoteRecall;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.sched.SchedEngine;
import com.onesay.assistant.v2.sched.SchedStore;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * ★ int/1102 常识清单（fix-20261025/int-1102/COMMON-SENSE.md）的 JVM 端到端断言。
 *
 * <p>用户标准（2026-11-02）：不只看路由审计，要看<b>实际产出</b>是否符合常识——最终回复文本、卡片类型、执行能力、是否追问、是否朗读。
 * 线上 LLM 的回答在 JVM 里没法断言，那些条目只断言「走哪条通道、不进 AgentLoop、不追问」；本地引擎（日程 / 记忆 / 记一记 /
 * 模板能力）断言到回复原文。每个测试方法对应清单里的一个功能，方法名 = 清单小节名。
 */
public class CommonSenseTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static RouteLayerModel model;
    static TimeZone saved;

    @BeforeClass public static void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TZ);
        model = new RouteLayerModel(true);
    }

    @AfterClass public static void tearDown() {
        TimeZone.setDefault(saved);
    }

    /** 2026-10-24（周六）的某个钟点。 */
    static long at(int month, int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, month - 1, day, h, m, 0);
        return c.getTimeInMillis();
    }

    static final long NOW = at(10, 24, 10, 0);

    // ------------------------------------------------------------------ 小工具

    static RouteLayerModel.Result route(String q) {
        return model.predict(q);
    }

    /** 不进 AgentLoop / GUI / LLM 分类兜底。 */
    static void assertNoLoop(String q) {
        RouteLayerModel.Result r = route(q);
        assertTrue(q + " 不该进 AgentLoop / GUI：" + r, r.layer != RouteLayerModel.Layer.AGENT && r.layer != RouteLayerModel.Layer.GUI);
        assertFalse(q + " 不该靠 LLM 分类：" + r, r.rule.contains("llm"));
    }

    static void assertSign(String q, String sign) {
        RouteLayerModel.Result r = route(q);
        assertEquals(q + " → " + r, sign, r.sign);
    }

    static Invocation landing(String q) {
        Invocation f = FastIntents.route(q);
        assertNotNull(q + " 应该有快路径落点", f);
        return f;
    }

    static Capability cap(String sign) {
        Capability c = model.registry().describe(sign);
        assertNotNull("能力表里没有 " + sign, c);
        return c;
    }

    static boolean asks(String q) {
        RouteLayerModel.Result r = route(q);
        return "clarify".equals(r.sign) || r.rule.startsWith("4-");
    }

    static SchedEngine sched() {
        return new SchedEngine(new SchedStore.Memory(), TZ);
    }

    static S2Engine.Result s2(S2Engine en, InMemoryMemoryStore st, String q, long now) {
        S2Engine.Result r = en.handle(q, now, new S2Engine.Flags(true, true, false));
        if (r != null && r.sign != null && r.sign.startsWith("s2.") && !S2Capabilities.SIGN_CLEAR.equals(r.sign)) {
            assertTrue(q, en.execute(r.sign, r.args, now).ok);
        }
        return r;
    }

    // ------------------------------------------------------------------ 闹钟

    @Test public void alarm() throws Exception {
        Invocation a = landing("每天早上7点叫我起床");
        assertEquals("clock.alarm.set", a.sign);
        assertEquals(7, a.args.getInt("hour"));
        assertTrue("每天 = 重复闹钟：" + a.args, a.args.has("days"));
        Invocation b = landing("明天早上七点叫醒我");
        assertEquals("clock.alarm.set", b.sign);
        assertEquals(7, b.args.getInt("hour"));
        // 批量：目标就是全部，不追问几点、不进循环
        assertSign("删除所有闹钟", "alarm.cancel.all");
        assertFalse(asks("删除所有闹钟"));
        assertSign("关掉所有闹钟", "alarm.toggle.all");
        // 「10 分钟后提醒我」= 计时器（相对时长），不是日程
        Invocation t = landing("10分钟后提醒我关火");
        assertEquals("clock.timer.set", t.sign);
        assertEquals(600, t.args.getInt("seconds"));
        assertNoLoop("每天早上7点叫我起床");
    }

    // ------------------------------------------------------------------ 日程

    @Test public void schedule() {
        SchedEngine e = sched();
        SchedEngine.Reply r = e.handle("明天下午三点开会", NOW);
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.startsWith("已添加日程：明天下午3点 开会。"));
        assertTrue("日程卡五行：" + r.text, r.text.contains("- 事项：开会") && r.text.contains("- 时间：明天下午3点")
                && r.text.contains("- 地点：") && r.text.contains("- 提醒：") && r.text.contains("- 重复：不重复"));
        assertTrue("可撤销：" + r.text, r.text.contains("撤销"));
        assertFalse("不能说成「我记住了」：" + r.text, r.text.contains("记住了"));
        // 查：今天没有 / 明天有
        assertEquals("今天没有日程。", e.handle("今天有什么安排", NOW + 20000L).text);
        SchedEngine.Reply l = e.handle("明天有什么安排", NOW + 40000L);
        assertEquals(SchedEngine.Kind.LISTED, l.kind);
        assertTrue(l.text, l.text.contains("开会"));
        // 没说上午下午：开会按下午记，不追问，回执说明
        SchedEngine.Reply pm = sched().handle("周一三点开会", NOW);
        assertEquals(SchedEngine.Kind.CREATED, pm.kind);
        assertTrue(pm.text, pm.text.contains("下午3点") && pm.text.contains("没说上午下午"));
        // 撤销
        SchedEngine u = sched();
        u.handle("明天下午三点开会", NOW);
        assertEquals(SchedEngine.Kind.UNDONE, u.handle("撤销", NOW + 20000L).kind);
        // 路由：日程句由日程插件本地接，不进 AgentLoop
        assertEquals("plugin:6d-sched", route("明天下午三点开会").sign);
        assertEquals("plugin:5d-sched-list", route("今天有什么安排").sign);
    }

    // ------------------------------------------------------------------ 提醒

    /** 已经回答过「问一次记住」的引擎（默认 9 点、今天已过 = 每次问）。 */
    static SchedEngine schedRemembered() {
        SchedEngine e = sched();
        e.settings.remindMinute = 9 * 60;
        e.settings.todayLate = SchedEngine.LATE_ASK;
        return e;
    }

    @Test public void reminder() {
        // 第一次只说日子 → 问一次默认几点（芯片 早上8点 / 上午9点 / 上午10点），选了就建并记住
        SchedEngine first = sched();
        SchedEngine.Reply ask = first.handle("提醒我明天交报告", NOW);
        assertEquals(SchedEngine.Kind.ASK, ask.kind);
        assertTrue(ask.text, ask.text.contains("以后默认几点") && ask.text.contains("设置里可以改"));
        assertEquals(java.util.Arrays.asList("早上8点", "上午9点", "上午10点"), ask.chips);
        SchedEngine.Reply pick = first.handle("上午9点", NOW + 5000L);
        assertEquals(SchedEngine.Kind.CREATED, pick.kind);
        assertEquals(SchedEngine.PREF_REMIND_MINUTE, pick.prefKey);
        assertEquals("540", pick.prefVal);
        assertTrue(pick.text, pick.text.contains("以后只说日子的提醒都按上午9点提醒你"));
        // 记住以后（同一引擎）不再问
        SchedEngine.Reply again = first.handle("提醒我后天交作业", NOW + 60000L);
        assertEquals(SchedEngine.Kind.CREATED, again.kind);
        // 只有日子没钟点（已记住 9 点）：那天上午 9 点准点提醒，回执说明，不追问、不进 AgentLoop
        SchedEngine.Reply r = schedRemembered().handle("提醒我明天交报告", NOW);
        assertEquals(SchedEngine.Kind.CREATED, r.kind);
        assertTrue(r.text, r.text.contains("明天上午9点") && r.text.contains("交报告") && r.text.contains("没说几点"));
        assertTrue(r.text, r.text.contains("- 提醒：准点"));
        assertEquals("plugin:6d-sched", route("提醒我明天交报告").sign);
        assertEquals("plugin:6d-sched", route("明天提醒我交报告").sign);
        // 「今天」而 9 点已过（已选「每次问我几点」）：只问一次几点，答了就建
        SchedEngine e = schedRemembered();
        SchedEngine.Reply q = e.handle("提醒我今天交报告", NOW);
        assertEquals(SchedEngine.Kind.ASK, q.kind);
        assertEquals("今天几点提醒你「交报告」？", q.text);
        SchedEngine.Reply a = e.handle("下午五点", NOW + 15000L);
        assertEquals(SchedEngine.Kind.CREATED, a.kind);
        assertTrue(a.text, a.text.contains("下午5点") && a.text.contains("交报告"));
        // 有钟点的提醒 = 准点
        SchedEngine.Reply t = sched().handle("提醒我明天下午三点开会", NOW);   // 有钟点的提醒从来不问
        assertTrue(t.text, t.text.contains("准点提醒"));
        // 相对时长仍是计时器
        assertEquals("clock.timer.set", landing("半小时后提醒我出门").sign);
    }

    // ------------------------------------------------------------------ 导航 / 打车

    @Test public void navigation() throws Exception {
        Invocation n = landing("导航到公司");
        assertEquals("screen.nav.to", n.sign);
        assertEquals("公司", n.args.getString("address"));
        assertEquals("北京西站", landing("导航去北京西站").args.getString("address"));
        assertNoLoop("导航去北京西站");
        assertFalse(asks("导航到公司"));
    }

    @Test public void taxi() {
        // 打车没有接口：走多步任务（打开打车 App 填目的地），不追问、不误判成导航 / 短信；下单前要确认（红卡词）
        RouteLayerModel.Result r = route("打车去机场");
        assertTrue(r.toString(), r.layer == RouteLayerModel.Layer.AGENT || r.layer == RouteLayerModel.Layer.GUI);
        assertFalse(asks("打车去机场"));
        assertFalse("screen.nav.to".equals(r.sign));
    }

    // ------------------------------------------------------------------ 电话 / 短信 / 微信

    @Test public void phone() throws Exception {
        Invocation c = landing("给妈妈打电话");
        assertEquals("phone.call", c.sign);
        assertEquals("妈妈", c.args.getString("number"));
        assertTrue("打电话要确认卡", cap("phone.call").confirm);
        assertEquals("phone.call", route("打电话给10086").sign);
    }

    @Test public void sms() throws Exception {
        Invocation s = landing("给张三发短信说我晚点到");
        assertEquals("sms.send", s.sign);
        assertEquals("张三", s.args.getString("number"));
        assertEquals("我晚点到", s.args.getString("text"));
        assertTrue("发短信要确认卡", cap("sms.send").confirm);
    }

    @Test public void wechat() {
        // 探针原问题：被发成短信。必须走微信（GUI 在微信里操作），发送前确认
        assertNull("不能落到短信模板", FastIntents.route("给老王发微信说我到了"));
        RouteLayerModel.Result r = route("给老王发微信说我到了");
        assertEquals(r.toString(), RouteLayerModel.Layer.GUI, r.layer);
        assertTrue(com.onesay.assistant.v2.agenttools.GuiFold.imSend("微信告诉老王明天开会"));
        assertTrue(com.onesay.assistant.v2.agenttools.GuiFold.imSend("用qq给张三发个消息"));
        assertTrue("发微信是对外动作，要确认", com.onesay.assistant.v2.s3.S3Safety.commandNeedsConfirm("给老王发微信说我到了"));
        assertNull("「发消息」没说渠道，也不默认短信", FastIntents.route("给老王发消息说我到了"));
        // 没说渠道 → 第一次问一次（微信 / 短信 / QQ），记住后按渠道改写
        assertNotNull(com.onesay.assistant.v2.askonce.AskOnce.msgNoChannel("给老王发消息说我到了"));
        assertNull("点了微信不问", com.onesay.assistant.v2.askonce.AskOnce.msgNoChannel("给老王发微信说我到了"));
        assertEquals("给老王发微信说我到了", com.onesay.assistant.v2.askonce.AskOnce.msgRewrite("给老王发消息说我到了", "微信"));
        // 正文里提到微信不影响短信
        assertEquals("sms.send", FastIntents.route("给张三发短信说我微信没电了").sign);
    }

    // ------------------------------------------------------------------ 设置开关 / 锁屏 / 清后台

    @Test public void settingsToggle() throws Exception {
        assertSign("打开蓝牙", "connectivity.bt.on");
        Invocation b = landing("把亮度调到50%");
        assertEquals("settings.brightness.set", b.sign);
        int lv = b.args.getInt("level");
        assertTrue("50% ≈ 128/255：" + lv, lv >= 120 && lv <= 135);
        // 亮度数字一律按百分比（不带 % 也一样）
        Invocation b2 = landing("亮度调到50");
        assertEquals("settings.brightness.set", b2.sign);
        assertEquals("「亮度调到 50」= 50%", lv, b2.args.getInt("level"));
        assertSign("打开勿扰", "dnd.on");
        assertFalse("开蓝牙不要确认卡", cap("connectivity.bt.on").confirm);
        assertSign("全部静音", "sound.mute.all");
        assertSign("清除所有通知", "notif.clear.all");
    }

    @Test public void lockAndRecents() {
        assertSign("锁屏", "screen.lock");
        assertSign("清理后台", "sys.recents.clear");
        assertSign("清空后台", "sys.recents.clear");
        assertNoLoop("清空后台");
    }

    // ------------------------------------------------------------------ 媒体

    @Test public void media() {
        assertSign("播放音乐", "media.play");
        assertSign("放首歌", "media.play");
        assertSign("下一首", "media.next");
        assertSign("暂停", "media.pause");
        assertNoLoop("播放音乐");
        // 「播放音乐」没有播放器（按了播放键还没声音）→ 交给默认音乐 App（没定过选一次）；「继续播放」不换
        assertTrue(com.onesay.assistant.v2.askonce.AskOnce.musicFallbackWanted("播放音乐"));
        assertFalse(com.onesay.assistant.v2.askonce.AskOnce.musicFallbackWanted("继续播放音乐"));
        assertFalse(com.onesay.assistant.v2.askonce.AskOnce.musicFallbackWanted("播放"));
        // 媒体在播时只显示字幕、不出声（不压着音乐说话）
        assertNotNull(com.onesay.assistant.v2.tts.VoiceSyncPolicy.silenceReason(true, false, false, true));
    }

    // ------------------------------------------------------------------ 截图 / 识屏 / 建议卡

    @Test public void screenshotAndVision() {
        assertSign("截个图", "ui.screenshot");
        assertTrue("「屏幕上这是什么」= 识屏指代", com.onesay.assistant.v2.vision.screenref.DeixisResolver.hasDeixis("屏幕上这是什么"));
        assertTrue(com.onesay.assistant.v2.vision.screenref.DeixisResolver.hasDeixis("这个是什么意思"));
    }

    @Test public void suggestCard() {
        com.onesay.assistant.plug.suggest.core.SuggestFakes.Rig r = new com.onesay.assistant.plug.suggest.core.SuggestFakes.Rig().start();
        r.engine.emit(r.shot("/storage/emulated/0/DCIM/Screenshots/Screenshot_9.jpg", 'A'));
        com.onesay.assistant.plug.suggest.core.Suggestion s = r.presenter.last();
        assertEquals("要不要分析这张截图？", s.title);
        assertEquals("分析", s.actions.get(0).label);
        assertEquals("忽略", s.actions.get(1).label);
        assertEquals("不再提示", s.actions.get(2).label);
        assertTrue("几秒自动收：" + s.ttlMs, s.ttlMs > 0 && s.ttlMs <= 10000L);
    }

    // ------------------------------------------------------------------ 记一记 / 记忆 / 快递

    @Test public void quickNote() {
        assertEquals(QuickNoteIntent.Kind.SUIKOUJI, QuickNoteIntent.of("随口记 明早八点交房租"));
        assertEquals(QuickNoteIntent.Kind.RUN, QuickNoteIntent.of("记一记"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("我的取件码是多少"));
        assertEquals(QuickNoteIntent.Kind.OPEN, QuickNoteIntent.of("打开记一记"));
    }

    @Test public void memory() {
        InMemoryMemoryStore st = new InMemoryMemoryStore();
        S2Engine en = new S2Engine(st, st, TZ);
        // 空库问家人属性：本地答「还没记过」，不交给 LLM
        S2Engine.Result miss = s2(en, st, "我女儿生日是哪天", NOW);
        assertNotNull("家人属性问句要本地答", miss);
        assertEquals(S2Engine.Type.RECALL_MISS, miss.type);
        assertTrue(miss.reply, miss.reply.contains("还没记过你女儿的生日"));
        // 记了再问
        S2Engine.Result w = s2(en, st, "我女儿生日是5月3号", NOW + 60000L);
        assertEquals(S2Engine.Type.NOTE_ADD, w.type);
        S2Engine.Result hit = s2(en, st, "我女儿生日是哪天", NOW + 120000L);
        assertEquals(S2Engine.Type.RECALL, hit.type);
        assertTrue(hit.reply, hit.reply.contains("5月3号"));
        assertTrue(s2(en, st, "我女儿什么时候过生日", NOW + 180000L).reply.contains("5月3号"));
        // 路由：回忆问句走记忆插件（3m），不进 L2
        assertEquals("plugin:3m-memory-recall", route("我女儿生日是哪天").sign);
        assertEquals("plugin:6m-memory-implicit", route("我女儿生日是5月3号").sign);
        // 停车
        s2(en, st, "记一下车停在 B2-17", NOW + 240000L);
        S2Engine.Result car = s2(en, st, "我车停哪了", NOW + 300000L);
        assertEquals(S2Engine.Type.RECALL, car.type);
        assertTrue(car.reply.contains("B2-17"));
        // 不是家人事实的知识问句照旧放行
        assertNull(en.handle("北京在哪", NOW, new S2Engine.Flags(true, true, false)));
    }

    @Test public void parcel() {
        // 没给单号的「查一下我的快递」= 先看本机记的单号 / 取件码，不是百度「我的快递」
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("查一下我的快递"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("我的快递到哪了"));
        assertEquals("plugin:quicknote", route("查一下我的快递").sign);
        String m = QuickNoteRecall.missReply("查一下我的快递");
        assertTrue(m, m.contains("单号") && !m.contains("百度"));
        // 带单号：不归记一记（交给查快递技能）
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("查一下快递SF1234567890"));
    }

    // ------------------------------------------------------------------ 诊断 / 翻译 / 搜索 / 富内容 / 问答

    @Test public void diagnose() {
        assertEquals("plugin:2d-diagnose:hotspot_no_client", route("热点开了电脑连不上").sign);
        assertEquals("plugin:2d-diagnose:battery_drain", route("手机发烫得厉害").sign);
    }

    @Test public void translate() {
        assertEquals("ask.reply", route("翻译一下你好").sign);
        assertEquals("ask.reply", route("把这句话翻译成英文：今天天气不错").sign);
        assertNoLoop("翻译一下你好");
    }

    @Test public void search() throws Exception {
        // ★ 11-04 ②（用户：调研时看到「搜索」就直接打开浏览器）：没明确要浏览器 → 快路径不拉起网页（交给问答 / 调研走搜索 API）；
        //   明确说「打开浏览器 / 在浏览器里」才落 browser.search，关键词照旧抽干净
        assertNull("没说浏览器不拉起网页", FastIntents.route("搜一下周杰伦新歌"));
        assertNull("没说浏览器不拉起网页", FastIntents.route("百度一下 iPhone 16 价格"));
        Invocation s = landing("打开浏览器搜一下周杰伦新歌");
        assertEquals("browser.search", s.sign);
        assertEquals("周杰伦新歌", s.args.getString("keyword"));
        assertEquals("iPhone 16 价格", landing("在浏览器里百度一下 iPhone 16 价格").args.getString("keyword"));
        assertNoLoop("打开浏览器搜一下周杰伦新歌");
        assertFalse("没说浏览器：路由层也不落可见浏览器", "browser.search".equals(route("搜一下周杰伦新歌").sign));
        assertTrue(cap("browser.search").replyTemplate.contains("{keyword}"));
        // 点了 App / 指代屏幕 / 搜设备：不是网页搜索
        assertNull(FastIntents.route("在淘宝搜一下牛奶"));
        assertNull(FastIntents.route("搜一下这个"));
        assertNull(FastIntents.route("搜索蓝牙设备"));
    }

    @Test public void richAndAsk() {
        assertEquals("3r-rich-gen", route("画个流程图说明用户注册流程").rule);
        assertEquals("3r-rich-gen", route("用表格对比一下iphone和三星").rule);
        assertNoLoop("今天天气怎么样");
        assertSign("现在几点", "time.now");
    }

    /**
     * release 富内容默认关，但用户明确要表格 → 直接出原生表格卡，不问；
     * 没要表格 → 照旧纯文本；表格内容消毒成纯文本（不执行脚本、无链接 / 代码 / HTML / 图）。
     */
    @Test public void tableOnRequest() {
        com.onesay.assistant.plug.richout.host.RichOutHost.resetTableForTest();
        try {
            assertFalse("JVM release 变体：富内容总开关默认关", com.onesay.assistant.plug.richout.host.RichOutHost.on());
            // 路由：明确要表格 → 问答通道（不进 AgentLoop、不追问）
            for (String q : new String[] {"用表格对比一下iPhone和三星", "列个表对比一下iPhone和三星", "iPhone和三星对比一下，用表格", "做个表对比下安卓和苹果"}) {
                assertTrue(q, com.onesay.assistant.plug.richout.core.RichOutPlugs.wantsTable(q));
                assertNoLoop(q);
                String rule = route(q).rule;
                assertTrue(q + " → " + route(q), rule.contains("3r-rich-gen") || rule.contains("3t-table"));
            }
            for (String q : new String[] {"解释一下什么是光合作用", "打开WPS表格", "做个表情包", "给我看看手表", "用列表列出三点", "不要表格，直接说"}) {
                assertFalse(q, com.onesay.assistant.plug.richout.core.RichOutPlugs.wantsTable(q));
            }
            // 表格轮：提示词只放行表格，AskStyle 不再压成「1~3 句」
            com.onesay.assistant.plug.richout.host.RichOutHost.beginAskTurn("用表格对比一下iPhone和三星");
            assertTrue(com.onesay.assistant.plug.richout.host.RichOutHost.tableOnlyTurn());
            String p = com.onesay.assistant.plug.richout.host.RichOutHost.patchAskPromptTable("规则：\n3. " + com.onesay.assistant.plug.richout.core.RichOutPlugs.ASK_RULE_PLAIN + "\n");
            assertTrue(p, p.contains(com.onesay.assistant.plug.richout.core.RichOutPlugs.ASK_RULE_TABLE_ONLY));
            assertFalse(p.contains(com.onesay.assistant.plug.richout.core.RichOutPlugs.ASK_RULE_PLAIN));
            String style = AskStyle.rule("用表格对比一下iPhone和三星");
            assertTrue(style, style.contains(AskStyle.RULE_TABLE));
            assertFalse(style, style.contains(AskStyle.RULE_BRIEF));
            // 终稿：模型夹带脚本 / 链接 / 代码 / 加粗 → 只剩纯文本表格
            String raw = "两者各有优势。\n\n| 项目 | **iPhone** | 三星 |\n|---|:-:|---|\n| 系统 | iOS<script>alert(1)</script> | [Android](javascript:alert(2)) |\n"
                    + "| 价格 | `5999` 起 | <img src=x onerror=alert(3)>4999 起 |\n\n```js\nalert(4)\n```\n\n<script>alert(5)</script>";
            String t = com.onesay.assistant.plug.richout.host.RichOutHost.polishTableOnly(raw);
            assertNotNull(t);
            assertTrue(t, t.contains("|---|") && t.contains("iPhone") && t.contains("Android") && t.contains("5999"));
            for (String bad : new String[] {"<script", "<img", "onerror", "javascript", "](", "```", "**", "`", "alert(4)", "alert(5)"}) {
                assertFalse("表格回答不该含 " + bad + "：" + t, t.contains(bad));
            }
            assertTrue(com.onesay.assistant.plug.richout.core.RichText.isTableAnswer(t));
            assertTrue("放行过的表格回答 → 表格卡", com.onesay.assistant.plug.richout.host.RichOutHost.tableGranted(t));
            assertFalse("表格轮也不开图 / 阅读器", com.onesay.assistant.plug.richout.host.RichOutHost.diagramsOn() || com.onesay.assistant.plug.richout.host.RichOutHost.readerOn());
            com.onesay.assistant.plug.richout.core.RichText.Speech sp = com.onesay.assistant.plug.richout.host.RichOutHost.speechPlan(t);
            assertNotNull(sp);
            assertEquals(com.onesay.assistant.plug.richout.core.RichText.CUE_TABLE, sp.cue);
            assertFalse("不念表格竖线：" + sp.spoken, sp.spoken.contains("|"));
            assertTrue(com.onesay.assistant.plug.richout.host.RichOutHost.islandText(t).contains("〔表格〕"));
            // 模型没给表格 → 返回 null，照纯文本口径
            assertNull(com.onesay.assistant.plug.richout.host.RichOutHost.polishTableOnly("iPhone 用 iOS，三星用安卓。"));

            // 普通问答：不是表格轮，不出表格卡
            com.onesay.assistant.plug.richout.host.RichOutHost.beginAskTurn("解释一下什么是光合作用");
            assertFalse(com.onesay.assistant.plug.richout.host.RichOutHost.tableOnlyTurn());
            assertFalse(AskStyle.rule("解释一下什么是光合作用").contains(AskStyle.RULE_TABLE));
            assertEquals("规则 3. x", com.onesay.assistant.plug.richout.host.RichOutHost.patchAskPromptTable("规则 3. x"));
            assertFalse(com.onesay.assistant.plug.richout.host.RichOutHost.tableGranted("光合作用是植物利用光能把二氧化碳和水变成有机物并放出氧气的过程。"));
            assertFalse("没登记过的表格（普通回答里冒出来的）不放行",
                    com.onesay.assistant.plug.richout.host.RichOutHost.tableGranted("| 甲 | 乙 |\n|---|---|\n| 1 | 2 |"));
            assertNull(com.onesay.assistant.plug.richout.host.RichOutHost.speechPlan("光合作用是植物利用光能的过程。"));
        } finally {
            com.onesay.assistant.plug.richout.host.RichOutHost.resetTableForTest();
        }
    }

    // ------------------------------------------------------------------ 邮件

    @Test public void mail() throws Exception {
        Invocation m = landing("看看我的邮件");
        assertEquals("mail.inbox.latest", m.sign);
        assertEquals(3, m.args.getInt("count"));
        assertFalse("看邮件不念正文", m.args.getBoolean("body"));
        assertNoLoop("看看我的邮件");
        assertEquals("mail.inbox.latest", landing("有新邮件吗").sign);
        // 「看看我的邮件」先问想看哪类（芯片），按答案读；「有新邮件吗」= 未读，不问
        assertTrue(com.onesay.assistant.v2.askonce.AskOnce.mailGeneric("看看我的邮件"));
        assertFalse(com.onesay.assistant.v2.askonce.AskOnce.mailGeneric("有新邮件吗"));
        assertEquals("unread", landing("有新邮件吗").args.getString("filter"));
        assertEquals("unread", landing("看看未读邮件").args.getString("filter"));
        assertEquals("flagged", landing("看看重要邮件").args.getString("filter"));
        assertEquals("张三", landing("看看张三发的邮件").args.getString("from"));
        assertEquals("发票", landing("搜一下邮件里的发票").args.getString("keyword"));
        assertFalse("「打开邮箱」是开 App", FastIntents.route("打开邮箱") != null && "mail.inbox.latest".equals(FastIntents.route("打开邮箱").sign));
    }

    // ------------------------------------------------------------------ 多步任务

    @Test public void multiStep() {
        RouteLayerModel.Result r = route("帮我在淘宝买一箱牛奶然后发给我老婆");
        assertEquals(RouteLayerModel.Layer.GUI, r.layer);
        assertTrue("下单前要确认", com.onesay.assistant.v2.s3.S3Safety.commandNeedsConfirm("帮我在淘宝买一箱牛奶然后发给我老婆"));
        assertEquals(com.onesay.assistant.v2.tts.VoiceTemplate.ACK_MANY,
                com.onesay.assistant.v2.tts.VoiceSyncPolicy.ack(com.onesay.assistant.v2.tts.VoiceSyncPolicy.AckWhy.STEPS, 3));
        // 单步不先应答（直接给结果）：L0 句
        assertEquals(RouteLayerModel.Layer.L0, route("打开蓝牙").layer);
    }

    // ------------------------------------------------------------------ 朗读 / 思考模式 / 唤醒音

    @Test public void speakPolicy() {
        assertTrue("语音问就语音答", com.onesay.assistant.v2.wake.ConversationTurnPolicy.shouldSpeak(false, true, true, false));
        assertFalse("打字问默认不出声", com.onesay.assistant.v2.wake.ConversationTurnPolicy.shouldSpeak(false, false, true, false));
        assertNotNull("通话中不出声", com.onesay.assistant.v2.tts.VoiceSyncPolicy.silenceReason(true, false, true, false));
    }

    @Test public void thinkingMode() throws Exception {
        com.onesay.assistant.v2.net.ThinkingMode.Level off = com.onesay.assistant.v2.net.ThinkingMode.parse("off");
        com.onesay.assistant.v2.net.ThinkingMode.Level ag = com.onesay.assistant.v2.net.ThinkingMode.parse("agent");
        com.onesay.assistant.v2.net.ThinkingMode.Level all = com.onesay.assistant.v2.net.ThinkingMode.parse("all");
        assertEquals("默认 / 乱值 = 关", off, com.onesay.assistant.v2.net.ThinkingMode.parse("xyz"));
        com.onesay.assistant.v2.net.ThinkingMode.Purpose ASK = com.onesay.assistant.v2.net.ThinkingMode.Purpose.ASK;
        com.onesay.assistant.v2.net.ThinkingMode.Purpose AGENT = com.onesay.assistant.v2.net.ThinkingMode.Purpose.AGENT;
        assertFalse(com.onesay.assistant.v2.net.ThinkingMode.enabledFor(off, ASK) || com.onesay.assistant.v2.net.ThinkingMode.enabledFor(off, AGENT));
        assertFalse("仅多步：问答照旧快答", com.onesay.assistant.v2.net.ThinkingMode.enabledFor(ag, ASK));
        assertTrue(com.onesay.assistant.v2.net.ThinkingMode.enabledFor(ag, AGENT));
        assertTrue(com.onesay.assistant.v2.net.ThinkingMode.enabledFor(all, ASK) && com.onesay.assistant.v2.net.ThinkingMode.enabledFor(all, AGENT));
        assertFalse("内部调用（分类 / 摘要）永远不开", com.onesay.assistant.v2.net.ThinkingMode.enabledFor(all, com.onesay.assistant.v2.net.ThinkingMode.Purpose.INTERNAL));
    }

    @Test public void wakeCue() {
        assertEquals(com.onesay.assistant.v2.wake.WakeCue.Source.A, com.onesay.assistant.v2.wake.WakeCue.parse(null));
        assertEquals(com.onesay.assistant.v2.wake.WakeCue.Source.A, com.onesay.assistant.v2.wake.WakeCue.parse("a"));
        assertEquals(com.onesay.assistant.v2.wake.WakeCue.Source.LEGACY, com.onesay.assistant.v2.wake.WakeCue.parse("legacy"));
        com.onesay.assistant.v2.wake.WakeCue.Source before = com.onesay.assistant.v2.wake.WakeCue.pref();
        try {
            com.onesay.assistant.v2.wake.WakeCue.setPref(com.onesay.assistant.v2.wake.WakeCue.Source.VENDOR);
            if (com.onesay.assistant.v2.wake.WakeCue.vendorFrom() == null) {
                assertEquals("厂商原声没加载 → 退回方案 A，不能没声", com.onesay.assistant.v2.wake.WakeCue.Source.A, com.onesay.assistant.v2.wake.WakeCue.effective());
            }
            assertTrue(com.onesay.assistant.v2.wake.WakeCue.pcm().length > 0);
        } finally {
            com.onesay.assistant.v2.wake.WakeCue.setPref(before);
        }
    }

    // 纯询问屏幕 → 直接识屏，不进多步循环
    @Test public void screenPureQuestionGoesStraightToVision() {
        String[] yes = {"屏幕上这是什么", "这页讲什么", "这个是什么意思", "屏幕上写的什么", "这张图是什么", "这一页说的啥"};
        for (String q : yes) assertTrue("纯询问应直接识屏：" + q, com.onesay.assistant.v2.vision.screenref.DeixisResolver.isPureQuestion(q));
        String[] no = {"把这个发给老婆", "导航去这里", "复制这个", "打这个电话", "看看屏幕上写的什么然后打开微信", "打开微信", "这个周末有什么安排", "把这个保存一下"};
        for (String q : no) assertFalse("不是纯询问：" + q, com.onesay.assistant.v2.vision.screenref.DeixisResolver.isPureQuestion(q));
    }

    private static Capability cap(String sign, com.onesay.assistant.v2.capability.SideEffect se, int risk, boolean confirm) {
        return new Capability.Builder(sign, sign).schema(new com.onesay.assistant.v2.capability.ParamsSchema()).executor("x")
                .sideEffect(se).risk(risk, confirm).build();
    }

    // 只读动作（读屏 / 查询 / 列表）永不弹「做了就撤不回来」；污点下本机只读直接放行
    @Test public void readOnlyNeverIrreversibleCard() {
        com.onesay.assistant.v2.capability.SideEffect R = com.onesay.assistant.v2.capability.SideEffect.PRIVATE_DATA_READ;
        com.onesay.assistant.v2.capability.SideEffect N = com.onesay.assistant.v2.capability.SideEffect.NONE;
        Capability[] reads = {
                com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.get(com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_CONTEXT),
                cap("vision.screen.understand", R, 0, false), cap("memory.search", R, 0, false),
                cap("mail.inbox.latest", R, 0, false), cap("alarm.list", N, 0, false), cap("battery.level", N, 0, false)};
        for (Capability c : reads) {
            assertNotNull(c);
            assertTrue("只读：" + c.sign, com.onesay.assistant.v2.executor.ConsentPolicy.isReadOnly(c));
            assertEquals("只读不出红卡：" + c.sign, com.onesay.assistant.v2.executor.ConsentPolicy.TIER_YELLOW,
                    com.onesay.assistant.v2.executor.ConsentPolicy.tierOf(c, true));
            assertEquals("污点下本机只读也放行（看一眼不弹卡）：" + c.sign, com.onesay.assistant.v2.executor.ToolGate.Decision.ALLOW,
                    com.onesay.assistant.v2.executor.ToolGate.check(c, new JSONObject(), true).decision);
            assertFalse(com.onesay.assistant.v2.ui.wake.WakeCopy.confirmBody(
                    com.onesay.assistant.v2.executor.ConsentPolicy.tierOf(c, true) == com.onesay.assistant.v2.executor.ConsentPolicy.TIER_RED, c.sign, null, null)
                    .contains("撤不回来"));
        }
        // 写 / 发 / 删 照旧：污点下要确认；拨号仍红卡
        assertEquals(com.onesay.assistant.v2.executor.ToolGate.Decision.REQUIRE_CONFIRM,
                com.onesay.assistant.v2.executor.ToolGate.check(cap("mail.send", com.onesay.assistant.v2.capability.SideEffect.PUBLIC_COMMS, 2, true), new JSONObject(), true).decision);
        assertEquals(com.onesay.assistant.v2.executor.ToolGate.Decision.REQUIRE_CONFIRM,
                com.onesay.assistant.v2.executor.ToolGate.check(cap("note.add", com.onesay.assistant.v2.capability.SideEffect.PERSISTENT_WRITE, 1, false), new JSONObject(), true).decision);
        assertEquals(com.onesay.assistant.v2.executor.ConsentPolicy.TIER_RED,
                com.onesay.assistant.v2.executor.ConsentPolicy.tierOf(cap("phone.call", com.onesay.assistant.v2.capability.SideEffect.PUBLIC_COMMS, 2, true), false));
        // 远程 MCP 读工具：本机核实不了 → 污点下仍确认（但不是红卡）
        Capability remote = new Capability.Builder("conn.acme.read_doc", "x").schema(new com.onesay.assistant.v2.capability.ParamsSchema())
                .executor("x").domain("conn").sideEffect(R).risk(0, false).build();
        assertFalse(com.onesay.assistant.v2.executor.ConsentPolicy.isLocalReadOnly(remote));
    }

    // 翻译只给译文；「一句话」就一句；没要求详细时简短
    @Test public void askStyleConstraints() throws Exception {
        String t = AskStyle.rule("我饿了用英语怎么说");
        assertTrue(t.contains("只输出译文") && t.contains("最多再给一个常用") && t.contains("不列出其他语言"));
        assertFalse("翻译不再叠加简短规则", t.contains(AskStyle.RULE_BRIEF));
        assertTrue(AskStyle.isTranslate("把这句翻译成日语"));
        assertTrue(AskStyle.isTranslate("谢谢用日语怎么说"));
        assertFalse(AskStyle.isTranslate("北京今天天气怎么样"));
        assertTrue(AskStyle.rule("用一句话介绍一下北京").contains(AskStyle.RULE_ONE_SENTENCE));
        assertFalse(AskStyle.rule("用一句话介绍一下北京").contains(AskStyle.RULE_BRIEF));
        assertTrue(AskStyle.rule("北京有多少人口").contains(AskStyle.RULE_BRIEF));
        assertEquals("要求详细时不加约束", "", AskStyle.rule("详细讲讲量子计算的原理"));
        String vp = com.onesay.assistant.v2.vision.ScreenVisionEngine.questionPrompt("屏幕上这是什么");
        assertTrue("识屏问答同口径：简短、纯文本", vp.contains(AskStyle.RULE_BRIEF) && vp.contains("不用 Markdown") && vp.endsWith("屏幕上这是什么"));
        assertTrue(com.onesay.assistant.v2.vision.ScreenVisionEngine.questionPrompt("把屏幕上的英文翻译成中文").contains(AskStyle.RULE_TRANSLATE));
        assertFalse("提示词改了 → ASK 缓存版本换掉，旧长答案作废", "ask".equals(com.onesay.assistant.v2.router.cache.SkillCache.ASK_CAP_VERSION));
        // 模板不再强制 200 字下限；两条问答路径都追加本轮约束；翻译技能不讲多语言
        String ask = PromptLibrary.Defaults.ASK_OUTPUT;
        assertFalse(ask.contains("{{minChars}}~{{maxChars}}"));
        assertTrue(ask.contains("默认简短"));
        String l2 = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java")), "UTF-8");
        assertEquals("同步与流式两条问答路径都要追加 AskStyle", 2, l2.split("AskStyle\\.rule\\(query\\)", -1).length - 1);
        String sk = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/assets/skill_translate.md")), "UTF-8");
        assertTrue(sk.contains("只给译文本身") && !sk.contains("读音 / 用法提示"));
    }

    /** ★ fix/shop-research（RESEARCH-TEST-basilisk 2026-11-03）：「帮我买 X」= 购物比价跑腿（多平台、停在付款前、不问预算），「你决定」接回同一任务。 */
    @Test public void shopBuyResearch() {
        com.onesay.assistant.v2.errand.ErrandIntent in = com.onesay.assistant.v2.errand.ErrandIntent.parse("帮我买雷蛇巴塞利斯蛇v3");
        assertEquals(com.onesay.assistant.v2.errand.ErrandIntent.Kind.SHOP, in.kind);
        assertTrue(in.compare && in.sites.size() >= 2);
        assertFalse(com.onesay.assistant.v2.errand.ErrandIntent.isBuy("今天谁买单"));
        assertFalse(com.onesay.assistant.v2.errand.ErrandIntent.isBuy("推荐几本买菜谱"));
        assertTrue(com.onesay.assistant.v2.errand.ErrandPrompt.shopSystem().contains("不问预算"));
        TaskContinuation.record("帮我买雷蛇巴塞利斯蛇v3", "有三个版本，你要哪一款？", TaskContinuation.Origin.ERRAND, 1000L);
        String rw = TaskContinuation.rewrite("你决定", 2000L);
        assertTrue(com.onesay.assistant.v2.errand.ErrandIntent.parse(rw).userDelegated());
        TaskContinuation.clear();
    }

    /** ★ fix/research-depth（用户 11-03 12:04）：行情 / 市场 / 二手价类不走纯问答直答，进 AgentLoop 先拆维度检索（收工要数字、例子、来源）。 */
    @Test public void researchDepth() {
        for (String q : new String[]{"今天全球股市行情", "二手奢侈品行情与客单价", "最近金价走势", "现在二手iPhone 15什么价"}) {
            TriageDecider.Decision d = TriageDecider.decide(q, TriageDecider.Signals.offline());
            assertTrue(q, d.useAgentLoop);
            assertEquals(q, ResearchGate.Kind.RESEARCH, ResearchGate.kindOf(q));
        }
        assertEquals(ResearchGate.RESEARCH_DATA_NUDGE, ResearchGate.finishNudge(ResearchGate.Kind.RESEARCH, null, "整体偏强。", true, false));
    }
}
