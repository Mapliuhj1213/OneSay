package com.onesay.assistant.v2.planner;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [planner] 把 OneSay 本地已有的日程 / 待办 / 定时任务 / 事件任务 / 码 / 票导进计划 App（只读源：只读打开本地库，不删不改）。
 * 第一次连上计划 App 时全量导一次；之后只有「计划 App 不在、回落本地写过」才补导那段时间新建的。计划 App 按 src_key 去重。
 */
public final class PlannerMigration {
    private PlannerMigration() {}

    static final String TAG = "BixbyPlanner";
    static final String P = "bixby_planner", K_DONE = "migrated_at", K_DIRTY = "local_dirty", K_LAST_TRY = "migrate_try_at", K_RESULT = "migrate_result";
    static volatile boolean sRunning;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static void markLocalDirty(Context c) {
        if (sp(c).getLong(K_DONE, 0) > 0) sp(c).edit().putBoolean(K_DIRTY, true).apply();
    }

    public static String lastResult(Context c) {
        return sp(c).getString(K_RESULT, "");
    }

    /** 需要就在后台导一次（十分钟内最多试一次）。 */
    public static void maybeRun(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        SharedPreferences s = sp(c);
        long done = s.getLong(K_DONE, 0);
        boolean dirty = s.getBoolean(K_DIRTY, false);
        if (done > 0 && !dirty) return;
        long now = System.currentTimeMillis();
        if (sRunning || now - s.getLong(K_LAST_TRY, 0) < 10 * 60000L) return;
        s.edit().putLong(K_LAST_TRY, now).apply();
        sRunning = true;
        com.onesay.assistant.v2.util.Threads.start("planner-migrate", new Runnable() {
            @Override public void run() {
                try {
                    runNow(c);
                } catch (Throwable t) {
                    Log.w(TAG, "[PLANNER] 迁移失败（下次再试）: " + t.getMessage());
                } finally {
                    sRunning = false;
                }
            }
        });
    }

    public static String runNow(Context c) {
        SharedPreferences s = sp(c);
        long done = s.getLong(K_DONE, 0);
        long since = done > 0 ? done : 0;
        long now = System.currentTimeMillis();
        Map<String, List<Map<String, Object>>> t = new LinkedHashMap<String, List<Map<String, Object>>>();
        t.put("sched_event", read(c, "sched.db", "sched_event"));
        t.put("s3_task", read(c, "s3_tasks.db", "task"));
        t.put("s3_todo", read(c, "s3_tasks.db", "todo"));
        t.put("event_task", read(c, "jobs.db", "event_task"));
        t.put("quicknote", read(c, "quicknote.db", "quicknote"));
        String cards = c.getSharedPreferences("bixby_s1_pickup", Context.MODE_PRIVATE).getString("cards", "[]");
        JSONObject b = MigrationBundle.build(t, cards, since, now);
        String r = PlannerLink.get(c).backend().importBundle(b.toString());
        String first = r == null ? "" : r.split("\n")[0];
        s.edit().putLong(K_DONE, done > 0 ? done : now).putBoolean(K_DIRTY, false).putString(K_RESULT, first).apply();
        Log.i(TAG, "[PLANNER] 迁移完成（" + (since > 0 ? "补导" : "首次") + "，导出 " + MigrationBundle.count(b) + " 行）: " + first);
        return first;
    }

    /** 只读打开本地库读一整张表（库不存在 / 表不存在 → 空）。 */
    static List<Map<String, Object>> read(Context c, String db, String table) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File f = c.getDatabasePath(db);
        if (!f.exists()) return out;
        SQLiteDatabase d = null;
        Cursor cur = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            cur = d.rawQuery("SELECT * FROM " + table, null);
            while (cur.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (int i = 0; i < cur.getColumnCount(); i++) {
                    int ty = cur.getType(i);
                    if (ty == Cursor.FIELD_TYPE_INTEGER) m.put(cur.getColumnName(i), cur.getLong(i));
                    else if (ty == Cursor.FIELD_TYPE_FLOAT) m.put(cur.getColumnName(i), cur.getDouble(i));
                    else if (ty == Cursor.FIELD_TYPE_NULL) m.put(cur.getColumnName(i), null);
                    else if (ty == Cursor.FIELD_TYPE_BLOB) continue;
                    else m.put(cur.getColumnName(i), cur.getString(i));
                }
                out.add(m);
            }
        } catch (Exception e) {
            Log.w(TAG, "[PLANNER] 读 " + db + "/" + table + " 失败: " + e.getMessage());
        } finally {
            if (cur != null) cur.close();
            if (d != null) d.close();
        }
        return out;
    }
}
