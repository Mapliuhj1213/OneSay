package com.onesay.assistant.v2.applog;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * [settings-diag-1124] 应用内日志的 Android 侧：初始化目录、崩溃时记一笔、导出打包 + 系统分享、清空。
 *
 * <p>初始化在 {@link LogShareProvider#onCreate}（进程一起来就跑，比任何 Activity / Service 都早），所以不用在各处补调用。
 */
public final class AppLogAndroid {

    private AppLogAndroid() {}

    private static volatile boolean sInit;

    public static File logDir(Context ctx) {
        return new File(ctx.getFilesDir(), "applog");
    }

    public static File exportDir(Context ctx) {
        return new File(logDir(ctx), "export");
    }

    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".logshare";
    }

    public static void init(Context ctx) {
        if (sInit || ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            AppLog.init(logDir(app));
            sInit = true;
            installCrashHook();
            AppLog.i("AppLog", "进程启动 v" + versionName(app) + " Android " + Build.VERSION.RELEASE + "/" + Build.VERSION.SDK_INT);
        } catch (Throwable ignored) {
        }
    }

    private static void installCrashHook() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    AppLog.e("Crash", "[CRASH] 线程 " + t.getName() + " 崩了\n" + sw);
                    AppLog.flush();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static String versionName(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 导出头部：版本、机型、系统、时间、日志大小、OneSay 自己的设置清单（不含任何 Key，只写配没配）。 */
    static String header(Context ctx) {
        StringBuilder b = new StringBuilder();
        b.append("OneSay 运行日志\n");
        b.append("导出时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(new java.util.Date())).append('\n');
        b.append("版本：").append(versionName(ctx)).append(com.onesay.assistant.v2.BuildVariant.IS_DEBUG ? " 调试包" : " 正式包").append('\n');
        b.append("手机：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
                .append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n");
        b.append("日志大小：").append(AppLog.sizeText(AppLog.totalBytes())).append('\n');
        try {
            b.append("\n==== OneSay 设置清单 ====\n");
            b.append(com.onesay.assistant.v2.selfset.SelfSettingsEngine.plainText(
                    com.onesay.assistant.v2.selfset.SelfSettingsEngine.snapshot(com.onesay.assistant.v2.selfset.SelfSettingsAndroid.store(ctx))));
        } catch (Throwable t) {
            b.append("（设置清单读不出来）\n");
        }
        b.append("\n说明：日志只在这台手机上，聊天原文只记字数，Key / 密码 / 请求内容不会写进来。\n");
        return b.toString();
    }

    /** 本应用自己的系统日志（应用本来就能读自己的，不需要无线调试或 Shizuku）。读不到返回 null。后台线程。 */
    static String ownLogcat(Context ctx) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", "4000"});
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = com.onesay.assistant.v2.util.Threads.start("applog-logcat", new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            synchronized (sb) {
                                if (sb.length() < 3 * 1024 * 1024) sb.append(line).append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.join(6000);
            List<Integer> pids = new ArrayList<Integer>();
            pids.add(android.os.Process.myPid());
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
                if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo i : ps) {
                    if (i.uid == android.os.Process.myUid() && !pids.contains(i.pid)) pids.add(i.pid);
                }
            } catch (Throwable ignored) {
            }
            String all;
            synchronized (sb) {
                all = sb.toString();
            }
            String own = AppLogExport.filterOwn(all, pids, ctx.getPackageName());
            return own.length() == 0 ? null : own;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 打包（后台线程）。@return 结果；失败抛异常（调用方给大白话提示）。 */
    public static AppLogExport.Result export(Context ctx) throws java.io.IOException {
        init(ctx);
        AppLog.i("AppLog", "导出日志");
        return AppLogExport.build(exportDir(ctx), header(ctx), ownLogcat(ctx), System.currentTimeMillis());
    }

    /** 用系统分享把 zip 发出去（主线程）。 */
    public static boolean share(Context ctx, File zip) {
        try {
            Uri uri = Uri.parse("content://" + authority(ctx) + "/" + Uri.encode(zip.getName()));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "OneSay 日志");
            send.setClipData(ClipData.newRawUri(zip.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "把日志发给…");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(ctx instanceof android.app.Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            AppLog.w("AppLog", "分享日志失败: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 清空全部日志和导出的 zip。 */
    public static int clear(Context ctx) {
        int n = AppLog.clear();
        File[] old = exportDir(ctx).listFiles();
        if (old != null) for (File f : old) f.delete();
        AppLog.i("AppLog", "日志已清空");
        return n;
    }
}
