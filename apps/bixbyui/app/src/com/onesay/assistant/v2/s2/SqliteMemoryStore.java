package com.onesay.assistant.v2.s2;

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
 * [s2] 记忆库 + 习惯库的 SQLite 实现。<b>独立库文件</b> {@link #DB_PROD}（与技能缓存 skill_cache_v3.db 分开），
 * 自测用 {@link #DB_SELFTEST}，自测永远不碰生产库。
 *
 * <p>与 SqliteSkillStore 同样的约定：同名库进程内只建一个 helper，<b>严禁 close()</b>（共享连接）。
 * 数据量是个人级（百~千条），检索在 Java 里打分（S2Engine），这里只做增删查。
 *
 * <pre>
 * memory(id PK, kind, content, subject, relation, value, amount_fen, category, day, created_at)
 *   idx(kind, created_at)  idx(subject)
 * habit_use(id PK, grp, app, ts)       idx(grp, ts)
 * habit_pref(grp PK, explicit_app, reset_at)
 * </pre>
 */
public final class SqliteMemoryStore implements MemoryStore, HabitStore {

    private static final String TAG = "S2";
    public static final String DB_PROD = "s2_memory.db";
    public static final String DB_SELFTEST = "s2_memory_selftest.db";
    /** v2（r2/soul-memory）：同库新增人设 / 画像 / 核心记忆 / 日志等表（{@link com.onesay.assistant.v2.soul.SoulSchema}），旧 memory 表不动。 */
    private static final int DB_VERSION = 2;

    private static final Map<String, SqliteMemoryStore> sInstances = new HashMap<String, SqliteMemoryStore>();

    public static synchronized SqliteMemoryStore get(Context ctx, String dbName) {
        SqliteMemoryStore s = sInstances.get(dbName);
        if (s == null) {
            s = new SqliteMemoryStore(ctx.getApplicationContext(), dbName);
            sInstances.put(dbName, s);
        }
        return s;
    }

    private final SQLiteOpenHelper mHelper;

    private SqliteMemoryStore(Context ctx, String dbName) {
        mHelper = new SQLiteOpenHelper(ctx, dbName, null, DB_VERSION) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memory ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, content TEXT, subject TEXT, relation TEXT, "
                        + "value TEXT, amount_fen INTEGER DEFAULT 0, category TEXT, day TEXT, created_at INTEGER DEFAULT 0)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_mem_kind ON memory(kind, created_at)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_mem_subject ON memory(subject)");
                db.execSQL("CREATE TABLE IF NOT EXISTS habit_use (id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT NOT NULL, app TEXT NOT NULL, ts INTEGER)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_habit_use ON habit_use(grp, ts)");
                db.execSQL("CREATE TABLE IF NOT EXISTS habit_pref (grp TEXT PRIMARY KEY, explicit_app TEXT, reset_at INTEGER DEFAULT 0)");
                com.onesay.assistant.v2.soul.SoulSchema.create(db);   // [soul]
            }

            @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
                // v1 起步；v2 [soul] 加表（IF NOT EXISTS，旧数据由 SoulEngine.migrate 一次性搬）
                if (oldV < 2) com.onesay.assistant.v2.soul.SoulSchema.create(db);
            }
        };
    }

    /** [soul] 同库共用连接（SqliteSoulRepo）；严禁 close。 */
    public SQLiteOpenHelper helper() {
        return mHelper;
    }

    private static final String COLS = "id, kind, content, subject, relation, value, amount_fen, category, day, created_at";

    private static MemoryEntry read(Cursor c) {
        MemoryEntry e = new MemoryEntry();
        e.id = c.getLong(0);
        e.kind = nz(c.getString(1));
        e.content = nz(c.getString(2));
        e.subject = nz(c.getString(3));
        e.relation = nz(c.getString(4));
        e.value = nz(c.getString(5));
        e.amountFen = c.getLong(6);
        e.category = nz(c.getString(7));
        e.day = nz(c.getString(8));
        e.createdAt = c.getLong(9);
        return e;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override public long insert(MemoryEntry e) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("kind", e.kind);
            cv.put("content", e.content);
            cv.put("subject", e.subject);
            cv.put("relation", e.relation);
            cv.put("value", e.value);
            cv.put("amount_fen", e.amountFen);
            cv.put("category", e.category);
            cv.put("day", e.day);
            cv.put("created_at", e.createdAt);
            return mHelper.getWritableDatabase().insert("memory", null, cv);
        } catch (Throwable t) {
            Log.w(TAG, "[S2] insert 失败: " + t.getMessage());
            return -1;
        }
    }

    @Override public List<MemoryEntry> list(String kind) {
        List<MemoryEntry> out = new ArrayList<MemoryEntry>();
        Cursor c = null;
        try {
            c = kind == null
                    ? mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM memory ORDER BY created_at DESC, id DESC", null)
                    : mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM memory WHERE kind = ? ORDER BY created_at DESC, id DESC",
                    new String[]{kind});
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable t) {
            Log.w(TAG, "[S2] list 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override public MemoryEntry get(long id) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM memory WHERE id = ?", new String[]{String.valueOf(id)});
            return c.moveToFirst() ? read(c) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public int delete(long[] ids) {
        if (ids == null) return 0;
        int n = 0;
        try {
            SQLiteDatabase db = mHelper.getWritableDatabase();
            for (long id : ids) n += db.delete("memory", "id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            Log.w(TAG, "[S2] delete 失败: " + t.getMessage());
        }
        return n;
    }

    @Override public int clear(String kind) {
        try {
            return kind == null ? mHelper.getWritableDatabase().delete("memory", "1", null)
                    : mHelper.getWritableDatabase().delete("memory", "kind = ?", new String[]{kind});
        } catch (Throwable t) {
            Log.w(TAG, "[S2] clear 失败: " + t.getMessage());
            return 0;
        }
    }

    @Override public int count(String kind) {
        Cursor c = null;
        try {
            c = kind == null ? mHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memory", null)
                    : mHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memory WHERE kind = ?", new String[]{kind});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    // ------------------------------------------------------------------ HabitStore

    @Override public void recordUse(String group, String app, long ts) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("grp", group);
            cv.put("app", app);
            cv.put("ts", ts);
            SQLiteDatabase db = mHelper.getWritableDatabase();
            db.insert("habit_use", null, cv);
            // 只留每组最近 50 条
            db.execSQL("DELETE FROM habit_use WHERE grp = ? AND id NOT IN (SELECT id FROM habit_use WHERE grp = ? ORDER BY ts DESC, id DESC LIMIT 50)",
                    new Object[]{group, group});
        } catch (Throwable t) {
            Log.w(TAG, "[S2] recordUse 失败: " + t.getMessage());
        }
    }

    @Override public List<String> recentUses(String group, long sinceTs, int limit) {
        List<String> out = new ArrayList<String>();
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT app FROM habit_use WHERE grp = ? AND ts > ? ORDER BY ts DESC, id DESC LIMIT ?",
                    new String[]{group, String.valueOf(sinceTs), String.valueOf(limit)});
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Throwable t) {
            Log.w(TAG, "[S2] recentUses 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private String[] pref(String group) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT explicit_app, reset_at FROM habit_pref WHERE grp = ?", new String[]{group});
            if (c.moveToFirst()) return new String[]{c.getString(0), String.valueOf(c.getLong(1))};
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new String[]{null, "0"};
    }

    private void savePref(String group, String app, long resetAt) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("grp", group);
            if (app == null) cv.putNull("explicit_app");
            else cv.put("explicit_app", app);
            cv.put("reset_at", resetAt);
            mHelper.getWritableDatabase().insertWithOnConflict("habit_pref", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "[S2] savePref 失败: " + t.getMessage());
        }
    }

    @Override public synchronized String explicitDefault(String group) {
        String a = pref(group)[0];
        return a == null || a.length() == 0 ? null : a;
    }

    @Override public synchronized void setExplicitDefault(String group, String app) {
        savePref(group, app, Long.parseLong(pref(group)[1]));
    }

    @Override public synchronized long resetAt(String group) {
        return Long.parseLong(pref(group)[1]);
    }

    @Override public synchronized void setResetAt(String group, long ts) {
        savePref(group, pref(group)[0], ts);
    }

    @Override public void clearHabits() {
        try {
            mHelper.getWritableDatabase().delete("habit_use", "1", null);
            mHelper.getWritableDatabase().delete("habit_pref", "1", null);
        } catch (Throwable t) {
            Log.w(TAG, "[S2] clearHabits 失败: " + t.getMessage());
        }
    }
}
