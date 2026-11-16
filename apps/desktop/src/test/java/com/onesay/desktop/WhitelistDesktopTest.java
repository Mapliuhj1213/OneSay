package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Test;

/**
 * [app-whitelist-1116] 电脑端接线：PcGuard 的分层策略（白名单放行、支付 / 隐私外发仍拦、读过隐私后外发拦、可疑照旧确认、硬拒不变）、
 * 白名单存盘 / 审计、同步接口、界面接口。规则本身的用例在 {@link AppWhitelistSharedTest}（和手机同一份）。
 */
public class WhitelistDesktopTest {

    static final class Stub implements PcGuard.Confirmer {
        int answer = PcGuard.DENY;
        final List<String> asked = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            asked.add(cmd + "|" + detail);
            return answer;
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            kinds.add(rememberKind);
            return ask(from, cmd, detail, timeoutMs);
        }
    }

    static final class Env {
        final Config cfg;
        final Stub st = new Stub();
        final PcGuard g;
        final WhitelistStore w;
        final Path dir;

        Env(LinkPolicy.Sandbox sb, LinkPolicy.Approval ap) throws Exception {
            cfg = PcGuardTest.cfg("auto");
            cfg.setMode(new LinkPolicy.Mode(sb, ap));
            g = new PcGuard(cfg, st);
            dir = Files.createTempDirectory("wl");
            w = new WhitelistStore(dir);
            g.whitelist = w;
        }

        PcGuard.Outcome run(String cmd, Object... kv) { return g.check("ph-1", cmd, PcGuardTest.m(kv)); }

        String audit() throws Exception {
            Path p = dir.resolve("whitelist_audit.log");
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        }
    }

    static final LinkPolicy.Sandbox WS = LinkPolicy.Sandbox.WORKSPACE_WRITE, FA = LinkPolicy.Sandbox.FULL_ACCESS, RO = LinkPolicy.Sandbox.READ_ONLY;
    static final LinkPolicy.Approval UN = LinkPolicy.Approval.UNTRUSTED, NV = LinkPolicy.Approval.NEVER;

    static boolean passed(PcGuard.Outcome o) { return o.reply == null && o.pass != null; }

    @Test public void whitelistedProgramSkipsConfirm() throws Exception {
        Env e = new Env(WS, UN);
        e.st.answer = PcGuard.DENY;
        assertFalse("不在白名单：照旧问（问了、拒了）", passed(e.run("sys_exec", "command", "notepad.exe a.txt")));
        assertEquals(1, e.st.asked.size());
        assertNull(e.w.add("windows", "C:\\Windows\\notepad.exe", "记事本"));
        PcGuard.Outcome o = e.run("sys_exec", "command", "notepad.exe a.txt");
        assertTrue(passed(o));
        assertTrue(o.whitelisted);
        assertEquals("没再问", 1, e.st.asked.size());
        assertTrue(e.audit(), e.audit().contains("windows:notepad.exe\tWHITELIST\tALLOW\tsys_exec"));
        // 打开 / 关掉 程序（关掉第三方程序本来只在完全访问档；工作区档下的拒绝是沙箱档决定的，不放松）
        assertTrue(passed(e.run("open_app", "name", "notepad.exe")));
        assertFalse(passed(e.run("app_close", "name", "notepad.exe")));
    }

    @Test public void chainedOrUnknownCommandsAreNotWhitelisted() throws Exception {
        Env e = new Env(WS, UN);
        e.w.add("windows", "notepad.exe", "");
        for (String c : new String[]{"notepad.exe a & del b", "notepad.exe a | more", "notepad.exe $(x)", "notepad.exe a; rm b", "notepad.exe %X%",
                "notepad.exe > out", "cmd /c notepad.exe", "powershell notepad.exe", "\"C:\\x\\notepad.exe\" a && b", "notepad.exe start evil.exe"}) {
            int n = e.st.asked.size();
            assertFalse(c, e.run("sys_exec", "command", c).whitelisted);
            assertTrue(c + " 应当问（或被插件拒）", e.st.asked.size() > n || e.run("sys_exec", "command", c).reply != null);
        }
        // 带引号的完整路径照样认
        assertTrue(passed(e.run("sys_exec", "command", "\"C:\\Program Files\\x\\notepad.exe\" a.txt")));
    }

    @Test public void payAndPrivacyStillConfirmInWhitelist() throws Exception {
        Env e = new Env(FA, UN);
        e.w.add("windows", "alipay.exe", "支付宝");
        e.w.add("windows", "notepad.exe", "");
        e.st.answer = PcGuard.ALLOW;
        PcGuard.Outcome pay = e.run("open_app", "name", "alipay.exe");
        assertFalse(pay.whitelisted);
        assertEquals("支付类程序照样问", 1, e.st.asked.size());
        assertEquals("这张卡不能「以后这类不用问」", "", e.st.kinds.isEmpty() ? "" : e.st.kinds.get(0));
        // 命令参数里有隐私：要问，卡上写类别
        e.run("sys_exec", "command", "notepad.exe someone@example.com");
        assertEquals(2, e.st.asked.size());
        assertTrue(e.st.asked.get(1), e.st.asked.get(1).contains("隐私资料（邮箱）"));
        e.run("sys_exec", "command", "notepad.exe 11010519491231002X");
        assertTrue(e.st.asked.get(2).contains("身份证号"));
        String a = e.audit();
        assertFalse("审计里没有隐私原文", a.contains("someone@example.com") || a.contains("11010519491231002X"));
        assertTrue(a, a.contains("out=email"));
    }

    @Test public void afterReadingPrivacyOutboundAsks() throws Exception {
        Env e = new Env(FA, UN);
        e.w.add("windows", "wget.exe", "");
        e.w.add("windows", "notepad.exe", "");
        assertTrue("没读过隐私：白名单的联网命令放行", passed(e.run("sys_exec", "command", "wget.exe https://example.com/a")));
        e.w.noteRead("ph-1", PrivacyScan.text("我的手机 13812345678"));
        e.st.answer = PcGuard.DENY;
        assertFalse(passed(e.run("sys_exec", "command", "wget.exe https://example.com/a")));
        assertTrue(e.st.asked.get(0), e.st.asked.get(0).contains("读到过隐私资料（手机号）"));
        assertTrue("非外发照样放行", passed(e.run("sys_exec", "command", "notepad.exe a.txt")));
        assertTrue("别的手机不受影响", e.g.check("ph-2", "sys_exec", PcGuardTest.m("command", "wget.exe https://example.com/a")).whitelisted);
        // 会话过了 10 分钟没动静：重新算
        long[] now = {System.currentTimeMillis()};
        e.w.clock = () -> now[0];
        e.w.noteRead("ph-1", PrivacyScan.text("13812345678"));
        now[0] += WhitelistStore.SESSION_MS + 1;
        assertTrue(passed(e.run("sys_exec", "command", "wget.exe https://example.com/a")));
    }

    @Test public void suspectAndHardRulesUnchanged() throws Exception {
        Env e = new Env(WS, UN);
        e.w.add("windows", "git.exe", "");
        e.w.add("windows", "notepad.exe", "");
        e.st.answer = PcGuard.DENY;
        // 工作区档默认断网：认不准的可能联网命令 → 照旧每次问、不能记住
        assertFalse(passed(e.run("sys_exec", "command", "git.exe clone https://example.org/x")));
        assertEquals(1, e.st.asked.size());
        assertEquals("", e.st.kinds.isEmpty() ? "" : e.st.kinds.get(0));
        // 8.3 短名
        assertFalse(passed(e.run("sys_exec", "command", "git.exe -C PROGRA~1 status")));
        // 提权 / 要管理员：插件硬拒 / 只生成脚本，白名单放不松
        PcGuard.Outcome up = e.run("sys_exec", "command", "notepad.exe -Verb RunAs");
        PcGuard.Outcome adm = e.run("sys_exec", "command", "git.exe config --system x reg add HKLM\\Software\\X /f");
        for (PcGuard.Outcome o : new PcGuard.Outcome[]{up, adm}) assertFalse(o.whitelisted);
        // 只读档
        Env r = new Env(RO, UN);
        r.w.add("windows", "notepad.exe", "");
        assertNotNull(r.run("sys_exec", "command", "notepad.exe a").reply);
        // 从不询问：要确认的照旧拒（工作区档），不因白名单变成直接执行
        Env n = new Env(WS, NV);
        n.w.add("windows", "notepad.exe", "");
        PcGuard.Outcome no = n.run("sys_exec", "command", "notepad.exe a");
        assertNotNull(no.reply);
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, PcGuardTest.reason(no));
        // 远程加白名单的指令不收（自授权）
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, PcGuardTest.reason(e.run("whitelist_add", "id", "x.exe")));
        // 付款最后一下
        assertEquals(LinkPolicy.R_PAY_USER_TAP, PcGuardTest.reason(e.run("pay_confirm")));
    }

    @Test public void passLimitPerSession() throws Exception {
        Env e = new Env(WS, UN);
        e.w.add("windows", "notepad.exe", "");
        for (int i = 0; i < ActionPolicy.DEFAULT_PASS_LIMIT; i++) assertTrue("第 " + i + " 次", passed(e.run("sys_exec", "command", "notepad.exe a")));
        e.st.answer = PcGuard.ALLOW;
        PcGuard.Outcome o = e.run("sys_exec", "command", "notepad.exe a");
        assertFalse(o.whitelisted);
        assertEquals(1, e.st.asked.size());
        assertTrue(e.st.asked.get(0).contains("已放行 " + ActionPolicy.DEFAULT_PASS_LIMIT + " 步"));
    }

    @Test public void noWhitelistStoreMeansOldBehaviour() throws Exception {
        Env e = new Env(WS, UN);
        e.w.add("windows", "notepad.exe", "");
        e.g.whitelist = null;
        e.st.answer = PcGuard.DENY;
        assertFalse(passed(e.run("sys_exec", "command", "notepad.exe a")));
        assertEquals("pc.shell_exec", e.st.kinds.get(0));
    }

    @Test public void storeRoundTripBrokenFileAndAudit() throws Exception {
        Path d = Files.createTempDirectory("wls");
        WhitelistStore w = new WhitelistStore(d);
        assertNull(w.add("android", "com.tencent.mm", "微信"));
        assertNotNull(w.add("windows", "powershell.exe", ""));
        assertTrue(new WhitelistStore(d).contains("android", "com.tencent.mm"));
        Files.writeString(d.resolve("app_whitelist.json"), "{坏的", StandardCharsets.UTF_8);
        WhitelistStore b = new WhitelistStore(d);
        assertTrue(b.broken());
        assertFalse(b.contains("android", "com.tencent.mm"));
        assertNotNull(b.add("windows", "notepad.exe", ""));
        b.reset();
        assertFalse(b.broken());
        assertNull(b.add("windows", "notepad.exe", ""));
    }

    @Test public void syncInAndUiApi() throws Exception {
        Path d = Files.createTempDirectory("wlsync");
        WhitelistStore pc = new WhitelistStore(d);
        pc.device = "pc-1";
        AppWhitelist phone = new AppWhitelist();
        phone.add("android", "com.tencent.mm", "微信", "ph-1", "phone", 100);
        List<String> told = new ArrayList<>(), log = new ArrayList<>();
        Map<String, Object> a = new TreeMap<>();
        a.put("doc", phone.toJson("ph-1"));
        a.put("_from", "ph-1");
        Actions.Result r = App.whitelistSyncIn(pc, a, told::add, log::add);
        assertTrue(r.text, r.ok);
        assertFalse("手机同步来的在电脑上先待确认", pc.contains("android", "com.tencent.mm"));
        assertEquals(1, pc.pending().size());
        assertEquals(1, told.size());
        assertTrue(told.get(0), told.get(0).contains("确认一次才生效"));
        phone.merge(AppWhitelist.parse(String.valueOf(r.data.get("doc"))));
        assertEquals(phone.toJson("x"), AppWhitelist.parse(pc.json()).size() == 1 ? phone.toJson("x") : "");
        a.put("doc", "{\"format\":\"onesay.app_whitelist\",\"version\":9,\"entries\":[]}");
        assertFalse(App.whitelistSyncIn(pc, a, told::add, log::add).ok);
        // 界面：不确认不能加；风险说明；不能加的
        Map<String, Object> b = new TreeMap<>();
        b.put("op", "add");
        b.put("platform", "windows");
        b.put("id", "code.exe");
        assertEquals(false, App.whitelistApi(pc, b, log::add).get("ok"));
        b.put("op", "risk");
        Map<String, Object> risk = App.whitelistApi(pc, b, log::add);
        assertTrue(String.valueOf(risk.get("text")).contains("付款"));
        b.put("op", "add");
        b.put("confirmed", true);
        assertEquals(true, App.whitelistApi(pc, b, log::add).get("ok"));
        b.put("id", "cmd.exe");
        assertEquals(false, App.whitelistApi(pc, b, log::add).get("ok"));
        b.put("op", "remove");
        b.put("id", "Code.EXE");
        assertEquals(true, App.whitelistApi(pc, b, log::add).get("ok"));
        assertFalse(pc.contains("windows", "code.exe"));
        // 界面：待确认的不确认不能生效；确认后生效、存盘后仍生效
        Map<String, Object> c = new TreeMap<>();
        c.put("op", "confirm");
        c.put("platform", "android");
        c.put("id", "com.tencent.mm");
        assertEquals(false, App.whitelistApi(pc, c, log::add).get("ok"));
        assertFalse(pc.contains("android", "com.tencent.mm"));
        c.put("confirmed", true);
        assertEquals(true, App.whitelistApi(pc, c, log::add).get("ok"));
        assertTrue(pc.contains("android", "com.tencent.mm"));
        assertTrue(new WhitelistStore(d).contains("android", "com.tencent.mm"));
        assertEquals(false, App.whitelistApi(pc, c, log::add).get("ok"));
        // 确认记录不进同步格式
        assertFalse(pc.json().contains("confirmed_here"));
        // 同步指令在任何沙箱档都能过（和查询同一行），单独加一条不行
        assertEquals(LinkPolicy.Cat.QUERY, LinkPolicy.pcCat("whitelist_sync"));
        assertEquals(LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.pcCat("whitelist_add"));
    }

    /** [规则调整 2026-11-16] 手机推来的白名单程序：电脑上确认前照旧问，确认后放行。 */
    @Test public void pushedFromPhoneNeedsConfirmOnPc() throws Exception {
        Env e = new Env(WS, UN);
        e.st.answer = PcGuard.DENY;
        AppWhitelist phone = new AppWhitelist();
        assertNull(phone.add("windows", "notepad.exe", "记事本", "ph-1", "phone", 100));
        e.w.merge(phone.toJson("ph-1"), "ph-1");
        assertFalse(passed(e.run("sys_exec", "command", "notepad.exe a.txt")));
        assertEquals("待确认：照旧问", 1, e.st.asked.size());
        assertNull(e.w.confirm("windows", "notepad.exe"));
        assertTrue(passed(e.run("sys_exec", "command", "notepad.exe a.txt")));
        assertEquals("确认后不再问", 1, e.st.asked.size());
        assertTrue(e.audit(), e.audit().contains("confirm"));
    }

    /** [规则调整 2026-11-16] 卸载白名单里的电脑程序必须确认，不能记住；「从不询问」档拒绝。 */
    @Test public void uninstallWhitelistedProgramAlwaysAsks() throws Exception {
        Env e = new Env(FA, UN);
        e.st.answer = PcGuard.DENY;
        assertNull(e.w.add("windows", "notepad++.exe", "Notepad++"));
        assertTrue("打开照样放行", passed(e.run("sys_exec", "command", "notepad++.exe a.txt")));
        int before = e.st.asked.size();
        PcGuard.Outcome o = e.run("sys_exec", "command", "notepad++.exe --uninstall");
        assertFalse(passed(o));
        assertEquals(before + 1, e.st.asked.size());
        assertEquals(ActionPolicy.Layer.UNINSTALL, o.layered.layer);
        assertTrue(e.st.asked.get(before), e.st.asked.get(before).contains("卸载"));
        assertTrue("不给「以后不用问」", e.st.kinds.isEmpty() || "".equals(e.st.kinds.get(e.st.kinds.size() - 1)));
        assertFalse(o.layered.rememberable);
        assertFalse(passed(e.run("sys_exec", "command", "notepad++.exe /uninstall")));
        assertTrue(e.audit(), e.audit().contains("UNINSTALL"));
        assertEquals(LinkPolicy.Cat.UNINSTALL_APP, PcGuard.category("uninstall_app", PcGuardTest.m("name", "notepad++.exe"), null));
        Env n = new Env(FA, NV);
        assertNull(n.w.add("windows", "notepad++.exe", ""));
        assertFalse(passed(n.run("sys_exec", "command", "notepad++.exe --uninstall")));
        assertEquals("从不询问档直接拒绝，不弹", 0, n.st.asked.size());
    }

    /** [规则保持 2026-11-16] 这台手机本次会话经电脑从网上拉过内容后，白名单程序的外发也要确认；没拉过照旧放行。 */
    @Test public void outboundAfterExternalReadAsks() throws Exception {
        Env e = new Env(FA, UN);
        e.st.answer = PcGuard.DENY;
        assertNull(e.w.add("windows", "notepad.exe", ""));
        Agent ag = new Agent(e.cfg, new Main.TestActions(), s -> { });
        ag.guard.whitelist = e.w;
        ag.noteRead("ph-1", "media_get", Actions.Result.ok("ok", new TreeMap<>()));
        assertTrue(e.w.readExternal("ph-1"));
        assertFalse("别的手机不受影响", e.w.readExternal("ph-2"));
        ag.stop();
        ActionPolicy.Facts f = new ActionPolicy.Facts();
        f.whitelisted = true;
        f.appId = "notepad.exe";
        f.kind = ActionPolicy.Kind.OUTBOUND;
        f.readExternal = e.w.readExternal("ph-1");
        assertEquals(ActionPolicy.Layer.READ_EXTERNAL, ActionPolicy.decide(f).layer);
        // 会话过期（10 分钟没动静）后清掉
        long[] now = {System.currentTimeMillis()};
        e.w.clock = () -> now[0];
        e.w.noteExternal("ph-3");
        now[0] += WhitelistStore.SESSION_MS + 1;
        assertFalse(e.w.readExternal("ph-3"));
    }

    @Test public void readTrackingFromResults() throws Exception {
        Env e = new Env(FA, UN);
        Agent ag = new Agent(e.cfg, new Main.TestActions(), s -> { });
        ag.guard.whitelist = e.w;
        ag.noteRead("ph-9", "screenshot", Actions.Result.ok("ok", new TreeMap<>()));
        Set<String> k = e.w.readKinds("ph-9");
        assertTrue(k.contains(PrivacyScan.IMAGE_UNKNOWN));
        Map<String, Object> d = new TreeMap<>();
        d.put("text", "收货地址：北京市海淀区中关村大街1号");
        ag.noteRead("ph-8", "clipboard_get", Actions.Result.ok("ok", d));
        assertTrue(e.w.readKinds("ph-8").contains(PrivacyScan.ADDRESS));
        ag.noteRead("ph-7", "clipboard_get", Actions.Result.ok("ok", PcGuardTest.m("text", "今天开会")));
        assertTrue(e.w.readKinds("ph-7").isEmpty());
        ag.stop();
    }
}
