/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/run/RunInbox.kt translated to Java 7 (coroutines / channels → a lock + a listener);
 * answer / confirm / hand-back routing stays in the host (OneSay has AskUserBroker). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.inbox.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 运行中输入的唯一入口：用户插话无限排队、逐字保留，只在模型调用之前或一批工具做完之后交给模型；停止信号直接送达。
 * 收尾时还有没交给模型的插话就不关（调用方带着它接着做）；关了以后的插话成为下一个任务。
 */
public final class RunInbox {

    public enum Receipt { ACCEPTED, NEXT_TASK, CLOSED }

    public static final class Steer {
        public final String id, text;

        Steer(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** 有新插话时叫醒（宿主可以借此提前结束等待）。 */
    public interface Listener {
        void onSteer();
    }

    private final Object lock = new Object();
    private final ArrayDeque<Steer> steers = new ArrayDeque<Steer>();
    private List<Steer> remaining = new ArrayList<Steer>();
    private volatile boolean closed, stopRequested;
    private volatile Listener listener;

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean wasCancelled() {
        return stopRequested;
    }

    public boolean closed() {
        return closed;
    }

    public Receipt cancel() {
        synchronized (lock) {
            if (closed) return Receipt.CLOSED;
            stopRequested = true;
        }
        return Receipt.ACCEPTED;
    }

    public Receipt steer(String id, String text) {
        Listener l;
        synchronized (lock) {
            if (closed) return Receipt.NEXT_TASK;
            if (text == null || text.trim().length() == 0) return Receipt.ACCEPTED;
            steers.addLast(new Steer(id, text));
            l = listener;
        }
        if (l != null) {
            try {
                l.onSteer();
            } catch (Throwable ignored) {
            }
        }
        return Receipt.ACCEPTED;
    }

    public boolean withdraw(String id) {
        synchronized (lock) {
            if (closed) return false;
            Iterator<Steer> it = steers.iterator();
            while (it.hasNext()) if (it.next().id.equals(id)) {
                it.remove();
                return true;
            }
            return false;
        }
    }

    public boolean hasSteers() {
        synchronized (lock) {
            return !steers.isEmpty();
        }
    }

    public List<Steer> takeSteers() {
        synchronized (lock) {
            List<Steer> out = new ArrayList<Steer>(steers);
            steers.clear();
            return out;
        }
    }

    /** 要交出回答时：还有插话就不关（false），否则关上（true）。看和关在同一把锁里。 */
    public boolean closeUnlessSteered() {
        synchronized (lock) {
            if (!steers.isEmpty()) return false;
            closeLocked();
            return true;
        }
    }

    /** 关上，返回还没消费的插话（交给宿主当下一个任务）。 */
    public List<Steer> close() {
        synchronized (lock) {
            closeLocked();
            return new ArrayList<Steer>(remaining);
        }
    }

    private void closeLocked() {
        if (closed) return;
        closed = true;
        listener = null;
        remaining = new ArrayList<Steer>(steers);
        steers.clear();
    }

    /** 把插话拼成给模型的一句（逐字保留原话）。 */
    public static String render(List<Steer> s) {
        StringBuilder b = new StringBuilder("（用户在任务进行中补充：");
        for (int i = 0; i < s.size(); i++) {
            if (i > 0) b.append(" / ");
            b.append('「').append(s.get(i).text).append('」');
        }
        return b.append("。按补充调整接下来的做法；已经做完的不用重做，和之前要求冲突时以补充为准。）").toString();
    }
}
