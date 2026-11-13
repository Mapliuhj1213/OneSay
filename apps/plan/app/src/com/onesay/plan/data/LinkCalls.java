package com.onesay.plan.data;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.LinkContract;
import com.onesay.plan.core.Row;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 伴侣连接的函数实现（PlanProvider.call 在门卫 AccessPolicy 放行后转进来）。写入全部经 {@link Store}（同一套校验、变更广播、提醒重排）。
 * 参数不对 → code 2；找不到 → code 3；不抛异常给调用方（结果都在 Bundle 里）。日志口径 [LINK]，只记函数名与结果码。
 */
final class LinkCalls {
    private LinkCalls() {}

    static final String TAG = "BixbyPlan";

    static Bundle call(Context c, String method, Bundle x, String by) {
        Bundle out = new Bundle();
        if (x == null) x = new Bundle();
        try {
            if (LinkContract.M_HELLO.equals(method)) {
                for (Map.Entry<String, Object> e : LinkContract.hello(Settings.versionName(c), Contract.AUTHORITY).entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Integer) out.putInt(e.getKey(), (Integer) v);
                    else out.putString(e.getKey(), String.valueOf(v));
                }
                out.putBoolean("client_ok", LinkContract.clientOk(x.getInt(LinkContract.K_CLIENT_VERSION, 0)));
                return ok(out, 1);
            }
            long now = System.currentTimeMillis();
            if (LinkContract.F_LIST_TASKS.equals(method)) {
                boolean all = x.getBoolean(LinkContract.X_INCLUDE_DONE, false);
                List<Row> l = Store.list(c, Contract.T_TODOS, all ? null : "done_at=0", null, "done_at ASC, priority DESC, due_at=0, due_at ASC, _id ASC");
                out.putString(LinkContract.K_DATA, rows(l));
                return ok(out, l.size());
            }
            if (LinkContract.F_CREATE_TASK.equals(method)) {
                String t = x.getString(LinkContract.X_TITLE, "").trim();
                if (t.length() == 0) return code(out, LinkContract.RC_BAD_ARGS, "缺 title");
                Row r = new Row().with("title", t).with(Contract.C_SOURCE, "bixbyui");
                long due = x.getLong(LinkContract.X_DUE_AT, 0);
                if (due > 0) r.put("due_at", due);
                long id = Store.insert(c, Contract.T_TODOS, r, false, by);
                out.putLong(LinkContract.X_ID, id);
                return ok(out, 1);
            }
            if (LinkContract.F_UPDATE_TASK.equals(method) || LinkContract.F_COMPLETE_TASK.equals(method) || LinkContract.F_DELETE_TASK.equals(method)) {
                long id = x.getLong(LinkContract.X_ID, -1);
                if (id <= 0) return code(out, LinkContract.RC_BAD_ARGS, "缺 id");
                if (Store.get(c, Contract.T_TODOS, id) == null) return code(out, LinkContract.RC_NOT_FOUND, "没有这条待办");
                List<Long> one = one(id);
                int n;
                if (LinkContract.F_DELETE_TASK.equals(method)) {
                    // 它派生的提醒（自动任务 todo_id）一并删，不留孤儿
                    for (long a : Store.ids(c, Contract.T_AUTOMATIONS, "todo_id=?", new String[]{String.valueOf(id)})) Store.delete(c, Contract.T_AUTOMATIONS, one(a), by);
                    n = Store.delete(c, Contract.T_TODOS, one, by);
                } else if (LinkContract.F_COMPLETE_TASK.equals(method)) {
                    boolean done = x.getBoolean(LinkContract.X_DONE, true);
                    n = Store.update(c, Contract.T_TODOS, one, new Row().with("done_at", done ? now : 0L), by);
                    if (done) for (long a : Store.ids(c, Contract.T_AUTOMATIONS, "todo_id=?", new String[]{String.valueOf(id)}))
                        Store.update(c, Contract.T_AUTOMATIONS, one(a), new Row().with("enabled", 0L), by);
                } else {
                    String t = x.getString(LinkContract.X_TITLE, "").trim();
                    if (t.length() == 0) return code(out, LinkContract.RC_BAD_ARGS, "缺 title");
                    n = Store.update(c, Contract.T_TODOS, one, new Row().with("title", t), by);
                }
                out.putLong(LinkContract.X_ID, id);
                return n > 0 ? ok(out, n) : code(out, LinkContract.RC_NOT_FOUND, "没有这条待办");
            }
            if (LinkContract.F_CREATE_EVENT.equals(method)) {
                String t = x.getString(LinkContract.X_TITLE, "").trim();
                long st = x.getLong(LinkContract.X_START_AT, 0);
                if (t.length() == 0 || st <= 0) return code(out, LinkContract.RC_BAD_ARGS, "缺 title / start_at");
                Row r = new Row().with("title", t).with("start_at", st).with(Contract.C_SOURCE, "bixbyui");
                long en = x.getLong(LinkContract.X_END_AT, 0);
                if (en > 0) r.put("end_at", en);
                String loc = x.getString(LinkContract.X_LOCATION, "");
                if (loc.length() > 0) r.put("location", loc);
                long id = Store.insert(c, Contract.T_EVENTS, r, false, by);
                out.putLong(LinkContract.X_ID, id);
                return ok(out, 1);
            }
            if (LinkContract.F_LIST_EVENTS.equals(method)) {
                long from = x.getLong(LinkContract.X_FROM, 0), to = x.getLong(LinkContract.X_TO, Long.MAX_VALUE);
                List<Row> l = Store.list(c, Contract.T_EVENTS, "deleted_at=0 AND start_at>=? AND start_at<?",
                        new String[]{String.valueOf(from), String.valueOf(to)}, "start_at ASC");
                out.putString(LinkContract.K_DATA, rows(l));
                return ok(out, l.size());
            }
            return code(out, LinkContract.RC_UNKNOWN, "不认识的函数 " + method);
        } catch (IllegalArgumentException e) {
            return code(out, LinkContract.RC_BAD_ARGS, e.getMessage());
        } finally {
            Log.i(TAG, "[LINK] " + method + " code=" + out.getInt(LinkContract.K_CODE, 0) + (by == null ? "" : " by=" + by));
        }
    }

    static List<Long> one(long id) {
        List<Long> l = new ArrayList<Long>();
        l.add(id);
        return l;
    }

    static Bundle ok(Bundle out, long count) {
        out.putInt(LinkContract.K_CODE, LinkContract.RC_OK);
        out.putBoolean(LinkContract.K_OK, true);
        out.putLong("count", count);
        return out;
    }

    static Bundle code(Bundle out, int code, String err) {
        out.putInt(LinkContract.K_CODE, code);
        out.putBoolean(LinkContract.K_OK, false);
        out.putString(LinkContract.K_ERROR, err == null ? "" : err);
        return out;
    }

    static String rows(List<Row> l) {
        JSONArray a = new JSONArray();
        for (Row r : l) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : r.entrySet()) {
                try {
                    o.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
                } catch (Exception ignored) {
                }
            }
            a.put(o);
        }
        return a.toString();
    }
}
