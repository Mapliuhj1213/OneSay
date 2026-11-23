package com.onesay.desktop;

/**
 * [fix-1123-misc] 按字符截文字：最多留 n 个字符（码点，一个表情 / 生僻字算一个），绝不把一个字切成两半（不拆 UTF-16 代理对），
 * 截了才在末尾加 tail（通常是「…」）。null 当空串。
 */
public final class TextClip {

    private TextClip() {}

    public static String clip(String s, int n, String tail) {
        if (s == null) return "";
        if (n <= 0) return s.isEmpty() ? s : (tail == null ? "" : tail);
        if (s.length() <= n) return s; // UTF-16 长度不超 → 码点数也不超
        if (s.codePointCount(0, s.length()) <= n) return s;
        int end = s.offsetByCodePoints(0, n);
        return s.substring(0, end) + (tail == null ? "" : tail);
    }

    public static String clip(String s, int n) {
        return clip(s, n, "…");
    }
}
