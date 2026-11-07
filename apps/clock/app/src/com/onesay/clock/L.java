package com.onesay.clock;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 日志：logcat（tag BixbyClock）+ files/bc.log（512 KB 滚动一份）。约定：密钥只记长度，绝不写值。 */
public final class L {
    private L() { }

    public static final String TAG = "BixbyClock";
    private static final long MAX = 512 * 1024;
    private static final Object LOCK = new Object();

    public static void i(Context c, String msg) { Log.i(TAG, msg); file(c, "I", msg); }

    public static void w(Context c, String msg) { Log.w(TAG, msg); file(c, "W", msg); }

    public static void e(Context c, String msg, Throwable t) {
        Log.e(TAG, msg, t);
        file(c, "E", msg + (t == null ? "" : " :: " + t));
    }

    public static File file(Context c) { return new File(c.getFilesDir(), "bc.log"); }

    private static void file(Context c, String lv, String msg) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.length() > MAX) {
                    File old = new File(c.getFilesDir(), "bc.log.1");
                    old.delete();
                    f.renameTo(old);
                }
                FileOutputStream o = new FileOutputStream(f, true);
                try {
                    String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                    o.write((ts + " " + lv + " " + msg + "\n").getBytes("UTF-8"));
                } finally { o.close(); }
            } catch (Throwable ignored) { }
        }
    }
}
