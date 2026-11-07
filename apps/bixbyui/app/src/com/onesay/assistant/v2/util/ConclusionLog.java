package com.onesay.assistant.v2.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 购物 / 调研结论原文进日志（用户 11-03：机上核价要看原文）。
 *
 * <p>release 的 {@link LogText#u} 只记长度，结论原文因此拿不到；这里**只给结论**开口子：截前 {@link #MAX_CHARS} 字，
 * 换行压成「⏎」，按 {@link #CHUNK_CHARS} 字分段（logcat 单行约 4 KB，中文 3 字节），每段带 i/n。
 * 结论是给用户看的回答本身，不含 key / 请求头 / 请求体；调用方不要把别的东西塞进来。
 */
public final class ConclusionLog {

    private ConclusionLog() {}

    public static final int MAX_CHARS = 1500;
    public static final int CHUNK_CHARS = 750;
    public static final String TAG = "BixbyConclusion";

    /** 组装日志行（纯函数，JVM 单测用）。 */
    public static List<String> lines(String kind, String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) text = "";
        int total = text.length();
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        t = t.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "⏎");
        int n = Math.max(1, (t.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int i = 0; i < n; i++) {
            int a = i * CHUNK_CHARS, b = Math.min(t.length(), a + CHUNK_CHARS);
            out.add("[CONCLUSION] " + kind + " len=" + total + (total > MAX_CHARS ? " (截前" + MAX_CHARS + ")" : "")
                    + " " + (i + 1) + "/" + n + " " + t.substring(a, b));
        }
        return out;
    }

    public static void log(String kind, String text) {
        try {
            for (String l : lines(kind, text)) android.util.Log.i(TAG, l);
        } catch (Throwable ignored) {
        }
    }
}
