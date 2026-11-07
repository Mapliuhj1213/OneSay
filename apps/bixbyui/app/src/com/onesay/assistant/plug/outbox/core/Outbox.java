/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/run/Outbox.kt translated to Java 7 (Mutex → synchronized); the record store is backed by
 * the host Storage port as JSON so it survives process death. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.outbox.core;

import com.onesay.assistant.plug.api.Storage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 先记账再发送；结果不明的发送不自动重试（免得用户收到两份）；同一个 id 换了内容直接拒。 */
public final class Outbox {

    public enum State { PENDING, ATTEMPTING, DELIVERED, FAILED, UNCERTAIN }

    public enum Sent { DELIVERED, FAILED, UNCERTAIN }

    /** 一个送达渠道（岛 / 通知 / 播报 / 邮件）。 */
    public interface Channel {
        String id();

        boolean alive(String peer);

        Sent send(String peer, String deliveryId, String content);
    }

    public static final class Record {
        public final String channel, peer, id, contentHash;
        public final State state;

        Record(String channel, String peer, String id, String contentHash, State state) {
            this.channel = channel;
            this.peer = peer;
            this.id = id;
            this.contentHash = contentHash;
            this.state = state;
        }

        Record with(State s) {
            return new Record(channel, peer, id, contentHash, s);
        }

        String json() {
            try {
                return new JSONObject().put("c", channel).put("p", peer).put("h", contentHash).put("s", state.name()).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        static Record parse(String id, String json) {
            try {
                JSONObject o = new JSONObject(json);
                return new Record(o.optString("c"), o.optString("p"), id, o.optString("h"), State.valueOf(o.optString("s", "PENDING")));
            } catch (Exception e) {
                return null;
            }
        }
    }

    static final String PREFIX = "outbox:";
    private final Storage store;

    public Outbox(Storage store) {
        this.store = store;
    }

    public static String hash(String content) {
        long h = 0xcbf29ce484222325L;
        String s = content == null ? "" : content;
        for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i)) * 0x100000001b3L;
        return Long.toHexString(h);
    }

    public synchronized Record get(String id) {
        String v = store.all().get(PREFIX + id);
        return v == null ? null : Record.parse(id, v);
    }

    private void save(Record r) {
        store.put(PREFIX + r.id, r.json());
    }

    public synchronized Record deliver(Channel ch, String peer, String id, String content) {
        String h = hash(content);
        Record old = get(id);
        if (old != null) {
            if (!old.channel.equals(ch.id()) || !old.peer.equals(peer) || !old.contentHash.equals(h))
                throw new IllegalArgumentException("delivery id used for different content");
            if (old.state == State.ATTEMPTING) {
                Record u = old.with(State.UNCERTAIN);
                save(u);
                return u;
            }
            if (old.state != State.PENDING) return old;
        }
        Record pending = old != null ? old : new Record(ch.id(), peer, id, h, State.PENDING);
        save(pending);
        if (!ch.alive(peer)) {
            Record f = pending.with(State.FAILED);
            save(f);
            return f;
        }
        save(pending.with(State.ATTEMPTING));
        State st;
        try {
            Sent s = ch.send(peer, id, content);
            st = s == Sent.DELIVERED ? State.DELIVERED : s == Sent.FAILED ? State.FAILED : State.UNCERTAIN;
        } catch (Throwable t) {
            st = State.UNCERTAIN;
        }
        Record done = pending.with(st);
        save(done);
        return done;
    }

    public synchronized List<Record> pendingRecovery() {
        return filter(State.PENDING, null);
    }

    /** 结果不明的（发送中进程没了 / 渠道说不清）：交给宿主「中断后告知」，不自动重发。 */
    public synchronized List<Record> uncertain() {
        return filter(State.ATTEMPTING, State.UNCERTAIN);
    }

    private List<Record> filter(State a, State b) {
        List<Record> out = new ArrayList<Record>();
        for (Map.Entry<String, String> e : store.all().entrySet()) {
            if (!e.getKey().startsWith(PREFIX)) continue;
            Record r = Record.parse(e.getKey().substring(PREFIX.length()), e.getValue());
            if (r != null && (r.state == a || r.state == b)) out.add(r);
        }
        return out;
    }
}
