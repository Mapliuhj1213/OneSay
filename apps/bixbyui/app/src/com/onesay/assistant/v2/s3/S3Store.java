package com.onesay.assistant.v2.s3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [s3] 定时任务 + 待办的存储接口（生产 {@link SqliteS3Store}：独立库 s3_tasks.db，风格同 s2_memory.db；JVM 单测 {@link Mem}）。
 */
public interface S3Store {

    /** 一条定时任务。 */
    final class Task {
        public long id;
        /** {@link S3Parser#KIND_REMINDER} / {@link S3Parser#KIND_AGENT}。 */
        public int kind;
        public int repeat;
        public int weekMask;
        public int hour, minute;
        public long onceAt;
        public String title = "";
        public String command = "";
        /** 下一次计划触发（-1 = 没有下一次 / 已结束）。 */
        public long nextAt = -1L;
        public long lastFiredAt;
        /** 最近一次被判为「错过」的计划时刻（0 = 没有）。 */
        public long lastMissedAt;
        public String lastResult = "";
        public boolean enabled = true;
        public long createdAt;
        /** 由待办截止时间派生的提醒：对应的待办 id（0 = 不是）。 */
        public long todoId;

        public Task copy() {
            Task t = new Task();
            t.id = id; t.kind = kind; t.repeat = repeat; t.weekMask = weekMask; t.hour = hour; t.minute = minute;
            t.onceAt = onceAt; t.title = title; t.command = command; t.nextAt = nextAt; t.lastFiredAt = lastFiredAt;
            t.lastMissedAt = lastMissedAt; t.lastResult = lastResult; t.enabled = enabled; t.createdAt = createdAt; t.todoId = todoId;
            return t;
        }

        public String label() {
            return kind == S3Parser.KIND_AGENT ? command : title;
        }
    }

    /** 一条待办。 */
    final class Todo {
        public long id;
        public String text = "";
        public long dueAt;
        public long createdAt;
        public long doneAt;
        public long remindTaskId;

        public Todo copy() {
            Todo t = new Todo();
            t.id = id; t.text = text; t.dueAt = dueAt; t.createdAt = createdAt; t.doneAt = doneAt; t.remindTaskId = remindTaskId;
            return t;
        }
    }

    long addTask(Task t);

    void updateTask(Task t);

    boolean deleteTask(long id);

    Task task(long id);

    List<Task> tasks();

    long addTodo(Todo t);

    void updateTodo(Todo t);

    Todo todo(long id);

    /** 未完成的待办，按截止从近到远（无截止的排最后，按创建先后）。 */
    List<Todo> openTodos();

    void clear();

    /** 排序规则（两种实现共用）。 */
    Comparator<Todo> TODO_ORDER = new Comparator<Todo>() {
        @Override
        public int compare(Todo a, Todo b) {
            long da = a.dueAt > 0 ? a.dueAt : Long.MAX_VALUE, db = b.dueAt > 0 ? b.dueAt : Long.MAX_VALUE;
            if (da != db) return da < db ? -1 : 1;
            return a.createdAt == b.createdAt ? (a.id < b.id ? -1 : a.id == b.id ? 0 : 1) : (a.createdAt < b.createdAt ? -1 : 1);
        }
    };

    /** 内存实现（JVM 单测 / 自测）。 */
    final class Mem implements S3Store {
        private final Map<Long, Task> mTasks = new LinkedHashMap<Long, Task>();
        private final Map<Long, Todo> mTodos = new LinkedHashMap<Long, Todo>();
        private long mSeq;

        @Override public synchronized long addTask(Task t) {
            t.id = ++mSeq;
            mTasks.put(t.id, t.copy());
            return t.id;
        }

        @Override public synchronized void updateTask(Task t) {
            if (mTasks.containsKey(t.id)) mTasks.put(t.id, t.copy());
        }

        @Override public synchronized boolean deleteTask(long id) {
            return mTasks.remove(id) != null;
        }

        @Override public synchronized Task task(long id) {
            Task t = mTasks.get(id);
            return t == null ? null : t.copy();
        }

        @Override public synchronized List<Task> tasks() {
            List<Task> l = new ArrayList<Task>();
            for (Task t : mTasks.values()) l.add(t.copy());
            return l;
        }

        @Override public synchronized long addTodo(Todo t) {
            t.id = ++mSeq;
            mTodos.put(t.id, t.copy());
            return t.id;
        }

        @Override public synchronized void updateTodo(Todo t) {
            if (mTodos.containsKey(t.id)) mTodos.put(t.id, t.copy());
        }

        @Override public synchronized Todo todo(long id) {
            Todo t = mTodos.get(id);
            return t == null ? null : t.copy();
        }

        @Override public synchronized List<Todo> openTodos() {
            List<Todo> l = new ArrayList<Todo>();
            for (Todo t : mTodos.values()) if (t.doneAt == 0) l.add(t.copy());
            Collections.sort(l, TODO_ORDER);
            return l;
        }

        @Override public synchronized void clear() {
            mTasks.clear();
            mTodos.clear();
        }
    }
}
