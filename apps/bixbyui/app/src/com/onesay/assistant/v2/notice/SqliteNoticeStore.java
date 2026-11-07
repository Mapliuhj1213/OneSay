package com.onesay.assistant.v2.notice;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link NoticeStore} 的 SQLite 实现：复用 A 线的 jobs.db（{@link com.onesay.assistant.v2.jobs.SqliteJobStore}），表第一次用时
 * {@code CREATE TABLE IF NOT EXISTS}——与 event_task 同一做法，<b>不升 jobs.db 版本号</b>，已装的 debug 包不用迁移。
 *
 * <pre>island_notice(dedup_key TEXT PRIMARY KEY, json TEXT, updated_at INTEGER)</pre>
 */
public final class SqliteNoticeStore implements NoticeStore {

    private final com.onesay.assistant.v2.jobs.SqliteJobStore mJobs;
    private volatile boolean mReady;

    public SqliteNoticeStore(com.onesay.assistant.v2.jobs.SqliteJobStore jobs) {
        mJobs = jobs;
    }

    private SQLiteDatabase w() {
        SQLiteDatabase db = mJobs.writableDatabase();
        if (!mReady) {
            db.execSQL("CREATE TABLE IF NOT EXISTS island_notice (dedup_key TEXT PRIMARY KEY, json TEXT, updated_at INTEGER)");
            mReady = true;
        }
        return db;
    }

    @Override public synchronized void put(IslandNotice n) {
        if (n == null || !n.persist) return;
        ContentValues v = new ContentValues();
        v.put("dedup_key", n.dedupKey);
        v.put("json", n.toJson());
        v.put("updated_at", System.currentTimeMillis());
        w().insertWithOnConflict("island_notice", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public synchronized IslandNotice get(String key) {
        Cursor c = w().rawQuery("SELECT json FROM island_notice WHERE dedup_key=?", new String[]{key});
        try {
            return c.moveToFirst() ? IslandNotice.fromJson(c.getString(0)) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<IslandNotice> all() {
        List<IslandNotice> l = new ArrayList<IslandNotice>();
        Cursor c = w().rawQuery("SELECT json FROM island_notice ORDER BY updated_at", null);
        try {
            while (c.moveToNext()) {
                IslandNotice n = IslandNotice.fromJson(c.getString(0));
                if (n != null) l.add(n);
            }
        } finally {
            c.close();
        }
        return l;
    }

    @Override public synchronized void delete(String key) {
        w().delete("island_notice", "dedup_key=?", new String[]{key});
    }
}
