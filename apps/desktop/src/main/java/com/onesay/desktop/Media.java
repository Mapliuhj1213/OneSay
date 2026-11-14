package com.onesay.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 链接下载（思路参考 omniget：链接 → 预览 → 队列下载 → 进度；GPL-3.0 项目，只借思路，代码自写）。引擎是 yt-dlp（Unlicense），以子进程调用。
 *
 * <ul>
 *   <li>先取标题 / 时长 / 大小预览（yt-dlp -J），再进队列下载；队列同时最多 2 个；断点续传靠 yt-dlp 的 --continue。</li>
 *   <li>进度从 yt-dlp 的进度行解析（百分比、速度、剩余时间）；遇到 HTTP 429 退避重试，最多 3 次（10 / 30 / 90 秒）。</li>
 *   <li>每个任务记下实际执行的命令（Cookie 路径打码），也写进日志。</li>
 *   <li>运行前先校验工具的 SHA-256（{@link MediaTools#verify}），对不上不运行。</li>
 *   <li>远程（手机）发来的 media_get 已经过 {@link PcGuard}（动作类型 media_download，「能批就不拦」），这里只管下载。</li>
 * </ul>
 *
 * 手机端指令：media_get {url, quality, audio}、media_status {id?}、media_fetch {id}（≤ 5 MB 时整文件回传，复用取文件同一条通道和上限）、media_cancel {id}。
 */
public final class Media {

    public static final int CONCURRENCY = 2, MAX_RETRY = 3, KEEP = 50;
    public static final long FETCH_MAX = DesktopActions.MAX_FILE;

    public enum State { QUEUED, INSTALLING, PREVIEW, DOWNLOADING, DONE, FAILED, CANCELED }

    /** 一个下载任务（界面 / 手机看到的样子见 {@link #view}）。 */
    public static final class Task {
        public final int id;
        public final String url, quality, audio, from;
        public volatile State state = State.QUEUED;
        public volatile double percent;
        public volatile String speed = "", eta = "", total = "", error = "", title = "", file = "";
        public volatile double duration;
        public volatile long size;
        public volatile int retries;
        public final List<String> commands = new ArrayList<>();
        public final long created = System.currentTimeMillis();
        volatile Process proc;
        volatile boolean cancel;
        volatile boolean installOk, previewed;
        /** yt-dlp 这次写的目标文件（[download] Destination: …），取消时据此清掉 .part / .ytdl / 中间文件；值 = 开始前就已存在（不删它本身）。 */
        final Map<Path, Boolean> dests = new java.util.LinkedHashMap<>();

        Task(int id, String url, String quality, String audio, String from) {
            this.id = id;
            this.url = url;
            this.quality = quality;
            this.audio = audio;
            this.from = from == null ? "" : from;
        }

        boolean finished() { return state == State.DONE || state == State.FAILED || state == State.CANCELED; }
    }

    /** 预览结果。 */
    public static final class Info {
        public String title = "", ext = "";
        public double duration;
        public long size;
    }

    /** 退避等待（测试换成不等的）。 */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    final MediaTools tools;
    final MediaCookies cookies;
    final Path outDir;
    final Consumer<String> log;
    /** 任务结束时（完成 / 失败）告诉电脑前的人。 */
    public volatile Consumer<Task> onFinish = t -> { };
    volatile Sleeper sleeper = Thread::sleep;
    volatile long backoffBaseMs = 10_000;
    volatile long previewTimeoutMs = 90_000;
    private final ExecutorService pool;
    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    public Media(MediaTools tools, MediaCookies cookies, Path outDir, Consumer<String> log) {
        this.tools = tools;
        this.cookies = cookies;
        this.outDir = outDir;
        this.log = log == null ? s -> { } : log;
        this.pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
            Thread t = new Thread(r, "onesay-media");
            t.setDaemon(true);
            return t;
        });
    }

    /** 下载目录：%USERPROFILE%\OneSay\Downloads（其他系统 ~/OneSay/Downloads）；配置里写了就用配置的。 */
    public static Path defaultOutDir(String configured) {
        // 下载目录不按中文用户名回退到 ProgramData（[integ]，见 UserDirs.downloadsDir）
        return UserDirs.downloadsDir(configured, System.getenv("USERPROFILE"), System.getProperty("user.home"));
    }

    public Path outDir() { return outDir; }

    // ------------------------------------------------------------------ 预览

    /** 取标题 / 时长 / 大小。出错抛 IllegalStateException（消息给人看）。 */
    public Info preview(String url, String quality, String audio) {
        String bad = MediaArgs.checkUrl(url);
        if (bad != null) throw new IllegalArgumentException(bad);
        String a = MediaArgs.audio(audio);
        if (a == null) throw new IllegalArgumentException("仅音频只能选 m4a / mp3");
        Task t = new Task(0, url, MediaArgs.quality(quality), a, "");
        return previewFor(t);
    }

    @SuppressWarnings("unchecked")
    Info previewFor(Task t) {
        String why = tools.verify(MediaTools.YTDLP);
        if (why != null) throw new IllegalStateException(why);
        String ff = ffmpegDir();
        Path cookie = null;
        try {
            cookie = cookies == null ? null : cookies.copyFor(t.url, outDir.resolve(".onesay-tmp"));
            List<String> argv = MediaArgs.preview(tools.ytdlpPath().toString(), t.url, t.quality, t.audio, ff, cookie == null ? null : cookie.toString());
            for (int attempt = 0; ; attempt++) {
                if (t.cancel) throw new IllegalStateException("已取消");
                record(t, argv);
                Run r = run(t, argv, null, previewTimeoutMs);
                if (r.exit == 0) {
                    Map<String, Object> j;
                    try { j = Json.obj(r.json.toString()); } catch (Exception e) { throw new IllegalStateException("看不懂 yt-dlp 的预览结果"); }
                    Info in = new Info();
                    in.title = str(j.get("title"));
                    in.ext = str(j.get("ext"));
                    in.duration = num(j.get("duration"));
                    long sz = 0;
                    if (j.get("requested_formats") instanceof List) for (Object f : (List<Object>) j.get("requested_formats")) if (f instanceof Map) sz += size((Map<String, Object>) f);
                    if (sz == 0) sz = size(j);
                    in.size = sz;
                    return in;
                }
                if (!r.limited || attempt >= MAX_RETRY || t.cancel) throw new IllegalStateException(r.error.isEmpty() ? "预览失败（yt-dlp 退出码 " + r.exit + "）" : r.error);
                backoff(t, attempt + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("已停止");
        } catch (RuntimeException e) {
            throw e;
        } catch (SafeExec.Refused e) {
            throw new IllegalStateException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("预览出错：" + e.getClass().getSimpleName());
        } finally {
            delete(cookie);
        }
    }

    static long size(Map<String, Object> m) {
        double v = num(m.get("filesize"));
        if (v <= 0) v = num(m.get("filesize_approx"));
        return (long) v;
    }

    // ------------------------------------------------------------------ 队列

    /**
     * 加一个任务。installOk = 工具缺时允许先装（远程的由确认卡批过；本机的由设置页按钮确认过）；info 非空 = 界面上已经预览过。
     * 参数不对抛 IllegalArgumentException。
     */
    public Task enqueue(String url, Object quality, Object audio, String from, boolean installOk, Info info) {
        String bad = MediaArgs.checkUrl(url);
        if (bad != null) throw new IllegalArgumentException(bad);
        String a = MediaArgs.audio(audio);
        if (a == null) throw new IllegalArgumentException("仅音频只能选 m4a / mp3");
        Task t = new Task(seq.getAndIncrement(), url, MediaArgs.quality(quality), a, from);
        t.installOk = installOk;
        if (info != null) {
            t.title = info.title;
            t.duration = info.duration;
            t.size = info.size;
            t.previewed = true;
        }
        synchronized (tasks) {
            tasks.put(t.id, t);
            if (tasks.size() > KEEP) {
                for (Integer k : new ArrayList<>(tasks.keySet())) {
                    if (tasks.size() <= KEEP) break;
                    if (tasks.get(k).finished()) tasks.remove(k);
                }
            }
        }
        log.accept("链接下载 #" + t.id + " 排队：" + t.url + (t.from.isEmpty() ? "" : "（来自 " + t.from + "）"));
        pool.execute(() -> work(t));
        return t;
    }

    void work(Task t) {
        boolean remote = !t.from.isEmpty();
        SafeExec.REMOTE.set(remote); // 远程触发的：OneSay 是管理员时子进程拒绝启动（永不提权）
        try {
            if (t.cancel) { t.state = State.CANCELED; return; }
            if (tools.missing()) {
                if (!t.installOk) throw new IllegalStateException("下载工具还没安装（在设置的「下载」里安装）");
                t.state = State.INSTALLING;
                log.accept("链接下载 #" + t.id + "：先安装下载工具（官方地址，校验 SHA-256）");
                tools.install(false, null);
            }
            if (!t.previewed) {
                t.state = State.PREVIEW;
                Info in = previewFor(t);
                t.title = in.title;
                t.duration = in.duration;
                t.size = in.size;
            }
            download(t);
        } catch (Throwable e) {
            if (t.cancel) t.state = State.CANCELED;
            else {
                t.state = State.FAILED;
                t.error = MediaTools.msg(e);
            }
        } finally {
            SafeExec.REMOTE.set(Boolean.FALSE);
            if (t.finished()) {
                log.accept("链接下载 #" + t.id + " " + zh(t.state) + (t.error.isEmpty() ? "" : "：" + t.error) + (t.file.isEmpty() ? "" : "：" + t.file));
                try { onFinish.accept(t); } catch (Throwable ignored) { }
            }
        }
    }

    void download(Task t) throws Exception {
        String why = tools.verify(MediaTools.YTDLP);
        if (why != null) throw new IllegalStateException(why);
        String ff = ffmpegDir();
        if (!t.audio.isEmpty() && ff == null) throw new IllegalStateException("仅音频要 FFmpeg（在设置的「下载」里安装）");
        Files.createDirectories(outDir);
        Path cookie = null;
        try {
            cookie = cookies == null ? null : cookies.copyFor(t.url, outDir.resolve(".onesay-tmp"));
            List<String> argv = MediaArgs.download(tools.ytdlpPath().toString(), t.url, t.quality, t.audio, ff, outDir.toString(), cookie == null ? null : cookie.toString());
            for (int attempt = 0; ; attempt++) {
                if (t.cancel) { t.state = State.CANCELED; return; }
                t.state = State.DOWNLOADING;
                record(t, argv);
                Run r = run(t, argv, t, 0);
                if (t.cancel) { t.state = State.CANCELED; cleanupPartial(t); return; }
                if (r.exit == 0) {
                    if (!r.file.isEmpty()) t.file = r.file;
                    t.percent = 100;
                    t.state = State.DONE;
                    return;
                }
                if (!r.limited || attempt >= MAX_RETRY) throw new IllegalStateException(r.error.isEmpty() ? "下载失败（yt-dlp 退出码 " + r.exit + "）" : r.error);
                backoff(t, attempt + 1);
            }
        } finally {
            delete(cookie);
        }
    }

    void backoff(Task t, int n) throws InterruptedException {
        t.retries = n;
        long ms = MediaArgs.backoffMs(n, backoffBaseMs);
        t.error = "被网站限流（HTTP 429），" + (ms / 1000) + " 秒后第 " + n + " 次重试";
        log.accept("链接下载 #" + t.id + "：" + t.error);
        sleeper.sleep(ms);
        t.error = "";
    }

    void record(Task t, List<String> argv) {
        String line = MediaArgs.masked(argv);
        synchronized (t.commands) { t.commands.add(line); }
        log.accept("链接下载 #" + (t.id == 0 ? "预览" : String.valueOf(t.id)) + " 执行：" + line);
    }

    /** FFmpeg 目录：装了且校验通过才给；装了但校验不过 → 拒绝运行（抛错）；没装 → null。 */
    String ffmpegDir() {
        if (!tools.installed(MediaTools.FFMPEG)) return null;
        String why = tools.verify(MediaTools.FFMPEG);
        if (why != null) throw new IllegalStateException(why);
        return tools.ffmpegDir().toString();
    }

    static final class Run {
        int exit = -1;
        boolean limited;
        String error = "", file = "";
        final StringBuilder json = new StringBuilder();
    }

    /** 跑一次 yt-dlp：读输出（进度 / 最终路径 / 错误 / 429）；timeoutMs = 0 不限时。 */
    Run run(Task t, List<String> argv, Task progress, long timeoutMs) throws Exception {
        Run r = new Run();
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = SafeExec.start(pb);
        t.proc = p;
        p.getOutputStream().close();
        Thread killer = null;
        if (timeoutMs > 0) {
            killer = new Thread(() -> {
                try { if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) { }
            }, "onesay-media-timeout");
            killer.setDaemon(true);
            killer.start();
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (MediaArgs.rateLimited(line)) r.limited = true;
                if (line.startsWith("ERROR:")) r.error = line.substring(6).trim();
                if (line.startsWith(MediaArgs.FILE_TAG)) { r.file = line.substring(MediaArgs.FILE_TAG.length()).trim(); continue; }
                noteDest(t, line);
                if (progress != null) {
                    MediaArgs.Progress g = MediaArgs.progress(line);
                    if (g != null) {
                        progress.percent = g.percent;
                        if (!g.speed.isEmpty()) progress.speed = g.speed;
                        progress.eta = g.eta;
                        if (!g.total.isEmpty()) progress.total = g.total;
                    }
                } else if (line.startsWith("{") && line.length() < 64 * 1024 * 1024) {
                    r.json.setLength(0); // -J 的结果是一行 JSON；前后的 WARNING 行不算
                    r.json.append(line);
                }
            }
        }
        r.exit = p.waitFor();
        if (killer != null) killer.interrupt();
        t.proc = null;
        if (r.error.length() > 300) r.error = r.error.substring(0, 300);
        return r;
    }

    private static final java.util.regex.Pattern DEST = java.util.regex.Pattern.compile("^\\[(?:download|ExtractAudio|Merger|VideoConvertor)\\]\\s+(?:Destination:|Merging formats into)\\s+\"?(.+?)\"?\\s*$");

    /** 记下 yt-dlp 说要写的文件（只认下载文件夹里的）。 */
    void noteDest(Task t, String line) {
        java.util.regex.Matcher m = DEST.matcher(line);
        if (!m.find()) return;
        try {
            Path p = Paths.get(m.group(1)).toAbsolutePath().normalize();
            Path dir = outDir().toAbsolutePath().normalize();
            if (!p.startsWith(dir)) return;
            synchronized (t.dests) { t.dests.putIfAbsent(p, Files.exists(p)); }
        } catch (Exception ignored) { }
    }

    /**
     * [fix-1114 实测 P2] 取消后清掉这次下载留下的半截文件：目标文件的 .part / .ytdl / .part-FragN，以及这次新出现的中间文件（只认下载文件夹里的；
     * 开始前就有的完整文件不删）。Windows 上进程刚退出时句柄可能还没放，删不掉就稍等再试几次。
     */
    void cleanupPartial(Task t) {
        List<Path> ds;
        synchronized (t.dests) { ds = new ArrayList<>(t.dests.keySet()); }
        int removed = 0;
        for (Path d : ds) {
            boolean existed;
            synchronized (t.dests) { existed = Boolean.TRUE.equals(t.dests.get(d)); }
            List<Path> victims = new ArrayList<>();
            victims.add(Paths.get(d + ".part"));
            victims.add(Paths.get(d + ".ytdl"));
            if (!existed) victims.add(d);
            try (var s = Files.newDirectoryStream(d.getParent(), d.getFileName().toString().replace("[", "\\[").replace("]", "\\]").replace("{", "\\{").replace("}", "\\}").replace("*", "\\*").replace("?", "\\?") + ".part-Frag*")) {
                for (Path f : s) victims.add(f);
            } catch (Exception ignored) { }
            for (Path v : victims) if (deleteRetry(v)) removed++;
        }
        if (removed > 0) log.accept("链接下载 #" + t.id + " 已取消，清掉了 " + removed + " 个没下完的文件");
    }

    static boolean deleteRetry(Path p) {
        for (int i = 0; i < 5; i++) {
            try {
                return Files.deleteIfExists(p);
            } catch (Exception e) {
                try { Thread.sleep(200); } catch (InterruptedException x) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    public boolean cancel(int id) {
        Task t = task(id);
        if (t == null || t.finished()) return false;
        t.cancel = true;
        Process p = t.proc;
        if (p != null) {
            try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) { } // yt-dlp 起的 ffmpeg 等子进程
            p.destroy();
        }
        if (t.state == State.QUEUED) t.state = State.CANCELED;
        return true;
    }

    public Task task(int id) {
        synchronized (tasks) { return tasks.get(id); }
    }

    public List<Task> tasks() {
        synchronized (tasks) { return new ArrayList<>(tasks.values()); }
    }

    public void shutdown() {
        for (Task t : tasks()) cancel(t.id);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 给界面 / 手机看

    public static String zh(State s) {
        switch (s) {
            case QUEUED: return "排队中";
            case INSTALLING: return "正在安装下载工具";
            case PREVIEW: return "正在取信息";
            case DOWNLOADING: return "下载中";
            case DONE: return "已完成";
            case FAILED: return "失败";
            default: return "已取消";
        }
    }

    public Map<String, Object> view(Task t) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", (long) t.id);
        m.put("url", t.url);
        m.put("quality", t.quality);
        m.put("audio", t.audio);
        m.put("state", t.state.name().toLowerCase());
        m.put("state_zh", zh(t.state));
        m.put("percent", Math.round(t.percent * 10) / 10.0);
        m.put("speed", t.speed);
        m.put("eta", t.eta);
        m.put("total", t.total);
        m.put("title", t.title);
        m.put("duration", MediaArgs.duration(t.duration));
        m.put("size", MediaArgs.mb(t.size));
        m.put("error", t.error);
        m.put("retries", (long) t.retries);
        m.put("from", t.from);
        if (!t.file.isEmpty()) {
            m.put("path", t.file);
            m.put("name", Paths.get(t.file).getFileName().toString());
            try {
                long sz = Files.size(Paths.get(t.file));
                m.put("bytes", sz);
                m.put("fetchable", sz <= FETCH_MAX);
            } catch (Exception e) {
                m.put("fetchable", false);
            }
        }
        synchronized (t.commands) { m.put("commands", new ArrayList<Object>(t.commands)); }
        return m;
    }

    public List<Object> views() {
        List<Object> out = new ArrayList<>();
        List<Task> l = tasks();
        for (int i = l.size() - 1; i >= 0; i--) out.add(view(l.get(i)));
        return out;
    }

    // ------------------------------------------------------------------ 手机指令（判定已经过了）

    /** media_get：args 里 _media_install_ok 是 PcGuard 放行时打的内部标记（手机自己带的 _ 参数在判定时已经丢掉）。 */
    public Actions.Result remoteGet(Map<String, Object> a) {
        String url = str(a.get("url"));
        try {
            boolean installOk = Boolean.TRUE.equals(a.get("_media_install_ok"));
            if (tools.missing() && !installOk) return Actions.Result.fail("电脑上的下载工具还没安装");
            Task t = enqueue(url, a.get("quality"), a.get("audio"), str(a.get("_from")), installOk, null);
            return Actions.Result.ok("已加入电脑的下载队列（#" + t.id + "）", view(t));
        } catch (IllegalArgumentException e) {
            Map<String, Object> d = new TreeMap<>();
            d.put("reason", LinkPolicy.R_UNTRUSTED_URL);
            return new Actions.Result(false, e.getMessage(), d);
        }
    }

    public Actions.Result remoteStatus(Map<String, Object> a) {
        Object id = a.get("id");
        if (id != null && !str(id).isEmpty()) {
            Task t = task(intOf(id));
            return t == null ? Actions.Result.fail("没有这个下载任务") : Actions.Result.ok(zh(t.state), view(t));
        }
        Map<String, Object> d = new TreeMap<>();
        d.put("tasks", views());
        return Actions.Result.ok(tasks().size() + " 个下载任务", d);
    }

    /** media_fetch：已完成且 ≤ 5 MB 的整文件（base64，和取文件同一种回法、同一个上限）；大了只给电脑上的路径。 */
    public Actions.Result remoteFetch(Map<String, Object> a) {
        Task t = task(intOf(a.get("id")));
        if (t == null) return Actions.Result.fail("没有这个下载任务");
        if (t.state != State.DONE || t.file.isEmpty()) return Actions.Result.fail("还没下载完");
        Path p = Paths.get(t.file).toAbsolutePath().normalize();
        try {
            if (!p.startsWith(outDir.toAbsolutePath().normalize()) || !Files.isRegularFile(p)) return Actions.Result.fail("文件不在下载文件夹里了");
            long sz = Files.size(p);
            Map<String, Object> d = new TreeMap<>();
            d.put("path", p.toString());
            d.put("name", p.getFileName().toString());
            d.put("bytes", sz);
            if (sz > FETCH_MAX) return new Actions.Result(false, "文件 " + MediaArgs.mb(sz) + "，超过现有传文件通道的 5 MB 上限，留在电脑上：" + p, d);
            d.put("b64", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
            return Actions.Result.ok("已传回 " + p.getFileName(), d);
        } catch (Exception e) {
            return Actions.Result.fail("读不了文件：" + e.getClass().getSimpleName());
        }
    }

    public Actions.Result remoteCancel(Map<String, Object> a) {
        return cancel(intOf(a.get("id"))) ? Actions.Result.ok("已取消") : Actions.Result.fail("没有这个进行中的任务");
    }

    static int intOf(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o).trim()); } catch (Exception e) { return -1; }
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    static void delete(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }
}
