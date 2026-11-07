package com.onesay.assistant.v2.quicknote;

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
 * [quicknote] R10：记一记独立库 {@link #DB_PROD}（quicknote.db，D3）。同名库进程内只建一个 helper，严禁 close()。
 * 不动 S2 的 s2_memory.db（S2 DB_VERSION 仍是 1）。
 */
public final class SqliteQuickNoteStore implements QuickNoteStore {

    private static final String TAG = "QuickNote";
    public static final String DB_PROD = "quicknote.db";
    public static final String DB_SELFTEST = "quicknote_selftest.db";
    private static final int DB_VERSION = 1;

    private static final Map<String, SqliteQuickNoteStore> sInstances = new HashMap<String, SqliteQuickNoteStore>();

    public static synchronized SqliteQuickNoteStore get(Context ctx, String name) {
        SqliteQuickNoteStore s = sInstances.get(name);
        if (s == null) {
            s = new SqliteQuickNoteStore(ctx.getApplicationContext(), name);
            sInstances.put(name, s);
        }
        return s;
    }

    private final SQLiteOpenHelper mHelper;

    private SqliteQuickNoteStore(Context ctx, String name) {
        mHelper = new SQLiteOpenHelper(ctx, name, null, DB_VERSION) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS quicknote ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, "
                        + "day TEXT NOT NULL, source TEXT NOT NULL, category TEXT NOT NULL, tags TEXT DEFAULT '[]', "
                        + "title TEXT NOT NULL, summary TEXT NOT NULL, body TEXT DEFAULT '', entities TEXT DEFAULT '[]', "
                        + "app_pkg TEXT DEFAULT '', app_label TEXT DEFAULT '', activity TEXT DEFAULT '', "
                        + "code_kind TEXT DEFAULT '', code_value TEXT DEFAULT '', code_state INTEGER DEFAULT 0, "
                        + "amount_fen INTEGER DEFAULT 0, event_at INTEGER DEFAULT 0, notes TEXT DEFAULT '', thumb_path TEXT DEFAULT '', "
                        + "deleted INTEGER DEFAULT 0, deleted_at INTEGER DEFAULT 0, ledger_id INTEGER DEFAULT 0)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_qn_time ON quicknote(deleted, created_at)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_qn_cat ON quicknote(category, created_at)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_qn_code ON quicknote(code_state)");
            }

            @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            }
        };
    }

    private static final String COLS = "id, created_at, updated_at, day, source, category, tags, title, summary, body, entities, "
            + "app_pkg, app_label, activity, code_kind, code_value, code_state, amount_fen, event_at, notes, thumb_path, deleted, deleted_at, ledger_id";

    private static QuickNoteEntry read(Cursor c) {
        QuickNoteEntry e = new QuickNoteEntry();
        int i = 0;
        e.id = c.getLong(i++);
        e.createdAt = c.getLong(i++);
        e.updatedAt = c.getLong(i++);
        e.day = nz(c.getString(i++));
        e.source = nz(c.getString(i++));
        e.category = nz(c.getString(i++));
        e.setTagsJson(c.getString(i++));
        e.title = nz(c.getString(i++));
        e.summary = nz(c.getString(i++));
        e.body = nz(c.getString(i++));
        e.entities = c.getString(i) == null ? "[]" : c.getString(i);
        i++;
        e.appPkg = nz(c.getString(i++));
        e.appLabel = nz(c.getString(i++));
        e.activity = nz(c.getString(i++));
        e.codeKind = nz(c.getString(i++));
        e.codeValue = nz(c.getString(i++));
        e.codeState = c.getInt(i++);
        e.amountFen = c.getLong(i++);
        e.eventAt = c.getLong(i++);
        e.notes = nz(c.getString(i++));
        e.thumbPath = nz(c.getString(i++));
        e.deleted = c.getInt(i++) != 0;
        e.deletedAt = c.getLong(i++);
        e.ledgerId = c.getLong(i);
        return e;
    }

    private static ContentValues values(QuickNoteEntry e) {
        ContentValues v = new ContentValues();
        v.put("created_at", e.createdAt);
        v.put("updated_at", e.updatedAt == 0 ? e.createdAt : e.updatedAt);
        v.put("day", nz(e.day));
        v.put("source", nz(e.source));
        v.put("category", nz(e.category));
        v.put("tags", e.tagsJson());
        v.put("title", nz(e.title));
        v.put("summary", nz(e.summary));
        v.put("body", nz(e.body));
        v.put("entities", e.entities == null ? "[]" : e.entities);
        v.put("app_pkg", nz(e.appPkg));
        v.put("app_label", nz(e.appLabel));
        v.put("activity", nz(e.activity));
        v.put("code_kind", nz(e.codeKind));
        v.put("code_value", nz(e.codeValue));
        v.put("code_state", e.codeState);
        v.put("amount_fen", e.amountFen);
        v.put("event_at", e.eventAt);
        v.put("notes", nz(e.notes));
        v.put("thumb_path", nz(e.thumbPath));
        v.put("deleted", e.deleted ? 1 : 0);
        v.put("deleted_at", e.deletedAt);
        v.put("ledger_id", e.ledgerId);
        return v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override public long insert(QuickNoteEntry e) {
        try {
            long id = mHelper.getWritableDatabase().insert("quicknote", null, values(e));
            e.id = id;
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] insert 失败: " + t.getMessage());
            return -1;
        }
    }

    @Override public QuickNoteEntry get(long id) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM quicknote WHERE id=?", new String[]{String.valueOf(id)});
            return c.moveToFirst() ? read(c) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public List<QuickNoteEntry> list(boolean deleted) {
        List<QuickNoteEntry> out = new ArrayList<QuickNoteEntry>();
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM quicknote WHERE deleted=? ORDER BY created_at DESC, id DESC",
                    new String[]{deleted ? "1" : "0"});
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] list 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override public boolean update(QuickNoteEntry e) {
        try {
            return mHelper.getWritableDatabase().update("quicknote", values(e), "id=?", new String[]{String.valueOf(e.id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean softDelete(long id, long now) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        v.put("deleted_at", now);
        try {
            return mHelper.getWritableDatabase().update("quicknote", v, "id=? AND deleted=0", new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean restore(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 0);
        v.put("deleted_at", 0);
        try {
            return mHelper.getWritableDatabase().update("quicknote", v, "id=? AND deleted=1", new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean deleteForever(long id) {
        try {
            return mHelper.getWritableDatabase().delete("quicknote", "id=?", new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public List<QuickNoteEntry> purgeDeletedBefore(long cutoff) {
        List<QuickNoteEntry> gone = new ArrayList<QuickNoteEntry>();
        for (QuickNoteEntry e : list(true)) {
            if (e.deletedAt < cutoff && deleteForever(e.id)) gone.add(e);
        }
        return gone;
    }

    @Override public int clearAll() {
        try {
            return mHelper.getWritableDatabase().delete("quicknote", "1", null);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override public int count(boolean deleted) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM quicknote WHERE deleted=?", new String[]{deleted ? "1" : "0"});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }
}
