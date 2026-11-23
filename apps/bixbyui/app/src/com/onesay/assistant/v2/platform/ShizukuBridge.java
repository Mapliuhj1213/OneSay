package com.onesay.assistant.v2.platform;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.onesay.plugin.api.exec.ExecResult;
import com.onesay.plugin.exec.shizuku.ShizukuExecBackend;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 「Shizuku 特权」插件的 Android 侧桥：只在这里碰 Shizuku 官方 API 库（dev.rikka.shizuku:api / provider 13.1.5，
 * MIT License，Copyright (c) 2021 RikkaW，jar 在 apps/bixbyui/libs/）。库不在运行时（被裁掉 / 类加载失败）时一律报「不可用」，不抛出。
 *
 * <p>执行用库里公开的 AIDL 接口 {@code IShizukuService.newProcess(argv, null, null)}（不经 shell，argv 来自白名单），
 * 读完输出后 {@code waitForTimeout}，超时 {@code destroy}。输出最多读 64 KB。
 */
public final class ShizukuBridge implements ShizukuExecBackend.Bridge, ShizukuExecBackend.Spawner {

    public static final int REQUEST_CODE = 0x5A17;
    private static final int MAX_OUT = 64 * 1024;

    @Override public boolean running() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean granted() {
        try {
            return !rikka.shizuku.Shizuku.isPreV11()
                    && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public int uid() {
        try {
            return rikka.shizuku.Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 打开插件时调用：Shizuku 在跑、还没授权 → 弹 Shizuku 自己的授权框（结果由 Shizuku 回调，这里不等）。 */
    public static boolean requestPermissionIfNeeded() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder() || rikka.shizuku.Shizuku.isPreV11()) return false;
            if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            rikka.shizuku.Shizuku.requestPermission(REQUEST_CODE);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public ExecResult exec(List<String> argv, long timeoutMs) {
        moe.shizuku.server.IRemoteProcess p = null;
        try {
            IBinder b = rikka.shizuku.Shizuku.getBinder();
            if (b == null) return ExecResult.down(ShizukuExecBackend.REASON_NOT_RUNNING);
            moe.shizuku.server.IShizukuService svc = moe.shizuku.server.IShizukuService.Stub.asInterface(b);
            p = svc.newProcess(argv.toArray(new String[0]), null, null);
            if (p == null) return ExecResult.down("Shizuku 没有起进程");
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            boolean finished = p.waitForTimeout(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS.name());
            if (!finished) {
                try { p.destroy(); } catch (Throwable ignored) { }
                return new ExecResult(-1, out, true, false);
            }
            int rc = p.exitValue();
            return new ExecResult(rc, out.length() > 0 || rc == 0 ? out : err, false, false);
        } catch (Throwable t) {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
            return ExecResult.down("Shizuku 执行失败（" + t.getClass().getSimpleName() + "）");
        }
    }

    /** a11y-1118：最近一次起的常驻进程（读屏助手）；留着引用，App 退出时随 Shizuku 的客户端死亡一起回收。 */
    private static volatile moe.shizuku.server.IRemoteProcess sHelper;

    /**
     * a11y-1118：起常驻进程（argv 已过 {@code ShizukuWhitelist.checkHelperArgv}）。输出在后台线程读掉（最多留 4 KB 给报错用），
     * 最多等 settleMs 看是不是马上退出。
     */
    @Override public ExecResult spawn(List<String> argv, long settleMs) {
        moe.shizuku.server.IRemoteProcess p = null;
        try {
            IBinder b = rikka.shizuku.Shizuku.getBinder();
            if (b == null) return ExecResult.down(ShizukuExecBackend.REASON_NOT_RUNNING);
            moe.shizuku.server.IShizukuService svc = moe.shizuku.server.IShizukuService.Stub.asInterface(b);
            p = svc.newProcess(argv.toArray(new String[0]), null, null);
            if (p == null) return ExecResult.down("Shizuku 没有起进程");
            final StringBuffer out = new StringBuffer(), err = new StringBuffer();
            // ★ fix/1123-bug1 B5：标准输出不再读掉 —— 它就是和助手说话的通道（FsStdio）；日志在标准错误里
            final ParcelFileDescriptor stdout = p.getInputStream(), stdin = p.getOutputStream();
            drain("shz-helper-err", p.getErrorStream(), err);
            long end = android.os.SystemClock.elapsedRealtime() + Math.max(0L, settleMs);
            while (android.os.SystemClock.elapsedRealtime() < end && p.alive()) android.os.SystemClock.sleep(50);
            if (p.alive()) {
                HelperPipes old = sPipes;
                sHelper = p;
                sPipes = new HelperPipes(p, stdout, stdin);
                if (old != null) old.closeOnly();
                return new ExecResult(0, "spawned", false, false);
            }
            closeQuiet(stdout);
            closeQuiet(stdin);
            int rc = p.exitValue();
            android.os.SystemClock.sleep(50);   // 让读线程收完最后几行
            String msg = err.length() > 0 ? err.toString() : out.toString();
            return new ExecResult(rc == 0 ? 1 : rc, "helper exited rc=" + rc + (msg.trim().length() > 0 ? " " + msg.trim() : ""), false, false);
        } catch (Throwable t) {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
            return ExecResult.down("Shizuku 执行失败（" + t.getClass().getSimpleName() + "）");
        }
    }

    /** ★ fix/1123-bug1 B5：最近一次起的读屏助手的标准输出 / 输入（只能取走一次）。 */
    private static volatile HelperPipes sPipes;

    public static com.onesay.assistant.v2.fastscreen.FsStdio.Pipes takeHelperPipes() {
        HelperPipes h = sPipes;
        sPipes = null;
        return h;
    }

    static final class HelperPipes implements com.onesay.assistant.v2.fastscreen.FsStdio.Pipes {
        final moe.shizuku.server.IRemoteProcess proc;
        final ParcelFileDescriptor out, in;

        HelperPipes(moe.shizuku.server.IRemoteProcess proc, ParcelFileDescriptor out, ParcelFileDescriptor in) {
            this.proc = proc; this.out = out; this.in = in;
        }

        @Override public InputStream stdout() {
            return new ParcelFileDescriptor.AutoCloseInputStream(out);
        }

        @Override public java.io.OutputStream stdin() {
            return new ParcelFileDescriptor.AutoCloseOutputStream(in);
        }

        @Override public void kill() {
            try { proc.destroy(); } catch (Throwable ignored) { }
            closeOnly();
        }

        void closeOnly() {
            closeQuiet(out);
            closeQuiet(in);
        }
    }

    private static void closeQuiet(ParcelFileDescriptor f) {
        if (f == null) return;
        try { f.close(); } catch (Throwable ignored) { }
    }

    private static void drain(String name, final ParcelFileDescriptor pfd, final StringBuffer keep) {
        if (pfd == null) return;
        com.onesay.assistant.v2.util.Threads.start(name, new Runnable() {
            @Override public void run() {
                InputStream in = null;
                try {
                    in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (keep.length() < 4096) keep.append(new String(buf, 0, Math.min(n, 4096 - keep.length()), "UTF-8"));
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
                }
            }
        });
    }

    private static String read(ParcelFileDescriptor pfd) {
        if (pfd == null) return "";
        InputStream in = null;
        try {
            in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) > 0) {
                if (bo.size() < MAX_OUT) bo.write(buf, 0, Math.min(n, MAX_OUT - bo.size()));
            }
            sb.append(new String(bo.toByteArray(), "UTF-8"));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }
}
