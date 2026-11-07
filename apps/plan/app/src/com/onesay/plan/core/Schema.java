package com.onesay.plan.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各表的结构（纯 Java）：列名、类型、是否必填、外部可写与否。建表 SQL 与字段校验（{@link Validator}）都从这里出。
 * 改结构：在列表末尾加列 + {@link Contract#SCHEMA_VERSION} 加一 + PlanDb.onUpgrade 补 ALTER。
 */
public final class Schema {
    private Schema() {}

    public static final int INT = 1, TEXT = 2;

    /** 一列：名字、类型（INT/TEXT）、约束。 */
    public static final class Col {
        public final String name;
        public final int type;
        public final boolean required;
        /** false = 外部（后门）不能写，只能由计划 App 自己维护（如 qr_path）。 */
        public final boolean writable;
        public final String dflt;
        public final int maxLen;

        Col(String name, int type, boolean required, boolean writable, String dflt, int maxLen) {
            this.name = name; this.type = type; this.required = required; this.writable = writable; this.dflt = dflt; this.maxLen = maxLen;
        }
    }

    /** 一张表的结构；建表 / 建索引 / 升级补列的 SQL 都从这里生成。 */
    public static final class Table {
        public final String name;
        public final List<Col> cols;
        private final Map<String, Col> byName = new LinkedHashMap<String, Col>();

        Table(String name, Col... own) {
            this.name = name;
            List<Col> l = new ArrayList<Col>();
            l.add(new Col(Contract.C_ID, INT, false, false, null, 0));
            l.addAll(Arrays.asList(own));
            l.add(new Col(Contract.C_NOTE, TEXT, false, true, "''", 2000));
            l.add(new Col(Contract.C_SOURCE, TEXT, false, true, "'ui'", 32));
            l.add(new Col(Contract.C_SRC_KEY, TEXT, false, true, null, 200));
            l.add(new Col(Contract.C_CREATED, INT, false, false, "0", 0));
            l.add(new Col(Contract.C_UPDATED, INT, false, false, "0", 0));
            this.cols = Collections.unmodifiableList(l);
            for (Col c : l) byName.put(c.name, c);
        }

        /** 按名取列，没有返回 null。 */
        public Col col(String n) { return byName.get(n); }

        /** 全部列名（声明顺序）。 */
        public String[] columnNames() {
            String[] a = new String[cols.size()];
            for (int i = 0; i < a.length; i++) a[i] = cols.get(i).name;
            return a;
        }

        /** CREATE TABLE IF NOT EXISTS 语句。 */
        public String createSql() {
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
            for (int i = 0; i < cols.size(); i++) {
                Col c = cols.get(i);
                if (i > 0) sb.append(", ");
                sb.append(c.name).append(' ');
                if (c.name.equals(Contract.C_ID)) { sb.append("INTEGER PRIMARY KEY AUTOINCREMENT"); continue; }
                sb.append(c.type == INT ? "INTEGER" : "TEXT");
                if (c.dflt != null) sb.append(" DEFAULT ").append(c.dflt);
            }
            return sb.append(")").toString();
        }

        /** 升级补列：已有库缺的列逐个 ALTER TABLE ADD COLUMN（带默认值；幂等）。 */
        public List<String> addMissingSql(java.util.Set<String> have) {
            List<String> l = new ArrayList<String>();
            for (Col c : cols) {
                if (have.contains(c.name) || c.name.equals(Contract.C_ID)) continue;
                l.add("ALTER TABLE " + name + " ADD COLUMN " + c.name + " " + (c.type == INT ? "INTEGER" : "TEXT") + (c.dflt != null ? " DEFAULT " + c.dflt : ""));
            }
            return l;
        }

        /** 索引语句（幂等）。 */
        public String[] indexSql() {
            List<String> l = new ArrayList<String>();
            l.add("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + name + "_src ON " + name + "(src_key)");
            if (name.equals(Contract.T_RUNS)) l.add("CREATE INDEX IF NOT EXISTS ix_runs_auto ON runs(automation_id, started_at)");
            if (name.equals(Contract.T_EVENTS)) l.add("CREATE INDEX IF NOT EXISTS ix_events_start ON events(start_at)");
            if (name.equals(Contract.T_SHORTCUT_RUNS)) l.add("CREATE INDEX IF NOT EXISTS ix_scruns_sc ON shortcut_runs(shortcut_id, started_at)");
            return l.toArray(new String[0]);
        }
    }

    static Col req(String n, int t, int max) { return new Col(n, t, true, true, null, max); }
    static Col opt(String n, int t, String d, int max) { return new Col(n, t, false, true, d, max); }
    static Col ro(String n, int t, String d) { return new Col(n, t, false, false, d, 0); }

    public static final Table EVENTS = new Table(Contract.T_EVENTS,
            req("title", TEXT, 200), req("start_at", INT, 0), opt("end_at", INT, "0", 0), opt("all_day", INT, "0", 0),
            opt("location", TEXT, "''", 200), opt("repeat", INT, "0", 0), opt("week_mask", INT, "0", 0), opt("month_day", INT, "0", 0),
            opt("lunar", INT, "0", 0), opt("lunar_month", INT, "0", 0), opt("lunar_day", INT, "0", 0), opt("lead_min", INT, "-1", 0),
            opt("ext_id", TEXT, "''", 200), opt("deleted_at", INT, "0", 0));

    public static final Table TODOS = new Table(Contract.T_TODOS,
            req("title", TEXT, 200), opt("due_at", INT, "0", 0), opt("done_at", INT, "0", 0), opt("remind", INT, "1", 0),
            opt("priority", INT, "0", 0), opt("list", TEXT, "''", 40));

    public static final Table TICKETS = new Table(Contract.T_TICKETS,
            req("kind", TEXT, 16), req("title", TEXT, 200), opt("number", TEXT, "''", 40), opt("from_place", TEXT, "''", 80),
            opt("to_place", TEXT, "''", 80), req("depart_at", INT, 0), opt("arrive_at", INT, "0", 0), opt("venue", TEXT, "''", 120),
            opt("seat", TEXT, "''", 80), opt("gate", TEXT, "''", 40), opt("passenger", TEXT, "''", 60), opt("order_no", TEXT, "''", 60),
            opt("qr_text", TEXT, "''", 2000), ro("qr_path", TEXT, "''"), opt("status", INT, "0", 0), opt("remind_lead_min", INT, "120", 0));

    public static final Table PICKUPS = new Table(Contract.T_PICKUPS,
            req("code", TEXT, 40), opt("kind", TEXT, "'取件码'", 16), opt("station", TEXT, "''", 120), opt("carrier", TEXT, "''", 40),
            opt("app_pkg", TEXT, "''", 120), opt("deadline_at", INT, "0", 0), opt("taken_at", INT, "0", 0), opt("remind", INT, "1", 0));

    public static final Table AUTOMATIONS = new Table(Contract.T_AUTOMATIONS,
            req("title", TEXT, 200), req("mode", TEXT, 16), req("trigger_type", TEXT, 32), opt("trigger_json", TEXT, "'{}'", 4000),
            req("action_type", TEXT, 16), opt("action", TEXT, "''", 2000), opt("enabled", INT, "1", 0),
            opt("repeat", INT, "0", 0), opt("week_mask", INT, "0", 0), opt("hour", INT, "0", 0), opt("minute", INT, "0", 0),
            opt("once_at", INT, "0", 0), opt("next_at", INT, "-1", 0), opt("guard_json", TEXT, "'{}'", 2000),
            opt("state_json", TEXT, "'{}'", 4000), opt("last_run_at", INT, "0", 0), opt("last_result", TEXT, "''", 2000),
            opt("last_missed_at", INT, "0", 0), opt("todo_id", INT, "0", 0), opt("owner", TEXT, "'bixbyui'", 40));

    public static final Table RUNS = new Table(Contract.T_RUNS,
            req("automation_id", INT, 0), req("started_at", INT, 0), opt("ended_at", INT, "0", 0), req("status", TEXT, 16),
            opt("result", TEXT, "''", 2000), opt("trigger_info", TEXT, "''", 500));

    /** 快捷指令定义。steps_json / triggers_json / history_json / aliases_json 的格式见 {@link Shortcuts} 与 docs/SHORTCUTS.md。 */
    public static final Table SHORTCUTS = new Table(Contract.T_SHORTCUTS,
            req("name", TEXT, 40), opt("aliases_json", TEXT, "'[]'", 2000), req("steps_json", TEXT, 8000),
            opt("triggers_json", TEXT, "'" + Shortcuts.DEFAULT_TRIGGERS + "'", 500), opt("enabled", INT, "1", 0),
            opt("version", INT, "1", 0), opt("history_json", TEXT, "'[]'", 16000), opt("run_seq", INT, "0", 0),
            opt("wrong_streak", INT, "0", 0), opt("last_wrong_seq", INT, "0", 0), opt("flagged", INT, "0", 0),
            opt("flag_reason", TEXT, "''", 200), opt("last_run_at", INT, "0", 0), opt("last_status", TEXT, "''", 16),
            opt("last_result", TEXT, "''", 500), opt("origin", TEXT, "'ui'", 16));

    /** 快捷指令执行记录：只存工具名与每步状态、脱敏后的结果，不存参数（参数里可能有号码、正文、密钥）。 */
    public static final Table SHORTCUT_RUNS = new Table(Contract.T_SHORTCUT_RUNS,
            req("shortcut_id", INT, 0), req("started_at", INT, 0), opt("ended_at", INT, "0", 0), req("status", TEXT, 16),
            opt("mode", TEXT, "'shortcut'", 16), opt("trigger_info", TEXT, "''", 32), opt("steps_summary", TEXT, "''", 1000),
            opt("result", TEXT, "''", 500), opt("failed_step", INT, "-1", 0), opt("updated", INT, "0", 0), opt("run_seq", INT, "0", 0));

    public static final Table[] ALL = {EVENTS, TODOS, TICKETS, PICKUPS, AUTOMATIONS, RUNS, SHORTCUTS, SHORTCUT_RUNS};

    /** 按表名取结构；不认识的表返回 null。 */
    public static Table table(String name) {
        for (Table t : ALL) if (t.name.equals(name)) return t;
        return null;
    }

    /** 每个自动任务最多留多少条运行记录（插入后裁掉更旧的）。 */
    public static final int RUNS_KEEP = 50;
    /** 每条快捷指令最多留多少条执行记录。 */
    public static final int SHORTCUT_RUNS_KEEP = 30;
}
