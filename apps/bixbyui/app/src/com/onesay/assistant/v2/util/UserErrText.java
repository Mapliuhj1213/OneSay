package com.onesay.assistant.v2.util;

/**
 * ★ 1123-dui B37：给用户看的错误文字去掉内部路径和异常类名，只留一句大白话。
 * 原文照样进日志，这里只管界面上显示什么。
 */
public final class UserErrText {
    private UserErrText() {}

    /** 带手机内部路径（/data/…、/storage/…、/sdcard/…）或 Java 异常类名的错误，换成 fallback；其余原样。 */
    public static String scrub(String err, String fallback) {
        if (err == null) return fallback;
        String t = err.trim();
        if (t.length() == 0) return fallback;
        if (t.indexOf("/data/") >= 0 || t.indexOf("/storage/") >= 0 || t.indexOf("/sdcard") >= 0
                || t.indexOf("Exception") >= 0 || t.indexOf("Error:") >= 0 || t.indexOf("java.") >= 0) {
            return fallback;
        }
        return t;
    }
}
