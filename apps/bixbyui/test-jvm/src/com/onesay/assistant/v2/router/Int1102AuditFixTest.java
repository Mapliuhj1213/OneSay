package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.caps.FastCaps;
import com.onesay.assistant.v2.executor.backend.SysActionBackend;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

/**
 * ★ int/1102 审计剩余项（修后）：批量通知 / 静音 / 待办、上一轮感知改值、家人事实记忆、热点诊断、NFC 刷卡、表格富内容、守卫按生产方式构建。
 */
public class Int1102AuditFixTest {

    private static TriageDecider.Decision tri(String q) {
        return TriageDecider.decide(q, new TriageDecider.Signals(null, false, false));
    }

    @After public void reset() {
        FollowupEdit.clearForTest();
    }

    // ------------------------------------------------------------------ 批量：通知 / 静音

    @Test public void notifAndMuteTemplates() throws Exception {
        for (String q : new String[]{"清除所有通知", "把通知全清了", "清空通知栏", "通知都清掉", "帮我清除全部通知"}) {
            FastIntents.Hit h = FastIntents.match(q);
            assertNotNull(q, h);
            assertEquals(q, FastCaps.SIGN_NOTIF_CLEAR_ALL, h.sign);
        }
        for (String q : new String[]{"全部静音", "把所有声音都关了", "关掉所有声音", "一键静音", "声音全关了"}) {
            FastIntents.Hit h = FastIntents.match(q);
            assertNotNull(q, h);
            assertEquals(q, FastCaps.SIGN_MUTE_ALL, h.sign);
        }
        for (String no : new String[]{"通知怎么关", "为什么收不到微信通知", "关掉微信通知", "静音铃声"}) {
            FastIntents.Hit h = FastIntents.match(no);
            assertTrue(no + " → " + h, h == null || (!FastCaps.SIGN_NOTIF_CLEAR_ALL.equals(h.sign) && !FastCaps.SIGN_MUTE_ALL.equals(h.sign)));
        }
        // 能力登记 + 执行侧
        com.onesay.assistant.v2.capability.CapabilityRegistry reg = new com.onesay.assistant.v2.capability.CapabilityRegistry();
        FastCaps.register(reg);
        assertEquals("custom", reg.describe(FastCaps.SIGN_NOTIF_CLEAR_ALL).executorType);
        assertEquals("shell", reg.describe(FastCaps.SIGN_MUTE_ALL).executorType);
        assertNotNull("全部静音由 ShellPrograms 逐条单命令执行", com.onesay.assistant.v2.executor.backend.ShellPrograms.forSign(FastCaps.SIGN_MUTE_ALL));
        assertFalse("闹钟流 4 不动", Arrays.toString(com.onesay.assistant.v2.executor.backend.ShellPrograms.MUTE_ALL_CMDS).contains("--stream 4"));
        assertTrue("没有通知使用权时给明确的降级话术（不硬拒）", SysActionBackend.NOTIF_NEED_ACCESS.contains("通知使用权"));
        UnifiedRouter.Triage ft = FastPathTriage.triage("清除所有通知", new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String clause) { return null; }
        });
        assertNotNull("分诊第 0 层接住（永不进 AgentLoop）", ft);
        assertFalse(ft.decision.useAgentLoop);
    }

    @Test public void muteAllCommandsPassDaemon() throws Exception {
        org.junit.Assume.assumeTrue(com.onesay.assistant.v2.sec.SecIntExecWhitelistCiTest.haveSh());
        for (String c : com.onesay.assistant.v2.executor.backend.ShellPrograms.MUTE_ALL_CMDS) {
            String v = com.onesay.assistant.v2.sec.SecIntExecWhitelistCiTest.check(c);
            assertTrue(c + " → " + v, v.startsWith("ALLOW"));
        }
        assertTrue(com.onesay.assistant.v2.sec.SecIntExecWhitelistCiTest.check(com.onesay.assistant.v2.executor.backend.ShellPrograms.NFC_ON).startsWith("ALLOW"));
    }

    // ------------------------------------------------------------------ 批量：待办

    @Test public void todoClearIsConfirmedBatch() throws Exception {
        long now = System.currentTimeMillis();
        for (String q : new String[]{"清空待办", "删除所有待办", "把待办全删了", "清空所有待办事项"}) {
            com.onesay.assistant.v2.s3.S3Parser.Parsed p = com.onesay.assistant.v2.s3.S3Parser.parse(q, now, java.util.TimeZone.getDefault());
            assertNotNull(q, p);
            assertEquals(q, com.onesay.assistant.v2.s3.S3Parser.Type.TODO_CLEAR, p.type);
            assertEquals(q, "6t-todo-clear", tri(q).rule);
        }
        assertNotEquals(com.onesay.assistant.v2.s3.S3Parser.Type.TODO_CLEAR,
                com.onesay.assistant.v2.s3.S3Parser.parse("完成待办买牛奶", now, java.util.TimeZone.getDefault()).type);

        com.onesay.assistant.v2.s3.S3Engine e = new com.onesay.assistant.v2.s3.S3Engine(new com.onesay.assistant.v2.s3.S3Store.Mem(), java.util.TimeZone.getDefault());
        com.onesay.assistant.v2.s3.S3Engine.Flags on = new com.onesay.assistant.v2.s3.S3Engine.Flags(true, true);
        com.onesay.assistant.v2.s3.S3Engine.Result empty = e.handle("清空待办", now, on);
        assertTrue("没有待办 → 本地一句话", empty.isLocalReply());
        for (String t : new String[]{"记个待办 买牛奶", "记个待办 交电费"}) {
            com.onesay.assistant.v2.s3.S3Engine.Result r = e.handle(t, now, on);
            assertTrue(e.execute(r.sign, r.args, now).ok);
        }
        com.onesay.assistant.v2.s3.S3Engine.Result r = e.handle("清空待办", now, on);
        assertEquals(com.onesay.assistant.v2.s3.S3Capabilities.SIGN_TODO_CLEAR, r.sign);
        assertEquals(2, r.args.getInt("count"));
        com.onesay.assistant.v2.capability.Capability cap = com.onesay.assistant.v2.s3.S3Capabilities.get(r.sign);
        assertTrue("批量删除走确认卡（risk 2 + confirm）", cap.confirm && cap.risk >= 2);
        com.onesay.assistant.v2.s3.S3Engine.Exec x = e.execute(r.sign, r.args, now);
        assertTrue(x.ok);
        assertEquals("已清空 2 条待办", x.message);
        assertTrue(e.handle("清空待办", now, on).isLocalReply());
    }

    // ------------------------------------------------------------------ 上一轮感知

    @Test public void followupEditReferencesLastTurn() throws Exception {
        long now = 1_000_000L;
        // 上一轮：设了 7 点闹钟（工作日）→「改成八点」= 删 7 点 + 设 8 点（保留 days）
        FollowupEdit.record("clock.alarm.set", new JSONObject().put("hour", 7).put("minute", 0).put("days", 0x3E), now);
        Invocation inv = FollowupEdit.resolve("改成八点", FollowupEdit.last(now + 5000));
        assertNotNull(inv);
        assertTrue(inv.isPlan());
        assertEquals("alarm.cancel", inv.steps.get(0).sign);
        assertEquals(7, inv.steps.get(0).args.getInt("hour"));
        assertEquals("clock.alarm.set", inv.steps.get(1).sign);
        assertEquals(8, inv.steps.get(1).args.getInt("hour"));
        assertEquals(0x3E, inv.steps.get(1).args.getInt("days"));
        // 晚上的闹钟：没写时段沿用下午 / 晚上
        FollowupEdit.record("clock.alarm.set", new JSONObject().put("hour", 22).put("minute", 0), now);
        assertEquals(23, FollowupEdit.resolve("改到十一点", FollowupEdit.last(now)).steps.get(1).args.getInt("hour"));
        assertEquals(30, FollowupEdit.resolve("改成10点半", FollowupEdit.last(now)).steps.get(1).args.getInt("minute"));

        // 上一轮：媒体音量 →「再大一点」= volume.media.up；「小点声」= down
        FollowupEdit.record("volume.media.set", new JSONObject().put("level", 6), now);
        assertEquals("volume.media.up", FollowupEdit.resolve("再大一点", FollowupEdit.last(now)).sign);
        assertEquals("volume.media.down", FollowupEdit.resolve("再小一点", FollowupEdit.last(now)).sign);
        assertEquals(10, FollowupEdit.resolve("调到10", FollowupEdit.last(now)).args.getInt("level"));
        // 上一轮：铃声音量 6 →「再大一点」= ring.set 8
        FollowupEdit.record("volume.ring.set", new JSONObject().put("level", 6), now);
        Invocation ring = FollowupEdit.resolve("再大一点", FollowupEdit.last(now));
        assertEquals("volume.ring.set", ring.sign);
        assertEquals(8, ring.args.getInt("level"));
        // 上一轮：亮度 →「再亮一点」/「调到50%」
        FollowupEdit.record("settings.brightness.set", new JSONObject().put("level", 100), now);
        assertEquals("settings.brightness.up", FollowupEdit.resolve("再亮一点", FollowupEdit.last(now)).sign);
        assertEquals(128, FollowupEdit.resolve("调到50%", FollowupEdit.last(now)).args.getInt("level"));
        // 上一轮：计时器 →「改成十分钟」
        FollowupEdit.record("clock.timer.set", new JSONObject().put("seconds", 300), now);
        assertEquals(600, FollowupEdit.resolve("改成十分钟", FollowupEdit.last(now)).args.getInt("seconds"));

        // 只读 / 不可改的能力不覆盖上一轮
        FollowupEdit.record("battery.level", new JSONObject(), now);
        assertEquals("clock.timer.set", FollowupEdit.last(now).sign);
        // 过期（3 分钟）→ 没有上一轮
        assertNull(FollowupEdit.last(now + FollowupEdit.TTL_MS + 1));
        // 上一轮改不了这个值（计时器 + 改成八点）→ null
        assertNull(FollowupEdit.resolve("改成八点", FollowupEdit.last(now)));

        // 第 0 层接入：有上一轮 → 快路径（永不进 AgentLoop）
        FollowupEdit.record("volume.media.up", new JSONObject(), System.currentTimeMillis());
        UnifiedRouter.Triage t = FastPathTriage.triage("再大一点", new CompoundSplitter.Resolver() {
            @Override public Invocation resolve(String clause) { return null; }
        });
        assertNotNull(t);
        assertEquals("volume.media.up", t.l0.sign);
        assertFalse(t.decision.useAgentLoop);
    }

    @Test public void followupEditWithoutContextClarifiesLocally() {
        FollowupEdit.clearForTest();
        for (String q : new String[]{"改成八点", "再大一点", "调到10", "改成十分钟"}) {
            TriageDecider.Decision d = tri(q);
            assertEquals(q + " → " + d, TriageDecider.Label.CLARIFY, d.label);
            assertEquals("1f-followup-edit", d.rule);
            assertFalse(d.useAgentLoop);
        }
        // 不是改值句的不受影响
        for (String q : new String[]{"八点", "大一点的字体怎么设置", "音量调到10", "明天早上八点叫我"}) {
            assertNotEquals(q, "1f-followup-edit", tri(q).rule);
        }
        assertTrue(UnifiedRouter.FOLLOWUP_NO_CONTEXT.contains("要改哪一项"));
    }

    // ------------------------------------------------------------------ 记忆：家人事实

    @Test public void kinFactsAreMemory() {
        String[][] ok = {{"我女儿生日是5月3号", "女儿的生日"}, {"我的生日是10月18号", "我的生日"}, {"我们结婚纪念日是6月1日", "我们的结婚纪念日"},
                {"我女儿5月3号生日", "女儿的生日"}, {"我老婆喜欢吃火锅", "老婆的喜好"}, {"我女儿对花生过敏", "女儿的喜好"}, {"我老公鞋码42", "老公的鞋码"}};
        for (String[] c : ok) {
            com.onesay.assistant.v2.s2.ImplicitNote.Hit h = com.onesay.assistant.v2.s2.ImplicitNote.detect(c[0]);
            assertNotNull(c[0], h);
            assertEquals(c[0], com.onesay.assistant.v2.s2.ImplicitNote.Kind.SLOT, h.kind);
            assertEquals(c[0], c[1], h.subject);
            assertEquals(c[0], "6m-memory-implicit", tri(c[0]).rule);
        }
        for (String no : new String[]{"我老婆喜欢吃什么", "我女儿生日是哪天", "生日快乐", "我女儿在学校", "我妈说明天下雨", "帮我记住女儿生日"}) {
            com.onesay.assistant.v2.s2.ImplicitNote.Hit h = com.onesay.assistant.v2.s2.ImplicitNote.detect(no);
            assertNull(no + " → " + h, h);
        }
    }

    // ------------------------------------------------------------------ 诊断 / NFC / 富内容

    @Test public void hotspotGoesToDiag() {
        for (String q : new String[]{"热点开了电脑连不上", "开了热点电脑搜不到", "电脑连不上我的热点", "热点连上了没网"}) {
            TriageDecider.Decision d = tri(q);
            assertEquals(q + " → " + d, "2d-diagnose:hotspot_no_client", d.rule);
        }
        assertNotNull(com.onesay.assistant.v2.diag.DiagKnowledge.fix("page_hotspot"));
        // 模板句不受影响：「打开热点」仍是单步
        assertNotEquals(TriageDecider.Label.DIAGNOSE, TriageDecider.decide("打开热点",
                new TriageDecider.Signals("settings.page.hotspot", false, false)).label);
    }

    @Test public void nfcTransitOpensWalletOnlyIfInstalled() {
        for (String q : new String[]{"打开nfc刷公交卡", "开nfc刷地铁卡", "用NFC刷交通卡", "刷公交卡"}) {
            FastIntents.Hit h = FastIntents.match(q);
            assertNotNull(q, h);
            assertEquals(q, FastCaps.SIGN_NFC_TRANSIT, h.sign);
        }
        assertEquals("connectivity.nfc.on", FastIntents.match("打开nfc").sign);
        assertNull(SysActionBackend.pickWallet(new HashSet<String>(Arrays.asList("com.tencent.mm"))));
        assertEquals("com.samsung.android.spay",
                SysActionBackend.pickWallet(new HashSet<String>(Arrays.asList("com.unionpay", "com.samsung.android.spay"))));
        assertEquals("com.unionpay", SysActionBackend.pickWallet(new HashSet<String>(Arrays.asList("com.unionpay"))));
        assertTrue(SysActionBackend.nfcTransitReply(true, null).contains("没找到钱包"));
        assertTrue(SysActionBackend.nfcTransitReply(true, "三星钱包").contains("已打开「三星钱包」"));
    }

    @Test public void tableCompareIsRichAsk() {
        for (String q : new String[]{"用表格对比一下iphone和三星", "做个表格比较一下三款耳机", "列个表对比一下"}) {
            TriageDecider.Decision d = tri(q);
            assertEquals(q + " → " + d, TriageDecider.Label.ASK, d.label);
            assertFalse(d.useAgentLoop);
        }
    }

    // ------------------------------------------------------------------ 守卫按生产方式构建

    @Test public void leakGuardBuiltLikeProduction() throws Exception {
        com.onesay.assistant.plug.leakguard.core.PromptLeakGuard g = AuditHarnessTest.productionGuard();
        assertTrue(g.promptSources().toString(), g.promptSources().contains("agent_prompt.md"));
        assertTrue(g.promptSources().contains("L2.ask.output.default"));
        assertTrue(g.check(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX + "必应、bing").leak());
        assertFalse("正常指令不误拦", g.check("打开蓝牙").leak());
        assertFalse(g.check("全部静音").leak());
    }
}
