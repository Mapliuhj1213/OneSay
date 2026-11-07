package com.onesay.assistant.v2.soul;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** [soul] 内存实现（JVM 单测 / 自测）。线程安全。 */
public class InMemorySoulRepo implements SoulRepo {

    private final Map<String, LinkedHashMap<Long, Claim>> mTables = new HashMap<String, LinkedHashMap<Long, Claim>>();
    private long mSeq = ID_BASE;
    private final List<SoulVersion> mSouls = new ArrayList<SoulVersion>();
    private long mSoulSeq;
    private final Map<String, String> mIdentity = new HashMap<String, String>();
    private final List<String[]> mOps = new ArrayList<String[]>();
    private final Set<String> mForgotten = new HashSet<String>();
    private final List<String> mSnapshots = new ArrayList<String>();
    private final Map<String, String> mMeta = new HashMap<String, String>();

    private LinkedHashMap<Long, Claim> t(String table) {
        LinkedHashMap<Long, Claim> m = mTables.get(table);
        if (m == null) {
            m = new LinkedHashMap<Long, Claim>();
            mTables.put(table, m);
        }
        return m;
    }

    @Override public synchronized long insert(Claim c) {
        if (c == null || c.table == null) return -1;
        long id = ++mSeq;
        Claim x = c.copy();
        x.id = id;
        t(c.table).put(Long.valueOf(id), x);
        return id;
    }

    @Override public synchronized boolean update(Claim c) {
        if (c == null || !t(c.table).containsKey(Long.valueOf(c.id))) return false;
        t(c.table).put(Long.valueOf(c.id), c.copy());
        return true;
    }

    @Override public synchronized Claim get(String table, long id) {
        Claim c = t(table).get(Long.valueOf(id));
        return c == null ? null : c.copy();
    }

    @Override public synchronized List<Claim> list(String table) {
        List<Claim> out = new ArrayList<Claim>();
        for (Claim c : t(table).values()) out.add(c.copy());
        return out;
    }

    @Override public synchronized int delete(String table, long id) {
        return t(table).remove(Long.valueOf(id)) != null ? 1 : 0;
    }

    @Override public synchronized int clear(String table) {
        int n = t(table).size();
        t(table).clear();
        return n;
    }

    @Override public synchronized long insertSoul(SoulVersion v) {
        SoulVersion x = v.copy();
        x.id = ++mSoulSeq;
        mSouls.add(x);
        return x.id;
    }

    @Override public synchronized boolean updateSoul(SoulVersion v) {
        for (int i = 0; i < mSouls.size(); i++) {
            if (mSouls.get(i).id == v.id) {
                mSouls.set(i, v.copy());
                return true;
            }
        }
        return false;
    }

    @Override public synchronized int deleteSoul(long id) {
        for (int i = 0; i < mSouls.size(); i++) {
            if (mSouls.get(i).id == id) {
                mSouls.remove(i);
                return 1;
            }
        }
        return 0;
    }

    @Override public synchronized List<SoulVersion> souls() {
        List<SoulVersion> out = new ArrayList<SoulVersion>();
        for (SoulVersion v : mSouls) out.add(v.copy());
        return out;
    }

    @Override public synchronized String identity(String key) { return mIdentity.get(key); }

    @Override public synchronized void setIdentity(String key, String value) {
        if (value == null) mIdentity.remove(key); else mIdentity.put(key, value);
    }

    @Override public synchronized long addOpsNote(String scope, String text, long now) {
        mOps.add(new String[]{scope, text, String.valueOf(now)});
        return mOps.size();
    }

    @Override public synchronized List<String[]> opsNotes() { return new ArrayList<String[]>(mOps); }

    @Override public synchronized void addForgotten(String keyHash, long now) { if (keyHash != null) mForgotten.add(keyHash); }

    @Override public synchronized boolean isForgotten(String keyHash) { return keyHash != null && mForgotten.contains(keyHash); }

    @Override public synchronized long addSnapshot(String json, long now) {
        mSnapshots.add(json);
        return mSnapshots.size();
    }

    @Override public synchronized String latestSnapshot() {
        return mSnapshots.isEmpty() ? null : mSnapshots.get(mSnapshots.size() - 1);
    }

    @Override public synchronized int clearSnapshots() {
        int n = mSnapshots.size();
        mSnapshots.clear();
        return n;
    }

    @Override public synchronized String meta(String key) { return mMeta.get(key); }

    @Override public synchronized void setMeta(String key, String value) {
        if (value == null) mMeta.remove(key); else mMeta.put(key, value);
    }
}
