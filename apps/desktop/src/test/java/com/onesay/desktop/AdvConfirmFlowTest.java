package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 确认卡的结果怎么落地（PcGuard#check 的 CONFIRM 分支）：
 * <ul>
 *   <li>允许 / 以后直接执行 / 以后这类不用问 → 发一次性凭证；其余任何返回值、确认框抛异常 → confirm_denied；超时 / 没有确认框 → needs_confirm_timeout。</li>
 *   <li>「以后这类不用问」按动作类型记住：下次同类不再问（带提醒）；撤销后又问；记住 A 类不影响 B 类。</li>
 *   <li>说话不算批准（ConfirmGate#spokenApproval）；界面传来的输入来源只认 mouse / keyboard 且 trusted。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvConfirmFlowTest {

    static final LinkPolicy.Mode WS_UN = new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.UNTRUSTED),
            WS_OR = new LinkPolicy.Mode(LinkPolicy.Sandbox.WORKSPACE_WRITE, LinkPolicy.Approval.ON_REQUEST),
            FA_OR = new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.ON_REQUEST),
            FA_UN = new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.UNTRUSTED);

    /** 会弹确认的一条：档位、指令、参数、期望的动作类型、额外准备（script = 工作区里放一个脚本；missing = 下载工具没装）。 */
    static final class Case {
        final String id, cmd, kind, setup;
        final LinkPolicy.Mode mode;
        final Object[] args;

        Case(String id, LinkPolicy.Mode mode, String cmd, String kind, String setup, Object... args) {
            this.id = id;
            this.mode = mode;
            this.cmd = cmd;
            this.kind = kind;
            this.setup = setup;
            this.args = args;
        }

        @Override public String toString() { return id; }
    }

    static final Case[] CASES = {
            new Case("shell-un", WS_UN, "sys_exec", "pc.shell_exec", "", "command", "Get-Date"),
            new Case("shell-or", WS_OR, "sys_exec", "pc.shell_exec", "", "command", "dir"),
            new Case("volume", WS_UN, "volume", "pc.setting", "", "action", "up"),
            new Case("brightness-full", FA_OR, "brightness", "pc.setting", "", "level", 50L),
            new Case("shutdown", WS_UN, "shutdown", "pc.power", ""),
            new Case("sleep-or", WS_OR, "sleep", "pc.power", ""),
            new Case("uninstall", WS_UN, "uninstall_app", "pc.uninstall", "", "name", "Foo", "path", "C:\\Program Files\\Foo\\foo.exe"),
            new Case("script", WS_UN, "script_run", "pc.script_run", "script", "name", "hello.ps1"),
            new Case("outside", WS_UN, "file_write", "pc.outside_write", "", "path", "C:\\Users\\me\\Desktop\\note.txt", "text", "hi"),
            new Case("url", WS_UN, "open_url", "pc.url", "", "url", "https://example.org/"),
            new Case("url-risky", WS_OR, "open_url", "pc.url.risky", "", "url", "http://192.168.1.1/"),
            new Case("clip", WS_UN, "clipboard_set", "pc.clipboard", "", "text", "hello"),
            new Case("clip-cmd", WS_OR, "clipboard_set", "pc.clipboard.command", "", "text", "rm -rf /"),
            new Case("clip-otp", WS_OR, "clipboard_set", "pc.clipboard.sensitive", "", "text", "验证码 123456"),
            new Case("pay-app", WS_UN, "open_app", "pc.blocked_app.open", "", "name", "支付宝"),
            new Case("pw-uninstall", WS_UN, "uninstall_app", "pc.blocked_app.uninstall", "", "name", "KeePass"),
            new Case("pw-close", FA_UN, "app_close", "pc.blocked_app.operate", "", "name", "Bitwarden"),
            new Case("sys-app", WS_UN, "uninstall_app", "pc.system_app", "", "name", "记事本", "path", "C:\\Windows\\System32\\notepad.exe"),
            new Case("git", WS_UN, "file_write", "pc.workspace.protected", "", "path", ".git/config", "text", "[core]"),
            new Case("media", WS_UN, "media_get", "pc.media_download", "", "url", "https://www.youtube.com/watch?v=abc&t=10"),
            new Case("media-tools", WS_OR, "media_get", "pc.media_tool_install", "missing", "url", "https://www.bilibili.com/video/BV1xx?p=2")};

    static final int THROW = 1001, NONE = 1002;
    static final int[] RESULTS = {PcGuard.ALLOW, PcGuard.DENY, PcGuard.TIMEOUT, PcGuard.ALWAYS, PcGuard.REMEMBER, 7, -9, THROW, NONE};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("flow");
        for (Case c : CASES)
            for (int res : RESULTS) r.add("result " + c + " → " + res, "result", c, res, null, null);
        for (Case c : CASES) r.add("remember-forget " + c, "cycle", c, 0, null, null);
        for (Case c : CASES)
            for (String k : AdvSupport.ALL_KINDS) r.add("cross " + c + " remembered=" + k, "cross", c, 0, k, null);
        // 说话不算批准
        String[] yes = {"允许", "允许一次", "允许执行", "同意", "批准", "确认", "确定", "确认执行", "确认允许", "可以", "行", "好", "好的", "是", "是的", "对", "执行",
                "继续", "通过", "放行", "以后直接执行", "直接执行", "点允许", "点同意", "帮我点允许", "帮我允许", "帮我批准", "allow", "yes", "ok", "okay", "approve",
                "confirm", "sure", "go", "goahead", "doit", "允许。", "好的吧", "嗯，允许", "那就确认吧！", "OK!", "Do it.", "go ahead", "请批准", "Yes", "  好  ",
                "可以啊", "批准了", "确定吗", "ALLOW", "Approve!!", "嗯嗯好的呀"};
        for (String s : yes) r.add("spoken yes [" + s + "]", "spoken", null, 0, s, Boolean.TRUE);
        String[] no = {"确认关机", "允许打开浏览器", "不允许", "拒绝", "不要", "取消", "", "好的我知道了然后呢", "allow me to explain", "确认一下明天的天气", "执行关机命令",
                "no", "nope", "停", "允许允许允许允许允许", "批准一下这个报销单", "yes please open notepad", "ok google"};
        for (String s : no) r.add("spoken no [" + s + "]", "spoken", null, 0, s, Boolean.FALSE);
        r.add("spoken no [null]", "spoken", null, 0, null, Boolean.FALSE);
        // 界面传来的 input 字段
        Object[][] ins = {{"mouse", "MOUSE"}, {"MOUSE", "MOUSE"}, {" Mouse ", "MOUSE"}, {"keyboard", "KEYBOARD"}, {"KEYBOARD\n", "KEYBOARD"},
                {"\tkeyboard\t", "KEYBOARD"}, {"voice", "VOICE"}, {"Voice", "VOICE"}, {"remote", "REMOTE"}, {"phone_touch", "UNKNOWN"}, {"touch", "UNKNOWN"},
                {"PHONE_TOUCH", "UNKNOWN"}, {"", "UNKNOWN"}, {"mouse\u200b", "UNKNOWN"}, {"mous", "UNKNOWN"}, {"mouse,keyboard", "UNKNOWN"},
                {"ｍｏｕｓｅ", "UNKNOWN"}, {"robot", "UNKNOWN"}};
        for (Object[] i : ins) r.add("input [" + i[0] + "]", "input", null, 0, i[0], i[1]);
        r.add("input [null]", "input", null, 0, null, "UNKNOWN");
        r.add("input [Integer 1]", "input-obj", null, 1, null, "UNKNOWN");
        r.add("input [Boolean true]", "input-obj", null, 2, null, "UNKNOWN");
        // 界面 /api/confirm（App.confirmApi）：trusted × input × 允许；只有 trusted + mouse/keyboard 的允许算数
        for (boolean trusted : new boolean[]{true, false})
            for (String in : new String[]{"mouse", "keyboard", "voice", "remote", "phone_touch", ""})
                r.add("api allow trusted=" + trusted + " input=" + in, "api", null, trusted ? 1 : 0, in, Boolean.valueOf(trusted && (in.equals("mouse") || in.equals("keyboard"))));
        r.add("api deny untrusted voice", "api-deny", null, 0, "voice", Boolean.TRUE);
        r.add("api deny trusted mouse", "api-deny", null, 1, "mouse", Boolean.TRUE);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public Case c;
    @Parameterized.Parameter(3) public int res;
    @Parameterized.Parameter(4) public String text;
    @Parameterized.Parameter(5) public Object want;

    static final Map<String, Object[]> ENV = new HashMap<>();

    /** 每个 case 一份配置 + 守卫（同一 case 的行按顺序跑，记住的类型每行开头重置）。 */
    static synchronized Object[] env(Case c) throws Exception {
        Object[] e = ENV.get(c.id);
        if (e == null) {
            Config cfg = AdvSupport.cfg(c.mode);
            if (c.setup.equals("script")) {
                File s = new File(cfg.workspaceDir, "scripts");
                s.mkdirs();
                Files.write(new File(s, "hello.ps1").toPath(), "Write-Host 你好".getBytes(StandardCharsets.UTF_8));
            }
            PcGuard g = new PcGuard(cfg, null);
            if (c.setup.equals("missing")) g.mediaToolsMissing = () -> true;
            e = new Object[]{cfg, g};
            ENV.put(c.id, e);
        }
        ((Config) e[0]).approveKinds = "";
        ((Config) e[0]).shortcutApprovals = "";
        return e;
    }

    PcGuard.Outcome run(PcGuard g, AdvSupport.Stub st) {
        g.confirmer = st;
        return g.check("phone-a", c.cmd, AdvSupport.m(c.args));
    }

    @Test public void flow() throws Exception {
        switch (kind) {
            case "result": result(); break;
            case "cycle": cycle(); break;
            case "cross": cross(); break;
            case "spoken": assertEquals(name, want, ConfirmGate.spokenApproval(text)); break;
            case "input": {
                ConfirmGate.Input in = ConfirmGate.Input.of(text);
                assertEquals(name, want, in.name());
                assertEquals(name, in == ConfirmGate.Input.MOUSE || in == ConfirmGate.Input.KEYBOARD, in.local());
                break;
            }
            case "input-obj":
                assertEquals(name, want, ConfirmGate.Input.of(res == 1 ? (Object) Integer.valueOf(1) : Boolean.TRUE).name());
                break;
            case "api": api(false); break;
            case "api-deny": api(true); break;
            default: throw new AssertionError(kind);
        }
    }

    void result() throws Exception {
        Object[] e = env(c);
        Config cfg = (Config) e[0];
        PcGuard g = (PcGuard) e[1];
        AdvSupport.Stub st = new AdvSupport.Stub(res);
        st.throwIt = res == THROW;
        g.confirmer = res == NONE ? null : st;
        PcGuard.Outcome o = g.check("phone-a", c.cmd, AdvSupport.m(c.args));
        if (res != NONE) {
            assertEquals(name + " 只问一次", 1, st.asked.size());
            assertEquals(name + " 卡上给的动作类型", c.kind, st.kinds.get(0));
            assertFalse("不是快捷指令：不给「以后直接执行」", st.offers.get(0));
            assertTrue("确认框里写明是谁请求的", st.asked.get(0).contains("phone-a 请求："));
        }
        if (res == PcGuard.ALLOW || res == PcGuard.ALWAYS || res == PcGuard.REMEMBER) {
            assertNull(name + " 应放行：" + (o.reply == null ? "" : o.reply.text), o.reply);
            assertNotNull(o.pass);
            assertTrue("凭证能用一次", o.pass.consume(c.cmd, o.args));
            assertFalse("凭证只能用一次", o.pass.consume(c.cmd, o.args));
            assertEquals(name, res == PcGuard.REMEMBER ? c.kind : "", cfg.approveKinds);
            if (c.kind.equals("pc.workspace.protected")) assertEquals(Boolean.TRUE, o.args.get("_protected_ok"));
            if (c.setup.equals("missing")) assertEquals(Boolean.TRUE, o.args.get("_media_install_ok"));
        } else {
            assertNotNull(name + " 应拒绝", o.reply);
            assertNull(o.pass);
            String want = res == PcGuard.TIMEOUT || res == NONE ? LinkPolicy.R_CONFIRM_TIMEOUT : LinkPolicy.R_CONFIRM_DENIED;
            assertEquals(name, want, AdvSupport.reason(o));
            assertEquals("没批就不记住", "", cfg.approveKinds);
        }
    }

    void cycle() throws Exception {
        Object[] e = env(c);
        Config cfg = (Config) e[0];
        PcGuard g = (PcGuard) e[1];
        PcGuard.Outcome o1 = run(g, new AdvSupport.Stub(PcGuard.REMEMBER));
        assertNull(o1.reply);
        assertEquals(List.of(c.kind), g.rememberedKinds());
        assertEquals("设置页能看到", c.kind, ((Map<?, ?>) g.rememberedView().get(0)).get("kind"));
        AdvSupport.Stub silent = new AdvSupport.Stub(PcGuard.DENY);
        PcGuard.Outcome o2 = run(g, silent);
        assertEquals("记住后不再问", 0, silent.asked.size());
        assertNull(o2.reply);
        assertNotNull(o2.pass);
        assertEquals(c.kind, o2.rememberedKind);
        assertTrue("直接执行要提醒", o2.headsUp);
        assertTrue(o2.headsText.startsWith("按「以后这类不用问」直接执行："));
        assertTrue("能撤销", g.forget(c.kind));
        assertFalse("撤销两次：第二次没有", g.forget(c.kind));
        assertEquals("", cfg.approveKinds);
        AdvSupport.Stub again = new AdvSupport.Stub(PcGuard.DENY);
        PcGuard.Outcome o3 = run(g, again);
        assertEquals("撤销后又问", 1, again.asked.size());
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, AdvSupport.reason(o3));
        // 存盘后重新读出来也对
        assertTrue(g.remember(c.kind));
        Config back = Config.load(cfg.file);
        assertEquals(c.kind, back.approveKinds);
        g.forget(c.kind);
    }

    void cross() throws Exception {
        Object[] e = env(c);
        Config cfg = (Config) e[0];
        PcGuard g = (PcGuard) e[1];
        cfg.approveKinds = text;
        AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
        PcGuard.Outcome o = run(g, st);
        if (text.equals(c.kind)) {
            assertEquals(name + " 记住的就是这类：不问", 0, st.asked.size());
            assertNull(o.reply);
            assertEquals(c.kind, o.rememberedKind);
        } else {
            assertEquals(name + " 记住的是别的类：照样问", 1, st.asked.size());
            assertEquals(LinkPolicy.R_CONFIRM_DENIED, AdvSupport.reason(o));
            assertEquals("", o.rememberedKind);
        }
        cfg.approveKinds = "";
    }

    /** 界面 /api/confirm：确认框在另一条线程里等，假时钟推过 0.7 秒再回答。 */
    void api(boolean deny) throws Exception {
        PcGuard.Confirms cf = new PcGuard.Confirms();
        AtomicLong now = new AtomicLong(10_000);
        cf.clock = now::get;
        cf.tickMs = 2;
        AtomicInteger got = new AtomicInteger(Integer.MIN_VALUE);
        Thread t = new Thread(() -> got.set(cf.ask("phone-a", "sys_exec", "执行命令：dir", LinkPolicy.CONFIRM_TIMEOUT_MS)));
        t.start();
        long end = System.currentTimeMillis() + 5_000;
        while (cf.list().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(1);
        long id = (Long) ((Map<?, ?>) cf.list().get(0)).get("id");
        java.util.function.Consumer<String> log = s -> { };
        Map<String, Object> seen = App.confirmApi(cf, AdvSupport.m("op", "seen", "id", id, "focused", true), log);
        assertEquals(Boolean.TRUE, seen.get("ok"));
        now.addAndGet(1_000);
        Map<String, Object> ans = App.confirmApi(cf, AdvSupport.m("id", id, "allow", !deny, "input", text, "trusted", res == 1), log);
        if (deny || Boolean.TRUE.equals(want)) {
            assertEquals(name + "：" + ans, Boolean.TRUE, ans.get("ok"));
            t.join(5_000);
            assertEquals(name, deny ? PcGuard.DENY : PcGuard.ALLOW, got.get());
        } else {
            assertEquals(name + "：" + ans, Boolean.FALSE, ans.get("ok"));
            assertEquals(LinkPolicy.R_VOICE_APPROVAL_BLOCKED, ans.get("reason"));
            assertEquals("没算数：请求还在等", 1, cf.list().size());
            App.confirmApi(cf, AdvSupport.m("id", id, "allow", false, "input", "voice"), log); // 拒绝从哪来都收：收尾
            t.join(5_000);
            assertEquals(PcGuard.DENY, got.get());
        }
    }
}
