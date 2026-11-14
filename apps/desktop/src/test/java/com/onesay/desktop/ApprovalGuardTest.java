package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * 电脑端确认框加固（思路参考 Haris 审批卡，MIT；代码自己写的）：
 * 1. 防误点：显示后约 0.7 秒「允许」才算数（失焦回来重新等），焦点默认在「拒绝」；
 * 2. 默认拒绝：关窗口 / Esc / 超时 / 失焦没人点 / 界面没动静 / 出错 = 拒绝；
 * 3. 说话和远程消息不能批准（voice_approval_blocked），只认本机鼠标 / 键盘点击。
 * 时间相关的都用假时钟。
 */
public class ApprovalGuardTest {

    static final ConfirmGate.Input MOUSE = ConfirmGate.Input.MOUSE, KEY = ConfirmGate.Input.KEYBOARD, VOICE = ConfirmGate.Input.VOICE,
            REMOTE = ConfirmGate.Input.REMOTE;
    static final ConfirmGate.Answer OK = ConfirmGate.Answer.ACCEPTED, EARLY = ConfirmGate.Answer.TOO_EARLY,
            NOT_SHOWN = ConfirmGate.Answer.NOT_SHOWN, NOT_LOCAL = ConfirmGate.Answer.NOT_LOCAL, GONE = ConfirmGate.Answer.GONE;

    /** 测试助手（旧测试也用）：像界面那样先报「已显示」（窗口有焦点），等过防误点再用鼠标点。 */
    static void click(PcGuard.Confirms cf, int id, boolean allow, boolean always) {
        cf.seen(id, true);
        if (allow) try { Thread.sleep(ConfirmGate.ARM_MS + 80); } catch (InterruptedException ignored) { }
        cf.answer(id, allow, always, MOUSE);
    }

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    /** 假时钟的 Confirms：等待间隔调短，时间由测试推。 */
    static PcGuard.Confirms fake(AtomicLong t) {
        PcGuard.Confirms cf = new PcGuard.Confirms();
        cf.clock = t::get;
        cf.tickMs = 5;
        return cf;
    }

    static CompletableFuture<Integer> askAsync(PcGuard.Confirms cf, long timeoutMs, boolean offer) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        Thread th = new Thread(() -> {
            try { f.complete(cf.ask("phone", "sys_exec", "执行命令", timeoutMs, offer)); } catch (Throwable e) { f.completeExceptionally(e); }
        });
        th.setDaemon(true);
        th.start();
        return f;
    }

    static int waitId(PcGuard.Confirms cf) throws Exception {
        for (int i = 0; i < 2000 && cf.list().isEmpty(); i++) Thread.sleep(5);
        assertFalse("请求应该在等确认", cf.list().isEmpty());
        return ((Number) ((Map<?, ?>) cf.list().get(0)).get("id")).intValue();
    }

    // ================================================================== 1. 防误点（纯逻辑，假时钟）

    @Test public void allowArmsOnlyAfterShownPlusDelay() {
        ConfirmGate g = new ConfirmGate(0, 30_000);
        assertEquals("还没显示，允许不算", NOT_SHOWN, g.check(500, true, MOUSE));
        g.seen(1_000, true);
        assertFalse(g.armed(1_000));
        assertEquals(EARLY, g.check(1_000, true, MOUSE));
        assertEquals("键盘回车 / 空格同样要等", EARLY, g.check(1_000 + ConfirmGate.ARM_MS - 1, true, KEY));
        assertEquals(1, g.armIn(1_000 + ConfirmGate.ARM_MS - 1));
        assertTrue(g.armed(1_000 + ConfirmGate.ARM_MS));
        assertEquals(OK, g.check(1_000 + ConfirmGate.ARM_MS, true, MOUSE));
        assertEquals(OK, g.check(1_000 + ConfirmGate.ARM_MS, true, KEY));
        assertEquals("0.7 秒", 700L, ConfirmGate.ARM_MS);
    }

    @Test public void refocusRearmsAndUnfocusedNeverArms() {
        ConfirmGate g = new ConfirmGate(0, 30_000);
        g.seen(0, true);
        assertEquals(OK, g.check(1_000, true, MOUSE));
        g.window(1_100, false);
        assertEquals("窗口没焦点：允许不算", EARLY, g.check(1_150, true, MOUSE));
        g.window(1_200, true);
        assertEquals("切回来那一下不算", EARLY, g.check(1_200, true, MOUSE));
        assertEquals(EARLY, g.check(1_899, true, MOUSE));
        assertEquals(OK, g.check(1_900, true, MOUSE));

        ConfirmGate u = new ConfirmGate(0, 30_000);
        u.seen(0, false); // 请求来的时候你在用别的程序
        assertEquals(EARLY, u.check(5_000, true, MOUSE));
        u.window(5_000, true);
        assertEquals(EARLY, u.check(5_699, true, MOUSE));
        assertEquals(OK, u.check(5_700, true, MOUSE));
    }

    @Test public void confirmsRejectEarlyAllowAndKeepWaiting() throws Exception {
        AtomicLong t = new AtomicLong(10_000);
        PcGuard.Confirms cf = fake(t);
        CompletableFuture<Integer> r = askAsync(cf, 30_000, false);
        int id = waitId(cf);
        assertFalse("没显示就点允许 = 不算", cf.resolve(id, true));
        assertTrue(cf.seen(id, true));
        assertEquals(EARLY, cf.answer(id, true, false, MOUSE));
        t.addAndGet(ConfirmGate.ARM_MS - 1);
        assertEquals(EARLY, cf.answer(id, true, false, KEY));
        assertFalse("没收的回答不结束请求", r.isDone());
        assertEquals(Boolean.FALSE, ((Map<?, ?>) cf.list().get(0)).get("armed"));
        t.addAndGet(1);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) cf.list().get(0)).get("armed"));
        assertEquals(OK, cf.answer(id, true, false, KEY));
        assertEquals(PcGuard.ALLOW, (int) r.get(2, TimeUnit.SECONDS));
        assertEquals("已回答的不能再点", GONE, cf.answer(id, true, false, MOUSE));
    }

    // ================================================================== 2. 默认拒绝

    @Test public void denyAcceptedFromAnywhereAnytime() {
        ConfirmGate g = new ConfirmGate(0, 30_000);
        assertEquals("拒绝不用等、不看来源", OK, g.check(0, false, VOICE));
        assertEquals(OK, g.check(0, false, REMOTE));
        assertEquals(OK, g.check(0, false, MOUSE));
    }

    @Test public void gateExpiryRules() {
        ConfirmGate g = new ConfirmGate(0, 30_000);
        assertNull(g.expired(29_999));
        assertEquals(ConfirmGate.END_TIMEOUT, g.expired(30_000));
        assertEquals("超时后点也不算", GONE, g.check(30_000, true, MOUSE));

        ConfirmGate b = new ConfirmGate(0, 30_000);
        b.seen(0, true);
        b.window(1_000, false);
        b.uiAlive(6_000); // 界面还在（只是失焦）
        assertNull(b.expired(1_000 + ConfirmGate.BLUR_DENY_MS - 1));
        assertEquals("失焦后没人点 = 拒绝", ConfirmGate.END_BLUR, b.expired(1_000 + ConfirmGate.BLUR_DENY_MS));
        ConfirmGate b2 = new ConfirmGate(0, 30_000);
        b2.seen(0, true);
        b2.window(1_000, false);
        b2.window(5_000, true);
        assertNull("切回来就不算失焦", b2.expired(1_000 + ConfirmGate.BLUR_DENY_MS + 1));

        ConfirmGate n = new ConfirmGate(0, 30_000);
        n.seen(0, false); // 显示时就没焦点（你在别处）：不套失焦规则，等到 30 秒
        n.window(100, false);
        for (long x = 2_000; x < 30_000; x += 2_000) n.uiAlive(x);
        assertNull(n.expired(29_000));

        ConfirmGate ui = new ConfirmGate(0, 30_000);
        ui.seen(0, false);
        ui.uiAlive(5_000);
        assertNull(ui.expired(5_000 + ConfirmGate.UI_GONE_MS - 1));
        assertEquals("界面没动静 = 窗口关了 = 拒绝", ConfirmGate.END_UI_GONE, ui.expired(5_000 + ConfirmGate.UI_GONE_MS));

        ConfirmGate never = new ConfirmGate(0, 30_000);
        assertNull("没有界面（托盘模式）：不套界面规则，等到超时", never.expired(20_000));

        ConfirmGate c = new ConfirmGate(0, 30_000);
        c.seen(0, true);
        c.close();
        assertEquals(ConfirmGate.END_CLOSED, c.expired(1_000));
        assertEquals(GONE, c.check(5_000, true, MOUSE));
        assertFalse(c.armed(5_000));
    }

    @Test public void confirmsBlurAndUiGoneAndTimeoutDeny() throws Exception {
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = fake(t);
        CompletableFuture<Integer> r = askAsync(cf, 30_000, false);
        int id = waitId(cf);
        cf.seen(id, true);
        cf.window(false);
        assertFalse(cf.windowFocused());
        t.addAndGet(ConfirmGate.BLUR_DENY_MS);
        assertEquals("失焦后没人点", PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));
        assertTrue(cf.list().isEmpty());

        r = askAsync(cf, 30_000, false);
        id = waitId(cf);
        cf.seen(id, false);
        t.addAndGet(5_000);
        cf.uiAlive();
        t.addAndGet(5_000);
        Thread.sleep(50);
        assertFalse("界面还在报到，继续等", r.isDone());
        t.addAndGet(ConfirmGate.UI_GONE_MS);
        assertEquals("界面没动静", PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));

        r = askAsync(cf, 30_000, false);
        waitId(cf);
        t.addAndGet(30_000);
        assertEquals("超时", PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));
    }

    @Test public void closeWindowAndEscDeny() throws Exception {
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = fake(t);
        CompletableFuture<Integer> r = askAsync(cf, 30_000, true);
        int id = waitId(cf);
        cf.seen(id, true);
        assertEquals("关窗口：所有在等的都拒", 1, cf.closeAll());
        assertEquals(PcGuard.DENY, (int) r.get(2, TimeUnit.SECONDS));
        assertEquals(GONE, cf.answer(id, true, true, MOUSE));

        // Esc：界面发 allow=false（键盘）
        r = askAsync(cf, 30_000, true);
        id = waitId(cf);
        Map<String, Object> res = App.confirmApi(cf, m("id", (long) id, "allow", false, "input", "keyboard", "trusted", true), s -> { });
        assertEquals(Boolean.TRUE, res.get("ok"));
        assertEquals(PcGuard.DENY, (int) r.get(2, TimeUnit.SECONDS));

        // 界面报 closed
        r = askAsync(cf, 30_000, false);
        waitId(cf);
        StringBuilder log = new StringBuilder();
        App.confirmApi(cf, m("op", "closed"), log::append);
        assertEquals(PcGuard.DENY, (int) r.get(2, TimeUnit.SECONDS));
        assertTrue(log.toString(), log.toString().contains("按拒绝"));
    }

    @Test public void anyErrorIsDeny() throws Exception {
        // 通知出错：照样等，不会变成允许
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = fake(t);
        cf.onNew = d -> { throw new IllegalStateException("toast broke"); };
        CompletableFuture<Integer> r = askAsync(cf, 30_000, false);
        waitId(cf);
        t.addAndGet(30_000);
        assertEquals(PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));

        // 等待线程被打断 = 没人确认
        PcGuard.Confirms cf2 = new PcGuard.Confirms();
        Thread.currentThread().interrupt();
        assertEquals(PcGuard.TIMEOUT, cf2.ask("phone", "volume", "x", 5_000));
        assertTrue(Thread.interrupted());

        // 确认框自己抛异常 / 回奇怪的值：PcGuard 按拒绝，不执行
        Config c = PcGuardTest.cfg("auto");
        for (PcGuard.Confirmer bad : List.<PcGuard.Confirmer>of(
                (from, cmd, detail, ms) -> { throw new RuntimeException("ui crashed"); },
                (from, cmd, detail, ms) -> 42)) {
            PcGuard g = new PcGuard(c, bad);
            PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", "dir"));
            assertNotNull("出错没放行", o.reply);
            assertFalse(o.reply.ok);
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, PcGuardTest.reason(o));
        }
        // 界面接口：参数坏了 = 不算
        assertEquals(Boolean.FALSE, App.confirmApi(cf, m("id", "x", "allow", true), s -> { }).get("ok"));
        assertEquals(Boolean.FALSE, App.confirmApi(cf, m("op", "hack"), s -> { }).get("ok"));
    }

    // ================================================================== 3. 说话 / 远程消息不能批准

    @Test public void onlyLocalMouseOrKeyboardCanAllow() throws Exception {
        assertTrue(ConfirmGate.Input.of("mouse").local());
        assertTrue(ConfirmGate.Input.of("KEYBOARD").local());
        for (Object o : new Object[]{null, "", "voice", "remote", "phone", "touch-remote", 1})
            assertFalse(String.valueOf(o), ConfirmGate.Input.of(o).local());
        ConfirmGate g = new ConfirmGate(0, 30_000);
        g.seen(0, true);
        assertEquals("已解锁也不收语音", NOT_LOCAL, g.check(5_000, true, VOICE));
        assertEquals(NOT_LOCAL, g.check(5_000, true, REMOTE));
        assertEquals(NOT_LOCAL, g.check(5_000, true, ConfirmGate.Input.UNKNOWN));
        assertEquals(NOT_LOCAL, g.check(5_000, true, null));

        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = fake(t);
        CompletableFuture<Integer> r = askAsync(cf, 30_000, true);
        int id = waitId(cf);
        StringBuilder log = new StringBuilder();
        Map<String, Object> seen = App.confirmApi(cf, m("op", "seen", "id", (long) id, "focused", true), log::append);
        assertEquals(Boolean.TRUE, seen.get("ok"));
        assertEquals(ConfirmGate.ARM_MS, seen.get("arm_ms"));
        t.addAndGet(2_000);
        // 语音 / 远程 / 不是真实输入（脚本合成的点击）/ 没说来源：都回 voice_approval_blocked，请求照旧等
        for (Map<String, Object> b : List.of(
                m("id", (long) id, "allow", true, "input", "voice", "trusted", true),
                m("id", (long) id, "allow", true, "always", true, "input", "remote", "trusted", true),
                m("id", (long) id, "allow", true, "input", "mouse", "trusted", false),
                m("id", (long) id, "allow", true, "input", "mouse"),
                m("id", (long) id, "allow", true))) {
            Map<String, Object> res = App.confirmApi(cf, b, log::append);
            assertEquals(b.toString(), Boolean.FALSE, res.get("ok"));
            assertEquals(b.toString(), LinkPolicy.R_VOICE_APPROVAL_BLOCKED, res.get("reason"));
        }
        assertFalse(r.isDone());
        assertTrue(log.toString(), log.toString().contains(LinkPolicy.R_VOICE_APPROVAL_BLOCKED));
        Map<String, Object> ok = App.confirmApi(cf, m("id", (long) id, "allow", true, "always", true, "input", "mouse", "trusted", true), log::append);
        assertEquals(Boolean.TRUE, ok.get("ok"));
        assertEquals(PcGuard.ALWAYS, (int) r.get(2, TimeUnit.SECONDS));

        // 太早点（界面倒计时没走完）：not_armed，请求照旧
        r = askAsync(cf, 30_000, false);
        id = waitId(cf);
        App.confirmApi(cf, m("op", "seen", "id", (long) id, "focused", true), s -> { });
        assertEquals("not_armed", App.confirmApi(cf, m("id", (long) id, "allow", true, "input", "keyboard", "trusted", true), s -> { }).get("reason"));
        App.confirmApi(cf, m("op", "window", "focused", false), s -> { });
        t.addAndGet(5_000);
        assertEquals("窗口没焦点不收", "not_armed", App.confirmApi(cf, m("id", (long) id, "allow", true, "input", "mouse", "trusted", true), s -> { }).get("reason"));
        App.confirmApi(cf, m("op", "window", "focused", true), s -> { });
        assertEquals("切回来重新等", "not_armed", App.confirmApi(cf, m("id", (long) id, "allow", true, "input", "mouse", "trusted", true), s -> { }).get("reason"));
        t.addAndGet(ConfirmGate.ARM_MS);
        assertEquals(Boolean.TRUE, App.confirmApi(cf, m("id", (long) id, "allow", true, "input", "mouse", "trusted", true), s -> { }).get("ok"));
        assertEquals(PcGuard.ALLOW, (int) r.get(2, TimeUnit.SECONDS));
    }

    @Test public void remoteApprovalCommandsBlockedInEveryMode() {
        String[] names = {"confirm", "approve", "allow", "voice_approve", "voice_confirm", "confirm_allow", "approval", "Allow_Always"};
        for (String n : names) {
            assertTrue(n, LinkPolicy.isRemoteApproval(n));
            assertEquals(n, LinkPolicy.Cat.REMOTE_APPROVAL, LinkPolicy.pcCat(n));
            assertEquals(n, LinkPolicy.Cat.REMOTE_APPROVAL, LinkPolicy.phoneCat(n));
        }
        for (String n : new String[]{"shutdown", "sys_exec", "notify", "ping", null}) assertFalse(String.valueOf(n), LinkPolicy.isRemoteApproval(n));
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values())
            for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(sb, ap), LinkPolicy.Cat.REMOTE_APPROVAL, new LinkPolicy.Facts());
                assertEquals(sb + "/" + ap, LinkPolicy.Verdict.DENY, d.verdict);
                assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, d.reason);
            }
        LinkPolicy.Decision p = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.REMOTE_APPROVAL, null);
        assertEquals(LinkPolicy.Verdict.DENY, p.verdict);
        assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, p.reason);
        assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, LinkPolicy.phoneLegacy(LinkPolicy.Cat.REMOTE_APPROVAL, new LinkPolicy.Facts()).reason);
        assertEquals("voice_approval_blocked", LinkPolicy.R_VOICE_APPROVAL_BLOCKED);
        assertFalse(LinkPolicy.reasonZh(LinkPolicy.R_VOICE_APPROVAL_BLOCKED).isEmpty());
    }

    @Test public void pcGuardBlocksRemoteApprovalAndPhoneConfirmFlagDoesNotSkipDialog() throws Exception {
        PcGuardTest.Stub stub = new PcGuardTest.Stub();
        stub.answer = PcGuard.ALLOW;
        Config c = PcGuardTest.cfg("auto");
        PcGuard g = new PcGuard(c, stub);
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values())
            for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                c.setMode(new LinkPolicy.Mode(sb, ap));
                PcGuard.Outcome o = g.check("phone", "approve", m("id", 1L, "allow", true, "always", true));
                assertEquals(sb + "/" + ap, LinkPolicy.R_VOICE_APPROVAL_BLOCKED, PcGuardTest.reason(o));
                o = g.check("phone", "voice_confirm", m("text", "允许"));
                assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, PcGuardTest.reason(o));
            }
        assertTrue("远程批准指令从不弹确认框", stub.asked.isEmpty());
        // 手机那边点过「确认」（confirm=true）不能代替电脑上的确认：要确认的档位照样在电脑上问
        c.setMode(LinkPolicy.Mode.DEFAULT);
        stub.answer = PcGuard.TIMEOUT;
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, PcGuardTest.reason(g.check("phone", "shutdown", m("confirm", true, "approved", true, "voice", "确认关机"))));
        assertEquals(1, stub.asked.size());
    }

    @Test public void spokenApprovalNeverAnswersTheDialog() {
        for (String s : new String[]{"允许", "允许。", "好的", "OK", "ok!", "那就允许吧", "同意", "批准", "以后直接执行", "确认", "Yes", "可以啊"})
            assertTrue(s, ConfirmGate.spokenApproval(s));
        for (String s : new String[]{"", null, "确认关机", "打开B站", "允许手机访问电脑上的照片文件夹吗", "明天北京冷不冷"})
            assertFalse(String.valueOf(s), ConfirmGate.spokenApproval(s));
        assertNotNull("有确认框在等：拦下", Voice.spokenApprovalBlocked("允许", () -> true));
        assertNull("没有确认框：照常走", Voice.spokenApprovalBlocked("好的", () -> false));
        assertNull("具体的本机指令照常走", Voice.spokenApprovalBlocked("确认关机", () -> true));
        assertNotNull("判断出错：宁可拦下", Voice.spokenApprovalBlocked("允许", () -> { throw new IllegalStateException(); }));
    }

    @Test public void uiCardFollowsTheRules() throws Exception {
        String html = new String(UiServer.resource("onesay/ui/index.html"), StandardCharsets.UTF_8);
        assertTrue("允许按钮一开始禁用", html.contains("cfallow\" disabled"));
        assertTrue("焦点默认在拒绝", html.contains("$('cfdeny')") && html.contains("d.focus()"));
        assertTrue("Esc = 拒绝", html.contains("e.key==='Escape'&&cfId") && html.contains("cfAns(cfId,false"));
        assertTrue("关窗口 = 拒绝", html.contains("'pagehide'") && html.contains("op:'closed'"));
        assertTrue("失焦锁住、切回来重新等", html.contains("addEventListener('blur'") && html.contains("addEventListener('focus'"));
        assertTrue("只认真实输入", html.contains("isTrusted") && html.contains("ev.detail===0?'keyboard':'mouse'"));
        assertTrue("先报 seen 再计时", html.contains("op:'seen'"));
        assertFalse(html.contains("Bixby"));
        assertFalse(html.contains("三星"));
    }

    /** 端到端：手机（另一端）经局域网发「批准」类指令 → voice_approval_blocked，电脑上在等的确认不受影响。 */
    @Test public void endToEndRemoteApprovalRefused() throws Exception {
        int port = LanTest.freePortPair();
        Config pcCfg = LanTest.pc(port);
        pcCfg.workspaceDir = Files.createTempDirectory("ag-e2e").toFile().getCanonicalPath();
        StringBuilder log = new StringBuilder();
        Agent agent = new Agent(pcCfg, new Main.TestActions(), x -> { synchronized (log) { log.append(x).append('\n'); } });
        AtomicInteger prompts = new AtomicInteger();
        agent.confirms.onNew = d -> prompts.incrementAndGet();
        agent.start();
        Thread.sleep(300);
        Path out = Files.createTempDirectory("ag-e2e-out");
        try {
            Config cli = LanTest.client(pcCfg, "127.0.0.1:" + port);
            CompletableFuture<Integer> held = new CompletableFuture<>();
            Thread th = new Thread(() -> { try { held.complete(Cli.send(cli, "pc-lan", "volume", m("action", "up"), out, 15)); } catch (Exception e) { held.completeExceptionally(e); } });
            th.setDaemon(true);
            th.start();
            int id = waitId(agent.confirms);
            agent.confirms.seen(id, true);
            Thread alive = new Thread(() -> { // 像界面那样每 0.5 秒报到一次
                while (!held.isDone()) { agent.confirms.uiAlive(); try { Thread.sleep(500); } catch (InterruptedException e) { return; } }
            });
            alive.setDaemon(true);
            alive.start();
            Thread.sleep(ConfirmGate.ARM_MS + 50);
            for (String n : new String[]{"confirm", "approve", "voice_approve"})
                assertEquals(n, 1, Cli.send(cli, "pc-lan", n, m("id", (long) id, "allow", true), out, 10));
            // （同一台手机再发指令时，测试客户端前一条的等待可能先断开；这里看的是电脑端：那条请求还在等，没被批准）
            if (held.isDone()) assertEquals("远程批准没让它通过", 1, (int) held.getNow(-9));
            assertEquals(1, agent.confirms.list().size());
            String l;
            synchronized (log) { l = log.toString(); }
            assertTrue(l, l.contains(LinkPolicy.R_VOICE_APPROVAL_BLOCKED));
            assertEquals(1, prompts.get());
            assertEquals(OK, agent.confirms.answer(id, false, false, KEY)); // 本机键盘拒绝
            for (int i = 0; i < 400; i++) { synchronized (log) { if (log.toString().contains("没执行 volume")) break; } Thread.sleep(10); }
            synchronized (log) { l = log.toString(); }
            assertTrue(l, l.contains("没执行 volume") && l.contains(LinkPolicy.R_CONFIRM_DENIED));
            assertTrue(agent.confirms.list().isEmpty());
        } finally {
            agent.stop();
        }
    }
}
