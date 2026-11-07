package com.onesay.assistant.v2.router.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内存版存储（JVM 单测用；也可作为 SQLite 不可用时的降级）。线程安全（方法级同步）。 */
public final class InMemorySkillStore implements SkillStore {

    private final Map<String, SkillEntry> mMap = new LinkedHashMap<String, SkillEntry>();
    private final Map<String, Object[]> mNeg = new LinkedHashMap<String, Object[]>();

    private static String pk(String key, String grp) {
        return grp + "\u0001" + key;
    }

    @Override public synchronized SkillEntry get(String key, String grp) {
        SkillEntry e = mMap.get(pk(key, grp));
        return e == null ? null : e.copy();
    }

    @Override public synchronized void put(SkillEntry e) {
        mMap.put(pk(e.key, e.grp), e.copy());
    }

    @Override public synchronized void delete(String key, String grp) {
        mMap.remove(pk(key, grp));
    }

    @Override public synchronized List<SkillEntry> listTextTemplates(String grp) {
        List<SkillEntry> out = new ArrayList<SkillEntry>();
        for (SkillEntry e : mMap.values()) {
            if (e.hasTextSlot && grp.equals(e.grp)) out.add(e.copy());
        }
        return out;
    }

    @Override public synchronized List<SkillEntry> listAll() {
        List<SkillEntry> out = new ArrayList<SkillEntry>();
        for (SkillEntry e : mMap.values()) out.add(e.copy());
        return out;
    }

    @Override public synchronized int count() {
        return mMap.size();
    }

    @Override public synchronized int evictLru(int max) {
        if (mMap.size() <= max) return 0;
        List<SkillEntry> all = new ArrayList<SkillEntry>(mMap.values());
        Collections.sort(all, new Comparator<SkillEntry>() {
            @Override public int compare(SkillEntry a, SkillEntry b) {
                long ta = Math.max(a.lastHitTs, a.createdTs);
                long tb = Math.max(b.lastHitTs, b.createdTs);
                return ta < tb ? -1 : (ta == tb ? 0 : 1);
            }
        });
        int n = mMap.size() - max;
        for (int i = 0; i < n; i++) {
            mMap.remove(pk(all.get(i).key, all.get(i).grp));
        }
        return n;
    }

    @Override public synchronized void putNegative(String key, String grp, String reason, long untilTs) {
        mNeg.put(pk(key, grp), new Object[]{ reason, Long.valueOf(untilTs) });
    }

    @Override public synchronized Object[] getNegative(String key, String grp) {
        Object[] v = mNeg.get(pk(key, grp));
        return v == null ? null : new Object[]{ v[0], v[1] };
    }

    @Override public synchronized void deleteNegative(String key, String grp) {
        mNeg.remove(pk(key, grp));
    }

    @Override public synchronized int clear() {
        int n = mMap.size();
        mMap.clear();
        mNeg.clear();
        return n;
    }
}
