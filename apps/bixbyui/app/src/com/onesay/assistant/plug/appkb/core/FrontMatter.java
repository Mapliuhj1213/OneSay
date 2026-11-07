/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: core/knowledge/FrontMatter.kt translated to Java 7. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.appkb.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析 APP.md 的头部：{@code key: value}、{@code key: [a, b]}、带引号字符串。值为 String 或 List&lt;String&gt;。返回 {map, body}。 */
public final class FrontMatter {
    private FrontMatter() {
    }

    public static Object[] parse(String md) {
        String text = md == null ? "" : md;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        if (!text.startsWith("---\n")) return new Object[]{map, text};
        String[] lines = text.split("\n", -1);
        int end = -1;
        for (int i = 1; i < lines.length; i++) if (lines[i].trim().equals("---")) {
            end = i;
            break;
        }
        if (end < 0) return new Object[]{map, text};
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            if (line.trim().length() == 0 || line.trim().startsWith("#")) continue;
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String key = line.substring(0, c).trim(), raw = line.substring(c + 1).trim();
            if (raw.startsWith("[") && raw.endsWith("]")) {
                List<String> l = new ArrayList<String>();
                for (String s : splitList(raw.substring(1, raw.length() - 1))) {
                    String u = unquote(s);
                    if (u.length() > 0) l.add(u);
                }
                map.put(key, l);
            } else map.put(key, unquote(raw));
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            if (body.length() > 0) body.append('\n');
            body.append(lines[i]);
        }
        return new Object[]{map, body.toString().trim()};
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0))
            return t.substring(1, t.length() - 1);
        return t;
    }
}
