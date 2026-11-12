package com.onesay.plan.remind;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.StrongAlert;
import com.onesay.plan.data.Store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [borrow-1112] 提醒记录本的存取（core/StrongAlert.Book，存在私有设置 plan_alerts）。所有读改写都在 {@link #LOCK} 里串行。
 * 只存待办 id、标题、时刻、状态；不联网、不备份。
 */
public final class AlertLog {
    private AlertLog() {}

    static final Object LOCK = new Object();
    static final String P = "plan_alerts", K = "book";

    static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE); }

    public static StrongAlert.Book load(Context c) {
        synchronized (LOCK) { return StrongAlert.Book.parse(sp(c).getString(K, "")); }
    }

    static void save(Context c, StrongAlert.Book b) {
        synchronized (LOCK) {
            b.prune(System.currentTimeMillis());
            sp(c).edit().putString(K, b.serialize()).commit();
        }
    }

    /** 读-改-写一次。 */
    public interface Edit { StrongAlert.Entry apply(StrongAlert.Book b); }

    public static StrongAlert.Entry edit(Context c, Edit e) {
        synchronized (LOCK) {
            StrongAlert.Book b = load(c);
            StrongAlert.Entry r = e.apply(b);
            save(c, b);
            return r;
        }
    }

    /** 读-改-写一次，返回多条（并表同时提醒：全部稍后 / 全部停止 / 单条完成后剩下的）。 */
    public interface EditAll { List<StrongAlert.Entry> apply(StrongAlert.Book b); }

    public static List<StrongAlert.Entry> editAll(Context c, EditAll e) {
        synchronized (LOCK) {
            StrongAlert.Book b = load(c);
            List<StrongAlert.Entry> r = e.apply(b);
            save(c, b);
            return r;
        }
    }

    /** 记录本里提到的待办的当前行（id → 行；已删的不在里面）。 */
    public static Map<Long, Row> todos(Context c, StrongAlert.Book b) {
        Map<Long, Row> m = new HashMap<Long, Row>();
        for (StrongAlert.Entry e : b.entries) {
            Row r = Store.get(c, Contract.T_TODOS, e.todoId);
            if (r != null) m.put(e.todoId, r);
        }
        return m;
    }

    public static List<StrongAlert.Entry> recent(Context c, long windowMs) {
        StrongAlert.Book b = load(c);
        return StrongAlert.recent(b, todos(c, b), System.currentTimeMillis(), windowMs);
    }
}
