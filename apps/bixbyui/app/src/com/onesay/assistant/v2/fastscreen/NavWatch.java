package com.onesay.assistant.v2.fastscreen;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [fix-1123-c] 导航键观察线程的开 / 关（纯 Java，JVM 可测）。从读屏助手（HelperMain）里拆出来修线程泄漏：
 *
 * <p>以前所有观察线程共用一个全局 {@code sWatchOn}：停的时候置假再 interrupt，可线程卡在读 /dev/input 上，interrupt 叫不醒它；
 * 下一个任务又把 {@code sWatchOn} 置回真，旧线程就永远不退（真机上读屏助手攥着 6→8 个 event0 句柄）。
 * 现在每个线程有自己的 {@link Token}：停的时候只关它自己的，关掉它的输入流并 interrupt（助手那边用可中断的通道读，interrupt 会关通道、叫醒卡住的读），
 * 就算系统没叫醒，下一次触摸进来它看到自己的令牌已停也马上退出、不再计数；新任务碰不到旧线程。
 */
public final class NavWatch {

    public interface Opener {
        InputStream open() throws IOException;
    }

    public interface Log {
        void log(String s);
    }

    /** 一次观察。 */
    public static final class Token {
        volatile boolean on = true;
        volatile Closeable res;
        Thread thread;

        public boolean on() { return on; }
    }

    private volatile Token mCur;
    /** 还活着的观察线程数（诊断 / 测试）。 */
    public final AtomicInteger live = new AtomicInteger();

    /** 停掉旧的，开一个新的。 */
    public synchronized Token start(final Opener opener, final NavTouchParser parser, final NavTouchParser.Sink sink, final Log log) {
        stop();
        final Token t = new Token();
        Runnable body = new Runnable() {
            @Override public void run() {
                live.incrementAndGet();
                InputStream in = null;
                try {
                    in = opener.open();
                    t.res = in;
                    if (!t.on) return; // 刚开就被停了
                    byte[] buf = new byte[NavTouchParser.EV64 * 64]; // 1536：16 和 24 都能整除
                    while (t.on) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (t.on) parser.feed(buf, n, sink);
                    }
                } catch (Throwable e) {
                    if (t.on && log != null) log.log("backwatch end " + e);
                } finally {
                    closeQuietly(in);
                    live.decrementAndGet();
                }
            }
        };
        t.thread = com.onesay.assistant.v2.util.Threads.start("fs-backwatch", body);
        mCur = t;
        return t;
    }

    /** 停掉当前这个（关它的输入流，卡在读上的线程马上退出）。 */
    public synchronized void stop() {
        Token t = mCur;
        mCur = null;
        if (t == null) return;
        t.on = false;
        closeQuietly(t.res);
        if (t.thread != null) t.thread.interrupt();
    }

    public boolean on() {
        Token t = mCur;
        return t != null && t.on;
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
