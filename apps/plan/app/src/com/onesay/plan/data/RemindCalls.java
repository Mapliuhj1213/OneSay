package com.onesay.plan.data;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.RemindLink;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.remind.AlertLog;
import com.onesay.plan.remind.Remind;

import java.util.List;
import java.util.Map;

/**
 * [borrow-1112] 给助手的提醒接口实现（listRemindedTasks / completeRemindedTask，契约见 core/RemindLink）。
 * 只经 PlanProvider.call()，门卫（AccessPolicy：本 App / signature 权限 / 同证书）放行之后才转进来。日志 [REMIND-LINK] 只记函数名与结果码。
 */
public final class RemindCalls {
    private RemindCalls() {}

    static final String TAG = "BixbyPlan";

    public static Bundle call(Context c, String method, Bundle x) {
        Bundle out = new Bundle();
        if (x == null) x = new Bundle();
        try {
            long now = System.currentTimeMillis();
            long win = StrongAlert.windowMs(x.getInt(RemindLink.X_WINDOW_MIN, 30));
            StrongAlert.Book b = AlertLog.load(c);
            Map<Long, Row> todos = AlertLog.todos(c, b);
            if (RemindLink.F_LIST_REMINDED.equals(method)) {
                List<StrongAlert.Entry> l = StrongAlert.recent(b, todos, now, win);
                out.putString(RemindLink.K_DATA, StrongAlert.recentJson(l, todos));
                return ok(out, l.size());
            }
            if (RemindLink.F_COMPLETE_REMINDED.equals(method)) {
                long id = x.getLong(RemindLink.X_ID, -1);
                if (id <= 0) return code(out, RemindLink.RC_BAD_ARGS, "缺 id");
                Row r = Store.get(c, Contract.T_TODOS, id);
                if (r == null) return code(out, RemindLink.RC_NOT_FOUND, "没有这条待办");
                out.putLong(RemindLink.X_ID, id);
                if (r.l("done_at") != 0) { out.putBoolean(RemindLink.X_ALREADY_DONE, true); return ok(out, 0); }
                if (!StrongAlert.completable(b, todos, id, now, win)) return code(out, RemindLink.RC_NOT_FOUND, "这条待办不在最近的提醒里");
                Remind.markDone(c, id);   // Store.update → 撤通知、停响铃、重排（与界面勾掉同一条路）
                out.putBoolean(RemindLink.X_ALREADY_DONE, false);
                return ok(out, 1);
            }
            return code(out, RemindLink.RC_UNKNOWN, "不认识的函数 " + method);
        } catch (IllegalArgumentException e) {
            return code(out, RemindLink.RC_BAD_ARGS, e.getMessage());
        } finally {
            Log.i(TAG, "[REMIND-LINK] " + method + " code=" + out.getInt(RemindLink.K_CODE, 0));
        }
    }

    static Bundle ok(Bundle out, long count) {
        out.putInt(RemindLink.K_CODE, RemindLink.RC_OK);
        out.putBoolean(RemindLink.K_OK, true);
        out.putLong("count", count);
        return out;
    }

    static Bundle code(Bundle out, int code, String err) {
        out.putInt(RemindLink.K_CODE, code);
        out.putBoolean(RemindLink.K_OK, false);
        out.putString(RemindLink.K_ERROR, err == null ? "" : err);
        return out;
    }
}
