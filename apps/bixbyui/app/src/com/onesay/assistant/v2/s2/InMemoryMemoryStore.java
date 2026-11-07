package com.onesay.assistant.v2.s2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** [s2] 内存版记忆库 + 习惯库（JVM 单测、自测、SQLite 不可用时的兜底）。线程安全（方法级同步）。 */
public final class InMemoryMemoryStore implements MemoryStore, HabitStore {

    private final List<MemoryEntry> mRows = new ArrayList<MemoryEntry>();
    private long mNextId = 1;

    private final List<long[]> mUseTs = new ArrayList<long[]>();      // 占位：与 mUses 同下标
    private final List<String[]> mUses = new ArrayList<String[]>();   // {group, app}
    private final java.util.Map<String, String> mExplicit = new java.util.HashMap<String, String>();
    private final java.util.Map<String, Long> mReset = new java.util.HashMap<String, Long>();

    static final Comparator<MemoryEntry> NEWEST_FIRST = new Comparator<MemoryEntry>() {
        @Override
        public int compare(MemoryEntry a, MemoryEntry b) {
            if (a.createdAt != b.createdAt) return a.createdAt > b.createdAt ? -1 : 1;
            return a.id > b.id ? -1 : (a.id == b.id ? 0 : 1);
        }
    };

    @Override
    public synchronized long insert(MemoryEntry e) {
        if (e == null) return -1;
        MemoryEntry c = e.copy();
        c.id = mNextId++;
        mRows.add(c);
        return c.id;
    }

    @Override
    public synchronized List<MemoryEntry> list(String kind) {
        List<MemoryEntry> out = new ArrayList<MemoryEntry>();
        for (MemoryEntry e : mRows) if (kind == null || kind.equals(e.kind)) out.add(e.copy());
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    @Override
    public synchronized MemoryEntry get(long id) {
        for (MemoryEntry e : mRows) if (e.id == id) return e.copy();
        return null;
    }

    @Override
    public synchronized int delete(long[] ids) {
        if (ids == null) return 0;
        int n = 0;
        for (long id : ids) {
            for (int i = mRows.size() - 1; i >= 0; i--) {
                if (mRows.get(i).id == id) {
                    mRows.remove(i);
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public synchronized int clear(String kind) {
        int n = 0;
        for (int i = mRows.size() - 1; i >= 0; i--) {
            if (kind == null || kind.equals(mRows.get(i).kind)) {
                mRows.remove(i);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized int count(String kind) {
        int n = 0;
        for (MemoryEntry e : mRows) if (kind == null || kind.equals(e.kind)) n++;
        return n;
    }

    // ------------------------------------------------------------------ HabitStore

    @Override
    public synchronized void recordUse(String group, String app, long ts) {
        mUses.add(new String[]{group, app});
        mUseTs.add(new long[]{ts});
    }

    @Override
    public synchronized List<String> recentUses(String group, long sinceTs, int limit) {
        List<String> out = new ArrayList<String>();
        for (int i = mUses.size() - 1; i >= 0 && out.size() < limit; i--) {
            if (mUses.get(i)[0].equals(group) && mUseTs.get(i)[0] > sinceTs) out.add(mUses.get(i)[1]);
        }
        return out;
    }

    @Override
    public synchronized String explicitDefault(String group) {
        return mExplicit.get(group);
    }

    @Override
    public synchronized void setExplicitDefault(String group, String app) {
        if (app == null) mExplicit.remove(group);
        else mExplicit.put(group, app);
    }

    @Override
    public synchronized long resetAt(String group) {
        Long v = mReset.get(group);
        return v == null ? 0L : v.longValue();
    }

    @Override
    public synchronized void setResetAt(String group, long ts) {
        mReset.put(group, Long.valueOf(ts));
    }

    @Override
    public synchronized void clearHabits() {
        mUses.clear();
        mUseTs.clear();
        mExplicit.clear();
        mReset.clear();
    }
}
