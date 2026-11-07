package com.onesay.assistant.v2.jobs;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限卡（lane-a 第 3 项，用户要求）：创建 / 修改定时或事件任务时，先把静态分析出的权限清单做成卡片逐项勾选，<b>确认后才入库</b>。
 *
 * <p>待确认的卡只在内存里（没确认 = 没入库，进程被杀就当用户没确认）。卡片 10 分钟没人处理自动作废。
 * 发出邮件 / 付款 / 红卡在卡上只显示「每次执行时单独确认」，不能勾（{@link TaskGrant#grant} 也会剔掉）。
 */
public final class PermissionCards {

    private PermissionCards() {}

    private static final String TAG = "PermCard";
    public static final long TTL_MS = 10L * 60L * 1000L;

    public enum Kind { NEW_JOB, NEW_S3, REAUTH, NEW_EVENT }

    /** 一张待确认的卡。 */
    public static final class Card {
        public final String token;
        public final Kind kind;
        public final String title;
        public final String desc;
        public final Set<Perm> required;
        public final long createdAt;
        /** NEW_JOB 的待入库任务。 */
        JobsEngine.Pending pending;
        /** NEW_S3 的 s3.task.add 参数 + 签名。 */
        org.json.JSONObject s3Args;
        String s3Sign;
        /** REAUTH 的授权键。 */
        String owner;
        /** NEW_EVENT（第 7 项）的待入库事件任务。 */
        com.onesay.assistant.v2.events.EventEngine.Pending event;

        Card(String token, Kind kind, String title, String desc, Set<Perm> required, long now) {
            this.token = token;
            this.kind = kind;
            this.title = title;
            this.desc = desc;
            this.required = required;
            this.createdAt = now;
        }

        public Set<Perm> grantable() {
            Set<Perm> s = EnumSet.noneOf(Perm.class);
            for (Perm p : required) if (p.preGrantable) s.add(p);
            return s;
        }

        public Set<Perm> perRun() {
            return TaskGrant.perRun(required);
        }
    }

    static final Map<String, Card> PENDING = new ConcurrentHashMap<String, Card>();

    static String newToken() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static Card card(String token) {
        Card c = token == null ? null : PENDING.get(token);
        if (c != null && System.currentTimeMillis() - c.createdAt > TTL_MS) {
            PENDING.remove(token);
            return null;
        }
        return c;
    }

    // ------------------------------------------------------------------ 发起

    public static Card forNewJob(JobsEngine.Pending p, long now) {
        Card c = new Card(newToken(), Kind.NEW_JOB, p.job.title.length() > 0 ? p.job.title : "定时任务",
                describe(p.job), p.required, now);
        c.pending = p;
        PENDING.put(c.token, c);
        return c;
    }

    /** 第 7 项：新建事件任务（同样先过权限卡，确认后才入库）。 */
    public static Card forNewEvent(com.onesay.assistant.v2.events.EventEngine.Pending p, long now) {
        Card c = new Card(newToken(), Kind.NEW_EVENT, p.task.title.length() > 0 ? p.task.title : "事件任务",
                p.task.describeTrigger() + "：" + p.task.command, p.required, now);
        c.event = p;
        PENDING.put(c.token, c);
        return c;
    }

    public static Card forNewS3(String sign, org.json.JSONObject args, long now) {
        JobStore.Job j = JobsHooks.s3ArgsAsJob(args);
        Card c = new Card(newToken(), Kind.NEW_S3, j.command, describe(j), JobsEngine.analyze(j), now);
        c.s3Args = args;
        c.s3Sign = sign;
        PENDING.put(c.token, c);
        return c;
    }

    public static Card forReauth(JobsEngine e, String owner, long now) {
        JobStore.Job j = e.describe(owner);
        if (j == null) return null;
        Card c = new Card(newToken(), Kind.REAUTH, j.title.length() > 0 ? j.title : j.command, "任务改过或是旧任务，需要重新授权。" + describe(j),
                JobsEngine.analyze(j), now);
        c.owner = owner;
        PENDING.put(c.token, c);
        return c;
    }

    static String describe(JobStore.Job j) {
        if (j.templateId.startsWith(com.onesay.assistant.v2.events.EventTask.TPL_PREFIX)) return j.title + "：" + j.command;   // 事件任务没有时刻
        String when = com.onesay.assistant.v2.s3.S3Schedule.describe(j.repeat, j.hour, j.minute, j.weekMask, j.onceAt, System.currentTimeMillis(),
                java.util.TimeZone.getDefault());
        String what = j.templateId.length() > 0 ? j.title : j.command;
        return when + "：" + what;
    }

    /** 打开卡片界面。 */
    public static void show(Context ctx, Card c) {
        try {
            Intent it = new Intent(ctx, com.onesay.assistant.v2.ui.PermissionCardActivity.class);
            it.putExtra(com.onesay.assistant.v2.ui.PermissionCardActivity.EXTRA_TOKEN, c.token);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[PERM] 打开权限卡失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 结果

    /** 用户点了确认。返回给用户看的一句话。 */
    public static String confirm(Context ctx, String token, Set<Perm> checked) {
        Card c = card(token);
        if (c == null) return "这张权限卡已过期，请重新创建任务。";
        PENDING.remove(token);
        Context app = ctx.getApplicationContext();
        JobsEngine e = JobsHooks.engine(app);
        long now = System.currentTimeMillis();
        String msg;
        switch (c.kind) {
            case NEW_JOB: {
                long id = e.confirmCreate(c.pending, checked, now);
                msg = id > 0 ? "已创建：" + c.desc : "没建成：时间已过或任务太多（最多 " + JobsEngine.MAX_JOBS + " 个）";
                break;
            }
            case NEW_EVENT: {
                long id = com.onesay.assistant.v2.events.EventHooks.engine(app).confirmCreate(c.event, checked, now);
                msg = id > 0 ? "已创建事件任务：" + c.desc : "没建成：事件任务太多（最多 " + com.onesay.assistant.v2.events.EventEngine.MAX_TASKS + " 个）或配置不对";
                break;
            }
            case NEW_S3: {
                com.onesay.assistant.v2.s3.S3Engine s3 = com.onesay.assistant.v2.s3.S3Hooks.engine(app);
                com.onesay.assistant.v2.s3.S3Engine.Exec x = s3.execute(c.s3Sign, c.s3Args, now);
                if (x.ok) {
                    e.grantExternal(JobsHooks.s3Key(s3.lastAddedTaskId()), checked, now);
                    msg = x.message;
                } else {
                    msg = x.message;
                }
                break;
            }
            default: {
                if (c.owner.startsWith("job:")) e.reauthorize(JobsEngine.parseId(c.owner), checked, now);
                else e.grantExternal(c.owner, checked, now);
                msg = "已重新授权：" + c.title;
            }
        }
        Log.i(TAG, "[PERM] 确认 " + c.kind + " 勾选=" + TaskGrant.encode(checked) + " 清单=" + TaskGrant.encode(c.required));
        com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "perm-card");
        return msg;
    }

    public static void cancel(String token) {
        Card c = token == null ? null : PENDING.remove(token);
        if (c != null) Log.i(TAG, "[PERM] 取消 " + c.kind + "：没有入库");
    }
}
