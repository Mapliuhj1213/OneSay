package com.onesay.assistant.v2.suggest.shot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ feat/suggest：截图分析的结构化结果（纯 Java，JVM 可测）。视觉模型被要求只输出
 * {@code {"summary":..,"text":..,"entities":[{"type":..,"value":..}]}}；容忍 ```json 包裹、前后废话；不是 JSON 时整段当摘要。
 * 结果来自截图（第三方内容），展示时标「未核实」，从结果里做的动作仍走 ExecutionGate。
 */
public final class ScreenshotResult {
    public static final String PROMPT = "用中文简要说明这张手机截图是什么（≤60 字）；完整提取图中文字；列出其中的快递单号、地址、电话、链接、日期时间、取件码。"
            + "只输出 JSON：{\"summary\":..,\"text\":..,\"entities\":[{\"type\":..,\"value\":..}]}";
    public static final String UNTRUSTED_NOTE = "（内容来自截图，未核实）";

    public static final class Entity {
        public final String type, value;

        Entity(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    public final String summary;
    public final String text;
    public final List<Entity> entities;
    public final boolean structured;

    ScreenshotResult(String summary, String text, List<Entity> entities, boolean structured) {
        this.summary = summary == null ? "" : summary.trim();
        this.text = text == null ? "" : text.trim();
        this.entities = Collections.unmodifiableList(entities);
        this.structured = structured;
    }

    public static ScreenshotResult parse(String answer) {
        if (answer == null) return new ScreenshotResult("", "", new ArrayList<Entity>(), false);
        String a = answer.trim();
        int i = a.indexOf('{'), j = a.lastIndexOf('}');
        if (i >= 0 && j > i) {
            try {
                JSONObject o = new JSONObject(a.substring(i, j + 1));
                List<Entity> es = new ArrayList<Entity>();
                JSONArray arr = o.optJSONArray("entities");
                if (arr != null) for (int k = 0; k < arr.length() && es.size() < 20; k++) {
                    JSONObject e = arr.optJSONObject(k);
                    if (e == null) continue;
                    String t = e.optString("type", "").trim(), v = e.optString("value", "").trim();
                    if (v.length() > 0 && v.length() <= 200) es.add(new Entity(t, v));
                }
                return new ScreenshotResult(o.optString("summary", ""), o.optString("text", ""), es, true);
            } catch (Exception ignored) {
                // 落到非结构化
            }
        }
        return new ScreenshotResult(a.length() > 300 ? a.substring(0, 300) + "…" : a, a, new ArrayList<Entity>(), false);
    }

    /** 给 EntityExtractor 的行：识别出的文字按行 + 模型列出的实体值（实体抽取仍按本地规则判类型）。 */
    public List<String> lines() {
        List<String> out = new ArrayList<String>();
        for (String l : text.split("[\\r\\n]+")) if (l.trim().length() > 0 && out.size() < 80) out.add(l.trim());
        for (Entity e : entities) out.add(e.value);
        return out;
    }

    /** 模型说是取件码 / 取餐码的值（本地规则抽不到时用来出「放到岛上」芯片）。 */
    public List<String> pickupCodes() {
        List<String> out = new ArrayList<String>();
        for (Entity e : entities) {
            String t = e.type.toLowerCase(java.util.Locale.ROOT);
            if ((t.contains("取件") || t.contains("取餐") || t.contains("pickup")) && e.value.matches("[A-Za-z0-9-]{3,16}")) out.add(e.value);
        }
        return out;
    }

    /** 面板里显示的正文。 */
    public String display() {
        StringBuilder b = new StringBuilder();
        b.append(summary.length() > 0 ? summary : "没看出这张截图在说什么。");
        if (structured && text.length() > 0) {
            String t = text.length() > 500 ? text.substring(0, 500) + "…" : text;
            b.append("\n\n图中文字：\n").append(t);
        }
        b.append("\n\n").append(UNTRUSTED_NOTE);
        return b.toString();
    }
}
