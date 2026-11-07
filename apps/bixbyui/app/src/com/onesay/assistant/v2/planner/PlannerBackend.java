package com.onesay.assistant.v2.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [planner] 读写计划 App 的最小接口（真机 = {@link AndroidPlannerBackend} 经 ContentResolver；JVM 单测 = {@link Mem}）。
 * 每张表数据量很小（几十到几百行），读一律整表读、在 Java 里筛，避免把 SQL 条件散落在 OneSay 里。
 * 出错（计划 App 被卸载、被拒、字段不合法）抛 {@link PlannerException}，上层回落本地存储。
 */
public interface PlannerBackend {

    final class PlannerException extends RuntimeException {
        public PlannerException(String m, Throwable t) { super(m, t); }
        public PlannerException(String m) { super(m); }
    }

    List<Map<String, Object>> list(String table);

    Map<String, Object> get(String table, long id);

    /** 返回新行 _id（src_key 已存在时返回原行 _id）。 */
    long insert(String table, Map<String, Object> values);

    int update(String table, long id, Map<String, Object> values);

    int delete(String table, long id);

    /** 运行记录（log_run）：automation_id / status / result / trigger_info / started_at / ended_at。 */
    long logRun(Map<String, Object> run);

    /** 导入原样导出包，返回计划 App 的结果摘要。 */
    String importBundle(String json);

    /** 内存实现：行为对齐计划 App（自增 _id、src_key 去重、created/updated、只认已知表）。 */
    final class Mem implements PlannerBackend {
        final Map<String, LinkedHashMap<Long, Map<String, Object>>> t = new LinkedHashMap<String, LinkedHashMap<Long, Map<String, Object>>>();
        long seq;
        public boolean down;
        public final List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        public String lastImport;

        public Mem() {
            for (String n : new String[]{PlannerContract.T_EVENTS, PlannerContract.T_TODOS, PlannerContract.T_TICKETS, PlannerContract.T_PICKUPS,
                    PlannerContract.T_AUTOMATIONS, PlannerContract.T_RUNS, PlannerContract.T_SHORTCUTS, PlannerContract.T_SHORTCUT_RUNS}) t.put(n, new LinkedHashMap<Long, Map<String, Object>>());
        }

        void up() {
            if (down) throw new PlannerException("计划 App 不在");
        }

        LinkedHashMap<Long, Map<String, Object>> tab(String n) {
            LinkedHashMap<Long, Map<String, Object>> m = t.get(n);
            if (m == null) throw new PlannerException("未知的表: " + n);
            return m;
        }

        @Override public synchronized List<Map<String, Object>> list(String table) {
            up();
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : tab(table).values()) l.add(new LinkedHashMap<String, Object>(r));
            return l;
        }

        @Override public synchronized Map<String, Object> get(String table, long id) {
            up();
            Map<String, Object> r = tab(table).get(id);
            return r == null ? null : new LinkedHashMap<String, Object>(r);
        }

        @Override public synchronized long insert(String table, Map<String, Object> values) {
            up();
            Object sk = values.get("src_key");
            if (sk != null && String.valueOf(sk).length() > 0)
                for (Map<String, Object> r : tab(table).values()) if (String.valueOf(sk).equals(r.get("src_key"))) return (Long) r.get("_id");
            long id = ++seq;
            Map<String, Object> r = new LinkedHashMap<String, Object>(values);
            r.put("_id", id);
            r.put("created_at", System.currentTimeMillis());
            r.put("updated_at", System.currentTimeMillis());
            tab(table).put(id, r);
            return id;
        }

        @Override public synchronized int update(String table, long id, Map<String, Object> values) {
            up();
            Map<String, Object> r = tab(table).get(id);
            if (r == null) return 0;
            r.putAll(values);
            r.put("updated_at", System.currentTimeMillis());
            return 1;
        }

        @Override public synchronized int delete(String table, long id) {
            up();
            return tab(table).remove(id) == null ? 0 : 1;
        }

        @Override public synchronized long logRun(Map<String, Object> run) {
            up();
            runs.add(new LinkedHashMap<String, Object>(run));
            return insert(PlannerContract.T_RUNS, run);
        }

        @Override public synchronized String importBundle(String json) {
            up();
            lastImport = json;
            return "ok";
        }
    }
}
