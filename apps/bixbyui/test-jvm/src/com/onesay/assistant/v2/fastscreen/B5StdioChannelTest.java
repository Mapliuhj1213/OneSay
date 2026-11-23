package com.onesay.assistant.v2.fastscreen;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ fix/1123-bug1 B5：经 Shizuku 起的读屏助手，App 连它的抽象 LocalSocket 被 SELinux 拒（untrusted_app → shell 没有 connectto 放行，
 * 反过来 shell → untrusted_app 也没有）。改走 Shizuku 起进程时交给 App 的标准输入 / 输出（{@link FsStdio}）。
 * JVM 里测：帧协议在管道上跑通、开头杂字能跳过、校验 / quit / EOF、看门狗到点能把卡住的读放出来；接线守卫看源码确实接上了。
 * SELinux 本身只能真机验（原始记录：meizu-test-1118 REPORT 第 36 行的 avc denied）。
 */
public class B5StdioChannelTest {

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    /** 假助手：在一对管道上跑 FsStdio.serve。 */
    static final class Rig {
        final PipedOutputStream toHelper = new PipedOutputStream();
        final PipedInputStream helperIn;
        final PipedOutputStream helperOut = new PipedOutputStream();
        final PipedInputStream fromHelper;
        final AtomicInteger touched = new AtomicInteger(), quit = new AtomicInteger();
        final Thread th;
        volatile Throwable err;
        volatile boolean ended;

        Rig(final byte[] garbage, final FsStdio.Handler h0) throws IOException {
            helperIn = new PipedInputStream(toHelper, 1 << 16);
            fromHelper = new PipedInputStream(helperOut, 1 << 20);
            final FsStdio.Handler h = h0 != null ? h0 : new FsStdio.Handler() {
                @Override public Object handle(String[] p) {
                    if (p[0].equals(FsProtocol.OP_PING)) return "pong";
                    if (p[0].equals(FsProtocol.OP_SHOT)) return new byte[300 * 1024];
                    if (p[0].equals(FsProtocol.OP_TOP)) return new FsStdio.Failure("NO_WINDOW");
                    throw new IllegalStateException("boom");
                }

                @Override public void quit() {
                    quit.incrementAndGet();
                }

                @Override public void touched() {
                    touched.incrementAndGet();
                }
            };
            th = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        DataOutputStream out = new DataOutputStream(helperOut);
                        if (garbage != null) out.write(garbage);
                        FsStdio.writeMagic(out);
                        FsStdio.serve(new DataInputStream(helperIn), out, h, new Object());
                    } catch (Throwable t) {
                        err = t;
                    } finally {
                        ended = true;
                        try { helperOut.close(); } catch (IOException ignored) { }
                    }
                }
            });
            th.setDaemon(true);
            th.start();
        }

        FsProtocol.Resp call(DataInputStream in, DataOutputStream out, String op, String... a) throws IOException {
            FsProtocol.writeRequest(out, FsProtocol.encode(op, a));
            return FsProtocol.readResponse(in);
        }
    }

    @Test public void framesOverPipes() throws Exception {
        Rig r = new Rig(null, null);
        DataInputStream in = new DataInputStream(r.fromHelper);
        DataOutputStream out = new DataOutputStream(r.toHelper);
        assertTrue(FsStdio.readMagic(in));
        FsProtocol.Resp p = r.call(in, out, FsProtocol.OP_PING);
        assertEquals(FsProtocol.ST_OK, p.status);
        assertEquals("pong", p.text());
        p = r.call(in, out, FsProtocol.OP_SHOT, "70", "0");
        assertEquals("大块应答（截图）完整收到", 300 * 1024, p.body.length);
        p = r.call(in, out, FsProtocol.OP_TOP);
        assertEquals(FsProtocol.ST_ERR, p.status);
        assertEquals("NO_WINDOW", p.text());
        p = r.call(in, out, FsProtocol.OP_TAP, "1", "2");
        assertEquals("处理抛异常 → 失败应答，通道不断", FsProtocol.ST_ERR, p.status);
        assertTrue(p.text().contains("boom"));
        assertEquals(4, r.touched.get());
    }

    @Test public void validationStillApplies() throws Exception {
        Rig r = new Rig(null, null);
        DataInputStream in = new DataInputStream(r.fromHelper);
        DataOutputStream out = new DataOutputStream(r.toHelper);
        assertTrue(FsStdio.readMagic(in));
        FsProtocol.Resp p = r.call(in, out, "sh", "-c", "reboot");
        assertEquals("没有任意 shell", FsProtocol.ST_DENY, p.status);
        p = r.call(in, out, FsProtocol.OP_KEY, "26");
        assertEquals("电源键不在白名单", FsProtocol.ST_DENY, p.status);
        assertEquals("pong", r.call(in, out, FsProtocol.OP_PING).text());
    }

    @Test public void quitAndEof() throws Exception {
        Rig r = new Rig(null, null);
        DataInputStream in = new DataInputStream(r.fromHelper);
        DataOutputStream out = new DataOutputStream(r.toHelper);
        assertTrue(FsStdio.readMagic(in));
        assertEquals("bye", r.call(in, out, FsProtocol.OP_QUIT).text());
        r.th.join(2000);
        assertTrue(r.ended);
        assertEquals(1, r.quit.get());
        assertNull(r.err);

        Rig r2 = new Rig(null, null);
        DataInputStream in2 = new DataInputStream(r2.fromHelper);
        assertTrue(FsStdio.readMagic(in2));
        r2.toHelper.close();   // App 没了：助手读到 EOF 正常结束
        r2.th.join(2000);
        assertTrue(r2.ended);
        assertNull(r2.err);
    }

    @Test public void magicSkipsGarbageButNotForever() throws Exception {
        byte[] junk = "WARNING: linker: something\n\0OSF".getBytes("UTF-8");
        Rig r = new Rig(junk, null);
        DataInputStream in = new DataInputStream(r.fromHelper);
        assertTrue(FsStdio.readMagic(in));
        assertEquals("pong", r.call(in, new DataOutputStream(r.toHelper), FsProtocol.OP_PING).text());

        assertFalse("空流", FsStdio.readMagic(new java.io.ByteArrayInputStream(new byte[0])));
        byte[] big = new byte[FsStdio.MAX_SKIP + 100];
        java.util.Arrays.fill(big, (byte) 'x');
        assertFalse("杂字太多就放弃", FsStdio.readMagic(new java.io.ByteArrayInputStream(big)));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FsStdio.writeMagic(bo);
        assertEquals(8, bo.size());
    }

    @Test(timeout = 5000) public void watchdogUnblocksHungRead() throws Exception {
        final PipedOutputStream w = new PipedOutputStream();
        final PipedInputStream rd = new PipedInputStream(w);
        FsStdio.Watchdog dog = new FsStdio.Watchdog();
        dog.arm(150, new Runnable() {
            @Override public void run() {
                try { w.close(); } catch (IOException ignored) { }   // 真机上是销毁助手进程
            }
        });
        long t0 = System.currentTimeMillis();
        try {
            FsProtocol.readResponse(new DataInputStream(rd));
            fail("应该读到 EOF");
        } catch (IOException expected) {
        }
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        assertEquals(1, dog.firedCount());

        // 正常返回前解除：不触发
        final AtomicInteger k = new AtomicInteger();
        dog.arm(200, new Runnable() {
            @Override public void run() {
                k.incrementAndGet();
            }
        });
        dog.disarm();
        Thread.sleep(400);
        assertEquals(0, k.get());
        dog.stop();
    }

    /** 接线守卫（修之前这几条都不成立：Shizuku 起的助手只能走 LocalSocket，标准输出被读掉扔了）。 */
    @Test public void wiring() throws Exception {
        String helper = src("app/src/com/onesay/assistant/v2/fastscreen/HelperMain.java");
        assertTrue(helper.contains("final boolean stdio = FsProtocol.launchPinAllowed(proc, me, sPkg);"));
        assertTrue("标准输出只放协议帧", helper.contains("if (stdio) System.setOut(System.err);"));
        assertTrue(helper.contains("if (stdio) startStdio(stdout);"));
        assertTrue("通道上先核 App 身份", helper.contains("String deny = authorize(sUid);"));
        String bridge = src("app/src/com/onesay/assistant/v2/platform/ShizukuBridge.java");
        assertFalse("标准输出不能再被读掉", bridge.contains("drain(\"shz-helper-out\""));
        assertTrue(bridge.contains("drain(\"shz-helper-err\""));
        assertTrue(bridge.contains("sPipes = new HelperPipes(p, stdout, stdin);"));
        String fs = src("app/src/com/onesay/assistant/v2/fastscreen/FastScreen.java");
        assertTrue("Shizuku 拉起成功后先试标准输入输出", fs.contains("if (openStdio(t0)) return true;"));
        assertTrue(fs.contains("if (mSock != null || mPipes != null) return true;"));
        assertTrue("请求有超时看门狗", fs.contains("if (pp != null) mDog.arm(SO_TIMEOUT_MS, killer(pp));"));
    }
}
