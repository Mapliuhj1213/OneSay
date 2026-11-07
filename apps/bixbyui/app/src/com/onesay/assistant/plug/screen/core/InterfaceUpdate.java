/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/InterfaceUpdate.kt) to Java 7 + org.json; treeChanges
 * removed (no window tree is sent), the full fallback uses the 6000-char element budget, and a consecutive-diff
 * limit (5) is added as in MIKA-EXEC §0.2. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 两屏之间的差异（按稳定编号对齐），给写操作结果的 screen 字段：
 * {"snapshotId","base","currentApp","full":false,"added":[元素],"removed":[{ref,name,role}],"changed":[元素+"was"],"unchanged":N}。
 * 没有 base、前台 App 变了、差异超过全量一半、连续差异超过 5 次时发全量：{"snapshotId","currentApp","full":true,"elements":[…]}。
 */
public final class InterfaceUpdate {
    private InterfaceUpdate() {
    }

    static final double MAX_DIFF_RATIO = 0.5;
    public static final int MAX_CONSECUTIVE_DIFFS = 5;

    public static JSONObject between(ScreenSnapshot base, ScreenSnapshot current, boolean baseVisible, int consecutiveDiffs, int maxChars) {
        JSONObject out = new JSONObject();
        try {
            if (base == null || !baseVisible || consecutiveDiffs >= MAX_CONSECUTIVE_DIFFS || !eq(base.packageName, current.packageName))
                return full(current, maxChars);
            Map<String, JSONObject> before = all(base), now = all(current);
            JSONArray added = new JSONArray(), removed = new JSONArray(), changed = new JSONArray();
            for (Map.Entry<String, JSONObject> en : now.entrySet()) {
                JSONObject old = before.get(en.getKey());
                if (old == null) added.put(en.getValue());
                else if (!old.toString().equals(en.getValue().toString())) {
                    JSONObject c = new JSONObject(en.getValue().toString());
                    if (!old.optString("name").equals(en.getValue().optString("name"))) c.put("was", old.optString("name"));
                    changed.put(c);
                }
            }
            for (Map.Entry<String, JSONObject> en : before.entrySet()) {
                if (now.containsKey(en.getKey())) continue;
                JSONObject r = new JSONObject();
                r.put("ref", en.getKey());
                r.put("name", en.getValue().optString("name"));
                r.put("role", en.getValue().optString("role"));
                removed.put(r);
            }
            int fullChars = 0;
            for (JSONObject o : now.values()) fullChars += o.toString().length() + 1;
            int diffChars = added.toString().length() + removed.toString().length() + changed.toString().length();
            if (diffChars > fullChars * MAX_DIFF_RATIO || diffChars > maxChars) return full(current, maxChars);
            out.put("snapshotId", current.snapshotId);
            out.put("base", base.snapshotId);
            out.put("currentApp", current.packageName == null ? "" : current.packageName);
            out.put("full", false);
            if (current.changedDuringRead) out.put("screenChangedDuringRead", true);
            out.put("added", added);
            out.put("removed", removed);
            out.put("changed", changed);
            out.put("unchanged", now.size() - added.length() - changed.length());
        } catch (JSONException ignored) {
        }
        return out;
    }

    static JSONObject full(ScreenSnapshot s, int maxChars) throws JSONException {
        JSONObject o = ScreenJson.observe(s, maxChars);
        o.put("full", true);
        return o;
    }

    private static Map<String, JSONObject> all(ScreenSnapshot s) throws JSONException {
        Map<String, JSONObject> m = new LinkedHashMap<String, JSONObject>();
        for (UiElement e : s.elements) if (e.meaningful()) m.put(e.ref, ScreenJson.element(e, s));
        return m;
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static boolean isFull(JSONObject update) {
        return update != null && update.optBoolean("full", false);
    }
}
