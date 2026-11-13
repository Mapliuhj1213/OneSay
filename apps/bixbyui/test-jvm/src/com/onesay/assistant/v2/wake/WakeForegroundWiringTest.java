package com.onesay.assistant.v2.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * 唤醒修复（2026-11-08 真机：重装后 2 次 ANR + 2 次崩溃
 * 「Context.startForegroundService() did not then call Service.startForeground()」，ServiceRecord .v2.wake.WakeService）。
 *
 * <p>约定：经 startForegroundService 拉起的 WakeService，onCreate / onStartCommand 的<b>第一件事</b>就是前台声明；
 * 任何提前返回（没有录音权限 / Root 未就绪 / 暂停 / 模型缺失）、stopSelf 都只能出现在声明之后；
 * MainActivity 把 startForegroundService 推到本轮主线程消息之后，计时期间主线程不再被控制台构建占住。
 */
public class WakeForegroundWiringTest {

    private static final String WS = "app/src/com/onesay/assistant/v2/wake/WakeService.java";

    private static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(Paths.get(root, rel)), StandardCharsets.UTF_8);
    }

    /** 去掉注释和字符串 / 字符字面量（保留长度无关，只看代码 token）。 */
    static String stripCode(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                b.append('\n');
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i++;
                b.append(' ');
            } else if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n && s.charAt(i) != q) { if (s.charAt(i) == '\\') i++; i++; }
                b.append(q).append(q);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** 取方法体（签名之后第一个 { 到配对的 }，不含两端大括号）；签名找不到返回 null。 */
    static String body(String code, String signature) {
        int at = code.indexOf(signature);
        if (at < 0) return null;
        int open = code.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return code.substring(open + 1, i); }
        }
        return null;
    }

    private static final Pattern FG = Pattern.compile("\\b(ensureForeground|startForeground)\\s*\\(");
    private static final Pattern EXIT = Pattern.compile("\\breturn\\b|\\bstopSelf\\s*\\(|\\bstopForeground\\s*\\(|\\bthrow\\b");

    /**
     * 核心判定：方法体里前台声明出现了，而且在第一个 return / stopSelf / stopForeground / throw 之前。
     * 返回 null = 合格；否则返回原因。
     */
    static String foregroundBeforeAnyExit(String methodBody) {
        if (methodBody == null) return "方法没找到";
        String b = stripCode(methodBody);
        Matcher fg = FG.matcher(b);
        if (!fg.find()) return "没有前台声明";
        Matcher ex = EXIT.matcher(b);
        if (ex.find() && ex.start() < fg.start()) return "前台声明之前就有 '" + ex.group().trim() + "'";
        return null;
    }

    /** 声明之前只允许这些语句（super / sInstance 赋值）。 */
    private static List<String> statementsBeforeForeground(String methodBody) {
        String b = stripCode(methodBody);
        Matcher fg = FG.matcher(b);
        assertTrue(fg.find());
        List<String> out = new ArrayList<String>();
        for (String st : b.substring(0, fg.start()).split(";")) {
            String t = st.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 判定器自检（坏样例必须被抓住）

    @Test public void detectorCatchesEarlyReturnBeforeStartForeground() {
        String badMic = "{ sInstance = this; if (!hasMic()) { stopSelf(); return START_NOT_STICKY; } ensureForeground(\"x\"); return START_STICKY; }";
        String badRoot = "{ if (!rootReady) return START_NOT_STICKY; startForeground(1, n); return START_STICKY; }";
        String badDisabled = "{ if (disabled) { stopSelf(); } startForeground(1, n); return START_STICKY; }";
        String badModel = "{ if (!modelOk) throw new IllegalStateException(); ensureForeground(\"x\"); return START_STICKY; }";
        String missing = "{ bootEngine(); return START_STICKY; }";
        assertTrue(foregroundBeforeAnyExit(badMic) != null);
        assertTrue(foregroundBeforeAnyExit(badRoot) != null);
        assertTrue(foregroundBeforeAnyExit(badDisabled) != null);
        assertTrue(foregroundBeforeAnyExit(badModel) != null);
        assertEquals("没有前台声明", foregroundBeforeAnyExit(missing));
        // 注释 / 字符串里的 return 不算
        String ok = "{ // return early? no\n String s = \"return\"; ensureForeground(\"r\"); if (!hasMic()) { stopSelf(); return START_NOT_STICKY; } return START_STICKY; }";
        assertEquals(null, foregroundBeforeAnyExit(ok));
    }

    // ------------------------------------------------------------------ WakeService 本体

    @Test public void onStartCommandDeclaresForegroundFirst() throws Exception {
        String ws = stripCode(src(WS));
        String b = body(ws, "public int onStartCommand(Intent intent, int flags, int startId)");
        assertEquals(null, foregroundBeforeAnyExit(b));
        List<String> before = statementsBeforeForeground(b);
        assertTrue("onStartCommand 里前台声明之前只许 sInstance = this，实际 " + before,
                before.isEmpty() || (before.size() == 1 && before.get(0).equals("sInstance = this")));
    }

    @Test public void onCreateDeclaresForegroundBeforeAnyInit() throws Exception {
        String ws = stripCode(src(WS));
        String b = body(ws, "public void onCreate()");
        assertEquals(null, foregroundBeforeAnyExit(b));
        List<String> before = statementsBeforeForeground(b);
        for (String st : before) {
            assertTrue("onCreate 里前台声明之前只许 super.onCreate() / sInstance = this，实际有：" + st,
                    st.equals("super.onCreate()") || st.equals("sInstance = this"));
        }
        String code = stripCode(b);
        int fg = code.indexOf("ensureForeground(");
        for (String heavy : new String[]{"WakeTuning.get(", "AsrRouter.getInstance(", "initScreenState(", "updateWakeLock(", "Threads.start("}) {
            int h = code.indexOf(heavy);
            assertTrue(heavy + " 必须在前台声明之后", h < 0 || h > fg);
        }
    }

    @Test public void ensureForegroundCallsStartForegroundWithFallback() throws Exception {
        String ws = stripCode(src(WS));
        String b = body(ws, "private void ensureForeground(String why)");
        assertTrue("ensureForeground 存在", b != null);
        String code = stripCode(b);
        int n = 0, i = -1;
        while ((i = code.indexOf("startForeground(NOTIF_ID", i + 1)) >= 0) n++;
        assertTrue("主通知 + 最简通知重试，至少 2 处 startForeground，实际 " + n, n >= 2);
        assertTrue("声明失败不能把服务弄崩（catch Throwable）", code.contains("catch (Throwable"));
        assertFalse("ensureForeground 里不许 stopSelf", code.contains("stopSelf("));
    }

    /** 类里只要出现 stopSelf / stopForeground（将来「不该运行就停」的分支），onCreate 和 onStartCommand 都必须先声明前台。 */
    @Test public void everyStopSelfComesAfterForeground() throws Exception {
        String code = stripCode(src(WS));
        boolean stops = Pattern.compile("\\bstopSelf\\s*\\(|\\bstopForeground\\s*\\(").matcher(code).find();
        if (stops) {
            assertEquals(null, foregroundBeforeAnyExit(body(code, "public void onCreate()")));
            assertEquals(null, foregroundBeforeAnyExit(body(code, "public int onStartCommand(Intent intent, int flags, int startId)")));
        }
        // 无论有没有：onCreate 的声明必须在 bootEngine / 任何初始化之前已覆盖（见 onCreateDeclaresForegroundBeforeAnyInit）
        assertTrue(body(code, "public void onCreate()").contains("ensureForeground("));
    }

    /** 不会重启循环：服务自己不 stopSelf 再被 START_STICKY 拉起（现在一处都没有；以后加了必须先声明前台，见上）。 */
    @Test public void stickyWithoutSelfStopLoop() throws Exception {
        String code = stripCode(src(WS));
        String b = body(code, "public int onStartCommand(Intent intent, int flags, int startId)");
        assertTrue(b.contains("START_STICKY"));
        assertFalse("onStartCommand 里不许 stopService 自己", b.contains("stopService("));
    }

    // ------------------------------------------------------------------ 拉起方

    @Test public void mainActivityDefersStartForegroundServiceOffOnCreate() throws Exception {
        String ma = src("app/src/com/onesay/assistant/v2/ui/MainActivity.java");
        String code = stripCode(ma);
        String b = body(code, "private void ensureWakeServiceStarted()");
        assertTrue(b != null);
        int post = b.indexOf(".post(new Runnable()");
        int sfs = b.indexOf("startForegroundService(");
        assertTrue("MainActivity 的 startForegroundService 必须推到 post 之后（不在 onCreate 中途计时）", post >= 0 && sfs > post);
        // IN-3 照旧：onCreate 里仍调用，且在 mConsole.start() 之前
        String oc = body(code, "protected void onCreate(Bundle savedInstanceState)");
        assertTrue(oc.contains("ensureWakeServiceStarted();") && oc.indexOf("ensureWakeServiceStarted();") < oc.indexOf("mConsole.start();"));
    }

    /**
     * 开机 / 应用更新（MY_PACKAGE_REPLACED，正是重装后那一刻）：WakeService 必须先于面板服务（S3Scheduler.startRunner →
     * FloatingSheetService）被拉起 —— 两个 onCreate 排在同一条主线程上，11-08 traces 里主线程正卡在 FloatingSheetService.onCreate 的插件初始化。
     */
    @Test public void bootReceiverStartsWakeServiceBeforePanelService() throws Exception {
        String br = stripCode(src("app/src/com/onesay/assistant/v2/ui/BootReceiver.java"));
        int branch = br.indexOf("Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)");
        assertTrue(branch > 0);
        String b = br.substring(branch);
        int wake = b.indexOf("WakeService.class");
        int sfs = b.indexOf("startForegroundService(it)");
        int runner = b.indexOf("S3Scheduler.startRunner(");
        assertTrue("开机 / 更新分支仍拉起 WakeService", wake > 0 && sfs > wake);
        assertTrue("S3Scheduler.startRunner（面板服务）必须排在 WakeService 之后", runner > sfs);
    }

    /** 11-08 OSS traces 的现场：PowerWhitelistHooks.ensure 在服务 onCreate（主线程）里读 prefs → 读 prefs 必须在后台线程里。 */
    @Test public void powerWhitelistEnsureReadsPrefsOffMainThread() throws Exception {
        String code = stripCode(src("app/src/com/onesay/assistant/v2/jobs/PowerWhitelistHooks.java"));
        String b = body(code, "public static void ensure(final Context ctx)");
        assertTrue(b != null);
        int thread = b.indexOf("Threads.start(");
        assertTrue("ensure 里要起后台线程", thread > 0);
        for (String read : new String[]{"autoOn(", "sp(app)", "getString(", "getBoolean("}) {
            int r = b.indexOf(read);
            assertTrue(read + " 只能出现在后台线程里（Threads.start 之后）", r < 0 || r > thread);
        }
    }
}
