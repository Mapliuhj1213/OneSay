package com.onesay.clock.core;

/** 数字 → 中文读法（与 tools/bank_spec.py 逐字一致）。 */
public final class ZhNum {
    private ZhNum() { }

    private static final String D = "零一二三四五六七八九";

    /** 0~99；负数取绝对值；更大返回阿拉伯数字。 */
    public static String n(int v) {
        if (v < 0) v = -v;
        if (v > 99) return Integer.toString(v);
        if (v < 10) return String.valueOf(D.charAt(v));
        if (v < 20) return v == 10 ? "十" : "十" + D.charAt(v - 10);
        int t = v / 10, o = v % 10;
        return D.charAt(t) + "十" + (o == 0 ? "" : String.valueOf(D.charAt(o)));
    }

    public static String deg(int t) { return (t < 0 ? "零下" : "") + n(t); }

    public static String hour(int h) { return (h == 2 ? "两" : n(h)) + "点"; }

    public static String minute(int m) {
        if (m == 0) return "整，";
        return (m < 10 ? "零" + D.charAt(m) : n(m)) + "分，";
    }
}
