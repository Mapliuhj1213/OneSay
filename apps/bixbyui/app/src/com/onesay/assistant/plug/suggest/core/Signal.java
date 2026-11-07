/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 一个情境信号（不可变）。attrs 里只放判定要用的元数据；敏感原文（剪贴板 / 短信）只有过了敏感过滤才放，且不进日志。 */
public final class Signal {
    public static final String SCREENSHOT = "screenshot";
    public static final String CLIP = "clip";
    public static final String TORCH = "torch";
    public static final String HEADSET = "headset";
    public static final String PKG_ADDED = "pkg_added";
    public static final String SMS = "sms";
    public static final String NOTICE = "notice";
    public static final String MISSED_CALL = "missed_call";
    /** 取件码 / 取餐码已上岛（S1 现有链路），只接入总频控。 */
    public static final String PICKUP = "pickup";
    public static final String BATTERY = "battery";
    public static final String CALENDAR = "calendar";
    /** 通用属性：信号源宣布某场景的前提没了（如手电筒已关）→ 引擎撤掉该场景在岛上的卡、取消它推迟中的候选。值 = 场景 id。 */
    public static final String K_WITHDRAW = "withdraw";

    public final String type;
    public final long atElapsed;
    public final long atWall;
    private final Map<String, String> attrs;

    public Signal(String type, long atElapsed, long atWall, Map<String, String> attrs) {
        this.type = type;
        this.atElapsed = atElapsed;
        this.atWall = atWall;
        this.attrs = attrs == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(attrs));
    }

    public String get(String k) {
        return attrs.get(k);
    }

    public String get(String k, String def) {
        String v = attrs.get(k);
        return v == null ? def : v;
    }

    public long getLong(String k, long def) {
        String v = attrs.get(k);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String k) {
        String v = attrs.get(k);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public Map<String, String> attrs() {
        return attrs;
    }

    /** 便捷构造：k1, v1, k2, v2 …（null 值跳过）。 */
    public static Signal of(String type, long atElapsed, long atWall, String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        if (kv != null) for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        return new Signal(type, atElapsed, atWall, m);
    }

    /** 只给日志：类型 + attr 键名（不含值）。 */
    @Override public String toString() {
        return "Signal(" + type + " keys=" + new java.util.TreeSet<String>(attrs.keySet()) + ")";
    }
}
