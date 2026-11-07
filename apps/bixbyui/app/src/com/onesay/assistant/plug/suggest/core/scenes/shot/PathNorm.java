/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import java.util.Locale;

/** 外部存储路径规范化：/storage/emulated/0、/storage/self/primary、/mnt/sdcard、/data/media/0 → /sdcard（A、B 两路同一文件得同一个键）。 */
public final class PathNorm {
    private PathNorm() {
    }

    private static final String[] PREFIXES = {"/storage/emulated/0/", "/storage/self/primary/", "/mnt/sdcard/", "/data/media/0/", "/mnt/user/0/primary/", "/sdcard/"};

    public static String norm(String p) {
        if (p == null) return "";
        String s = p.trim().replace('\\', '/');
        while (s.contains("//")) s = s.replace("//", "/");
        for (String pre : PREFIXES) {
            if (s.startsWith(pre)) return "/sdcard/" + s.substring(pre.length());
        }
        return s;
    }

    public static String fileName(String p) {
        String s = norm(p);
        int k = s.lastIndexOf('/');
        return k >= 0 ? s.substring(k + 1) : s;
    }

    public static String parentName(String p) {
        String s = norm(p);
        int k = s.lastIndexOf('/');
        if (k <= 0) return "";
        String dir = s.substring(0, k);
        int j = dir.lastIndexOf('/');
        return j >= 0 ? dir.substring(j + 1) : dir;
    }

    public static String lower(String p) {
        return norm(p).toLowerCase(Locale.ROOT);
    }
}
