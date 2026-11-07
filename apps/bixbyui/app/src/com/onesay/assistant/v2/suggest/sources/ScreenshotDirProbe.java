package com.onesay.assistant.v2.suggest.sources;

import android.os.FileObserver;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ feat/suggest 方式 C 的调试对照（只 debug 包挂）：应用内 FileObserver 盯 /sdcard/…/Screenshots，<b>只记日志、不发信号</b>。
 * 用来上机留证据：Android 8 sdcardfs 上 SystemUI 写的截图，应用进程到底收不收得到事件（核实项 3）。日志不含文件名。
 */
public final class ScreenshotDirProbe {
    static final String TAG = "Suggest";
    static final String[] DIRS = {"/sdcard/DCIM/Screenshots", "/sdcard/Pictures/Screenshots"};
    private final List<FileObserver> mObs = new ArrayList<FileObserver>();

    public synchronized void start() {
        stop();
        for (final String d : DIRS) {
            FileObserver o = new FileObserver(d, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
                @Override public void onEvent(int event, String path) {
                    Log.i(TAG, "[SUGGEST] C 对照：FileObserver 收到事件 mask=" + (event & FileObserver.ALL_EVENTS) + " dir=" + (d.contains("DCIM") ? "DCIM" : "Pictures"));
                }
            };
            try {
                o.startWatching();
                mObs.add(o);
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "[SUGGEST] C 对照已挂 " + mObs.size() + " 个目录（只记日志）");
    }

    public synchronized void stop() {
        for (FileObserver o : mObs) {
            try {
                o.stopWatching();
            } catch (Throwable ignored) {
            }
        }
        mObs.clear();
    }
}
