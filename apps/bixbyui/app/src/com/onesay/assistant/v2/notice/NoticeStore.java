package com.onesay.assistant.v2.notice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提醒持久化（生产：jobs.db 的 island_notice 表，见 {@code SqliteNoticeStore}；JVM：{@link Mem}）。按去重键一行。 */
public interface NoticeStore {

    void put(IslandNotice n);

    IslandNotice get(String key);

    List<IslandNotice> all();

    void delete(String key);

    final class Mem implements NoticeStore {
        private final Map<String, String> mRows = new LinkedHashMap<String, String>();

        @Override public synchronized void put(IslandNotice n) {
            if (n != null && n.persist) mRows.put(n.dedupKey, n.toJson());
        }

        @Override public synchronized IslandNotice get(String key) {
            String s = mRows.get(key);
            return s == null ? null : IslandNotice.fromJson(s);
        }

        @Override public synchronized List<IslandNotice> all() {
            List<IslandNotice> l = new ArrayList<IslandNotice>();
            for (String s : mRows.values()) {
                IslandNotice n = IslandNotice.fromJson(s);
                if (n != null) l.add(n);
            }
            return l;
        }

        @Override public synchronized void delete(String key) {
            mRows.remove(key);
        }

        public synchronized int size() {
            return mRows.size();
        }
    }
}
