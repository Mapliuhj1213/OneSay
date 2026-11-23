package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [workflow-1120] JSON Schema 的一个小子集：skill 的输入输出声明、工作流开始节点的参数、节点表单都用它。
 * 只认这些关键字：type（string / number / integer / boolean / object / array）、properties、required、items、enum、
 * default、title、description、minimum、maximum、maxLength、maxItems。别的关键字一律当「不支持」报出来（{@link #checkSchema}），
 * 免得声明里写了以为生效、其实没检查。
 */
public final class JsonSchemaLite {
    private JsonSchemaLite() { }

    static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array");
    static final Set<String> KEYS = Set.of("type", "properties", "required", "items", "enum", "default", "title", "description",
            "minimum", "maximum", "maxLength", "maxItems", "x-widget");

    /** 声明本身合不合子集。返回问题列表（空 = 合格）。 */
    public static List<String> checkSchema(Object schema) {
        List<String> out = new ArrayList<>();
        check(schema, "$", out, 0);
        return out;
    }

    private static void check(Object s, String at, List<String> out, int depth) {
        if (depth > 8) { out.add(at + "：嵌套太深（最多 8 层）"); return; }
        if (!(s instanceof Map)) { out.add(at + "：应该是一个对象"); return; }
        Map<?, ?> m = (Map<?, ?>) s;
        for (Object k : m.keySet()) if (!KEYS.contains(String.valueOf(k))) out.add(at + "：不支持的关键字 " + k);
        Object t = m.get("type");
        if (t != null && !TYPES.contains(String.valueOf(t))) out.add(at + "：不支持的类型 " + t);
        Object p = m.get("properties");
        if (p != null) {
            if (!(p instanceof Map)) out.add(at + ".properties：应该是对象");
            else for (Map.Entry<?, ?> e : ((Map<?, ?>) p).entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) out.add(at + "：参数名 " + name + " 只能用字母、数字、下划线");
                check(e.getValue(), at + "." + name, out, depth + 1);
            }
        }
        Object r = m.get("required");
        if (r != null && !(r instanceof List)) out.add(at + ".required：应该是数组");
        Object it = m.get("items");
        if (it != null) check(it, at + "[]", out, depth + 1);
        Object en = m.get("enum");
        if (en != null && !(en instanceof List)) out.add(at + ".enum：应该是数组");
    }

    /** 按声明检查一个值，并补上默认值。返回 [补好的值, 错误列表]。 */
    public static Object[] validate(Object schema, Object value) {
        List<String> errs = new ArrayList<>();
        Object v = walk(schema, value, "", errs, 0);
        return new Object[] {v, errs};
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object schema, Object v, String at, List<String> errs, int depth) {
        if (!(schema instanceof Map) || depth > 8) return v;
        Map<String, Object> s = (Map<String, Object>) schema;
        String name = at.isEmpty() ? "参数" : at;
        if (v == null && s.containsKey("default")) v = copy(s.get("default"));
        if (v == null) return null;
        String t = s.get("type") == null ? "" : String.valueOf(s.get("type"));
        switch (t) {
            case "string":
                if (v instanceof Number || v instanceof Boolean) v = String.valueOf(v instanceof Double && ((Double) v) == Math.rint((Double) v) ? (Object) ((Double) v).longValue() : v);
                if (!(v instanceof String)) { errs.add(name + " 应该是文字"); return v; }
                if (s.get("maxLength") instanceof Number && ((String) v).length() > ((Number) s.get("maxLength")).intValue()) errs.add(name + " 太长（最多 " + ((Number) s.get("maxLength")).intValue() + " 字）");
                break;
            case "number":
            case "integer":
                if (v instanceof String) {
                    try { v = t.equals("integer") ? (Object) Long.parseLong(((String) v).trim()) : (Object) Double.parseDouble(((String) v).trim()); } catch (NumberFormatException e) { errs.add(name + " 应该是数字"); return v; }
                }
                if (!(v instanceof Number)) { errs.add(name + " 应该是数字"); return v; }
                if (t.equals("integer")) {
                    double d = ((Number) v).doubleValue();
                    if (d != Math.rint(d)) { errs.add(name + " 应该是整数"); return v; }
                    v = (long) d;
                }
                double d = ((Number) v).doubleValue();
                if (s.get("minimum") instanceof Number && d < ((Number) s.get("minimum")).doubleValue()) errs.add(name + " 不能小于 " + s.get("minimum"));
                if (s.get("maximum") instanceof Number && d > ((Number) s.get("maximum")).doubleValue()) errs.add(name + " 不能大于 " + s.get("maximum"));
                break;
            case "boolean":
                if (v instanceof String && (v.equals("true") || v.equals("false"))) v = Boolean.valueOf((String) v);
                if (!(v instanceof Boolean)) errs.add(name + " 应该是 是/否");
                break;
            case "array": {
                if (v instanceof String) { try { v = Json.parse((String) v); } catch (RuntimeException e) { /* 下面报 */ } }
                if (!(v instanceof List)) { errs.add(name + " 应该是列表"); return v; }
                List<Object> in = (List<Object>) v, outL = new ArrayList<>();
                if (s.get("maxItems") instanceof Number && in.size() > ((Number) s.get("maxItems")).intValue()) errs.add(name + " 太多（最多 " + s.get("maxItems") + " 项）");
                for (int i = 0; i < in.size(); i++) outL.add(walk(s.get("items"), in.get(i), name + "[" + i + "]", errs, depth + 1));
                v = outL;
                break;
            }
            case "object": {
                if (v instanceof String) { try { v = Json.parse((String) v); } catch (RuntimeException e) { /* 下面报 */ } }
                if (!(v instanceof Map)) { errs.add(name + " 应该是对象"); return v; }
                Map<String, Object> in = (Map<String, Object>) v, outM = new TreeMap<>(in);
                Map<String, Object> props = s.get("properties") instanceof Map ? (Map<String, Object>) s.get("properties") : Map.of();
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    Object pv = walk(e.getValue(), in.get(e.getKey()), at.isEmpty() ? e.getKey() : at + "." + e.getKey(), errs, depth + 1);
                    if (pv != null) outM.put(e.getKey(), pv);
                }
                if (s.get("required") instanceof List) for (Object r : (List<Object>) s.get("required")) {
                    Object pv = outM.get(String.valueOf(r));
                    if (pv == null || (pv instanceof String && ((String) pv).isEmpty())) errs.add((at.isEmpty() ? "" : at + ".") + r + " 必填");
                }
                v = outM;
                break;
            }
            default:
                break;
        }
        if (s.get("enum") instanceof List && !((List<Object>) s.get("enum")).isEmpty()) {
            boolean hit = false;
            for (Object e : (List<Object>) s.get("enum")) if (String.valueOf(e).equals(String.valueOf(v))) hit = true;
            if (!hit) errs.add(name + " 只能是 " + s.get("enum"));
        }
        return v;
    }

    /** 表单字段（给界面画表单、给节点画端口）：[{name,type,title,description,required,enum,default}]。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> fields(Object schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(schema instanceof Map)) return out;
        Map<String, Object> s = (Map<String, Object>) schema;
        Map<String, Object> props = s.get("properties") instanceof Map ? (Map<String, Object>) s.get("properties") : Map.of();
        List<Object> req = s.get("required") instanceof List ? (List<Object>) s.get("required") : List.of();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Map<String, Object> p = e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : Map.of();
            Map<String, Object> f = new TreeMap<>();
            f.put("name", e.getKey());
            f.put("type", p.getOrDefault("type", "string"));
            f.put("title", p.getOrDefault("title", e.getKey()));
            if (p.get("description") != null) f.put("description", p.get("description"));
            if (p.get("enum") != null) f.put("enum", p.get("enum"));
            if (p.get("default") != null) f.put("default", p.get("default"));
            f.put("required", req.contains(e.getKey()));
            out.add(f);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object copy(Object v) {
        if (v instanceof Map) { Map<String, Object> m = new TreeMap<>(); for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) m.put(e.getKey(), copy(e.getValue())); return m; }
        if (v instanceof List) { List<Object> l = new ArrayList<>(); for (Object o : (List<Object>) v) l.add(copy(o)); return l; }
        return v;
    }
}
