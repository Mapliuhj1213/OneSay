package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.ui.widget.SendMicPolicy;

import org.junit.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ★ feat/api-first：停止键真中断 —— 正在等的网络请求被立刻断开（按「已取消」返回）、点屏代理的停止钩子被调用、
 * 已停就不再发请求、子线程不继承范围（任务线程显式 bind / wrap）；输入框执行中变停止键。
 */
public class StopScopeTest {

    /** 本机假服务器：收下连接、读请求，但永远不回（模拟卡住的模型 / 网页）。 */
    private static ServerSocket silentServer() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    InputStream in = s.getInputStream();
                                    byte[] b = new byte[1024];
                                    while (in.read(b) >= 0) { /* 只读不回 */ }
                                } catch (Exception ignored) {
                                }
                            }
                        }).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    @Test(timeout = 20000)
    public void stopInterruptsBlockedHttpRequest() throws Exception {
        ServerSocket ss = silentServer();
        try {
            final UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();
            final AtomicReference<HttpClient.Response> got = new AtomicReference<HttpClient.Response>();
            final long[] took = {0};
            final String url = "http://127.0.0.1:" + ss.getLocalPort() + "/v1/chat";
            Thread task = new Thread(new Runnable() {
                @Override public void run() {
                    StopScope.bind(cancel.scope());
                    try {
                        long t0 = System.currentTimeMillis();
                        got.set(HttpClient.execute(new HttpClient.Request(url).method("POST").json("{}").timeouts(5000, 15000)));
                        took[0] = System.currentTimeMillis() - t0;
                    } finally {
                        StopScope.unbind();
                    }
                }
            });
            task.start();
            // 等请求真的挂上
            long dl = System.currentTimeMillis() + 5000;
            while (cancel.scope().pending() == 0 && System.currentTimeMillis() < dl) Thread.sleep(20);
            assertEquals("请求已登记进停止范围", 1, cancel.scope().pending());
            Thread.sleep(200);
            cancel.cancel();   // 点停止
            task.join(5000);
            assertFalse("停止后请求线程很快返回", task.isAlive());
            HttpClient.Response r = got.get();
            assertNotNull(r);
            assertEquals(HttpClient.Kind.CANCELLED, HttpClient.classify(r.code, r.error));
            assertTrue("远小于 15 秒读超时：" + took[0], took[0] < 5000);
            assertEquals(0, cancel.scope().pending());
        } finally {
            ss.close();
        }
    }

    @Test public void alreadyStoppedDoesNotSend() {
        UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();
        cancel.cancel();
        StopScope.bind(cancel.scope());
        try {
            HttpClient.Response r = HttpClient.execute(new HttpClient.Request("http://127.0.0.1:9/never"));
            assertEquals(HttpClient.Kind.CANCELLED, HttpClient.classify(r.code, r.error));
        } finally {
            StopScope.unbind();
        }
    }

    @Test public void finishedRequestUnregisters() throws Exception {
        StopScope s = new StopScope("t");
        StopScope.bind(s);
        try {
            HttpClient.execute(new HttpClient.Request("http://127.0.0.1:9/refused").timeouts(500, 500));   // 连不上，立即结束
            assertEquals("结束的请求不留钩子", 0, s.pending());
        } finally {
            StopScope.unbind();
        }
    }

    @Test public void guiAgentAndOtherHooksRunOnceOnStop() {
        UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();
        final AtomicInteger guiStops = new AtomicInteger();
        cancel.scope().register(new Runnable() {
            @Override public void run() {
                guiStops.incrementAndGet();   // 宿主登记的「停掉点屏代理」（ExecModeController.stop）
            }
        });
        Runnable un = cancel.scope().register(new Runnable() {
            @Override public void run() {
                throw new RuntimeException("钩子异常不影响别的钩子");
            }
        });
        assertEquals(2, cancel.scope().pending());
        assertFalse(cancel.isCancelled());
        cancel.cancel();
        cancel.cancel();   // 幂等
        assertTrue(cancel.isCancelled());
        assertTrue(cancel.scope().isStopped());
        assertEquals(1, guiStops.get());
        un.run();   // 停止后注销是空操作
        // 停止之后再登记的钩子立刻执行
        cancel.scope().register(new Runnable() {
            @Override public void run() {
                guiStops.incrementAndGet();
            }
        });
        assertEquals(2, guiStops.get());
    }

    /** 子线程不继承（任务里懒建的常驻线程不能背上一个会被停掉的范围）。 */
    @Test public void childThreadsDoNotInheritScope() throws Exception {
        final StopScope s = new StopScope("parent");
        StopScope.bind(s);
        final AtomicReference<StopScope> seen = new AtomicReference<StopScope>();
        try {
            Thread c = new Thread(new Runnable() {
                @Override public void run() {
                    seen.set(StopScope.current());
                }
            });
            c.start();
            c.join();
        } finally {
            StopScope.unbind();
        }
        assertTrue(seen.get() == null);
        assertTrue(StopScope.current() == null);
        assertFalse(StopScope.currentStopped());
    }

    @Test public void wrapBindsOnlyForTheRunAndUnbinds() throws Exception {
        final StopScope s = new StopScope("t");
        final AtomicReference<StopScope> seen = new AtomicReference<StopScope>();
        Thread th = new Thread(StopScope.wrap(s, new Runnable() {
            @Override public void run() {
                seen.set(StopScope.current());
                throw new RuntimeException("任务里抛异常也要解绑");
            }
        }));
        th.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
            }
        });
        th.start();
        th.join(5000);
        assertTrue("任务线程运行时在范围内", seen.get() == s);
        assertTrue("调用线程不受影响", StopScope.current() == null);
        final AtomicReference<StopScope> after = new AtomicReference<StopScope>(s);
        Runnable r = StopScope.wrap(s, new Runnable() {
            @Override public void run() {
            }
        });
        r.run();   // 同线程直接跑：结束后解绑
        after.set(StopScope.current());
        assertTrue("跑完解绑", after.get() == null);
        assertNotNull(StopScope.wrap(null, r));
    }

    @Test public void inputBecomesStopKeyWhileRunning() {
        assertEquals(SendMicPolicy.Mode.STOP, SendMicPolicy.mode("", false, false, true));
        assertEquals(SendMicPolicy.Mode.STOP, SendMicPolicy.mode("  ", false, false, true));
        assertEquals("执行中打了字仍可发送（新指令接管）", SendMicPolicy.Mode.SEND, SendMicPolicy.mode("改成明天", false, false, true));
        assertEquals(SendMicPolicy.Mode.LISTENING, SendMicPolicy.mode("", false, true, true));
        assertEquals(SendMicPolicy.Mode.MIC, SendMicPolicy.mode("", false, false, false));
        assertEquals("停止", SendMicPolicy.desc(SendMicPolicy.Mode.STOP));
        // 原三参版本不变
        assertEquals(SendMicPolicy.Mode.MIC, SendMicPolicy.mode("", false, false));
    }
}
