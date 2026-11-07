package com.onesay.assistant.v2.suggest.sources;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import com.onesay.assistant.plug.suggest.core.Ports;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SignalSource;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotCandidate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotJudge;
import com.onesay.assistant.plug.suggest.core.scenes.shot.GrantPoll;
import com.onesay.assistant.plug.suggest.core.scenes.shot.WriteSettle;
import com.onesay.assistant.v2.suggest.ForegroundProbe;

import java.io.File;
import java.util.Map;

/**
 * ★ feat/suggest 截图 A 源（主路径，不需要 root）：MediaStore ContentObserver（HandlerThread「suggest-ms」）→ 查 _ID 比上次大的最近 3 行 →
 * 只放过路径像截图的 → 当场取前台包名 → 宽高为 0 时用 BitmapFactory 只读文件头补（双路径，日志记用了哪条）→ WriteSettle 写完再 emit。
 * 需要 READ_EXTERNAL_STORAGE；没授权时 debug 包试一次 root pm grant，仍不行就只记「需要存储权限」（控制台显示）。
 * ★ fix/shot-1104：没授权时<b>不再只查一次就放弃</b>（Android 8 授予运行时权限不杀进程，原来用户事后授权也永远不注册）——
 * 按 {@link GrantPoll} 定时重查，另在亮屏 / 解锁 / {@link #poke()}（控制台回前台、授权页返回）时立刻查；变成已授予就地注册，
 * 并通过 {@link PermListener#onGranted()} 让宿主启动免 root 文件监听源。没授权期间观察者能注册就先注册：媒体库一有新图
 * 就回调 {@link PermListener#onNeedPermission()}（宿主据此弹一次「截图分析需要存储权限 · 去授权」），但不查任何行。
 * 日志只记来源、尺寸、原因，不记文件名。
 */
public final class MediaStoreScreenshotSource implements SignalSource {
    static final String TAG = "Suggest";
    private final Context mCtx;
    private final Ports.FileProbe mFiles;
    private HandlerThread mThread;
    private Handler mH;
    private ContentObserver mObs;
    private volatile Sink mSink;
    private long mLastId = -1;
    private volatile String mState = "未启动";
    private final GrantPoll mPoll = new GrantPoll();
    private BroadcastReceiver mScreenRx;
    private volatile PermListener mPermListener;
    private long mLastNeedAt;

    /** ★ fix/shot-1104：存储权限状态回调（在源线程上调）。 */
    public interface PermListener {
        /** 没授权时媒体库来了新图（大概率刚截了图）：宿主决定要不要弹一次「去授权」。最多 30 s 一次。 */
        void onNeedPermission();

        /** 刚从未授权变成已授权，A 源已就地注册。 */
        void onGranted();
    }

    public void setPermListener(PermListener l) {
        mPermListener = l;
    }

    public boolean granted() {
        return mPoll.granted();
    }

    public MediaStoreScreenshotSource(Context ctx, Ports.FileProbe files) {
        mCtx = ctx.getApplicationContext();
        mFiles = files;
    }

    public String state() {
        return mState;
    }

    @Override public String type() {
        return Signal.SCREENSHOT;
    }

    public static boolean storageGranted(Context ctx) {
        try {
            return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public synchronized void attach(Sink sink) {
        mSink = sink;
        mThread = new HandlerThread("suggest-ms");
        mThread.start();
        mH = new Handler(mThread.getLooper());
        mH.post(new Runnable() {
            @Override public void run() {
                if (!storageGranted(mCtx) && com.onesay.assistant.v2.BuildVariant.IS_DEBUG) {
                    try {   // debug 包：试一次 root pm grant（release 不做，由控制台 / 岛上一次性提示引导授权）
                        new com.onesay.assistant.v2.executor.ShellExecutor(mCtx).execute("pm grant " + mCtx.getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                    } catch (Throwable ignored) {
                    }
                }
                if (!storageGranted(mCtx)) {
                    mState = "需要存储权限";
                    Log.w(TAG, "[SUGGEST] 截图 A 源：没有存储权限，先不查媒体库；授权后自动注册（亮屏 / 解锁 / 回控制台立刻重查，平时每 "
                            + (GrantPoll.FAST_MS / 1000) + "–" + (GrantPoll.SLOW_MS / 1000) + " s 一次）");
                    mPoll.onCheck(false);
                    registerObserver();   // 能注册就先注册：新图只用来触发一次「去授权」提示，不查行
                    armWaitForGrant();
                    return;
                }
                mPoll.onCheck(true);
                register();
            }
        });
    }

    /** 主动重查一次存储权限（控制台回前台 / 授权页返回 / 宿主收到亮屏解锁时调）。任意线程。 */
    public void poke() {
        Handler h = mH;
        if (h == null || mPoll.granted()) return;
        h.removeCallbacks(mRecheck);
        h.post(mRecheck);
    }

    private final Runnable mRecheck = new Runnable() {
        @Override public void run() {
            Handler h = mH;
            if (h == null) return;
            h.removeCallbacks(this);
            if (mPoll.onCheck(storageGranted(mCtx))) {
                Log.i(TAG, "[SUGGEST] 存储权限已授予（第 " + mPoll.tries() + " 次重查）→ 就地注册截图 A 源，不用重启服务");
                disarmWaitForGrant();
                register();
                PermListener l = mPermListener;
                if (l != null) {
                    try {
                        l.onGranted();
                    } catch (Throwable t) {
                        Log.w(TAG, "[SUGGEST] onGranted 回调异常: " + t.getClass().getSimpleName());
                    }
                }
                return;
            }
            if (!mPoll.granted()) h.postDelayed(this, mPoll.nextDelayMs());
        }
    };

    private void armWaitForGrant() {
        Handler h = mH;
        if (h == null) return;
        if (mScreenRx == null) {
            mScreenRx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    poke();
                }
            };
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_USER_PRESENT);
                mCtx.registerReceiver(mScreenRx, f, null, h);
            } catch (Throwable t) {
                mScreenRx = null;
            }
        }
        h.removeCallbacks(mRecheck);
        h.postDelayed(mRecheck, mPoll.nextDelayMs());
    }

    private void disarmWaitForGrant() {
        Handler h = mH;
        if (h != null) h.removeCallbacks(mRecheck);
        if (mScreenRx != null) {
            try {
                mCtx.unregisterReceiver(mScreenRx);
            } catch (Throwable ignored) {
            }
        }
        mScreenRx = null;
    }

    private void register() {
        mLastId = maxId();
        if (mObs == null) registerObserver();
        if (mObs != null) {
            mState = "运行中";
            Log.i(TAG, "[SUGGEST] 截图 A 源已注册（MediaStore）");
        }
    }

    private void registerObserver() {
        if (mObs != null) return;
        ContentObserver obs = new ContentObserver(mH) {
            @Override public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override public void onChange(boolean selfChange, Uri uri) {
                Handler h = mH;
                if (h == null) return;
                if (!mPoll.granted()) {   // 还没授权：不查行，只当「刚来了新图」的触发
                    long now = SystemClock.elapsedRealtime();
                    if (now - mLastNeedAt < 30000L) return;
                    mLastNeedAt = now;
                    poke();   // 顺手重查一次（也许刚授权）
                    PermListener l = mPermListener;
                    if (l != null && !storageGranted(mCtx)) l.onNeedPermission();
                    return;
                }
                h.removeCallbacks(mQuery);
                h.postDelayed(mQuery, 120L);   // 一次插入常连着回调几次：合一下
            }
        };
        try {
            mCtx.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs);
            mObs = obs;
        } catch (Throwable t) {
            if (mPoll.granted()) mState = "注册失败";
            Log.w(TAG, "[SUGGEST] 截图 A 源观察者注册失败" + (mPoll.granted() ? "" : "（未授权）") + ": " + t.getClass().getSimpleName());
        }
    }

    private final Runnable mQuery = new Runnable() {
        @Override public void run() {
            query();
        }
    };

    private long maxId() {
        Cursor c = null;
        try {
            c = mCtx.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID},
                    null, null, MediaStore.Images.Media._ID + " DESC LIMIT 1");
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private void query() {
        Cursor c = null;
        long[] ids = new long[3];
        String[] paths = new String[3];
        long[] added = new long[3], modified = new long[3], sizes = new long[3];
        int[] ws = new int[3], hs = new int[3];
        int n = 0;
        try {
            ContentResolver cr = mCtx.getContentResolver();
            c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA,
                            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.WIDTH,
                            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE},
                    MediaStore.Images.Media._ID + ">?", new String[]{String.valueOf(mLastId)}, MediaStore.Images.Media._ID + " DESC LIMIT 3");
            while (c != null && c.moveToNext() && n < 3) {
                ids[n] = c.getLong(0);
                paths[n] = c.getString(1);
                added[n] = c.getLong(2);
                modified[n] = c.getLong(3);
                ws[n] = c.getInt(4);
                hs[n] = c.getInt(5);
                sizes[n] = c.getLong(6);
                n++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 截图 A 源查询失败: " + t.getClass().getSimpleName());
        } finally {
            if (c != null) c.close();
        }
        for (int i = n - 1; i >= 0; i--) {   // 旧 → 新
            if (ids[i] > mLastId) mLastId = ids[i];
            if (!ScreenshotJudge.pathLooksLikeScreenshot(paths[i])) continue;
            handle(ids[i], paths[i], added[i], modified[i], ws[i], hs[i]);
        }
    }

    private void handle(final long id, final String path, final long addedSec, long modifiedSec, int w, int h) {
        final long seen = SystemClock.elapsedRealtime();
        final String[] fg = ForegroundProbe.now(mCtx, true);   // 截图那一刻的前台（源线程上可以走 root 兜底）
        if (w <= 0 || h <= 0) {   // 媒体库刚插入时宽高可能还没填：只读文件头补
            int[] b = bounds(path);
            Log.i(TAG, "[SUGGEST] 截图 A 源：媒体库宽高为空，读文件头 → " + b[0] + "x" + b[1]);
            w = b[0];
            h = b[1];
        }
        File f = new File(path);
        final long mtime = f.lastModified() > 0 ? f.lastModified() : modifiedSec * 1000L;
        final int fw = w, fh = h;
        WriteSettle.await(path, mFiles, new Ports.Sched() {
            @Override public Ports.Cancel schedule(final Runnable r, long delayMs) {
                mH.postDelayed(r, delayMs);
                return new Ports.Cancel() {
                    @Override public void cancel() {
                        mH.removeCallbacks(r);
                    }
                };
            }
        }, new WriteSettle.Callback() {
            @Override public void settled(long size) {
                ScreenshotCandidate cand = new ScreenshotCandidate(path, id, size, mtime, fw, fh, addedSec, ScreenshotCandidate.SRC_MEDIASTORE, seen, true);
                Map<String, String> a = cand.toAttrs();
                if (fg != null) {
                    a.put(ScreenshotCandidate.K_FG, fg[0]);
                    if (fg[1] != null) a.put(ScreenshotCandidate.K_FG_LABEL, fg[1]);
                }
                Sink s = mSink;
                if (s != null) s.emit(new Signal(Signal.SCREENSHOT, seen, System.currentTimeMillis(), a));
            }

            @Override public void gaveUp(String reason) {
                Log.i(TAG, "[SUGGEST] 截图 A 源：没等到写完（" + reason + "）");
            }
        });
    }

    static int[] bounds(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            return new int[]{Math.max(0, o.outWidth), Math.max(0, o.outHeight)};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    @Override public synchronized void detach() {
        mSink = null;
        disarmWaitForGrant();
        try {
            if (mObs != null) mCtx.getContentResolver().unregisterContentObserver(mObs);
        } catch (Throwable ignored) {
        }
        mObs = null;
        if (mThread != null) mThread.quitSafely();
        mThread = null;
        mH = null;
        mState = "已停止";
    }
}
