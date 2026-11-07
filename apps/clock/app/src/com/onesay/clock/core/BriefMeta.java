package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 成品的身份与内容摘要（brief.json），和 brief.wav 一起原子提交。 */
public final class BriefMeta {
    public long forAlarmAt, generatedAt;
    public int day;                       // forAlarmAt 的本地 dayKey
    public String script = "";
    public String newsSource = "";        // 中文来源名（CP 行）
    public String weatherLine = "", wxKey = "", city = "";
    public double temp = Double.NaN;
    public final List<String> news = new ArrayList<String>();
    public long durationMs;
    public final List<String> degraded = new ArrayList<String>();
    public long genMs;
    /** 0.1.6 降雨提醒句（说了的那句；没说 = ""）。 */
    public String rainLine = "";

    public void degrade(String tag) { if (!degraded.contains(tag)) degraded.add(tag); }

    public String toJson() {
        try {
            JSONObject o = new JSONObject().put("forAlarmAt", forAlarmAt).put("generatedAt", generatedAt).put("day", day)
                    .put("script", script).put("newsSource", newsSource).put("weatherLine", weatherLine).put("wxKey", wxKey)
                    .put("city", city).put("durationMs", durationMs).put("genMs", genMs).put("rainLine", rainLine);
            if (!Double.isNaN(temp)) o.put("temp", temp);
            JSONArray n = new JSONArray();
            for (String s : news) n.put(s);
            o.put("news", n);
            JSONArray d = new JSONArray();
            for (String s : degraded) d.put(s);
            o.put("degraded", d);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static BriefMeta fromJson(String s) {
        if (s == null || s.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(s);
            BriefMeta m = new BriefMeta();
            m.forAlarmAt = o.optLong("forAlarmAt");
            m.generatedAt = o.optLong("generatedAt");
            m.day = o.optInt("day");
            m.script = o.optString("script", "");
            m.newsSource = o.optString("newsSource", "");
            m.weatherLine = o.optString("weatherLine", "");
            m.wxKey = o.optString("wxKey", "");
            m.city = o.optString("city", "");
            m.durationMs = o.optLong("durationMs");
            m.genMs = o.optLong("genMs");
            m.rainLine = o.optString("rainLine", "");
            m.temp = o.has("temp") ? o.optDouble("temp") : Double.NaN;
            JSONArray n = o.optJSONArray("news");
            if (n != null) for (int i = 0; i < n.length(); i++) m.news.add(n.optString(i));
            JSONArray d = o.optJSONArray("degraded");
            if (d != null) for (int i = 0; i < d.length(); i++) m.degraded.add(d.optString(i));
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
