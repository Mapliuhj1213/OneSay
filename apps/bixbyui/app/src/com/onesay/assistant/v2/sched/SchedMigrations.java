package com.onesay.assistant.v2.sched;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [sched] sched.db 的迁移框架（纯 Java，JVM 可测：SchedMigrationsTest）。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>每一步 = 一个版本号 + 一组 SQL，版本号从 1 起连续递增；{@link #LATEST} = 最后一步的版本号 = SQLiteOpenHelper 的 version；</li>
 *   <li>新装：onCreate 从 0 跑到 LATEST；升级：onUpgrade(old, new) 只跑 old 之后的步骤；同一事务里跑完（SQLiteOpenHelper 自己包事务）；</li>
 *   <li><b>只加不改</b>：已发布的步骤永远不改内容，改结构就再加一步（ALTER TABLE / 新表 / 新索引）；</li>
 *   <li>降级（装回旧版本）：不删数据、不抛异常，保留当前结构（旧代码只读它认识的列）。</li>
 * </ul>
 */
public final class SchedMigrations {

    private SchedMigrations() {}

    public static final String DB_NAME = "sched.db";
    public static final String T_EVENT = "sched_event";
    public static final String T_KV = "sched_kv";

    /** 执行 SQL 的最小接口（真机 = SQLiteDatabase.execSQL；单测 = 记录下来）。 */
    public interface Exec {
        void exec(String sql);
    }

    public static final class Step {
        public final int version;
        public final String what;
        public final String[] sql;

        Step(int version, String what, String... sql) {
            this.version = version;
            this.what = what;
            this.sql = sql;
        }
    }

    static final List<Step> STEPS;

    static {
        List<Step> l = new ArrayList<Step>();
        l.add(new Step(1, "日程表",
                "CREATE TABLE IF NOT EXISTS " + T_EVENT + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "title TEXT NOT NULL DEFAULT '',"
                        + "start_at INTEGER NOT NULL,"
                        + "end_at INTEGER NOT NULL DEFAULT 0,"
                        + "all_day INTEGER NOT NULL DEFAULT 0,"
                        + "location TEXT NOT NULL DEFAULT '',"
                        + "note TEXT NOT NULL DEFAULT '',"
                        + "repeat INTEGER NOT NULL DEFAULT 0,"
                        + "week_mask INTEGER NOT NULL DEFAULT 0,"
                        + "month_day INTEGER NOT NULL DEFAULT 0,"
                        + "lead_min INTEGER NOT NULL DEFAULT -1,"
                        + "source TEXT NOT NULL DEFAULT 'voice',"
                        + "created_at INTEGER NOT NULL DEFAULT 0,"
                        + "updated_at INTEGER NOT NULL DEFAULT 0,"
                        + "deleted INTEGER NOT NULL DEFAULT 0,"
                        + "deleted_at INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS idx_sched_start ON " + T_EVENT + "(start_at)"));
        l.add(new Step(2, "农历重复 + 导入来源",
                "ALTER TABLE " + T_EVENT + " ADD COLUMN lunar INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + T_EVENT + " ADD COLUMN lunar_month INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + T_EVENT + " ADD COLUMN lunar_day INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + T_EVENT + " ADD COLUMN ext_id TEXT NOT NULL DEFAULT ''",
                "CREATE INDEX IF NOT EXISTS idx_sched_ext ON " + T_EVENT + "(ext_id)"));
        l.add(new Step(3, "键值表（导入水位等）",
                "CREATE TABLE IF NOT EXISTS " + T_KV + " (k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '')"));
        STEPS = Collections.unmodifiableList(l);
    }

    public static final int LATEST = 3;

    public static List<Step> steps() {
        return STEPS;
    }

    /** 从 from 版本升到 to（含）；返回实际跑了几步。from &gt;= to（含降级）什么都不做。 */
    public static int migrate(Exec db, int from, int to) {
        if (from >= to) return 0;
        int n = 0;
        for (Step s : STEPS) {
            if (s.version <= from || s.version > to) continue;
            for (String q : s.sql) db.exec(q);
            n++;
        }
        return n;
    }
}
