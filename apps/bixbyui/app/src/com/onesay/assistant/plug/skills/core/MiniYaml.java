/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能文件头的极小 YAML 子集解析器（不引第三方库）。支持：
 * <ul>
 *   <li>{@code key: value}（值可带单 / 双引号，引号内原样保留，不处理反斜杠转义 —— 正则直接写 {@code \d}）；</li>
 *   <li>{@code key: [a, "b, c", 'd']} 行内列表；</li>
 *   <li>一层嵌套：{@code key:} 下面缩进的 {@code sub: value} / {@code sub: [..]}；</li>
 *   <li>块列表：{@code key:} 下面缩进的 {@code - item}（顶层或嵌套一层都行）；</li>
 *   <li>{@code #} 开头的注释行、空行。</li>
 * </ul>
 * 不支持：多行字符串、锚点、两层以上嵌套。解析不会抛异常：看不懂的行记进 {@link Result#errors}，调用方决定丢弃还是降级。
 */
public final class MiniYaml {
    private MiniYaml() {
    }

    public static final class Result {
        /** 值：String、List&lt;String&gt; 或 Map&lt;String,Object&gt;（一层）。 */
        public final Map<String, Object> map = new LinkedHashMap<String, Object>();
        public String body = "";
        public boolean hasFrontMatter;
        public final List<String> errors = new ArrayList<String>();
    }

    /** 拆 {@code ---} 头部 + 正文并解析头部。 */
    public static Result parseDocument(String md) {
        Result r = new Result();
        String text = md == null ? "" : md;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            r.body = text.trim();
            r.errors.add("缺少 --- 头部");
            return r;
        }
        String[] lines = text.split("\n", -1);
        int end = -1;
        for (int i = 1; i < lines.length; i++) if (lines[i].trim().equals("---")) {
            end = i;
            break;
        }
        if (end < 0) {
            r.body = text.trim();
            r.errors.add("头部没有结束的 ---");
            return r;
        }
        r.hasFrontMatter = true;
        List<String> head = new ArrayList<String>();
        for (int i = 1; i < end; i++) head.add(lines[i]);
        parseLines(head, r);
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            if (body.length() > 0) body.append('\n');
            body.append(lines[i]);
        }
        r.body = body.toString().trim();
        return r;
    }

    @SuppressWarnings("unchecked")
    static void parseLines(List<String> lines, Result r) {
        String topKey = null;            // 当前打开的顶层键（值为空，等缩进内容）
        Object topVal = null;            // 打开的顶层容器：Map 或 List
        String subKey = null;            // 嵌套 map 里打开的子键（等块列表）
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            if (raw.indexOf('\t') >= 0) {
                r.errors.add("第 " + (n + 2) + " 行有制表符");
                raw = raw.replace("\t", "  ");
            }
            String t = raw.trim();
            if (t.length() == 0 || t.startsWith("#")) continue;
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            if (indent == 0) {
                subKey = null;
                topKey = null;
                topVal = null;
                int c = colon(t);
                if (c <= 0) {
                    r.errors.add("第 " + (n + 2) + " 行看不懂：" + clip(t));
                    continue;
                }
                String k = t.substring(0, c).trim(), v = t.substring(c + 1).trim();
                if (r.map.containsKey(k)) r.errors.add("重复的键：" + k);
                if (v.length() == 0) {
                    topKey = k;
                    topVal = null;      // 由下一行决定是 map 还是 list
                    r.map.put(k, new LinkedHashMap<String, Object>());
                } else {
                    Object val = scalarOrList(v, r, n);
                    if (val != null) r.map.put(k, val);
                }
                continue;
            }
            // 缩进行
            if (topKey == null) {
                r.errors.add("第 " + (n + 2) + " 行缩进不属于任何键：" + clip(t));
                continue;
            }
            if (t.startsWith("- ") || t.equals("-")) {
                String item = unquote(t.length() > 1 ? t.substring(2) : "");
                if (subKey != null && topVal instanceof Map) {
                    Object cur = ((Map<String, Object>) topVal).get(subKey);
                    if (!(cur instanceof List)) {
                        cur = new ArrayList<String>();
                        ((Map<String, Object>) topVal).put(subKey, cur);
                    }
                    if (item.length() > 0) ((List<String>) cur).add(item);
                } else if (topVal == null || topVal instanceof List) {
                    if (topVal == null) {
                        topVal = new ArrayList<String>();
                        r.map.put(topKey, topVal);
                    }
                    if (item.length() > 0) ((List<String>) topVal).add(item);
                } else {
                    r.errors.add("第 " + (n + 2) + " 行列表项位置不对：" + clip(t));
                }
                continue;
            }
            int c = colon(t);
            if (c <= 0) {
                r.errors.add("第 " + (n + 2) + " 行看不懂：" + clip(t));
                continue;
            }
            if (topVal == null) {
                topVal = r.map.get(topKey);
                if (!(topVal instanceof Map)) {
                    topVal = new LinkedHashMap<String, Object>();
                    r.map.put(topKey, topVal);
                }
            }
            if (!(topVal instanceof Map)) {
                r.errors.add("第 " + (n + 2) + " 行：" + topKey + " 已经是列表，不能再放子键");
                continue;
            }
            String k = t.substring(0, c).trim(), v = t.substring(c + 1).trim();
            Map<String, Object> m = (Map<String, Object>) topVal;
            if (m.containsKey(k)) r.errors.add("重复的键：" + topKey + "." + k);
            if (v.length() == 0) {
                subKey = k;
                m.put(k, new ArrayList<String>());
            } else {
                subKey = null;
                Object val = scalarOrList(v, r, n);
                if (val != null) m.put(k, val);
            }
        }
    }

    /** 冒号位置（忽略引号里的冒号；值里的 "://" 不算键分隔：键必须在第一个冒号前且不含引号）。 */
    static int colon(String t) {
        if (t.startsWith("\"") || t.startsWith("'")) return -1;
        return t.indexOf(':');
    }

    static Object scalarOrList(String v, Result r, int n) {
        if (v.startsWith("[")) {
            if (!v.endsWith("]")) {
                r.errors.add("第 " + (n + 2) + " 行列表没有闭合 ]");
                return null;
            }
            List<String> l = new ArrayList<String>();
            List<String> parts = splitList(v.substring(1, v.length() - 1));
            if (parts == null) {
                r.errors.add("第 " + (n + 2) + " 行列表引号没有闭合");
                return null;
            }
            for (String s : parts) {
                String u = unquote(s);
                if (u.length() > 0) l.add(u);
            }
            return l;
        }
        if ((v.startsWith("\"") || v.startsWith("'")) && (v.length() < 2 || v.charAt(v.length() - 1) != v.charAt(0))) {
            r.errors.add("第 " + (n + 2) + " 行引号没有闭合");
            return null;
        }
        return unquote(v);
    }

    /** 逗号切分（引号内的逗号不切）；引号没闭合返回 null。 */
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
        if (quote != 0) return null;
        out.add(cur.toString());
        return out;
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0))
            return t.substring(1, t.length() - 1);
        return t;
    }

    static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
