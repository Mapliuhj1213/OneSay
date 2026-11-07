package com.onesay.assistant.v2.vision.screenref;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * [screenref] 唤醒时刻的屏幕采集 + 会话缓存（TTL 60 秒）。
 *
 * <h3>采集（一次 root shell 往返）</h3>
 * <pre>
 * rm -f P
 * dumpsys activity activities | grep -m 2 -E 'mResumedActivity|mFocusedActivity'   ← 前台包名/Activity
 * timeout 4 uiautomator dump --compressed P                                       ← 文字树（--compressed 去掉无意义布局层）
 * chown App uid + chmod 600 P + restorecon（PrivateScratch.fixupCmd）
 * </pre>
 * P 在 App 私有目录 cache/scratch/screenref（拿不到才退回外部缓存目录），读完立即删除（含屏幕隐私）。
 * 外部缓存兜底也不再 chmod 666，与 ScreenVisionEngine 一致一律 600 + chown（{@link #fixCmd}）。
 *
 * <h3>"在面板盖住屏幕之前"怎么保证</h3>
 * <p>uiautomator dump 读的是<b>活动窗口</b>（获得焦点的窗口）的无障碍树。面板默认可获焦，挂上去后 dump 到的就是自家面板。
 * 因此 {@link ScreenRefHooks#onWake} 在面板挂载<b>之前</b>给它临时加 FLAG_NOT_FOCUSABLE，采集完再恢复：
 * 前台 App 保持活动窗口，dump 拿到的是用户真正在看的界面；另外解析时再按包名丢掉自家节点做双保险。
 * 截图兜底（VL）只在需要时才发生，面板此时已显示 —— 沿用既有 ScreenVisionEngine 的"忽略底部助手面板"提示词。
 *
 * <h3>失败 → 兜底</h3>
 * dump 超时/报错、文字节点 &lt; 2（WebView/自绘/安全界面）、或 dump 到的是自家面板 → 标记 {@code needsVl}；
 * 只有用户真的说了指代（或 AgentLoop 调了 screen.context）时才按需调用既有截图 + Qwen-VL 路径，避免每次唤醒都花钱。
 */
public final class ScreenContextCapture {

    private static final String TAG = "ScreenRef";

    private ScreenContextCapture() {}

    private static final Object LOCK = new Object();
    private static ScreenContext sCurrent;
    private static CountDownLatch sInFlight;

    /** ★ r2/sysux P1-4：本次唤醒的识屏 dump 已排期 / 在途（ScreenRefHooks 写；面板这时不能抢焦点）。 */
    private static volatile boolean sWakeCaptureBusy;

    public static boolean wakeCaptureBusy() { return sWakeCaptureBusy; }

    static void setWakeCaptureBusy(boolean b) { sWakeCaptureBusy = b; }

    public interface Listener {
        void onCaptured(ScreenContext ctx);
    }

    /** 当前缓存（过期返回 null）。 */
    public static ScreenContext fresh() {
        synchronized (LOCK) {
            if (sCurrent != null && sCurrent.isFresh(System.currentTimeMillis(), ScreenRefSettings.CONTEXT_TTL_MS)) return sCurrent;
            return null;
        }
    }

    public static boolean inFlight() {
        synchronized (LOCK) {
            return sInFlight != null && sInFlight.getCount() > 0;
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            sCurrent = null;
        }
    }

    /** 若有在途采集，最多等 maxWaitMs；返回新鲜缓存或 null。（不可在主线程调用） */
    public static ScreenContext await(long maxWaitMs) {
        CountDownLatch l;
        synchronized (LOCK) {
            l = sInFlight;
        }
        if (l != null) {
            try {
                l.await(maxWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        return fresh();
    }

    /** 异步采集（唤醒时调用）。已有在途采集则不重复发起。 */
    public static void captureAsync(final Context context, final Listener listener) {
        final Context app = context.getApplicationContext();
        final CountDownLatch latch;
        synchronized (LOCK) {
            if (sInFlight != null && sInFlight.getCount() > 0) {
                Log.i(TAG, "[CAPTURE] 已有在途采集，跳过");
                return;
            }
            latch = new CountDownLatch(1);
            sInFlight = latch;
        }
        com.onesay.assistant.v2.util.Threads.start("ScreenRefCapture", new Runnable() {
            @Override
            public void run() {
                ScreenContext sc = null;
                try {
                    sc = captureNow(app, true);   // ★ R14：唤醒采集降优先级
                    synchronized (LOCK) {
                        sCurrent = sc;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[CAPTURE] 异常: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
                if (listener != null) {
                    try {
                        listener.onCaptured(sc);
                    } catch (Throwable t) {
                        Log.w(TAG, "[CAPTURE] 回调异常: " + t.getMessage());
                    }
                }
            }
        });
    }

    /**
     * [s1] 圈选即问：同步整屏采集（后台线程），<b>不</b>写入会话缓存（调用方会把框内的区域上下文 {@link #put} 进去）。
     * 有在途的唤醒采集时先等它结束，避免两次 dump 抢 root 通道。
     */
    public static ScreenContext captureSync(Context context) {
        await(ScreenRefSettings.AWAIT_CAPTURE_MS);
        return captureNow(context.getApplicationContext());
    }

    /** [s1] 用指定上下文替换会话缓存（圈选区域上下文；之后「复制这个/导航去这里」只在框内消解）。 */
    public static void put(ScreenContext sc) {
        synchronized (LOCK) {
            sCurrent = sc;
        }
    }

    /**
     * root 写完界面树后的修正命令（纯函数，JVM 测试守住）：不论私有目录还是外部缓存兜底，一律
     * chown 给目录属主（= App uid）+ chmod 600 + restorecon —— <b>任何目录都不放开成 666</b>（IPC-5，与 ScreenVisionEngine 一致）。
     */
    static String fixCmd(String path, String dir) {
        return com.onesay.assistant.v2.util.PrivateScratch.fixupCmd(path, dir);
    }

    /** 前台 Activity（单条：只读命令 | 过滤器，v4 受控管道）。{@code -m2} 写成一个词：v4 的 grep 规则把 {@code -m 2} 的 2 当成文件名。 */
    static final String ACTIVITY_CMD = "dumpsys activity activities | grep -m2 -E 'mResumedActivity|mFocusedActivity'";

    /** 界面树（单条）。注意：v4 守护的 uiautomator 规则目前不认 {@code --compressed}，需守护侧补白名单（INTEGRATION.md）。 */
    static String dumpCmd(String path) {
        return dumpCmd(path, false);
    }

    /** @param lowPriority true = 唤醒采集，dump 用 nice -n 10 跑（别和入场动画抢 CPU）。 */
    static String dumpCmd(String path, boolean lowPriority) {
        return (lowPriority ? "nice -n 10 " : "") + "uiautomator dump --compressed " + path;
    }

    /** 同步采集（后台线程）。 */
    static ScreenContext captureNow(Context app) {
        return captureNow(app, false);
    }

    /** @param lowPriority true = dump 用 nice 10 跑（唤醒采集：别和入场动画 / RenderThread 抢 CPU）。 */
    static ScreenContext captureNow(Context app, boolean lowPriority) {
        long t0 = SystemClock.elapsedRealtime();
        // ★ r2/fast-screen：先走常驻 root 读屏助手（热态约 10–60 ms）；不可用时 FsCapture 已替原路径让出 UiAutomation
        com.onesay.assistant.v2.fastscreen.FsCapture.Xml fast = com.onesay.assistant.v2.fastscreen.FsCapture.xml(app, true);
        if (fast != null) {
            long cost = SystemClock.elapsedRealtime() - t0;
            ScreenContext sc = ScreenContext.fromDump(fast.xml, app.getPackageName(), fast.activityLine,
                    System.currentTimeMillis(), TimeZone.getDefault(), cost);
            Log.i(TAG, "[CAPTURE] 完成 " + cost + "ms via=fast source=" + sc.source + " app=" + sc.pkg + "/" + sc.activity
                    + " texts=" + sc.texts.size() + " entities=" + sc.entities.size() + " redacted=" + sc.redacted
                    + (sc.error != null ? " note=" + sc.error : ""));
            return sc;
        }
        // 界面树（可能含聊天 / 验证码文字）先放私有目录，root 写完 chown + restorecon；拿不到才用外部缓存
        File dir = com.onesay.assistant.v2.util.PrivateScratch.dir(app, "screenref");
        boolean priv = dir != null;
        if (dir == null) {
            try {
                dir = app.getExternalCacheDir();
            } catch (Throwable ignored) {
            }
        }
        if (dir == null) dir = app.getCacheDir();
        File out = new File(dir, "sr_ui.xml");
        String p = out.getAbsolutePath();
        String fix = fixCmd(p, dir.getAbsolutePath());
        // 2026-10-25 exec 守护 v4（fix-root.md §3「识屏」）：不再发复合脚本，拆成两条单命令；界面树文件由 App 先建好
        //   （替代 rm -f），root 的 uiautomator 原地写，属主 / 标签仍是 App 的；读不了才退回 fix（复合脚本，v4 下需 allow_raw）。
        //   原来的 timeout 不在 v4 白名单里，去掉了：单条上限由守护（10 s）和 ScreenRefHooks 的看门狗兜。
        //   lowPriority 仍发 nice -n 10 前缀（v4 目前同样不认 nice，与 --compressed 一起列为守护侧缺口，见 INTEGRATION.md）。
        com.onesay.assistant.v2.util.PrivateScratch.prepare(out);
        ShellExecutor sh = new ShellExecutor(app);
        ShellExecutor.ShellResponse resp = sh.execute(ACTIVITY_CMD);
        if (resp != null && !resp.isDown) {
            sh.execute(dumpCmd(p, lowPriority));
            if (com.onesay.assistant.v2.util.PrivateScratch.needsFixup(out)) sh.execute(fix);
        }
        String xml = readAndDelete(out);
        long cost = SystemClock.elapsedRealtime() - t0;
        ScreenContext sc = ScreenContext.fromDump(xml, app.getPackageName(), resp != null ? resp.output : null,
                System.currentTimeMillis(), TimeZone.getDefault(), cost);
        if (resp != null && resp.isDown) {
            sc = new ScreenContext(sc.pkg, sc.activity, ScreenContext.SRC_NONE, sc.capturedAtMs, cost, null, null, null,
                    "root 守护离线", true, 0);
        }
        Log.i(TAG, "[CAPTURE] 完成 " + cost + "ms source=" + sc.source + " app=" + sc.pkg + "/" + sc.activity
                + " texts=" + sc.texts.size() + " entities=" + sc.entities.size() + " redacted=" + sc.redacted
                + (sc.error != null ? " note=" + sc.error : ""));
        Log.d(TAG, "[CAPTURE] 实体: " + sc.entities);
        return sc;
    }

    /**
     * 文字树不可用时，按需走既有截图 + Qwen-VL 路径（后台线程，阻塞 ≤ VL_TIMEOUT_MS）。
     * 成功则替换缓存；失败返回原上下文。
     */
    public static ScreenContext ensureVl(Context context, ScreenContext base) {
        if (!ScreenRefSettings.vlFallbackEnabled(context)) return base;
        long t0 = SystemClock.elapsedRealtime();
        String q = "请逐行原样抄录当前手机屏幕上可见的文字（忽略底部的语音助手悬浮面板和水波光效），"
                + "尤其是电话号码、地址、日期时间、网址链接、快递单号、取件码/取餐码/验证码、邮箱、金额。每行一条，不要解释、不要总结。";
        ScreenVisionEngine.VisionResult vr = ScreenVisionEngine.getInstance(context).recognizeScreenSync(q, ScreenRefSettings.VL_TIMEOUT_MS);
        long cost = SystemClock.elapsedRealtime() - t0;
        if (vr == null || !vr.ok) {
            Log.w(TAG, "[VL] 兜底失败 " + cost + "ms: " + (vr != null ? vr.text : "null"));
            return base;
        }
        ScreenContext sc = ScreenContext.fromVlText(base, vr.text, System.currentTimeMillis(), TimeZone.getDefault(), cost);
        synchronized (LOCK) {
            sCurrent = sc;
        }
        Log.i(TAG, "[VL] 兜底完成 " + cost + "ms entities=" + sc.entities);
        return sc;
    }

    private static String readAndDelete(File f) {
        if (f == null || !f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > 2 * 1024 * 1024) break;   // 异常大的 dump 截断
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "[CAPTURE] 读取 dump 失败: " + t.getMessage());
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }
}
