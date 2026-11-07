package com.onesay.assistant.v2.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享后台线程池（P1-2）。
 *
 * <p>用来替代散落各处的裸 {@code new Thread(...).start()}：线程有名字（logcat / ANR trace 里能认出来）、
 * 有上限（不会因为连点按钮开出几十个线程）、空闲自动回收。
 *
 * <p>规则：
 * <ul>
 *   <li>{@link #bg(String, Runnable)}：一般后台任务（网络、文件、shell IPC）。最多 {@link #MAX_THREADS} 并发，
 *       超出排队；</li>
 *   <li>需要<b>严格串行</b>的场景（同一会话的步骤、音频识别队列）请自建单线程 executor，
 *       用 {@link #namedFactory(String)} 起名字 —— 不要往共享池里塞依赖顺序的任务；</li>
 *   <li>切回主线程用 {@code new Handler(Looper.getMainLooper())}（本类不依赖 android）。</li>
 * </ul>
 * 纯 Java，无 android 依赖，可在 JVM 单测中使用。
 */
public final class Threads {

    /** 共享池并发上限。2 核设备上 4 个足够覆盖"网络 + shell IPC + 文件"同时进行。 */
    public static final int MAX_THREADS = 4;

    private static volatile ExecutorService sShared;

    private Threads() {}

    /** 带名字前缀的守护线程工厂（守护线程不阻止进程/JVM 退出，便于单测）。 */
    public static ThreadFactory namedFactory(final String prefix) {
        final AtomicInteger n = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** 共享后台池（懒创建，核心线程空闲 30s 回收）。 */
    public static ExecutorService shared() {
        ExecutorService e = sShared;
        if (e == null) {
            synchronized (Threads.class) {
                e = sShared;
                if (e == null) {
                    ThreadPoolExecutor p = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS,
                            30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                            namedFactory("bixby-bg"));
                    p.allowCoreThreadTimeOut(true);
                    sShared = e = p;
                }
            }
        }
        return e;
    }

    /**
     * 在共享池执行。{@code tag} 只用于把当前线程临时改名，方便排查（执行完恢复）。
     */
    public static Future<?> bg(final String tag, final Runnable r) {
        return shared().submit(new Runnable() {
            @Override
            public void run() {
                Thread t = Thread.currentThread();
                String old = t.getName();
                if (tag != null && tag.length() > 0) t.setName(old + ":" + tag);
                try {
                    r.run();
                } finally {
                    t.setName(old);
                }
            }
        });
    }

    /** 可中断的睡眠：被中断时恢复中断标志并返回 false。 */
    public static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 审计 B-3：起一个<b>专用</b>的命名后台线程（长任务用：AgentLoop、到点任务、计划执行……不进共享池，
     * 避免把 4 个共享线程占满）。和裸 {@code new Thread(r, name).start()} 行为一致（非守护、立即启动），
     * 区别只在：名字必填（统一 "bixby-" 前缀，logcat / ANR trace 里一眼能认出），未捕获异常先打印线程名再交给
     * 进程默认处理器（崩溃行为不变）。
     */
    public static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, threadName(name));
        t.setUncaughtExceptionHandler(UNCAUGHT);
        t.start();
        return t;
    }

    /** 纯函数：统一线程名（JVM 可测）。 */
    public static String threadName(String name) {
        if (name == null || name.length() == 0) name = "task";
        return name.startsWith("bixby-") ? name : "bixby-" + name;
    }

    private static final Thread.UncaughtExceptionHandler UNCAUGHT = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            System.err.println("[THREADS] 线程 " + t.getName() + " 未捕获异常: " + e);
            Thread.UncaughtExceptionHandler d = Thread.getDefaultUncaughtExceptionHandler();
            if (d != null && d != this) {
                d.uncaughtException(t, e);
            } else {
                e.printStackTrace();
            }
        }
    };
}
