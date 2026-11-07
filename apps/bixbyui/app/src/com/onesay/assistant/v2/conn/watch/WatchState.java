package com.onesay.assistant.v2.conn.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [conn/watch] 一个监控任务的运行状态：见过的链接（滚动保留 {@link #MAX_SEEN} 条，防止搜索排名抖动把老结果当新的）、
 * 每个链接最近一次的标题指纹、攒着还没通知的新结果、上次运行 / 通知时间、连续失败次数。
 */
public final class WatchState {

    public static final int MAX_SEEN = 300;
    public static final int MAX_PENDING = 20;

    /** 规范化链接 → 标题指纹（插入序 = 新旧序）。 */
    final LinkedHashMap<String, String> seen = new LinkedHashMap<String, String>();
    final List<WatchItem> pending = new ArrayList<WatchItem>();
    public long lastRunAt;
    public long lastNotifyAt;
    public long lastOkAt;
    public int failures;
    public boolean baselined;
    public String lastError = "";

    public int seenCount() {
        return seen.size();
    }

    public List<WatchItem> pending() {
        return new ArrayList<WatchItem>(pending);
    }

    void remember(String url, String titleHash) {
        seen.remove(url);
        seen.put(url, titleHash);
        while (seen.size() > MAX_SEEN) seen.remove(seen.keySet().iterator().next());
    }

    void addPending(WatchItem it) {
        for (WatchItem p : pending) if (p.url.equals(it.url)) return;
        pending.add(it);
        while (pending.size() > MAX_PENDING) pending.remove(0);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject s = new JSONObject();
            for (Map.Entry<String, String> e : seen.entrySet()) s.put(e.getKey(), e.getValue());
            JSONArray p = new JSONArray();
            for (WatchItem it : pending) p.put(it.toJson());
            o.put("seen", s).put("pending", p).put("last_run", lastRunAt).put("last_notify", lastNotifyAt).put("last_ok", lastOkAt)
                    .put("failures", failures).put("baselined", baselined).put("last_error", lastError);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static WatchState fromJson(JSONObject o) {
        WatchState st = new WatchState();
        if (o == null) return st;
        JSONObject s = o.optJSONObject("seen");
        if (s != null) {
            java.util.Iterator<?> it = s.keys();
            // JSONObject 不保证顺序：老数据顺序丢失只影响淘汰次序，不影响判重
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                st.seen.put(k, s.optString(k));
            }
        }
        JSONArray p = o.optJSONArray("pending");
        for (int i = 0; p != null && i < p.length(); i++) {
            WatchItem w = WatchItem.fromJson(p.optJSONObject(i));
            if (w != null) st.pending.add(w);
        }
        st.lastRunAt = o.optLong("last_run");
        st.lastNotifyAt = o.optLong("last_notify");
        st.lastOkAt = o.optLong("last_ok");
        st.failures = o.optInt("failures");
        st.baselined = o.optBoolean("baselined");
        st.lastError = o.optString("last_error", "");
        return st;
    }
}
