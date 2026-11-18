package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * phone-mirror M1：「手机投屏」页背后的控制器 —— 盯着 adb 设备列表、按用户点击开投屏 / App 窗口、处理拖进来的文件。
 *
 * <p><b>只响应用户在电脑界面上的点击 / 拖拽</b>：不注册成 agent 工具，手机发来的指令、对话里的模型都调不到这里（M1 不给 agent 用）。
 * <p><b>不自动投屏</b>（用户 11-18 定）：插上并授权后只显示「可投屏」和「投屏」按钮。
 * <p>只在投屏组件装好（用户点了「安装」= 同意）之后才开始轮询 adb；没装时什么进程都不起。
 */
public final class PhoneMirror {

    /** 起 scrcpy 窗口进程（可换成假的做测试）。 */
    public interface Launcher {
        Proc start(List<String> argv, Map<String, String> env) throws IOException;
    }

    public interface Proc {
        /** 等它结束，返回退出码；out 收集输出（最多 64 KB）。 */
        int await(StringBuilder out) throws InterruptedException;

        void destroy();
    }

    static final Launcher REAL_LAUNCHER = (argv, env) -> {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
        return new Proc() {
            @Override public int await(StringBuilder out) throws InterruptedException {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        if (bo.size() < 64 * 1024) bo.write(b, 0, n);
                        else bo.reset(); // 只留最后一段（报错在最后）
                    }
                } catch (IOException ignored) { }
                int code = p.waitFor();
                out.append(bo.toString(java.nio.charset.StandardCharsets.UTF_8));
                return code;
            }

            @Override public void destroy() { p.destroy(); }
        };
    };

    final MirrorTools tools;
    final PhoneDevices devices = new PhoneDevices();
    final Consumer<String> log;
    final Path dropDir;
    Adb.Runner adb = Adb.REAL;
    Launcher launcher = REAL_LAUNCHER;
    /** 「物理键盘（UHID）」模式（设置里改，App 传进来）。 */
    Supplier<Boolean> uhid = () -> false;
    /** 状态变化（插上 / 待授权 / 已授权 / 拔掉）→ 通知（App 接 Windows 通知）。 */
    volatile Consumer<PhoneDevices.Event> onEvent;
    volatile String status = "";
    volatile String install = "";
    final Map<String, Proc> procs = new ConcurrentHashMap<>();
    /** 装 APK 被手机拦下时留着的文件（可改为放到 Download），id → 文件；10 分钟过期。 */
    final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile Thread watcher;
    private volatile boolean stopped;

    static final class Pending {
        final String serial, name;
        final Path file;
        final long at;

        Pending(String serial, String name, Path file, long at) {
            this.serial = serial;
            this.name = name;
            this.file = file;
            this.at = at;
        }
    }

    public PhoneMirror(MirrorTools tools, Path dropDir, Consumer<String> log) {
        this.tools = tools;
        this.dropDir = dropDir;
        this.log = log == null ? s -> { } : log;
    }

    String adbPath() { return tools.adb().toString(); }

    Map<String, String> env() {
        Map<String, String> e = new HashMap<>();
        e.put("ADB", tools.adb().toString());
        Path server = tools.scrcpy().resolveSibling("scrcpy-server");
        if (Files.isRegularFile(server)) e.put("SCRCPY_SERVER_PATH", server.toString());
        return e;
    }

    List<String> adbArgs(String serial, String... rest) {
        List<String> a = new ArrayList<>();
        a.add(adbPath());
        if (serial != null) { a.add("-s"); a.add(serial); }
        a.addAll(List.of(rest));
        return a;
    }

    // ------------------------------------------------------------------ 轮询

    /** 后台盯设备：组件装好前空转（不起 adb）；装好后每 2 秒一次 `adb devices -l`。 */
    public synchronized void start() {
        if (watcher != null) return;
        watcher = new Thread(() -> {
            while (!stopped) {
                long wait = 2000;
                try {
                    if (!tools.installed()) { status = ""; wait = 5000; }
                    else {
                        String why = tools.verify();
                        if (why != null) { status = why; wait = 10000; }
                        else {
                            poll(System.currentTimeMillis());
                            if (status.startsWith("adb")) wait = 6000; // adb 起不来时少试几次
                        }
                    }
                } catch (Exception e) {
                    status = "看不了手机列表：" + MediaTools.msg(e);
                    wait = 5000;
                }
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            }
        }, "onesay-phone-mirror");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** 一次轮询（测试可直接调）：列设备 → 更新状态机 → 给新授权的手机读一次 getprop。 */
    void poll(long now) throws IOException, InterruptedException {
        Adb.Run r = adb.run(adbArgs(null, "devices", "-l"), 15000);
        if (r.exit != 0) { status = "adb 没跑起来（退出码 " + r.exit + "）"; return; }
        status = "";
        for (PhoneDevices.Event e : devices.update(Adb.parseDevices(r.out), now)) {
            log.accept("投屏：" + e.zh);
            Consumer<PhoneDevices.Event> c = onEvent;
            if (c != null && !"changed".equals(e.kind)) c.accept(e);
            if ("removed".equals(e.kind)) { stopProc(e.serial + "|main"); for (String k : new ArrayList<>(procs.keySet())) if (k.startsWith(e.serial + "|")) stopProc(k); }
        }
        for (String s : devices.needProbe()) {
            Adb.Run g = adb.run(adbArgs(s, "shell", "getprop"), 10000);
            Map<String, String> p = g.exit == 0 ? Adb.parseGetprop(g.out) : Map.of();
            devices.setInfo(s, PhoneDevices.Info.of(p));
        }
    }

    void stopProc(String key) {
        Proc p = procs.remove(key);
        if (p != null) p.destroy();
    }

    public void shutdown() {
        stopped = true;
        Thread w = watcher;
        if (w != null) w.interrupt();
        for (String k : new ArrayList<>(procs.keySet())) stopProc(k);
    }

    // ------------------------------------------------------------------ 投屏 / App 窗口（用户点击）

    /** 点「投屏」：开主屏镜像窗口。返回 null = 已开，否则原因。 */
    public String mirror(String serial) {
        String why = devices.canMirror(serial);
        if (why != null) return why;
        why = tools.verify();
        if (why != null) return why;
        PhoneDevices.Phone p = devices.get(serial);
        boolean u = Boolean.TRUE.equals(uhid.get());
        boolean audio = p != null && p.info() != null && p.info().audio();
        List<String> argv = ScrcpyArgs.mirror(tools.scrcpy().toString(), serial, p == null ? serial : p.name(), u, audio);
        String key = serial + "|main";
        Proc proc;
        try {
            proc = launcher.start(argv, env());
        } catch (IOException e) {
            return "投屏没打开：" + MediaTools.msg(e);
        }
        procs.put(key, proc);
        devices.mirrorStarted(serial);
        log.accept("投屏：开始 " + (p == null ? serial : p.name()) + (u ? "（物理键盘模式）" : ""));
        watch(proc, key, out -> {
            StringBuilder sb = new StringBuilder();
            int code;
            try { code = proc.await(sb); } catch (InterruptedException e) { return; }
            procs.remove(key, proc);
            String note = Adb.scrcpyZh(sb.toString(), code, u, false);
            devices.mirrorStopped(serial, note);
            log.accept("投屏：窗口关了" + (note == null ? "" : "（" + note + "）"));
        });
        return null;
    }

    /** 点 App：开单 App 窗口（虚拟屏）。返回 null = 已开，否则原因。 */
    public String openApp(String serial, String pkg) {
        String why = devices.canOpenApp(serial, pkg);
        if (why != null) return why;
        why = tools.verify();
        if (why != null) return why;
        boolean u = Boolean.TRUE.equals(uhid.get());
        List<String> argv = ScrcpyArgs.app(tools.scrcpy().toString(), serial, pkg, u);
        if (!devices.appOpened(serial, pkg)) return "这个 App 已经开着窗口了";
        String key = serial + "|" + pkg;
        Proc proc;
        try {
            proc = launcher.start(argv, env());
        } catch (IOException e) {
            devices.appClosed(serial, pkg, null);
            return "窗口没打开：" + MediaTools.msg(e);
        }
        procs.put(key, proc);
        log.accept("投屏：App 窗口 " + pkg);
        watch(proc, key, out -> {
            StringBuilder sb = new StringBuilder();
            int code;
            try { code = proc.await(sb); } catch (InterruptedException e) { return; }
            procs.remove(key, proc);
            devices.appClosed(serial, pkg, Adb.scrcpyZh(sb.toString(), code, u, true));
        });
        return null;
    }

    /** 关掉一个窗口（主屏：pkg 为空）。 */
    public boolean close(String serial, String pkg) {
        String key = serial + "|" + (pkg == null || pkg.isEmpty() ? "main" : pkg);
        if (!procs.containsKey(key)) return false;
        stopProc(key);
        return true;
    }

    void watch(Proc proc, String key, Consumer<Void> body) {
        Thread t = new Thread(() -> body.accept(null), "onesay-mirror-" + key.replaceAll("[^A-Za-z0-9._-]", "_"));
        t.setDaemon(true);
        t.start();
    }

    /** 列第三方 App（pm list packages -3）。 */
    public List<String> apps(String serial) throws IOException, InterruptedException {
        PhoneDevices.Phone p = devices.get(serial);
        if (p == null || !Adb.validSerial(serial)) throw new IOException("这台手机已经拔掉了");
        if (p.state() != PhoneDevices.State.AVAILABLE && p.state() != PhoneDevices.State.MIRRORING) throw new IOException(devices.canMirror(serial));
        String why = tools.verify();
        if (why != null) throw new IOException(why);
        Adb.Run r = adb.run(adbArgs(serial, "shell", "pm", "list", "packages", "-3"), 20000);
        if (r.exit != 0) throw new IOException("读不了 App 列表：" + Adb.installZh(Adb.connectionCode(r.out, r.exit), ""));
        return Adb.parsePackages(r.out);
    }

    // ------------------------------------------------------------------ 拖文件

    /** 拖进来的文件：先落到本机临时目录，再按 {@link DropRoute} 装 / 推。返回给界面的结果。 */
    public Map<String, Object> drop(String serial, String rawName, long size, InputStream body) {
        sweep(System.currentTimeMillis());
        PhoneDevices.Phone p = devices.get(serial);
        if (p == null) return res(false, "这台手机已经拔掉了");
        if (p.state() != PhoneDevices.State.AVAILABLE && p.state() != PhoneDevices.State.MIRRORING) return res(false, devices.canMirror(serial));
        DropRoute.Route r = DropRoute.route(rawName, size);
        if (r.kind == DropRoute.Kind.REJECT) return res(false, (rawName == null ? "" : DropRoute.safeName(rawName) + "：") + r.why);
        String why = tools.verify();
        if (why != null) return res(false, why);
        Path tmp;
        try {
            Files.createDirectories(dropDir);
            byte[] rnd = new byte[9];
            new SecureRandom().nextBytes(rnd);
            String ext = r.kind == DropRoute.Kind.INSTALL_APK ? ".apk" : ".bin";
            tmp = dropDir.resolve("drop-" + java.util.HexFormat.of().formatHex(rnd) + ext);
            long got = 0;
            try (OutputStream o = Files.newOutputStream(tmp)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = body.read(b)) > 0) {
                    got += n;
                    if (got > DropRoute.MAX_BYTES || (size > 0 && got > size)) throw new IOException("文件比说的大");
                    o.write(b, 0, n);
                }
            }
            if (size > 0 && got != size) { Files.deleteIfExists(tmp); return res(false, "文件没传完整"); }
        } catch (IOException e) {
            return res(false, "接收文件失败：" + MediaTools.msg(e));
        }
        try {
            if (r.kind == DropRoute.Kind.INSTALL_APK) {
                Adb.Run run = adb.run(adbArgs(serial, "install", "-r", tmp.toString()), 5 * 60_000);
                Adb.Outcome o = Adb.parseInstall(run.out, run.exit);
                log.accept("投屏：安装 " + r.name + " → " + (o.ok ? "成功" : o.code));
                if (o.ok) return res(true, "已安装 " + r.name);
                Map<String, Object> m = res(false, r.name + "：" + o.zh);
                m.put("code", o.code);
                if ("INSTALL_FAILED_USER_RESTRICTED".equals(o.code)) {
                    String id = java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(8).putLong(new SecureRandom().nextLong()).array());
                    pending.put(id, new Pending(serial, r.name, tmp, System.currentTimeMillis()));
                    m.put("pending", id);
                    m.put("flyme", true);
                    tmp = null; // 留着，可以改为放到 Download
                }
                return m;
            }
            return push(serial, tmp, r.name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return res(false, "被中断了");
        } catch (IOException e) {
            return res(false, "没办成：" + MediaTools.msg(e));
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** 装包被拦后「改为放到 Download」。 */
    public Map<String, Object> pushInstead(String id) {
        Pending pd = id == null ? null : pending.remove(id);
        if (pd == null) return res(false, "文件已经过期了，再拖一次");
        try {
            return push(pd.serial, pd.file, pd.name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return res(false, "被中断了");
        } catch (IOException e) {
            return res(false, "没办成：" + MediaTools.msg(e));
        } finally {
            try { Files.deleteIfExists(pd.file); } catch (IOException ignored) { }
        }
    }

    Map<String, Object> push(String serial, Path local, String name) throws IOException, InterruptedException {
        Adb.Run ls = adb.run(adbArgs(serial, "shell", "ls", "-1", DropRoute.TARGET_DIR), 15000);
        List<String> have = new ArrayList<>();
        if (ls.exit == 0) for (String l : ls.out.split("\\r?\\n")) if (!l.isBlank()) have.add(l.strip());
        String remoteName = DropRoute.unique(name, have);
        String remote = DropRoute.TARGET_DIR + remoteName;
        Adb.Run run = adb.run(adbArgs(serial, "push", local.toString(), remote), 30 * 60_000);
        Adb.Outcome o = Adb.parsePush(run.out, run.exit);
        log.accept("投屏：推文件 " + remoteName + " → " + (o.ok ? "成功" : o.code));
        if (!o.ok) return res(false, remoteName + "：" + o.zh);
        // 让相册 / 文件管理马上看到（失败无所谓）
        adb.run(adbArgs(serial, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d",
                Adb.shQuote("file://" + remote)), 10000);
        Map<String, Object> m = res(true, "已放到手机 Download：" + remoteName);
        m.put("remote", remote);
        return m;
    }

    void sweep(long now) {
        for (Map.Entry<String, Pending> e : new ArrayList<>(pending.entrySet())) {
            if (now - e.getValue().at > 10 * 60_000) {
                pending.remove(e.getKey());
                try { Files.deleteIfExists(e.getValue().file); } catch (IOException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ 安装组件 / 界面

    /** 点「安装」：后台下载官方 scrcpy。 */
    public synchronized String installTools() {
        if (!install.isEmpty() && !install.startsWith("已") && !install.startsWith("失败")) return "正在安装";
        install = "正在下载…";
        Thread t = new Thread(() -> {
            try {
                install = tools.install((what, d, tot) -> install = "正在下载 scrcpy " + MediaArgs.mb(d) + (tot > 0 ? " / " + MediaArgs.mb(tot) : ""));
                log.accept("投屏组件：" + install);
            } catch (Exception e) {
                install = "失败：" + MediaTools.msg(e);
                log.accept("投屏组件安装失败：" + MediaTools.msg(e));
            }
        }, "onesay-mirror-install");
        t.setDaemon(true);
        t.start();
        return null;
    }

    public Map<String, Object> view() {
        Map<String, Object> m = new TreeMap<>();
        m.put("tools", tools.view());
        m.put("install", install);
        m.put("status", status);
        m.put("devices", devices.view());
        m.put("max_apps", (long) PhoneDevices.MAX_APP_WINDOWS);
        m.put("uhid", Boolean.TRUE.equals(uhid.get()));
        return m;
    }

    static Map<String, Object> res(boolean ok, String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", ok);
        m.put("text", text == null ? "" : text);
        return m;
    }
}
