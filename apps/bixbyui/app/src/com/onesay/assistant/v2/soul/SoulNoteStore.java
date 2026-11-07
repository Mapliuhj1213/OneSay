package com.onesay.assistant.v2.soul;

import com.onesay.assistant.v2.s2.MemoryEntry;
import com.onesay.assistant.v2.s2.MemoryStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * [soul] S2 记忆库的新存储：S2Engine 不改逻辑，只把 {@link MemoryStore} 换成本类。
 *
 * <ul>
 *   <li>笔记（「记一下」/ 免前缀）→ {@link SoulEngine}：明确记的进 memory_core / user_profile，免前缀的进 daily_log；
 *       读出来时核心 + 画像 + 日志笔记（含被替代的历史，S2「以最新为准」照旧）合成 MemoryEntry。</li>
 *   <li>账目、以及旧表里迁移时没搬的记一记行（relation=quicknote，已有独立库视图）→ 仍在旧 memory 表。</li>
 *   <li>id 分流：记忆表 id ≥ {@link SoulRepo#ID_BASE}，旧表 id 小，删除 / 查找按 id 走对应的库。</li>
 *   <li>删除笔记 = {@link SoulEngine#forget}（真删 + 证据 + 同句候选 + 快照 + forgotten）。</li>
 * </ul>
 * 这样「记一下 / 车停哪了 / 我记了什么 / 删掉那条 / 清空记忆 / memory.search」与长期记忆是<b>同一套数据</b>，不并存两套。
 */
public final class SoulNoteStore implements MemoryStore {

    private final SoulEngine mEngine;
    private final MemoryStore mLegacy;
    private final Clock mClock;

    public interface Clock {
        long now();
    }

    public SoulNoteStore(SoulEngine engine, MemoryStore legacy, Clock clock) {
        mEngine = engine;
        mLegacy = legacy;
        mClock = clock != null ? clock : new Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    public SoulEngine engine() { return mEngine; }

    public MemoryStore legacy() { return mLegacy; }

    static boolean isSoulId(long id) {
        return id >= SoulRepo.ID_BASE;
    }

    static MemoryEntry toEntry(Claim c) {
        MemoryEntry e = new MemoryEntry();
        e.id = c.id;
        e.kind = MemoryEntry.KIND_NOTE;
        e.content = c.text;
        e.subject = c.subject;
        e.relation = c.relation;
        e.value = c.value;
        e.day = c.day;
        e.createdAt = c.firstSeen;
        e.origin = Claim.T_LOG.equals(c.table) ? MemoryEntry.ORIGIN_IMPLICIT : "";
        return e;
    }

    @Override public long insert(MemoryEntry e) {
        if (e == null) return -1;
        if (!MemoryEntry.KIND_NOTE.equals(e.kind)) return mLegacy.insert(e);
        long at = e.createdAt > 0 ? e.createdAt : mClock.now();
        SoulEngine.WriteResult w = MemoryEntry.ORIGIN_IMPLICIT.equals(e.origin)
                ? mEngine.noteImplicit(e.content, e.subject, e.relation, e.value, "", at)
                : mEngine.remember(e.content, Claim.SRC_USER, e.content, "", false, e.subject, e.relation, e.value, at);
        return w.ok && w.claim != null ? w.claim.id : -1;
    }

    private List<MemoryEntry> notes() {
        List<MemoryEntry> out = new ArrayList<MemoryEntry>();
        for (Claim c : mEngine.userNotes()) out.add(toEntry(c));
        out.addAll(mLegacy.list(MemoryEntry.KIND_NOTE));
        return out;
    }

    @Override public List<MemoryEntry> list(String kind) {
        List<MemoryEntry> out;
        if (MemoryEntry.KIND_LEDGER.equals(kind)) return mLegacy.list(kind);
        if (MemoryEntry.KIND_NOTE.equals(kind)) out = notes();
        else {
            out = notes();
            out.addAll(mLegacy.list(MemoryEntry.KIND_LEDGER));
        }
        Collections.sort(out, new Comparator<MemoryEntry>() {
            @Override public int compare(MemoryEntry a, MemoryEntry b) {
                if (a.createdAt != b.createdAt) return a.createdAt > b.createdAt ? -1 : 1;
                return a.id > b.id ? -1 : (a.id == b.id ? 0 : 1);
            }
        });
        return out;
    }

    @Override public MemoryEntry get(long id) {
        if (!isSoulId(id)) return mLegacy.get(id);
        Claim c = mEngine.find(id);
        if (c == null) return null;
        if (Claim.T_LOG.equals(c.table) && !Claim.K_NOTE.equals(c.kind)) return null;   // 证据 / 候选不是「笔记」
        return toEntry(c);
    }

    @Override public int delete(long[] ids) {
        if (ids == null) return 0;
        List<Long> soul = new ArrayList<Long>(), old = new ArrayList<Long>();
        for (long id : ids) (isSoulId(id) ? soul : old).add(Long.valueOf(id));
        int n = 0;
        if (!old.isEmpty()) n += mLegacy.delete(toArr(old));
        if (!soul.isEmpty()) {
            long[] a = toArr(soul);
            int before = 0;
            for (long id : a) if (mEngine.find(id) != null) before++;
            mEngine.forget(a, mClock.now());
            int after = 0;
            for (long id : a) if (mEngine.find(id) != null) after++;
            n += before - after;
        }
        return n;
    }

    @Override public int clear(String kind) {
        if (MemoryEntry.KIND_LEDGER.equals(kind)) return mLegacy.clear(kind);
        int n = mEngine.userNotes().size();
        mEngine.clearMemory();
        if (MemoryEntry.KIND_NOTE.equals(kind)) return n + mLegacy.clear(MemoryEntry.KIND_NOTE);
        return n + mLegacy.clear(null);
    }

    @Override public int count(String kind) {
        if (MemoryEntry.KIND_LEDGER.equals(kind)) return mLegacy.count(kind);
        int notes = mEngine.userNotes().size() + mLegacy.count(MemoryEntry.KIND_NOTE);
        if (MemoryEntry.KIND_NOTE.equals(kind)) return notes;
        return notes + mLegacy.count(MemoryEntry.KIND_LEDGER);
    }

    private static long[] toArr(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).longValue();
        return a;
    }

    /** 迁移用：旧 memory 表的「记一下」笔记（排除记一记 quicknote 行）。 */
    public static SoulEngine.LegacySource legacySource(final MemoryStore legacy) {
        return new SoulEngine.LegacySource() {
            @Override public List<SoulEngine.LegacyNote> notes() {
                List<SoulEngine.LegacyNote> out = new ArrayList<SoulEngine.LegacyNote>();
                for (MemoryEntry e : legacy.list(MemoryEntry.KIND_NOTE)) {
                    if ("quicknote".equals(e.relation)) continue;
                    SoulEngine.LegacyNote n = new SoulEngine.LegacyNote();
                    n.id = e.id;
                    n.content = e.content;
                    n.subject = e.subject;
                    n.relation = e.relation;
                    n.value = e.value;
                    n.day = e.day;
                    n.createdAt = e.createdAt;
                    out.add(n);
                }
                return out;
            }

            @Override public int delete(long[] ids) {
                return legacy.delete(ids);
            }
        };
    }
}
