package com.onesay.assistant.v2.util;

/**
 * r2/fixes 13：日志里的<b>用户原文</b>（识别结果、输入指令、问答答复、模型自述、工具观测、跑腿进度等）统一过这里。
 *
 * <ul>
 *   <li>release 包：只记长度 {@code ‹N字›}，不出现任何原文字符（logcat 谁都能读，bugreport 也会带走）。</li>
 *   <li>debug 包：照旧打原文（单行、截断），方便上机排查。</li>
 * </ul>
 * {@code BuildVariant.IS_DEBUG} 是编译期常量，release 里 debug 分支会被 javac 直接剪掉。
 */
public final class LogText {

    private LogText() {}

    /** 默认截断长度（只在 debug 包生效）。 */
    public static final int DEBUG_MAX = 200;

    /** 用户原文（任意对象取 toString）→ release 只给长度，debug 给单行截断原文。 */
    public static String u(Object s) {
        return u(s, DEBUG_MAX);
    }

    public static String u(Object s, int debugMax) {
        if (s == null) return "null";
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return len(s);
        return debugText(s, debugMax);
    }

    /**
     * debug 包的写法：单行、截断；[logcat-1114] 先过 {@link SafeLog#clean} 脱敏（手机号 / 身份证 / 银行卡 / 邮箱 / 验证码，含全角写法），
     * debug 包也不把这些原样写进 logcat。单独成方法是为了 JVM（按 release 口径编译）也能测到这一支。
     */
    static String debugText(Object s, int debugMax) {
        if (s == null) return "null";
        String t = SafeLog.clean(s.toString()).replace('\n', ' ').replace('\r', ' ');
        return t.length() <= debugMax ? t : t.substring(0, Math.max(0, debugMax)) + "…(+" + (t.length() - debugMax) + ")";
    }

    /** 只给长度（两种包都一样）。 */
    public static String len(Object s) {
        return s == null ? "null" : "‹" + String.valueOf(s).length() + "字›";
    }

    /**
     * 工具 / 技能参数（JSONObject 或其字符串形式）：release 只列键名，值不出现（短信正文、收件人、搜索词都在值里）；debug 原样截断。
     */
    public static String args(Object jsonArgs) {
        if (jsonArgs == null) return "null";
        if (com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return u(String.valueOf(jsonArgs), 400);
        org.json.JSONObject o = null;
        if (jsonArgs instanceof org.json.JSONObject) {
            o = (org.json.JSONObject) jsonArgs;
        } else {
            try { o = new org.json.JSONObject(String.valueOf(jsonArgs)); } catch (Exception ignore) { }
        }
        if (o == null) return len(String.valueOf(jsonArgs));
        StringBuilder sb = new StringBuilder("{");
        java.util.Iterator<String> it = o.keys();
        boolean first = true;
        while (it.hasNext()) {
            if (!first) sb.append(',');
            sb.append(it.next()).append("=…");
            first = false;
        }
        return sb.append('}').toString();
    }
}
