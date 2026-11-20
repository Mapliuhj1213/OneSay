package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * [workflow-1120] 连接器的 key / OAuth 令牌放哪：Windows 上是系统凭据库（凭据管理器，普通凭据），别的系统 / 测试用内存。
 * 配置文件、日志、导出文件、模型上下文里都不出现 key 本身，只出现「存了没有」。
 *
 * <p>Windows 实现：PowerShell 里用 advapi32 的 CredWriteW / CredReadW / CredDeleteW（Add-Type 一段 P/Invoke）。
 * key 只从标准输入传（base64），不出现在命令行里（命令行别的进程看得到）；脚本本身用 -EncodedCommand 传，只含凭据名。
 */
public interface SecretStore {
    boolean put(String name, String secret);

    String get(String name);

    boolean delete(String name);

    /** 给人看的：存在哪。 */
    String where();

    /** 上一次失败的原因（给人看的一句话），没有就 null。 */
    default String lastProblem() { return null; }

    /** 凭据名前缀：正式版 OneSay:，预览版 OneSay-Preview:（两版互不读）。 */
    static String name(String kind, String id) { return Flavor.dir() + ":" + kind + ":" + id; }

    static SecretStore system() { return SafeExec.WINDOWS ? new WinCred() : new Memory(); }

    /** 内存（测试 / 非 Windows）：进程退出就没了。 */
    final class Memory implements SecretStore {
        final Map<String, String> m = new ConcurrentHashMap<>();

        @Override public boolean put(String name, String secret) { if (secret == null) return false; m.put(name, secret); return true; }

        @Override public String get(String name) { return m.get(name); }

        @Override public boolean delete(String name) { return m.remove(name) != null; }

        @Override public String where() { return "内存（只在这次运行有效）"; }
    }

    /** Windows 凭据管理器。 */
    final class WinCred implements SecretStore {
        static final String TYPES = "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices; using System.Text;\n"
                + "public static class OsCred { public static int LastErr;\n"
                + " [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct CREDENTIAL { public int Flags; public int Type; public string TargetName; public string Comment; public long LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist; public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName; }\n"
                + " [DllImport(\"advapi32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CredWriteW(ref CREDENTIAL c, int flags);\n"
                + " [DllImport(\"advapi32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CredReadW(string target, int type, int flags, out IntPtr c);\n"
                + " [DllImport(\"advapi32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CredDeleteW(string target, int type, int flags);\n"
                + " [DllImport(\"advapi32.dll\")] static extern void CredFree(IntPtr p);\n"
                + " public static bool Put(string t, string s) { byte[] b = Encoding.UTF8.GetBytes(s); var c = new CREDENTIAL(); c.Type = 1; c.TargetName = t; c.Persist = 2; c.UserName = \"OneSay\"; c.CredentialBlobSize = b.Length; c.CredentialBlob = Marshal.AllocHGlobal(b.Length); Marshal.Copy(b, 0, c.CredentialBlob, b.Length); try { bool ok = CredWriteW(ref c, 0); LastErr = ok ? 0 : Marshal.GetLastWin32Error(); return ok; } finally { Marshal.FreeHGlobal(c.CredentialBlob); } }\n"
                + " public static string Get(string t) { IntPtr p; if (!CredReadW(t, 1, 0, out p)) return null; try { var c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL)); byte[] b = new byte[c.CredentialBlobSize]; Marshal.Copy(c.CredentialBlob, b, 0, b.Length); return Encoding.UTF8.GetString(b); } finally { CredFree(p); } }\n"
                + " public static bool Del(string t) { bool ok = CredDeleteW(t, 1, 0); LastErr = ok ? 0 : Marshal.GetLastWin32Error(); return ok; }\n"
                + "}\n'@\n";

        static String psQuote(String s) { return "'" + s.replace("'", "''") + "'"; }

        /** 生成脚本（测试里检查：只含凭据名，不含 key）。 */
        static String script(String op, String name) {
            // 真机 1120：输出被重定向时 PowerShell 会把「首次使用模块」的进度写成 #< CLIXML 到 stderr；以前和 stdout 合并后取最后一行 → 明明存进去了也报失败
            StringBuilder sb = new StringBuilder("$ErrorActionPreference='Stop'\n$ProgressPreference='SilentlyContinue'\n").append(TYPES);
            switch (op) {
                case "put":
                    sb.append("$s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::In.ReadLine()))\n")
                      .append("if([OsCred]::Put(").append(psQuote(name)).append(",$s)){'OK'}else{'FAIL:'+[OsCred]::LastErr}\n");
                    break;
                case "get":
                    sb.append("$v=[OsCred]::Get(").append(psQuote(name)).append(")\n")
                      .append("if($v -eq $null){'NONE'}else{'VAL:'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($v))}\n");
                    break;
                default:
                    sb.append("if([OsCred]::Del(").append(psQuote(name)).append(")){'OK'}else{'FAIL:'+[OsCred]::LastErr}\n");
            }
            return sb.toString();
        }

        static String[] command(String op, String name) {
            String enc = Base64.getEncoder().encodeToString(script(op, name).getBytes(StandardCharsets.UTF_16LE));
            return new String[] {"powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc};
        }

        /** 从脚本输出里找结果行（OK / NONE / VAL:… / FAIL:错误码），别的行（进度、警告）都不算。 */
        static String marker(String out) {
            if (out == null) return null;
            String[] ls = out.split("\\r?\\n");
            for (int i = ls.length - 1; i >= 0; i--) {
                String l = ls[i].trim();
                if (l.equals("OK") || l.equals("NONE") || l.matches("VAL:[A-Za-z0-9+/=]*") || l.matches("FAIL(:\\d+)?")) return l;
            }
            return null;
        }

        static String problem(String r) {
            if (r == null) return "调用系统凭据库没有结果（PowerShell 没跑起来或超时）";
            if (r.equals("FAIL:1312")) return "这次 OneSay 不是从桌面正常打开的（比如被计划任务 / 服务拉起），这种情况下 Windows 不给用凭据库（错误 1312）。从开始菜单重新打开 OneSay 再试";
            if (r.startsWith("FAIL:")) return "系统凭据库拒绝了（错误 " + r.substring(5) + "）";
            return null;
        }

        private volatile String last;

        private String call(String op, String name, String stdin) {
            try {
                Process p = new ProcessBuilder(command(op, name)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (OutputStream o = p.getOutputStream()) { if (stdin != null) o.write((stdin + "\n").getBytes(StandardCharsets.US_ASCII)); }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) { in.transferTo(bo); }
                if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
                return marker(bo.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }

        @Override public boolean put(String name, String secret) {
            if (secret == null) return false;
            String r = call("put", name, Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            last = "OK".equals(r) ? null : problem(r);
            return "OK".equals(r);
        }

        @Override public String get(String name) {
            String r = call("get", name, null);
            if (r == null || !r.startsWith("VAL:")) return null;
            try { return new String(Base64.getDecoder().decode(r.substring(4)), StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return null; }
        }

        @Override public boolean delete(String name) { return "OK".equals(call("del", name, null)); }

        @Override public String where() { return "Windows 凭据管理器（普通凭据）"; }

        @Override public String lastProblem() { return last; }
    }
}
