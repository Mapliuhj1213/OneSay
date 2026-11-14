package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Locale;
import java.util.Random;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 不提权 / 要管理员只生成脚本（PcGuard 类说明、LinkPolicy「硬规则 ③」）：手机发来的 sys_exec 端到端过 {@link PcGuard#check}。
 *
 * <ul>
 *   <li>ELEV：想提权 / 绕 UAC（runas、-Verb RunAs、ShellExecute…runas、gsudo、psexec、fodhelper 等）→ 拒绝 plugin_blocked，不弹确认、不生成脚本。</li>
 *   <li>ADMIN：要管理员（HKLM / HKCR / HKU、sc、*-Service、bcdedit、netsh、防火墙、hosts、系统级计划任务、磁盘、账户、HKCU 劫持键、授权类）→
 *       只生成 .ps1（needs_admin_script），不弹确认；脚本带 UTF-8 BOM、中文头注释、原命令。</li>
 *   <li>ENC：编码 / 动态执行包着的管理员命令 → 拒绝（看不出内容就不放行）。</li>
 *   <li>ASK：普通用户自己能做的命令 → 弹确认（桩点「拒绝」→ confirm_denied），确认框里有完整命令。</li>
 * </ul>
 * 每个基础命令再做变形：大小写、cmd 的 ^、PowerShell 的反引号、cmd /c / powershell -Command / pwsh -c 外壳、管道 / && / ; 拼接、
 * %ComSpec% 环境变量、-enc / -EncodedCommand / -e / -ec 编码。
 */
@RunWith(Parameterized.class)
public class AdvElevationTest {

    static final String ELEV = "ELEV", ADMIN = "ADMIN", ENC = "ENC", ASK = "ASK";

    static final String[] ELEV_CMD = {
            "runas /user:Administrator cmd.exe", "runas /savecred /user:admin \"notepad C:\\x.txt\"", "runas /noprofile /user:%COMPUTERNAME%\\Administrator calc",
            "gsudo cmd /c whoami", "sudo powershell", "psexec -s -i cmd.exe", "psexec.exe -h -accepteula cmd", "fodhelper.exe",
            "C:\\Windows\\System32\\fodhelper.exe", "eventvwr.exe", "computerdefaults.exe", "sdclt.exe /kickoffelev", "slui.exe", "wsreset.exe",
            "cmstp.exe /au C:\\x.inf", "dccw.exe", "schtasks /run /tn \\Microsoft\\Windows\\DiskCleanup\\SilentCleanup",
            "schtasks /create /tn up /tr cmd.exe /sc onlogon /rl highest"};
    static final String[] ELEV_PS = {
            "Start-Process cmd -Verb RunAs", "Start-Process powershell -Verb runAs -ArgumentList '-NoProfile'", "saps notepad -verb runas",
            "start-process cmd -verb:runas", "Start-Process -FilePath cmd.exe -Verb RunAs -Wait",
            "$psi = New-Object System.Diagnostics.ProcessStartInfo cmd; $psi.Verb = 'runas'; [Diagnostics.Process]::Start($psi)",
            "(New-Object -ComObject Shell.Application).ShellExecute('cmd.exe', '', '', 'runas')",
            "Register-ScheduledTask -TaskName up -Action (New-ScheduledTaskAction -Execute cmd) -RunLevel Highest"};

    /** {命令, 是不是 cmd 风格（可以加 ^）}。 */
    static final String[][] ADMIN_CMDS = {
            {"reg add HKLM\\Software\\Contoso /v Enabled /t REG_DWORD /d 1 /f", "c"}, {"reg delete HKLM\\Software\\Contoso /f", "c"},
            {"reg add HKEY_LOCAL_MACHINE\\SOFTWARE\\Policies\\Contoso /v x /d 1", "c"}, {"New-ItemProperty -Path HKLM:\\Software\\Contoso -Name Enabled -Value 1", "p"},
            {"Set-ItemProperty -Path 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\Contoso' -Name Start -Value 4", "p"},
            {"Remove-Item -Path Registry::HKEY_LOCAL_MACHINE\\Software\\Contoso -Recurse", "p"}, {"reg add HKCR\\.contoso /ve /d ContosoFile", "c"},
            {"reg add HKU\\.DEFAULT\\Software\\Contoso /v a /d b", "c"}, {"reg load HKU\\Temp C:\\Users\\Default\\NTUSER.DAT", "c"},
            {"reg import C:\\temp\\settings.reg", "c"}, {"reg restore HKCU\\Software\\x C:\\x.hiv", "c"},
            {"sc create Contoso binPath= C:\\contoso.exe start= auto", "c"}, {"sc.exe config wuauserv start= disabled", "c"}, {"sc delete Contoso", "c"},
            {"sc start Contoso", "c"}, {"sc stop wuauserv", "c"}, {"sc failure Contoso reset= 0 actions= restart/1000", "c"},
            {"sc sdset Contoso D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)", "c"}, {"New-Service -Name Contoso -BinaryPathName C:\\contoso.exe", "p"},
            {"Set-Service -Name wuauserv -StartupType Disabled", "p"}, {"Stop-Service -Name WinDefend -Force", "p"}, {"Start-Service -Name Contoso", "p"},
            {"Restart-Service -Name Spooler", "p"}, {"Remove-Service -Name Contoso", "p"}, {"net start Contoso", "c"}, {"net stop wuauserv", "c"},
            {"pnputil /add-driver C:\\drivers\\x.inf /install", "c"}, {"devcon disable *PNP0F13", "c"}, {"driverquery /v", "c"},
            {"bcdedit /set testsigning on", "c"}, {"bcdedit /set {default} recoveryenabled no", "c"}, {"netsh advfirewall set allprofiles state off", "c"},
            {"netsh interface ip set dns name=Ethernet static 1.1.1.1", "c"}, {"netsh wlan delete profile name=*", "c"},
            {"New-NetFirewallRule -DisplayName Contoso -Direction Inbound -Action Allow", "p"},
            {"Set-NetFirewallProfile -Profile Domain,Public,Private -Enabled False", "p"}, {"Disable-NetAdapter -Name Wi-Fi -Confirm:$false", "p"},
            {"Set-DnsClientServerAddress -InterfaceIndex 12 -ServerAddresses 8.8.8.8", "p"},
            {"Add-Content C:\\Windows\\System32\\drivers\\etc\\hosts '1.2.3.4 example.com'", "p"}, {"route add 10.0.0.0 mask 255.0.0.0 192.168.1.1", "c"},
            {"route delete 0.0.0.0", "c"}, {"ipconfig /release", "c"}, {"ipconfig /renew", "c"}, {"arp -d *", "c"},
            {"Set-NetIPInterface -InterfaceIndex 12 -Dhcp Disabled", "p"}, {"netcfg -d", "c"},
            {"schtasks /create /tn Contoso /tr C:\\x.exe /sc onstart /ru SYSTEM", "c"},
            {"schtasks /create /tn Contoso /tr C:\\x.exe /sc daily /ru \"NT AUTHORITY\\SYSTEM\"", "c"}, {"schtasks /change /tn Contoso /ru Administrator", "c"},
            {"Register-ScheduledTask -TaskName Contoso -Action $a -User SYSTEM", "p"}, {"at 23:00 /every:M C:\\x.bat", "c"}, {"diskpart /s C:\\x.txt", "c"},
            {"format D: /FS:NTFS /Q", "c"}, {"vssadmin delete shadows /all /quiet", "c"}, {"dism /online /disable-feature /featurename:SMB1Protocol", "c"},
            {"sfc /scannow", "c"}, {"chkdsk C: /f", "c"}, {"wbadmin delete catalog -quiet", "c"}, {"reagentc /disable", "c"}, {"bootrec /fixmbr", "c"},
            {"mountvol X: /d", "c"}, {"fsutil behavior set disable8dot3 1", "c"}, {"wusa /uninstall /kb:5005565 /quiet", "c"},
            {"secedit /configure /db x.sdb /cfg x.inf", "c"}, {"auditpol /set /category:* /success:disable", "c"}, {"net user hacker P@ss1 /add", "c"},
            {"net accounts /maxpwage:unlimited", "c"}, {"setx PATH C:\\tools /M", "c"}, {"msiexec /i C:\\setup.msi /qn", "c"},
            {"Set-ExecutionPolicy Unrestricted -Force", "p"}, {"Set-ExecutionPolicy Bypass -Scope LocalMachine", "p"},
            {"reg add HKCU\\Software\\Classes\\ms-settings\\shell\\open\\command /ve /d cmd.exe /f", "c"},
            {"reg add HKCU\\Software\\Classes\\mscfile\\shell\\open\\command /ve /d cmd.exe", "c"},
            {"New-Item -Path HKCU:\\Software\\Classes\\exefile\\shell\\open\\command -Force", "p"},
            {"Set-ItemProperty HKCU:\\Software\\Classes\\Folder\\shell\\open\\command -Name DelegateExecute -Value ''", "p"},
            {"reg add HKCU\\Environment /v windir /d \"cmd /c start calc &&\"", "c"}, {"Set-ItemProperty -Path HKCU:\\Environment -Name COR_PROFILER -Value '{x}'", "p"},
            {"reg add HKCU\\Environment /v COR_ENABLE_PROFILING /d 1", "c"}, {"reg add HKCU\\Software\\Policies\\Microsoft\\Windows\\System /v DisableCMD /d 0", "c"},
            {"reg add HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System /v DisableTaskMgr /d 0", "c"},
            {"icacls C:\\Windows\\System32 /grant Everyone:F", "c"}, {"takeown /f C:\\Windows\\System32\\x.dll", "c"},
            {"Set-Acl -Path C:\\x -AclObject $acl", "p"}, {"Add-MpPreference -ExclusionPath C:\\", "p"}, {"net localgroup administrators bob /add", "c"}};

    static final String[] ASK_CMDS = {
            "dir C:\\Users", "Get-ChildItem -Recurse .", "reg add HKCU\\Software\\Contoso /v Theme /d dark /f", "reg query HKCU\\Software\\Contoso",
            "sc query wuauserv", "Get-Service -Name Spooler", "schtasks /query /fo LIST", "schtasks /create /tn Remind /tr notepad.exe /sc once /st 23:00",
            "Set-ExecutionPolicy RemoteSigned -Scope CurrentUser", "Set-ExecutionPolicy Bypass -Scope Process", "ipconfig /all", "route print",
            "netstat -ano", "Get-NetAdapter", "Get-Process | Format-Table Name", "echo runassist", "type pseudo.txt", "notepad sudoers.txt",
            "copy a.txt b.txt", "ping 127.0.0.1", "whoami /priv", "Get-Date", "mkdir build", "Write-Host \"run as admin later\"", "tasklist",
            "systeminfo", "Get-ItemProperty HKCU:\\Environment", "reg add HKCU\\Environment /v MYVAR /d 1"};

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("elevation");
        Random rnd = AdvSupport.rng("elev");
        for (String x : ELEV_CMD) {
            for (String v : new String[]{x, x.toUpperCase(Locale.ROOT), x.toLowerCase(Locale.ROOT), AdvSupport.randomCase(x, rnd), AdvSupport.caret(x),
                    "cmd /c " + x, "cmd.exe /s /c \"" + x.replace("\"", "") + "\"", "dir & " + x, "echo ok && " + x, "type nul | " + x,
                    "%ComSpec% /c " + x, "powershell -NoProfile -EncodedCommand " + AdvSupport.b64ps("cmd /c " + x)})
                r.addIfNew("elev " + v, v, ELEV);
        }
        for (String x : ELEV_PS) {
            for (String v : new String[]{x, x.toUpperCase(Locale.ROOT), x.toLowerCase(Locale.ROOT), AdvSupport.randomCase(x, rnd), AdvSupport.backtick(x),
                    "powershell -NoProfile -Command \"" + x + "\"", "pwsh -nop -c '" + x.replace("'", "''") + "'", "Get-Date; " + x,
                    "Write-Output 1 | % { " + x + " }", "powershell -enc " + AdvSupport.b64ps(x), "powershell -e " + AdvSupport.b64ps(x),
                    "powershell.exe -ec " + AdvSupport.b64ps(x)})
                r.addIfNew("elev " + v, v, ELEV);
        }
        for (String[] a : ADMIN_CMDS) {
            String x = a[0];
            boolean cmd = "c".equals(a[1]);
            String wrap = cmd ? "cmd /c " + x : "powershell -NoProfile -Command \"" + x.replace("\"", "") + "\"";
            for (String v : new String[]{x, x.toUpperCase(Locale.ROOT), AdvSupport.randomCase(x, rnd), wrap, "echo start && " + x,
                    cmd ? AdvSupport.caret(x) : AdvSupport.backtick(x)})
                r.addIfNew("admin " + v, v, ADMIN);
        }
        // 编码 / 动态执行包着的管理员命令：看不出内容 → 拒绝（不生成脚本）
        int k = 0;
        for (String[] a : ADMIN_CMDS) {
            if (k++ % 3 != 0) continue;
            String x = a[0];
            r.addIfNew("enc -enc " + x, "powershell -enc " + AdvSupport.b64ps(x), ENC);
            r.addIfNew("enc iex " + x, "Invoke-Expression '" + x.replace("'", "''") + "'", ENC);
        }
        for (String x : ASK_CMDS) {
            for (String v : new String[]{x, x.toUpperCase(Locale.ROOT), "cmd /c " + x})
                r.addIfNew("ask " + v, v, ASK);
        }
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String command;
    @Parameterized.Parameter(2) public String expect;

    static Config cfg;
    static PcGuard guard;

    @BeforeClass public static void setUp() throws Exception {
        cfg = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
        guard = new PcGuard(cfg, null);
    }

    @Test public void sysExec() throws Exception {
        AdvSupport.Stub st = new AdvSupport.Stub(PcGuard.DENY);
        guard.confirmer = st;
        PcGuard.Outcome o = guard.check("phone-a", "sys_exec", AdvSupport.m("command", command));
        assertNotNull("这条不能直接放行：" + command, o.reply);
        assertNull("没放行就不该有执行凭证", o.pass);
        String why = AdvSupport.reason(o);
        switch (expect) {
            case ELEV:
                assertEquals(command, LinkPolicy.R_PLUGIN_BLOCKED, why);
                assertTrue(o.reply.text, o.reply.text.contains("想用管理员身份运行"));
                assertEquals("提权不弹确认", 0, st.asked.size());
                assertNull("提权不生成脚本", o.reply.data.get("path"));
                break;
            case ENC:
                assertEquals(command, LinkPolicy.R_PLUGIN_BLOCKED, why);
                assertTrue(o.reply.text, o.reply.text.contains("编码 / 动态执行"));
                assertEquals(0, st.asked.size());
                assertNull(o.reply.data.get("path"));
                break;
            case ADMIN: {
                assertEquals(command, LinkPolicy.R_NEEDS_ADMIN_SCRIPT, why);
                assertEquals("要管理员的不弹确认", 0, st.asked.size());
                assertTrue(o.reply.text, o.reply.text.startsWith("这件事要管理员，已生成脚本"));
                String rel = String.valueOf(o.reply.data.get("path"));
                assertTrue(rel, rel.matches("scripts/\\d{8}-\\d{6}-sys-exec(-\\d+)?\\.ps1"));
                File f = LinkPolicy.resolveInside(new File(cfg.workspaceDir), rel);
                assertNotNull(rel, f);
                byte[] b = Files.readAllBytes(f.toPath());
                assertTrue("ps1 带 UTF-8 BOM", b.length > 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF);
                String s = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
                assertTrue("头注释说明没执行", s.startsWith("# 由 OneSay 远程指令生成，没有执行。"));
                assertTrue("风险写明不提权", s.contains("OneSay 不会自己提权"));
                assertTrue("原命令原样在脚本里", s.contains(LinkPolicy.body(command.trim())));
                break;
            }
            case ASK:
                assertEquals(command, LinkPolicy.R_CONFIRM_DENIED, why);
                assertEquals("普通命令弹一次确认", 1, st.asked.size());
                assertTrue(st.asked.get(0), st.asked.get(0).contains("执行命令：" + command.trim()));
                assertEquals("可勾「以后这类不用问」", "pc.shell_exec", st.kinds.get(0));
                break;
            default:
                throw new AssertionError(expect);
        }
    }
}
