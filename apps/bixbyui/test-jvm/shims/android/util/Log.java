package android.util;

/**
 * JVM 单测用的 android.util.Log 替身（只在 test-jvm 运行时 classpath 上，排在 android.jar 之前；
 * 不参与 APK 构建）。android.jar 里的真 Log 是 stub，任何调用都会抛 RuntimeException("Stub!")。
 *
 * 默认静默；设置环境变量 BIXBY_TEST_LOG=1 时打印到 stderr。
 */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    private static final boolean ON = "1".equals(System.getenv("BIXBY_TEST_LOG"));

    /** 测试捕获（审计 B-8 用）：非 null 时每条日志以 "W/tag: msg" 追加进来。默认 null = 不捕获。 */
    public static volatile java.util.List<String> sCapture;

    private Log() {}

    private static int p(String lvl, String tag, String msg, Throwable tr) {
        java.util.List<String> cap = sCapture;
        if (cap != null) cap.add(lvl + "/" + tag + ": " + msg);
        if (ON) {
            System.err.println(lvl + "/" + tag + ": " + msg);
            if (tr != null) tr.printStackTrace(System.err);
        }
        return 0;
    }

    public static int v(String t, String m) { return p("V", t, m, null); }
    public static int v(String t, String m, Throwable tr) { return p("V", t, m, tr); }
    public static int d(String t, String m) { return p("D", t, m, null); }
    public static int d(String t, String m, Throwable tr) { return p("D", t, m, tr); }
    public static int i(String t, String m) { return p("I", t, m, null); }
    public static int i(String t, String m, Throwable tr) { return p("I", t, m, tr); }
    public static int w(String t, String m) { return p("W", t, m, null); }
    public static int w(String t, String m, Throwable tr) { return p("W", t, m, tr); }
    public static int w(String t, Throwable tr) { return p("W", t, "", tr); }
    public static int e(String t, String m) { return p("E", t, m, null); }
    public static int e(String t, String m, Throwable tr) { return p("E", t, m, tr); }
    public static int wtf(String t, String m) { return p("A", t, m, null); }
    public static int wtf(String t, String m, Throwable tr) { return p("A", t, m, tr); }
    public static int wtf(String t, Throwable tr) { return p("A", t, "", tr); }
    public static boolean isLoggable(String t, int level) { return ON; }
    public static int println(int priority, String t, String m) { return p(String.valueOf(priority), t, m, null); }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
