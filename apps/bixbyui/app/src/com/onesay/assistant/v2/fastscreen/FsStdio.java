package com.onesay.assistant.v2.fastscreen;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * ★ fix/1123-bug1 B5：经 Shizuku 起的读屏助手改走「进程自带的标准输入 / 输出」通道（纯 Java，JVM 可测）。
 *
 * <h3>为什么</h3>
 * 魅族 21 Pro（Android 16，SELinux enforcing）上，App（untrusted_app）连 shell 身份助手的抽象 LocalSocket 被拒：
 * {@code avc: denied { connectto } scontext=untrusted_app_27 tcontext=shell tclass=unix_stream_socket}。
 * 查 AOSP sepolicy：app 域连 shell 域、shell 域连 app 域的 unix_stream_socket connectto 都没有放行规则，所以「反过来连」也会被拒。
 * 放行的是「已经连好的匿名管道 / socket 经 Binder 交过来」：Shizuku 起进程时本来就把这个进程的标准输入 / 输出经 Binder
 * 交给发起的 App（{@code IRemoteProcess.getOutputStream / getInputStream}）。所以 App 直接在这两根管道上说原来的帧协议，不再需要 connectto。
 *
 * <h3>格式</h3>
 * 助手先写 {@link #MAGIC}（让 App 跳过 app_process 可能先吐出来的杂字），之后和 LocalSocket 上完全一样：
 * App 写请求帧（{@link FsProtocol#writeRequest}），助手回应答帧（{@link FsProtocol#writeResponse}）。
 * 助手读到标准输入 EOF（App 进程没了 / App 主动关通道）就退出。
 *
 * <h3>身份</h3>
 * 管道两端只在 Shizuku 起进程时交给发起方，别的 App 拿不到；助手启动时已经按 uid + 证书钉住了 App（{@link HelperMain} 的 launchPin），
 * 每条请求照旧过 {@link FsProtocol#validate}（固定方法表、没有任意 shell）。
 */
public final class FsStdio {

    private FsStdio() {}

    /** 通道开头的魔数（8 字节）。 */
    static final byte[] MAGIC = {0, 'O', 'S', 'F', 'S', 'I', 'O', '1'};

    /** App 找魔数最多跳过多少字节杂字。 */
    static final int MAX_SKIP = 64 * 1024;

    /** 助手侧每条请求怎么处理（{@link HelperMain} 实现；测试里换成假的）。 */
    public interface Handler {
        /** @return byte[] / 文本 / {@link Failure}；抛异常 = 失败应答 */
        Object handle(String[] p) throws Exception;

        /** 收到 quit：应答已写出，之后由实现决定退出。 */
        void quit();

        /** 每收到一条请求（空闲计时用）。 */
        void touched();
    }

    /** App 侧拿到的助手管道（Android 侧由 ShizukuBridge 实现）。 */
    public interface Pipes {
        /** 助手的标准输出（读应答）。 */
        InputStream stdout();

        /** 助手的标准输入（写请求）。 */
        OutputStream stdin();

        /** 销毁助手进程并关管道（看门狗 / 断开时用）。 */
        void kill();
    }

    /** 失败应答（ST_ERR）。 */
    public static final class Failure {
        public final String msg;

        public Failure(String msg) {
            this.msg = msg;
        }
    }

    /** 助手侧：写魔数。 */
    public static void writeMagic(OutputStream out) throws IOException {
        out.write(MAGIC);
        out.flush();
    }

    /**
     * App 侧：读到魔数为止（之前最多跳过 {@link #MAX_SKIP} 字节）。
     * @return true = 找到；false = 流结束或杂字太多
     */
    public static boolean readMagic(InputStream in) throws IOException {
        byte[] win = new byte[MAGIC.length];
        int n = 0;
        while (n < MAX_SKIP + MAGIC.length) {
            int c = in.read();
            if (c < 0) return false;
            System.arraycopy(win, 1, win, 0, win.length - 1);   // 滑动窗口：最近 8 字节
            win[win.length - 1] = (byte) c;
            n++;
            if (n >= MAGIC.length && isMagic(win)) return true;
        }
        return false;
    }

    /**
     * 助手侧主循环：逐帧读请求、校验、处理、回应答，直到 EOF（正常返回）。处理在 lock 上串行（和 LocalSocket 连接共用一把锁）。
     */
    public static void serve(DataInputStream in, DataOutputStream out, Handler h, Object lock) throws IOException {
        while (true) {
            String line;
            try {
                line = FsProtocol.readRequest(in);
            } catch (EOFException eof) {
                return;
            }
            h.touched();
            String[] p = FsProtocol.decode(line);
            String bad = FsProtocol.validate(p);
            if (bad != null) {
                FsProtocol.writeResponse(out, FsProtocol.ST_DENY, "DENY " + bad);
                continue;
            }
            if (FsProtocol.OP_QUIT.equals(p[0])) {
                FsProtocol.writeResponse(out, FsProtocol.ST_OK, "bye");
                h.quit();
                return;
            }
            Object r;
            try {
                synchronized (lock) {
                    r = h.handle(p);
                }
            } catch (Throwable t) {
                Throwable x = t.getCause() != null ? t.getCause() : t;
                r = new Failure(String.valueOf(x));
            }
            if (r instanceof byte[]) FsProtocol.writeResponse(out, FsProtocol.ST_OK, (byte[]) r);
            else if (r instanceof Failure) FsProtocol.writeResponse(out, FsProtocol.ST_ERR, ((Failure) r).msg);
            else FsProtocol.writeResponse(out, FsProtocol.ST_OK, String.valueOf(r));
        }
    }

    /**
     * App 侧看门狗：管道读不能设超时，到点还没 {@link #disarm} 就执行 kill（销毁助手进程 → 读端拿到 EOF，不会一直卡住）。
     */
    public static final class Watchdog {
        private final Object mLock = new Object();
        private long mDeadline = Long.MAX_VALUE;
        private Runnable mKill;
        private boolean mStop;
        private Thread mThread;
        private volatile int fired;

        public int firedCount() {
            return fired;
        }

        public void arm(long timeoutMs, Runnable kill) {
            synchronized (mLock) {
                mDeadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
                mKill = kill;
                if (mThread == null) {
                    mThread = com.onesay.assistant.v2.util.Threads.start("fs-stdio-dog", new Runnable() {
                        @Override public void run() {
                            loop();
                        }
                    });
                }
                mLock.notifyAll();
            }
        }

        public void disarm() {
            synchronized (mLock) {
                mDeadline = Long.MAX_VALUE;
                mKill = null;
                mLock.notifyAll();
            }
        }

        public void stop() {
            synchronized (mLock) {
                mStop = true;
                mKill = null;
                mLock.notifyAll();
            }
        }

        private void loop() {
            while (true) {
                Runnable k = null;
                synchronized (mLock) {
                    if (mStop) return;
                    long now = System.currentTimeMillis();
                    if (mKill != null && now >= mDeadline) {
                        k = mKill;
                        mKill = null;
                        mDeadline = Long.MAX_VALUE;
                    } else {
                        try {
                            mLock.wait(mDeadline == Long.MAX_VALUE ? 0 : Math.max(1L, mDeadline - now));
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                }
                fired++;
                try {
                    k.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static boolean isMagic(byte[] b) {
        return b != null && Arrays.equals(b, MAGIC);
    }
}
