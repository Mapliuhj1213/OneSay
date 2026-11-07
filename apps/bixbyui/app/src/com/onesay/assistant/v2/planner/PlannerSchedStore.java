package com.onesay.assistant.v2.planner;

import com.onesay.assistant.v2.sched.SchedEvent;
import com.onesay.assistant.v2.sched.SchedMigrations;
import com.onesay.assistant.v2.sched.SchedStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** [planner] 日程存进计划 App（events 表）。软删 = deleted_at &gt; 0（计划 App 界面不显示，7 天后清）。 */
public final class PlannerSchedStore implements SchedStore {

    final PlannerBackend b;

    public PlannerSchedStore(PlannerBackend b) {
        this.b = b;
    }

    @Override public long insert(SchedEvent e) {
        long id = b.insert(PlannerContract.T_EVENTS, PlannerMap.fromEvent(e));
        e.id = id;
        return id;
    }

    @Override public boolean update(SchedEvent e) {
        return b.update(PlannerContract.T_EVENTS, e.id, PlannerMap.fromEvent(e)) > 0;
    }

    @Override public SchedEvent get(long id) {
        Map<String, Object> m = b.get(PlannerContract.T_EVENTS, id);
        return m == null ? null : PlannerMap.toEvent(m);
    }

    @Override public List<SchedEvent> all(boolean includeDeleted) {
        List<SchedEvent> l = new ArrayList<SchedEvent>();
        for (Map<String, Object> m : b.list(PlannerContract.T_EVENTS)) {
            SchedEvent e = PlannerMap.toEvent(m);
            if (includeDeleted || !e.deleted) l.add(e);
        }
        Collections.sort(l, new Comparator<SchedEvent>() {
            @Override public int compare(SchedEvent x, SchedEvent y) {
                return x.startAt < y.startAt ? -1 : x.startAt > y.startAt ? 1 : (x.id < y.id ? -1 : x.id > y.id ? 1 : 0);
            }
        });
        return l;
    }

    @Override public boolean setDeleted(long id, boolean deleted, long at) {
        java.util.Map<String, Object> v = new java.util.LinkedHashMap<String, Object>();
        v.put("deleted_at", deleted ? Math.max(1L, at) : 0L);
        return b.update(PlannerContract.T_EVENTS, id, v) > 0;
    }

    @Override public int purgeDeleted(long before) {
        int n = 0;
        for (SchedEvent e : all(true)) if (e.deleted && e.deletedAt < before) n += b.delete(PlannerContract.T_EVENTS, e.id);
        return n;
    }

    @Override public SchedEvent byExtId(String extId) {
        if (extId == null || extId.length() == 0) return null;
        for (SchedEvent e : all(true)) if (extId.equals(e.extId)) return e;
        return null;
    }

    @Override public int schemaVersion() {
        return SchedMigrations.LATEST;
    }
}
