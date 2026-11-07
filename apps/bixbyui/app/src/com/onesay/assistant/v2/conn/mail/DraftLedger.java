package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.Codec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [conn/mail] 本 App 建过的草稿台账：本机草稿号 d-xxxxxx → (邮箱, 远端定位, 内容指纹, 摘要)。
 *
 * <p>只有台账里的草稿能被改 / 发 / 删——模型拿不到也编不出远端 id，更动不了用户自己写的草稿。
 * 摘要只存收件人、主题和正文前 120 字（确认卡预览用），整封正文在邮箱服务器上，不落本机。
 * 持久化经 {@link Store}（真机 = bixby_conn prefs，App 私有）；超过 7 天或超过 50 条的旧记录自动清掉。
 */
public final class DraftLedger {

    static final long TTL_MS = 7L * 24 * 3600 * 1000;
    static final int MAX = 50;

    public interface Store {
        String load();

        void save(String json);
    }

    public static final class Entry {
        public final String id;
        public final String account;
        public final String remoteId;
        public final String rev;
        public final String hash;
        public final String to;
        public final String subject;
        public final String bodyHead;
        public final long updatedAt;

        Entry(String id, String account, String remoteId, String rev, String hash, String to, String subject, String bodyHead, long updatedAt) {
            this.id = id;
            this.account = account;
            this.remoteId = remoteId;
            this.rev = rev;
            this.hash = hash;
            this.to = to;
            this.subject = subject;
            this.bodyHead = bodyHead;
            this.updatedAt = updatedAt;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("account", account).put("remote", remoteId).put("rev", rev).put("hash", hash)
                        .put("to", to).put("subject", subject).put("head", bodyHead).put("at", updatedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Entry fromJson(JSONObject o) {
            if (o == null || o.optString("id", "").length() == 0) return null;
            return new Entry(o.optString("id"), o.optString("account"), o.optString("remote"), o.optString("rev"), o.optString("hash"),
                    o.optString("to"), o.optString("subject"), o.optString("head"), o.optLong("at"));
        }

        /** 确认卡上显示的内容。 */
        public String preview() {
            return "收件人：" + to + "\n主题：" + subject + "\n正文：" + bodyHead;
        }
    }

    private final Store mStore;
    private final Map<String, Entry> mMap = new LinkedHashMap<String, Entry>();
    private final MailSendGuard.Clock mClock;

    public DraftLedger(Store store) {
        this(store, MailSendGuard.sClock);
    }

    DraftLedger(Store store, MailSendGuard.Clock clock) {
        mStore = store;
        mClock = clock;
        if (store != null) {
            try {
                JSONArray arr = new JSONArray(store.load() == null || store.load().length() == 0 ? "[]" : store.load());
                for (int i = 0; i < arr.length(); i++) {
                    Entry e = Entry.fromJson(arr.optJSONObject(i));
                    if (e != null) mMap.put(e.id, e);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized Entry get(String id) {
        prune();
        return id == null ? null : mMap.get(id);
    }

    public synchronized String newId() {
        String id;
        do {
            id = "d-" + Codec.random(6).replace('-', 'x').replace('_', 'y').substring(0, 8).toLowerCase(java.util.Locale.ROOT);
        } while (mMap.containsKey(id));
        return id;
    }

    public synchronized Entry put(String id, String account, String remoteId, String rev, MailDraft d) {
        String head = d.body.length() > 120 ? d.body.substring(0, 120) + "…" : d.body;
        Entry e = new Entry(id, account, remoteId, rev, d.contentHash(), d.to + (d.cc.length() > 0 ? "（抄送 " + d.cc + "）" : ""),
                d.subject, head, mClock.now());
        mMap.remove(id);
        mMap.put(id, e);
        prune();
        save();
        return e;
    }

    public synchronized void remove(String id) {
        if (mMap.remove(id) != null) save();
    }

    public synchronized List<Entry> all() {
        prune();
        return new ArrayList<Entry>(mMap.values());
    }

    private void prune() {
        long now = mClock.now();
        Iterator<Map.Entry<String, Entry>> it = mMap.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().updatedAt > TTL_MS) it.remove();
        }
        while (mMap.size() > MAX) {
            mMap.remove(mMap.keySet().iterator().next());
        }
    }

    private void save() {
        if (mStore == null) return;
        JSONArray arr = new JSONArray();
        for (Entry e : mMap.values()) arr.put(e.toJson());
        mStore.save(arr.toString());
    }
}
