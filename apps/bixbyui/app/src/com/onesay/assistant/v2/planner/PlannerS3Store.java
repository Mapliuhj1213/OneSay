package com.onesay.assistant.v2.planner;

import com.onesay.assistant.v2.s3.S3Store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [planner] S3 定时任务 + 待办存进计划 App：任务 → automations（mode=time），待办 → todos。
 * <b>执行仍在 OneSay</b>：S3Engine / S3Runner 照旧读这里的定义自己排闹钟；每次触发（lastFiredAt 变了）顺手写一条运行记录（runs）。
 * 计划 App 里 action_type=builtin 的定时任务不归 S3 管（不返回）。
 */
public final class PlannerS3Store implements S3Store {

    final PlannerBackend b;

    public PlannerS3Store(PlannerBackend b) {
        this.b = b;
    }

    @Override public long addTask(Task t) {
        t.id = b.insert(PlannerContract.T_AUTOMATIONS, PlannerMap.fromTask(t));
        return t.id;
    }

    @Override public void updateTask(Task t) {
        Map<String, Object> old = b.get(PlannerContract.T_AUTOMATIONS, t.id);
        b.update(PlannerContract.T_AUTOMATIONS, t.id, PlannerMap.fromTask(t));
        if (old != null && t.lastFiredAt > 0 && t.lastFiredAt != PlannerMap.l(old, "last_run_at")) {
            Map<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("automation_id", t.id);
            run.put("started_at", t.lastFiredAt);
            run.put("status", status(t.lastResult));
            run.put("result", t.lastResult == null ? "" : (t.lastResult.length() > 500 ? t.lastResult.substring(0, 500) : t.lastResult));
            run.put("trigger_info", "到点");
            try {
                b.logRun(run);
            } catch (RuntimeException ignored) {
                // 运行记录写不进不影响任务本身
            }
        }
    }

    /** lastResult 是给人看的一句话，按关键词粗分成功 / 失败 / 错过 / 跳过。 */
    static String status(String r) {
        if (r == null) return "ok";
        if (r.contains("错过")) return "missed";
        if (r.contains("失败") || r.contains("出错") || r.contains("没做成") || r.contains("拒绝")) return "fail";
        if (r.contains("跳过") || r.contains("暂停")) return "skip";
        return "ok";
    }

    @Override public boolean deleteTask(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_AUTOMATIONS, id);
        if (m == null || !PlannerMap.isS3Task(m)) return false;
        return b.delete(PlannerContract.T_AUTOMATIONS, id) > 0;
    }

    @Override public Task task(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_AUTOMATIONS, id);
        return m == null || !PlannerMap.isS3Task(m) ? null : PlannerMap.toTask(m);
    }

    @Override public List<Task> tasks() {
        List<Task> l = new ArrayList<Task>();
        for (Map<String, Object> m : b.list(PlannerContract.T_AUTOMATIONS)) if (PlannerMap.isS3Task(m)) l.add(PlannerMap.toTask(m));
        return l;
    }

    @Override public long addTodo(Todo t) {
        t.id = b.insert(PlannerContract.T_TODOS, PlannerMap.fromTodo(t));
        return t.id;
    }

    @Override public void updateTodo(Todo t) {
        b.update(PlannerContract.T_TODOS, t.id, PlannerMap.fromTodo(t));
    }

    @Override public Todo todo(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_TODOS, id);
        if (m == null) return null;
        Todo t = PlannerMap.toTodo(m);
        t.remindTaskId = remindTaskFor(id);
        return t;
    }

    long remindTaskFor(long todoId) {
        for (Map<String, Object> m : b.list(PlannerContract.T_AUTOMATIONS)) if (PlannerMap.l(m, "todo_id") == todoId) return PlannerMap.l(m, "_id");
        return 0;
    }

    @Override public List<Todo> openTodos() {
        Map<Long, Long> remind = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> m : b.list(PlannerContract.T_AUTOMATIONS)) {
            long tid = PlannerMap.l(m, "todo_id");
            if (tid > 0) remind.put(tid, PlannerMap.l(m, "_id"));
        }
        List<Todo> l = new ArrayList<Todo>();
        for (Map<String, Object> m : b.list(PlannerContract.T_TODOS)) {
            Todo t = PlannerMap.toTodo(m);
            if (t.doneAt != 0) continue;
            Long r = remind.get(t.id);
            t.remindTaskId = r == null ? 0 : r;
            l.add(t);
        }
        Collections.sort(l, TODO_ORDER);
        return l;
    }

    /** 清空只清 S3 管的（定时任务 + 待办），不碰计划 App 里的日程 / 票 / 取件码 / 触发任务。 */
    @Override public void clear() {
        for (Task t : tasks()) b.delete(PlannerContract.T_AUTOMATIONS, t.id);
        for (Map<String, Object> m : b.list(PlannerContract.T_TODOS)) b.delete(PlannerContract.T_TODOS, PlannerMap.l(m, "_id"));
    }
}
