package com.onesay.assistant.v2.s3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.router.AgentLoop;
import com.onesay.assistant.v2.router.UnifiedRouter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * [s3] 到点执行（在面板服务进程里，岛也在这里）：
 * <ul>
 *   <li>REMINDER → 岛上关键事件展开（完成 / 稍后10分钟），岛不可用时退回一条普通通知；</li>
 *   <li>AGENT → 后台线程无人值守跑一遍 AgentLoop（<b>照样走 ExecutionGate</b>）；岛上显示运行中；需要确认 → 岛上展开确认卡等最多 10 分钟，
 *       没人回应 → 安全中止并显示失败；<b>红卡类能力没有确认卡上的批准绝不执行</b>（{@link S3Safety#isRedClass}）；收尾：岛上完成 / 失败 + 一条结果通知；</li>
 *   <li>错过超过 1 小时的 → 只记「错过」，不补跑（列表里显示「上次错过」）。</li>
 * </ul>
 * 新唤醒不会取消这里的后台任务（v3 多任务）：前台面板任务与它们互不影响。
 */
public final class S3Runner implements com.onesay.assistant.v2.kernel.IslandHost.Listener {

    private static final String TAG = "S3";
    private static final String CHANNEL = "bixby_s3_channel";
    private static volatile S3Runner sInstance;

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** 岛活动 id → 提醒信息。 */
    private final Map<String, String[]> mReminds = new ConcurrentHashMap<String, String[]>();
    private final Map<String, Pending> mPending = new ConcurrentHashMap<String, Pending>();
    private final Map<String, boolean[]> mCancel = new ConcurrentHashMap<String, boolean[]>();

    private final Runnable mTodoTick = new Runnable() {
        @Override
        public void run() {
            refreshTodos();
            mMain.postDelayed(this, 30L * 60L * 1000L);
        }
    };

    static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean allow;
    }

    private S3Runner(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    /** 第 4 阶段（D-3a）：往灵动岛放活动的通道（插件 id = s3；岛控制器没挂载时空操作）。 */
    static com.onesay.assistant.v2.kernel.IslandHost.Channel island() {
        return com.onesay.assistant.v2.kernel.FeatureKernel.get().island("s3");
    }

    public static S3Runner get() {
        return sInstance;
    }

    /** 面板服务 onCreate（S1Hooks 之后）。 */
    public static void onServiceCreate(Context ctx) {
        try {
            S3Runner r = new S3Runner(ctx);
            sInstance = r;
            island().setListener(r);   // 第 4 阶段（D-3a）：经内核
            final Context app = ctx.getApplicationContext();
            S3Hooks.engine(app).setOnChange(new Runnable() {
                @Override
                public void run() {
                    S3Scheduler.reschedule(app, "change");
                    com.onesay.assistant.v2.notice.NoticeHooks.onTasksChanged(app);   // ★：定时邮件提前提醒跟着任务变
                    S3Runner rr = sInstance;
                    if (rr != null) rr.refreshTodos();
                }
            });
            r.mMain.post(r.mTodoTick);
            com.onesay.assistant.v2.jobs.JobsHooks.recover(app);   // lane-a：被杀时执行中的 Run 恢复为待执行 / 失败
            com.onesay.assistant.v2.jobs.JobsHooks.pumpQueue(app);   // 第 4 项：队列接着跑
            com.onesay.assistant.v2.events.EventHooks.onServiceCreate(app);   // 第 7 项：充电 / Wi‑Fi / 蓝牙事件
            com.onesay.assistant.v2.jobs.PowerWhitelistHooks.ensure(app);   // 第 7 项：root 省电白名单（只 debug 变体，默认开）
            com.onesay.assistant.v2.notice.NoticeHooks.onServiceCreate(app);   // ★：灵动岛消息提醒（恢复未过期的、拉日程 / 定时邮件）
            r.fireDue("service-create");
        } catch (Throwable t) {
            Log.w(TAG, "[S3] 初始化异常（不影响面板）: " + t.getMessage());
        }
    }

    public static void onServiceDestroy() {
        S3Runner r = sInstance;
        if (r != null) {
            r.mMain.removeCallbacksAndMessages(null);
            com.onesay.assistant.v2.events.EventHooks.onServiceDestroy(r.mCtx);
        }
        sInstance = null;
    }

    // ================================================================== 到点

    /** 处理所有到期 / 补触发 / 错过的任务（闹钟、开机、改时间、服务启动）。任意线程。 */
    public void fireDue(final String why) {
        com.onesay.assistant.v2.util.Threads.start("s3-fire", new Runnable() {
            @Override
            public void run() {
                synchronized (FIRE_LOCK) {   // 服务启动 + 闹钟 Intent 可能同时到：串行处理，第二次看到的是已重排的库，不会重复触发
                    fireDueLocked(why);
                }
            }
        });
    }

    private static final Object FIRE_LOCK = new Object();

    private void fireDueLocked(String why) {
                try {
                    S3Engine e = S3Hooks.engine(mCtx);
                    long now = System.currentTimeMillis();
                    List<S3Engine.Fire> due = e.due(now);
                    if (!due.isEmpty()) Log.i(TAG, "[S3-FIRE] " + why + " → " + due);
                    for (S3Engine.Fire f : due) {
                        if (f.missed) {
                            Log.i(TAG, "[S3-FIRE] 错过（晚了 " + ((now - f.plannedAt) / 60000L) + " 分钟，超过 1 小时不补）: task#" + f.task.id);
                        } else if (f.task.kind == S3Parser.KIND_REMINDER) {
                            if (S3Settings.s3Enabled(mCtx) || f.task.todoId > 0 && S3Settings.todoEnabled(mCtx)) remind(f);
                            else Log.i(TAG, "[S3-FIRE] 定时任务已关闭，跳过提醒 task#" + f.task.id);
                        } else if (S3Settings.s3Enabled(mCtx)) {
                            runAgent(f);
                        } else {
                            Log.i(TAG, "[S3-FIRE] 定时任务已关闭，跳过 task#" + f.task.id);
                        }
                        e.fired(f, now);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[S3-FIRE] 处理异常: " + t.getMessage());
                }
                // lane-a：定时任务 v2（模板 / 预生成 / 重试 / outbox 送达）与 S3 共用这一个闹钟
                try {
                    com.onesay.assistant.v2.jobs.JobsHooks.onFire(mCtx, why);
                    if (S3Settings.s3Enabled(mCtx)) {
                        com.onesay.assistant.v2.jobs.JobsEngine je = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx);
                        for (com.onesay.assistant.v2.jobs.JobStore.Run run : com.onesay.assistant.v2.jobs.JobStore.Util.inState(je.store().runs(),
                                com.onesay.assistant.v2.jobs.JobMachine.State.PENDING)) {
                            if (run.owner.startsWith("s3:")) execRun(run);   // 失败重试 / 重新授权后放回待执行的 S3 任务
                        }
                        for (com.onesay.assistant.v2.jobs.JobStore.Run run : com.onesay.assistant.v2.jobs.JobStore.Util.inState(je.store().runs(),
                                com.onesay.assistant.v2.jobs.JobMachine.State.PENDING)) {
                            if (run.owner.startsWith("ev:")) execRun(run);   // 第 7 项：服务没起来时留下的 / 重新授权后放回的事件 Run
                        }
                        com.onesay.assistant.v2.jobs.WatchBridgeHooks.runDue(mCtx);   // A+B 合并：B 的 P3 监控到点（watch:<id>，按 plannedAt）
                    } else {
                        com.onesay.assistant.v2.jobs.JobsEngine je = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx);
                        for (com.onesay.assistant.v2.jobs.JobStore.Run run : com.onesay.assistant.v2.jobs.JobStore.Util.inState(je.store().runs(),
                                com.onesay.assistant.v2.jobs.JobMachine.State.PENDING)) {
                            if (run.owner.startsWith("s3:")) je.apply(run, com.onesay.assistant.v2.jobs.JobMachine.Event.CANCEL, System.currentTimeMillis());
                        }
                    }
                    com.onesay.assistant.v2.jobs.JobsHooks.pumpQueue(mCtx);
                } catch (Throwable t) {
                    Log.w(TAG, "[JOBS] 处理异常: " + t.getMessage());
                }
                com.onesay.assistant.v2.notice.NoticeHooks.onFire(mCtx, why);   // ★：提醒到点 / 过期 / 免打扰结束 / 拉来源
                S3Scheduler.reschedule(mCtx, "after-fire(" + why + ")");
    }

    private void remind(S3Engine.Fire f) {
        String id = "r" + f.task.id + "-" + f.plannedAt;
        mReminds.put(id, new String[] {f.task.title, String.valueOf(f.task.todoId)});
        if (f.task.todoId > 0) com.onesay.assistant.v2.planner.RecentReminds.note(f.task.todoId, f.task.title, System.currentTimeMillis());   // ★ borrow-1112：给「说『做完了』勾待办」用
        Log.i(TAG, "[S3-FIRE] 提醒 " + id + " 「" + f.task.title + "」" + (f.lateMin > 0 ? " 晚了 " + f.lateMin + " 分钟" : ""));
        if (island().ready() && island().islandMode()) {
            island().remindFired(id, f.task.title, f.plannedAt, f.lateMin);
        } else {
            postNote(id.hashCode(), "提醒", f.task.title + (f.lateMin > 0 ? "（晚了 " + f.lateMin + " 分钟）" : ""));
        }
    }

    // ================================================================== AGENT（无人值守）

    private void runAgent(final S3Engine.Fire f) {
        // lane-a：每次触发是一条 Run（状态机 待执行 → 执行中 → 等确认 → 完成 / 失败重试），持久化在 jobs.db
        com.onesay.assistant.v2.jobs.JobStore.Run run = new com.onesay.assistant.v2.jobs.JobStore.Run();
        run.owner = com.onesay.assistant.v2.jobs.JobsHooks.s3Key(f.task.id);
        run.title = com.onesay.assistant.v2.shortcut.ShortcutModel.displayTitle(f.task.command);   // 定时快捷指令：授权提示 / 历史里不出现内部指令文字
        run.command = f.task.command;
        run.plannedAt = f.plannedAt;
        run.createdAt = System.currentTimeMillis();
        run.updatedAt = run.createdAt;
        run.step = f.lateMin > 0 ? "晚了 " + f.lateMin + " 分钟" : "";
        com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).store().addRun(run);
        execRun(run);
    }

    /** 第 7 项：事件任务的一次触发（ev:&lt;id&gt;），走同一个执行路径（授权 → 每步权限闸 → 越权停在等确认）。 */
    public void execEvent(com.onesay.assistant.v2.jobs.JobStore.Run run) {
        execRun(run);
    }

    /** 第 4 项：队列项（用户当面交代、排队执行），走同一个执行路径；做完接着跑下一条。 */
    public void execQueued(com.onesay.assistant.v2.jobs.JobStore.Run run) {
        execRun(run);
    }

    /** 执行一条 S3 AGENT 的 Run：先查授权（没有 / 任务改过 → 等确认，不跑），运行时每一步过权限闸。 */
    void execRun(final com.onesay.assistant.v2.jobs.JobStore.Run run) {
        final com.onesay.assistant.v2.jobs.JobsEngine je = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx);
        final com.onesay.assistant.v2.jobs.TaskGrant grant = je.begin(run, System.currentTimeMillis());
        if (grant == null) {
            com.onesay.assistant.v2.jobs.JobsHooks.hint(mCtx, run, com.onesay.assistant.v2.jobs.PermissionGuard.hint(
                    new com.onesay.assistant.v2.jobs.PermissionGuard.Decision(com.onesay.assistant.v2.jobs.PermissionGuard.Verdict.DENY_NO_GRANT, null), run.title));
            return;
        }
        final String id = "a" + run.id;
        // 快捷指令的内部指令「执行快捷指令#id（名字）」不上屏——开始只说「正在执行快捷指令」，结束标题 = 名字（正文「已执行」）
        final boolean shortcut = com.onesay.assistant.v2.shortcut.ShortcutModel.isRunCommand(run.command);
        final String title = shortcut ? com.onesay.assistant.v2.shortcut.ShortcutModel.RUNNING_TITLE
                : run.command + (run.step.length() > 0 ? "（" + run.step + "）" : "") + (run.attempts > 1 ? "（第 " + run.attempts + " 次）" : "");
        final boolean[] cancel = {false};
        mCancel.put(id, cancel);
        island().bgStarted(id, title);
        Log.i(TAG, "[S3-AGENT] 开始 " + id + "：" + com.onesay.assistant.v2.shortcut.ShortcutModel.logText(run.command) + " " + grant);
        com.onesay.assistant.v2.util.Threads.start("s3-agent-" + run.id, new Runnable() {
            @Override
            public void run() {
                AgentLoop.Result r = null;
                String fail = null;
                Unattended cb = new Unattended(id, cancel, run, grant);
                cb.startTainted = com.onesay.assistant.v2.events.EventEngine.isTainted(run);   // 第 7 项：通知原文进了提示词 → 全程 tainted
                boolean scheduled = run.owner.startsWith("s3:");
                // 第 5 项：定时任务要模型顺带给出 worth_telling 判断；发送类工具被拦下时按「定时任务」来源起草 + 等卡片
                com.onesay.assistant.v2.callback.OutboundMailHooks.UNATTENDED.set(new String[]{scheduled ? "scheduled" : "queue", run.command});
                try {
                    r = UnifiedRouter.getInstance(mCtx).runAgentLoop(scheduled ? run.command + com.onesay.assistant.v2.callback.WorthTelling.PROMPT_SUFFIX
                            : run.command, cb, 0);
                } catch (Throwable t) {
                    fail = "执行异常：" + t.getMessage();
                } finally {
                    com.onesay.assistant.v2.callback.OutboundMailHooks.UNATTENDED.remove();
                }
                boolean ok = r != null && r.finished && !cancel[0];
                if (!ok && fail == null) fail = cancel[0] ? (cb.blocked != null ? cb.blocked : "已停止") : r == null ? "任务处理失败" : r.reason;
                String answer = r != null && r.answer != null ? r.answer.trim() : "";
                com.onesay.assistant.v2.callback.WorthTelling.Verdict wv = com.onesay.assistant.v2.callback.WorthTelling.judge(answer,
                        com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency.NORMAL);
                if (scheduled) answer = wv.body;
                Log.i(TAG, "[S3-AGENT] 结束 " + id + " ok=" + ok + (ok ? "" : " reason=" + fail));
                if (shortcut) {
                    // 结束时岛上的完成卡 / 结果通知标题换成快捷指令的名字（bgStarted 同 id 重挂一次 = 改标题，随后 bgEnded 照常计时撤掉）
                    run.title = com.onesay.assistant.v2.shortcut.ShortcutModel.doneTitle(run.command);
                    island().bgStarted(id, com.onesay.assistant.v2.shortcut.ShortcutModel.doneLine(run.command, ok, null));
                }
                island().bgEnded(id, ok, fail);
                long tid = com.onesay.assistant.v2.jobs.JobsEngine.parseIdPublic(run.owner);
                if (run.owner.startsWith("s3:")) {   // 第 7 项：ev:<id> 的 id 是事件任务的，不能写进同号 S3 任务
                    S3Hooks.engine(mCtx).recordResult(tid, ok ? (answer.length() > 0 ? answer : "已完成") : "没完成：" + fail);
                }
                long now = System.currentTimeMillis();
                com.onesay.assistant.v2.jobs.JobStore.Outbox ob = null;
                if (cb.blocked != null) {
                    // 越权 / 要确认：Run 已停在「等确认」，岛上已提示
                } else if (cancel[0]) {
                    je.apply(run, com.onesay.assistant.v2.jobs.JobMachine.Event.CANCEL, now);
                } else {
                    ob = je.finish(run, ok, ok ? (answer.length() > 0 ? answer : "已完成") : fail,
                            scheduled ? wv.urgency.name() : "NORMAL", false, now);
                    if (ob != null && scheduled && wv.parsed) {
                        ob.worth = wv.worth ? 1 : 0;   // 第 5 项：worth_telling（只能下调，判不出按原样送达）
                        je.store().updateOutbox(ob);
                    }
                }
                boolean hub = com.onesay.assistant.v2.kernel.plugins.CallbackPlugin.SWITCH.on(mCtx);
                if (shortcut && run.owner.startsWith(com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX)) {
                    // 点击 / 桌面图标运行的快捷指令，结果不再进灵动岛消息提醒（那条「执行快捷指… +1」胶囊会一直挂着）：
                    //   岛上只有上面那张会自己撤掉的完成 / 失败卡；结果另发一条系统通知（标题 = 名字），计划 App 里也会弹出结果。
                    //   定时触发的快捷指令照旧走回调中心（免打扰 / 送达窗口 / 合并），只是标题换成了名字。
                    if (ob != null) je.delivered(ob, "shortcut-notify", now);
                    if (ok) com.onesay.assistant.v2.callback.CallbackHub.notify(mCtx, 0x5C000000 | (id.hashCode() & 0xFFFFFF), run.title,
                            answer.length() > 0 ? answer : com.onesay.assistant.v2.shortcut.ShortcutModel.DONE_SUFFIX);
                    else if (!cancel[0] && !(island().ready() && island().islandMode()))
                        postNote(id.hashCode(), com.onesay.assistant.v2.shortcut.ShortcutModel.doneLine(run.command, false, null), fail);
                } else if (ok && answer.length() > 0) {
                    if (hub) com.onesay.assistant.v2.callback.CallbackHub.deliverDue(mCtx);
                    else postNote(id.hashCode(), "定时任务：" + run.command, answer);
                } else if (!ok && !cancel[0] && !(island().ready() && island().islandMode())) {
                    // 岛关着：失败卡没地方显示 → 通知（岛开着时失败卡在岛上，不重复通知）
                    postNote(id.hashCode(), "定时任务没完成：" + run.command, fail);
                }
                if (run.state == com.onesay.assistant.v2.jobs.JobMachine.State.RETRY_WAIT) S3Scheduler.reschedule(mCtx, "retry");
                if (com.onesay.assistant.v2.events.EventEngine.isEventRun(run)) com.onesay.assistant.v2.events.EventHooks.onRunDone(mCtx, run);   // 连续失败 → 自动暂停
                mCancel.remove(id);
                if (run.owner.startsWith(com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX)) com.onesay.assistant.v2.jobs.JobsHooks.pumpQueue(mCtx);
            }
        });
    }

    /** 无人值守的回调：门禁照常；红卡类没批准不执行；确认只在岛上、最多等 10 分钟、绝不自动批准。 */
    private final class Unattended implements AgentLoop.CancellableGatedCallback, AgentLoop.Unattended, AgentLoop.TaintedStart,
            com.onesay.assistant.v2.jobs.PermissionGuard.Preflight {
        final String id;
        final boolean[] cancel;
        final com.onesay.assistant.v2.jobs.JobStore.Run run;
        final com.onesay.assistant.v2.jobs.TaskGrant grant;
        /** 被权限闸拦下的原因（非 null = Run 已停在等确认）。 */
        volatile String blocked;
        /** 第 7 项：提示词里带了通知原文（污染数据）→ AgentLoop 从第一步起按 tainted 过闸。 */
        volatile boolean startTainted;

        @Override public boolean taintedFromStart() {
            return startTainted;
        }

        Unattended(String id, boolean[] cancel, com.onesay.assistant.v2.jobs.JobStore.Run run, com.onesay.assistant.v2.jobs.TaskGrant grant) {
            this.id = id;
            this.cancel = cancel;
            this.run = run;
            this.grant = grant;
        }

        @Override public boolean isCancelled() {
            return cancel[0];
        }

        @Override public ExecutionResult execute(Capability cap, JSONObject args) {
            return execute(cap, args, null, true);
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
            return onRequireConfirm(cap, args, reason, 0, true);
        }

        /**
         * ★ feat/shortcuts：权限闸 + 岛上确认（不执行）。execute 先过它；快捷指令的内部动作（加日程 / 播报简报等，不经
         * UnifiedExecutor）在无人值守时也先过它 —— 授权、每次确认、红卡规则与普通定时任务完全一样。null = 放行。
         */
        @Override public ExecutionResult preflight(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token) {
            if (cancel[0]) return ExecutionResult.failed(ErrorCode.CANCELLED, "已取消", "cancelled", cap, args);
            // lane-a 权限卡：只能用创建时授权的工具；越权一律不执行，任务停在「等确认」并在岛上提示。
            // 发邮件 / 付款 / 红卡不可预授权：本次没在卡上确认过（token == null）就先在岛上要确认。
            // 队列项（ADHOC）是用户当面交代的：不走预授权，照常走门禁 + 红卡确认（下面的 S3Safety 分支）
            com.onesay.assistant.v2.jobs.PermissionGuard.Decision pd = grant == com.onesay.assistant.v2.jobs.JobsEngine.ADHOC
                    ? com.onesay.assistant.v2.jobs.PermissionGuard.check("ask.reply", null, null, false)
                    : com.onesay.assistant.v2.jobs.PermissionGuard.check(cap.sign, cap.sideEffect, grant, token != null);
            if (pd.verdict == com.onesay.assistant.v2.jobs.PermissionGuard.Verdict.DENY_UNGRANTED
                    || pd.verdict == com.onesay.assistant.v2.jobs.PermissionGuard.Verdict.DENY_NO_GRANT) {
                String hint = com.onesay.assistant.v2.jobs.PermissionGuard.hint(pd, run.title);
                blocked = hint;
                cancel[0] = true;
                com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).blocked(run, pd, System.currentTimeMillis());
                com.onesay.assistant.v2.jobs.JobsHooks.hint(mCtx, run, hint);
                Log.i(TAG, "[S3-AGENT] 越权调用 " + cap.sign + " → " + pd + "，不执行，任务停在等确认");
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "这一步没有授权，已停下等你确认", "jobs ungranted", cap, args);
            }
            boolean askedThisStep = false;
            if (pd.verdict == com.onesay.assistant.v2.jobs.PermissionGuard.Verdict.CONFIRM_EACH_TIME) {
                Log.i(TAG, "[S3-AGENT] " + cap.sign + " 属于每次确认（" + pd.perm + "）→ 先在岛上要确认");
                if (askOnIsland(cap, args) != AgentLoop.ConfirmVerdict.ALLOW) {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "需要你确认才能执行，已安全跳过", "jobs per-run confirm", cap, args);
                }
                askedThisStep = true;
            }
            if (!askedThisStep && token == null && S3Safety.isRedClass(cap.sign, cap.sideEffect)) {
                // 门禁放行了（例如「始终允许」），但无人值守时红卡动作必须先在岛上弹确认卡
                Log.i(TAG, "[S3-AGENT] 红卡类能力 " + cap.sign + " 无人值守 → 先在岛上要确认");
                if (askOnIsland(cap, args) != AgentLoop.ConfirmVerdict.ALLOW) {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "需要你确认才能执行，已安全跳过", "s3 unattended red", cap, args);
                }
            }
            return null;
        }

        @Override public ExecutionResult execute(Capability cap, JSONObject args, ExecutionGate.ConfirmToken token, boolean tainted) {
            ExecutionResult blockedHere = preflight(cap, args, token);
            if (blockedHere != null) return blockedHere;
            island().bgStep(id, "正在：" + (cap.summary != null ? cap.summary : cap.sign));
            if (run.owner.startsWith(com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX)) {
                run.step = cap.summary != null ? cap.summary : cap.sign;
                com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx).store().updateRun(run);
                com.onesay.assistant.v2.jobs.JobsHooks.queueIsland(mCtx);
            }
            return UnifiedExecutor.getInstance(mCtx).execute(cap, args, token, tainted);
        }

        @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason, int tier, boolean tainted) {
            return askOnIsland(cap, args);
        }

        private AgentLoop.ConfirmVerdict askOnIsland(Capability cap, JSONObject args) {
            if (cancel[0]) return AgentLoop.ConfirmVerdict.DENY;
            if (!island().ready() || !island().islandMode()) {
                // 岛不可用 → 本步不执行（绝不自动批准），但<b>不再静默判拒中止</b>：
                //   Run 停在「等你确认」持久态 + 通知；用户事后在控制台「等你确认」里补确认即可重跑（防安全自动化被「岛忙」消音）。
                String what = com.onesay.assistant.v2.executor.ConfirmFacts.headline(cap);
                Log.i(TAG, "[S3-AGENT] 岛不可用，没法弹确认卡 → 本步不执行，任务停在「等你确认」持久态 + 通知");
                com.onesay.assistant.v2.jobs.JobsEngine je0 = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx);
                je0.parkForConfirm(run, what, System.currentTimeMillis());
                blocked = noIslandConfirmText(what);
                cancel[0] = true;
                String det = com.onesay.assistant.v2.executor.ConfirmFacts.islandDetail(cap, args);
                postNote(("c" + id).hashCode(), "定时任务需要你确认：" + what,
                        noIslandConfirmText(what) + (det != null ? "\n" + det : ""));
                return AgentLoop.ConfirmVerdict.DENY;
            }
            com.onesay.assistant.v2.jobs.JobsEngine je = com.onesay.assistant.v2.jobs.JobsHooks.engine(mCtx);
            run.waitReason = com.onesay.assistant.v2.jobs.JobsEngine.WAIT_CONFIRM;
            je.apply(run, com.onesay.assistant.v2.jobs.JobMachine.Event.NEED_CONFIRM, System.currentTimeMillis());   // 状态机：执行中 → 等确认
            if (run.owner.startsWith(com.onesay.assistant.v2.jobs.JobQueue.OWNER_PREFIX)) com.onesay.assistant.v2.jobs.JobsHooks.queueIsland(mCtx);
            Pending p = new Pending();
            mPending.put(id, p);
            island().bgConfirm(id, com.onesay.assistant.v2.executor.ConfirmFacts.headline(cap),
                    com.onesay.assistant.v2.executor.ConfirmFacts.islandDetail(cap, args));   // ★ LOGIC-6/7b：远程工具真实 sign；收件人 / 金额 / 网址不截断
            boolean decided = false;
            long deadline = System.currentTimeMillis() + IslandModel.BG_CONFIRM_TIMEOUT_MS;
            try {
                while (System.currentTimeMillis() < deadline && !cancel[0]) {
                    if (p.latch.await(500, TimeUnit.MILLISECONDS)) {
                        decided = true;
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            }
            mPending.remove(id);
            island().bgConfirmResolved(id);
            if (!decided) {
                Log.i(TAG, "[S3-AGENT] 确认卡 10 分钟没人回应 → 安全中止 " + id);
                cancel[0] = true;
                je.apply(run, com.onesay.assistant.v2.jobs.JobMachine.Event.DENIED, System.currentTimeMillis());   // 超时 = 取消，绝不自动批准
                return AgentLoop.ConfirmVerdict.DENY;
            }
            je.apply(run, p.allow ? com.onesay.assistant.v2.jobs.JobMachine.Event.CONFIRMED : com.onesay.assistant.v2.jobs.JobMachine.Event.DENIED,
                    System.currentTimeMillis());
            return p.allow ? AgentLoop.ConfirmVerdict.ALLOW : AgentLoop.ConfirmVerdict.DENY;
        }
    }

    // ================================================================== 岛上的按钮（主线程）

    @Override public void onBgConfirm(String id, boolean allow) {
        Pending p = mPending.get(id);
        if (p == null) {
            island().bgConfirmResolved(id);
            return;
        }
        p.allow = allow;
        p.latch.countDown();
    }

    @Override public void onBgStop(String id) {
        boolean[] c = mCancel.get(id);
        if (c != null) c[0] = true;
        Pending p = mPending.get(id);
        if (p != null) {
            p.allow = false;
            p.latch.countDown();
        }
    }

    @Override public void onRemindDone(String id) {
        String[] info = mReminds.remove(id);
        if (info == null) return;
        final long todoId = S3Engine.parseLong(info[1]);
        if (todoId > 0) {
            com.onesay.assistant.v2.planner.RecentReminds.forget(todoId);   // ★ borrow-1112
            com.onesay.assistant.v2.util.Threads.start("s3-todo", new Runnable() {
                @Override public void run() {
                    S3Hooks.engine(mCtx).todoCheck(todoId, System.currentTimeMillis());   // 待办派生的提醒点「完成」= 勾销这条待办
                }
            });
        }
    }

    @Override public void onRemindSnooze(String id) {
        final String[] info = mReminds.remove(id);
        if (info == null) return;
        com.onesay.assistant.v2.util.Threads.start("s3-snooze", new Runnable() {
            @Override public void run() {
                long nid = S3Hooks.engine(mCtx).snooze(info[0], S3Engine.parseLong(info[1]), System.currentTimeMillis());
                Log.i(TAG, "[S3] 稍后10分钟 → 新提醒 task#" + nid);
            }
        });
    }

    @Override public void onTodoCheck(final String id) {
        com.onesay.assistant.v2.util.Threads.start("s3-todo", new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.planner.RecentReminds.forget(S3Engine.parseLong(id));   // ★ borrow-1112
                S3Engine.Exec x = S3Hooks.engine(mCtx).todoCheck(S3Engine.parseLong(id), System.currentTimeMillis());
                Log.i(TAG, "[S3-TODO] 岛上勾销 " + id + " ok=" + x.ok);
            }
        });
    }

    // ================================================================== 待办上岛

    public void refreshTodos() {
        com.onesay.assistant.v2.util.Threads.start("s3-todos", new Runnable() {
            @Override public void run() {
                try {
                    S3Engine e = S3Hooks.engine(mCtx);
                    long now = System.currentTimeMillis();
                    List<IslandModel.TodoRow> rows = new ArrayList<IslandModel.TodoRow>();
                    boolean on = S3Settings.todoEnabled(mCtx);
                    if (on) {
                        for (S3Store.Todo t : e.store().openTodos()) {
                            if (rows.size() >= IslandModel.MAX_TODOS_SHOWN) break;
                            rows.add(new IslandModel.TodoRow(String.valueOf(t.id), t.text, t.dueAt));
                        }
                    }
                    island().setTodos(rows, on ? e.todoDueTodayCount(now) : 0, S3Settings.chipEnabled(mCtx));
                } catch (Throwable t) {
                    Log.w(TAG, "[S3-TODO] 刷新异常: " + t.getMessage());
                }
            }
        });
    }

    // ================================================================== 通知（结果 / 岛不可用时的提醒）

    /** 岛关着（island.off / 开关关 / 没有悬浮窗权限）时无人值守确认的通知正文。纯函数，JVM 可测。 */
    static String noIslandConfirmText(String what) {
        return "「" + what + "」需要你确认，但灵动岛已关闭，没法弹确认卡。这一步没有执行（不会自动批准），任务已停在「等你确认」；"
                + "打开灵动岛后到控制台「等你确认」里点「重新授权」即可补确认重跑，或直接对我说。";
    }

    private void postNote(int nid, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent it = new Intent(mCtx, com.onesay.assistant.v2.ui.MainActivity.class).putExtra("page", "s3")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(mCtx, 7302, it, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OneSay 定时任务", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(mCtx, CHANNEL);
            } else {
                b = newLegacyBuilder();
            }
            nm.notify(nid, b.setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(mCtx.getApplicationInfo().icon).setAutoCancel(true).setContentIntent(pi).build());
        } catch (Throwable t) {
            Log.w(TAG, "[S3] 通知失败: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder newLegacyBuilder() {
        return new Notification.Builder(mCtx);
    }
}
