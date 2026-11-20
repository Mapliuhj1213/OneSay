package com.onesay.desktop;

import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** [preview-1120] AUDIT.md 里修掉的逻辑漏洞（A-01 … A-08）的回归测试。 */
public class Preview1120AuditTest {

    @After public void reset() {
        Progress.CANCEL.set(false);
        Progress.TOKEN.remove();
        Progress.SINK.remove();
    }

    /** 一个慢慢吐数据的流：每次 1000 字节、每次停 20 毫秒，共 n 次。 */
    static InputStream slow(int n) {
        return new InputStream() {
            int i;
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (++i > n) return -1;
                try { Thread.sleep(20); } catch (InterruptedException e) { return -1; }
                return Math.min(len, 1000);
            }
        };
    }

    // A-01：每一步自己的取消开关，不和别的传输串
    @Test public void a01_stepTokenIsIsolatedFromGlobalCancel() throws Exception {
        Downloader.Net old = Downloader.net;
        try {
            Downloader.net = (u, t) -> new Object[] {200, 60_000L, slow(60)};
            Path dir = Files.createTempDirectory("pv-a01");
            AtomicReference<Actions.Result> got = new AtomicReference<>();
            AtomicBoolean tok = new AtomicBoolean();
            Thread th = new Thread(() -> {
                Progress.TOKEN.set(tok);
                got.set(Downloader.download("https://example.com/a.bin", dir.resolve("a.bin"), 60));
            });
            th.start();
            Thread.sleep(150);
            Progress.CANCEL.set(true); // 界面上别的传输被取消：不该影响这一步
            th.join(10_000);
            assertTrue("全局取消不影响带自己开关的那一步：" + got.get().text, got.get().ok);
            // 自己的开关置上才停
            Progress.CANCEL.set(false);
            AtomicBoolean tok2 = new AtomicBoolean();
            Thread th2 = new Thread(() -> {
                Progress.TOKEN.set(tok2);
                got.set(Downloader.download("https://example.com/b.bin", dir.resolve("b.bin"), 60));
            });
            th2.start();
            Thread.sleep(150);
            tok2.set(true);
            th2.join(10_000);
            assertFalse(got.get().ok);
            assertTrue(got.get().text, got.get().text.contains("已取消"));
            // 开始新传输时不会清掉别人的取消
            Progress.CANCEL.set(true);
            Progress.TOKEN.set(new AtomicBoolean());
            Progress.begin();
            assertTrue("别的线程的全局取消没被清", Progress.CANCEL.get());
        } finally {
            Downloader.net = old;
        }
    }

    // A-02：连接卡住不出数据时，取消照样生效
    @Test public void a02_cancelWorksWhileReadIsBlocked() throws Exception {
        Downloader.Net old = Downloader.net;
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        // 像卡住的网络连接：给 100 字节之后 read() 一直阻塞，直到有人 close()
        InputStream pi = new InputStream() {
            int calls;
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (calls++ == 0) return Math.min(len, 100);
                try { closed.await(); } catch (InterruptedException e) { throw new java.io.IOException("interrupted"); }
                throw new java.io.IOException("Socket closed");
            }
            @Override public void close() { closed.countDown(); }
        };
        try {
            Downloader.net = (u, t) -> new Object[] {200, 10_000_000L, pi};
            Path dir = Files.createTempDirectory("pv-a02");
            AtomicBoolean tok = new AtomicBoolean();
            AtomicReference<Actions.Result> got = new AtomicReference<>();
            Thread th = new Thread(() -> {
                Progress.TOKEN.set(tok);
                got.set(Downloader.download("https://example.com/stuck.bin", dir.resolve("stuck.bin"), 600));
            });
            long t0 = System.currentTimeMillis();
            th.start();
            Thread.sleep(300);
            tok.set(true); // 这时 read() 正阻塞着
            th.join(5000);
            assertFalse("取消后线程应该结束", th.isAlive());
            assertTrue("取消在几秒内生效", System.currentTimeMillis() - t0 < 5000);
            assertFalse(got.get().ok);
            assertTrue(got.get().text, got.get().text.contains("已取消"));
            assertFalse("不留半截", Files.exists(dir.resolve("stuck.bin.part")));
        } finally {
            Downloader.net = old;
            closed.countDown();
        }
    }

    // A-03：跳转逐跳核对
    @Test public void a03_redirectToUnapprovedHostStops() throws Exception {
        Downloader.Net old = Downloader.net;
        try {
            byte[] data = "hello".getBytes();
            List<String> asked = new ArrayList<>();
            Downloader.net = (u, t) -> {
                asked.add(u);
                if (u.contains("example.com/start")) return new Object[] {302, -1L, new java.io.ByteArrayInputStream(new byte[0]), "https://cdn.other.net/file.bin"};
                if (u.contains("example.com/rel")) return new Object[] {301, -1L, new java.io.ByteArrayInputStream(new byte[0]), "/real.bin"};
                if (u.contains("example.com/plain")) return new Object[] {302, -1L, new java.io.ByteArrayInputStream(new byte[0]), "http://example.com/x"};
                if (u.contains("example.com/loop")) return new Object[] {302, -1L, new java.io.ByteArrayInputStream(new byte[0]), "https://example.com/loop"};
                return new Object[] {200, (long) data.length, new java.io.ByteArrayInputStream(data)};
            };
            Path dir = Files.createTempDirectory("pv-a03");
            Actions.Result r = Downloader.download("https://example.com/start", dir.resolve("a"), 60, h -> false);
            assertFalse(r.ok);
            assertEquals("redirect_not_approved", r.data.get("reason"));
            assertTrue(r.text, r.text.contains("cdn.other.net"));
            assertFalse("没去下没批准的主机", asked.contains("https://cdn.other.net/file.bin"));
            assertFalse(Files.exists(dir.resolve("a")));
            // 白名单 / 完全访问放行
            Actions.Result ok = Downloader.download("https://example.com/start", dir.resolve("b"), 60, h -> h.equals("cdn.other.net"));
            assertTrue(ok.text, ok.ok);
            assertArrayEquals(data, Files.readAllBytes(dir.resolve("b")));
            // 同一主机的相对跳转照跟
            assertTrue(Downloader.download("https://example.com/rel", dir.resolve("c"), 60, null).ok);
            assertTrue(asked.contains("https://example.com/real.bin"));
            // https → http 不跟；跳转太多次停
            assertTrue(Downloader.download("https://example.com/plain", dir.resolve("d"), 60, h -> true).text.contains("不加密"));
            assertTrue(Downloader.download("https://example.com/loop", dir.resolve("e"), 60, h -> true).text.contains("跳转太多次"));
        } finally {
            Downloader.net = old;
        }
    }

    // A-04：命令超时 / 停止真的生效，子进程一起结束，结果如实
    @Test public void a04_waitOutHonoursTimeoutAndStop() throws Exception {
        org.junit.Assume.assumeFalse(SafeExec.WINDOWS);
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder("sh", "-c", "echo hi; sleep 30").redirectErrorStream(true).start();
        Object[] r = PcGuard.waitOut(p, 1, 4000);
        assertFalse("1 秒超时", (Boolean) r[0]);
        assertTrue(System.currentTimeMillis() - t0 < 5000);
        assertTrue(new String((byte[]) r[1]).contains("hi"));
        assertTrue("进程被结束", p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
        // 子进程也结束
        Process q = new ProcessBuilder("sh", "-c", "sleep 30 & sleep 30").start();
        Thread.sleep(200);
        List<ProcessHandle> kids = new ArrayList<>();
        q.descendants().forEach(kids::add);
        AtomicBoolean tok = new AtomicBoolean();
        Progress.TOKEN.set(tok);
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } tok.set(true); }).start();
        Object[] c = PcGuard.waitOut(q, 60, 4000);
        assertTrue("停止 = 取消", (Boolean) c[2]);
        Thread.sleep(300);
        for (ProcessHandle k : kids) assertFalse("子进程 " + k.pid() + " 也结束了", k.isAlive());
        // 输出不到上限、进程正常退出
        Process e = new ProcessBuilder("sh", "-c", "echo done; exit 3").start();
        Object[] er = PcGuard.waitOut(e, 10, 4000);
        assertTrue((Boolean) er[0]);
        assertEquals(3, e.exitValue());
    }

    // A-05：设备名 / 结尾点空格
    @Test public void a05_safeNameAvoidsWindowsDeviceNames() {
        assertEquals("_CON", FileXfer.safeName("CON"));
        assertEquals("_nul.txt", FileXfer.safeName("nul.txt"));
        assertEquals("_COM1.log", FileXfer.safeName("COM1.log"));
        assertEquals("a.txt", FileXfer.safeName("a.txt. . "));
        assertEquals("文件", FileXfer.safeName(".."));
        assertEquals("console.txt", FileXfer.safeName("console.txt"));
        assertEquals("b.png", FileXfer.safeName("..\\..\\b.png"));
    }

    // A-06：adb 连上了但后面那条失败，不能是绿色完成
    @Test public void a06_adbConnectedButLaterCommandFailedIsNotOk() {
        Outcomes.Judged j = Outcomes.judgeExec("adb connect 127.0.0.1:16384; adb -s 127.0.0.1:16384 shell su -c id", 1,
                "already connected to 127.0.0.1:16384\n/system/bin/sh: su: not found");
        assertEquals(Outcomes.Level.FAIL, j.level);
        // 原来的两种仍对
        assertEquals(Outcomes.Level.OK, Outcomes.judgeExec("adb connect 127.0.0.1:16384", 1, "already connected to 127.0.0.1:16384").level);
        assertEquals(Outcomes.Level.OK, Outcomes.judgeExec("adb connect 127.0.0.1:16384", 0, "connected to 127.0.0.1:16384").level);
    }

    // A-07：本机地址判断
    @Test public void a07_baseUrlLocalhostMustReallyBeLocal() {
        assertTrue(App.baseOk(""));
        assertTrue(App.baseOk("https://api.deepseek.com/v1"));
        assertTrue(App.baseOk("http://127.0.0.1:11434/v1"));
        assertTrue(App.baseOk("http://localhost:8080"));
        assertFalse(App.baseOk("http://localhost.evil.com/v1"));
        assertFalse(App.baseOk("http://127.0.0.1.nip.io/v1"));
        assertFalse(App.baseOk("http://example.com"));
        assertFalse(App.baseOk("https://user:pw@example.com"));
        assertFalse(App.baseOk("ftp://localhost"));
    }

    // A-08：遍历对话记录时另一个线程在加
    @Test public void a08_storeListIsSnapshot() throws Exception {
        Path f = Files.createTempDirectory("pv-a08").resolve("state.json");
        Store s = new Store(f);
        for (int i = 0; i < 50; i++) { Map<String, Object> m = new TreeMap<>(); m.put("id", "m" + i); s.add("chat", m, 60); }
        AtomicBoolean stop = new AtomicBoolean();
        Thread w = new Thread(() -> { int i = 100; while (!stop.get()) { Map<String, Object> m = new TreeMap<>(); m.put("id", "m" + i++); s.add("chat", m, 60); } });
        w.start();
        try {
            for (int k = 0; k < 200; k++) {
                int n = 0;
                for (Map<String, Object> m : s.list("chat")) if (m.get("id") != null) n++;
                assertTrue(n > 0);
            }
        } finally {
            stop.set(true);
            w.join();
        }
        List<Map<String, Object>> copy = s.list("chat");
        copy.clear();
        assertFalse("改副本不影响里面", s.list("chat").isEmpty());
    }
}
