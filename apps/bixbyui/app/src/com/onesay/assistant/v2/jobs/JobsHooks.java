package com.onesay.assistant.v2.jobs;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.s3.S3Store;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * lane-a 定时任务 / 主动播报的 Android 胶水：引擎单例、到点处理、模板执行、S3 任务的授权解析。
 *
 * <p>闹钟复用 S3 的单闹钟（{@code S3Scheduler.reschedule} 取 S3 与本引擎的最早时刻），到点由 {@code S3Runner.fireDue} 顺带调 {@link #onFire}。
 */
public final class JobsHooks {

    private JobsHooks() {}

    private static final String TAG = "Jobs";
    private static volatile JobsEngine sEngine;

    public static JobsEngine engine(Context ctx) {
        JobsEngine e = sEngine;
        if (e == null) {
            synchronized (JobsHooks.class) {
                e = sEngine;
                if (e == null) {
                    final Context app = ctx.getApplicationContext();
                    e = new JobsEngine(SqliteJobStore.get(app, SqliteJobStore.DB_PROD), TimeZone.getDefault());
                    e.setResolver(new JobsEngine.Resolver() {
                        @Override public JobStore.Job describe(String owner) {
                            return describeExternal(app, owner);
                        }
                    });
                    sEngine = e;
                }
            }
        }
        return e;
    }

    /** JVM 单测：注入引擎。 */
    public static void setEngineForTest(JobsEngine e) {
        sEngine = e;
    }

    // ================================================================== S3 任务 ↔ 授权

    public static String s3Key(long taskId) {
        return "s3:" + taskId;
    }

    /** S3 任务的规格（参与授权指纹：指令 / 时间改了就要重新授权）。 */
    public static JobStore.Job s3AsJob(S3Store.Task t) {
        JobStore.Job j = new JobStore.Job();
        j.id = t.id;
        j.title = t.label();
        j.command = t.command;
        j.repeat = t.repeat;
        j.weekMask = t.weekMask;
        j.hour = t.hour;
        j.minute = t.minute;
        j.onceAt = t.onceAt;
        j.speak = false;
        return j;
    }

    /** S3 新建 AGENT 任务的参数（还没入库）→ 规格。 */
    public static JobStore.Job s3ArgsAsJob(org.json.JSONObject args) {
        JobStore.Job j = new JobStore.Job();
        j.title = args.optString("command", "");
        j.command = args.optString("command", "");
        j.repeat = args.optInt("repeat", 0);
        j.weekMask = args.optInt("week_mask", 0);
        j.hour = args.optInt("hour", 0);
        j.minute = args.optInt("minute", 0);
        try {
            j.onceAt = Long.parseLong(args.optString("once_at", "0"));
        } catch (NumberFormatException e) {
            j.onceAt = 0;
        }
        j.speak = false;
        return j;
    }

    static JobStore.Job describeExternal(Context app, String owner) {
        try {
            if (owner.startsWith("s3:")) {
                S3Store.Task t = com.onesay.assistant.v2.s3.S3Hooks.engine(app).store().task(JobsEngine.parseId(owner));
                return t == null ? null : s3AsJob(t);
            }
            if (owner.startsWith("ev:")) return com.onesay.assistant.v2.events.EventHooks.engine(app).describe(owner);   // 第 7 项事件任务
            if (owner.startsWith(WatchBridge.OWNER_PREFIX)) return WatchBridgeHooks.describe(app, owner);   // A+B 合并：B 的 P3 监控
        } catch (Throwable t) {
            Log.w(TAG, "[JOBS] 解析来源失败 " + owner + ": " + t);
        }
        return null;
    }

    // ================================================================== 到点

    private static final Object LOCK = new Object();

    /** 闹钟 / 服务启动：建 Run → 跑待执行 → 送达到点的 outbox。在后台线程调用。 */
    public static void onFire(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        List<JobStore.Run> todo;
        synchronized (LOCK) {
            JobsEngine e = engine(app);
            long now = System.currentTimeMillis();
            List<JobStore.Run> created = e.tick(now);
            if (!created.isEmpty()) Log.i(TAG, "[JOBS] " + why + " → 新建 " + created);
            todo = new ArrayList<JobStore.Run>();
            for (JobStore.Run r : JobStore.Util.inState(e.store().runs(), JobMachine.State.PENDING)) {
                if (r.owner.startsWith("job:")) todo.add(r);
            }
        }
        for (JobStore.Run r : todo) runTemplate(app, r);
        com.onesay.assistant.v2.callback.CallbackHub.deliverDue(app);
    }

    /** 服务启动：恢复被杀时执行中的 Run。 */
    public static void recover(Context ctx) {
        try {
            int n = engine(ctx).recover(System.currentTimeMillis());
            if (n > 0) Log.i(TAG, "[JOBS] 恢复 " + n + " 条被中断的执行");
        } catch (Throwable t) {
            Log.w(TAG, "[JOBS] 恢复失败: " + t);
        }
    }

    public static long earliestNext(Context ctx) {
        try {
            return engine(ctx).earliestNext(System.currentTimeMillis());
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ================================================================== 任务队列（第 4 项）

    public static JobQueue queue(Context ctx) {
        return new JobQueue(engine(ctx));
    }

    public static final String QUEUE_ISLAND_ID = "jobs.queue";

    /** 队列里没有正在跑的 → 取下一条交给 S3Runner 执行（一次一个）；刷新岛上的队列进度。任意线程。 */
    public static void pumpQueue(Context ctx) {
        Context app = ctx.getApplicationContext();
        JobStore.Run next;
        synchronized (LOCK) {
            next = queue(app).next();
        }
        queueIsland(app);
        if (next == null) return;
        com.onesay.assistant.v2.s3.S3Runner r = com.onesay.assistant.v2.s3.S3Runner.get();
        if (r == null) {
            Log.i(TAG, "[QUEUE] 面板服务没起来，" + next + " 等下次再跑");
            return;
        }
        Log.i(TAG, "[QUEUE] 轮到 " + next);
        r.execQueued(next);
    }

    /** 岛上的队列进度（空了就撤）。 */
    public static void queueIsland(Context app) {
        com.onesay.assistant.v2.kernel.IslandHost.Channel ch = com.onesay.assistant.v2.callback.CallbackHub.island();
        String t = queue(app).progressText();
        if (t == null) {
            ch.removeBg(QUEUE_ISLAND_ID);
            return;
        }
        ch.bgStarted(QUEUE_ISLAND_ID, "任务队列");
        ch.bgStep(QUEUE_ISLAND_ID, t);
    }

    // ================================================================== 模板执行

    /** 执行一条模板 Run（每日播报）：授权 → 取数（每项过权限闸）→ 拼稿 → outbox。 */
    static void runTemplate(Context app, JobStore.Run r) {
        JobsEngine e = engine(app);
        long now = System.currentTimeMillis();
        TaskGrant g = e.begin(r, now);
        if (g == null) {
            hint(app, r, PermissionGuard.hint(new PermissionGuard.Decision(PermissionGuard.Verdict.DENY_NO_GRANT, null), r.title));
            return;
        }
        try {
            String text;
            if (DailyDigest.TEMPLATE_ID.equals(r.templateId)) {
                text = DailyDigest.compose(todos(app, g), mails(g), weather(app, g), schedule(app, g));   // [sched] 今日日程
            } else if (MyThingsExport.TEMPLATE_ID.equals(r.templateId)) {
                exportMyThings(app, e, r, g);   // 第 6 项
                return;
            } else {
                e.finish(r, false, "未知模板 " + r.templateId, null, false, System.currentTimeMillis());
                return;
            }
            JobStore.Job j = e.describe(r.owner);
            boolean speak = j != null && j.speak;
            e.finish(r, true, text, "NORMAL", speak, System.currentTimeMillis());
            Log.i(TAG, "[JOBS] 模板 " + r.templateId + " 生成完毕 run#" + r.id + "，送达时刻 " + new java.util.Date(r.plannedAt));
        } catch (Throwable t) {
            Log.w(TAG, "[JOBS] 模板执行失败: " + t);
            e.finish(r, false, String.valueOf(t.getMessage()), null, false, System.currentTimeMillis());
        }
    }

    /** 测试注入：root 执行 am broadcast。 */
    interface RootSink {
        boolean exec(Context ctx, String cmd);
    }

    static RootSink sRoot = new RootSink() {
        @Override public boolean exec(Context ctx, String cmd) {
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = new com.onesay.assistant.v2.executor.ShellExecutor(ctx).execute(cmd);
            return r != null && !r.isDown && !r.isTimeout && r.rc == 0;   // 不打日志：payload 里有邮件标题
        }
    };

    /**
     * 第 6 项：「我的事」导出——今日待办 + 未读重要邮件标题 → root 广播给晨间播报 v2（v2 自己落盘）。
     * 每项取数同样过权限闸；发出去后只在回调历史留一条（worth=0 → 没打扰），发不出去走失败重试。
     */
    static void exportMyThings(Context app, JobsEngine e, JobStore.Run r, TaskGrant g) {
        DailyDigest.Part t = todos(app, g), m = mails(g), ev = schedule(app, g);
        String cmd = MyThingsExport.amCommand(MyThingsExport.payload(t, m, ev, System.currentTimeMillis(), TimeZone.getDefault()));   // [sched] + events
        boolean ok;
        try {
            ok = sRoot.exec(app, cmd);
        } catch (Throwable x) {
            ok = false;
        }
        long now = System.currentTimeMillis();
        if (!ok) {
            Log.w(TAG, "[JOBS] 「我的事」导出失败（root 广播没发出去）run#" + r.id);
            e.finish(r, false, "发给晨间播报 v2 失败（root 广播没发出去）", null, false, now);
            return;
        }
        JobStore.Outbox ob = e.finish(r, true, MyThingsExport.summary(t, m), "LOW", false, now);
        if (ob != null) {
            ob.worth = 0;
            e.store().updateOutbox(ob);
        }
        Log.i(TAG, "[JOBS] 「我的事」已导出 run#" + r.id);
    }

    static boolean allowed(TaskGrant g, Perm p) {
        return g.granted.contains(p);
    }

    static DailyDigest.Part todos(Context app, TaskGrant g) {
        if (!allowed(g, Perm.READ_TODO)) return DailyDigest.Part.note("没授权读取");
        List<String> l = new ArrayList<String>();
        long end = com.onesay.assistant.v2.s3.S3Schedule.dayEnd(System.currentTimeMillis(), TimeZone.getDefault());
        for (S3Store.Todo t : com.onesay.assistant.v2.s3.S3Hooks.engine(app).store().openTodos()) {
            if (t.dueAt <= 0 || t.dueAt <= end) l.add(t.text);
        }
        return DailyDigest.Part.of(l);
    }

    /**
     * [sched] 今日本地日程（晨间播报 / 「我的事」导出）。本地日程和待办同属「我的事」、都只在本机，所以「读待办」或「读日历」任一授权即可
     * （旧的每日播报任务只勾了读待办，不用重新授权就能听到日程）。没有本地日程功能或关着 → null（稿子里不提日程，保持旧行为）。
     */
    static DailyDigest.Part schedule(Context app, TaskGrant g) {
        try {
            if (!com.onesay.assistant.v2.sched.SchedHooks.enabled(app)) return null;
            if (!allowed(g, Perm.READ_TODO) && !allowed(g, Perm.READ_CALENDAR)) return DailyDigest.Part.note("没授权读取");
            return DailyDigest.Part.of(com.onesay.assistant.v2.sched.SchedBrief.todayLines(com.onesay.assistant.v2.sched.SchedHooks.engine(app), System.currentTimeMillis()));
        } catch (Throwable t) {
            Log.w(TAG, "[JOBS] 读本地日程失败: " + t);
            return DailyDigest.Part.note("暂时读不到");
        }
    }

    static DailyDigest.Part mails(TaskGrant g) {
        if (!allowed(g, Perm.READ_MAIL)) return DailyDigest.Part.note("没授权读取");
        com.onesay.assistant.v2.conn.port.MailReadPort p = com.onesay.assistant.v2.conn.port.Ports.mailRead();
        if (!p.available()) return DailyDigest.Part.note("还没接入");
        try {
            return DailyDigest.Part.of(p.unreadImportantTitles(5));
        } catch (Throwable t) {
            return DailyDigest.Part.note("暂时读不到");
        }
    }

    static String weather(Context app, TaskGrant g) {
        if (!allowed(g, Perm.WEB_SEARCH)) return "";
        try {
            List<com.onesay.assistant.v2.search.SearchResult> l = com.onesay.assistant.v2.search.WebSearchClient.getInstance(app)
                    .search(com.onesay.assistant.v2.search.WeatherQuery.withHomeCity("今天天气",
                            com.onesay.assistant.v2.search.WebSearchClient.getInstance(app).config().getHomeCity()));
            for (com.onesay.assistant.v2.search.SearchResult s : l) if (s.snippet.length() > 0) return s.snippet;
        } catch (Throwable t) {
            Log.w(TAG, "[JOBS] 天气失败: " + t);
        }
        return "";
    }

    /** 岛上 + 通知提示（等确认 / 越权 / 要重新授权）。 */
    public static void hint(Context app, JobStore.Run r, String text) {
        String id = "jobwait." + r.owner;
        com.onesay.assistant.v2.kernel.IslandHost.Channel ch = com.onesay.assistant.v2.callback.CallbackHub.island();
        ch.removeBg(id);
        ch.bgStarted(id, r.title.length() > 0 ? r.title : "定时任务");
        ch.bgEnded(id, false, text);
        com.onesay.assistant.v2.callback.CallbackHub.notify(app, (int) (0x5D000000L + (r.id & 0xFFFFFF)), "定时任务等你确认", text);
        Log.i(TAG, "[JOBS] 等确认 " + r + "：" + com.onesay.assistant.v2.util.LogText.u(text));
    }
}
