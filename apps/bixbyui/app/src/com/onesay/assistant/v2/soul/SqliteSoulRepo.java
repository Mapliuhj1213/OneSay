package com.onesay.assistant.v2.soul;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * [soul] {@link SoulRepo} 的 SQLite 实现：表建在现有 {@code s2_memory.db} 里（共用 SqliteMemoryStore 的 helper，不另起库、严禁 close）。
 * 所有方法失败不抛异常（返回 -1 / false / 空）。
 */
public final class SqliteSoulRepo implements SoulRepo {

    private static final String TAG = "Soul";

    private final SQLiteOpenHelper mHelper;

    public SqliteSoulRepo(SQLiteOpenHelper helper) {
        mHelper = helper;
    }

    private SQLiteDatabase w() {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        return db;
    }

    private static final String COLS = "id, kind, text, quote, evidence_ref, confidence, first_seen, reinforced_at, valid_until, supersedes, "
            + "status, pinned, privacy_class, source, recall_count, subject, relation, value, topic, day, session_id, tainted, legacy_id, "
            + "user_edited, cand_kind";

    private static Claim read(String table, Cursor c) {
        Claim x = new Claim();
        x.table = table;
        x.id = c.getLong(0);
        x.kind = nz(c.getString(1));
        x.text = nz(c.getString(2));
        x.quote = nz(c.getString(3));
        x.evidenceRef = nz(c.getString(4));
        x.confidence = c.getDouble(5);
        x.firstSeen = c.getLong(6);
        x.reinforcedAt = c.getLong(7);
        x.validUntil = c.getLong(8);
        x.supersedes = c.getLong(9);
        x.status = nz(c.getString(10));
        x.pinned = c.getInt(11) != 0;
        x.privacy = c.getString(12) == null ? Claim.P_NORMAL : c.getString(12);
        x.source = nz(c.getString(13));
        x.recallCount = c.getInt(14);
        x.subject = nz(c.getString(15));
        x.relation = nz(c.getString(16));
        x.value = nz(c.getString(17));
        x.topic = nz(c.getString(18));
        x.day = nz(c.getString(19));
        x.sessionId = nz(c.getString(20));
        x.tainted = c.getInt(21) != 0;
        x.legacyId = c.getLong(22);
        x.userEdited = c.getInt(23) != 0;
        x.candKind = nz(c.getString(24));
        return x;
    }

    private static ContentValues cv(Claim x) {
        ContentValues v = new ContentValues();
        v.put("kind", x.kind);
        v.put("text", x.text);
        v.put("quote", x.quote);
        v.put("evidence_ref", x.evidenceRef);
        v.put("confidence", x.confidence);
        v.put("first_seen", x.firstSeen);
        v.put("reinforced_at", x.reinforcedAt);
        v.put("valid_until", x.validUntil);
        v.put("supersedes", x.supersedes);
        v.put("status", x.status);
        v.put("pinned", x.pinned ? 1 : 0);
        v.put("privacy_class", x.privacy);
        v.put("source", x.source);
        v.put("recall_count", x.recallCount);
        v.put("subject", x.subject);
        v.put("relation", x.relation);
        v.put("value", x.value);
        v.put("topic", x.topic);
        v.put("day", x.day);
        v.put("session_id", x.sessionId);
        v.put("tainted", x.tainted ? 1 : 0);
        v.put("legacy_id", x.legacyId);
        v.put("user_edited", x.userEdited ? 1 : 0);
        v.put("cand_kind", x.candKind);
        return v;
    }

    private static boolean okTable(String t) {
        return Claim.T_CORE.equals(t) || Claim.T_PROFILE.equals(t) || Claim.T_LOG.equals(t);
    }

    private long nextId(SQLiteDatabase db) {
        long max = ID_BASE;
        for (String t : new String[]{Claim.T_CORE, Claim.T_PROFILE, Claim.T_LOG}) {
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT MAX(id) FROM " + t, null);
                if (c.moveToFirst() && !c.isNull(0)) max = Math.max(max, c.getLong(0));
            } finally {
                if (c != null) c.close();
            }
        }
        // 删除后不复用最大号：记在 meta 里（被忘的 id 不会被新条目顶替，S2 撤销芯片里的旧 id 不会误删新条目）
        String hw = meta("id_hw");
        try {
            if (hw != null) max = Math.max(max, Long.parseLong(hw));
        } catch (NumberFormatException ignored) {
        }
        return max + 1;
    }

    @Override public synchronized long insert(Claim c) {
        if (c == null || !okTable(c.table)) return -1;
        try {
            SQLiteDatabase db = w();
            long id = nextId(db);
            ContentValues v = cv(c);
            v.put("id", id);
            long r = db.insert(c.table, null, v);
            if (r < 0) return -1;
            setMeta("id_hw", String.valueOf(id));
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] insert 失败: " + t.getMessage());
            return -1;
        }
    }

    @Override public synchronized boolean update(Claim c) {
        if (c == null || !okTable(c.table)) return false;
        try {
            return w().update(c.table, cv(c), "id = ?", new String[]{String.valueOf(c.id)}) > 0;
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] update 失败: " + t.getMessage());
            return false;
        }
    }

    @Override public Claim get(String table, long id) {
        if (!okTable(table)) return null;
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM " + table + " WHERE id = ?", new String[]{String.valueOf(id)});
            return c.moveToFirst() ? read(table, c) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public List<Claim> list(String table) {
        List<Claim> out = new ArrayList<Claim>();
        if (!okTable(table)) return out;
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT " + COLS + " FROM " + table + " ORDER BY id", null);
            while (c.moveToNext()) out.add(read(table, c));
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] list 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override public synchronized int delete(String table, long id) {
        if (!okTable(table)) return 0;
        try {
            return w().delete(table, "id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override public synchronized int clear(String table) {
        if (!okTable(table)) return 0;
        try {
            return w().delete(table, "1", null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- soul

    private static SoulVersion readSoul(Cursor c) {
        SoulVersion v = new SoulVersion();
        v.id = c.getLong(0);
        v.text = nz(c.getString(1));
        v.source = nz(c.getString(2));
        v.status = nz(c.getString(3));
        v.userLocked = c.getInt(4) != 0;
        v.reason = nz(c.getString(5));
        v.createdAt = c.getLong(6);
        v.baseId = c.getLong(7);
        return v;
    }

    private static ContentValues cvSoul(SoulVersion v) {
        ContentValues x = new ContentValues();
        x.put("text", v.text);
        x.put("source", v.source);
        x.put("status", v.status);
        x.put("user_locked", v.userLocked ? 1 : 0);
        x.put("reason", v.reason);
        x.put("created_at", v.createdAt);
        x.put("base_id", v.baseId);
        return x;
    }

    @Override public synchronized long insertSoul(SoulVersion v) {
        try {
            return w().insert("soul", null, cvSoul(v));
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override public synchronized boolean updateSoul(SoulVersion v) {
        try {
            return w().update("soul", cvSoul(v), "id = ?", new String[]{String.valueOf(v.id)}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public synchronized int deleteSoul(long id) {
        try {
            return w().delete("soul", "id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override public List<SoulVersion> souls() {
        List<SoulVersion> out = new ArrayList<SoulVersion>();
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery(
                    "SELECT id, text, source, status, user_locked, reason, created_at, base_id FROM soul ORDER BY id", null);
            while (c.moveToNext()) out.add(readSoul(c));
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] souls 失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ---------------------------------------------------------------- kv 小表

    private String kvGet(String table, String key) {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT value FROM " + table + " WHERE key = ?", new String[]{key});
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private synchronized void kvSet(String table, String key, String value) {
        try {
            if (value == null) {
                w().delete(table, "key = ?", new String[]{key});
            } else {
                ContentValues v = new ContentValues();
                v.put("key", key);
                v.put("value", value);
                w().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SOUL] kvSet 失败: " + t.getMessage());
        }
    }

    @Override public String identity(String key) { return kvGet("soul_identity", key); }

    @Override public void setIdentity(String key, String value) { kvSet("soul_identity", key, value); }

    @Override public String meta(String key) { return kvGet("soul_meta", key); }

    @Override public void setMeta(String key, String value) { kvSet("soul_meta", key, value); }

    @Override public synchronized long addOpsNote(String scope, String text, long now) {
        try {
            ContentValues v = new ContentValues();
            v.put("scope", scope);
            v.put("text", text);
            v.put("created_at", now);
            return w().insert("ops_notes", null, v);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override public List<String[]> opsNotes() {
        List<String[]> out = new ArrayList<String[]>();
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT scope, text, created_at FROM ops_notes ORDER BY id", null);
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), String.valueOf(c.getLong(2))});
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override public synchronized void addForgotten(String keyHash, long now) {
        if (keyHash == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put("key_hash", keyHash);
            v.put("at", now);
            w().insertWithOnConflict("soul_forgotten", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignored) {
        }
    }

    @Override public boolean isForgotten(String keyHash) {
        if (keyHash == null) return false;
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT 1 FROM soul_forgotten WHERE key_hash = ?", new String[]{keyHash});
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public synchronized long addSnapshot(String json, long now) {
        try {
            ContentValues v = new ContentValues();
            v.put("json", json);
            v.put("at", now);
            long id = w().insert("soul_snapshot", null, v);
            // 只留最近 7 份
            w().execSQL("DELETE FROM soul_snapshot WHERE id NOT IN (SELECT id FROM soul_snapshot ORDER BY id DESC LIMIT 7)");
            return id;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override public String latestSnapshot() {
        Cursor c = null;
        try {
            c = mHelper.getReadableDatabase().rawQuery("SELECT json FROM soul_snapshot ORDER BY id DESC LIMIT 1", null);
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public synchronized int clearSnapshots() {
        try {
            return w().delete("soul_snapshot", "1", null);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
