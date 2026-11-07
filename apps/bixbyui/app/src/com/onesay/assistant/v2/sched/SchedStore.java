package com.onesay.assistant.v2.sched;

import java.util.ArrayList;
import java.util.List;

/**
 * [sched] 本地日程存储接口。真机 = {@link SqliteSchedStore}（本机 SQLite，sched.db，迁移框架 {@link SchedMigrations}）；
 * 单测 = {@link Memory}。删除一律先软删（{@link SchedEvent#deleted}），给「撤销」留后路；软删 7 天后清掉。
 */
public interface SchedStore {

    /** 新建，返回 id（&gt;0）。 */
    long insert(SchedEvent e);

    /** 整条覆盖（按 id）。 */
    boolean update(SchedEvent e);

    SchedEvent get(long id);

    /** 全部（含不含软删的）。 */
    List<SchedEvent> all(boolean includeDeleted);

    boolean setDeleted(long id, boolean deleted, long at);

    /** 清掉 before 之前软删的。返回清了几条。 */
    int purgeDeleted(long before);

    /** 导入来源：按外部 id 找。 */
    SchedEvent byExtId(String extId);

    /** 当前库结构版本（迁移后）。 */
    int schemaVersion();

    /** 单测 / 兜底用的内存实现。 */
    final class Memory implements SchedStore {
        private final List<SchedEvent> mRows = new ArrayList<SchedEvent>();
        private long mNext = 1;

        @Override public synchronized long insert(SchedEvent e) {
            SchedEvent c = e.copy();
            c.id = mNext++;
            mRows.add(c);
            e.id = c.id;
            return c.id;
        }

        @Override public synchronized boolean update(SchedEvent e) {
            for (int i = 0; i < mRows.size(); i++) {
                if (mRows.get(i).id == e.id) {
                    mRows.set(i, e.copy());
                    return true;
                }
            }
            return false;
        }

        @Override public synchronized SchedEvent get(long id) {
            for (SchedEvent e : mRows) if (e.id == id) return e.copy();
            return null;
        }

        @Override public synchronized List<SchedEvent> all(boolean includeDeleted) {
            List<SchedEvent> l = new ArrayList<SchedEvent>();
            for (SchedEvent e : mRows) if (includeDeleted || !e.deleted) l.add(e.copy());
            return l;
        }

        @Override public synchronized boolean setDeleted(long id, boolean deleted, long at) {
            for (SchedEvent e : mRows) {
                if (e.id == id) {
                    e.deleted = deleted;
                    e.deletedAt = deleted ? at : 0;
                    e.updatedAt = at;
                    return true;
                }
            }
            return false;
        }

        @Override public synchronized int purgeDeleted(long before) {
            int n = 0;
            for (int i = mRows.size() - 1; i >= 0; i--) {
                SchedEvent e = mRows.get(i);
                if (e.deleted && e.deletedAt < before) {
                    mRows.remove(i);
                    n++;
                }
            }
            return n;
        }

        @Override public synchronized SchedEvent byExtId(String extId) {
            if (extId == null || extId.length() == 0) return null;
            for (SchedEvent e : mRows) if (extId.equals(e.extId)) return e.copy();
            return null;
        }

        @Override public int schemaVersion() {
            return SchedMigrations.LATEST;
        }
    }
}
