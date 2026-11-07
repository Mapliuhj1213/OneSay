package com.onesay.assistant.v2.jobs;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link JobStore} 的 SQLite 实现：独立库 {@link #DB_PROD}（不动 s3_tasks.db 的表结构，避免和 S3 既有数据迁移互相牵连）。
 * 约定同 SqliteS3Store：同名库进程内只建一个 helper，严禁 close()。App 被杀后任务 / Run / outbox / 授权都从这里恢复。
 *
 * <pre>
 * job(id PK, title, template_id, command, repeat, week_mask, hour, minute, once_at, lead_ms, speak, enabled, next_deliver_at, created_at)
 * run(id PK, owner, title, template_id, command, state, attempts, max_attempts, priority, seq, planned_at, expire_at, next_retry_at,
 *     wait_reason, result, error, progress, step, created_at, updated_at)
 * outbox(id PK, run_id, idem UNIQUE, title, text, urgency, want_speak, digest_key, deliver_after, expire_at, status, delivered_via,
 *        delivered_at, created_at, worth)
 * grant_(key PK, spec, required, granted, fingerprint, granted_at)
 * </pre>
 */
public final class SqliteJobStore implements JobStore {

    public static final String DB_PROD = "jobs.db";
    private static final int DB_VERSION = JobsDbSchema.VERSION;
    private static final Map<String, SqliteJobStore> sInstances = new HashMap<String, SqliteJobStore>();

    public static synchronized SqliteJobStore get(Context ctx, String name) {
        SqliteJobStore s = sInstances.get(name);
        if (s == null) {
            s = new SqliteJobStore(ctx.getApplicationContext(), name);
            sInstances.put(name, s);
        }
        return s;
    }

    private final SQLiteOpenHelper mHelper;

    private SqliteJobStore(Context ctx, String name) {
        mHelper = new SQLiteOpenHelper(ctx, name, null, DB_VERSION) {
            @Override public void onCreate(SQLiteDatabase db) {
                JobsDbSchema.create(adapt(db));
            }

            // r2/fixes 6：显式迁移框架（只加不删、逐版本、幂等；出错备份旧表再重建），见 JobsDbSchema
            @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
                boolean rebuilt = JobsDbSchema.upgrade(adapt(db), o, n, System.currentTimeMillis());
                android.util.Log.i("SqliteJobStore", "[JOBS-DB] onUpgrade " + o + "→" + n + (rebuilt ? "（迁移失败，旧表已改名备份后重建）" : ""));
            }

            // 回滚到旧 APK：SQLiteOpenHelper 默认直接抛异常（jobs / events 都打不开）→ 改为保留数据，只补齐
            @Override public void onDowngrade(SQLiteDatabase db, int o, int n) {
                boolean rebuilt = JobsDbSchema.downgrade(adapt(db), o, n, System.currentTimeMillis());
                android.util.Log.w("SqliteJobStore", "[JOBS-DB] onDowngrade " + o + "→" + n + (rebuilt ? "（补不齐，旧表已改名备份后重建）" : "（数据保留）"));
            }
        };
    }

    /** SQLiteDatabase → {@link JobsDbSchema.Db}。 */
    static JobsDbSchema.Db adapt(final SQLiteDatabase db) {
        return new JobsDbSchema.Db() {
            @Override public void exec(String sql) {
                db.execSQL(sql);
            }

            @Override public java.util.Set<String> columns(String table) {
                java.util.Set<String> s = new java.util.LinkedHashSet<String>();
                if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) return s;
                Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
                try {
                    int idx = c.getColumnIndex("name");
                    while (c.moveToNext()) s.add(c.getString(idx < 0 ? 1 : idx));
                } finally {
                    c.close();
                }
                return s;
            }
        };
    }

    private SQLiteDatabase w() {
        return mHelper.getWritableDatabase();
    }

    /** 第 7 项：事件任务表（events.SqliteEventStore）复用同一个库 / 同一个 helper。 */
    public SQLiteDatabase writableDatabase() {
        return w();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ job

    static final String JCOLS = "id, title, template_id, command, repeat, week_mask, hour, minute, once_at, lead_ms, speak, enabled, "
            + "next_deliver_at, created_at";

    private static ContentValues jv(Job j) {
        ContentValues v = new ContentValues();
        v.put("title", j.title); v.put("template_id", j.templateId); v.put("command", j.command); v.put("repeat", j.repeat);
        v.put("week_mask", j.weekMask); v.put("hour", j.hour); v.put("minute", j.minute); v.put("once_at", j.onceAt);
        v.put("lead_ms", j.leadMs); v.put("speak", j.speak ? 1 : 0); v.put("enabled", j.enabled ? 1 : 0);
        v.put("next_deliver_at", j.nextDeliverAt); v.put("created_at", j.createdAt);
        return v;
    }

    private static Job readJob(Cursor c) {
        Job j = new Job();
        j.id = c.getLong(0); j.title = nz(c.getString(1)); j.templateId = nz(c.getString(2)); j.command = nz(c.getString(3));
        j.repeat = c.getInt(4); j.weekMask = c.getInt(5); j.hour = c.getInt(6); j.minute = c.getInt(7); j.onceAt = c.getLong(8);
        j.leadMs = c.getLong(9); j.speak = c.getInt(10) != 0; j.enabled = c.getInt(11) != 0; j.nextDeliverAt = c.getLong(12);
        j.createdAt = c.getLong(13);
        return j;
    }

    @Override public synchronized long addJob(Job j) {
        j.id = w().insert("job", null, jv(j));
        return j.id;
    }

    @Override public synchronized void updateJob(Job j) {
        w().update("job", jv(j), "id=?", new String[]{String.valueOf(j.id)});
    }

    @Override public synchronized boolean deleteJob(long id) {
        return w().delete("job", "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    @Override public synchronized Job job(long id) {
        Cursor c = w().rawQuery("SELECT " + JCOLS + " FROM job WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readJob(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<Job> jobs() {
        List<Job> l = new ArrayList<Job>();
        Cursor c = w().rawQuery("SELECT " + JCOLS + " FROM job ORDER BY id", null);
        try {
            while (c.moveToNext()) l.add(readJob(c));
        } finally {
            c.close();
        }
        return l;
    }

    // ------------------------------------------------------------------ run

    static final String RCOLS = "id, owner, title, template_id, command, state, attempts, max_attempts, priority, seq, planned_at, "
            + "expire_at, next_retry_at, wait_reason, result, error, progress, step, created_at, updated_at";

    private static ContentValues rv(Run r) {
        ContentValues v = new ContentValues();
        v.put("owner", r.owner); v.put("title", r.title); v.put("template_id", r.templateId); v.put("command", r.command);
        v.put("state", r.state.name()); v.put("attempts", r.attempts); v.put("max_attempts", r.maxAttempts); v.put("priority", r.priority);
        v.put("seq", r.seq); v.put("planned_at", r.plannedAt); v.put("expire_at", r.expireAt); v.put("next_retry_at", r.nextRetryAt);
        v.put("wait_reason", r.waitReason); v.put("result", r.result); v.put("error", r.error); v.put("progress", r.progress);
        v.put("step", r.step); v.put("created_at", r.createdAt); v.put("updated_at", r.updatedAt);
        return v;
    }

    private static Run readRun(Cursor c) {
        Run r = new Run();
        r.id = c.getLong(0); r.owner = nz(c.getString(1)); r.title = nz(c.getString(2)); r.templateId = nz(c.getString(3));
        r.command = nz(c.getString(4)); r.state = JobMachine.parse(c.getString(5)); r.attempts = c.getInt(6); r.maxAttempts = c.getInt(7);
        r.priority = c.getInt(8); r.seq = c.getLong(9); r.plannedAt = c.getLong(10); r.expireAt = c.getLong(11);
        r.nextRetryAt = c.getLong(12); r.waitReason = nz(c.getString(13)); r.result = nz(c.getString(14)); r.error = nz(c.getString(15));
        r.progress = c.getInt(16); r.step = nz(c.getString(17)); r.createdAt = c.getLong(18); r.updatedAt = c.getLong(19);
        return r;
    }

    @Override public synchronized long addRun(Run r) {
        r.id = w().insert("run", null, rv(r));
        if (r.seq == 0) {
            r.seq = r.id;
            updateRun(r);
        }
        return r.id;
    }

    @Override public synchronized void updateRun(Run r) {
        w().update("run", rv(r), "id=?", new String[]{String.valueOf(r.id)});
    }

    @Override public synchronized Run run(long id) {
        Cursor c = w().rawQuery("SELECT " + RCOLS + " FROM run WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readRun(c) : null;
        } finally {
            c.close();
        }
    }

    /** 只回最近 500 条（历史页 / 统计足够；老记录由 {@link #prune} 清）。 */
    @Override public synchronized List<Run> runs() {
        List<Run> l = new ArrayList<Run>();
        Cursor c = w().rawQuery("SELECT " + RCOLS + " FROM run ORDER BY id DESC LIMIT 500", null);
        try {
            while (c.moveToNext()) l.add(0, readRun(c));
        } finally {
            c.close();
        }
        return l;
    }

    @Override public synchronized Run runFor(String owner, long plannedAt) {
        Cursor c = w().rawQuery("SELECT " + RCOLS + " FROM run WHERE owner=? AND planned_at=? LIMIT 1",
                new String[]{owner, String.valueOf(plannedAt)});
        try {
            return c.moveToFirst() ? readRun(c) : null;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------ outbox

    static final String OCOLS = "id, run_id, idem, title, text, urgency, want_speak, digest_key, deliver_after, expire_at, status, "
            + "delivered_via, delivered_at, created_at, worth";

    private static ContentValues ov(Outbox o) {
        ContentValues v = new ContentValues();
        v.put("run_id", o.runId); v.put("idem", o.idem); v.put("title", o.title); v.put("text", o.text); v.put("urgency", o.urgency);
        v.put("want_speak", o.wantSpeak ? 1 : 0); v.put("digest_key", o.digestKey); v.put("deliver_after", o.deliverAfter);
        v.put("expire_at", o.expireAt); v.put("status", o.status); v.put("delivered_via", o.deliveredVia);
        v.put("delivered_at", o.deliveredAt); v.put("created_at", o.createdAt); v.put("worth", o.worth);
        return v;
    }

    private static Outbox readOut(Cursor c) {
        Outbox o = new Outbox();
        o.id = c.getLong(0); o.runId = c.getLong(1); o.idem = nz(c.getString(2)); o.title = nz(c.getString(3)); o.text = nz(c.getString(4));
        o.urgency = nz(c.getString(5)); o.wantSpeak = c.getInt(6) != 0; o.digestKey = nz(c.getString(7)); o.deliverAfter = c.getLong(8);
        o.expireAt = c.getLong(9); o.status = c.getInt(10); o.deliveredVia = nz(c.getString(11)); o.deliveredAt = c.getLong(12);
        o.createdAt = c.getLong(13); o.worth = c.getInt(14);
        return o;
    }

    @Override public synchronized long addOutbox(Outbox o) {
        if (o.idem.length() > 0) {
            Cursor c = w().rawQuery("SELECT id FROM outbox WHERE idem=?", new String[]{o.idem});
            try {
                if (c.moveToFirst()) return c.getLong(0);
            } finally {
                c.close();
            }
        }
        o.id = w().insert("outbox", null, ov(o));
        return o.id;
    }

    @Override public synchronized void updateOutbox(Outbox o) {
        w().update("outbox", ov(o), "id=?", new String[]{String.valueOf(o.id)});
    }

    @Override public synchronized List<Outbox> outbox() {
        List<Outbox> l = new ArrayList<Outbox>();
        Cursor c = w().rawQuery("SELECT " + OCOLS + " FROM outbox ORDER BY id DESC LIMIT 500", null);
        try {
            while (c.moveToNext()) l.add(0, readOut(c));
        } finally {
            c.close();
        }
        return l;
    }

    // ------------------------------------------------------------------ grant

    @Override public synchronized void saveGrant(String key, TaskGrant g) {
        ContentValues v = new ContentValues();
        v.put("key", key); v.put("spec", g.spec); v.put("required", TaskGrant.encode(g.required)); v.put("granted", TaskGrant.encode(g.granted));
        v.put("fingerprint", g.fingerprint); v.put("granted_at", g.grantedAt);
        w().insertWithOnConflict("grant_", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public synchronized TaskGrant grant(String key) {
        Cursor c = w().rawQuery("SELECT spec, required, granted, fingerprint, granted_at FROM grant_ WHERE key=?", new String[]{key});
        try {
            return c.moveToFirst() ? TaskGrant.restore(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized void deleteGrant(String key) {
        w().delete("grant_", "key=?", new String[]{key});
    }

    @Override public synchronized void clear() {
        SQLiteDatabase db = w();
        db.delete("job", null, null);
        db.delete("run", null, null);
        db.delete("outbox", null, null);
        db.delete("grant_", null, null);
    }

    /** 清 30 天前已结束的 Run 和已处理的 outbox。 */
    public synchronized void prune(long now) {
        String cut = String.valueOf(now - 30L * 86400000L);
        w().delete("run", "updated_at<? AND state IN ('DONE','FAILED','CANCELLED')", new String[]{cut});
        w().delete("outbox", "created_at<? AND status<>0", new String[]{cut});
    }
}
