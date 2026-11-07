/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/ScreenText.kt ScreenJson / ScreenText) to Java 7 +
 * org.json; the element array is capped by serialized characters (default 6000, MIKA-EXEC §6.4 decision 4)
 * instead of by count, and the window tree is not sent. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 给模型的屏幕：Mika phone_observe 的 elements 形状，0–999 坐标，按字数截断。 */
public final class ScreenJson {
    private ScreenJson() {
    }

    public static final int DEFAULT_MAX_CHARS = 6000;

    public static JSONObject element(UiElement e, ScreenSnapshot s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ref", e.ref);
        o.put("role", e.role);
        o.put("name", e.name);
        JSONObject c = new JSONObject();
        c.put("x", UiElement.normalize(e.centerX, s.width));
        c.put("y", UiElement.normalize(e.centerY, s.height));
        o.put("center", c);
        JSONArray b = new JSONArray();
        for (int v : e.bounds) b.put(v);
        o.put("bounds", b);
        if (e.resourceId != null) o.put("resourceId", e.resourceId);
        if (e.hint) o.put("hint", true);
        if (e.editable) o.put("editable", true);
        if (e.scrollable) o.put("scrollable", true);
        if (e.focused && e.editable) o.put("focused", true);
        if (e.secret) o.put("secret", true);
        if (e.placeholder != null && !e.hint) o.put("placeholder", e.placeholder);
        if (!e.states.isEmpty()) {
            JSONArray st = new JSONArray();
            for (String x : e.states) st.put(x);
            o.put("states", st);
        }
        return o;
    }

    /** 元素数组按序列化字数截到 maxChars；out[0] = 被截掉的个数。 */
    public static JSONArray elements(ScreenSnapshot s, int maxChars, int[] omittedOut) {
        JSONArray arr = new JSONArray();
        int used = 2, total = 0, shown = 0;
        boolean full = false;
        for (UiElement e : s.elements) {
            if (!e.meaningful()) continue;
            total++;
            if (full) continue;
            try {
                JSONObject o = element(e, s);
                int len = o.toString().length() + 1;
                if (used + len > maxChars) {
                    full = true;
                    continue;
                }
                arr.put(o);
                used += len;
                shown++;
            } catch (JSONException ignored) {
            }
        }
        if (omittedOut != null && omittedOut.length > 0) omittedOut[0] = total - shown;
        return arr;
    }

    /** phone_observe 结果：currentApp、snapshotId、viewport、elements、treeQuality?、truncated/omitted。 */
    public static JSONObject observe(ScreenSnapshot s, int maxChars) {
        JSONObject o = new JSONObject();
        try {
            int[] om = new int[1];
            o.put("currentApp", s.packageName == null ? "" : s.packageName);
            o.put("snapshotId", s.snapshotId);
            if (s.changedDuringRead) o.put("screenChangedDuringRead", true);
            if (s.ime) o.put("keyboardShown", true);
            o.put("elements", elements(s, maxChars, om));
            String[] q = TreeQuality.of(s);
            if (!TreeQuality.ADEQUATE.equals(q[0])) o.put("treeQuality", q[0] + (q[1] == null ? "" : ":" + q[1]));
            if (om[0] > 0 || s.truncated) {
                o.put("truncated", true);
                if (om[0] > 0) o.put("omitted", om[0]);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 文字版：一行一个 {@code @e3 Button "搜索" (500,50)}；超预算截断并说明剩余数量。 */
    public static String render(ScreenSnapshot s, int maxChars) {
        StringBuilder out = new StringBuilder();
        int shown = 0, total = 0;
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (UiElement e : s.elements) {
            if (!e.meaningful()) continue;
            total++;
            String name = e.name.trim().length() == 0 ? "" : " \"" + e.name.replace('\n', ' ') + "\"";
            lines.add(e.ref + " " + e.role + name + (e.hint ? " hint" : "") + " (" + UiElement.normalize(e.centerX, s.width) + ","
                    + UiElement.normalize(e.centerY, s.height) + ")");
        }
        for (String l : lines) {
            int remaining = lines.size() - shown - 1;
            int reserve = remaining > 0 ? footer(remaining).length() + 1 : 0;
            if (out.length() + l.length() + 1 + reserve > maxChars) break;
            out.append(l).append('\n');
            shown++;
        }
        if (total - shown > 0) out.append(footer(total - shown));
        String r = out.toString();
        while (r.endsWith("\n")) r = r.substring(0, r.length() - 1);
        return r.length() > maxChars ? r.substring(0, maxChars) : r;
    }

    private static String footer(int n) {
        return "…还有 " + n + " 个元素未显示；滚动或缩小范围";
    }
}
