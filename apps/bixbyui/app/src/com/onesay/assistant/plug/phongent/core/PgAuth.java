/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/LanAccess.kt（Kotlin → Java 7）。
 * 协议（POST /pair {requestId,name} → 202 pending / 429 / 409；GET /pair/&lt;id&gt; → pending/approved+token/denied/expired；
 * 90 秒有效、15 秒限一次、同时只一个待批）与上游一致。改动：
 * ① 去掉 Android 依赖（存储 / 弹窗 / 时钟经接口注入，JVM 可测）；
 * ② 本机只存 token 的 SHA-256，不存明文（上游存明文 token）；
 * ③ token 只交付一次（之后状态为 claimed）；
 * ④ 已信任主控上限 {@link #MAX_PEERS}，可逐个撤销；
 * ⑤ 本机手动发放（控制台「手动配对链接」，给 Tailscale 等收不到 mDNS 的情况）。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** [phongent] 主控配对与鉴权。纯 Java；线程安全（所有公开方法 synchronized）。 */
public final class PgAuth {

    public static final long OFFER_TTL_MS = 90000L;
    public static final long REQUEST_GAP_MS = 15000L;
    public static final int MAX_PEERS = 8;
    static final String PEER_PREFIX = "peer:";

    static final class Offer {
        final String id;
        final String name;
        final long deadline;
        String status = "pending";
        String token;

        Offer(String id, String name, long deadline) {
            this.id = id;
            this.name = name;
            this.deadline = deadline;
        }
    }

    public static final class Result {
        public final int code;
        public final JSONObject body;

        Result(int code, JSONObject body) {
            this.code = code;
            this.body = body;
        }
    }

    private final Storage mStore;
    private final Approver mPrompt;
    private final Clock mClock;
    private final SecureRandom mRandom;
    private final Map<String, Offer> mOffers = new HashMap<String, Offer>();
    private long mLastRequest = Long.MIN_VALUE / 2;
    private final Map<String, Long> mLastSeenWrite = new HashMap<String, Long>();

    public PgAuth(Storage store, Approver prompt, Clock clock, SecureRandom random) {
        mStore = store;
        mPrompt = prompt;
        mClock = clock;
        mRandom = random == null ? new SecureRandom() : random;
    }

    // ------------------------------------------------------------------ 配对

    static boolean validRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{64,80}");
    }

    static String cleanName(String raw) {
        if (raw == null || raw.length() == 0) return "主控设备";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 60; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && c != '\u202e' && c != '\u202d') sb.append(c);
        }
        String s = sb.toString().trim();
        return s.length() == 0 ? "主控设备" : s;
    }

    private void expire() {
        long now = mClock.elapsed();
        Iterator<Map.Entry<String, Offer>> it = mOffers.entrySet().iterator();
        while (it.hasNext()) {
            Offer o = it.next().getValue();
            if (o.deadline < now) {
                if ("pending".equals(o.status)) mPrompt.dismissPair(o.id);
                it.remove();
            }
        }
    }

    /** POST /pair。 */
    public synchronized Result request(JSONObject body) {
        String id = body == null ? null : body.optString("requestId", null);
        if (!validRequestId(id)) return new Result(400, PgHttp.err("invalid_request"));
        String name = cleanName(body.optString("name", ""));
        expire();
        Offer existing = mOffers.get(id);
        if (existing != null) return new Result(200, status(existing.status));
        long now = mClock.elapsed();
        if (now - mLastRequest < REQUEST_GAP_MS || hasPending()) return new Result(429, PgHttp.err("approval_pending"));
        if (peerCount() >= MAX_PEERS) return new Result(409, PgHttp.err("too_many_controllers"));
        if (!mPrompt.canPrompt()) return new Result(409, PgHttp.err("enable_android_notifications"));
        mLastRequest = now;
        Offer o = new Offer(id, name, now + OFFER_TTL_MS);
        mOffers.put(id, o);
        mPrompt.askPair(id, name);
        return new Result(202, status("pending"));
    }

    private boolean hasPending() {
        for (Offer o : mOffers.values()) if ("pending".equals(o.status)) return true;
        return false;
    }

    /** 待批申请的主控名（通知 / 对话框用）；过期或已处理返回 null。 */
    public synchronized String pendingName(String id) {
        expire();
        Offer o = mOffers.get(id);
        return o != null && "pending".equals(o.status) ? o.name : null;
    }

    /** 本机允许 / 拒绝。返回是否生效。 */
    public synchronized boolean decide(String id, boolean allow) {
        expire();
        Offer o = mOffers.get(id);
        if (o == null || !"pending".equals(o.status)) return false;
        if (allow) {
            String token = newToken();
            if (savePeer(token, o.name)) {
                o.token = token;
                o.status = "approved";
            } else {
                o.status = "denied";
            }
        } else {
            o.status = "denied";
        }
        mPrompt.dismissPair(id);
        return true;
    }

    /** GET /pair/&lt;id&gt;。token 只交付一次。 */
    public synchronized JSONObject poll(String id) {
        expire();
        Offer o = validRequestId(id) ? mOffers.get(id) : null;
        if (o == null) return status("expired");
        JSONObject r = status(o.status);
        if ("approved".equals(o.status) && o.token != null) {
            try {
                r.put("token", o.token);
            } catch (Exception ignored) {
            }
            o.token = null;
            o.status = "claimed";
        }
        return r;
    }

    private static JSONObject status(String s) {
        JSONObject r = new JSONObject();
        try {
            r.put("status", s);
        } catch (Exception ignored) {
        }
        return r;
    }

    // ------------------------------------------------------------------ token

    String newToken() {
        byte[] b = new byte[32];
        mRandom.nextBytes(b);
        return hex(b);
    }

    public static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    private boolean savePeer(String token, String name) {
        JSONObject v = new JSONObject();
        try {
            v.put("name", name);
            v.put("created", mClock.wall());
            v.put("last", 0L);
        } catch (Exception ignored) {
        }
        return mStore.put(PEER_PREFIX + sha256(token), v.toString());
    }

    /** 本机手动发放（用户在本机控制台点的 = 本机已确认）。返回明文 token（只此一次），失败 null。 */
    public synchronized String issueLocal(String name) {
        if (peerCount() >= MAX_PEERS) return null;
        String token = newToken();
        return savePeer(token, cleanName(name)) ? token : null;
    }

    /**
     * 校验 Authorization 头。通过返回该主控的 peer 键（"peer:&lt;sha256&gt;"），否则 null。
     * 先对来者 token 做 SHA-256 再查表：比较的是摘要，不会因逐字节比较泄露 token 前缀。
     */
    public synchronized String authorize(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();
        if (!token.matches("[0-9a-f]{64}")) return null;
        String key = PEER_PREFIX + sha256(token);
        String v = mStore.all().get(key);
        if (v == null) return null;
        long now = mClock.elapsed();
        Long last = mLastSeenWrite.get(key);
        if (last == null || now - last > 60000L) {
            mLastSeenWrite.put(key, now);
            try {
                JSONObject o = new JSONObject(v);
                o.put("last", mClock.wall());
                mStore.put(key, o.toString());
            } catch (Exception ignored) {
            }
        }
        return key;
    }

    /** 已信任主控的名字（本机提示用）。 */
    public synchronized String peerName(String peerKey) {
        String v = peerKey == null ? null : mStore.all().get(peerKey);
        if (v == null) return "主控设备";
        try {
            return new JSONObject(v).optString("name", "主控设备");
        } catch (Exception e) {
            return "主控设备";
        }
    }

    public synchronized int peerCount() {
        int n = 0;
        for (String k : mStore.all().keySet()) if (k.startsWith(PEER_PREFIX)) n++;
        return n;
    }

    /** 已信任主控：[{key,name,created,last}]（控制台列表 / 撤销用）。 */
    public synchronized JSONArray peers() {
        JSONArray a = new JSONArray();
        List<String> keys = new ArrayList<String>();
        for (String k : mStore.all().keySet()) if (k.startsWith(PEER_PREFIX)) keys.add(k);
        java.util.Collections.sort(keys);
        for (String k : keys) {
            try {
                JSONObject o = new JSONObject(mStore.all().get(k));
                o.put("key", k);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    public synchronized boolean revoke(String peerKey) {
        if (peerKey == null || !peerKey.startsWith(PEER_PREFIX)) return false;
        mLastSeenWrite.remove(peerKey);
        return mStore.remove(peerKey);
    }

    public synchronized int revokeAll() {
        int n = 0;
        for (String k : new ArrayList<String>(mStore.all().keySet())) {
            if (k.startsWith(PEER_PREFIX) && mStore.remove(k)) n++;
        }
        mLastSeenWrite.clear();
        return n;
    }

    public synchronized void clearPending() {
        for (Offer o : mOffers.values()) if ("pending".equals(o.status)) mPrompt.dismissPair(o.id);
        mOffers.clear();
    }
}
