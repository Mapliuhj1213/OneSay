package com.onesay.assistant.v2.executor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * exec 通道请求文件 {@code .bixby_req} 的收尾（2026-10-23 lane-a，上机报告 §5-1）。
 *
 * <p>问题：守护 1.5 s 内没取走请求时 App 判「未就绪」并报失败，但请求文件原样留着；守护恢复后（debug 重新建标记 /
 * release 守护重启）会把这条<b>已经报失败</b>的命令补执行一次 —— 写操作会出现「说失败了，过一会儿却生效了」。
 *
 * <p>做法：超时进入失败分支时调用 {@link #abandon}：只有文件内容<b>仍是本次写入的命令</b>才清空（守护刚好取走、
 * 或者别的请求已经覆盖时不动）。守护侧另有一道保险（v3：过期请求丢弃，见 tools/kdaemon/exec_daemon.v3.sh）。
 * 纯 Java，JVM 可测。
 */
public final class ExecRequestFile {

    private ExecRequestFile() {}

    /** 结果：清掉了 / 守护已取走（文件空）/ 内容已不是本次命令（不动）/ 出错。 */
    public enum Abandon { CLEARED, ALREADY_TAKEN, REPLACED, ERROR }

    public static Abandon abandon(File req, String command) {
        if (req == null || command == null) return Abandon.ERROR;
        try {
            if (!req.exists() || req.length() == 0) return Abandon.ALREADY_TAKEN;
            String cur = read(req);
            if (!command.equals(cur)) return Abandon.REPLACED;
            FileOutputStream z = new FileOutputStream(req, false);
            z.close();
            return Abandon.CLEARED;
        } catch (Throwable t) {
            return Abandon.ERROR;
        }
    }

    static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 256 * 1024) break;
            }
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
