package com.onesay.assistant.v2.s1;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [s1] #10 取件码浮卡的小仓库（纯 Java，JVM 可测；Android 侧把 {@link #toJson()} 存进 SharedPreferences，服务重启后 {@link #fromJson} 恢复）。
 *
 * <ul>
 *   <li><b>去重</b>：同一 kind+code 在有效期内再来（重复短信、短信+通知双通道）→ 不新建、不重新弹出；
 *       已被用户关掉的同一个码也不会再弹；</li>
 *   <li><b>过期</b>：默认 12 小时（{@link S1Settings#PICKUP_TTL_MS}），{@link #purge} 清掉过期与已关闭超过有效期的条目；</li>
 *   <li><b>上限</b>：最多 {@link #MAX_CARDS} 条，满了挤掉最旧的。</li>
 * </ul>
 * 取件码不是秘密（驿站门口贴着），按用户要求不打码；验证码在 {@link PickupCodeDetector} 那一层就被拦下，到不了这里。
 */
public final class PickupCardStore {

    public static final int MAX_CARDS = 5;

    public static final class Card {
        public final String id;
        public final String code;
        public final String kind;
        public final String station;
        public final long createdAt;
        public final long expiresAt;
        public boolean dismissed;
        /** ★ R10：记一记放上来的码 —— 来源 App 包名（点「打开」拉起它）与记一记条目 id（关掉时同步「已取」）。短信来的为空 / 0。 */
        public String pkg = "";
        public long noteId;

        public Card(String id, String code, String kind, String station, long createdAt, long expiresAt, boolean dismissed) {
            this.id = id;
            this.code = code;
            this.kind = kind;
            this.station = station;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.dismissed = dismissed;
        }

        public boolean fromQuickNote() {
            return noteId > 0;
        }

        public boolean activeAt(long now) {
            return !dismissed && now < expiresAt;
        }

        /** 浮卡正文，例如「取件码 5-2-1234 · 菜鸟驿站」。 */
        public String display() {
            return kind + " " + code + (station != null && station.length() > 0 ? " · " + station : "");
        }
    }

    private final List<Card> mCards = new ArrayList<Card>();

    /**
     * 收到一个取件码。返回新建的卡片；重复（含已关闭的同码）返回 null。
     */
    public synchronized Card add(PickupCodeDetector.Pickup p, long now, long ttlMs) {
        if (p == null) return null;
        purge(now);
        String id = p.key();
        for (Card c : mCards) {
            if (c.id.equals(id) && now < c.expiresAt) return null;
        }
        // 同 id 但已过期的旧卡：移除后重建
        for (int i = mCards.size() - 1; i >= 0; i--) if (mCards.get(i).id.equals(id)) mCards.remove(i);
        Card c = new Card(id, p.code, p.kind, p.station, now, now + ttlMs, false);
        mCards.add(c);
        while (mCards.size() > MAX_CARDS) mCards.remove(0);
        return c;
    }

    /** ★ R10：记一记的码上岛（同 kind+code 已在且有效 → null）。 */
    public synchronized Card addFromQuickNote(String kind, String code, String station, String pkg, long noteId, long now, long ttlMs) {
        Card c = add(new PickupCodeDetector.Pickup(code, kind, station), now, ttlMs);
        if (c != null) {
            c.pkg = pkg == null ? "" : pkg;
            c.noteId = noteId;
        }
        return c;
    }

    /** ★ R10：按 kind+code 关掉（撤销记一记时）。 */
    public synchronized boolean dismissByKey(String kind, String code) {
        return dismiss(new PickupCodeDetector.Pickup(code, kind, null).key());
    }

    public synchronized Card find(String id) {
        for (Card c : mCards) if (c.id.equals(id)) return c;
        return null;
    }

    /** 用户点了 ✕。保留记录（防止同一条短信重复到达时再弹），到期后由 purge 清掉。 */
    public synchronized boolean dismiss(String id) {
        for (Card c : mCards) {
            if (c.id.equals(id) && !c.dismissed) {
                c.dismissed = true;
                return true;
            }
        }
        return false;
    }

    /** 清掉过期条目；返回清掉的条数。 */
    public synchronized int purge(long now) {
        int n = 0;
        for (int i = mCards.size() - 1; i >= 0; i--) {
            if (now >= mCards.get(i).expiresAt) {
                mCards.remove(i);
                n++;
            }
        }
        return n;
    }

    /** 当前该显示的卡片（未关闭且未过期），按到达先后。 */
    public synchronized List<Card> active(long now) {
        List<Card> r = new ArrayList<Card>();
        for (Card c : mCards) if (c.activeAt(now)) r.add(c);
        return r;
    }

    public synchronized int size() {
        return mCards.size();
    }

    /** 最早一张活动卡的到期时刻（无则 0），宿主据此安排下一次刷新。 */
    public synchronized long nextExpiry(long now) {
        long best = 0;
        for (Card c : mCards) {
            if (!c.activeAt(now)) continue;
            if (best == 0 || c.expiresAt < best) best = c.expiresAt;
        }
        return best;
    }

    public synchronized String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Card c : mCards) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("code", c.code);
                o.put("kind", c.kind);
                if (c.station != null) o.put("station", c.station);
                o.put("created", c.createdAt);
                o.put("expires", c.expiresAt);
                o.put("dismissed", c.dismissed);
                if (c.pkg != null && c.pkg.length() > 0) o.put("pkg", c.pkg);
                if (c.noteId > 0) o.put("noteId", c.noteId);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    /** 从持久化文本恢复（坏数据静默丢弃）。 */
    public static PickupCardStore fromJson(String json, long now) {
        PickupCardStore s = new PickupCardStore();
        if (json == null || json.trim().length() == 0) return s;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String code = o.optString("code", "");
                String kind = o.optString("kind", "");
                if (id.length() == 0 || code.length() == 0 || kind.length() == 0) continue;
                String st = o.has("station") ? o.optString("station", null) : null;
                Card c = new Card(id, code, kind, st, o.optLong("created", now), o.optLong("expires", 0L),
                        o.optBoolean("dismissed", false));
                c.pkg = o.optString("pkg", "");
                c.noteId = o.optLong("noteId", 0L);
                s.mCards.add(c);
            }
        } catch (Exception ignored) {
        }
        s.purge(now);
        while (s.mCards.size() > MAX_CARDS) s.mCards.remove(0);
        return s;
    }
}
