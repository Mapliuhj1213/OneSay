package com.onesay.assistant.v2.suggest.sources;

import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PathNorm;
import com.onesay.assistant.plug.suggest.core.Ports;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotCandidate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ShotEventGate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.WriteSettle;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * ★ feat/suggest 截图 B 源（快路径 + 兜底）：root 常驻 {@code inotifyd - /data/media/0/DCIM/Screenshots:wy …}，读 CLOSE_WRITE / MOVED_TO。
 *
 * <h3>不确定项 → 双路径 + 日志（上机核实）</h3>
 * <ul>
 *   <li>本项目的 root 守护（文件 IPC，v4 白名单）只跑一次性命令，不能常驻读流；这里直接 {@code su -c}。Magisk 单应用 su 在这台机上可能坏
 *       （ShellExecutor 注释），坏了就退避重试 5 次后<b>自动降级只用 A 源</b>；</li>
 *   <li>Android 8 的 toybox 有没有 inotifyd：没有就同样失败 → 降级；</li>
 *   <li>Pictures/Screenshots 可能不存在（inotifyd 遇到不存在的目录会整体退出）：三套目录轮换（两个都盯 → 只 DCIM → 只 Pictures）；</li>
 *   <li>输出行格式按三种认（{@link InotifydLine}），认不出只记「格式未识别」。</li>
 * </ul>
 * lower fs 路径换成 /sdcard 视图后读大小 / 宽高（读不到宽高为 0：B 源高置信，尺寸规则放行）。日志不记文件名。
 *
 * <h3>★ fix/shot-1104 真机结论（三星 Note8 / Android 8 / toybox 0.7.3）</h3>
 * <ul>
 *   <li>「启动失败 5/5」的原因：logcat {@code Magisk: su: request rejected (10222)}——App uid 不在 Magisk 授权名单（policies 里是拒绝），
 *       {@code su} 立刻被拒、inotifyd 根本没起来。exec 守护 v4 是一次性命令白名单（没有 inotifyd，也不能常驻读流），走不了。
 *       所以：<b>默认不再用本源</b>（prefs {@code root_inotify} 默认 false，debug {@code --es inotify on} 才开），默认 B 源换成免 root 的
 *       {@link FileObserverScreenshotSource}；开着时 su 第一次就被拒（几秒内退出、一行没输出）直接停，不再重试 5 次（每次都会打到 Magisk）。</li>
 *   <li>系统截图只报 {@code n}（新建），从不报 {@code w}（写完）：掩码改成 {@code :nwy}，认 n / w / y，任何一个到了都按
 *       150 ms × 2 次大小不变且 &gt; 0（{@link WriteSettle#awaitFast}）等写完再上报，同一文件多个事件合一次（{@link ShotEventGate}）。</li>
 * </ul>
 */
public final class RootInotifyScreenshotSource implements SignalSource {
    static final String TAG = "Suggest";
    static final String[][] DIR_SETS = {
            {"/data/media/0/DCIM/Screenshots", "/data/media/0/Pictures/Screenshots"},
            {"/data/media/0/DCIM/Screenshots"},
            {"/data/media/0/Pictures/Screenshots"},
    };
    static final int MAX_FAILS = 5;

    private final android.content.Context mCtx;
    private volatile Sink mSink;
    private volatile boolean mStop;
    private volatile Process mProc;
    private Thread mThread;
    private volatile String mState = "未启动";
    private final ShotEventGate mGate = new ShotEventGate();
    private final Ports.FileProbe mFiles;
    private android.os.HandlerThread mSettleThread;
    private volatile android.os.Handler mSettleH;

    public RootInotifyScreenshotSource(android.content.Context ctx, Ports.FileProbe files) {
        mCtx = ctx.getApplicationContext();
        mFiles = files;
    }

    /** su 第一次就被拒的判定：几秒内退出且一行输出都没有（Magisk 拒绝 / 没有 su / 没有 inotifyd 都是这样）。纯函数，JVM 可测。 */
    static boolean suRejectedFast(int attempt, long aliveMs, int lines) {
        return attempt == 0 && lines == 0 && aliveMs < 3000L;
    }

    public String state() {
        return mState;
    }

    @Override public String type() {
        return Signal.SCREENSHOT;
    }

    static String cmd(String[] dirs) {
        StringBuilder b = new StringBuilder("inotifyd -");
        for (String d : dirs) b.append(' ').append(d).append(":nwy");   // n = 新建（这台机唯一会报的），w = 写完，y = 移入
        return b.toString();
    }

    @Override public synchronized void attach(Sink sink) {
        mSink = sink;
        mStop = false;
        mSettleThread = new android.os.HandlerThread("suggest-inotify-settle");
        mSettleThread.start();
        mSettleH = new android.os.Handler(mSettleThread.getLooper());
        mThread = com.onesay.assistant.v2.util.Threads.start("suggest-inotify", new Runnable() {   // 统一线程入口（ThreadsStartTest：不许裸 new Thread）
            @Override public void run() {
                loop();
            }
        });
    }

    private void loop() {
        int fails = 0, everRan = 0;
        while (!mStop && fails < MAX_FAILS) {
            String[] dirs = DIR_SETS[fails % DIR_SETS.length];
            long t0 = SystemClock.elapsedRealtime();
            int lines = runOnce(dirs);
            long alive = SystemClock.elapsedRealtime() - t0;
            if (mStop) break;
            if (suRejectedFast(fails, alive, lines) && everRan == 0) {
                mState = "su 被拒（只用 A 源 + 免 root 监听）";
                Log.w(TAG, "[SUGGEST] 截图 B 源（root inotifyd）：su 立刻失败（Magisk 未授权本应用 / 没有 su）→ 不再重试，截图靠 A 源 + 免 root FileObserver");
                return;
            }
            if (lines > 0) everRan++;
            if (alive < 5000L && lines == 0) {
                fails++;
                long back = 1000L << Math.min(4, fails - 1);
                mState = "启动失败 " + fails + "/" + MAX_FAILS;
                Log.w(TAG, "[SUGGEST] 截图 B 源（root inotifyd）启动失败 " + fails + "/" + MAX_FAILS + "，" + back + "ms 后重试（dirs=" + dirs.length + "）");
                try {
                    Thread.sleep(back);
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                fails = 0;   // 跑过一阵才退出：当作正常，立即重开
            }
        }
        if (!mStop) {
            mState = "已降级（只用 A 源）";
            Log.w(TAG, "[SUGGEST] 截图 B 源不可用，降级只用 MediaStore A 源");
        }
    }

    private int runOnce(String[] dirs) {
        int n = 0;
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd(dirs)});
            mProc = p;
            mState = "运行中";
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            boolean warned = false;
            while (!mStop && (line = r.readLine()) != null) {
                n++;
                String[] ev = InotifydLine.parse(line);
                if (ev == null) {
                    if (!warned) Log.w(TAG, "[SUGGEST] 截图 B 源：输出格式未识别（len=" + line.length() + "）");
                    warned = true;
                    continue;
                }
                onFile(ev[1]);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 截图 B 源异常: " + t.getClass().getSimpleName());
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
            Process p = mProc;
            if (p != null) p.destroy();
            mProc = null;
        }
        return n;
    }

    private void onFile(String lowerPath) {
        final String path = PathNorm.norm(lowerPath);   // /data/media/0/… → /sdcard/…（App 能读的视图）
        final long seen = SystemClock.elapsedRealtime();
        final android.os.Handler h = mSettleH;
        if (h == null || !mGate.begin(path, seen)) return;   // 同一文件 n / w / y 连报只等一次
        final String[] fg = ForegroundProbe.now(mCtx, false);   // 快路径：不走 root，免得拖慢
        WriteSettle.awaitFast(path, mFiles, new Ports.Sched() {
            @Override public Ports.Cancel schedule(final Runnable r, long delayMs) {
                h.postDelayed(r, delayMs);
                return new Ports.Cancel() {
                    @Override public void cancel() {
                        h.removeCallbacks(r);
                    }
                };
            }
        }, new WriteSettle.Callback() {
            @Override public void settled(long size) {
                mGate.end(path, SystemClock.elapsedRealtime(), true);
                File f = new File(path);
                long mtime = f.lastModified() > 0 ? f.lastModified() : System.currentTimeMillis();
                int[] b = MediaStoreScreenshotSource.bounds(path);
                ScreenshotCandidate c = new ScreenshotCandidate(path, -1, size, mtime, b[0], b[1], 0,
                        ScreenshotCandidate.SRC_INOTIFY, seen, true);
                Map<String, String> a = c.toAttrs();
                if (fg != null) {
                    a.put(ScreenshotCandidate.K_FG, fg[0]);
                    if (fg[1] != null) a.put(ScreenshotCandidate.K_FG_LABEL, fg[1]);
                }
                Sink s = mSink;
                if (s != null) s.emit(new Signal(Signal.SCREENSHOT, seen, System.currentTimeMillis(), a));
            }

            @Override public void gaveUp(String reason) {
                mGate.end(path, SystemClock.elapsedRealtime(), false);
                Log.i(TAG, "[SUGGEST] 截图 B 源（root）：没等到写完（" + reason + "）");
            }
        });
    }

    @Override public synchronized void detach() {
        mStop = true;
        mSink = null;
        Process p = mProc;
        if (p != null) p.destroy();
        if (mThread != null) mThread.interrupt();
        mThread = null;
        if (mSettleThread != null) mSettleThread.quitSafely();
        mSettleThread = null;
        mSettleH = null;
        mState = "已停止";
    }
}
