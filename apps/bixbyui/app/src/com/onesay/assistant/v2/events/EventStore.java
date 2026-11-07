package com.onesay.assistant.v2.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 事件任务的持久化（生产 {@link SqliteEventStore}：jobs.db 里的 event_task 表；JVM 单测 {@link Mem}）。 */
public interface EventStore {

    long add(EventTask t);

    void update(EventTask t);

    boolean delete(long id);

    EventTask get(long id);

    List<EventTask> all();

    final class Mem implements EventStore {
        private final Map<Long, EventTask> mMap = new LinkedHashMap<Long, EventTask>();
        private long mSeq;

        @Override public synchronized long add(EventTask t) {
            t.id = ++mSeq;
            mMap.put(t.id, t.copy());
            return t.id;
        }

        @Override public synchronized void update(EventTask t) {
            if (mMap.containsKey(t.id)) mMap.put(t.id, t.copy());
        }

        @Override public synchronized boolean delete(long id) {
            return mMap.remove(id) != null;
        }

        @Override public synchronized EventTask get(long id) {
            EventTask t = mMap.get(id);
            return t == null ? null : t.copy();
        }

        @Override public synchronized List<EventTask> all() {
            List<EventTask> l = new ArrayList<EventTask>();
            for (EventTask t : mMap.values()) l.add(t.copy());
            return l;
        }
    }
}
