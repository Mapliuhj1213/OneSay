package com.onesay.desktop;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * phone-mirror M1：拖到「手机投屏」区的文件往哪走（纯 Java，可单测）。
 * <ul>
 *   <li>.apk → 装到手机（adb install -r）</li>
 *   <li>.apks / .xapk / .apkm / .aab（拆包或商店专有格式）→ 不装，说明原因（M1 不做 split 安装）</li>
 *   <li>其他文件 → 推到手机 /sdcard/Download/（同名不覆盖，自动加「 (1)」）</li>
 *   <li>空名 / 空文件 / 太大 → 拒绝</li>
 * </ul>
 */
public final class DropRoute {

    private DropRoute() {}

    public enum Kind { INSTALL_APK, PUSH_FILE, REJECT }

    public static final String TARGET_DIR = "/sdcard/Download/";
    /** 单个文件上限（经本机页面上传再推给手机）：4 GB。 */
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    public static final class Route {
        public final Kind kind;
        /** PUSH_FILE：手机上的文件名（已清洗、未去重）；INSTALL_APK：原名；REJECT：空。 */
        public final String name;
        public final String why;

        Route(Kind kind, String name, String why) {
            this.kind = kind;
            this.name = name;
            this.why = why;
        }
    }

    public static Route route(String fileName, long size) {
        String n = safeName(fileName);
        if (n == null) return new Route(Kind.REJECT, "", "文件名不能用");
        if (size == 0) return new Route(Kind.REJECT, "", "是个空文件");
        if (size > MAX_BYTES) return new Route(Kind.REJECT, "", "文件太大（单个最多 4 GB）");
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".apk")) return new Route(Kind.INSTALL_APK, n, "");
        if (l.endsWith(".apks") || l.endsWith(".xapk") || l.endsWith(".apkm") || l.endsWith(".aab"))
            return new Route(Kind.REJECT, "", "这是拆包格式（" + l.substring(l.lastIndexOf('.')) + "），现在只能装单个 .apk");
        return new Route(Kind.PUSH_FILE, n, "");
    }

    /**
     * 手机上的文件名：只取最后一段（去掉 / 和 \ 前面的路径），去掉控制字符和 Windows / FAT 不认的字符（换成 _），
     * 去掉首尾空格和点；"."、".." 或清洗后为空 → null。UTF-8 超过 200 字节时保留扩展名截断。
     */
    public static String safeName(String raw) {
        if (raw == null) return null;
        String n = raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        n.codePoints().forEach(c -> {
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == 0x2028 || c == 0x2029 || c == 0x202e || c == 0x202d || c == 0x200e || c == 0x200f) return;
            if ("<>:\"|?*'`$;&".indexOf(c) >= 0) b.append('_');
            else b.appendCodePoint(c);
        });
        n = b.toString().strip();
        while (n.startsWith(".")) n = n.substring(1);
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        n = n.strip();
        if (n.isEmpty() || n.startsWith("-")) n = n.isEmpty() ? "" : "_" + n;
        if (n.isEmpty()) return null;
        if (utf8(n) > 200) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 12 ? n.substring(dot) : "";
            String stem = ext.isEmpty() ? n : n.substring(0, dot);
            while (utf8(stem + ext) > 200 && !stem.isEmpty()) stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
            n = stem + ext;
        }
        return n;
    }

    static int utf8(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

    /** 和手机 Download 里已有的名字去重（不区分大小写，FUSE 上 sdcard 不分大小写）：a.txt → a (1).txt → a (2).txt … */
    public static String unique(String name, Collection<String> existing) {
        Set<String> have = new HashSet<>();
        for (String e : existing) have.add(e.toLowerCase(Locale.ROOT));
        if (!have.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String c = stem + " (" + i + ")" + ext;
            if (!have.contains(c.toLowerCase(Locale.ROOT))) return c;
        }
        return stem + " (" + System.currentTimeMillis() + ")" + ext;
    }
}
