package com.onesay.plugin.exec.root;

import com.onesay.plugin.api.exec.ExecBackend;
import com.onesay.plugin.api.exec.ExecResult;

/**
 * root 执行后端插件（可选，默认不选）。两种模式，都不做任何厂商假设：
 * <ul>
 *   <li>{@link Mode#DAEMON}：交给宿主已有的 root 执行守护（文件 IPC + HMAC + 命令白名单，见 tools/kdaemon）。
 *       宿主经 {@link Channel} 注入；守护在线与否由宿主回填。</li>
 *   <li>{@link Mode#SU}：通用 {@code su -c}（Magisk / KernelSU / APatch 等）。没有守护的白名单，安全性只靠上层 ExecutionGate。</li>
 * </ul>
 */
public final class RootExecBackend implements ExecBackend {

    public enum Mode { DAEMON, SU }

    /** 宿主注入的守护通道。 */
    public interface Channel {
        ExecResult run(String command, long timeoutMs);

        Tri online();
    }

    /** 可替换的 su 进程启动器（测试注入）。 */
    public interface SuRunner {
        ExecResult run(String command, long timeoutMs);
    }

    private final Mode mMode;
    private final Channel mChannel;
    private final SuRunner mSu;
    private volatile Tri mSuProbe = Tri.UNKNOWN;
    private volatile String mReason = "还没检测";

    public static RootExecBackend daemon(Channel ch) {
        return new RootExecBackend(Mode.DAEMON, ch, null);
    }

    public static RootExecBackend su(SuRunner runner) {
        return new RootExecBackend(Mode.SU, null, runner != null ? runner : new ProcessSu());
    }

    private RootExecBackend(Mode mode, Channel ch, SuRunner su) {
        mMode = mode;
        mChannel = ch;
        mSu = su;
    }

    @Override public String id() {
        return mMode == Mode.DAEMON ? "root" : "root_su";
    }

    @Override public String title() {
        return mMode == Mode.DAEMON ? "root（执行守护）" : "root（su 直连）";
    }

    @Override public UidLevel level() {
        return UidLevel.ROOT;
    }

    @Override public Tri available() {
        if (mMode == Mode.DAEMON) {
            Tri t = mChannel == null ? Tri.NO : mChannel.online();
            mReason = t == Tri.YES ? null : (mChannel == null ? "宿主没有接入执行守护" : "执行守护不在线（需 root 并安装 tools/kdaemon）");
            return t == null ? Tri.UNKNOWN : t;
        }
        return mSuProbe;
    }

    /** su 模式：跑一次 {@code id -u}，输出 0 才算可用（会触发 root 管理器的授权弹窗）。 */
    public Tri probe() {
        if (mMode == Mode.DAEMON) return available();
        ExecResult r = mSu.run("id -u", 5000);
        boolean ok = r.ok() && r.output.trim().startsWith("0");
        mSuProbe = ok ? Tri.YES : Tri.NO;
        mReason = ok ? null : (r.backendDown ? "没有 su 或被拒绝" : "su 返回的不是 root 身份");
        return mSuProbe;
    }

    @Override public String reason() {
        return mReason;
    }

    @Override public ExecResult run(String command, long timeoutMs) {
        if (command == null || command.trim().length() == 0) return new ExecResult(0, "", false, false);
        if (mMode == Mode.DAEMON) return mChannel == null ? ExecResult.down("no daemon") : mChannel.run(command, timeoutMs);
        return mSu.run(command, timeoutMs);
    }

    /** 默认的 su 启动器：{@code su -c <command>}，合并 stdout/stderr，超时即杀。 */
    static final class ProcessSu implements SuRunner {
        @Override public ExecResult run(String command, long timeoutMs) {
            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                final Process fp = p;
                final StringBuilder out = new StringBuilder();
                Thread rd = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.io.InputStream in = fp.getInputStream();
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                synchronized (out) { if (out.length() < 256 * 1024) out.append(new String(b, 0, n, "UTF-8")); }
                            }
                        } catch (Exception ignored) { }
                    }
                }, "onesay-su-read");
                rd.start();
                long end = System.currentTimeMillis() + Math.max(100, timeoutMs);
                while (true) {
                    try {
                        int rc = p.exitValue();
                        rd.join(500);
                        synchronized (out) { return new ExecResult(rc, out.toString(), false, false); }
                    } catch (IllegalThreadStateException running) {
                        if (System.currentTimeMillis() > end) {
                            p.destroy();
                            return new ExecResult(-1, "timeout", true, false);
                        }
                        Thread.sleep(20);
                    }
                }
            } catch (Exception e) {
                if (p != null) p.destroy();
                return ExecResult.down(e.getClass().getSimpleName());
            }
        }
    }
}
