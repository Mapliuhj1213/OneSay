package com.onesay.assistant.v2.events;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EventStore} 的 SQLite 实现：复用第 3 项的 jobs.db（{@link com.onesay.assistant.v2.jobs.SqliteJobStore}），表 event_task 第一次用时
 * {@code CREATE TABLE IF NOT EXISTS} 建出来——不升 jobs.db 的版本号，已装的 debug 包库不用迁移。
 *
 * <pre>event_task(id PK, json TEXT, created_at INTEGER)</pre>
 */
public final class SqliteEventStore implements EventStore {

    private final com.onesay.assistant.v2.jobs.SqliteJobStore mJobs;
    private volatile boolean mReady;

    public SqliteEventStore(com.onesay.assistant.v2.jobs.SqliteJobStore jobs) {
        mJobs = jobs;
    }

    private SQLiteDatabase w() {
        SQLiteDatabase db = mJobs.writableDatabase();
        if (!mReady) {
            db.execSQL("CREATE TABLE IF NOT EXISTS event_task (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT, created_at INTEGER)");
            mReady = true;
        }
        return db;
    }

    @Override public synchronized long add(EventTask t) {
        ContentValues v = new ContentValues();
        v.put("json", t.toJson());
        v.put("created_at", t.createdAt);
        t.id = w().insert("event_task", null, v);
        return t.id;
    }

    @Override public synchronized void update(EventTask t) {
        ContentValues v = new ContentValues();
        v.put("json", t.toJson());
        w().update("event_task", v, "id=?", new String[]{String.valueOf(t.id)});
    }

    @Override public synchronized boolean delete(long id) {
        return w().delete("event_task", "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    @Override public synchronized EventTask get(long id) {
        Cursor c = w().rawQuery("SELECT id, json FROM event_task WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<EventTask> all() {
        List<EventTask> l = new ArrayList<EventTask>();
        Cursor c = w().rawQuery("SELECT id, json FROM event_task ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                EventTask t = read(c);
                if (t != null) l.add(t);
            }
        } finally {
            c.close();
        }
        return l;
    }

    private static EventTask read(Cursor c) {
        try {
            EventTask t = EventTask.fromJson(c.getString(1));
            t.id = c.getLong(0);
            return t;
        } catch (Exception e) {
            return null;
        }
    }
}
