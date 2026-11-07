package com.onesay.assistant.v2.jobs;

import java.util.ArrayList;
import java.util.List;

/**
 * A↔B 合并：B 线 P3 监控（conn/watch）的「什么时候跑」「怎么送达」接到 ③ 的 jobs.db。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>授权键 / Run owner = {@code watch:<id>}；规格描述成 {@code Job{templateId = "watch.<id>#<查询词摘要>", command = ""}}——
 *       查询词<b>不放 command</b>（会被静态分析，「删除」之类的词会被误判成红卡）；{@link PermissionAnalyzer#forTemplate} 认 {@code watch.} → 只有 WEB_SEARCH。</li>
 *   <li>{@link #schedule}：run 表一条 PENDING（maxAttempts = 1，plannedAt = at）；同 id 已有待执行的 = 改期。
 *       {@link JobsEngine#earliestNext} 对 {@code watch:} 按 plannedAt 算（不是「立即」）。</li>
 *   <li>{@link #cancel}：这个 id 没结束的 Run 一律 CANCEL。</li>
 *   <li>{@link #due}：到点的待执行 Run（执行器：S3Runner.execWatch → JobsEngine.begin 查授权 → WatchService.runOne）。</li>
 *   <li>{@link #onChange} / {@link #onBroken}：写 outbox（幂等键去重），送达窗口 / 合并 / 免打扰 / 回调历史沿用 ⑤ 的 CallbackHub。</li>
 * </ul>
 */
public final class WatchBridge {

    public static final String OWNER_PREFIX = "watch:";
    public static final String TEMPLATE_PREFIX = "watch.";
    /** 结果 outbox 的有效期（过了不播）。 */
    public static final long OUTBOX_TTL_MS = 12L * 3600L * 1000L;

    private final JobsEngine mEngine;

    public WatchBridge(JobsEngine e) {
        mEngine = e;
    }

    public static String owner(String watchId) {
        return OWNER_PREFIX + watchId;
    }

    public static String idOf(String owner) {
        return owner != null && owner.startsWith(OWNER_PREFIX) ? owner.substring(OWNER_PREFIX.length()) : null;
    }

    /** 授权用的规格（标题 / 查询词摘要只进 templateId，command 永远为空）。 */
    public static JobStore.Job asJob(String watchId, String title, String query) {
        JobStore.Job j = new JobStore.Job();
        j.title = "监控：" + (title == null || title.length() == 0 ? watchId : title);
        j.templateId = TEMPLATE_PREFIX + watchId + "#" + digest(query);
        j.command = "";
        j.speak = false;
        return j;
    }

    static String digest(String q) {
        String s = q == null ? "" : q.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    static boolean open(JobStore.Run r) {
        return r.state == JobMachine.State.PENDING || r.state == JobMachine.State.RETRY_WAIT || r.state == JobMachine.State.WAITING_CONFIRM;
    }

    /** 安排 / 改期。@return 这条 Run */
    public synchronized JobStore.Run schedule(String watchId, String title, long atMillis, long now) {
        String owner = owner(watchId);
        JobStore store = mEngine.store();
        for (JobStore.Run r : store.runs()) {
            if (r.owner.equals(owner) && r.state == JobMachine.State.PENDING) {
                r.plannedAt = atMillis;
                r.updatedAt = now;
                store.updateRun(r);
                return r;
            }
        }
        JobStore.Run r = new JobStore.Run();
        r.owner = owner;
        r.title = "监控：" + (title == null || title.length() == 0 ? watchId : title);
        r.templateId = TEMPLATE_PREFIX + watchId;
        r.command = "";
        r.plannedAt = atMillis;
        r.maxAttempts = 1;
        r.createdAt = now;
        r.updatedAt = now;
        r.state = JobMachine.State.PENDING;
        store.addRun(r);
        return r;
    }

    public synchronized int cancel(String watchId, long now) {
        String owner = owner(watchId);
        int n = 0;
        for (JobStore.Run r : mEngine.store().runs()) {
            if (r.owner.equals(owner) && open(r) && mEngine.apply(r, JobMachine.Event.CANCEL, now)) n++;
        }
        return n;
    }

    /** 到点的待执行监控 Run。 */
    public synchronized List<JobStore.Run> due(long now) {
        List<JobStore.Run> l = new ArrayList<JobStore.Run>();
        for (JobStore.Run r : mEngine.store().runs()) {
            if (r.owner.startsWith(OWNER_PREFIX) && r.state == JobMachine.State.PENDING && r.plannedAt <= now) l.add(r);
        }
        return l;
    }

    /** 执行结束：只改状态，不写结果 outbox（有变化时 B 会调 {@link #onChange}，那才是要送达的内容）。 */
    public void finish(JobStore.Run r, boolean ok, String error, long now) {
        if (!ok) r.error = error == null ? "" : error;
        mEngine.apply(r, ok ? JobMachine.Event.SUCCEED : JobMachine.Event.FAIL, now);
    }

    /** 有变化 → outbox（同一组结果链接只写一次）。message 只有标题 + 来源域名。 */
    public JobStore.Outbox onChange(String watchId, String title, List<String> urls, String message, long now) {
        StringBuilder key = new StringBuilder();
        if (urls != null) for (String u : urls) key.append(u).append('\n');
        JobStore.Outbox o = new JobStore.Outbox();
        o.idem = "watch:" + watchId + ":" + Integer.toHexString(key.toString().hashCode());
        o.title = "监控：" + (title == null || title.length() == 0 ? watchId : title);
        o.text = message == null ? "" : message;
        o.urgency = "NORMAL";
        o.wantSpeak = false;   // items / 标题不进 TTS（外部数据）
        o.digestKey = "watch:" + watchId;
        o.worth = 1;
        o.deliverAfter = now;
        o.expireAt = now + OUTBOX_TTL_MS;
        o.createdAt = now;
        o.id = mEngine.store().addOutbox(o);
        return o;
    }

    /** 连续失败 → 低优先级一条「查不了了」（同一监控一天最多一条）。 */
    public JobStore.Outbox onBroken(String watchId, String title, String error, String day, long now) {
        JobStore.Outbox o = new JobStore.Outbox();
        o.idem = "watch:" + watchId + ":broken:" + day;
        o.title = "监控：" + (title == null || title.length() == 0 ? watchId : title);
        String e = error == null ? "" : error.replaceAll("[\\p{Cntrl}]+", " ").trim();
        o.text = "这个监控查不了了：" + (e.length() > 60 ? e.substring(0, 60) + "…" : e);
        o.urgency = "LOW";
        o.wantSpeak = false;
        o.digestKey = "watch:" + watchId;
        o.worth = 1;
        o.deliverAfter = now;
        o.expireAt = now + 24L * 3600L * 1000L;
        o.createdAt = now;
        o.id = mEngine.store().addOutbox(o);
        return o;
    }
}
