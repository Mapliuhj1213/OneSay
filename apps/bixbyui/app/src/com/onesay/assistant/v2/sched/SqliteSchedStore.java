package com.onesay.assistant.v2.sched;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * [sched] 本地日程的 SQLite 实现（App 私有目录 databases/sched.db，不联网、不同步）。结构与升级见 {@link SchedMigrations}。
 * 所有方法同步加锁；在后台线程调用（主线程调也不会错，只是慢一点）。
 */
public final class SqliteSchedStore extends SQLiteOpenHelper implements SchedStore {

    private static volatile SqliteSchedStore sInst;

    public static SqliteSchedStore get(Context ctx) {
        SqliteSchedStore s = sInst;
        if (s != null) return s;
        synchronized (SqliteSchedStore.class) {
            if (sInst == null) sInst = new SqliteSchedStore(ctx.getApplicationContext());
            return sInst;
        }
    }

    private SqliteSchedStore(Context ctx) {
        super(ctx, SchedMigrations.DB_NAME, null, SchedMigrations.LATEST);
    }

    @Override public void onCreate(final SQLiteDatabase db) {
        SchedMigrations.migrate(exec(db), 0, SchedMigrations.LATEST);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        SchedMigrations.migrate(exec(db), oldV, newV);
    }

    /** 装回旧版本：保留数据与结构（默认实现会抛异常让库打不开）。 */
    @Override public void onDowngrade(SQLiteDatabase db, int oldV, int newV) {
    }

    private static SchedMigrations.Exec exec(final SQLiteDatabase db) {
        return new SchedMigrations.Exec() {
            @Override public void exec(String sql) {
                db.execSQL(sql);
            }
        };
    }

    static final String COLS = "id,title,start_at,end_at,all_day,location,note,repeat,week_mask,month_day,lead_min,source,created_at,updated_at,"
            + "deleted,deleted_at,lunar,lunar_month,lunar_day,ext_id";

    private static SchedEvent read(Cursor c) {
        SchedEvent e = new SchedEvent();
        e.id = c.getLong(0);
        e.title = c.getString(1);
        e.startAt = c.getLong(2);
        e.endAt = c.getLong(3);
        e.allDay = c.getInt(4) != 0;
        e.location = c.getString(5);
        e.note = c.getString(6);
        e.repeat = c.getInt(7);
        e.weekMask = c.getInt(8);
        e.monthDay = c.getInt(9);
        e.leadMin = c.getInt(10);
        e.source = c.getString(11);
        e.createdAt = c.getLong(12);
        e.updatedAt = c.getLong(13);
        e.deleted = c.getInt(14) != 0;
        e.deletedAt = c.getLong(15);
        e.lunar = c.getInt(16) != 0;
        e.lunarMonth = c.getInt(17);
        e.lunarDay = c.getInt(18);
        e.extId = c.getString(19);
        return e;
    }

    private static ContentValues values(SchedEvent e) {
        ContentValues v = new ContentValues();
        v.put("title", e.title == null ? "" : e.title);
        v.put("start_at", e.startAt);
        v.put("end_at", e.endAt);
        v.put("all_day", e.allDay ? 1 : 0);
        v.put("location", e.location == null ? "" : e.location);
        v.put("note", e.note == null ? "" : e.note);
        v.put("repeat", e.repeat);
        v.put("week_mask", e.weekMask);
        v.put("month_day", e.monthDay);
        v.put("lead_min", e.leadMin);
        v.put("source", e.source == null ? "" : e.source);
        v.put("created_at", e.createdAt);
        v.put("updated_at", e.updatedAt);
        v.put("deleted", e.deleted ? 1 : 0);
        v.put("deleted_at", e.deletedAt);
        v.put("lunar", e.lunar ? 1 : 0);
        v.put("lunar_month", e.lunarMonth);
        v.put("lunar_day", e.lunarDay);
        v.put("ext_id", e.extId == null ? "" : e.extId);
        return v;
    }

    @Override public synchronized long insert(SchedEvent e) {
        long id = getWritableDatabase().insert(SchedMigrations.T_EVENT, null, values(e));
        if (id > 0) e.id = id;
        return id;
    }

    @Override public synchronized boolean update(SchedEvent e) {
        return getWritableDatabase().update(SchedMigrations.T_EVENT, values(e), "id=?", new String[]{String.valueOf(e.id)}) > 0;
    }

    @Override public synchronized SchedEvent get(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM " + SchedMigrations.T_EVENT + " WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<SchedEvent> all(boolean includeDeleted) {
        List<SchedEvent> l = new ArrayList<SchedEvent>();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM " + SchedMigrations.T_EVENT
                + (includeDeleted ? "" : " WHERE deleted=0") + " ORDER BY start_at ASC", null);
        try {
            while (c.moveToNext()) l.add(read(c));
        } finally {
            c.close();
        }
        return l;
    }

    @Override public synchronized boolean setDeleted(long id, boolean deleted, long at) {
        ContentValues v = new ContentValues();
        v.put("deleted", deleted ? 1 : 0);
        v.put("deleted_at", deleted ? at : 0);
        v.put("updated_at", at);
        return getWritableDatabase().update(SchedMigrations.T_EVENT, v, "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    @Override public synchronized int purgeDeleted(long before) {
        return getWritableDatabase().delete(SchedMigrations.T_EVENT, "deleted=1 AND deleted_at<?", new String[]{String.valueOf(before)});
    }

    @Override public synchronized SchedEvent byExtId(String extId) {
        if (extId == null || extId.length() == 0) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM " + SchedMigrations.T_EVENT + " WHERE ext_id=? LIMIT 1", new String[]{extId});
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public int schemaVersion() {
        return getReadableDatabase().getVersion();
    }

    public synchronized String kv(String k) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + SchedMigrations.T_KV + " WHERE k=?", new String[]{k});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public synchronized void putKv(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v == null ? "" : v);
        getWritableDatabase().insertWithOnConflict(SchedMigrations.T_KV, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
