package com.onesay.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 电脑端按账户的目录（[integ] 2026-11-13，安装程序已接受的默认做法）。只算路径，不建目录。
 *
 * <p>用户目录全是可打印 ASCII 时照旧放在自己的用户目录下；含中文等非 ASCII（很多命令行工具对这种路径不友好）时，
 * 退到 %ProgramData%\OneSay\accounts\&lt;账户标记&gt;\…，<b>每个账户一个子目录</b>，不再几个中文用户名账户共用
 * %ProgramData%\OneSay\workspace（卸载时「同时删除我的数据」只删自己的那份）。
 *
 * <p>账户标记 = 用户目录最后一段（如 C:\Users\张三 → 张三），A–Z a–z 0–9 和 - 原样保留，其余每个 UTF-16 字符写成
 * {@code _XXXX}（4 位大写十六进制），例如 张三 → {@code _5F20_4E09}。结果全是 ASCII、可逆、同一台电脑上不同
 * 账户不会撞（Windows 的用户目录名在本机唯一）。安装程序 OneSay.nsi 的 un.AccountTag 用同一规则算，卸载时找得到。
 *
 * <p>下载目录（链接下载的输出）<b>不回退</b>：永远是 %USERPROFILE%\OneSay\Downloads（或设置里指定的目录），因为那是给
 * 用户自己看的文件，放到 ProgramData 反而找不到；yt-dlp 只把它当输出路径，用 Unicode API，不受影响。
 */
public final class UserDirs {
    private UserDirs() {}

    /** 全是可打印 ASCII（0x20..0x7E）且非空。与安装程序 un.IsAsciiPath 判断相同。 */
    public static boolean isAscii(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /** 路径最后一段（\ 和 / 都算分隔符，忽略结尾的分隔符）。 */
    static String leaf(String path) {
        String p = path == null ? "" : path.trim();
        while (p.endsWith("\\") || p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 账户标记（见类说明）；用户目录为空时是 default。 */
    public static String accountTag(String userProfile) {
        String name = leaf(userProfile);
        if (name.isEmpty()) return "default";
        StringBuilder sb = new StringBuilder(name.length() * 5);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') sb.append(c);
            else sb.append('_').append(String.format("%04X", (int) c));
        }
        return sb.toString();
    }

    private static String pd(String programData) {
        String base = programData == null || programData.trim().isEmpty() ? "C:\\ProgramData" : programData.trim();
        while (base.endsWith("\\") || base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String strip(String s) {
        String b = s.trim();
        while (b.endsWith("\\") || b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** 本账户在 ProgramData 下的回退根目录：%ProgramData%\OneSay\accounts\&lt;账户标记&gt;。 */
    public static String fallbackRoot(String userProfile, String programData) {
        return pd(programData) + "\\OneSay\\accounts\\" + accountTag(userProfile);
    }

    /** 工作区：%USERPROFILE%\OneSay\workspace；非 ASCII 时 %ProgramData%\OneSay\accounts\&lt;账户&gt;\workspace。 */
    public static String workspaceDir(String userProfile, String programData) {
        String up = userProfile == null ? "" : userProfile.trim();
        if (isAscii(up)) return strip(up) + "\\OneSay\\workspace";
        return fallbackRoot(up, programData) + "\\workspace";
    }

    /**
     * 链接下载的工具目录：%LOCALAPPDATA%\OneSay\tools；LOCALAPPDATA 含非 ASCII 时 %ProgramData%\OneSay\accounts\&lt;账户&gt;\tools；
     * 没有 LOCALAPPDATA（非 Windows）时 ~/.local/share/onesay/tools。userProfile 为空时从 LOCALAPPDATA 去掉 \AppData\Local 推出来。
     */
    public static String toolsDir(String localAppData, String programData, String userHome, String userProfile) {
        String la = localAppData == null ? "" : localAppData.trim();
        if (la.isEmpty()) return Paths.get(userHome == null ? "." : userHome, ".local", "share", "onesay", "tools").toString();
        if (isAscii(la)) return strip(la) + "\\OneSay\\tools";
        String up = userProfile == null ? "" : userProfile.trim();
        if (up.isEmpty()) {
            String s = strip(la);
            String low = s.toLowerCase(java.util.Locale.ROOT).replace('/', '\\');
            if (low.endsWith("\\appdata\\local")) s = s.substring(0, s.length() - "\\appdata\\local".length());
            up = s;
        }
        return fallbackRoot(up, programData) + "\\tools";
    }

    /** 链接下载的输出目录：设置里的 media_dir；否则 %USERPROFILE%\OneSay\Downloads（不回退）；没有 USERPROFILE 时 ~/OneSay/Downloads。 */
    public static Path downloadsDir(String configured, String userProfile, String userHome) {
        if (configured != null && !configured.isBlank()) return Paths.get(configured.trim());
        if (userProfile != null && !userProfile.isEmpty()) return Paths.get(userProfile, "OneSay", "Downloads");
        return Paths.get(userHome == null ? "." : userHome, "OneSay", "Downloads");
    }
}
