package com.onesay.assistant.v2.quicknote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [quicknote] R10：记一记独立库 quicknote.db 的一条（纯 Java）。字段与 docs「记一记-小布对标方案」§4.9 的表一一对应。
 */
public final class QuickNoteEntry {

    public static final String SRC_LONG = "long", SRC_VOICE = "voice", SRC_SUIKOUJI = "suikouji", SRC_DEBUG = "debug",
            SRC_MIGRATED = "migrated", SRC_HOLD = "hold";

    /** code_state：0 无码 / 1 在岛上 / 2 已取（已关闭）/ 3 已过期。 */
    public static final int CODE_NONE = 0, CODE_ON_ISLAND = 1, CODE_TAKEN = 2, CODE_EXPIRED = 3;

    public long id;
    public long createdAt;
    public long updatedAt;
    public String day = "";
    public String source = SRC_LONG;
    public String category = "";
    public List<String> tags = new ArrayList<String>();
    public String title = "";
    public String summary = "";
    public String body = "";
    /** JSON 数组 [{t,v,r,b}]：t = 实体类型名（PHONE/ADDRESS/DATETIME/URL/TRACKING/CODE/EMAIL/AMOUNT/NAME），v = 值，r = 原文，b = 时间毫秒。 */
    public String entities = "[]";
    public String appPkg = "";
    public String appLabel = "";
    public String activity = "";
    public String codeKind = "";
    public String codeValue = "";
    public int codeState = CODE_NONE;
    public long amountFen;
    public long eventAt;
    public String notes = "";
    public String thumbPath = "";
    public boolean deleted;
    public long deletedAt;
    /** S2 账本里对应那笔账的 id（D9：订单类自动记一笔；0 = 没记）。 */
    public long ledgerId;

    public QuickNoteEntry copy() {
        QuickNoteEntry e = new QuickNoteEntry();
        e.id = id; e.createdAt = createdAt; e.updatedAt = updatedAt; e.day = day; e.source = source; e.category = category;
        e.tags = new ArrayList<String>(tags); e.title = title; e.summary = summary; e.body = body; e.entities = entities;
        e.appPkg = appPkg; e.appLabel = appLabel; e.activity = activity; e.codeKind = codeKind; e.codeValue = codeValue;
        e.codeState = codeState; e.amountFen = amountFen; e.eventAt = eventAt; e.notes = notes; e.thumbPath = thumbPath;
        e.deleted = deleted; e.deletedAt = deletedAt; e.ledgerId = ledgerId;
        return e;
    }

    public boolean hasCode() {
        return codeValue != null && codeValue.length() > 0;
    }

    /** 列表筛选用的大类：取件码/取餐码/券码… 都归「码」；「屏幕」显示为「其他」。 */
    public String group() {
        if (hasCode()) return "码";
        if ("屏幕".equals(category) || category == null || category.length() == 0) return "其他";
        return category;
    }

    public String tagsJson() {
        JSONArray a = new JSONArray();
        for (String t : tags) a.put(t);
        return a.toString();
    }

    public void setTagsJson(String json) {
        tags = new ArrayList<String>();
        if (json == null || json.length() == 0) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                String t = a.optString(i, "");
                if (t.length() > 0) tags.add(t);
            }
        } catch (Exception ignored) {
        }
    }

    /** 实体（解析 entities JSON）。坏数据返回空列表。 */
    public List<Ent> ents() {
        List<Ent> out = new ArrayList<Ent>();
        try {
            JSONArray a = new JSONArray(entities == null ? "[]" : entities);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                out.add(new Ent(o.optString("t", ""), o.optString("v", ""), o.optString("r", ""), o.optLong("b", 0L)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public Ent firstEnt(String type) {
        for (Ent e : ents()) if (e.type.equals(type)) return e;
        return null;
    }

    public static final class Ent {
        public final String type, value, raw;
        public final long at;

        public Ent(String type, String value, String raw, long at) {
            this.type = type; this.value = value; this.raw = raw; this.at = at;
        }
    }

    public static String entsJson(List<Ent> ents) {
        JSONArray a = new JSONArray();
        try {
            for (Ent e : ents) {
                JSONObject o = new JSONObject();
                o.put("t", e.type);
                o.put("v", e.value == null ? "" : e.value);
                if (e.raw != null && e.raw.length() > 0 && !e.raw.equals(e.value)) o.put("r", e.raw);
                if (e.at > 0) o.put("b", e.at);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    @Override public String toString() {
        return "#" + id + " " + category + " " + title + (deleted ? " (deleted)" : "") + (hasCode() ? " code=" + codeKind + "/" + codeState : "");
    }
}
