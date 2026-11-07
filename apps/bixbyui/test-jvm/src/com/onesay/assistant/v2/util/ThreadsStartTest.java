package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** 审计 B-3：长任务统一走 Threads.start（命名、未捕获异常可追踪）；S3Runner / FloatingSheetService 已无裸 new Thread。 */
public class ThreadsStartTest {

    @Test public void threadNamePrefix() {
        assertEquals("bixby-AgentLoop", Threads.threadName("AgentLoop"));
        assertEquals("bixby-s3-fire", Threads.threadName("bixby-s3-fire"));
        assertEquals("bixby-task", Threads.threadName(null));
        assertEquals("bixby-task", Threads.threadName(""));
    }

    @Test public void startRunsOnNamedNonDaemonThread() throws Exception {
        final AtomicReference<String> name = new AtomicReference<String>();
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = Threads.start("s3-agent-7", new Runnable() {
            @Override public void run() {
                name.set(Thread.currentThread().getName());
                done.countDown();
            }
        });
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("bixby-s3-agent-7", name.get());
        assertFalse("与原 new Thread 一致：非守护线程", t.isDaemon());
        t.join();
    }

    @Test public void uncaughtGoesToDefaultHandler() throws Exception {
        final AtomicReference<Throwable> seen = new AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) { seen.set(e); }
            });
            Thread t = Threads.start("boom", new Runnable() {
                @Override public void run() { throw new IllegalStateException("x"); }
            });
            t.join();
            assertTrue("崩溃行为不变：仍交给进程默认处理器", seen.get() instanceof IllegalStateException);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old);
        }
    }

    @Test public void hostsNoLongerUseBareThreads() {
        for (String f : new String[]{"s3/S3Runner.java", "ui/FloatingSheetService.java"}) {
            String s = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/" + f);
            assertFalse(f + " 仍有裸 new Thread", s.contains("new Thread("));
            assertTrue(f, s.contains("Threads.start("));
        }
    }

    /** 其余 24 处裸 new Thread 也已迁完——除 util/Threads 本身、AsrRouter 的线程池工厂、debug 包 test/ 外不许再有。 */
    @Test public void noBareThreadsAnywhereInApp() throws Exception {
        java.io.File root = new java.io.File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2");
        java.util.List<String> bad = new java.util.ArrayList<String>();
        scan(root, root, bad);
        assertTrue("仍有裸 new Thread: " + bad, bad.isEmpty());
    }

    private static void scan(java.io.File base, java.io.File f, java.util.List<String> bad) throws Exception {
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) scan(base, k, bad);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        String rel = base.toURI().relativize(f.toURI()).getPath();
        // fastscreen/HelperMain 跑在 root app_process 里（单独打 dex，不带 util 包），不经 Threads
        if (rel.startsWith("test/") || rel.equals("util/Threads.java") || rel.equals("fastscreen/HelperMain.java")) return;
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        int i = -1;
        while ((i = s.indexOf("new Thread(", i + 1)) >= 0) {
            String line = s.substring(s.lastIndexOf('\n', i) + 1, Math.max(i, s.indexOf('\n', i)));
            if (rel.equals("asr/AsrRouter.java") && line.contains("ThreadFactory") == false && line.contains("asr-cloud-")) continue;
            if (line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
            bad.add(rel + ": " + line.trim());
        }
    }
}
