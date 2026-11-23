package com.onesay.assistant.v2.pclink;

import android.Manifest;
import android.app.Notification;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * [overnight-1119] 文件互传的 Android 一侧：
 * <ul>
 *   <li>收（电脑 → 手机，file_put）：先收进 App 自己的暂存目录，核对过才落到「下载 / OneSay」——有存储权限直接写公共目录；
 *       Android 10+ 没权限时走媒体库（Downloads，relative_path = Download/OneSay，App 自己插入的文件不要权限）；都不行放 App 私有目录并如实说。
 *       收完弹一条通知。不打开、不安装、不覆盖。</li>
 *   <li>发（手机 → 电脑）：分享菜单「发到电脑」/ 控制台按钮选的文件先拷到缓存，再分块发，通知栏显示进度。</li>
 * </ul>
 * 日志只记大小和结果，不记文件名和内容。
 */
public final class PcFileAndroid {

    static final String TAG = "PcLink";
    static final int NID_FILE = 47880;

    private PcFileAndroid() {}

    private static PcFileRx sRx;

    /** 收文件的状态机（每个进程一个）。 */
    public static synchronized PcFileRx rx(Context c) {
        if (sRx == null) {
            final Context app = c.getApplicationContext();
            File staging = app.getExternalFilesDir("from_pc_part");
            if (staging == null) staging = new File(app.getFilesDir(), "from_pc_part");
            sRx = new PcFileRx(staging, new PcFileRx.Sink() {
                @Override public String place(File verified, String name) throws IOException {
                    String where = placeInDownloads(app, verified, name);
                    tellReceived(app, name, verified.length(), where);
                    return where;
                }
            });
        }
        return sRx;
    }

    /** 落盘：公共「下载 / OneSay」→ 媒体库 Downloads（Android 10+）→ App 私有目录。返回给人看的位置。 */
    static String placeInDownloads(Context app, File verified, String name) throws IOException {
        long size = verified.length();
        boolean perm = app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (perm) {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OneSay");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File fin = PcFileRx.unique(dir, name);
                    move(verified, fin);
                    MediaScannerConnection.scanFile(app, new String[]{fin.getAbsolutePath()}, null, null);
                    Log.i(TAG, "[PCLINK] 收到电脑文件 → 下载/OneSay bytes=" + size);
                    return "下载/OneSay/" + fin.getName();
                }
            } catch (Exception e) {
                Log.w(TAG, "[PCLINK] 写公共下载目录失败: " + e.getClass().getSimpleName());
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Uri u = null;
            ContentResolver cr = app.getContentResolver();
            try {
                ContentValues v = new ContentValues();
                v.put("_display_name", name);
                v.put("relative_path", PcFileRx.PHONE_DIR);
                v.put("is_pending", 1);
                u = cr.insert(Uri.parse("content://media/external/downloads"), v);
                if (u != null) {
                    OutputStream o = cr.openOutputStream(u);
                    InputStream in = new FileInputStream(verified);
                    try {
                        pipe(in, o);
                    } finally {
                        in.close();
                        o.close();
                    }
                    ContentValues done = new ContentValues();
                    done.put("is_pending", 0);
                    cr.update(u, done, null, null);
                    //noinspection ResultOfMethodCallIgnored
                    verified.delete();
                    Log.i(TAG, "[PCLINK] 收到电脑文件 → 媒体库 Download/OneSay bytes=" + size);
                    return "下载/OneSay/" + displayName(app, u, name);
                }
            } catch (Exception e) {
                Log.w(TAG, "[PCLINK] 写媒体库下载目录失败: " + e.getClass().getSimpleName());
                if (u != null) try { cr.delete(u, null, null); } catch (Exception ignored) { }
            }
        }
        File dir = app.getExternalFilesDir("from_pc");
        if (dir == null) dir = new File(app.getFilesDir(), "from_pc");
        String where = PcFileRx.dirSink(dir).place(verified, name);
        Log.i(TAG, "[PCLINK] 收到电脑文件 → App 私有目录 bytes=" + size);
        return "OneSay 私有目录（没有存储权限）：" + new File(where).getName();
    }

    static void tellReceived(Context app, String name, long size, String where) {
        try {
            Notification n = PcRxAndroid.notice(app, "收到电脑发来的文件", name + "（" + PcFileRx.human(size) + "）→ " + where);
            PcRxAndroid.channel(app).notify(NID_FILE + (int) (System.currentTimeMillis() % 100), n);
        } catch (Throwable ignored) {
        }
    }

    static String displayName(Context c, Uri u, String def) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (s != null && s.length() > 0) return s;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return def;
    }

    static void move(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        InputStream in = new FileInputStream(from);
        try {
            OutputStream o = new FileOutputStream(to);
            try {
                pipe(in, o);
            } finally {
                o.close();
            }
        } finally {
            in.close();
        }
        //noinspection ResultOfMethodCallIgnored
        from.delete();
    }

    static long pipe(InputStream in, OutputStream o) throws IOException {
        byte[] b = new byte[65536];
        long t = 0;
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            t += n;
        }
        return t;
    }

    // ================================================================== 手机 → 电脑

    public interface Done {
        void done(PcLanClient.Result r);
    }

    /** 分享过来的 content:// 或 file:// 拷到缓存后发给电脑（后台线程；通知栏显示进度；done 在后台线程回调）。 */
    public static void sendUri(final Context c, final Uri uri, final String nameHint, final Done done) {
        final Context app = c.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("pclink-file-send", new Runnable() {
            @Override public void run() {
                PcLanClient.Result r;
                File tmp = null;
                String name = nameHint;
                try {
                    if (name == null || name.length() == 0) name = displayName(app, uri, "");
                    if (name == null || name.length() == 0) name = uri.getLastPathSegment();
                    name = PcFileRx.safeName(name);
                    File dir = new File(app.getCacheDir(), "to_pc");
                    if (!dir.isDirectory()) dir.mkdirs();
                    tmp = new File(dir, "send-" + System.currentTimeMillis());
                    InputStream in = app.getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("打不开");
                    OutputStream o = new FileOutputStream(tmp);
                    try {
                        long n = 0;
                        byte[] b = new byte[65536];
                        int k;
                        while ((k = in.read(b)) > 0) {
                            n += k;
                            if (n > PcFileRx.MAX_SIZE) throw new IOException("太大");
                            o.write(b, 0, k);
                        }
                    } finally {
                        o.close();
                        in.close();
                    }
                    r = sendFile(app, tmp, name);
                } catch (Exception e) {
                    r = new PcLanClient.Result(false, "读不了这个文件：" + e.getMessage(), null, "");
                } finally {
                    if (tmp != null) //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                }
                if (done != null) done.done(r);
            }
        });
    }

    /** 发一个本机文件到电脑（阻塞；进度在通知栏）。 */
    public static PcLanClient.Result sendFile(final Context app, File f, final String name) {
        final int nid = NID_FILE + 50;
        final long[] lastTick = {0};
        PcLanClient.Result r = PcFileRx.send(f, name, new PcFileRx.Caller() {
            @Override public PcLanClient.Result call(String cmd, Map<String, Object> args) {
                return PcLink.runNow(app, cmd, args, 2500, 30000);
            }
        }, new PcFileRx.Progress() {
            @Override public boolean tick(long done, long total) {
                long now = System.currentTimeMillis();
                if (now - lastTick[0] < 700 && done < total) return true;
                lastTick[0] = now;
                try {
                    int pct = total <= 0 ? 100 : (int) (done * 100 / total);
                    Notification n = PcRxAndroid.builder(app).setSmallIcon(android.R.drawable.stat_sys_upload)
                            .setContentTitle("发到电脑：" + name).setContentText(PcFileRx.human(done) + " / " + PcFileRx.human(total))
                            .setProgress(100, pct, false).setOngoing(done < total).setOnlyAlertOnce(true).build();
                    PcRxAndroid.channel(app).notify(nid, n);
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        try {
            PcRxAndroid.channel(app).notify(nid, PcRxAndroid.notice(app, r.ok ? "已发到电脑" : "没发到电脑", r.text));
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "[PCLINK] 发文件到电脑 → " + (r.ok ? "ok" : "fail") + " bytes=" + f.length());
        return r;
    }
}
