package com.onesay.desktop;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * ★ exe-handoff-1118：直接用 javaw -jar 跑起来（不经 OneSay.exe）时，界面窗口会退回 Edge 应用窗口（msedge --app），
 * 在任务管理器 / Dock 里显示成 Edge。这里在启动最开始看一眼：本机装了 OneSay.exe（jar 旁边、自带运行时的上一级、
 * 或者默认安装目录 %LOCALAPPDATA%\Programs\OneSay），而且它加载的 app\onesay-desktop.jar 就是现在这个 jar，
 * 就改由 OneSay.exe 启动（窗口用 WebView2 开在 OneSay.exe 里），本进程退出。
 *
 * <p>不交接的情况：不是 Windows；已经是 OneSay.exe 托管（-Donesay.host=1）；用户关掉了（-Donesay.handoff=0 或
 * 环境变量 ONESAY_NO_HANDOFF=1）；本进程就是被交接拉起来的（环境变量 ONESAY_HANDOFF_FROM，防止来回拉）；
 * 找不到完整的安装（exe + app\onesay-desktop.jar + runtime\bin\server\jvm.dll）；
 * 安装目录里的 jar 和现在跑的不是同一个（换了就等于悄悄跑另一个版本 —— 不交接，只提示怎么放进去）。
 * 不交接时照旧，窗口最后才退回 Edge，并且明确标成「Edge 后备窗口」。
 */
final class ExeHandoff {
    static final String EXE = "OneSay.exe";
    static final String GUARD_ENV = "ONESAY_HANDOFF_FROM";

    enum Kind { HANDOFF, NOT_WINDOWS, HOSTED, DISABLED, ALREADY_HANDED, NO_EXE, JAR_DIFFERS, NO_JAR }

    static final class Decision {
        final Kind kind;
        final Path exe;      // 找到的 OneSay.exe（HANDOFF / JAR_DIFFERS）
        final Path appJar;   // 它会加载的 jar
        Decision(Kind kind, Path exe, Path appJar) { this.kind = kind; this.exe = exe; this.appJar = appJar; }
        @Override public String toString() { return kind + (exe == null ? "" : " " + exe); }
    }

    private ExeHandoff() { }

    /** 安装目录里 OneSay.exe 会加载的 jar（与 launcher/Program.cs 相同：&lt;目录&gt;\app\onesay-desktop.jar）。 */
    static Path appJar(Path installDir) { return installDir.resolve("app").resolve("onesay-desktop.jar"); }

    /** 一个目录算完整安装：有 OneSay.exe、app\onesay-desktop.jar、runtime\bin\server\jvm.dll（缺一个 OneSay.exe 就会报「文件不完整」）。 */
    static boolean complete(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(EXE)) && Files.isRegularFile(appJar(dir))
                && Files.isRegularFile(dir.resolve("runtime").resolve("bin").resolve("server").resolve("jvm.dll"));
    }

    /** 可能的安装目录，按先后：jar 在 &lt;目录&gt;\app\ 下；自带运行时 &lt;目录&gt;\runtime；默认安装目录。 */
    static List<Path> candidates(Path runningJar, Path javaHome, String localAppData) {
        LinkedHashSet<Path> c = new LinkedHashSet<>();
        if (runningJar != null) {
            Path p = runningJar.toAbsolutePath().getParent();
            if (p != null && p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("app") && p.getParent() != null) c.add(p.getParent());
        }
        if (javaHome != null) {
            Path h = javaHome.toAbsolutePath();
            if (h.getFileName() != null && h.getFileName().toString().equalsIgnoreCase("runtime") && h.getParent() != null) c.add(h.getParent());
        }
        if (localAppData != null && !localAppData.isBlank()) c.add(Paths.get(localAppData, "Programs", Flavor.dir()));
        return new ArrayList<>(c);
    }

    /** 两个 jar 是同一个：同一个文件，或者大小和 SHA-256 都一样。 */
    static boolean sameJar(Path a, Path b) {
        try {
            if (a == null || b == null || !Files.isRegularFile(a) || !Files.isRegularFile(b)) return false;
            try { if (Files.isSameFile(a, b)) return true; } catch (Exception ignored) { }
            if (Files.size(a) != Files.size(b)) return false;
            return Arrays.equals(sha256(a), sha256(b));
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return md.digest();
    }

    /** 纯判断（便于测试）：要不要交给 OneSay.exe、交给哪个。 */
    static Decision decide(boolean windows, boolean hosted, boolean disabled, boolean alreadyHanded,
                           Path runningJar, Path javaHome, String localAppData) {
        if (!windows) return new Decision(Kind.NOT_WINDOWS, null, null);
        if (hosted) return new Decision(Kind.HOSTED, null, null);
        if (disabled) return new Decision(Kind.DISABLED, null, null);
        if (alreadyHanded) return new Decision(Kind.ALREADY_HANDED, null, null);
        if (runningJar == null || !Files.isRegularFile(runningJar)) return new Decision(Kind.NO_JAR, null, null);
        Decision differs = null;
        for (Path dir : candidates(runningJar, javaHome, localAppData)) {
            if (!complete(dir)) continue;
            Path exe = dir.resolve(EXE), jar = appJar(dir);
            if (sameJar(runningJar, jar)) return new Decision(Kind.HANDOFF, exe, jar);
            if (differs == null) differs = new Decision(Kind.JAR_DIFFERS, exe, jar);
        }
        return differs != null ? differs : new Decision(Kind.NO_EXE, null, null);
    }

    /** OneSay.exe 的命令行：--tray 原样带过去（开机自启 / 计划任务只进托盘）。 */
    static List<String> command(Path exe, boolean tray) {
        List<String> c = new ArrayList<>();
        c.add(exe.toString());
        if (tray) c.add("--tray");
        return c;
    }

    /** 当前进程的判断（读系统属性 / 环境变量 / 自己的 jar 路径）。 */
    static Decision current() {
        Map<String, String> env = System.getenv();
        boolean disabled = "0".equals(System.getProperty("onesay.handoff")) || "1".equals(env.get("ONESAY_NO_HANDOFF"));
        Path jar = null;
        try { jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath(); } catch (Exception ignored) { }
        if (jar != null && !jar.toString().toLowerCase().endsWith(".jar")) jar = null; // 从 classes 目录跑（开发 / 测试）不交接
        String jh = System.getProperty("java.home");
        return decide(App.isWindows(), Host.present(), disabled, env.containsKey(GUARD_ENV), jar,
                jh == null ? null : Paths.get(jh), env.get("LOCALAPPDATA"));
    }

    /**
     * 在 App 启动最开始调用。返回 true 表示已经交给 OneSay.exe，调用方直接结束（不开端口、不开窗口）。
     * 返回 false 照旧往下走；JAR_DIFFERS 时在 stderr 说清楚为什么会是 Edge 后备窗口、怎么改。
     */
    static boolean tryHandoff(boolean tray) {
        Decision d = current();
        if (d.kind == Kind.JAR_DIFFERS) {
            System.err.println("[handoff] 已安装的 " + d.exe + " 加载的是 " + d.appJar + "，和现在跑的 jar 不同，不交接（避免悄悄换版本）。"
                    + "本次窗口只能用 Edge 后备窗口；要让窗口属于 OneSay，把这个 jar 复制成 " + d.appJar + " 后改用 OneSay.exe 启动。");
            return false;
        }
        if (d.kind != Kind.HANDOFF) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(command(d.exe, tray)).directory(d.exe.getParent().toFile());
            pb.environment().put(GUARD_ENV, "java");
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            System.err.println("[handoff] 直接用 java 启动，已交给 " + d.exe + (tray ? " --tray" : "") + "（窗口用 WebView2 开在 OneSay.exe 里），本进程退出。");
            return true;
        } catch (Exception e) {
            System.err.println("[handoff] 交给 " + d.exe + " 失败，照旧在本进程运行：" + e.getMessage());
            return false;
        }
    }
}
