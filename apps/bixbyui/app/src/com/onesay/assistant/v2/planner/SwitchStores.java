package com.onesay.assistant.v2.planner;

import com.onesay.assistant.v2.events.EventStore;
import com.onesay.assistant.v2.events.EventTask;
import com.onesay.assistant.v2.s3.S3Store;
import com.onesay.assistant.v2.sched.SchedEvent;
import com.onesay.assistant.v2.sched.SchedStore;

import java.util.List;

/**
 * [planner] 三个「会切换」的存储：计划 App 在 → 读写计划 App（唯一真相源）；不在 / 出错 → 回落 OneSay 原来的本地库（功能不丢）。
 * 引擎（SchedEngine / S3Engine / EventEngine）拿到的就是这里的对象，自己不知道数据在哪。
 * 本地库只在计划 App 不在时才写；计划 App 回来以后，{@link PlannerMigration} 会把这期间本地新增的（按 src_key 去重）补导过去。
 */
public final class SwitchStores {
    private SwitchStores() {}

    interface Op<T> {
        T run(boolean planner);
    }

    static <T> T go(PlannerGate g, Op<T> op) {
        if (g.on()) {
            try {
                return op.run(true);
            } catch (RuntimeException e) {
                g.failed(e);
            }
        }
        return op.run(false);
    }

    public static final class Sched implements SchedStore {
        final SchedStore local, planner;
        final PlannerGate g;

        public Sched(SchedStore local, SchedStore planner, PlannerGate g) {
            this.local = local; this.planner = planner; this.g = g;
        }

        SchedStore s(boolean p) { return p ? planner : local; }

        @Override public long insert(final SchedEvent e) {
            return go(g, new Op<Long>() { @Override public Long run(boolean p) { return s(p).insert(e); } });
        }

        @Override public boolean update(final SchedEvent e) {
            return go(g, new Op<Boolean>() { @Override public Boolean run(boolean p) { return s(p).update(e); } });
        }

        @Override public SchedEvent get(final long id) {
            return go(g, new Op<SchedEvent>() { @Override public SchedEvent run(boolean p) { return s(p).get(id); } });
        }

        @Override public List<SchedEvent> all(final boolean inc) {
            return go(g, new Op<List<SchedEvent>>() { @Override public List<SchedEvent> run(boolean p) { return s(p).all(inc); } });
        }

        @Override public boolean setDeleted(final long id, final boolean d, final long at) {
            return go(g, new Op<Boolean>() { @Override public Boolean run(boolean p) { return s(p).setDeleted(id, d, at); } });
        }

        @Override public int purgeDeleted(final long before) {
            return go(g, new Op<Integer>() { @Override public Integer run(boolean p) { return s(p).purgeDeleted(before); } });
        }

        @Override public SchedEvent byExtId(final String x) {
            return go(g, new Op<SchedEvent>() { @Override public SchedEvent run(boolean p) { return s(p).byExtId(x); } });
        }

        @Override public int schemaVersion() {
            return local.schemaVersion();
        }
    }

    public static final class S3 implements S3Store {
        final S3Store local, planner;
        final PlannerGate g;

        public S3(S3Store local, S3Store planner, PlannerGate g) {
            this.local = local; this.planner = planner; this.g = g;
        }

        S3Store s(boolean p) { return p ? planner : local; }

        @Override public long addTask(final Task t) {
            return go(g, new Op<Long>() { @Override public Long run(boolean p) { return s(p).addTask(t); } });
        }

        @Override public void updateTask(final Task t) {
            go(g, new Op<Void>() { @Override public Void run(boolean p) { s(p).updateTask(t); return null; } });
        }

        @Override public boolean deleteTask(final long id) {
            return go(g, new Op<Boolean>() { @Override public Boolean run(boolean p) { return s(p).deleteTask(id); } });
        }

        @Override public Task task(final long id) {
            return go(g, new Op<Task>() { @Override public Task run(boolean p) { return s(p).task(id); } });
        }

        @Override public List<Task> tasks() {
            return go(g, new Op<List<Task>>() { @Override public List<Task> run(boolean p) { return s(p).tasks(); } });
        }

        @Override public long addTodo(final Todo t) {
            return go(g, new Op<Long>() { @Override public Long run(boolean p) { return s(p).addTodo(t); } });
        }

        @Override public void updateTodo(final Todo t) {
            go(g, new Op<Void>() { @Override public Void run(boolean p) { s(p).updateTodo(t); return null; } });
        }

        @Override public Todo todo(final long id) {
            return go(g, new Op<Todo>() { @Override public Todo run(boolean p) { return s(p).todo(id); } });
        }

        @Override public List<Todo> openTodos() {
            return go(g, new Op<List<Todo>>() { @Override public List<Todo> run(boolean p) { return s(p).openTodos(); } });
        }

        @Override public void clear() {
            go(g, new Op<Void>() { @Override public Void run(boolean p) { s(p).clear(); return null; } });
        }
    }

    public static final class Events implements EventStore {
        final EventStore local, planner;
        final PlannerGate g;

        public Events(EventStore local, EventStore planner, PlannerGate g) {
            this.local = local; this.planner = planner; this.g = g;
        }

        EventStore s(boolean p) { return p ? planner : local; }

        @Override public long add(final EventTask t) {
            return go(g, new Op<Long>() { @Override public Long run(boolean p) { return s(p).add(t); } });
        }

        @Override public void update(final EventTask t) {
            go(g, new Op<Void>() { @Override public Void run(boolean p) { s(p).update(t); return null; } });
        }

        @Override public boolean delete(final long id) {
            return go(g, new Op<Boolean>() { @Override public Boolean run(boolean p) { return s(p).delete(id); } });
        }

        @Override public EventTask get(final long id) {
            return go(g, new Op<EventTask>() { @Override public EventTask run(boolean p) { return s(p).get(id); } });
        }

        @Override public List<EventTask> all() {
            return go(g, new Op<List<EventTask>>() { @Override public List<EventTask> run(boolean p) { return s(p).all(); } });
        }
    }
}
