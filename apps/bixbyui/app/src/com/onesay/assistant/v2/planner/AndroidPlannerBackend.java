package com.onesay.assistant.v2.planner;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** [planner] 真机后门客户端：ContentResolver → content://com.onesay.plan.provider。任何异常包成 PlannerException。 */
public final class AndroidPlannerBackend implements PlannerBackend {

    final ContentResolver cr;

    public AndroidPlannerBackend(Context c) {
        this.cr = c.getApplicationContext().getContentResolver();
    }

    static Uri uri(String table) {
        return Uri.parse(PlannerContract.BASE_URI + "/" + table);
    }

    static Map<String, Object> row(Cursor c) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < c.getColumnCount(); i++) {
            int t = c.getType(i);
            if (t == Cursor.FIELD_TYPE_INTEGER) m.put(c.getColumnName(i), c.getLong(i));
            else if (t == Cursor.FIELD_TYPE_NULL) m.put(c.getColumnName(i), null);
            else m.put(c.getColumnName(i), c.getString(i));
        }
        return m;
    }

    static ContentValues cv(Map<String, Object> m) {
        ContentValues v = new ContentValues();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object o = e.getValue();
            if (o == null) v.putNull(e.getKey());
            else if (o instanceof Long) v.put(e.getKey(), (Long) o);
            else if (o instanceof Integer) v.put(e.getKey(), (Integer) o);
            else if (o instanceof Boolean) v.put(e.getKey(), ((Boolean) o) ? 1 : 0);
            else v.put(e.getKey(), String.valueOf(o));
        }
        return v;
    }

    List<Map<String, Object>> query(Uri u) {
        Cursor c = null;
        try {
            c = cr.query(u, null, null, null, null);
            if (c == null) throw new PlannerException("计划 App 没响应: " + u);
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            while (c.moveToNext()) l.add(row(c));
            return l;
        } catch (PlannerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlannerException("读计划 App 失败: " + e.getMessage(), e);
        } finally {
            if (c != null) c.close();
        }
    }

    @Override public List<Map<String, Object>> list(String table) {
        return query(uri(table));
    }

    @Override public Map<String, Object> get(String table, long id) {
        List<Map<String, Object>> l = query(ContentUris.withAppendedId(uri(table), id));
        return l.isEmpty() ? null : l.get(0);
    }

    @Override public long insert(String table, Map<String, Object> values) {
        try {
            Uri r = cr.insert(uri(table), cv(values));
            if (r == null) throw new PlannerException("计划 App 没返回新行");
            return ContentUris.parseId(r);
        } catch (PlannerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlannerException("写计划 App 失败: " + e.getMessage(), e);
        }
    }

    @Override public int update(String table, long id, Map<String, Object> values) {
        try {
            return cr.update(ContentUris.withAppendedId(uri(table), id), cv(values), null, null);
        } catch (RuntimeException e) {
            throw new PlannerException("改计划 App 失败: " + e.getMessage(), e);
        }
    }

    @Override public int delete(String table, long id) {
        try {
            return cr.delete(ContentUris.withAppendedId(uri(table), id), null, null);
        } catch (RuntimeException e) {
            throw new PlannerException("删计划 App 失败: " + e.getMessage(), e);
        }
    }

    @Override public long logRun(Map<String, Object> run) {
        Bundle x = new Bundle();
        for (Map.Entry<String, Object> e : run.entrySet()) {
            Object o = e.getValue();
            if (o instanceof Number) x.putLong(e.getKey(), ((Number) o).longValue());
            else if (o != null) x.putString(e.getKey(), String.valueOf(o));
        }
        Bundle r = call(PlannerContract.M_LOG_RUN, null, x);
        return r == null ? 0 : r.getLong("run_id");
    }

    @Override public String importBundle(String json) {
        Bundle r = call(PlannerContract.M_IMPORT_BUNDLE, json, null);
        return r == null ? "" : r.getString("detail", "");
    }

    /** ★ feat/shortcuts：把快捷指令动作清单（带每项可用性）推给计划 App。 */
    public void pushShortcutActions(String json) {
        call(PlannerContract.M_SHORTCUT_ACTIONS, json, null);
    }

    public Bundle ping() {
        return call(PlannerContract.M_PING, null, null);
    }

    Bundle call(String method, String arg, Bundle extras) {
        ContentProviderClient c = null;
        try {
            c = cr.acquireUnstableContentProviderClient(PlannerContract.AUTHORITY);
            if (c == null) throw new PlannerException("计划 App 没装");
            return c.call(method, arg, extras);
        } catch (PlannerException e) {
            throw e;
        } catch (Exception e) {
            throw new PlannerException("调用计划 App 失败: " + e.getMessage(), e);
        } finally {
            if (c != null) c.close();
        }
    }
}
