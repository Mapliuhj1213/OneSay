package com.onesay.assistant.v2.quicknote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** [quicknote] 内存实现（JVM 测试 / 自测用）。 */
public final class InMemoryQuickNoteStore implements QuickNoteStore {

    private final List<QuickNoteEntry> mRows = new ArrayList<QuickNoteEntry>();
    private long mNext = 1;

    @Override public synchronized long insert(QuickNoteEntry e) {
        if (e == null) return -1;
        QuickNoteEntry c = e.copy();
        c.id = mNext++;
        if (c.updatedAt == 0) c.updatedAt = c.createdAt;
        mRows.add(c);
        e.id = c.id;
        return c.id;
    }

    @Override public synchronized QuickNoteEntry get(long id) {
        for (QuickNoteEntry e : mRows) if (e.id == id) return e.copy();
        return null;
    }

    @Override public synchronized List<QuickNoteEntry> list(boolean deleted) {
        List<QuickNoteEntry> out = new ArrayList<QuickNoteEntry>();
        for (QuickNoteEntry e : mRows) if (e.deleted == deleted) out.add(e.copy());
        sortNewestFirst(out);
        return out;
    }

    static void sortNewestFirst(List<QuickNoteEntry> out) {
        Collections.sort(out, new Comparator<QuickNoteEntry>() {
            @Override public int compare(QuickNoteEntry a, QuickNoteEntry b) {
                if (a.createdAt != b.createdAt) return a.createdAt > b.createdAt ? -1 : 1;
                return a.id > b.id ? -1 : (a.id == b.id ? 0 : 1);
            }
        });
    }

    @Override public synchronized boolean update(QuickNoteEntry e) {
        for (int i = 0; i < mRows.size(); i++) {
            if (mRows.get(i).id == e.id) {
                mRows.set(i, e.copy());
                return true;
            }
        }
        return false;
    }

    @Override public synchronized boolean softDelete(long id, long now) {
        for (QuickNoteEntry e : mRows) {
            if (e.id == id && !e.deleted) {
                e.deleted = true;
                e.deletedAt = now;
                return true;
            }
        }
        return false;
    }

    @Override public synchronized boolean restore(long id) {
        for (QuickNoteEntry e : mRows) {
            if (e.id == id && e.deleted) {
                e.deleted = false;
                e.deletedAt = 0;
                return true;
            }
        }
        return false;
    }

    @Override public synchronized boolean deleteForever(long id) {
        for (int i = 0; i < mRows.size(); i++) {
            if (mRows.get(i).id == id) {
                mRows.remove(i);
                return true;
            }
        }
        return false;
    }

    @Override public synchronized List<QuickNoteEntry> purgeDeletedBefore(long cutoff) {
        List<QuickNoteEntry> out = new ArrayList<QuickNoteEntry>();
        for (int i = mRows.size() - 1; i >= 0; i--) {
            QuickNoteEntry e = mRows.get(i);
            if (e.deleted && e.deletedAt < cutoff) {
                out.add(e);
                mRows.remove(i);
            }
        }
        return out;
    }

    @Override public synchronized int clearAll() {
        int n = mRows.size();
        mRows.clear();
        return n;
    }

    @Override public synchronized int count(boolean deleted) {
        int n = 0;
        for (QuickNoteEntry e : mRows) if (e.deleted == deleted) n++;
        return n;
    }
}
