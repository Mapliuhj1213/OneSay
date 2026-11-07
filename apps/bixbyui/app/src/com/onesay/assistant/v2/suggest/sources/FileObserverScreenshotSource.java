package com.onesay.assistant.v2.suggest.sources;

import android.content.Context;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Ports;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotCandidate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotJudge;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ShotEventGate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.WriteSettle;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ fix/shot-1104 截图 B 源（免 root 快路径）：{@link FileObserver}（内核 inotify）盯 DCIM/Screenshots + Pictures/Screenshots。
 *
 * <p>为什么换掉 root inotifyd 当默认：db1e0ce 真机 logcat 里 {@code Magisk: su: request rejected (10222)}——本 App 的 uid 不在
 * Magisk 授权名单（magisk.db policies 里 10222 = 1 拒绝），{@code su -c inotifyd} 每次立刻被拒，于是「启动失败 5/5 → 降级」；
 * 项目的 root 守护（exec 守护 v4）是文件 IPC、只跑白名单里的一次性命令，没有 inotifyd，也不能常驻读流。
 * FileObserver 走的是同一个 inotify，只要有存储权限就能用，不需要 root。
 *
 * <p>事件：这台三星 Note8（Android 8，sdcardfs）实测系统截图<b>只报新建（n / CREATE）、不报写完（w / CLOSE_WRITE）</b>，
 * 所以认 CREATE | CLOSE_WRITE | MOVED_TO，任何一个到了都起一次「等写完」：每 150 ms 读一次大小，连续 2 次不变且 &gt; 0 才上报
 * （{@link WriteSettle#awaitFast}）；同一文件的多个事件由 {@link ShotEventGate} 合成一次。需要存储权限：没授权时不启动，
 * 授权后宿主调 {@link #start()}（A 源的重查发现授权时回调）。日志不记文件名。
 */
public final class FileObserverScreenshotSource implements SignalSource {
    static final String TAG = "Suggest";
    static final int MASK = FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;

    /** 相对外部存储根的截图目录（三星 = DCIM/Screenshots；AOSP / 部分 ROM = Pictures/Screenshots）。 */
    public static final String[] REL_DIRS = {"DCIM/Screenshots", "Pictures/Screenshots"};

    private final Context mCtx;
    private final Ports.FileProbe mFiles;
    private final ShotEventGate mGate = new ShotEventGate();
    private final List<FileObserver> mObs = new ArrayList<FileObserver>();
    private HandlerThread mThread;
    private Handler mH;
    private volatile Sink mSink;
    private volatile String mState = "未启动";

    public FileObserverScreenshotSource(Context ctx, Ports.FileProbe files) {
        mCtx = ctx.getApplicationContext();
        mFiles = files;
    }

    public String state() {
        return mState;
    }

    @Override public String type() {
        return Signal.SCREENSHOT;
    }

    @Override public synchronized void attach(Sink sink) {
        mSink = sink;
        mThread = new HandlerThread("suggest-fo");
        mThread.start();
        mH = new Handler(mThread.getLooper());
        mH.post(new Runnable() {
            @Override public void run() {
                start();
            }
        });
    }

    /** 有存储权限就开始盯（已经在盯 = 空操作）。任意线程；没权限只记状态。 */
    public synchronized void start() {
        if (mH == null || !mObs.isEmpty()) return;
        if (!MediaStoreScreenshotSource.storageGranted(mCtx)) {
            mState = "等存储权限";
            return;
        }
        File root = Environment.getExternalStorageDirectory();
        int n = 0;
        for (String rel : REL_DIRS) {
            final File dir = new File(root, rel);
            if (!dir.isDirectory()) continue;
            final String base = dir.getAbsolutePath();
            FileObserver o = new FileObserver(base, MASK) {
                @Override public void onEvent(int event, String name) {
                    if (name == null || name.length() == 0) return;
                    onFile(base + "/" + name);
                }
            };
            o.startWatching();
            mObs.add(o);
            n++;
        }
        mState = n > 0 ? "运行中（" + n + " 个目录）" : "没有截图目录（只靠 A 源）";
        Log.i(TAG, "[SUGGEST] 截图 B 源（免 root FileObserver）" + (n > 0 ? "已启动 dirs=" + n : "没找到截图目录，只靠 A 源"));
    }

    private void onFile(final String path) {   // FileObserver 自己的线程
        if (!ScreenshotJudge.pathLooksLikeScreenshot(path)) return;
        final Handler h = mH;
        if (h == null) return;
        final long seen = SystemClock.elapsedRealtime();
        if (!mGate.begin(path, seen)) return;
        h.post(new Runnable() {
            @Override public void run() {
                final String[] fg = ForegroundProbe.now(mCtx, false);   // 截图那一刻的前台（快路径不走 root）
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
                        int[] b = MediaStoreScreenshotSource.bounds(path);
                        long mtime = f.lastModified() > 0 ? f.lastModified() : System.currentTimeMillis();
                        ScreenshotCandidate c = new ScreenshotCandidate(path, -1, size, mtime, b[0], b[1], 0,
                                ScreenshotCandidate.SRC_INOTIFY, seen, true);
                        Map<String, String> a = c.toAttrs();
                        if (fg != null) {
                            a.put(ScreenshotCandidate.K_FG, fg[0]);
                            if (fg[1] != null) a.put(ScreenshotCandidate.K_FG_LABEL, fg[1]);
                        }
                        Log.i(TAG, "[SUGGEST] 截图 B 源：写完 " + b[0] + "x" + b[1] + " 用时 " + (SystemClock.elapsedRealtime() - seen) + "ms");
                        Sink s = mSink;
                        if (s != null) s.emit(new Signal(Signal.SCREENSHOT, seen, System.currentTimeMillis(), a));
                    }

                    @Override public void gaveUp(String reason) {
                        mGate.end(path, SystemClock.elapsedRealtime(), false);
                        Log.i(TAG, "[SUGGEST] 截图 B 源：没等到写完（" + reason + "）");
                    }
                });
            }
        });
    }

    @Override public synchronized void detach() {
        mSink = null;
        for (FileObserver o : mObs) {
            try {
                o.stopWatching();
            } catch (Throwable ignored) {
            }
        }
        mObs.clear();
        if (mThread != null) mThread.quitSafely();
        mThread = null;
        mH = null;
        mState = "已停止";
    }
}
