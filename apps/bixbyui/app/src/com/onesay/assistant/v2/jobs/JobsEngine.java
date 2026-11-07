package com.onesay.assistant.v2.jobs;

import com.onesay.assistant.v2.s3.S3Schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * lane-a 定时任务 + 主动播报的引擎（纯 Java，JVM 可测；Android 一侧 {@link JobsHooks} 只负责闹钟、执行和送达）。
 *
 * <h3>入库要先授权</h3>
 * {@link #prepare} 只做静态分析，返回 {@link Pending}（不写库）；用户在权限卡上勾选后 {@link #confirmCreate} 才入库，授权跟任务一起存。
 * {@link #edit} 改任务：规格或清单变了 → 授权指纹对不上，之后每次运行都停在「等确认」直到重新授权（{@link #reauthorize}）。
 *
 * <h3>S3 预生成：提前生成，准时送达，过期的不播</h3>
 * 到「送达时刻 − 提前量」时建一条 Run 开始生成；结果写进 outbox，{@code deliverAfter = 送达时刻}，{@code expireAt = 送达时刻 + }{@link #STALE_MS}。
 * 送达时刻之前不送；过了 expireAt 还没送出去（手机关机 / 进程被杀太久）→ 标记过期，不播。
 * 生成时就已经比送达时刻晚了 {@link #MISS_MS} 以上 → 记「错过」，不生成。
 */
public final class JobsEngine {

    /** 默认提前生成量。 */
    public static final long DEFAULT_LEAD_MS = 5L * 60L * 1000L;
    /** 结果过期：送达时刻之后这么久还没送出 → 不播。 */
    public static final long STALE_MS = 30L * 60L * 1000L;
    /** 生成时已晚于送达时刻这么久 → 错过。 */
    public static final long MISS_MS = S3Schedule.CATCHUP_MS;
    /** 活跃任务上限（研究报告建议值）。 */
    public static final int MAX_JOBS = 20;

    private final JobStore mStore;
    private final TimeZone mTz;

    /** 外部来源（S3 定时任务 s3:&lt;id&gt;、事件任务 ev:&lt;id&gt;）的规格解析：把来源描述成一个 Job（只用 spec / command 算授权指纹）。 */
    public interface Resolver {
        /** null = 来源已不存在。 */
        JobStore.Job describe(String owner);
    }

    private volatile Resolver mResolver;

    public void setResolver(Resolver r) {
        mResolver = r;
    }

    public JobsEngine(JobStore store, TimeZone tz) {
        mStore = store;
        mTz = tz == null ? TimeZone.getDefault() : tz;
    }

    public JobStore store() {
        return mStore;
    }

    // ================================================================== 创建 / 修改 / 授权

    /** 待授权的新任务（还没入库）。 */
    public static final class Pending {
        public final JobStore.Job job;
        public final Set<Perm> required;

        Pending(JobStore.Job job, Set<Perm> required) {
            this.job = job;
            this.required = required;
        }

        /** 权限卡上可以勾的项。 */
        public Set<Perm> grantable() {
            Set<Perm> s = java.util.EnumSet.noneOf(Perm.class);
            for (Perm p : required) if (p.preGrantable) s.add(p);
            return s;
        }

        /** 权限卡上「每次执行都单独确认」的项（不能勾）。 */
        public Set<Perm> perRun() {
            return TaskGrant.perRun(required);
        }
    }

    public static Set<Perm> analyze(JobStore.Job j) {
        Set<Perm> s = PermissionAnalyzer.analyze(j.command, j.templateId, null);
        CommandPerms cp = sCommandPerms;
        if (cp != null && j.command != null) {
            try {
                Set<Perm> extra = cp.permsFor(j.command);
                if (extra != null) s.addAll(extra);
            } catch (RuntimeException ignored) {
                // 解析不了按只看指令文字处理（宁可多要一次授权，不放宽）
            }
        }
        return s;
    }

    /**
     * ★ feat/shortcuts：指令本身只是个引用（「执行快捷指令#12（晚安）」）时，权限清单按它实际要做的步骤算。
     * 宿主注入（ShortcutHooks）；返回 null = 不认识这条指令。参与授权指纹：快捷指令的步骤改了 → 清单变 → 到点要重新授权一次。
     */
    public interface CommandPerms {
        Set<Perm> permsFor(String command);
    }

    private static volatile CommandPerms sCommandPerms;

    public static void setCommandPerms(CommandPerms cp) {
        sCommandPerms = cp;
    }

    /** 静态分析出权限清单；不写库。 */
    public Pending prepare(JobStore.Job j) {
        return new Pending(j.copy(), analyze(j));
    }

    /** 用户在权限卡上确认（checked = 勾选项）→ 入库 + 存授权。返回任务 id；时间已过 / 太多返回 -1。 */
    public long confirmCreate(Pending p, Set<Perm> checked, long now) {
        if (p == null) return -1;
        int active = 0;
        for (JobStore.Job x : mStore.jobs()) if (x.enabled) active++;
        if (active >= MAX_JOBS) return -1;
        JobStore.Job j = p.job.copy();
        j.createdAt = now;
        if (j.leadMs <= 0) j.leadMs = DEFAULT_LEAD_MS;
        j.nextDeliverAt = nextDeliver(j, now);
        if (j.nextDeliverAt <= 0) return -1;
        long id = mStore.addJob(j);
        j.id = id;
        // 授权指纹按入库后的规格算（规格不含 id，与 prepare 时一致）
        mStore.saveGrant(j.grantKey(), TaskGrant.grant(j.spec(), analyze(j), checked, now));
        return id;
    }

    /** 改任务。返回是否需要重新授权。 */
    public boolean edit(JobStore.Job j, long now) {
        j.nextDeliverAt = nextDeliver(j, now);
        mStore.updateJob(j);
        TaskGrant g = mStore.grant(j.grantKey());
        return g == null || g.needsReauth(j.spec(), analyze(j));
    }

    /** 重新授权（权限卡再确认一次）；把这条任务停在「等确认」的 Run 放回待执行。 */
    public void reauthorize(long jobId, Set<Perm> checked, long now) {
        JobStore.Job j = mStore.job(jobId);
        if (j == null) return;
        mStore.saveGrant(j.grantKey(), TaskGrant.grant(j.spec(), analyze(j), checked, now));
        for (JobStore.Run r : mStore.runs()) {
            if (r.owner.equals(j.grantKey()) && r.state == JobMachine.State.WAITING_CONFIRM && resumable(r.waitReason)) {
                r.state = JobMachine.State.PENDING;
                r.waitReason = "";
                r.updatedAt = now;
                mStore.updateRun(r);
            }
        }
    }

    public void delete(long jobId) {
        JobStore.Job j = mStore.job(jobId);
        if (j == null) return;
        mStore.deleteJob(jobId);
        mStore.deleteGrant(j.grantKey());
    }

    /** 当前有效的授权（缺失或指纹不符 → null）。 */
    public TaskGrant validGrant(String owner) {
        TaskGrant g = mStore.grant(owner);
        if (g == null) return null;
        JobStore.Job j = describe(owner);
        if (j == null || g.needsReauth(j.spec(), analyze(j))) return null;
        return g;
    }

    /** 一个授权键对应的任务规格（job: 查本库，其余交给 {@link Resolver}）。 */
    public JobStore.Job describe(String owner) {
        if (owner == null) return null;
        if (owner.startsWith("job:")) return mStore.job(parseId(owner));
        Resolver r = mResolver;
        return r == null ? null : r.describe(owner);
    }

    /** 外部来源（s3: / ev:）授权：权限卡确认后存。 */
    public TaskGrant grantExternal(String owner, Set<Perm> checked, long now) {
        JobStore.Job j = describe(owner);
        if (j == null) return null;
        TaskGrant g = TaskGrant.grant(j.spec(), analyze(j), checked, now);
        mStore.saveGrant(owner, g);
        for (JobStore.Run r : mStore.runs()) {
            if (r.owner.equals(owner) && r.state == JobMachine.State.WAITING_CONFIRM && resumable(r.waitReason)) {
                r.state = JobMachine.State.PENDING;
                r.waitReason = "";
                r.updatedAt = now;
                mStore.updateRun(r);
            }
        }
        return g;
    }

    long nextDeliver(JobStore.Job j, long now) {
        return S3Schedule.next(j.repeat, j.hour, j.minute, j.weekMask, j.onceAt, now, mTz);
    }

    // ================================================================== 到点：建 Run

    public static final String WAIT_REAUTH = "需要重新授权";
    public static final String WAIT_UNGRANTED = "越权调用已拦下";
    public static final String WAIT_CONFIRM = "等你确认";
    /**
     * 无人值守时要确认、但灵动岛不可用（弹不出卡）——不再直接判拒中止，
     * 而是把 Run 停在「等你确认」持久态；用户在控制台「等你确认」里点「重新授权」（或权限卡再确认）后放回待执行重跑。
     */
    public static final String WAIT_NO_ISLAND = "等你确认（灵动岛关着，没法弹卡）";

    /** LOGIC-7a：把 Run 停在「等你确认（岛不可用）」持久态。 */
    public void parkForConfirm(JobStore.Run r, String what, long now) {
        if (r == null) return;
        r.waitReason = WAIT_NO_ISLAND + (what == null || what.length() == 0 ? "" : "：" + what);
        apply(r, JobMachine.Event.NEED_CONFIRM, now);
    }

    /** 重新授权 / 补确认时可以放回待执行的等待原因。 */
    static boolean resumable(String waitReason) {
        return waitReason != null && (waitReason.startsWith(WAIT_REAUTH) || waitReason.startsWith(WAIT_NO_ISLAND));
    }

    /**
     * 到点处理（闹钟 / 服务启动 / 每次 tick）：
     * 到了「送达 − 提前量」的任务建 Run（待执行）；晚太多的记错过；失败待重试到点的放回待执行。返回新建的 Run。
     */
    public List<JobStore.Run> tick(long now) {
        List<JobStore.Run> created = new ArrayList<JobStore.Run>();
        for (JobStore.Job j : mStore.jobs()) {
            if (!j.enabled || j.nextDeliverAt <= 0) continue;
            long planned = j.nextDeliverAt;
            if (now < planned - Math.max(0L, j.leadMs)) continue;
            if (mStore.runFor(j.grantKey(), planned) == null) {
                JobStore.Run r = new JobStore.Run();
                r.owner = j.grantKey();
                r.title = j.title;
                r.templateId = j.templateId;
                r.command = j.command;
                r.plannedAt = planned;
                r.expireAt = planned + STALE_MS;
                r.createdAt = now;
                r.updatedAt = now;
                if (now - planned > MISS_MS) {
                    r.state = JobMachine.State.FAILED;
                    r.error = "错过（晚了 " + ((now - planned) / 60000L) + " 分钟）";
                } else {
                    r.state = JobMachine.State.PENDING;
                }
                mStore.addRun(r);
                if (r.state == JobMachine.State.PENDING) created.add(r);
            }
            j.nextDeliverAt = nextDeliver(j, planned + 1000L);
            if (j.repeat == S3Schedule.ONCE) j.nextDeliverAt = -1L;
            mStore.updateJob(j);
        }
        for (JobStore.Run r : mStore.runs()) {
            if (r.state == JobMachine.State.RETRY_WAIT && now >= r.nextRetryAt) {
                apply(r, JobMachine.Event.RETRY_DUE, now);
            }
        }
        return created;
    }

    /** App 被杀后恢复：执行中的视为中断（有余量回到待执行，否则失败）；等确认 / 待重试原样保留。 */
    public int recover(long now) {
        int n = 0;
        for (JobStore.Run r : mStore.runs()) {
            if (r.state == JobMachine.State.RUNNING) {
                apply(r, JobMachine.Event.RECOVER, now);
                if (r.state == JobMachine.State.FAILED) r.error = "进程被杀，重试次数已用完";
                mStore.updateRun(r);
                n++;
            }
        }
        return n;
    }

    // ================================================================== 执行

    /** 应用一个事件；非法迁移返回 false（状态不变）。 */
    public boolean apply(JobStore.Run r, JobMachine.Event e, long now) {
        JobMachine.State n = JobMachine.next(r.state, e, r.attempts, r.maxAttempts);
        if (n == null) return false;
        if (e == JobMachine.Event.START) r.attempts++;
        if (n == JobMachine.State.RETRY_WAIT) r.nextRetryAt = now + JobMachine.retryDelay(r.attempts);
        if (n != JobMachine.State.WAITING_CONFIRM) r.waitReason = n == JobMachine.State.RUNNING ? "" : r.waitReason;
        r.state = n;
        r.updatedAt = now;
        mStore.updateRun(r);
        return true;
    }

    /**
     * 开始执行一条 Run：先查授权。授权缺失 / 指纹不符 → 等确认（WAIT_REAUTH），返回 null；否则 START，返回有效授权。
     * 用户临时排队的一次性任务（owner q:）不走预授权，返回 {@link #ADHOC}：运行时按当次对话的门禁确认。
     */
    public TaskGrant begin(JobStore.Run r, long now) {
        if (r.owner.startsWith("q:")) {
            apply(r, JobMachine.Event.START, now);
            return ADHOC;
        }
        TaskGrant g = validGrant(r.owner);
        if (g == null) {
            r.waitReason = WAIT_REAUTH;
            apply(r, JobMachine.Event.NEED_CONFIRM, now);
            return null;
        }
        apply(r, JobMachine.Event.START, now);
        return g;
    }

    /** 一次性排队任务的占位授权（{@link PermissionGuard} 不用它：这类任务走当次对话的 ExecutionGate）。 */
    public static final TaskGrant ADHOC = TaskGrant.grant("adhoc", java.util.EnumSet.noneOf(Perm.class), null, 0L);

    /** 运行时越权 → 停在等确认（不执行那一步）。 */
    public void blocked(JobStore.Run r, PermissionGuard.Decision d, long now) {
        r.waitReason = (d.verdict == PermissionGuard.Verdict.DENY_NO_GRANT ? WAIT_REAUTH : d.verdict == PermissionGuard.Verdict.CONFIRM_EACH_TIME
                ? WAIT_CONFIRM : WAIT_UNGRANTED) + "：" + (d.perm == null ? "" : d.perm.label);
        apply(r, JobMachine.Event.NEED_CONFIRM, now);
    }

    /** 执行结束：成功 → 完成 + 写 outbox（幂等）；失败 → 待重试 / 失败。 */
    public JobStore.Outbox finish(JobStore.Run r, boolean ok, String text, String urgency, boolean speak, long now) {
        if (!ok) {
            r.error = text == null ? "" : text;
            apply(r, JobMachine.Event.FAIL, now);
            return null;
        }
        r.result = text == null ? "" : text;
        apply(r, JobMachine.Event.SUCCEED, now);
        JobStore.Outbox o = new JobStore.Outbox();
        o.runId = r.id;
        o.idem = "run:" + r.id + ":result";
        o.title = r.title;
        o.text = r.result;
        o.urgency = urgency == null ? "NORMAL" : urgency;
        o.wantSpeak = speak;
        o.deliverAfter = r.plannedAt > 0 ? r.plannedAt : now;
        o.expireAt = r.expireAt;
        o.createdAt = now;
        o.digestKey = r.owner;
        o.id = mStore.addOutbox(o);
        return o;
    }

    // ================================================================== 送达

    /** 到了送达时刻的 outbox；顺手把过期的标成 EXPIRED（不播）。 */
    public List<JobStore.Outbox> dueOutbox(long now) {
        List<JobStore.Outbox> due = new ArrayList<JobStore.Outbox>();
        for (JobStore.Outbox o : mStore.outbox()) {
            if (o.status != JobStore.Outbox.PENDING) continue;
            if (o.expireAt > 0 && now > o.expireAt) {
                o.status = JobStore.Outbox.EXPIRED;
                mStore.updateOutbox(o);
                continue;
            }
            if (now >= o.deliverAfter) due.add(o);
        }
        return due;
    }

    public void delivered(JobStore.Outbox o, String via, long now) {
        o.status = JobStore.Outbox.DELIVERED;
        o.deliveredVia = via == null ? "" : via;
        o.deliveredAt = now;
        mStore.updateOutbox(o);
    }

    /** 下一次需要醒来的时刻（-1 = 没有）：任务的「送达 − 提前量」、待重试、未送达的 outbox。 */
    public long earliestNext(long now) {
        long best = -1L;
        for (JobStore.Job j : mStore.jobs()) {
            if (!j.enabled || j.nextDeliverAt <= 0) continue;
            best = min(best, Math.max(now, j.nextDeliverAt - Math.max(0L, j.leadMs)));
        }
        for (JobStore.Run r : mStore.runs()) {
            if (r.state == JobMachine.State.RETRY_WAIT) best = min(best, r.nextRetryAt);
            else if (r.state == JobMachine.State.PENDING && r.owner.startsWith(WatchBridge.OWNER_PREFIX)) best = min(best, Math.max(now, r.plannedAt));   // B 的 P3 监控：按计划时刻
            else if (r.state == JobMachine.State.PENDING && !r.owner.startsWith("q:")) best = min(best, now);   // 队列项由队列自己泵
        }
        for (JobStore.Outbox o : mStore.outbox()) {
            if (o.status == JobStore.Outbox.PENDING && o.deliverAfter > now) best = min(best, o.deliverAfter);
        }
        return best;
    }

    private static long min(long a, long b) {
        if (a <= 0) return b;
        if (b <= 0) return a;
        return Math.min(a, b);
    }

    public static long parseIdPublic(String owner) {
        return parseId(owner);
    }

    static long parseId(String owner) {
        int i = owner.indexOf(':');
        try {
            return Long.parseLong(owner.substring(i + 1));
        } catch (Exception e) {
            return -1L;
        }
    }

    // ================================================================== 可靠性统计（控制台自检卡）

    /** 最近 days 天：准点（送达时刻 ±2 分钟内送出）/ 晚到 / 错过或过期。 */
    public int[] reliability(long now, int days) {
        long since = now - days * 86400000L;
        int onTime = 0, late = 0, missed = 0;
        for (JobStore.Outbox o : mStore.outbox()) {
            if (o.createdAt < since) continue;
            if (o.status == JobStore.Outbox.DELIVERED) {
                if (o.deliveredAt - o.deliverAfter <= 2L * 60L * 1000L) onTime++;
                else late++;
            } else if (o.status == JobStore.Outbox.EXPIRED) missed++;
        }
        for (JobStore.Run r : mStore.runs()) {
            if (r.createdAt >= since && r.state == JobMachine.State.FAILED && r.error.startsWith("错过")) missed++;
        }
        return new int[]{onTime, late, missed};
    }
}
