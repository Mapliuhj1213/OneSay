package com.onesay.assistant.v2.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * [diag] 撤销记录（进程内，10 分钟有效，最多 5 条）。只保存「修复前的旧值」与它来自哪个表内修复，
 * 不保存任何用户内容。进程被杀 = 撤销记录丢失（如实告诉用户「没有可撤销的改动」）。
 */
public final class DiagUndoStore {

    public static final long TTL_MS = 10 * 60 * 1000L;
    static final int MAX = 5;

    /** 一项要恢复的东西。 */
    public static final class Entry {
        public final DiagKnowledge.FixKind kind;
        /** SETTING：ns/key；CAP：能力签名；DEVICEIDLE：包名。 */
        public final String ns;
        public final String key;
        public final String sign;
        public final String argName;
        public final String pkg;
        /** 旧值（SETTING / CAP）；DEVICEIDLE 无意义。 */
        public final String prev;

        Entry(DiagKnowledge.FixKind kind, String ns, String key, String sign, String argName, String pkg, String prev) {
            this.kind = kind;
            this.ns = ns;
            this.key = key;
            this.sign = sign;
            this.argName = argName;
            this.pkg = pkg;
            this.prev = prev;
        }

        public static Entry setting(String ns, String key, String prev) {
            return new Entry(DiagKnowledge.FixKind.SETTING, ns, key, null, null, null, prev);
        }

        public static Entry cap(String sign, String argName, String prev) {
            return new Entry(DiagKnowledge.FixKind.CAP, null, null, sign, argName, null, prev);
        }

        public static Entry idle(String pkg) {
            return new Entry(DiagKnowledge.FixKind.DEVICEIDLE, null, null, null, null, pkg, null);
        }
    }

    /** 一条撤销记录（对应一次修复）。 */
    public static final class Record {
        public final String id;
        public final String fixId;
        /** 「屏幕超时（自动熄屏）」 */
        public final String what;
        /** 修复前 / 修复后的人话值。 */
        public final String before;
        public final String after;
        public final List<Entry> entries;
        public final long at;

        Record(String id, String fixId, String what, String before, String after, List<Entry> entries, long at) {
            this.id = id;
            this.fixId = fixId;
            this.what = what;
            this.before = before;
            this.after = after;
            this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
            this.at = at;
        }

        public boolean alive(long now) {
            return now - at <= TTL_MS && now >= at - 60000L;
        }

        @Override public String toString() {
            return "Undo{" + id + " " + fixId + " " + before + "→" + after + "}";
        }
    }

    private final List<Record> mRecords = new ArrayList<Record>();
    private int mSeq;

    public synchronized Record push(String fixId, String what, String before, String after, List<Entry> entries, long now) {
        Record r = new Record("u" + (++mSeq), fixId, what, before, after, entries, now);
        mRecords.add(r);
        while (mRecords.size() > MAX) mRecords.remove(0);
        return r;
    }

    /** 最近一条仍有效的记录（不取走）。 */
    public synchronized Record latest(long now) {
        purge(now);
        return mRecords.isEmpty() ? null : mRecords.get(mRecords.size() - 1);
    }

    public synchronized Record get(String id, long now) {
        purge(now);
        if (id == null) return null;
        for (Record r : mRecords) if (r.id.equals(id)) return r;
        return null;
    }

    /** 取走（撤销执行前调用，保证一次记录只撤销一次）。 */
    public synchronized Record take(String id, long now) {
        purge(now);
        Iterator<Record> it = mRecords.iterator();
        while (it.hasNext()) {
            Record r = it.next();
            if (id == null ? !it.hasNext() : r.id.equals(id)) {
                it.remove();
                return r;
            }
        }
        return null;
    }

    public synchronized int size(long now) {
        purge(now);
        return mRecords.size();
    }

    public synchronized void clear() {
        mRecords.clear();
    }

    private void purge(long now) {
        Iterator<Record> it = mRecords.iterator();
        while (it.hasNext()) {
            if (!it.next().alive(now)) it.remove();
        }
    }
}
