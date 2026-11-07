package com.onesay.assistant.v2.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jobs.db 的表结构与迁移框架（纯 Java，JVM 可测；2026-10-24 r2/fixes 6）。
 *
 * <h3>规则（以后改表结构必须遵守）</h3>
 * <ol>
 *   <li><b>只加不删</b>：新版本只能「加表 / 加列 / 加索引」，不许删列、改列名、改列类型。
 *       这样旧版 APK（按显式列清单读写）打开新库照样能用，回滚时可以安全保留数据。</li>
 *   <li>加列：在 {@link #TABLES} 的列清单末尾加列，同时把 {@link #VERSION} 加 1，并在 {@link #MIGRATIONS} 里追加一步
 *       {@link Step#addColumn}（写法见那里的注释）。{@code JobsDbSchemaTest} 会核对「新建库的列 = 迁移后的列 = 代码读写的列」。</li>
 *   <li>每一步迁移都是<b>幂等</b>的（加列前先查 PRAGMA table_info），降级再升级不会因为「列已存在」失败。</li>
 * </ol>
 *
 * <h3>升级 / 降级</h3>
 * <ul>
 *   <li>{@link #upgrade}：按版本逐步执行 {@link #MIGRATIONS}，最后 {@link #ensure} 补齐当前版本该有的表 / 列；
 *       任何一步抛异常 → {@link #rebuildWithBackup}：把旧表改名成 {@code <表>_bak_v<旧版本>_<时间>} 留底，再建新表（数据不删）。</li>
 *   <li>{@link #downgrade}（回滚到旧 APK）：因为只加不删，新库里多出来的列旧代码不读也不写，<b>原样保留数据</b>，
 *       只 {@link #ensure} 补齐旧版本需要的表 / 列；补不上同样走 {@link #rebuildWithBackup}。
 *       SQLiteOpenHelper 默认的 onDowngrade 会直接抛异常，导致 jobs / events 都打不开，所以必须覆盖。</li>
 * </ul>
 */
public final class JobsDbSchema {

    private JobsDbSchema() {}

    /** 当前库版本。加列 / 加表时 +1，并在 {@link #MIGRATIONS} 追加对应步骤。 */
    public static final int VERSION = 1;

    /** 极简数据库抽象（真机由 SQLiteDatabase 适配，JVM 测试用假实现）。 */
    public interface Db {
        void exec(String sql);

        /** 表的现有列名（表不存在返回空集合）。 */
        Set<String> columns(String table);
    }

    /** 一张表：名字 + 有序列定义（列名 → 类型与约束）。 */
    public static final class Table {
        public final String name;
        final LinkedHashMap<String, String> cols = new LinkedHashMap<String, String>();
        final List<String> extra = new ArrayList<String>();

        Table(String name, String... nameTypePairs) {
            this.name = name;
            for (int i = 0; i + 1 < nameTypePairs.length; i += 2) cols.put(nameTypePairs[i], nameTypePairs[i + 1]);
        }

        Table index(String sql) {
            extra.add(sql);
            return this;
        }

        public List<String> columnNames() {
            return new ArrayList<String>(cols.keySet());
        }

        String createSql() {
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
            boolean first = true;
            for (Map.Entry<String, String> e : cols.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
            return sb.append(')').toString();
        }
    }

    /** 当前版本的全部表（列顺序 = 新建库的列顺序）。 */
    static final List<Table> TABLES = Collections.unmodifiableList(Arrays.asList(
            new Table("job",
                    "id", "INTEGER PRIMARY KEY AUTOINCREMENT", "title", "TEXT", "template_id", "TEXT", "command", "TEXT",
                    "repeat", "INTEGER", "week_mask", "INTEGER", "hour", "INTEGER", "minute", "INTEGER", "once_at", "INTEGER",
                    "lead_ms", "INTEGER", "speak", "INTEGER", "enabled", "INTEGER", "next_deliver_at", "INTEGER", "created_at", "INTEGER"),
            new Table("run",
                    "id", "INTEGER PRIMARY KEY AUTOINCREMENT", "owner", "TEXT", "title", "TEXT", "template_id", "TEXT",
                    "command", "TEXT", "state", "TEXT", "attempts", "INTEGER", "max_attempts", "INTEGER", "priority", "INTEGER",
                    "seq", "INTEGER", "planned_at", "INTEGER", "expire_at", "INTEGER", "next_retry_at", "INTEGER",
                    "wait_reason", "TEXT", "result", "TEXT", "error", "TEXT", "progress", "INTEGER", "step", "TEXT",
                    "created_at", "INTEGER", "updated_at", "INTEGER")
                    .index("CREATE INDEX IF NOT EXISTS idx_run_owner ON run(owner, planned_at)"),
            new Table("outbox",
                    "id", "INTEGER PRIMARY KEY AUTOINCREMENT", "run_id", "INTEGER", "idem", "TEXT UNIQUE", "title", "TEXT",
                    "text", "TEXT", "urgency", "TEXT", "want_speak", "INTEGER", "digest_key", "TEXT", "deliver_after", "INTEGER",
                    "expire_at", "INTEGER", "status", "INTEGER", "delivered_via", "TEXT", "delivered_at", "INTEGER",
                    "created_at", "INTEGER", "worth", "INTEGER DEFAULT -1"),
            new Table("grant_",
                    "key", "TEXT PRIMARY KEY", "spec", "TEXT", "required", "TEXT", "granted", "TEXT", "fingerprint", "TEXT",
                    "granted_at", "INTEGER")));

    public static Table table(String name) {
        for (Table t : TABLES) if (t.name.equals(name)) return t;
        return null;
    }

    /** 一步迁移（必须幂等）。 */
    public abstract static class Step {
        abstract void apply(Db db);

        /**
         * 加列（幂等）。用法示例（升到 v2 给 job 加 note 列）：
         * <pre>MIGRATIONS 追加：new Step[]{ Step.addColumn("job", "note", "TEXT") }   // 下标 1 = 1→2</pre>
         * 注意：PRIMARY KEY / UNIQUE 约束的列不能用 ALTER TABLE ADD COLUMN 加，只能加普通列（可带 DEFAULT 常量）。
         */
        public static Step addColumn(final String table, final String column, final String type) {
            return new Step() {
                @Override void apply(Db db) {
                    Set<String> have = db.columns(table);
                    if (have.isEmpty() || have.contains(column)) return;   // 表不在（稍后 ensure 会建全）或已加过
                    db.exec("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                }
            };
        }

        /** 任意幂等 SQL（如 CREATE INDEX IF NOT EXISTS）。 */
        public static Step sql(final String s) {
            return new Step() {
                @Override void apply(Db db) {
                    db.exec(s);
                }
            };
        }
    }

    /**
     * 迁移表：{@code MIGRATIONS[v]} = 从 v 升到 v+1 的步骤（v 从 1 开始；下标 0 不用）。
     * 当前 VERSION=1，没有任何迁移。数组长度必须 = VERSION（测试守住）。
     */
    static final Step[][] MIGRATIONS = {
            /* 0：占位（v1 是第一版，建库走 onCreate） */ new Step[0],
    };

    /** onCreate：建全部表 + 索引。 */
    public static void create(Db db) {
        for (Table t : TABLES) {
            db.exec(t.createSql());
            for (String x : t.extra) db.exec(x);
        }
    }

    /** 补齐当前版本该有的表、列、索引（幂等；只加不删）。 */
    public static void ensure(Db db) {
        for (Table t : TABLES) {
            Set<String> have = db.columns(t.name);
            if (have.isEmpty()) {
                db.exec(t.createSql());
            } else {
                for (Map.Entry<String, String> e : t.cols.entrySet()) {
                    if (have.contains(e.getKey())) continue;
                    String type = e.getValue();
                    if (type.contains("PRIMARY KEY") || type.contains("UNIQUE")) {
                        throw new IllegalStateException("表 " + t.name + " 缺少带约束的列 " + e.getKey() + "，无法就地补齐");
                    }
                    db.exec("ALTER TABLE " + t.name + " ADD COLUMN " + e.getKey() + " " + type);
                }
            }
            for (String x : t.extra) db.exec(x);
        }
    }

    /** onUpgrade：逐版本迁移 → 补齐；出错则备份旧表后重建。@return true = 走了备份重建 */
    public static boolean upgrade(Db db, int from, int to, long now) {
        return upgrade(db, from, to, now, MIGRATIONS);
    }

    static boolean upgrade(Db db, int from, int to, long now, Step[][] migrations) {
        try {
            for (int v = Math.max(1, from); v < to && v < migrations.length; v++) {
                for (Step s : migrations[v]) s.apply(db);
            }
            ensure(db);
            return false;
        } catch (RuntimeException e) {
            rebuildWithBackup(db, from, now);
            return true;
        }
    }

    /** onDowngrade：保留数据（只加不删 → 旧代码能读新库），只补齐旧版本需要的；补不上则备份重建。@return true = 走了备份重建 */
    public static boolean downgrade(Db db, int from, int to, long now) {
        try {
            ensure(db);
            return false;
        } catch (RuntimeException e) {
            rebuildWithBackup(db, from, now);
            return true;
        }
    }

    /** 旧表改名留底（{@code <表>_bak_v<版本>_<时间>}，数据不删），再按当前结构建新表。 */
    public static void rebuildWithBackup(Db db, int fromVersion, long now) {
        for (Table t : TABLES) {
            if (db.columns(t.name).isEmpty()) continue;
            db.exec("ALTER TABLE " + t.name + " RENAME TO " + t.name + "_bak_v" + fromVersion + "_" + now);
        }
        db.exec("DROP INDEX IF EXISTS idx_run_owner");   // 索引名全库唯一：随旧表改名后仍占着名字
        create(db);
    }

    /** 某表在当前版本的列集合（测试核对读写列清单用）。 */
    static Set<String> columnSet(String table) {
        Table t = table(table);
        return t == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(t.cols.keySet());
    }
}
