package com.onesay.assistant.v2.jobs;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.conn.watch.WatchHooks;
import com.onesay.assistant.v2.conn.watch.WatchItem;
import com.onesay.assistant.v2.conn.watch.WatchJudge;
import com.onesay.assistant.v2.conn.watch.WatchPorts;
import com.onesay.assistant.v2.conn.watch.WatchSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * A↔B 合并的 Android 装配：注册 {@link WatchPorts#setTrigger} / {@link WatchPorts#setSink}，到点执行 {@code watch:} Run。
 * 复用 ③ 的 jobs.db / S3 闹钟 / outbox，不另起调度。授权：第一次到点没有授权 → 等确认（WAIT_REAUTH）+ 岛上提示，
 * 用户在「定时任务」页「等你确认」里重新授权（权限卡只有「联网搜索」）后放回待执行。
 */
public final class WatchBridgeHooks {

    private WatchBridgeHooks() {}

    private static final String TAG = "Jobs";
    private static volatile boolean sInstalled;

    public static WatchBridge bridge(Context ctx) {
        return new WatchBridge(JobsHooks.engine(ctx.getApplicationContext()));
    }

    public static void install(Context ctx) {
        if (sInstalled || ctx == null) return;
        final Context app = ctx.getApplicationContext();
        synchronized (WatchBridgeHooks.class) {
            if (sInstalled) return;
            WatchPorts.setTrigger(new WatchPorts.Trigger() {
                @Override public void schedule(String watchId, long atMillis) {
                    try {
                        WatchSpec s = WatchHooks.service(app).get(watchId);
                        JobStore.Run r = bridge(app).schedule(watchId, s == null ? watchId : s.title, atMillis, System.currentTimeMillis());
                        Log.i(TAG, "[WATCH] 安排 " + r + " at=" + atMillis);
                        com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "watch-schedule");
                    } catch (Throwable t) {
                        Log.w(TAG, "[WATCH] 安排失败: " + t);
                    }
                }

                @Override public void cancel(String watchId) {
                    try {
                        int n = bridge(app).cancel(watchId, System.currentTimeMillis());
                        Log.i(TAG, "[WATCH] 取消 watch:" + watchId + " ×" + n);
                    } catch (Throwable t) {
                        Log.w(TAG, "[WATCH] 取消失败: " + t);
                    }
                }
            });
            WatchPorts.setSink(new WatchPorts.Sink() {
                @Override public void onChange(WatchSpec spec, List<WatchItem> items, String message) {
                    List<String> urls = new ArrayList<String>();
                    if (items != null) for (WatchItem i : items) urls.add(i.url);
                    bridge(app).onChange(spec.id, spec.title, urls, message, System.currentTimeMillis());
                    Log.i(TAG, "[WATCH] watch:" + spec.id + " 有变化 → outbox（" + urls.size() + " 条）");
                    com.onesay.assistant.v2.callback.CallbackHub.deliverDue(app);
                }

                @Override public void onBroken(WatchSpec spec, String error) {
                    String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date());
                    bridge(app).onBroken(spec.id, spec.title, error, day, System.currentTimeMillis());
                    Log.i(TAG, "[WATCH] watch:" + spec.id + " 连续失败 → outbox（LOW）");
                    com.onesay.assistant.v2.callback.CallbackHub.deliverDue(app);
                }
            });
            sInstalled = true;
            Log.i(TAG, "[WATCH] 已接上 A 线定时 / 回调（WatchPorts → jobs.db）");
        }
    }

    /** JobsHooks.describeExternal：watch:&lt;id&gt; → 授权规格。 */
    static JobStore.Job describe(Context app, String owner) {
        String id = WatchBridge.idOf(owner);
        WatchSpec s = id == null ? null : WatchHooks.service(app).get(id);
        return s == null ? null : WatchBridge.asJob(s.id, s.title, s.query);
    }

    /** 到点：S3Runner.fireDueLocked 调。查授权（没有 → 等确认 + 岛上提示），后台线程 runOne。 */
    public static void runDue(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final WatchBridge b = bridge(app);
        final JobsEngine je = JobsHooks.engine(app);
        for (final JobStore.Run run : b.due(System.currentTimeMillis())) {
            TaskGrant g = je.begin(run, System.currentTimeMillis());
            if (g == null) {
                JobsHooks.hint(app, run, PermissionGuard.hint(new PermissionGuard.Decision(PermissionGuard.Verdict.DENY_NO_GRANT, null), run.title));
                Log.i(TAG, "[WATCH] " + run + " 没有授权 → 等确认");
                continue;
            }
            if (!g.granted.contains(Perm.WEB_SEARCH)) {
                je.blocked(run, new PermissionGuard.Decision(PermissionGuard.Verdict.DENY_UNGRANTED, Perm.WEB_SEARCH), System.currentTimeMillis());
                continue;
            }
            com.onesay.assistant.v2.util.Threads.start("watch-" + run.id, new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    String err = "";
                    try {
                        WatchJudge.Verdict v = WatchHooks.service(app).runOne(WatchBridge.idOf(run.owner));
                        ok = v != null && v.outcome != WatchJudge.Outcome.ERROR;
                        err = v == null ? "监控已删除" : v.reason;
                    } catch (Throwable t) {
                        err = "执行异常：" + t.getMessage();
                    }
                    b.finish(run, ok, err, System.currentTimeMillis());
                    Log.i(TAG, "[WATCH] " + com.onesay.assistant.v2.shortcut.ShortcutModel.logText(run.owner) + " 跑完 ok=" + ok);
                    com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "watch-done");
                }
            });
        }
    }
}
