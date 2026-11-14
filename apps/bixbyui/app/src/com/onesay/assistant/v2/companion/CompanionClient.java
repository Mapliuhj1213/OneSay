package com.onesay.assistant.v2.companion;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** [companion] 伴侣连接的函数调用（计划：createTask / listTasks / …；时钟：createAlarm / … / getBriefStatus）。出错抛 RuntimeException。 */
public final class CompanionClient implements CompanionRouter.PlanPort {

    final CompanionLink link;

    public CompanionClient(Context c) {
        this.link = CompanionLink.get(c);
    }

    Bundle plan(String fn, Bundle x) {
        Bundle b = link.call(CompanionLink.Peer.PLAN, fn, null, x == null ? new Bundle() : x);
        Log.i(CompanionLink.TAG, "[COMPANION] link plan." + fn + " code=" + b.getInt(CompanionContract.K_CODE, 0));
        return b;
    }

    static int rows(Bundle b) {
        if (b.getInt(CompanionContract.K_CODE, 0) != CompanionContract.RC_OK) {
            String e = b.getString(CompanionContract.K_ERROR, "");
            if (b.getInt(CompanionContract.K_CODE, 0) == CompanionContract.RC_NOT_FOUND) return 0;
            throw new IllegalStateException(e.length() > 0 ? e : "code " + b.getInt(CompanionContract.K_CODE, 0));
        }
        return (int) b.getLong("count", 1);
    }

    @Override public List<Map<String, Object>> listTasks(boolean includeDone) {
        Bundle x = new Bundle();
        x.putBoolean(CompanionContract.X_INCLUDE_DONE, includeDone);
        Bundle b = plan(CompanionContract.F_LIST_TASKS, x);
        rows(b);
        return parseRows(b.getString(CompanionContract.K_DATA, "[]"));
    }

    @Override public int deleteTask(long id) {
        Bundle x = new Bundle();
        x.putLong(CompanionContract.X_ID, id);
        return rows(plan(CompanionContract.F_DELETE_TASK, x));
    }

    @Override public int updateTask(long id, String title) {
        Bundle x = new Bundle();
        x.putLong(CompanionContract.X_ID, id);
        x.putString(CompanionContract.X_TITLE, title);
        return rows(plan(CompanionContract.F_UPDATE_TASK, x));
    }

    @Override public int completeTask(long id) {
        Bundle x = new Bundle();
        x.putLong(CompanionContract.X_ID, id);
        x.putBoolean(CompanionContract.X_DONE, true);
        return rows(plan(CompanionContract.F_COMPLETE_TASK, x));
    }

    @Override public long createTask(String title, long dueAt) {
        Bundle x = new Bundle();
        x.putString(CompanionContract.X_TITLE, title);
        if (dueAt > 0) x.putLong(CompanionContract.X_DUE_AT, dueAt);
        Bundle b = plan(CompanionContract.F_CREATE_TASK, x);
        rows(b);
        return b.getLong(CompanionContract.X_ID, -1);
    }

    /** 时钟函数：结果 Bundle 原样返回（code + data = 闹钟表 JSON，与旧有序广播 resultCode / resultData 同格式）。 */
    public Bundle clock(String fn, Bundle x) {
        Bundle b = link.call(CompanionLink.Peer.CLOCK, fn, null, x == null ? new Bundle() : x);
        Log.i(CompanionLink.TAG, "[COMPANION] link clock." + fn + " code=" + b.getInt(CompanionContract.K_CODE, 0));
        return b;
    }

    /** 时钟当前的晨间播报状态（state.json 原文）；拿不到返回 null。 */
    public String briefStatus() {
        try {
            Bundle b = clock(CompanionContract.F_GET_BRIEF_STATUS, null);
            if (b.getInt(CompanionContract.K_CODE, 0) != CompanionContract.RC_OK) return null;
            return b.getString(CompanionContract.K_DATA, null);
        } catch (RuntimeException e) {
            Log.i(CompanionLink.TAG, "[COMPANION] getBriefStatus 失败: " + e.getMessage());
            return null;
        }
    }

    static List<Map<String, Object>> parseRows(String json) {
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    m.put(k, o.opt(k));
                }
                l.add(m);
            }
        } catch (Exception e) {
            throw new IllegalStateException("计划 App 返回的不是待办列表");
        }
        return l;
    }
}
