package com.onesay.assistant.v2.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * lane-a 定时任务 / 队列 / 回调的持久化接口（生产 {@link SqliteJobStore}：独立库 jobs.db；JVM 单测 {@link Mem}）。
 *
 * <ul>
 *   <li>{@link Job}：一条定时任务定义（模板任务 / 播报类），自带「提前生成」的提前量；</li>
 *   <li>{@link Run}：一次执行（状态机 {@link JobMachine}；队列也是 Run，见 {@link JobQueue}）；</li>
 *   <li>{@link Outbox}：待送达的回调（先写再发，带幂等键 → 重启不重播、不漏播）；</li>
 *   <li>授权：按任务键（{@code job:<id>} / {@code s3:<id>} / {@code ev:<id>}）存 {@link TaskGrant}。</li>
 * </ul>
 */
public interface JobStore {

    final class Job {
        public long id;
        public String title = "";
        /** 模板 id（{@link DailyDigest#TEMPLATE_ID} 等）；空 = AGENT 指令任务。 */
        public String templateId = "";
        public String command = "";
        public int repeat;
        public int weekMask;
        public int hour, minute;
        public long onceAt;
        /** 提前生成量（S3 预生成）。 */
        public long leadMs;
        /** 结果是否要播报（TTS）。 */
        public boolean speak = true;
        public boolean enabled = true;
        /** 下一次「送达」时刻（-1 = 没有）。 */
        public long nextDeliverAt = -1L;
        public long createdAt;

        /** 规格（参与授权指纹：任务改了 → 指纹变 → 重新授权）。 */
        public String spec() {
            return "tpl=" + templateId + "|cmd=" + command.trim() + "|r=" + repeat + "|w=" + weekMask + "|t=" + hour + ":" + minute
                    + "|once=" + onceAt + "|speak=" + speak;
        }

        public String grantKey() {
            return "job:" + id;
        }

        public Job copy() {
            Job j = new Job();
            j.id = id; j.title = title; j.templateId = templateId; j.command = command; j.repeat = repeat; j.weekMask = weekMask;
            j.hour = hour; j.minute = minute; j.onceAt = onceAt; j.leadMs = leadMs; j.speak = speak; j.enabled = enabled;
            j.nextDeliverAt = nextDeliverAt; j.createdAt = createdAt;
            return j;
        }
    }

    final class Run {
        public long id;
        /** 来源键：job:&lt;id&gt; / s3:&lt;id&gt; / q:&lt;用户原话摘要&gt; / ev:&lt;id&gt;。 */
        public String owner = "";
        public String title = "";
        public String templateId = "";
        public String command = "";
        public JobMachine.State state = JobMachine.State.PENDING;
        public int attempts;
        public int maxAttempts = JobMachine.DEFAULT_MAX_ATTEMPTS;
        /** 队列优先级（大的先跑）；插队 = 提到当前最大 + 1。 */
        public int priority;
        /** 入队顺序（同优先级先来先跑）。 */
        public long seq;
        /** 计划送达时刻（定时任务）；0 = 尽快。 */
        public long plannedAt;
        /** 过了这个时刻结果作废（不播）。0 = 不过期。 */
        public long expireAt;
        public long nextRetryAt;
        public String waitReason = "";
        public String result = "";
        public String error = "";
        /** 进度（队列在岛上显示）：0–100，-1 = 未知。 */
        public int progress = -1;
        public String step = "";
        public long createdAt;
        public long updatedAt;

        public Run copy() {
            Run r = new Run();
            r.id = id; r.owner = owner; r.title = title; r.templateId = templateId; r.command = command; r.state = state;
            r.attempts = attempts; r.maxAttempts = maxAttempts; r.priority = priority; r.seq = seq; r.plannedAt = plannedAt;
            r.expireAt = expireAt; r.nextRetryAt = nextRetryAt; r.waitReason = waitReason; r.result = result; r.error = error;
            r.progress = progress; r.step = step; r.createdAt = createdAt; r.updatedAt = updatedAt;
            return r;
        }

        @Override public String toString() {
            // 快捷指令的队列项 owner =「q:执行快捷指令#id（名字）」，日志里换成名字（ShortcutModel.logText）
            return "run#" + id + "[" + com.onesay.assistant.v2.shortcut.ShortcutModel.logText(owner) + " " + state + " a=" + attempts + "/" + maxAttempts + "]";
        }
    }

    final class Outbox {
        public static final int PENDING = 0, DELIVERED = 1, EXPIRED = 2, SUPPRESSED = 3;
        public long id;
        public long runId;
        /** 幂等键（run + 用途），重复写入返回已有那条。 */
        public String idem = "";
        public String title = "";
        public String text = "";
        /** {@link com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency#name()}。 */
        public String urgency = "NORMAL";
        public boolean wantSpeak;
        /** 汇总键：同一送达窗口里同键的合并成一条。 */
        public String digestKey = "";
        public long deliverAfter;
        public long expireAt;
        public int status = PENDING;
        public String deliveredVia = "";
        public long deliveredAt;
        public long createdAt;
        /** worth_telling 判断的结论（第 5 项）：1 值得 / 0 不值得 / -1 未判。 */
        public int worth = -1;

        public Outbox copy() {
            Outbox o = new Outbox();
            o.id = id; o.runId = runId; o.idem = idem; o.title = title; o.text = text; o.urgency = urgency; o.wantSpeak = wantSpeak;
            o.digestKey = digestKey; o.deliverAfter = deliverAfter; o.expireAt = expireAt; o.status = status;
            o.deliveredVia = deliveredVia; o.deliveredAt = deliveredAt; o.createdAt = createdAt; o.worth = worth;
            return o;
        }
    }

    // ---- job
    long addJob(Job j);
    void updateJob(Job j);
    boolean deleteJob(long id);
    Job job(long id);
    List<Job> jobs();

    // ---- run
    long addRun(Run r);
    void updateRun(Run r);
    Run run(long id);
    List<Run> runs();
    /** 某个来源在某个计划时刻是否已经建过 Run（防重复生成）。 */
    Run runFor(String owner, long plannedAt);

    // ---- outbox
    /** 写入；幂等键已存在 → 返回已有那条的 id，不重复写。 */
    long addOutbox(Outbox o);
    void updateOutbox(Outbox o);
    List<Outbox> outbox();

    // ---- grant
    void saveGrant(String key, TaskGrant g);
    TaskGrant grant(String key);
    void deleteGrant(String key);

    void clear();

    Comparator<Run> QUEUE_ORDER = new Comparator<Run>() {
        @Override public int compare(Run a, Run b) {
            if (a.priority != b.priority) return a.priority > b.priority ? -1 : 1;
            if (a.seq != b.seq) return a.seq < b.seq ? -1 : 1;
            return a.id < b.id ? -1 : a.id == b.id ? 0 : 1;
        }
    };

    /** 内存实现（JVM 单测）。 */
    final class Mem implements JobStore {
        private final Map<Long, Job> mJobs = new LinkedHashMap<Long, Job>();
        private final Map<Long, Run> mRuns = new LinkedHashMap<Long, Run>();
        private final Map<Long, Outbox> mOut = new LinkedHashMap<Long, Outbox>();
        private final Map<String, TaskGrant> mGrants = new LinkedHashMap<String, TaskGrant>();
        private long mSeq;

        @Override public synchronized long addJob(Job j) { j.id = ++mSeq; mJobs.put(j.id, j.copy()); return j.id; }
        @Override public synchronized void updateJob(Job j) { if (mJobs.containsKey(j.id)) mJobs.put(j.id, j.copy()); }
        @Override public synchronized boolean deleteJob(long id) { return mJobs.remove(id) != null; }
        @Override public synchronized Job job(long id) { Job j = mJobs.get(id); return j == null ? null : j.copy(); }
        @Override public synchronized List<Job> jobs() {
            List<Job> l = new ArrayList<Job>();
            for (Job j : mJobs.values()) l.add(j.copy());
            return l;
        }

        @Override public synchronized long addRun(Run r) { r.id = ++mSeq; if (r.seq == 0) r.seq = r.id; mRuns.put(r.id, r.copy()); return r.id; }
        @Override public synchronized void updateRun(Run r) { if (mRuns.containsKey(r.id)) mRuns.put(r.id, r.copy()); }
        @Override public synchronized Run run(long id) { Run r = mRuns.get(id); return r == null ? null : r.copy(); }
        @Override public synchronized List<Run> runs() {
            List<Run> l = new ArrayList<Run>();
            for (Run r : mRuns.values()) l.add(r.copy());
            return l;
        }
        @Override public synchronized Run runFor(String owner, long plannedAt) {
            for (Run r : mRuns.values()) if (r.owner.equals(owner) && r.plannedAt == plannedAt) return r.copy();
            return null;
        }

        @Override public synchronized long addOutbox(Outbox o) {
            for (Outbox x : mOut.values()) if (x.idem.equals(o.idem) && o.idem.length() > 0) return x.id;
            o.id = ++mSeq;
            mOut.put(o.id, o.copy());
            return o.id;
        }
        @Override public synchronized void updateOutbox(Outbox o) { if (mOut.containsKey(o.id)) mOut.put(o.id, o.copy()); }
        @Override public synchronized List<Outbox> outbox() {
            List<Outbox> l = new ArrayList<Outbox>();
            for (Outbox o : mOut.values()) l.add(o.copy());
            return l;
        }

        @Override public synchronized void saveGrant(String key, TaskGrant g) { mGrants.put(key, g); }
        @Override public synchronized TaskGrant grant(String key) { return mGrants.get(key); }
        @Override public synchronized void deleteGrant(String key) { mGrants.remove(key); }

        @Override public synchronized void clear() {
            mJobs.clear(); mRuns.clear(); mOut.clear(); mGrants.clear();
        }
    }

    /** 小工具：按状态过滤并排队序。 */
    final class Util {
        private Util() {}

        public static List<Run> inState(List<Run> all, JobMachine.State... states) {
            List<Run> l = new ArrayList<Run>();
            for (Run r : all) for (JobMachine.State s : states) if (r.state == s) { l.add(r); break; }
            Collections.sort(l, QUEUE_ORDER);
            return l;
        }
    }
}
