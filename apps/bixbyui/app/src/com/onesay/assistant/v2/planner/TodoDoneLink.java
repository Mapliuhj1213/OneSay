package com.onesay.assistant.v2.planner;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ borrow-1112：经计划 App 已有的 provider call() 通道问「正在提醒 / 最近提醒过哪些待办」、把一条标成完成。
 * <ul>
 *   <li>函数：{@code listRemindedTasks}（extras window_min）/ {@code completeRemindedTask}（extras id, window_min），
 *       结果 Bundle 的 code（1 成功 / 2 参数不对 / 3 找不到 / 4 不认识）、ok、count、data（JSON 数组：id, title, due_at, state, reminded_at,
 *       first_at, strong, snooze_until；这里只取 id / title / state / reminded_at）；completeRemindedTask 另回 id、already_done，失败带 error。
 *       window_min 计划 App 默认 30、限 1–120，这边固定传 {@link TodoVoiceDone#WINDOW_MIN}。
 *       这两个函数由计划 App 这一轮新加（另一个子任务在改计划 App）；计划 App 还没有时 call 返回 code 4 或抛异常 → 当作没有，回落助手本地待办。</li>
 *   <li>门卫沿用伴侣连接（companion-link）的规则：调用前先确认计划 App 与助手<b>同一证书</b>（checkSignatures = MATCH）、
 *       且 authority 的 provider 确实归 com.onesay.plan（防同名 authority 冒充）；不认证书白名单、不用 root / adb。</li>
 * </ul>
 */
public final class TodoDoneLink {

    private TodoDoneLink() {}

    static final String TAG = "BixbyPlanner";
    public static final String M_LIST = "listRemindedTasks", M_COMPLETE = "completeRemindedTask";
    public static final String X_ID = "id", X_WINDOW = "window_min", X_ALREADY_DONE = "already_done";
    public static final String K_CODE = "code", K_DATA = "data", K_ERROR = "error";
    public static final int CODE_OK = 1, CODE_BAD_ARGS = 2, CODE_NOT_FOUND = 3, CODE_UNKNOWN = 4;

    /** 同证书 + provider 归属（companion-link 规则）。 */
    public static boolean sameSigner(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            if (pm.checkSignatures(c.getPackageName(), PlannerContract.PKG) != PackageManager.SIGNATURE_MATCH) return false;
            ProviderInfo pi = pm.resolveContentProvider(PlannerContract.AUTHORITY, 0);
            return pi != null && PlannerContract.PKG.equals(pi.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 计划 App 那边正在提醒 / 最近提醒过的未完成待办；不可用 → null（调用方回落本地）。 */
    public static List<TodoVoiceDone.Cand> listReminded(Context c) {
        if (!PlannerLink.SWITCH.on(c) || !PlannerLink.installed(c) || !sameSigner(c)) return null;
        try {
            Bundle x = new Bundle();
            x.putInt(X_WINDOW, TodoVoiceDone.WINDOW_MIN);
            Bundle b = c.getContentResolver().call(Uri.parse(PlannerContract.BASE_URI), M_LIST, null, x);
            if (b == null || b.getInt(K_CODE, 0) != CODE_OK) {
                Log.i(TAG, "[TODO-VOICE] 计划 App 没有 listRemindedTasks（code=" + (b == null ? "null" : b.getInt(K_CODE, 0)) + "），用本地待办");
                return null;
            }
            return parse(b.getString(K_DATA));
        } catch (Throwable t) {
            Log.i(TAG, "[TODO-VOICE] listRemindedTasks 失败，用本地待办: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 标完成；返回 null = 成功，否则失败原因。 */
    public static String complete(Context c, long id) {
        if (!sameSigner(c)) return "计划 App 签名对不上，不经它改数据";
        try {
            Bundle x = new Bundle();
            x.putLong(X_ID, id);
            x.putInt(X_WINDOW, TodoVoiceDone.WINDOW_MIN);
            Bundle b = c.getContentResolver().call(Uri.parse(PlannerContract.BASE_URI), M_COMPLETE, null, x);
            int code = b == null ? 0 : b.getInt(K_CODE, 0);
            if (code == CODE_OK && b.getBoolean(X_ALREADY_DONE, false)) Log.i(TAG, "[TODO-VOICE] 计划 App：这条早已完成");
            return failReason(code, b == null ? null : b.getString(K_ERROR));
        } catch (Throwable t) {
            return "计划 App 连不上";
        }
    }

    /** completeRemindedTask 的结果码 → null（成功，含 already_done）或给用户看的失败原因（纯函数，JVM 可测）。 */
    public static String failReason(int code, String error) {
        if (code == CODE_OK) return null;
        if (code == CODE_NOT_FOUND) return "这条待办已经不在最近提醒里了";
        if (code == CODE_BAD_ARGS) return "计划 App 说参数不对" + (error == null || error.length() == 0 ? "" : "（" + error + "）");
        if (code == CODE_UNKNOWN) return "计划 App 版本太旧，还不支持这样标完成";
        return "计划 App 没有接受（code=" + code + "）";
    }

    /** data JSON → 候选（纯解析，JVM 可测）。 */
    public static List<TodoVoiceDone.Cand> parse(String data) {
        List<TodoVoiceDone.Cand> out = new ArrayList<TodoVoiceDone.Cand>();
        if (data == null || data.trim().length() == 0) return out;
        try {
            JSONArray a = new JSONArray(data);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("id", 0) <= 0) continue;
                out.add(new TodoVoiceDone.Cand(o.optLong("id"), o.optString("title", ""), o.optString("state", ""), o.optLong("reminded_at", 0), "planner"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
