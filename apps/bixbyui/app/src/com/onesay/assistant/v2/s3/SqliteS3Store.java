package com.onesay.assistant.v2.s3;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [s3] 定时任务 + 待办的 SQLite 实现。<b>独立库文件</b> {@link #DB_PROD}（不和 s2_memory.db / 技能缓存混用），自测用 {@link #DB_SELFTEST}。
 * 约定同 SqliteMemoryStore：同名库进程内只建一个 helper，严禁 close()。
 *
 * <pre>
 * task(id PK, kind, repeat, week_mask, hour, minute, once_at, title, command, next_at, last_fired_at, last_missed_at,
 *      last_result, enabled, created_at, todo_id)
 * todo(id PK, text, due_at, created_at, done_at, remind_task_id)
 * </pre>
 */
public final class SqliteS3Store implements S3Store {

    public static final String DB_PROD = "s3_tasks.db";
    public static final String DB_SELFTEST = "s3_tasks_selftest.db";
    private static final int DB_VERSION = 1;
    private static final Map<String, SqliteS3Store> sInstances = new HashMap<String, SqliteS3Store>();

    public static synchronized SqliteS3Store get(Context ctx, String name) {
        SqliteS3Store s = sInstances.get(name);
        if (s == null) {
            s = new SqliteS3Store(ctx.getApplicationContext(), name);
            sInstances.put(name, s);
        }
        return s;
    }

    private final SQLiteOpenHelper mHelper;

    private SqliteS3Store(Context ctx, String name) {
        mHelper = new SQLiteOpenHelper(ctx, name, null, DB_VERSION) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS task (id INTEGER PRIMARY KEY AUTOINCREMENT, kind INTEGER, repeat INTEGER, "
                        + "week_mask INTEGER, hour INTEGER, minute INTEGER, once_at INTEGER, title TEXT, command TEXT, next_at INTEGER, "
                        + "last_fired_at INTEGER DEFAULT 0, last_missed_at INTEGER DEFAULT 0, last_result TEXT, enabled INTEGER DEFAULT 1, "
                        + "created_at INTEGER, todo_id INTEGER DEFAULT 0)");
                db.execSQL("CREATE TABLE IF NOT EXISTS todo (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, due_at INTEGER DEFAULT 0, "
                        + "created_at INTEGER, done_at INTEGER DEFAULT 0, remind_task_id INTEGER DEFAULT 0)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_todo_open ON todo(done_at, due_at)");
            }

            @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
            }
        };
    }

    private static final String TCOLS = "id, kind, repeat, week_mask, hour, minute, once_at, title, command, next_at, last_fired_at, "
            + "last_missed_at, last_result, enabled, created_at, todo_id";

    private static Task readTask(Cursor c) {
        Task t = new Task();
        t.id = c.getLong(0);
        t.kind = c.getInt(1);
        t.repeat = c.getInt(2);
        t.weekMask = c.getInt(3);
        t.hour = c.getInt(4);
        t.minute = c.getInt(5);
        t.onceAt = c.getLong(6);
        t.title = nz(c.getString(7));
        t.command = nz(c.getString(8));
        t.nextAt = c.getLong(9);
        t.lastFiredAt = c.getLong(10);
        t.lastMissedAt = c.getLong(11);
        t.lastResult = nz(c.getString(12));
        t.enabled = c.getInt(13) != 0;
        t.createdAt = c.getLong(14);
        t.todoId = c.getLong(15);
        return t;
    }

    private static ContentValues cv(Task t) {
        ContentValues v = new ContentValues();
        v.put("kind", t.kind);
        v.put("repeat", t.repeat);
        v.put("week_mask", t.weekMask);
        v.put("hour", t.hour);
        v.put("minute", t.minute);
        v.put("once_at", t.onceAt);
        v.put("title", t.title);
        v.put("command", t.command);
        v.put("next_at", t.nextAt);
        v.put("last_fired_at", t.lastFiredAt);
        v.put("last_missed_at", t.lastMissedAt);
        v.put("last_result", t.lastResult);
        v.put("enabled", t.enabled ? 1 : 0);
        v.put("created_at", t.createdAt);
        v.put("todo_id", t.todoId);
        return v;
    }

    private static Todo readTodo(Cursor c) {
        Todo t = new Todo();
        t.id = c.getLong(0);
        t.text = nz(c.getString(1));
        t.dueAt = c.getLong(2);
        t.createdAt = c.getLong(3);
        t.doneAt = c.getLong(4);
        t.remindTaskId = c.getLong(5);
        return t;
    }

    private static ContentValues cv(Todo t) {
        ContentValues v = new ContentValues();
        v.put("text", t.text);
        v.put("due_at", t.dueAt);
        v.put("created_at", t.createdAt);
        v.put("done_at", t.doneAt);
        v.put("remind_task_id", t.remindTaskId);
        return v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override public synchronized long addTask(Task t) {
        t.id = mHelper.getWritableDatabase().insert("task", null, cv(t));
        return t.id;
    }

    @Override public synchronized void updateTask(Task t) {
        mHelper.getWritableDatabase().update("task", cv(t), "id=?", new String[] {String.valueOf(t.id)});
    }

    @Override public synchronized boolean deleteTask(long id) {
        return mHelper.getWritableDatabase().delete("task", "id=?", new String[] {String.valueOf(id)}) > 0;
    }

    @Override public synchronized Task task(long id) {
        Cursor c = mHelper.getReadableDatabase().rawQuery("SELECT " + TCOLS + " FROM task WHERE id=?", new String[] {String.valueOf(id)});
        try {
            return c.moveToFirst() ? readTask(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<Task> tasks() {
        List<Task> l = new ArrayList<Task>();
        Cursor c = mHelper.getReadableDatabase().rawQuery("SELECT " + TCOLS + " FROM task ORDER BY id", null);
        try {
            while (c.moveToNext()) l.add(readTask(c));
        } finally {
            c.close();
        }
        return l;
    }

    @Override public synchronized long addTodo(Todo t) {
        t.id = mHelper.getWritableDatabase().insert("todo", null, cv(t));
        return t.id;
    }

    @Override public synchronized void updateTodo(Todo t) {
        mHelper.getWritableDatabase().update("todo", cv(t), "id=?", new String[] {String.valueOf(t.id)});
    }

    @Override public synchronized Todo todo(long id) {
        Cursor c = mHelper.getReadableDatabase().rawQuery("SELECT id, text, due_at, created_at, done_at, remind_task_id FROM todo WHERE id=?",
                new String[] {String.valueOf(id)});
        try {
            return c.moveToFirst() ? readTodo(c) : null;
        } finally {
            c.close();
        }
    }

    @Override public synchronized List<Todo> openTodos() {
        List<Todo> l = new ArrayList<Todo>();
        Cursor c = mHelper.getReadableDatabase().rawQuery(
                "SELECT id, text, due_at, created_at, done_at, remind_task_id FROM todo WHERE done_at=0", null);
        try {
            while (c.moveToNext()) l.add(readTodo(c));
        } finally {
            c.close();
        }
        Collections.sort(l, TODO_ORDER);
        return l;
    }

    @Override public synchronized void clear() {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        db.delete("task", null, null);
        db.delete("todo", null, null);
    }
}
