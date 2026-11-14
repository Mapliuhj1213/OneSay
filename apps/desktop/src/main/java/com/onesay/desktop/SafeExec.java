package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * link-v2 · 安全插件「永不提权」：OneSay 启动的子进程都从这里走。
 *
 * <ul>
 *   <li>只用 {@link ProcessBuilder}（CreateProcess）：不走 ShellExecute，不用 runas 动词，所以永远不会弹 UAC；
 *       要管理员的程序（清单里 requireAdministrator）CreateProcess 直接失败（错误 740），不会提权。</li>
 *   <li>命令行里出现 runas / -Verb RunAs / ShellExecute…runas / 走 UAC 绕过程序（fodhelper 等）一律拒绝，本机触发的也一样。</li>
 *   <li>远程触发（手机发来的指令，{@link #REMOTE}）时，如果 OneSay 自己正以管理员（高完整性级别）令牌运行，子进程会继承这个令牌：
 *       需要用降权令牌启动。纯 Java 做不到创建受限 / 过滤令牌，所以默认<b>拒绝</b>（{@link #deElevate} 留了接口，没有实现就拒）。</li>
 *   <li>检测不了是不是管理员（命令失败 / 出异常）时当作「是」，按拒绝处理（插件出错即拒）。</li>
 * </ul>
 */
public final class SafeExec {

    private SafeExec() {}

    /** 这条线程正在执行远程指令（Agent 在执行手机指令时设上）。 */
    public static final ThreadLocal<Boolean> REMOTE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 检测 OneSay 当前是不是管理员令牌。测试里可以换掉。 */
    public interface Elevation {
        boolean elevated() throws Exception;
    }

    /** 把一个子进程改成用降权令牌启动；做不到返回 null（= 拒绝）。默认没有实现。 */
    public interface DeElevator {
        ProcessBuilder wrap(ProcessBuilder pb);
    }

    public static volatile Elevation elevation = SafeExec::detect;
    public static volatile DeElevator deElevate = null;
    private static volatile Boolean cached;

    /** 拒绝启动时抛出（带中文原因，原因码固定 plugin_blocked）。 */
    public static final class Refused extends IOException {
        public Refused(String why) { super(why); }
    }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 默认检测：Windows 看 whoami /groups 里有没有高 / 系统完整性级别（S-1-16-12288 / S-1-16-16384）；其他系统看是不是 root。 */
    static boolean detect() throws Exception {
        if (!WINDOWS) return "root".equals(System.getProperty("user.name"));
        Process p = new ProcessBuilder("whoami.exe", "/groups").redirectErrorStream(true).start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readNBytes(64 * 1024);
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("whoami 超时");
        }
        String s = new String(out, Charset.defaultCharset());
        if (!s.contains("S-1-16-")) throw new IOException("whoami 没有完整性级别");
        return s.contains("S-1-16-12288") || s.contains("S-1-16-16384");
    }

    /** 当前是不是管理员（缓存）；检测出错按「是」。 */
    public static boolean elevated() {
        Boolean c = cached;
        if (c != null) return c;
        boolean e;
        try {
            e = elevation.elevated();
        } catch (Throwable t) {
            e = true;
        }
        cached = e;
        return e;
    }

    /** 测试用：换检测方法并清缓存。 */
    public static void reset(Elevation e, DeElevator d) {
        elevation = e == null ? SafeExec::detect : e;
        deElevate = d;
        cached = null;
    }

    /** 启动前的检查：命令行想提权 → 拒；远程触发且 OneSay 是管理员 → 降权，降不了就拒。返回真正要启动的 ProcessBuilder。 */
    public static ProcessBuilder check(ProcessBuilder pb) throws Refused {
        List<String> cmd = pb.command();
        String line = LinkPolicy.normalizeCommand(String.join(" ", cmd));
        String why = LinkPolicy.elevationReason(line);
        if (!why.isEmpty()) throw new Refused("子进程" + why + "：OneSay 永远不提权，已拒绝");
        if (Boolean.TRUE.equals(REMOTE.get()) && elevated()) {
            DeElevator d = deElevate;
            ProcessBuilder w = d == null ? null : d.wrap(pb);
            if (w == null) throw new Refused("OneSay 正以管理员身份运行，远程指令的子进程没法降权启动，已拒绝（请用普通方式启动 OneSay）");
            return w;
        }
        return pb;
    }

    public static Process start(ProcessBuilder pb) throws IOException {
        return check(pb).start();
    }

    /** 远程触发、OneSay 是管理员时，JVM 自己写工作区以外的文件也等于提权写：拒绝。 */
    public static void checkInProcessWrite() throws Refused {
        if (Boolean.TRUE.equals(REMOTE.get()) && elevated())
            throw new Refused("OneSay 正以管理员身份运行，远程指令不能写工作区以外的文件（会变成管理员权限写），已拒绝");
    }
}
