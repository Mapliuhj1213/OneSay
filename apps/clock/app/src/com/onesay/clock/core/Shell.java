package com.onesay.clock.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 带超时的命令执行（P1-2）：stdout/stderr 合并（redirectErrorStream）由独立线程读，主线程轮询退出，超时就 destroy。
 * 只给可选增强用（root 读三星天气库、从 v1 导入 key）；主流程不依赖 root。永不抛异常。
 */
public final class Shell {
    private Shell() { }

    public static final class R {
        public int code = -1;
        public String out = "";
        public boolean ok, timedOut;
    }

    public static R su(String cmd, long timeoutMs) { return run(new String[]{"su", "-c", cmd}, timeoutMs); }

    public static R run(String[] argv, long timeoutMs) {
        R r = new R();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            p = pb.start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    byte[] b = new byte[8192];
                    int n;
                    try { while ((n = in.read(b)) > 0) synchronized (buf) { buf.write(b, 0, n); } } catch (Exception ignored) { }
                }
            }, "bc-shell");
            t.setDaemon(true);
            t.start();
            try { p.getOutputStream().close(); } catch (Exception ignored) { }
            long end = System.currentTimeMillis() + timeoutMs;
            Integer code = null;
            while (code == null) {
                try { code = Integer.valueOf(p.exitValue()); }
                catch (IllegalThreadStateException e) {
                    if (System.currentTimeMillis() > end) { r.timedOut = true; break; }
                    Thread.sleep(20);
                }
            }
            if (r.timedOut) { p.destroy(); t.join(200); }
            else t.join(1000);
            synchronized (buf) { r.out = new String(buf.toByteArray(), "UTF-8").trim(); }
            r.code = code == null ? -1 : code.intValue();
            r.ok = !r.timedOut && r.code == 0;
        } catch (Throwable e) {
            r.out = String.valueOf(e);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
        return r;
    }
}
