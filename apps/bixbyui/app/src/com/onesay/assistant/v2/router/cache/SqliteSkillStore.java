package com.onesay.assistant.v2.router.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能缓存的 SQLite 存储（）。
 *
 * <p>与旧 {@code skills_v2.db} 分开存放（{@link #DB_PROD}），自测用独立库 {@link #DB_SELFTEST}，
 * 自测永远不会清空或污染生产库。
 *
 * <p><b>严禁 close() 数据库</b>：{@link SQLiteOpenHelper} 返回的是进程级共享连接
 * （见旧 L1SkillRouter 2026-10-02 的事故注释），关了会让并发读写全部抛 already-closed。
 * 同名库在进程内只建一个 helper（{@link #get}）。
 */
public final class SqliteSkillStore implements SkillStore {

    private static final String TAG = "SkillStore";
    public static final String DB_PROD = "skill_cache_v3.db";
    public static final String DB_SELFTEST = "skill_cache_selftest.db";
    private static final int DB_VERSION = 1;

    private static final Map<String, SqliteSkillStore> sInstances = new HashMap<String, SqliteSkillStore>();

    public static synchronized SqliteSkillStore get(Context ctx, String dbName) {
        SqliteSkillStore s = sInstances.get(dbName);
        if (s == null) {
            s = new SqliteSkillStore(ctx.getApplicationContext(), dbName);
            sInstances.put(dbName, s);
        }
        return s;
    }

    private final SQLiteOpenHelper mHelper;

    private SqliteSkillStore(Context ctx, String dbName) {
        mHelper = new SQLiteOpenHelper(ctx, dbName, null, DB_VERSION) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skill_template ("
                        + "key TEXT NOT NULL, grp TEXT NOT NULL, label TEXT, sign TEXT, args_tpl TEXT, "
                        + "cap_version TEXT, epoch TEXT, status TEXT, ok_count INTEGER DEFAULT 0, "
                        + "fail_count INTEGER DEFAULT 0, hit_count INTEGER DEFAULT 0, last_hit_ts INTEGER DEFAULT 0, "
                        + "created_ts INTEGER DEFAULT 0, ttl_ms INTEGER DEFAULT 0, has_text_slot INTEGER DEFAULT 0, "
                        + "PRIMARY KEY(key, grp))");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tpl_text ON skill_template(grp, has_text_slot)");
                db.execSQL("CREATE TABLE IF NOT EXISTS negative_cache ("
                        + "key TEXT NOT NULL, grp TEXT NOT NULL, reason TEXT, until_ts INTEGER, PRIMARY KEY(key, grp))");
            }

            @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
                // v1 起步，暂无升级
            }
        };
    }

    private static final String COLS = "key, grp, label, sign, args_tpl, cap_version, epoch, status, ok_count, "
            + "fail_count, hit_count, last_hit_ts, created_ts, ttl_ms, has_text_slot";

    private static SkillEntry read(Cursor c) {
        SkillEntry e = new SkillEntry();
        e.key = c.getString(0);
        e.grp = c.getString(1);
        e.label = c.getString(2);
        e.sign = c.getString(3);
        e.argsTpl = c.getString(4);
        e.capVersion = c.getString(5);
        e.epoch = c.getString(6);
        e.status = c.getString(7);
        e.okCount = c.getInt(8);
        e.failCount = c.getInt(9);
        e.hitCount = c.getInt(10);
        e.lastHitTs = c.getLong(11);
        e.createdTs = c.getLong(12);
        e.ttlMs = c.getLong(13);
        e.hasTextSlot = c.getInt(14) != 0;
        return e;
    }

    @Override public SkillEntry get(String key, String grp) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM skill_template WHERE key = ? AND grp = ?", new String[]{ key, grp });
            return c.moveToFirst() ? read(c) : null;
        } catch (Throwable t) {
            Log.w(TAG, "get 失败: " + t.getMessage());
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public void put(SkillEntry e) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("key", e.key);
            cv.put("grp", e.grp);
            cv.put("label", e.label);
            cv.put("sign", e.sign);
            cv.put("args_tpl", e.argsTpl);
            cv.put("cap_version", e.capVersion);
            cv.put("epoch", e.epoch);
            cv.put("status", e.status);
            cv.put("ok_count", e.okCount);
            cv.put("fail_count", e.failCount);
            cv.put("hit_count", e.hitCount);
            cv.put("last_hit_ts", e.lastHitTs);
            cv.put("created_ts", e.createdTs);
            cv.put("ttl_ms", e.ttlMs);
            cv.put("has_text_slot", e.hasTextSlot ? 1 : 0);
            mHelper.getWritableDatabase().insertWithOnConflict("skill_template", null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.e(TAG, "put 失败: " + t.getMessage());
        }
    }

    @Override public void delete(String key, String grp) {
        try {
            mHelper.getWritableDatabase().delete("skill_template", "key = ? AND grp = ?", new String[]{ key, grp });
        } catch (Throwable t) {
            Log.w(TAG, "delete 失败: " + t.getMessage());
        }
    }

    private List<SkillEntry> query(String where, String[] args) {
        List<SkillEntry> out = new ArrayList<SkillEntry>();
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM skill_template" + (where != null ? " WHERE " + where : ""), args);
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable t) {
            Log.w(TAG, "query 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override public List<SkillEntry> listTextTemplates(String grp) {
        return query("grp = ? AND has_text_slot = 1", new String[]{ grp });
    }

    @Override public List<SkillEntry> listAll() {
        return query(null, null);
    }

    @Override public int count() {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT count(*) FROM skill_template", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 命中次数总和（观测用）。 */
    public int totalHits() {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT sum(hit_count) FROM skill_template", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public int evictLru(int max) {
        try {
            int n = count();
            if (n <= max) return 0;
            int drop = n - max;
            SQLiteDatabase db = mHelper.getWritableDatabase();
            db.execSQL("DELETE FROM skill_template WHERE rowid IN (SELECT rowid FROM skill_template "
                    + "ORDER BY MAX(last_hit_ts, created_ts) ASC LIMIT " + drop + ")");
            return drop;
        } catch (Throwable t) {
            Log.w(TAG, "evictLru 失败: " + t.getMessage());
            return 0;
        }
    }

    @Override public void putNegative(String key, String grp, String reason, long untilTs) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("key", key);
            cv.put("grp", grp);
            cv.put("reason", reason);
            cv.put("until_ts", untilTs);
            mHelper.getWritableDatabase().insertWithOnConflict("negative_cache", null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "putNegative 失败: " + t.getMessage());
        }
    }

    @Override public Object[] getNegative(String key, String grp) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery(
                    "SELECT reason, until_ts FROM negative_cache WHERE key = ? AND grp = ?", new String[]{ key, grp });
            if (!c.moveToFirst()) return null;
            return new Object[]{ c.getString(0), Long.valueOf(c.getLong(1)) };
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public void deleteNegative(String key, String grp) {
        try {
            mHelper.getWritableDatabase().delete("negative_cache", "key = ? AND grp = ?", new String[]{ key, grp });
        } catch (Throwable ignored) {}
    }

    @Override public int clear() {
        try {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            int n = db.delete("skill_template", null, null);
            db.delete("negative_cache", null, null);
            return n;
        } catch (Throwable t) {
            Log.e(TAG, "clear 失败: " + t.getMessage());
            return -1;
        }
    }
}
