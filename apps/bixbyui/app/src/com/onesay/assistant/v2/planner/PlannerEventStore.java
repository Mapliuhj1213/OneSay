package com.onesay.assistant.v2.planner;

import com.onesay.assistant.v2.events.EventStore;
import com.onesay.assistant.v2.events.EventTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [planner] 事件触发任务存进计划 App（automations，mode=trigger）。检测与执行仍在 OneSay（EventEngine）。
 * 计划 App 里 OneSay 还检测不了的触发器（sms / clip / screenshot …）不返回给 EventEngine，只在计划 App 里「先存着」。
 */
public final class PlannerEventStore implements EventStore {

    final PlannerBackend b;

    public PlannerEventStore(PlannerBackend b) {
        this.b = b;
    }

    @Override public long add(EventTask t) {
        t.id = b.insert(PlannerContract.T_AUTOMATIONS, PlannerMap.fromEventTask(t));
        return t.id;
    }

    @Override public void update(EventTask t) {
        Map<String, Object> old = b.get(PlannerContract.T_AUTOMATIONS, t.id);
        b.update(PlannerContract.T_AUTOMATIONS, t.id, PlannerMap.fromEventTask(t));
        if (old != null && t.lastFireAt > 0 && t.lastFireAt != PlannerMap.l(old, "last_run_at")) {
            Map<String, Object> run = new java.util.LinkedHashMap<String, Object>();
            run.put("automation_id", t.id);
            run.put("started_at", t.lastFireAt);
            run.put("status", t.paused ? "fail" : (t.failures > 0 ? "fail" : "ok"));
            run.put("result", t.paused ? ("已暂停：" + t.pausedReason) : "");
            run.put("trigger_info", t.describeTrigger());
            try {
                b.logRun(run);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override public boolean delete(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_AUTOMATIONS, id);
        if (m == null || PlannerMap.toEventTask(m) == null) return false;
        return b.delete(PlannerContract.T_AUTOMATIONS, id) > 0;
    }

    @Override public EventTask get(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_AUTOMATIONS, id);
        return m == null ? null : PlannerMap.toEventTask(m);
    }

    @Override public List<EventTask> all() {
        List<EventTask> l = new ArrayList<EventTask>();
        for (Map<String, Object> m : b.list(PlannerContract.T_AUTOMATIONS)) {
            EventTask t = PlannerMap.toEventTask(m);
            if (t != null) l.add(t);
        }
        return l;
    }
}
