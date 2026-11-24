package com.onesay.assistant.v2.applog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * [settings-diag-1124] OneSay 自己的运行日志（纯 Java，JVM 可测）：应用私有目录里的环形日志文件。
 *
 * <h3>为什么要有</h3>
 * 魅族上用户开不了无线调试，logcat 抓不到；以前出问题只能靠猜。现在 App 自己把关键链路记到私有目录，设置里一键导出、用系统分享发出去。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>文件：{@code <dir>/onesay.log}，写满 {@link #MAX_FILE}（1 MB）就轮转成 {@code onesay.1.log}、{@code onesay.2.log}，
 *       最多 {@link #KEEP} 个文件（总共不超过 3 MB）；</li>
 *   <li>debug 包和 release 包都记；每一行写盘前过 {@link AppLogSanitizer}（Redactor 脱敏 + 密钥 / 凭据 / 网址路径打码）；</li>
 *   <li>聊天原文不进来：调用方沿用 {@link com.onesay.assistant.v2.util.LogText} 口径（release 只记长度），这里不再放宽；</li>
 *   <li>写盘在后台线程（不卡主线程）；{@link #init} 之前的行先在内存里排队（最多 {@link #MAX_PENDING} 行）；</li>
 *   <li>写日志本身出错一律吞掉，绝不影响功能。</li>
 * </ul>
 */
public final class AppLog {

    private AppLog() {}

    public static final long MAX_FILE = 1024L * 1024L;
    public static final int KEEP = 3;
    public static final int MAX_PENDING = 2000;
    public static final String NAME = "onesay";

    /** 可替换时钟（单测）。 */
    public interface Clock {
        long now();
    }

    private static volatile Clock sClock = new Clock() {
        @Override public long now() {
            return System.currentTimeMillis();
        }
    };

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String[]> PENDING = new ArrayDeque<String[]>();
    private static volatile File sDir;
    private static volatile boolean sEnabled = true;
    private static Thread sWriter;
    private static long sDropped;

    public static void setClock(Clock c) {
        sClock = c;
    }

    /** 日志目录（App 私有：files/applog）。可重复调用；换目录只给单测用。 */
    public static void init(File dir) {
        if (dir == null) return;
        synchronized (LOCK) {
            sDir = dir;
            if (!dir.isDirectory()) dir.mkdirs();
        }
        kick();
    }

    /** 单测收尾：断开目录、清空排队（别的测试的 SafeLog 行不再写进临时目录）。 */
    static void resetForTest() {
        synchronized (LOCK) {
            sDir = null;
            PENDING.clear();
            sDropped = 0;
        }
    }

    public static File dir() {
        return sDir;
    }

    /** 单测 / 紧急情况：关掉写盘（行直接丢）。 */
    public static void setEnabled(boolean on) {
        sEnabled = on;
    }

    // ------------------------------------------------------------------ 写

    public static void i(String tag, String msg) {
        append('I', tag, msg);
    }

    public static void w(String tag, String msg) {
        append('W', tag, msg);
    }

    public static void e(String tag, String msg) {
        append('E', tag, msg);
    }

    /** 记一行。msg 由调用方按 LogText 口径组好（用户原文只给长度）；这里再做一次脱敏。 */
    public static void append(char level, String tag, String msg) {
        if (!sEnabled) return;
        String[] item = {stamp(sClock.now()) + " " + level + " " + (tag == null ? "-" : tag), msg == null ? "null" : msg};
        synchronized (LOCK) {
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.pollFirst();
                sDropped++;
            }
            PENDING.addLast(item);
        }
        kick();
    }

    static String stamp(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(ms));
    }

    private static void kick() {
        if (sDir == null) return;
        synchronized (LOCK) {
            if (sWriter != null && sWriter.isAlive()) {
                LOCK.notifyAll();
                return;
            }
            sWriter = com.onesay.assistant.v2.util.Threads.start("applog", new Runnable() {
                @Override public void run() {
                    try {
                        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    } catch (Throwable ignored) {
                    }
                    loop();
                }
            });
        }
    }

    private static void loop() {
        while (true) {
            synchronized (LOCK) {
                long until = System.currentTimeMillis() + 30000L;
                while (PENDING.isEmpty()) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) {
                        sWriter = null;   // 空闲 30 秒就退出，下一行再拉起
                        return;
                    }
                    try {
                        LOCK.wait(left);
                    } catch (InterruptedException e) {
                        sWriter = null;
                        return;
                    }
                }
            }
            drain();
        }
    }

    /** 把排队的行同步写盘（崩溃处理 / 导出前 / 单测调用）。 */
    public static void flush() {
        drain();
    }

    private static final Object FILE_LOCK = new Object();

    private static void drain() {
        File dir = sDir;
        if (dir == null) return;
        List<String[]> batch = new ArrayList<String[]>();
        long dropped;
        synchronized (LOCK) {
            while (!PENDING.isEmpty()) batch.add(PENDING.pollFirst());
            dropped = sDropped;
            sDropped = 0;
        }
        if (batch.isEmpty() && dropped == 0) return;
        StringBuilder sb = new StringBuilder();
        if (dropped > 0) sb.append(stamp(sClock.now())).append(" W AppLog: 排队太多，丢了 ").append(dropped).append(" 行\n");
        for (String[] it : batch) {
            String body;
            try {
                boolean multi = it[1].indexOf('\n') >= 0 && (it[1].indexOf("\tat ") >= 0 || it[1].indexOf("\n    at ") >= 0 || it[1].indexOf("Exception") >= 0);
                body = AppLogSanitizer.clean(it[1], multi);
            } catch (Throwable t) {
                body = "‹" + it[1].length() + "字›";
            }
            sb.append(it[0]).append(": ").append(body).append('\n');
        }
        synchronized (FILE_LOCK) {
            try {
                if (!dir.isDirectory()) dir.mkdirs();
                File cur = new File(dir, NAME + ".log");
                byte[] bytes = sb.toString().getBytes("UTF-8");
                if (cur.length() > 0 && cur.length() + bytes.length > MAX_FILE) rotate(dir);
                OutputStream os = new FileOutputStream(cur, true);
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
            } catch (Throwable ignored) {
                // 写不进去（空间满 / 目录没了）：丢掉，不影响功能
            }
        }
    }

    /** onesay.log → onesay.1.log → onesay.2.log（最旧的删掉）。 */
    static void rotate(File dir) {
        File oldest = new File(dir, NAME + "." + (KEEP - 1) + ".log");
        if (oldest.exists()) oldest.delete();
        for (int i = KEEP - 2; i >= 1; i--) {
            File f = new File(dir, NAME + "." + i + ".log");
            if (f.exists()) f.renameTo(new File(dir, NAME + "." + (i + 1) + ".log"));
        }
        File cur = new File(dir, NAME + ".log");
        if (cur.exists()) cur.renameTo(new File(dir, NAME + ".1.log"));
    }

    // ------------------------------------------------------------------ 读 / 清

    /** 现有日志文件，从旧到新。 */
    public static List<File> files() {
        List<File> out = new ArrayList<File>();
        File dir = sDir;
        if (dir == null) return out;
        for (int i = KEEP - 1; i >= 1; i--) {
            File f = new File(dir, NAME + "." + i + ".log");
            if (f.isFile()) out.add(f);
        }
        File cur = new File(dir, NAME + ".log");
        if (cur.isFile()) out.add(cur);
        return out;
    }

    public static long totalBytes() {
        long n = 0;
        for (File f : files()) n += f.length();
        return n;
    }

    /** 全部日志（从旧到新），每行导出前再过一次密钥打码（防旧版本写进去的东西）。 */
    public static String readAll() {
        flush();
        StringBuilder sb = new StringBuilder();
        synchronized (FILE_LOCK) {
            for (File f : files()) {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    try {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(AppLogSanitizer.maskSecrets(line)).append('\n');
                    } finally {
                        br.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return sb.toString();
    }

    /** 清空全部日志文件（设置里「清空日志」，确认后才调）。@return 删掉的文件数 */
    public static int clear() {
        synchronized (LOCK) {
            PENDING.clear();
            sDropped = 0;
        }
        int n = 0;
        synchronized (FILE_LOCK) {
            for (File f : files()) if (f.delete()) n++;
        }
        return n;
    }

    /** 体积给人看（「1.2 MB」「36 KB」）。 */
    public static String sizeText(long bytes) {
        if (bytes <= 0) return "空的";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
