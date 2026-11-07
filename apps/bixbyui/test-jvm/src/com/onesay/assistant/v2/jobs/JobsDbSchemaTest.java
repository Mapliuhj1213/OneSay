package com.onesay.assistant.v2.jobs;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** r2/fixes 第 6 项：jobs.db 迁移框架（逐版本、幂等、只加不删）+ onDowngrade 保留数据 / 备份重建。 */
public class JobsDbSchemaTest {

    /** 极简假库：解析 CREATE / ALTER ADD / RENAME / DROP，记录列集合与每行数据数。 */
    static final class FakeDb implements JobsDbSchema.Db {
        final Map<String, Set<String>> tables = new LinkedHashMap<String, Set<String>>();
        final Map<String, Integer> rows = new LinkedHashMap<String, Integer>();
        final List<String> log = new ArrayList<String>();
        static final Pattern CREATE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*)\\)");
        static final Pattern ADD = Pattern.compile("ALTER TABLE (\\w+) ADD COLUMN (\\w+) .*");
        static final Pattern RENAME = Pattern.compile("ALTER TABLE (\\w+) RENAME TO (\\w+)");

        @Override public void exec(String sql) {
            log.add(sql);
            Matcher m;
            if ((m = CREATE.matcher(sql)).matches()) {
                if (tables.containsKey(m.group(1))) return;
                Set<String> cols = new LinkedHashSet<String>();
                for (String part : m.group(2).split(",")) cols.add(part.trim().split(" ")[0]);
                tables.put(m.group(1), cols);
                rows.put(m.group(1), 0);
            } else if ((m = ADD.matcher(sql)).matches()) {
                Set<String> cols = tables.get(m.group(1));
                if (cols == null) throw new IllegalStateException("no such table");
                if (!cols.add(m.group(2))) throw new IllegalStateException("duplicate column name: " + m.group(2));
            } else if ((m = RENAME.matcher(sql)).matches()) {
                tables.put(m.group(2), tables.remove(m.group(1)));
                rows.put(m.group(2), rows.remove(m.group(1)));
            } else if (sql.startsWith("DROP TABLE")) {
                throw new AssertionError("迁移框架不许删表：" + sql);
            }
        }

        @Override public Set<String> columns(String table) {
            Set<String> s = tables.get(table);
            return s == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(s);
        }
    }

    static Set<String> cols(String csv) {
        Set<String> s = new LinkedHashSet<String>();
        for (String c : csv.split(",")) s.add(c.trim());
        return s;
    }

    @Test public void createMatchesColumnsCodeReadsAndWrites() {
        FakeDb db = new FakeDb();
        JobsDbSchema.create(db);
        assertEquals(cols(SqliteJobStore.JCOLS), db.columns("job"));
        assertEquals(cols(SqliteJobStore.RCOLS), db.columns("run"));
        assertEquals(cols(SqliteJobStore.OCOLS), db.columns("outbox"));
        assertEquals(cols("key, spec, required, granted, fingerprint, granted_at"), db.columns("grant_"));
        assertTrue(db.log.contains("CREATE INDEX IF NOT EXISTS idx_run_owner ON run(owner, planned_at)"));
    }

    @Test public void migrationTableShapeMatchesVersion() {
        assertEquals("MIGRATIONS 长度必须 = VERSION（每个版本差一步）", JobsDbSchema.VERSION, JobsDbSchema.MIGRATIONS.length);
    }

    @Test public void futureAddColumnStepIsStepwiseAndIdempotent() {
        FakeDb db = new FakeDb();
        JobsDbSchema.create(db);
        db.rows.put("job", 3);
        // 模拟以后的 v2：job 加 note 列（同时 ensure 用的是当前结构，所以 note 只来自迁移步骤）
        JobsDbSchema.Step[][] future = {new JobsDbSchema.Step[0], {JobsDbSchema.Step.addColumn("job", "note", "TEXT")}};
        assertFalse(JobsDbSchema.upgrade(db, 1, 2, 1000L, future));
        assertTrue(db.columns("job").contains("note"));
        assertEquals("数据不动", Integer.valueOf(3), db.rows.get("job"));
        // 降级（2→1）再升级（1→2）：加列幂等，不会 duplicate column
        assertFalse(JobsDbSchema.downgrade(db, 2, 1, 2000L));
        assertTrue("降级保留新列与数据", db.columns("job").contains("note"));
        assertFalse(JobsDbSchema.upgrade(db, 1, 2, 3000L, future));
        assertEquals(Integer.valueOf(3), db.rows.get("job"));
    }

    @Test public void downgradeKeepsDataAndFillsMissingPlainColumns() {
        FakeDb db = new FakeDb();
        JobsDbSchema.create(db);
        db.rows.put("outbox", 7);
        db.tables.get("outbox").remove("worth");   // 一个缺了普通列的旧库
        db.tables.remove("grant_");                // 缺表
        assertFalse(JobsDbSchema.downgrade(db, 3, 1, 1L));
        assertTrue(db.columns("outbox").contains("worth"));
        assertEquals(Integer.valueOf(7), db.rows.get("outbox"));
        assertFalse(db.columns("grant_").isEmpty());
    }

    @Test public void unrepairableSchemaIsBackedUpThenRebuilt() {
        FakeDb db = new FakeDb();
        JobsDbSchema.create(db);
        db.rows.put("run", 5);
        db.tables.get("outbox").remove("idem");    // UNIQUE 列没法 ALTER ADD → 备份重建
        assertTrue(JobsDbSchema.downgrade(db, 2, 1, 42L));
        assertEquals("旧表改名留底，数据还在", Integer.valueOf(5), db.rows.get("run_bak_v2_42"));
        assertTrue(db.tables.containsKey("outbox_bak_v2_42"));
        assertEquals(cols(SqliteJobStore.OCOLS), db.columns("outbox"));
        assertEquals(Integer.valueOf(0), db.rows.get("run"));
        for (String s : db.log) assertFalse(s, s.startsWith("DROP TABLE"));
    }

    @Test public void helperOverridesUpgradeAndDowngrade() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(root,
                "app/src/com/onesay/assistant/v2/jobs/SqliteJobStore.java").toPath()), "UTF-8");
        assertTrue(src.contains("public void onDowngrade(SQLiteDatabase db, int o, int n)"));
        assertTrue(src.contains("JobsDbSchema.upgrade(adapt(db), o, n"));
        assertTrue(src.contains("JobsDbSchema.downgrade(adapt(db), o, n"));
        assertTrue(src.contains("DB_VERSION = JobsDbSchema.VERSION"));
        assertEquals(Arrays.asList("job", "run", "outbox", "grant_"), names());
    }

    static List<String> names() {
        List<String> l = new ArrayList<String>();
        for (JobsDbSchema.Table t : JobsDbSchema.TABLES) l.add(t.name);
        return l;
    }
}
