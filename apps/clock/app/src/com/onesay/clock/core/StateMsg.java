package com.onesay.clock.core;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BRIEF_STATE 广播载荷（与 v2 V2State 同格式，包名换成 com.onesay.clock；接口文档）。
 * state：IDLE / GENERATING / READY / RINGING / PLAYING / DONE / ERROR。
 */
public final class StateMsg {
    private StateMsg() { }

    public static final String ACTION = "com.onesay.clock.BRIEF_STATE";
    public static final String[] STATES = {"IDLE", "GENERATING", "READY", "RINGING", "PLAYING", "DONE", "ERROR"};

    public static Map<String, Object> extras(String state, String detail, BriefMeta m, String briefPath, long now) {
        Map<String, Object> x = new LinkedHashMap<String, Object>();
        x.put("state", state);
        x.put("detail", detail == null ? "" : detail);
        x.put("briefPath", briefPath == null ? "" : briefPath);
        x.put("at", Long.valueOf(now));
        if (m != null) {
            x.put("title", m.city != null && m.city.length() > 0 ? m.city + " 今日播报" : "今日播报");
            x.put("l1", m.weatherLine == null ? "" : m.weatherLine);
            x.put("l2", m.news.size() > 0 ? m.news.get(0) : "");
            x.put("l3", m.news.size() > 1 ? m.news.get(1) : "");
            x.put("generatedAt", Long.valueOf(m.generatedAt));
            x.put("forAlarmAt", Long.valueOf(m.forAlarmAt));
            x.put("source", m.newsSource == null ? "" : m.newsSource);
        }
        return x;
    }

    public static String json(Map<String, Object> x) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : x.entrySet()) o.put(e.getKey(), e.getValue());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
