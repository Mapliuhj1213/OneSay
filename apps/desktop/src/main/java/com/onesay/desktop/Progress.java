package com.onesay.desktop;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * [overnight-1119] 下载 / 传文件的进度：百分比、已下 / 总大小、速度、剩余时间；总大小不知道时只报已下多少（界面显示不定进度条）。
 * 正在跑的那一步把进度写进任务卡（{@link #SINK}，Chat 设上）；界面上「取消」或任务「停止」置 {@link #CANCEL}。
 */
public final class Progress {
    private Progress() {}

    /** 这条线程上正在执行的那一步的进度出口（没有 = 不报）。 */
    public static final ThreadLocal<Consumer<Map<String, Object>>> SINK = new ThreadLocal<>();
    /** 取消当前的下载 / 传输（界面「取消」按钮、任务「停止」）。 */
    public static final AtomicBoolean CANCEL = new AtomicBoolean();
    /**
     * [preview-1120 AUDIT A-01] 这条线程上正在跑的那一步自己的取消开关（对话任务每一步一个，Chat 设上）。
     * 以前只有全局 {@link #CANCEL}：对话里「停止 / 取消」会顺带停掉界面上另一个正在发的文件，新开的下载又会把别人的取消清掉。
     * 有这个就只认它；没有（界面直接发文件、测试）才退回全局的。
     */
    public static final ThreadLocal<AtomicBoolean> TOKEN = new ThreadLocal<>();

    /** 当前这一步该看的取消开关。 */
    public static AtomicBoolean cancelFlag() {
        AtomicBoolean t = TOKEN.get();
        return t != null ? t : CANCEL;
    }

    /** 开始一次新的下载 / 传输：只有用的是全局开关时才清掉它（每步自己的开关是新建的，不用清）。 */
    public static AtomicBoolean begin() {
        AtomicBoolean t = TOKEN.get();
        if (t != null) return t;
        CANCEL.set(false);
        return CANCEL;
    }

    /** 一次下载 / 传输的计量器：报得太勤会把任务卡刷爆，最多每 {@link #EVERY_MS} 报一次（结束那次一定报）。 */
    public static final class Meter {
        public static final long EVERY_MS = 300;
        final String what;
        final long total, t0;
        final Consumer<Map<String, Object>> sink;
        final java.util.function.LongSupplier clock;
        long lastAt = -1;

        public Meter(String what, long total, Consumer<Map<String, Object>> sink, java.util.function.LongSupplier clock) {
            this.what = what;
            this.total = total;
            this.sink = sink;
            this.clock = clock == null ? System::currentTimeMillis : clock;
            this.t0 = this.clock.getAsLong();
        }

        public Meter(String what, long total) { this(what, total, SINK.get(), null); }

        /** 报一次（force = 不管间隔）。 */
        public void tick(long done, boolean force) {
            if (sink == null) return;
            long now = clock.getAsLong();
            if (!force && lastAt >= 0 && now - lastAt < EVERY_MS) return;
            lastAt = now;
            try { sink.accept(view(what, done, total, now - t0)); } catch (RuntimeException ignored) { }
        }
    }

    /** 进度的样子（任务卡 / 界面直接用）。 */
    public static Map<String, Object> view(String what, long done, long total, long elapsedMs) {
        Map<String, Object> m = new TreeMap<>();
        m.put("what", what == null ? "" : what);
        m.put("done", done);
        m.put("total", total);
        double sec = Math.max(0.001, elapsedMs / 1000.0);
        long speed = elapsedMs < 200 ? 0 : (long) (done / sec);
        m.put("speed", speed);
        m.put("percent", total > 0 ? Math.min(100L, done * 100 / total) : -1L);
        m.put("eta_sec", total > 0 && speed > 0 ? Math.max(0L, (total - done) / speed) : -1L);
        m.put("text", line(done, total, speed));
        return m;
    }

    /** 一行字：「45%  12.3 MB / 27.0 MB  · 3.1 MB/s · 剩 5 秒」；不知道总大小：「已下 12.3 MB · 3.1 MB/s」。 */
    public static String line(long done, long total, long speed) {
        StringBuilder b = new StringBuilder();
        if (total > 0) {
            b.append(Math.min(100, done * 100 / total)).append("%  ").append(FileXfer.human(done)).append(" / ").append(FileXfer.human(total));
        } else b.append("已下 ").append(FileXfer.human(done)).append("（总大小未知）");
        if (speed > 0) b.append(" · ").append(FileXfer.human(speed)).append("/s");
        if (total > 0 && speed > 0) b.append(" · 剩 ").append(eta((total - done) / speed));
        return b.toString();
    }

    static String eta(long s) {
        if (s < 60) return s + " 秒";
        if (s < 3600) return (s / 60) + " 分 " + (s % 60) + " 秒";
        return (s / 3600) + " 小时 " + (s % 3600 / 60) + " 分";
    }
}
