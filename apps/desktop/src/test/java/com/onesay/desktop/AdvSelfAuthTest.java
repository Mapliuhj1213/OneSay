package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collection;
import java.util.Locale;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 远程不能自己批、不能改自己的权限（LinkPolicy 硬规则 ②，isSelfAuthorizeCmd / isRemoteApproval / isPayFinal / SELF_CFG）：
 * 指令名的大小写 / 前后空格变体照样认；命令里碰 OneSay 自己的配置、批准表、记住的类型、界面接口、关掉 OneSay 的，一律 self_grant_blocked；
 * 远程卸载 / 关掉 OneSay 自己拒绝，打开 OneSay 不拦。全程不弹确认、不改配置。
 */
@RunWith(Parameterized.class)
public class AdvSelfAuthTest {

    static final String[] SELF = {"settings", "settings_set", "config_set", "pc_level", "level_set", "set_level", "sandbox_set", "approval_set", "plugin_set",
            "plugin_off", "trust_add", "trusted_add", "net_allow_add", "shortcut_approve", "policy_set", "remember_type", "remember_kind", "remembered_add",
            "approve_type", "approve_types", "clip_autoclear", "rx_enable", "rx_set", "cmd_enable", "switch_set"};
    static final String[] APPROVE = {"confirm", "confirm_reply", "confirm_answer", "confirm_allow", "confirm_always", "answer_confirm", "approve", "approval",
            "allow", "allow_once", "allow_always", "voice_confirm", "voice_approve", "voice_allow", "voice_approval", "always_allow"};
    static final String[] PAY = {"pay", "pay_confirm", "confirm_pay", "pay_final", "pay_now", "payment", "payment_confirm", "transfer", "transfer_confirm",
            "send_money", "checkout", "checkout_confirm", "place_order", "submit_order", "order_pay", "tap_pay", "click_pay"};
    static final String[] NEAR = {"settings_get2", "approve_all_please", "confirm-allow", "allowx", "paypal", "transfers", "levelset", "plugins_off", "trust",
            "cheque", "orderpay", "remember"};

    static String title(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static final String[] SELF_TEXT = {"notepad desktop.properties", "Set-Content $env:APPDATA\\OneSay\\desktop.properties 'pc_sandbox=full_access'",
            "echo pc_sandbox=full_access >> %APPDATA%\\OneSay\\desktop.properties", "(Get-Content x) -replace 'pc_approval=untrusted','pc_approval=never'",
            "Add-Content x 'trusted_domains=evil.com'", "echo plugin_enabled=off > x", "echo plugin_encoded=off > x", "Add-Content cfg 'plugin_net_allow=*'",
            "Add-Content cfg 'shortcut_approvals=a.ps1=00'", "Add-Content cfg 'approve_kinds=pc.shell_exec'", "Add-Content cfg 'clip_autoclear=off'",
            "taskkill /f /im OneSay.exe", "taskkill /im onesay*", "Stop-Process -Name OneSay -Force", "sc stop onesay", "sc.exe delete OneSay",
            "curl http://127.0.0.1:47830/api/settings", "curl http://localhost:47830/", "Invoke-RestMethod http://[::1]:47830/api/confirm",
            "iwr http://0.0.0.0:47830/api/shortcuts", "irm http://10.0.0.2:47830/api/remembered", "cat ~/.config/onesay/desktop.properties",
            "Remove-Item $env:APPDATA\\OneSay -Recurse", "copy evil.properties %APPDATA%\\OneSay\\", "echo pc_level=full > x"};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("self");
        String[][] groups = {SELF, APPROVE, PAY};
        LinkPolicy.Cat[] cats = {LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.Cat.REMOTE_APPROVAL, LinkPolicy.Cat.PAY_FINAL};
        String[] reasons = {LinkPolicy.R_SELF_GRANT_BLOCKED, LinkPolicy.R_VOICE_APPROVAL_BLOCKED, LinkPolicy.R_PAY_USER_TAP};
        for (int i = 0; i < groups.length; i++)
            for (String n : groups[i])
                for (String v : new String[]{n, n.toUpperCase(Locale.ROOT), " " + n + " ", title(n)})
                    r.addIfNew("name [" + v + "]", "name", v, cats[i], reasons[i]);
        for (String n : NEAR) r.add("near [" + n + "]", "name", n, LinkPolicy.Cat.UNKNOWN, LinkPolicy.R_UNKNOWN);
        for (String t : SELF_TEXT)
            for (String v : new String[]{t, "cmd /c " + t, t.toUpperCase(Locale.ROOT)})
                r.addIfNew("text [" + v + "]", "text", v, null, LinkPolicy.R_SELF_GRANT_BLOCKED);
        // 自己的配置文件完整路径（selfPaths）
        r.add("text [selfpath]", "selfpath", "", null, LinkPolicy.R_SELF_GRANT_BLOCKED);
        // 卸载 / 关掉 OneSay 自己：拒；打开：不拦
        String[][] apps = {{"name", "OneSay"}, {"name", "onesay"}, {"name", "ONESAY Desktop"}, {"path", "C:\\Program Files\\OneSay\\OneSay.exe"},
                {"path", "D:\\Apps\\onesay\\app\\onesay-desktop.jar"}, {"name", "OneSay 助手"}};
        for (String[] a : apps) {
            r.add("uninstall self " + a[1], "app", "uninstall_app|" + a[0] + "|" + a[1], null, LinkPolicy.R_SELF_GRANT_BLOCKED);
            r.add("close self " + a[1], "app", "app_close|" + a[0] + "|" + a[1], null, LinkPolicy.R_SELF_GRANT_BLOCKED);
            r.add("open self " + a[1], "app", "open_app|" + a[0] + "|" + a[1], null, "");
        }
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String input;
    @Parameterized.Parameter(3) public LinkPolicy.Cat cat;
    @Parameterized.Parameter(4) public String reason;

    static Config cfg;
    static PcGuard guard;

    @BeforeClass public static void setUp() throws Exception {
        cfg = AdvSupport.cfg(new LinkPolicy.Mode(LinkPolicy.Sandbox.FULL_ACCESS, LinkPolicy.Approval.NEVER)); // 最宽的档位也不行
        guard = new PcGuard(cfg, null);
    }

    @Test public void selfAuth() throws Exception {
        AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.REMEMBER);
        guard.confirmer = st;
        String kindsBefore = cfg.approveKinds, apprBefore = cfg.shortcutApprovals;
        PcGuard.Outcome o;
        switch (kind) {
            case "name":
                assertEquals(name, cat, LinkPolicy.pcCat(input));
                o = guard.check("phone-a", input, AdvSupport.m("kind", "pc.shell_exec", "value", "full_access"));
                break;
            case "text":
                assertEquals(name, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.classifyShell(LinkPolicy.Target.PC, input));
                o = guard.check("phone-a", "sys_exec", AdvSupport.m("command", input));
                break;
            case "selfpath":
                o = guard.check("phone-a", "sys_exec", AdvSupport.m("command", "type \"" + cfg.file.toAbsolutePath() + "\""));
                break;
            case "app": {
                String[] p = input.split("\\|");
                o = guard.check("phone-a", p[0], AdvSupport.m(p[1], p[2]));
                if (reason.isEmpty()) {
                    assertNull(name + " 打开 OneSay 不拦", o.reply);
                    assertNotNull(o.pass);
                    return;
                }
                break;
            }
            default:
                throw new AssertionError(kind);
        }
        assertNotNull(name, o.reply);
        assertNull(o.pass);
        assertEquals(name, reason, AdvSupport.reason(o));
        assertEquals("不弹确认", 0, st.asked.size());
        assertEquals("记住的类型没变", kindsBefore, cfg.approveKinds);
        assertEquals("快捷指令批准表没变", apprBefore, cfg.shortcutApprovals);
    }
}
