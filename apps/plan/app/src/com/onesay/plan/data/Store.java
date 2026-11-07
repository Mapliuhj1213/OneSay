package com.onesay.plan.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import com.onesay.plan.core.AccessPolicy;
import com.onesay.plan.core.AutomationTiming;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.RawImport;
import com.onesay.plan.core.Reminders;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.Schema;
import com.onesay.plan.core.Today;
import com.onesay.plan.core.Validator;
import com.onesay.plan.remind.Remind;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 唯一写入口（Provider 与 App 界面共用）：校验 → 写库 → notifyChange + 变更广播 + 重排本地提醒。
 * 校验失败抛 IllegalArgumentException（消息是给人看的原因，后门调用方能直接拿到）。
 */
public final class Store {
    private Store() {}

    static final String TAG = "BixbyPlan";

    static SQLiteDatabase db(Context c) {
        return PlanDb.get(c).getWritableDatabase();
    }

    public static Row fromValues(ContentValues cv) {
        Row r = new Row();
        if (cv != null) for (Map.Entry<String, Object> e : cv.valueSet()) r.put(e.getKey(), e.getValue());
        return r;
    }

    static ContentValues toValues(Map<String, Object> m) {
        ContentValues v = new ContentValues();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object o = e.getValue();
            if (o == null) v.putNull(e.getKey());
            else if (o instanceof Long) v.put(e.getKey(), (Long) o);
            else if (o instanceof Integer) v.put(e.getKey(), (Integer) o);
            else v.put(e.getKey(), String.valueOf(o));
        }
        return v;
    }

    static Row read(Cursor c) {
        Row r = new Row();
        for (int i = 0; i < c.getColumnCount(); i++) {
            int t = c.getType(i);
            if (t == Cursor.FIELD_TYPE_INTEGER) r.put(c.getColumnName(i), c.getLong(i));
            else if (t == Cursor.FIELD_TYPE_NULL) r.put(c.getColumnName(i), null);
            else r.put(c.getColumnName(i), c.getString(i));
        }
        return r;
    }

    // ------------------------------------------------------------------ 读

    public static List<Row> list(Context c, String table, String where, String[] args, String order) {
        List<Row> out = new ArrayList<Row>();
        Cursor cur = db(c).query(table, null, where, args, null, null, order);
        try {
            while (cur.moveToNext()) out.add(read(cur));
        } finally {
            cur.close();
        }
        return out;
    }

    public static Row get(Context c, String table, long id) {
        List<Row> l = list(c, table, "_id=?", new String[]{String.valueOf(id)}, null);
        return l.isEmpty() ? null : l.get(0);
    }

    public static long count(Context c, String table) {
        Cursor cur = db(c).rawQuery("SELECT COUNT(*) FROM " + table, null);
        try {
            return cur.moveToFirst() ? cur.getLong(0) : 0;
        } finally {
            cur.close();
        }
    }

    static List<Long> ids(Context c, String table, String sel, String[] args) {
        List<Long> out = new ArrayList<Long>();
        Cursor cur = db(c).query(table, new String[]{"_id"}, sel, args, null, null, null);
        try {
            while (cur.moveToNext()) out.add(cur.getLong(0));
        } finally {
            cur.close();
        }
        return out;
    }

    public static Today.Data today(Context c, long now) {
        return Today.build(list(c, Contract.T_EVENTS, "deleted_at=0", null, null), list(c, Contract.T_TODOS, "done_at=0", null, null),
                list(c, Contract.T_TICKETS, "status=0", null, null), list(c, Contract.T_PICKUPS, "taken_at=0", null, null),
                list(c, Contract.T_AUTOMATIONS, "mode='time' AND enabled=1", null, null), now, TimeZone.getDefault());
    }

    // ------------------------------------------------------------------ 写

    /** 插入；src_key 已存在 → 不改原行，返回原行 id（幂等）。 */
    public static long insert(Context c, String table, Row in, boolean fromImport, String by) {
        long id = insertNoSignal(c, table, in, fromImport, new boolean[1]);
        changed(c, table, Contract.OP_INSERT, id, by);
        return id;
    }

    static long insertNoSignal(Context c, String table, Row in, boolean fromImport, boolean[] dup) {
        Row r = new Row(in);
        long created = r.l(Contract.C_CREATED);
        r.remove(Contract.C_CREATED);
        r.remove(Contract.C_UPDATED);
        r.remove(Contract.C_ID);
        Validator.Result v = Validator.validate(table, r, true, null);
        if (!v.ok()) throw new IllegalArgumentException(v.error);
        Row w = new Row(v.values);
        long now = System.currentTimeMillis();
        w.put(Contract.C_CREATED, fromImport && created > 0 ? created : now);
        w.put(Contract.C_UPDATED, now);
        if (Contract.T_AUTOMATIONS.equals(table)) fillNextAt(w, null, now);
        if (Contract.T_SHORTCUTS.equals(table)) checkUniqueName(c, w.s("name"), 0);
        if (w.s(Contract.C_SRC_KEY).length() == 0) w.remove(Contract.C_SRC_KEY);
        else {
            List<Long> ex = ids(c, table, "src_key=?", new String[]{w.s(Contract.C_SRC_KEY)});
            if (!ex.isEmpty()) {
                dup[0] = true;
                return ex.get(0);
            }
        }
        long id = db(c).insertOrThrow(table, null, toValues(w));
        if (Contract.T_RUNS.equals(table)) trimRuns(c, w.l("automation_id"));
        if (Contract.T_SHORTCUT_RUNS.equals(table)) trimShortcutRuns(c, w.l("shortcut_id"));
        return id;
    }

    /** 自动任务（定时）：没给 next_at 时由计划 App 按时间规则算一个（只给界面看，真正排程在 OneSay）。见 {@link AutomationTiming}。 */
    static void fillNextAt(Row w, Row existing, long now) {
        AutomationTiming.fillNextAt(w, existing, now, TimeZone.getDefault());
    }

    public static int update(Context c, String table, List<Long> ids, Row in, String by) {
        int n = 0;
        Row r = new Row(in);
        r.remove(Contract.C_ID);
        for (long id : ids) {
            Row ex = get(c, table, id);
            if (ex == null) continue;
            Validator.Result v = Validator.validate(table, r, false, ex);
            if (!v.ok()) throw new IllegalArgumentException(v.error);
            Row w = new Row(v.values);
            long now = System.currentTimeMillis();
            w.put(Contract.C_UPDATED, now);
            if (Contract.T_AUTOMATIONS.equals(table)) fillNextAt(w, ex, now);
            if (Contract.T_SHORTCUTS.equals(table) && w.containsKey("name")) checkUniqueName(c, w.s("name"), id);
            n += db(c).update(table, toValues(w), "_id=?", new String[]{String.valueOf(id)});
            Row after = new Row(ex);
            after.putAll(w);
            if (Reminders.dismissOnUpdate(table, ex, after)) Remind.dismiss(c, table, id);
            changed(c, table, Contract.OP_UPDATE, id, by);
        }
        return n;
    }

    public static int delete(Context c, String table, List<Long> ids, String by) {
        int n = 0;
        for (long id : ids) {
            int k = db(c).delete(table, "_id=?", new String[]{String.valueOf(id)});
            if (k == 0) continue;
            n += k;
            if (Reminders.dismissOnDelete(table)) Remind.dismiss(c, table, id);
            if (Contract.T_AUTOMATIONS.equals(table)) db(c).delete(Contract.T_RUNS, "automation_id=?", new String[]{String.valueOf(id)});
            if (Contract.T_SHORTCUTS.equals(table)) {
                // 快捷指令删了：执行记录和它的定时触发（自动任务）一起删
                db(c).delete(Contract.T_SHORTCUT_RUNS, "shortcut_id=?", new String[]{String.valueOf(id)});
                for (long a : ids(c, Contract.T_AUTOMATIONS, "src_key=?", new String[]{Contract.SC_TIME_SRC_PREFIX + id})) {
                    db(c).delete(Contract.T_RUNS, "automation_id=?", new String[]{String.valueOf(a)});
                    db(c).delete(Contract.T_AUTOMATIONS, "_id=?", new String[]{String.valueOf(a)});
                    changed(c, Contract.T_AUTOMATIONS, Contract.OP_DELETE, a, by);
                }
                // 桌面上固定的图标一并禁用（Android 不让 App 直接删固定图标；禁用后桌面置灰，点了有说明）
                List<Long> gone = new ArrayList<Long>();
                gone.add(id);
                com.onesay.plan.platform.PinnedShortcuts.disable(c, gone);
            }
            if (Contract.T_TICKETS.equals(table)) {
                File f = qrFile(c, id);
                if (f.exists() && !f.delete()) Log.w(TAG, "二维码文件没删掉: " + f);
            }
            changed(c, table, Contract.OP_DELETE, id, by);
        }
        return n;
    }

    /** 过期票归档：status 0 → 1。不经 update()（免得 changed→schedule 递归），只 notifyChange。 */
    public static int archiveTickets(Context c, List<Long> ids) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Long id : ids) {
            ContentValues v = new ContentValues();
            v.put("status", 1);
            v.put(Contract.C_UPDATED, now);
            int k = db(c).update(Contract.T_TICKETS, v, "_id=? AND status=0", new String[]{String.valueOf(id)});
            if (k > 0) Remind.dismiss(c, Contract.T_TICKETS, id);
            n += k;
        }
        if (n > 0) c.getContentResolver().notifyChange(Uri.parse(Contract.BASE_URI + "/" + Contract.T_TICKETS), null);
        return n;
    }

    /** OneSay「删日程」先软删（deleted_at）留 10 分钟撤销；软删超过 7 天的在这里清掉。 */
    public static int purgeSoftDeleted(Context c, long now) {
        return db(c).delete(Contract.T_EVENTS, "deleted_at>0 AND deleted_at<?", new String[]{String.valueOf(now - com.onesay.plan.core.Defaults.SOFT_DELETE_KEEP_MS)});
    }

    public static long logRun(Context c, Row r, String by) {
        long autoId = r.l("automation_id");
        if (get(c, Contract.T_AUTOMATIONS, autoId) == null) throw new IllegalArgumentException("没有这个自动任务: " + autoId);
        if (!r.containsKey("started_at")) r.put("started_at", System.currentTimeMillis());
        r.put(Contract.C_SOURCE, AccessPolicy.runSource(by));
        long id = insertNoSignal(c, Contract.T_RUNS, r, false, new boolean[1]);
        Row upd = new Row().with("last_run_at", r.l("started_at")).with("last_result", (r.s("status") + " " + r.s("result")).trim());
        Validator.Result v = Validator.validate(Contract.T_AUTOMATIONS, upd, false, get(c, Contract.T_AUTOMATIONS, autoId));
        if (v.ok()) {
            Row w = new Row(v.values);
            w.put(Contract.C_UPDATED, System.currentTimeMillis());
            db(c).update(Contract.T_AUTOMATIONS, toValues(w), "_id=?", new String[]{String.valueOf(autoId)});
        }
        changed(c, Contract.T_RUNS, Contract.OP_INSERT, id, by);
        return id;
    }

    static void trimRuns(Context c, long autoId) {
        db(c).execSQL("DELETE FROM runs WHERE automation_id=? AND _id NOT IN (SELECT _id FROM runs WHERE automation_id=? ORDER BY started_at DESC, _id DESC LIMIT "
                + Schema.RUNS_KEEP + ")", new Object[]{autoId, autoId});
    }

    static void trimShortcutRuns(Context c, long scId) {
        db(c).execSQL("DELETE FROM shortcut_runs WHERE shortcut_id=? AND _id NOT IN (SELECT _id FROM shortcut_runs WHERE shortcut_id=? ORDER BY started_at DESC, _id DESC LIMIT "
                + Schema.SHORTCUT_RUNS_KEEP + ")", new Object[]{scId, scId});
    }

    /** 快捷指令重名（按 {@link com.onesay.plan.core.Shortcuts#normName} 比）→ 拒绝：名字也是语音触发词，重名就分不清。 */
    static void checkUniqueName(Context c, String name, long selfId) {
        String n = com.onesay.plan.core.Shortcuts.normName(name);
        for (Row r : list(c, Contract.T_SHORTCUTS, null, null, null)) {
            if (r.id() != selfId && com.onesay.plan.core.Shortcuts.normName(r.s("name")).equals(n)) throw new IllegalArgumentException("已有同名的快捷指令「" + r.s("name") + "」");
        }
    }

    public static File qrFile(Context c, long ticketId) {
        return new File(new File(c.getFilesDir(), "qr"), ticketId + ".img");
    }

    public static void setQrPath(Context c, long id, String path, String by) {
        ContentValues v = new ContentValues();
        v.put("qr_path", path);
        v.put(Contract.C_UPDATED, System.currentTimeMillis());
        db(c).update(Contract.T_TICKETS, v, "_id=?", new String[]{String.valueOf(id)});
        changed(c, Contract.T_TICKETS, Contract.OP_UPDATE, id, by);
    }

    // ------------------------------------------------------------------ 导入

    public static final class ImportResult {
        public int inserted, duplicate, rejected, skipped;
        public final List<String> notes = new ArrayList<String>();

        public String summary() {
            return com.onesay.plan.core.RawImport.summary(inserted, duplicate, rejected, skipped);
        }

        public String detail() {
            StringBuilder sb = new StringBuilder(summary());
            for (String n : notes) sb.append("\n").append(n);
            return sb.toString();
        }
    }

    public static ImportResult importPlan(Context c, RawImport.Plan p, String by) {
        ImportResult r = new ImportResult();
        SQLiteDatabase db = db(c);
        db.beginTransaction();
        try {
            for (Map.Entry<String, List<Row>> e : p.rows.entrySet()) {
                for (Row row : e.getValue()) {
                    try {
                        boolean[] dup = new boolean[1];
                        insertNoSignal(c, e.getKey(), row, true, dup);
                        if (dup[0]) r.duplicate++;
                        else r.inserted++;
                    } catch (IllegalArgumentException ex) {
                        r.rejected++;
                        r.notes.add(e.getKey() + " " + row.s(Contract.C_SRC_KEY) + "：" + ex.getMessage());
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (String s : p.skipped) r.notes.add("跳过 " + s);
        r.skipped = p.skipped.size();
        changed(c, "*", Contract.OP_IMPORT, 0, by);
        Log.i(TAG, "[IMPORT] " + r.summary() + " · " + p.counts());
        return r;
    }

    public static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ 变更信号

    static void changed(Context c, String table, String op, long id, String by) {
        c.getContentResolver().notifyChange(Uri.parse(Contract.BASE_URI + ("*".equals(table) ? "" : "/" + table)), null);
        if (!"*".equals(table)) c.getContentResolver().notifyChange(Uri.parse(Contract.BASE_URI + "/" + Contract.P_TODAY), null);
        Intent i = new Intent(Contract.ACTION_CHANGED);
        i.putExtra(Contract.EXTRA_TABLE, table);
        i.putExtra(Contract.EXTRA_OP, op);
        i.putExtra(Contract.EXTRA_ID, id);
        i.putExtra(Contract.EXTRA_BY, by == null ? "self" : by);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        c.sendBroadcast(i, Contract.PERMISSION);
        if (!Contract.T_RUNS.equals(table) && !Contract.T_AUTOMATIONS.equals(table) && !Contract.T_SHORTCUTS.equals(table)
                && !Contract.T_SHORTCUT_RUNS.equals(table)) Remind.schedule(c);
    }
}
