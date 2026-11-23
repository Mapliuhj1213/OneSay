package com.onesay.assistant.v2.util;

/**
 * 顶层指令入口的兜底（a8fix-1118）：任何解析器里冒出的 PatternSyntaxException / 类初始化失败 / 运行时异常，
 * 都不能让 App 崩溃 —— 记日志，面板给一句友好提示。纯 Java，可单测。
 */
public final class CommandGuard {

    private CommandGuard() {}

    /** 正则 / 系统版本不兼容一类（老 Android）。 */
    public static final String MSG_COMPAT = "这条指令在当前系统版本上处理出错了，已记下日志。可以换个说法再试一次。";
    /** 其他意外异常。 */
    public static final String MSG_GENERIC = "处理这条指令时出了点问题，已记下日志。可以换个说法再试一次。";

    /** 给用户看的一句话（不带异常细节、不带堆栈）。 */
    public static String userMessage(Throwable t) {
        return RegexCompat.isRegexCompatFailure(t) ? MSG_COMPAT : MSG_GENERIC;
    }

    /** 日志用：异常类 + 描述（正则异常带 description，不带用户原话）。 */
    public static String logLine(String where, Throwable t) {
        StringBuilder b = new StringBuilder("[CMD-GUARD] ").append(where).append(" 异常已兜住");
        if (RegexCompat.isRegexCompatFailure(t)) b.append("（正则 / 系统版本不兼容）");
        b.append(": ").append(t == null ? "null" : t.getClass().getName());
        Throwable c = t;
        for (int i = 0; c != null && i < 8; i++, c = c.getCause()) {
            if (c instanceof java.util.regex.PatternSyntaxException) {
                b.append(" / regex: ").append(((java.util.regex.PatternSyntaxException) c).getDescription());
                break;
            }
        }
        return b.toString();
    }

    /** 包一层：body 里的 RuntimeException / LinkageError 交给 onFail，不往线程外抛（线程外抛 = 进程崩溃）。 */
    public static Runnable wrap(final String where, final Runnable body, final Failure onFail) {
        return new Runnable() {
            @Override public void run() {
                try {
                    body.run();
                } catch (RuntimeException | LinkageError t) {
                    SafeLog.e("CommandGuard", logLine(where, t), t);
                    if (onFail != null) {
                        try { onFail.onFailure(t, userMessage(t)); } catch (Throwable ignored) {}
                    }
                }
            }
        };
    }

    public interface Failure {
        void onFailure(Throwable t, String userMessage);
    }
}
