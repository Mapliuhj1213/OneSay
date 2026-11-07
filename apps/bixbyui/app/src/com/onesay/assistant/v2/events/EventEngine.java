package com.onesay.assistant.v2.events;

import com.onesay.assistant.v2.jobs.JobMachine;
import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.TaskGrant;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * lane-a 第 7 项 P1 事件触发的引擎（纯 Java，JVM 可测；Android 一侧 {@link EventHooks} 只负责收广播 / 通知、执行）。
 *
 * <h3>复用第 3 项</h3>
 * 事件任务的每次触发是 jobs.db 里一条 {@link JobStore.Run}（owner {@code ev:<id>}），走同一个 {@link JobMachine} 状态机和
 * {@code S3Runner.execRun}：开始前查授权（没有 / 任务改过 → 等确认），运行时每一步过 {@link com.onesay.assistant.v2.jobs.PermissionGuard}
 * （越权 → 不执行，停在等确认并上岛）。授权存 jobs.db 授权表，键 {@code ev:<id>}，创建时先过权限卡（{@link #prepare} 不写库，
 * {@link #confirmCreate} 才入库）。
 *
 * <h3>防护（按顺序）</h3>
 * <ol>
 *   <li>状态去重：同一源同一对象的同一状态重复到来（粘性广播、Wi‑Fi 状态反复刷新）不算新事件；{@link #baseline} 只记基线不触发；</li>
 *   <li>任务关闭 / 已自动暂停 → 跳过；</li>
 *   <li>条件：源、连上 / 断开、目标（SSID / 蓝牙）、通知白名单 App + 白名单关键词；</li>
 *   <li>时段守卫；</li>
 *   <li>防抖：距上次触发不足 {@link EventTask#debounceMs}；</li>
 *   <li>每天上限 {@link EventTask#dailyCap}（按本机时区自然日）；</li>
 *   <li>这条任务上一次触发还没结束（待执行 / 执行中 / 等确认）→ 不叠加；</li>
 * </ol>
 * 执行失败（含被杀后重试用完）累计 {@link EventTask#failures}，连续 {@link EventTask#maxFailures} 次自动暂停；成功一次清零；
 * 被拦下等确认 / 用户取消不算失败。事件任务每次触发只跑一次（maxAttempts = 1）：事件是即时的，晚一小时重跑没有意义。
 */
public final class EventEngine implements JobsEngine.Resolver {

    /** 事件 Run 的 templateId 标记（通知触发 = 污染数据 → 整个任务按 tainted 过闸）。 */
    public static final String RUN_TPL = "event";
    public static final String RUN_TPL_TAINTED = "event.notification";
    public static final int MAX_TASKS = 20;

    private final EventStore mStore;
    private final JobsEngine mJobs;
    private final TimeZone mTz;
    /** 状态去重：源|对象 → 最近一次状态。 */
    private final Map<String, Boolean> mLast = new HashMap<String, Boolean>();
    /**
     * 2026-10-23 debug 上机测试用：临时覆盖<b>所有</b>事件任务的防抖窗口 / 每天上限（&lt; 0 = 不覆盖，用任务自己的值）。
     * 只在内存里（进程重启即失效），不写库、不改任务、不进授权指纹。只有 {@code EventHooks.debugGuards} 会设置它，
     * 那里先查 {@code BuildVariant.IS_DEBUG}（release 直接返回）；调用方 EventTestDriver 在 test 包，release 不编译。
     */
    private long mDebugDebounceMs = -1L;
    private int mDebugDailyCap = -1;

    public EventEngine(EventStore store, JobsEngine jobs, TimeZone tz) {
        mStore = store;
        mJobs = jobs;
        mTz = tz == null ? TimeZone.getDefault() : tz;
    }

    public EventStore store() {
        return mStore;
    }

    public JobsEngine jobs() {
        return mJobs;
    }

    // ================================================================== 创建 / 修改（权限卡）

    public static final class Pending {
        public final EventTask task;
        public final Set<Perm> required;

        Pending(EventTask task, Set<Perm> required) {
            this.task = task;
            this.required = required;
        }
    }

    /** 权限清单：源对应的读权限 + 用户指令的静态分析（只看用户写的，不看事件内容）。 */
    public static Set<Perm> analyze(EventTask t) {
        return JobsEngine.analyze(t.asJob());
    }

    /** 校验 + 静态分析；不写库。配置不合法返回 null（{@link #invalidReason} 说原因）。 */
    public Pending prepare(EventTask t) {
        if (invalidReason(t) != null) return null;
        EventTask c = t.copy();
        return new Pending(c, analyze(c));
    }

    public static String invalidReason(EventTask t) {
        if (t == null) return "没有任务";
        if (t.command == null || t.command.trim().length() == 0) return "要做的事不能为空";
        if (t.source == EventTask.Source.NOTIFICATION) {
            if (t.apps.isEmpty()) return "通知触发要先填白名单 App（包名）";
            if (t.keywords.isEmpty()) return "通知触发要先填白名单关键词";
            if (!t.onConnect) return "通知只能「收到时」触发";
        }
        if (t.dailyCap < 1 || t.dailyCap > 50) return "每天上限要在 1–50 之间";
        if (t.maxFailures < 1 || t.maxFailures > 10) return "连续失败暂停次数要在 1–10 之间";
        if (t.debounceMs < 0) return "防抖时间不能为负";
        if ((t.windowStart < 0) != (t.windowEnd < 0) || t.windowStart >= 24 * 60 || t.windowEnd >= 24 * 60) return "时段设置不对";
        return null;
    }

    /** 权限卡确认 → 入库 + 存授权。返回任务 id；太多 / 不合法返回 -1。 */
    public long confirmCreate(Pending p, Set<Perm> checked, long now) {
        if (p == null || invalidReason(p.task) != null) return -1;
        if (mStore.all().size() >= MAX_TASKS) return -1;
        EventTask t = p.task.copy();
        t.createdAt = now;
        t.paused = false;
        t.failures = 0;
        long id = mStore.add(t);
        t.id = id;
        JobStore.Job j = t.asJob();
        mJobs.store().saveGrant(t.grantKey(), TaskGrant.grant(j.spec(), JobsEngine.analyze(j), checked, now));
        return id;
    }

    /** 改任务。返回是否需要重新授权（条件 / 指令变了 → 指纹对不上 → 之后的触发停在等确认，直到权限卡重新确认）。 */
    public boolean edit(EventTask t) {
        mStore.update(t);
        TaskGrant g = mJobs.store().grant(t.grantKey());
        JobStore.Job j = t.asJob();
        return g == null || g.needsReauth(j.spec(), JobsEngine.analyze(j));
    }

    public void delete(long id) {
        EventTask t = mStore.get(id);
        if (t == null) return;
        mStore.delete(id);
        mJobs.store().deleteGrant(t.grantKey());
    }

    /** 手动恢复（自动暂停后）：清零失败计数。 */
    public boolean resume(long id) {
        EventTask t = mStore.get(id);
        if (t == null) return false;
        t.paused = false;
        t.pausedReason = "";
        t.failures = 0;
        mStore.update(t);
        return true;
    }

    public void setEnabled(long id, boolean on) {
        EventTask t = mStore.get(id);
        if (t == null) return;
        t.enabled = on;
        mStore.update(t);
    }

    /** {@link JobsEngine.Resolver}：ev:&lt;id&gt; → 授权用的任务描述。 */
    @Override public JobStore.Job describe(String owner) {
        if (owner == null || !owner.startsWith("ev:")) return null;
        EventTask t = mStore.get(JobsEngine.parseIdPublic(owner));
        return t == null ? null : t.asJob();
    }

    // ================================================================== 事件

    /** 跳过原因（单测 / 日志用；不含通知内容）。 */
    public static final class Outcome {
        public final List<JobStore.Run> runs = new ArrayList<JobStore.Run>();
        public final List<String> skipped = new ArrayList<String>();

        @Override public String toString() {
            return "runs=" + runs.size() + " skipped=" + skipped;
        }
    }

    // ================================================================== debug 上机测试（调用方只在 debug 包）

    /** 临时覆盖防抖 / 每天上限（&lt; 0 = 不覆盖）。见字段说明。 */
    public synchronized void setDebugGuards(long debounceMs, int dailyCap) {
        mDebugDebounceMs = debounceMs;
        mDebugDailyCap = dailyCap;
    }

    public synchronized String debugGuards() {
        return mDebugDebounceMs < 0 && mDebugDailyCap < 0 ? "无覆盖"
                : "防抖=" + (mDebugDebounceMs < 0 ? "任务自己的" : (mDebugDebounceMs / 1000L) + "s") + " 每天上限=" + (mDebugDailyCap < 0 ? "任务自己的" : String.valueOf(mDebugDailyCap));
    }

    /** 这条任务此刻生效的防抖 / 每天上限（有 debug 覆盖就用覆盖值）。 */
    public synchronized long effectiveDebounceMs(EventTask t) {
        return mDebugDebounceMs >= 0 ? mDebugDebounceMs : t.debounceMs;
    }

    public synchronized int effectiveDailyCap(EventTask t) {
        return mDebugDailyCap >= 0 ? mDebugDailyCap : t.dailyCap;
    }

    /** 模拟进程重启：忘掉状态去重的记忆（之后注册广播时的粘性广播重新记基线）。 */
    public synchronized void forgetStates() {
        mLast.clear();
    }

    /** 最近一次记到的状态（null = 没有基线）。 */
    public synchronized Boolean lastState(EventSignal s) {
        return s == null ? null : mLast.get(stateKey(s));
    }

    /** 清掉运行态计数（今日次数 / 上次触发时刻；id &lt;= 0 = 全部）。运行态不进授权指纹，不用重新授权。返回改了几条。 */
    public synchronized int resetCounters(long id) {
        int n = 0;
        for (EventTask t : mStore.all()) {
            if (id > 0 && t.id != id) continue;
            t.firesToday = 0;
            t.lastFireAt = 0;
            t.dayKey = "";
            mStore.update(t);
            n++;
        }
        return n;
    }

    /**
     * 空跑：把这次触发的 Run 直接记成完成（START → SUCCEED），不调模型、不执行任何步骤；同样记连续失败（清零）。
     * 用来在没有 LLM key 时测防抖 / 每天上限（否则 Run 一直待执行 / 失败，会挡住下一次触发或触发自动暂停）。
     */
    public void debugCompleteDry(JobStore.Run r, long now) {
        if (!isEventRun(r)) return;
        mJobs.apply(r, JobMachine.Event.START, now);
        mJobs.apply(r, JobMachine.Event.SUCCEED, now);
        recordOutcome(r);
    }

    /** 粘性广播 / 服务刚起来时读到的当前状态：只记基线，不触发。 */
    public synchronized void baseline(EventSignal s) {
        if (s != null && s.source != EventTask.Source.NOTIFICATION) mLast.put(stateKey(s), s.on);
    }

    static String stateKey(EventSignal s) {
        return s.source.name() + "|" + (s.source == EventTask.Source.BLUETOOTH ? (s.key2.length() > 0 ? s.key2 : s.key) : s.key);
    }

    /** 一次事件 → 符合条件的任务各建一条 Run（待执行）。nonce 由调用方给（单测固定），null = 随机。 */
    public synchronized Outcome onSignal(EventSignal s, long now, String nonce) {
        Outcome out = new Outcome();
        if (s == null) return out;
        if (s.source != EventTask.Source.NOTIFICATION) {
            String k = stateKey(s);
            Boolean last = mLast.get(k);
            mLast.put(k, s.on);
            if (s.source == EventTask.Source.WIFI && s.on) {
                // 连上新 SSID = 其它 SSID 都算断开（只更新基线，断开事件由 Hooks 按上一个 SSID 补发）
                for (Map.Entry<String, Boolean> e : mLast.entrySet()) {
                    if (e.getKey().startsWith("WIFI|") && !e.getKey().equals(k)) e.setValue(Boolean.FALSE);
                }
            }
            if (last != null && last == s.on) {
                out.skipped.add("状态没变");
                return out;
            }
        }
        List<JobStore.Run> existing = mJobs.store().runs();
        for (EventTask t : mStore.all()) {
            String why = check(t, s, now, existing);
            if (why != null) {
                if (!"源不符".equals(why)) out.skipped.add(t.grantKey() + ":" + why);
                continue;
            }
            // 计数（自然日）+ 防抖时刻
            String day = dayKey(now);
            if (!day.equals(t.dayKey)) {
                t.dayKey = day;
                t.firesToday = 0;
            }
            t.firesToday++;
            t.lastFireAt = now;
            mStore.update(t);

            JobStore.Run r = new JobStore.Run();
            r.owner = t.grantKey();
            r.title = t.title.length() > 0 ? t.title : t.describeTrigger();
            r.templateId = s.source == EventTask.Source.NOTIFICATION ? RUN_TPL_TAINTED : RUN_TPL;
            r.command = buildCommand(t, s, nonce);
            r.maxAttempts = 1;
            r.plannedAt = now;
            r.expireAt = 0;
            r.createdAt = now;
            r.updatedAt = now;
            r.state = JobMachine.State.PENDING;
            r.step = t.describeTrigger();
            mJobs.store().addRun(r);
            out.runs.add(r);
        }
        return out;
    }

    /** null = 触发；否则跳过原因。 */
    String check(EventTask t, EventSignal s, long now, List<JobStore.Run> existing) {
        if (t.source != s.source) return "源不符";
        if (!t.enabled) return "已关闭";
        if (t.paused) return "已自动暂停";
        if (t.onConnect != s.on) return "方向不符";
        switch (s.source) {
            case WIFI:
                if (t.target.length() > 0 && !t.target.equals(s.key)) return "SSID 不符";
                if (s.key.length() == 0 && t.target.length() > 0) return "SSID 读不到";
                break;
            case BLUETOOTH:
                if (t.target.length() > 0 && !t.target.equalsIgnoreCase(s.key) && !t.target.equalsIgnoreCase(s.key2)) return "设备不符";
                break;
            case NOTIFICATION:
                if (!NotificationQuarantine.matches(t, s.key, s.title, s.text)) return "不在白名单";
                break;
            default:
                break;
        }
        if (!inWindow(t, now)) return "不在时段内";
        if (t.lastFireAt > 0 && now - t.lastFireAt < effectiveDebounceMs(t)) return "防抖";
        if (dayKey(now).equals(t.dayKey) && t.firesToday >= effectiveDailyCap(t)) return "今天次数已满";
        for (JobStore.Run r : existing) {
            if (r.owner.equals(t.grantKey()) && (r.state == JobMachine.State.PENDING || r.state.active())) return "上一次还没结束";
        }
        return null;
    }

    boolean inWindow(EventTask t, long now) {
        if (t.windowStart < 0 || t.windowEnd < 0) return true;
        Calendar c = Calendar.getInstance(mTz);
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (t.windowStart <= t.windowEnd) return m >= t.windowStart && m < t.windowEnd;
        return m >= t.windowStart || m < t.windowEnd;   // 跨午夜
    }

    String dayKey(long now) {
        Calendar c = Calendar.getInstance(mTz);
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 交给 AgentLoop 的指令：用户写的指令在前（这是唯一的「要做的事」），事件描述其次；通知内容只以隔离块附在最后。
     * Wi‑Fi SSID / 蓝牙名也是外部可控的字符串，同样清洗（它们只有和用户配置精确匹配才会触发）。
     */
    static String buildCommand(EventTask t, EventSignal s, String nonce) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.command.trim()).append("\n（事件触发：");
        switch (s.source) {
            case CHARGING: sb.append(s.on ? "刚插上充电" : "刚拔掉充电"); break;
            case WIFI: sb.append(s.on ? "刚连上 Wi‑Fi" : "刚断开 Wi‑Fi").append("「").append(NotificationQuarantine.clean(s.key, 40)).append("」"); break;
            case BLUETOOTH: sb.append(s.on ? "蓝牙设备刚连上" : "蓝牙设备刚断开").append("「")
                    .append(NotificationQuarantine.clean(s.key.length() > 0 ? s.key : s.key2, 40)).append("」"); break;
            default: sb.append("收到一条白名单 App 的通知"); break;
        }
        sb.append("）");
        if (s.source == EventTask.Source.NOTIFICATION) {
            sb.append("\n").append(NotificationQuarantine.wrap(s.appLabel, s.title, s.text, nonce));
        }
        return sb.toString();
    }

    public static boolean isEventRun(JobStore.Run r) {
        return r != null && r.owner.startsWith("ev:");
    }

    /** 通知触发的 Run：提示词里有第三方原文 → 整个任务按 tainted 过闸。 */
    public static boolean isTainted(JobStore.Run r) {
        return r != null && RUN_TPL_TAINTED.equals(r.templateId);
    }

    // ================================================================== 结果 → 连续失败自动暂停

    /**
     * 一条事件 Run 结束后调用：DONE → 失败清零；FAILED → 失败 +1，达到上限自动暂停（返回 true = 刚刚暂停）。
     * 等确认 / 取消 / 待重试不计。
     */
    public synchronized boolean recordOutcome(JobStore.Run r) {
        if (!isEventRun(r)) return false;
        EventTask t = mStore.get(JobsEngine.parseIdPublic(r.owner));
        if (t == null) return false;
        if (r.state == JobMachine.State.DONE) {
            if (t.failures != 0) {
                t.failures = 0;
                mStore.update(t);
            }
            return false;
        }
        if (r.state != JobMachine.State.FAILED) return false;
        t.failures++;
        boolean pausedNow = false;
        if (t.failures >= t.maxFailures && !t.paused) {
            t.paused = true;
            t.pausedReason = "连续失败 " + t.failures + " 次，已自动暂停";
            pausedNow = true;
        }
        mStore.update(t);
        return pausedNow;
    }

    /** 当前事件任务里是否有要读通知的（没有就不必处理任何通知）。 */
    public boolean wantsPackage(String pkg) {
        return NotificationQuarantine.anyWants(mStore.all(), pkg);
    }
}
