package com.onesay.assistant.v2.util;

/**
 * [logcat-1114] 往 logcat 写的每一行先过本地脱敏（{@link com.onesay.assistant.v2.privacy.Redactor}：手机号、身份证、银行卡、邮箱、验证码），
 * 全角数字 / 全角 ＠、零宽字符、不换行 / 全角空格拆开的写法也认（Redactor 先折叠再认）。debug 包和 release 包都一样。
 *
 * <p>起因：2026-11-14 第三轮真机，用户原话里的全角手机号 / 邮箱 / 验证码被 L0RuleRouter、UnifiedRouter、FloatingSheetService、
 * AgentLoop、PhoneAgent「结束」行原样写进 logcat（logcat 谁都能读，bugreport 也会带走）。这些类的日志一律改走这里。
 * 带异常的重载把堆栈也拼进正文一起脱敏（异常信息里也可能有原话）。
 */
public final class SafeLog {

    private SafeLog() {}

    /** 脱敏后的日志正文。每次用新的 Redactor（占位符只在这一行里编号，不留映射）；脱敏本身出错时只给长度，不回退到原文。 */
    public static String clean(Object msg) {
        if (msg == null) return "null";
        String s = msg.toString();
        if (s.length() == 0) return s;
        try {
            return new com.onesay.assistant.v2.privacy.Redactor().redact(s);
        } catch (Throwable t) {
            return LogText.len(s);
        }
    }

    static String withTrace(String msg, Throwable tr) {
        if (tr == null) return msg;
        java.io.StringWriter sw = new java.io.StringWriter();
        try {
            tr.printStackTrace(new java.io.PrintWriter(sw));
        } catch (Throwable ignore) {
            sw.write(String.valueOf(tr));
        }
        return (msg == null ? "" : msg) + "\n" + sw;
    }

    public static int v(String tag, String msg) { return android.util.Log.v(tag, clean(msg)); }
    public static int d(String tag, String msg) { return android.util.Log.d(tag, clean(msg)); }
    public static int i(String tag, String msg) { return android.util.Log.i(tag, clean(msg)); }
    public static int w(String tag, String msg) { return android.util.Log.w(tag, clean(msg)); }
    public static int e(String tag, String msg) { return android.util.Log.e(tag, clean(msg)); }
    public static int v(String tag, String msg, Throwable tr) { return android.util.Log.v(tag, clean(withTrace(msg, tr))); }
    public static int d(String tag, String msg, Throwable tr) { return android.util.Log.d(tag, clean(withTrace(msg, tr))); }
    public static int i(String tag, String msg, Throwable tr) { return android.util.Log.i(tag, clean(withTrace(msg, tr))); }
    public static int w(String tag, String msg, Throwable tr) { return android.util.Log.w(tag, clean(withTrace(msg, tr))); }
    public static int e(String tag, String msg, Throwable tr) { return android.util.Log.e(tag, clean(withTrace(msg, tr))); }
}
