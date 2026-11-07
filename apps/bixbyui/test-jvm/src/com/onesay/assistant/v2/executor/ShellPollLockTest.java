package com.onesay.assistant.v2.executor;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.s1.FullscreenPolicy;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;

/** 审计 B-2：轮询类只读 root 命令拿不到 IPC 锁就跳过本轮，不排队，也不关掉全屏检测这一路。 */
public class ShellPollLockTest {

    private static ShellExecutor bare() throws Exception {
        // 不经构造函数（JVM 里没有真 Context）：executePoll 在拿不到锁时不会碰 Context
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        return (ShellExecutor) u.allocateInstance(ShellExecutor.class);
    }

    private static ReentrantLock ipcLock() throws Exception {
        Field f = ShellExecutor.class.getDeclaredField("IPC_LOCK");
        f.setAccessible(true);
        return (ReentrantLock) f.get(null);
    }

    @Test public void pollReturnsBusyImmediatelyWhenChannelHeld() throws Exception {
        final ReentrantLock lock = ipcLock();
        final CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread holder = new Thread(new Runnable() {
            @Override public void run() {
                lock.lock();
                try {
                    held.countDown();
                    release.await();
                } catch (InterruptedException ignored) {
                } finally {
                    lock.unlock();
                }
            }
        });
        holder.start();
        held.await();
        try {
            long t0 = System.nanoTime();
            ShellExecutor.ShellResponse r = bare().executePoll("dumpsys window policy");
            long ms = (System.nanoTime() - t0) / 1000000L;
            assertTrue(r.isBusy);
            assertFalse(r.isSuccess());
            assertEquals(-2, r.rc);
            assertTrue("不应排队等锁，实际 " + ms + "ms", ms < 200);
            assertEquals(FullscreenPolicy.Read.BUSY, FullscreenPolicy.interpret(r));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test public void emptyCommandShortCircuits() throws Exception {
        ShellExecutor.ShellResponse r = bare().executePoll("  ");
        assertTrue(r.isSuccess());
        assertFalse(r.isBusy);
    }

    @Test public void pollTimeoutIsShort() {
        assertTrue(ShellExecutor.POLL_TIMEOUT_MS <= 1500);
    }

    @Test public void interpretDistinguishesBusyFromUnavailable() {
        assertEquals(FullscreenPolicy.Read.UNAVAILABLE, FullscreenPolicy.interpret(null));
        assertEquals(FullscreenPolicy.Read.UNAVAILABLE,
                FullscreenPolicy.interpret(new ShellExecutor.ShellResponse(-1, "守护未就绪", false, true)));
        assertEquals(FullscreenPolicy.Read.UNAVAILABLE,
                FullscreenPolicy.interpret(new ShellExecutor.ShellResponse(0, "no fields", false, false)));
        assertEquals(FullscreenPolicy.Read.FULLSCREEN,
                FullscreenPolicy.interpret(new ShellExecutor.ShellResponse(0, "mTopIsFullscreen=true", false, false)));
        assertEquals(FullscreenPolicy.Read.NOT_FULLSCREEN,
                FullscreenPolicy.interpret(new ShellExecutor.ShellResponse(0, "mTopIsFullscreen=false", false, false)));
        assertEquals(FullscreenPolicy.Read.BUSY, FullscreenPolicy.interpret(ShellExecutor.ShellResponse.busy()));
    }

    @Test public void islandSkipsRoundOnBusyAndUsesPollChannel() {
        String ic = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue(ic.contains("fs == FullscreenPolicy.Read.BUSY"));
        String fp = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/s1/FullscreenPolicy.java");
        assertTrue(fp.contains(".executePoll(CMD)"));
        String se = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/executor/ShellExecutor.java");
        assertTrue(se.contains("IPC_LOCK.tryLock()"));
    }
}
