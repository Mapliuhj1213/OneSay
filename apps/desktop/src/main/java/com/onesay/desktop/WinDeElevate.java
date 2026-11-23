package com.onesay.desktop;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * [fix-1123-misc] OneSay 被「以管理员身份」打开时，手机发来的命令降成普通用户权限再跑（{@link SafeExec.DeElevator} 的 Windows 实现）。
 *
 * <p>做法：起一个固定的 PowerShell 包装进程（内容写死在这里，不带任何手机给的文字），它用 Windows 自带的
 * 「软件限制策略」接口（advapi32 的 SaferCreateLevel / SaferComputeTokenFromLevel，NORMALUSER 级别）从 OneSay 自己的令牌
 * 算出一个<b>普通用户</b>令牌：管理员组变成「只拒绝」、特权去掉；再把完整性级别设成「中」，<b>启动前读回令牌核对</b>，
 * 不是「中」或更低就不启动。然后用 CreateProcessAsUser 以这个令牌启动真正的命令，输入输出接到包装进程上（OneSay 照常读输出），
 * 等它结束、原样带回退出码。
 *
 * <p>任何一步失败（接口调用失败、核对不过、起不来）：真正的命令<b>不启动</b>，包装进程往输出里写一句中文原因、以
 * {@link #EXIT_FAILED} 退出，OneSay 按「已拒绝」如实告诉用户 —— 绝不会悄悄以管理员身份跑。
 *
 * <p>真正的命令行通过环境变量（UTF-16LE 再 base64）交给包装进程，不拼进 PowerShell 脚本，避免引号 / 注入问题。
 * 安全插件「永不提权」的检查（runas、UAC 绕过…）照旧在包装之前对原命令做（{@link SafeExec#check}）。
 */
public final class WinDeElevate {

    private WinDeElevate() {}

    /** 包装进程在没法降权时的退出码（真正的命令没有启动）。 */
    public static final int EXIT_FAILED = 20307;
    /** 包装进程输出里没法降权时的那句话的开头（给 OneSay 认）。 */
    public static final String FAIL_MARK = "ONESAY_DEELEVATE_FAILED";
    static final String ENV_CMD = "ONESAY_DEELEVATE_CMD";
    static final String ENV_CWD = "ONESAY_DEELEVATE_CWD";

    /** 给人看的：没法降权时说什么。 */
    public static final String FAIL_ZH = "电脑上的 OneSay 是用管理员身份打开的，这条命令没法降成普通权限去跑，已经拦下没执行。"
            + "关掉 OneSay 后直接双击打开（不要右键「以管理员身份运行」），再试一次就行";

    /** {@link SafeExec.DeElevator}：把 pb 换成「包装进程 + 降权启动原命令」；不是 Windows 返回 null（= 拒绝）。 */
    public static ProcessBuilder wrap(ProcessBuilder pb) {
        if (!SafeExec.WINDOWS) return null;
        return wrapFor(pb, System.getenv("SystemRoot"));
    }

    /** 不看当前系统，直接生成包装（测试用）。 */
    static ProcessBuilder wrapFor(ProcessBuilder pb, String systemRoot) {
        List<String> cmd = pb.command();
        if (cmd == null || cmd.isEmpty()) return null;
        String line = commandLine(cmd);
        String ps = (systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot)
                + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
        ProcessBuilder w = new ProcessBuilder(ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE)));
        w.environment().clear();
        w.environment().putAll(pb.environment());
        w.environment().put(ENV_CMD, Base64.getEncoder().encodeToString(line.getBytes(StandardCharsets.UTF_16LE)));
        if (pb.directory() != null) {
            w.directory(pb.directory());
            w.environment().put(ENV_CWD, Base64.getEncoder().encodeToString(pb.directory().getAbsolutePath().getBytes(StandardCharsets.UTF_16LE)));
        } else w.environment().remove(ENV_CWD);
        w.redirectErrorStream(pb.redirectErrorStream());
        w.redirectInput(pb.redirectInput());
        w.redirectOutput(pb.redirectOutput());
        w.redirectError(pb.redirectError());
        return w;
    }

    /** 按 Windows（CommandLineToArgvW / MSVC 运行库）的规则把参数拼成一行命令行。 */
    static String commandLine(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(quote(args.get(i) == null ? "" : args.get(i)));
        }
        return sb.toString();
    }

    static String quote(String a) {
        if (!a.isEmpty() && a.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '"')) return a;
        StringBuilder sb = new StringBuilder("\"");
        int bs = 0;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c == '\\') { bs++; continue; }
            if (c == '"') { sb.append("\\".repeat(bs * 2 + 1)).append('"'); bs = 0; continue; }
            sb.append("\\".repeat(bs)).append(c);
            bs = 0;
        }
        sb.append("\\".repeat(bs * 2)).append('"');
        return sb.toString();
    }

    /** 拆回参数（CommandLineToArgvW 的规则，测试里核对 {@link #commandLine} 用）。 */
    static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= n) break;
            StringBuilder cur = new StringBuilder();
            boolean inq = false;
            while (i < n) {
                char c = line.charAt(i);
                if (!inq && (c == ' ' || c == '\t')) break;
                if (c == '\\') {
                    int bs = 0;
                    while (i < n && line.charAt(i) == '\\') { bs++; i++; }
                    if (i < n && line.charAt(i) == '"') {
                        cur.append("\\".repeat(bs / 2));
                        if (bs % 2 == 1) { cur.append('"'); i++; }
                    } else cur.append("\\".repeat(bs));
                    continue;
                }
                if (c == '"') { inq = !inq; i++; continue; }
                cur.append(c);
                i++;
            }
            out.add(cur.toString());
        }
        return out;
    }

    /** 包装进程的 PowerShell（固定内容）。C# 部分只调 Windows 自带接口。 */
    static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference='Stop'",
            "try {",
            "Add-Type -TypeDefinition @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public static class OneSayDeElevate {",
            "  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct SI { public int cb; public string r; public string d; public string t; public int x; public int y; public int xs; public int ys; public int xc; public int yc; public int fa; public int fl; public short sw; public short cb2; public IntPtr r2; public IntPtr hi; public IntPtr ho; public IntPtr he; }",
            "  [StructLayout(LayoutKind.Sequential)] public struct PI { public IntPtr hp; public IntPtr ht; public int pid; public int tid; }",
            "  [StructLayout(LayoutKind.Sequential)] public struct SAA { public IntPtr Sid; public uint Attr; }",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern bool SaferCreateLevel(uint scope, uint level, uint flags, out IntPtr h, IntPtr res);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern bool SaferComputeTokenFromLevel(IntPtr level, IntPtr inTok, out IntPtr outTok, uint flags, IntPtr res);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern bool SaferCloseLevel(IntPtr h);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool ConvertStringSidToSid(string s, out IntPtr sid);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern bool SetTokenInformation(IntPtr tok, int cls, ref SAA info, int len);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern bool GetTokenInformation(IntPtr tok, int cls, IntPtr buf, int len, out int ret);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern IntPtr GetSidSubAuthority(IntPtr sid, uint i);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true)] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);",
            "  [DllImport(\"advapi32.dll\", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool CreateProcessAsUser(IntPtr tok, string app, System.Text.StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string cwd, ref SI si, out PI pi);",
            "  [DllImport(\"kernel32.dll\")] static extern IntPtr GetStdHandle(int n);",
            "  [DllImport(\"kernel32.dll\")] static extern uint WaitForSingleObject(IntPtr h, uint ms);",
            "  [DllImport(\"kernel32.dll\")] static extern bool GetExitCodeProcess(IntPtr h, out uint code);",
            "  [DllImport(\"kernel32.dll\")] static extern bool CloseHandle(IntPtr h);",
            "  [DllImport(\"kernel32.dll\")] static extern IntPtr LocalFree(IntPtr h);",
            "  static int Integrity(IntPtr tok) {",
            "    int need; GetTokenInformation(tok, 25, IntPtr.Zero, 0, out need);",
            "    if (need <= 0) return -1;",
            "    IntPtr buf = Marshal.AllocHGlobal(need);",
            "    try {",
            "      if (!GetTokenInformation(tok, 25, buf, need, out need)) return -1;",
            "      IntPtr sid = Marshal.ReadIntPtr(buf);",
            "      int n = Marshal.ReadByte(GetSidSubAuthorityCount(sid));",
            "      if (n < 1) return -1;",
            "      return Marshal.ReadInt32(GetSidSubAuthority(sid, (uint)(n - 1)));",
            "    } finally { Marshal.FreeHGlobal(buf); }",
            "  }",
            "  public static int Run(string cmd, string cwd) {",
            "    IntPtr lvl, tok, sid;",
            "    if (!SaferCreateLevel(2, 0x20000, 1, out lvl, IntPtr.Zero)) throw new Exception(\"SaferCreateLevel \" + Marshal.GetLastWin32Error());",
            "    try { if (!SaferComputeTokenFromLevel(lvl, IntPtr.Zero, out tok, 0, IntPtr.Zero)) throw new Exception(\"SaferComputeTokenFromLevel \" + Marshal.GetLastWin32Error()); }",
            "    finally { SaferCloseLevel(lvl); }",
            "    try {",
            "      if (!ConvertStringSidToSid(\"S-1-16-8192\", out sid)) throw new Exception(\"ConvertStringSidToSid \" + Marshal.GetLastWin32Error());",
            "      try {",
            "        SAA l = new SAA(); l.Sid = sid; l.Attr = 0x20;",
            "        if (!SetTokenInformation(tok, 25, ref l, Marshal.SizeOf(typeof(SAA)) + 12)) throw new Exception(\"SetTokenInformation \" + Marshal.GetLastWin32Error());",
            "      } finally { LocalFree(sid); }",
            "      int il = Integrity(tok);",
            "      if (il < 0 || il > 0x2000) throw new Exception(\"integrity \" + il);",
            "      SI si = new SI(); si.cb = Marshal.SizeOf(typeof(SI)); si.fl = 0x100;",
            "      si.hi = GetStdHandle(-10); si.ho = GetStdHandle(-11); si.he = GetStdHandle(-12);",
            "      PI pi;",
            "      if (!CreateProcessAsUser(tok, null, new System.Text.StringBuilder(cmd), IntPtr.Zero, IntPtr.Zero, true, 0, IntPtr.Zero, cwd, ref si, out pi)) throw new Exception(\"CreateProcessAsUser \" + Marshal.GetLastWin32Error());",
            "      CloseHandle(pi.ht);",
            "      WaitForSingleObject(pi.hp, 0xFFFFFFFF);",
            "      uint code; GetExitCodeProcess(pi.hp, out code);",
            "      CloseHandle(pi.hp);",
            "      return (int)code;",
            "    } finally { CloseHandle(tok); }",
            "  }",
            "}",
            "'@",
            "$c=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($env:" + ENV_CMD + "))",
            "$d=$null; if ($env:" + ENV_CWD + ") { $d=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($env:" + ENV_CWD + ")) }",
            "Remove-Item Env:" + ENV_CMD + " -ErrorAction SilentlyContinue; Remove-Item Env:" + ENV_CWD + " -ErrorAction SilentlyContinue",
            "$code=[OneSayDeElevate]::Run($c,$d)",
            "} catch { [Console]::Error.WriteLine('" + FAIL_MARK + ": ' + $_.Exception.Message); exit " + EXIT_FAILED + " }",
            "exit $code");

    /** 一段输出 / 退出码是不是「没法降权、没执行」。 */
    public static boolean failed(int exit, String output) {
        return exit == EXIT_FAILED && output != null && output.contains(FAIL_MARK);
    }
}
