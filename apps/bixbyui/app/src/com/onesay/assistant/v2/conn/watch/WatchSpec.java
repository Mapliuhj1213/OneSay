package com.onesay.assistant.v2.conn.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [conn/watch] 一个监控任务的定义（P3「盯着某件事，有变化才告诉我」）：搜什么、多久查一次、什么算变化。
 *
 * <ul>
 *   <li>{@link #MODE_NEW}（默认）：出现了以前没见过的结果（按规范化后的链接判重）才算变化；</li>
 *   <li>{@link #MODE_CHANGE}：除了新结果，已有结果的标题变了也算（适合盯一个固定页面的标题，比如公告 / 价格页）。</li>
 * </ul>
 * keywords 非空时，新结果的标题或摘要里必须含其中一个词才算数（过滤噪声）。
 */
public final class WatchSpec {

    public static final String MODE_NEW = "new";
    public static final String MODE_CHANGE = "change";

    /** 查询间隔下限：1 小时（搜索 Key 有额度；监控不是实时推送）。 */
    public static final int MIN_INTERVAL_MIN = 60;
    public static final int MAX_INTERVAL_MIN = 7 * 24 * 60;
    /** 同一任务两次通知之间至少隔多久（期间的新结果攒着，到点一起说）。 */
    public static final long DEFAULT_NOTIFY_GAP_MS = 3 * 3600 * 1000L;

    public final String id;
    public final String title;
    public final String query;
    public final int intervalMin;
    public final String mode;
    public final List<String> keywords;
    public final boolean enabled;
    public final long createdAt;
    public final long notifyGapMs;

    public WatchSpec(String id, String title, String query, int intervalMin, String mode, List<String> keywords, boolean enabled,
                     long createdAt, long notifyGapMs) {
        this.id = id;
        this.title = title == null || title.trim().length() == 0 ? query : title.trim();
        this.query = query == null ? "" : query.trim();
        this.intervalMin = Math.max(MIN_INTERVAL_MIN, Math.min(MAX_INTERVAL_MIN, intervalMin <= 0 ? 360 : intervalMin));
        this.mode = MODE_CHANGE.equals(mode) ? MODE_CHANGE : MODE_NEW;
        List<String> k = new ArrayList<String>();
        if (keywords != null) for (String s : keywords) if (s != null && s.trim().length() > 0) k.add(s.trim());
        this.keywords = Collections.unmodifiableList(k);
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.notifyGapMs = notifyGapMs <= 0 ? DEFAULT_NOTIFY_GAP_MS : notifyGapMs;
    }

    /** @return null = 合法 */
    public String validate() {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) return "id 不合法";
        if (query.length() < 2) return "监控内容太短";
        if (query.length() > 200) return "监控内容太长";
        return null;
    }

    public WatchSpec withEnabled(boolean on) {
        return new WatchSpec(id, title, query, intervalMin, mode, keywords, on, createdAt, notifyGapMs);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray k = new JSONArray();
            for (String s : keywords) k.put(s);
            o.put("id", id).put("title", title).put("query", query).put("interval_min", intervalMin).put("mode", mode)
                    .put("keywords", k).put("enabled", enabled).put("created_at", createdAt).put("notify_gap_ms", notifyGapMs);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static WatchSpec fromJson(JSONObject o) {
        if (o == null) return null;
        List<String> k = new ArrayList<String>();
        JSONArray arr = o.optJSONArray("keywords");
        for (int i = 0; arr != null && i < arr.length(); i++) k.add(arr.optString(i));
        return new WatchSpec(o.optString("id", ""), o.optString("title", ""), o.optString("query", ""), o.optInt("interval_min", 360),
                o.optString("mode", MODE_NEW), k, o.optBoolean("enabled", true), o.optLong("created_at", 0), o.optLong("notify_gap_ms", 0));
    }
}
