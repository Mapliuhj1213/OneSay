package com.onesay.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [workflow-1120] 工作流节点图的文件格式（onesay.workflow/1）与校验。
 *
 * <p>外形沿用自由布局画布的 JSON（nodes / edges，节点 {id,type,meta.position,data}，边 {sourceNodeID,targetNodeID,sourcePortID,targetPortID}），
 * 这样画布 toJSON 出来的东西不用转换就能存、能跑。顶层多了 schema / id / name / requires（依赖清单：skill 与连接器）/ shortcut。
 * 格式细节见 docs 与 /workspace/work/preview-1120/WORKFLOW.md。
 *
 * <p>循环不用子画布：循环节点有两个出口 —— body（循环体，每一项跑一遍）和 next（跑完以后）。从 body 出口能走到的节点就是循环体，
 * 循环体不能连到外面、外面也不能连进来（校验里查）。这样整张图仍是无环图。
 */
public final class WfSchema {
    private WfSchema() { }

    public static final String SCHEMA = "onesay.workflow/1";
    public static final Set<String> TYPES = Set.of("start", "end", "llm", "condition", "loop", "code", "tool", "skill", "api");
    public static final Set<String> COND_OPS = Set.of("==", "!=", ">", ">=", "<", "<=", "contains", "not_contains", "empty", "not_empty", "true", "false");
    public static final int MAX_NODES = 300, MAX_EDGES = 1000, MAX_JSON = 1024 * 1024, MAX_CODE = 64 * 1024;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** {{节点id.字段.字段[0]}}；节点 id 也可以写 input（= 开始节点收到的参数）。 */
    public static final Pattern REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)((?:\\.[A-Za-z0-9_\\u4e00-\\u9fa5-]+|\\[\\d+\\])*)\\s*\\}\\}");
    /** 节点里不许直接写这些名字的值（key 要放连接器里，存在凭据库）。 */
    static final Pattern SECRETISH = Pattern.compile("(?i)^(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token|secret|client[_-]?secret|password|passwd|authorization|x-api-key|cookie)$");

    /** 一条校验问题。fatal = 不能保存 / 运行。 */
    public static final class Issue {
        public final String node, code, text;
        public final boolean fatal;

        Issue(String node, String code, String text, boolean fatal) { this.node = node; this.code = code; this.text = text; this.fatal = fatal; }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            if (node != null) m.put("node", node);
            m.put("code", code);
            m.put("text", text);
            m.put("fatal", fatal);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> nodes(Map<String, Object> wf) {
        Object n = wf == null ? null : wf.get("nodes");
        List<Map<String, Object>> out = new ArrayList<>();
        if (n instanceof List) for (Object o : (List<Object>) n) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> edges(Map<String, Object> wf) {
        Object n = wf == null ? null : wf.get("edges");
        List<Map<String, Object>> out = new ArrayList<>();
        if (n instanceof List) for (Object o : (List<Object>) n) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(Map<String, Object> node) {
        Object d = node.get("data");
        return d instanceof Map ? (Map<String, Object>) d : new TreeMap<>();
    }

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    public static String type(Map<String, Object> n) { return s(n.get("type")); }

    public static String id(Map<String, Object> n) { return s(n.get("id")); }

    /** 边的出口：空 / "out" / "default" 都当默认出口（null）。 */
    public static String port(Map<String, Object> e) {
        String p = s(e.get("sourcePortID")).trim();
        return p.isEmpty() || p.equals("out") || p.equals("default") || p.equals("null") ? null : p;
    }

    /** 把一份工作流规整：补 schema、给缺的 data 补空对象、data.title 缺时用类型名。不改原对象。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(Map<String, Object> wf) {
        Map<String, Object> out = (Map<String, Object>) JsonSchemaLite.copy(wf == null ? new TreeMap<>() : wf);
        out.putIfAbsent("schema", SCHEMA);
        out.putIfAbsent("nodes", new ArrayList<>());
        out.putIfAbsent("edges", new ArrayList<>());
        for (Map<String, Object> n : nodes(out)) {
            if (!(n.get("data") instanceof Map)) n.put("data", new TreeMap<String, Object>());
            if (!(n.get("meta") instanceof Map)) {
                Map<String, Object> meta = new TreeMap<>();
                Map<String, Object> pos = new TreeMap<>();
                pos.put("x", 0L);
                pos.put("y", 0L);
                meta.put("position", pos);
                n.put("meta", meta);
            }
        }
        return out;
    }

    /** 校验整份工作流。 */
    public static List<Issue> validate(Map<String, Object> wf) {
        List<Issue> out = new ArrayList<>();
        if (wf == null) { out.add(new Issue(null, "empty", "工作流是空的", true)); return out; }
        try {
            if (Json.stringify(wf).length() > MAX_JSON) out.add(new Issue(null, "too_big", "工作流太大（超过 1 MB）", true));
        } catch (RuntimeException e) {
            out.add(new Issue(null, "bad_json", "工作流里有写不成 JSON 的值", true));
            return out;
        }
        Object sc = wf.get("schema");
        if (sc != null && !SCHEMA.equals(sc)) out.add(new Issue(null, "schema", "不认识的格式版本：" + sc + "（只认 " + SCHEMA + "）", true));
        if (!(wf.get("nodes") instanceof List)) { out.add(new Issue(null, "no_nodes", "缺 nodes 列表", true)); return out; }
        if (wf.get("edges") != null && !(wf.get("edges") instanceof List)) out.add(new Issue(null, "no_edges", "edges 应该是列表", true));
        List<Map<String, Object>> ns = nodes(wf);
        List<Map<String, Object>> es = edges(wf);
        if (ns.size() > MAX_NODES) out.add(new Issue(null, "too_many", "节点太多（最多 " + MAX_NODES + " 个）", true));
        if (es.size() > MAX_EDGES) out.add(new Issue(null, "too_many_edges", "连线太多（最多 " + MAX_EDGES + " 条）", true));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        int starts = 0, ends = 0;
        for (Map<String, Object> n : ns) {
            String id = id(n), t = type(n);
            if (!ID.matcher(id).matches()) { out.add(new Issue(id, "bad_id", "节点编号只能用字母、数字、下划线、短横线（1–64 位）：" + id, true)); continue; }
            if (id.equals("input")) out.add(new Issue(id, "reserved_id", "节点编号不能叫 input（引用开始参数时用）", true));
            if (byId.put(id, n) != null) out.add(new Issue(id, "dup_id", "节点编号重复：" + id, true));
            if (!TYPES.contains(t)) { out.add(new Issue(id, "bad_type", "不认识的节点类型：" + t, true)); continue; }
            if (t.equals("start")) starts++;
            if (t.equals("end")) ends++;
            if (n.get("blocks") instanceof List && !((List<?>) n.get("blocks")).isEmpty()) out.add(new Issue(id, "blocks", "这一版不支持子画布（blocks）：循环体请从循环节点的「循环体」出口连出去", true));
            checkData(id, t, data(n), out);
            secretScan(id, n.get("data"), out, 0);
        }
        if (starts != 1) out.add(new Issue(null, "start", starts == 0 ? "缺开始节点" : "开始节点只能有一个", true));
        if (ends < 1) out.add(new Issue(null, "end", "缺结束节点", true));
        Set<String> seenEdge = new HashSet<>();
        for (Map<String, Object> e : es) {
            String a = s(e.get("sourceNodeID")), b = s(e.get("targetNodeID"));
            Map<String, Object> sa = byId.get(a), sb = byId.get(b);
            if (sa == null || sb == null) { out.add(new Issue(sa == null ? a : b, "dangling", "连线连到了不存在的节点：" + a + " → " + b, true)); continue; }
            if (a.equals(b)) out.add(new Issue(a, "self_loop", "节点不能连自己", true));
            if (!seenEdge.add(a + "|" + port(e) + "|" + b)) out.add(new Issue(a, "dup_edge", "重复的连线：" + a + " → " + b, false));
            String ta = type(sa), p = port(e);
            if (ta.equals("end")) out.add(new Issue(a, "end_out", "结束节点不能再往后连", true));
            if (type(sb).equals("start")) out.add(new Issue(b, "start_in", "开始节点不能有进来的连线", true));
            if (ta.equals("condition")) { if (!"true".equals(p) && !"false".equals(p)) out.add(new Issue(a, "cond_port", "条件节点要从「是」或「否」出口连出", true)); }
            else if (ta.equals("loop")) { if (p != null && !p.equals("body") && !p.equals("next")) out.add(new Issue(a, "loop_port", "循环节点只有「循环体」和「之后」两个出口", true)); }
            else if (p != null) out.add(new Issue(a, "bad_port", "这个节点只有一个出口（不认识 " + p + "）", true));
        }
        boolean structural = out.stream().anyMatch(i -> i.fatal && (i.code.equals("dangling") || i.code.equals("dup_id") || i.code.equals("bad_id")));
        if (!structural) {
            List<String> cyc = cycle(wf);
            if (!cyc.isEmpty()) out.add(new Issue(cyc.get(0), "cycle", "连线绕成了圈：" + String.join(" → ", cyc) + "（要重复做请用循环节点）", true));
            else {
                loopBodies(wf, out);
                Set<String> reach = reachable(wf);
                for (Map<String, Object> n : ns) if (!reach.contains(id(n)) && !type(n).equals("start")) out.add(new Issue(id(n), "unreachable", "「" + title(n) + "」没有连上，运行时不会执行", false));
            }
        }
        // 变量引用：只能引用存在的节点
        for (Map<String, Object> n : ns) {
            for (String r : refs(n.get("data"))) if (!r.equals("input") && !byId.containsKey(r)) out.add(new Issue(id(n), "bad_ref", "「" + title(n) + "」引用了不存在的节点：" + r, true));
        }
        return out;
    }

    public static boolean ok(List<Issue> issues) {
        for (Issue i : issues) if (i.fatal) return false;
        return true;
    }

    public static String title(Map<String, Object> n) {
        String t = s(data(n).get("title")).trim();
        return t.isEmpty() ? zhType(type(n)) : t;
    }

    public static String zhType(String t) {
        switch (t) {
            case "start": return "开始";
            case "end": return "结束";
            case "llm": return "大模型";
            case "condition": return "条件";
            case "loop": return "循环";
            case "code": return "代码";
            case "tool": return "能力";
            case "skill": return "技能";
            case "api": return "接口";
            default: return t;
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkData(String id, String t, Map<String, Object> d, List<Issue> out) {
        switch (t) {
            case "start":
                if (d.get("inputs") != null) for (String p : JsonSchemaLite.checkSchema(d.get("inputs"))) out.add(new Issue(id, "start_schema", "开始参数声明：" + p, true));
                break;
            case "end":
                if (d.get("output") != null && !(d.get("output") instanceof Map)) out.add(new Issue(id, "end_output", "结束节点的输出应该是「名字 → 值」", true));
                break;
            case "llm":
                if (s(d.get("prompt")).isBlank()) out.add(new Issue(id, "llm_prompt", "大模型节点要写提示词", true));
                break;
            case "condition":
                if (!COND_OPS.contains(s(d.get("op")))) out.add(new Issue(id, "cond_op", "条件节点的比较方式不认识：" + s(d.get("op")), true));
                break;
            case "loop":
                if (d.get("items") == null || s(d.get("items")).isBlank()) out.add(new Issue(id, "loop_items", "循环节点要指定按哪个列表循环", true));
                if (d.get("max") != null && !(d.get("max") instanceof Number)) out.add(new Issue(id, "loop_max", "最多循环次数应该是数字", true));
                break;
            case "code":
                if (s(d.get("code")).isBlank()) out.add(new Issue(id, "code_empty", "代码节点没有代码", true));
                if (s(d.get("code")).length() > MAX_CODE) out.add(new Issue(id, "code_big", "代码太长（最多 64 KB）", true));
                if (d.get("language") != null && !"javascript".equals(d.get("language")) && !"js".equals(d.get("language"))) out.add(new Issue(id, "code_lang", "这一版只支持 JavaScript", true));
                break;
            case "tool":
                if (s(d.get("capability")).isBlank()) out.add(new Issue(id, "tool_cap", "能力节点要选一个能力", true));
                break;
            case "skill": {
                Object sk = d.get("skill");
                if (!(sk instanceof Map) || s(((Map<String, Object>) sk).get("id")).isBlank()) out.add(new Issue(id, "skill_ref", "技能节点要选一个技能", true));
                break;
            }
            case "api":
                if (s(d.get("connector")).isBlank()) out.add(new Issue(id, "api_conn", "接口节点要选一个连接器", true));
                if (d.get("method") != null && !s(d.get("method")).matches("(?i)GET|POST|PUT|PATCH|DELETE")) out.add(new Issue(id, "api_method", "请求方法只能是 GET / POST / PUT / PATCH / DELETE", true));
                break;
            default:
                break;
        }
        if (d.get("timeoutMs") != null && !(d.get("timeoutMs") instanceof Number)) out.add(new Issue(id, "timeout", "超时应该是毫秒数", true));
    }

    @SuppressWarnings("unchecked")
    static void secretScan(String id, Object v, List<Issue> out, int depth) {
        if (depth > 10) return;
        if (v instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
            Object x = e.getValue();
            if (SECRETISH.matcher(e.getKey()).matches() && x instanceof String && !((String) x).isBlank() && !REF.matcher((String) x).find())
                out.add(new Issue(id, "secret_inline", "节点里不能直接写 key / 口令（" + e.getKey() + "）：请放进连接器，存在系统凭据库里", true));
            secretScan(id, x, out, depth + 1);
        }
        else if (v instanceof List) for (Object o : (List<Object>) v) secretScan(id, o, out, depth + 1);
    }

    /** 一个值里引用到的节点 id（去重）。 */
    @SuppressWarnings("unchecked")
    public static Set<String> refs(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof String) { Matcher m = REF.matcher((String) v); while (m.find()) out.add(m.group(1)); }
        else if (v instanceof Map) for (Object o : ((Map<String, Object>) v).values()) out.addAll(refs(o));
        else if (v instanceof List) for (Object o : (List<Object>) v) out.addAll(refs(o));
        return out;
    }

    /** 有环就返回环上的节点 id（首尾相同），没有返回空。 */
    public static List<String> cycle(Map<String, Object> wf) {
        Map<String, List<String>> adj = new HashMap<>();
        for (Map<String, Object> e : edges(wf)) adj.computeIfAbsent(s(e.get("sourceNodeID")), k -> new ArrayList<>()).add(s(e.get("targetNodeID")));
        Map<String, Integer> color = new HashMap<>();
        for (Map<String, Object> n : nodes(wf)) {
            List<String> path = new ArrayList<>();
            List<String> c = dfs(id(n), adj, color, path);
            if (c != null) return c;
        }
        return List.of();
    }

    private static List<String> dfs(String u, Map<String, List<String>> adj, Map<String, Integer> color, List<String> path) {
        Integer c = color.get(u);
        if (c != null && c == 2) return null;
        if (c != null && c == 1) {
            List<String> cyc = new ArrayList<>(path.subList(path.indexOf(u), path.size()));
            cyc.add(u);
            return cyc;
        }
        color.put(u, 1);
        path.add(u);
        for (String v : adj.getOrDefault(u, List.of())) {
            List<String> r = dfs(v, adj, color, path);
            if (r != null) return r;
        }
        path.remove(path.size() - 1);
        color.put(u, 2);
        return null;
    }

    /** 循环节点的循环体：从 body 出口能走到的所有节点（含嵌套循环的循环体）。 */
    public static Set<String> body(Map<String, Object> wf, String loopId) {
        Map<String, List<String>> adj = new HashMap<>();
        Set<String> heads = new LinkedHashSet<>();
        for (Map<String, Object> e : edges(wf)) {
            String a = s(e.get("sourceNodeID")), b = s(e.get("targetNodeID"));
            if (a.equals(loopId)) { if ("body".equals(port(e))) heads.add(b); continue; }
            adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        }
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>(heads);
        while (!q.isEmpty()) {
            String u = q.poll();
            if (u.equals(loopId) || !seen.add(u)) continue;
            q.addAll(adj.getOrDefault(u, List.of()));
        }
        return seen;
    }

    /** 循环体的入口节点（循环节点 body 出口直接连的）。 */
    public static List<String> bodyHeads(Map<String, Object> wf, String loopId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : edges(wf)) if (s(e.get("sourceNodeID")).equals(loopId) && "body".equals(port(e)) && !out.contains(s(e.get("targetNodeID")))) out.add(s(e.get("targetNodeID")));
        return out;
    }

    private static void loopBodies(Map<String, Object> wf, List<Issue> out) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> n : nodes(wf)) byId.put(id(n), n);
        for (Map<String, Object> n : nodes(wf)) {
            if (!type(n).equals("loop")) continue;
            String lid = id(n);
            Set<String> b = body(wf, lid);
            List<String> heads = bodyHeads(wf, lid);
            // 「共享」节点：循环体走得到、但循环外面也连进来（或者是开始 / 结束）—— 说明循环体连到了外面
            Set<String> shared = new HashSet<>();
            for (String x : b) {
                String t = type(byId.getOrDefault(x, Map.of()));
                if (t.equals("end") || t.equals("start")) shared.add(x);
            }
            for (Map<String, Object> e : edges(wf)) {
                String a = s(e.get("sourceNodeID")), c = s(e.get("targetNodeID"));
                if (b.contains(c) && !b.contains(a) && !(a.equals(lid) && "body".equals(port(e)))) shared.add(c);
            }
            boolean grew = true;
            while (grew) { // 共享节点往后走到的也都在外面
                grew = false;
                for (Map<String, Object> e : edges(wf)) if (shared.contains(s(e.get("sourceNodeID"))) && b.contains(s(e.get("targetNodeID"))) && shared.add(s(e.get("targetNodeID")))) grew = true;
            }
            for (Map<String, Object> e : edges(wf)) {
                String a = s(e.get("sourceNodeID")), c = s(e.get("targetNodeID"));
                if (b.contains(a) && !shared.contains(a) && shared.contains(c)) out.add(new Issue(a, "body_escape", "循环体里的节点不能连到循环外面（" + a + " → " + c + "）；循环做完后的步骤请从「之后」出口连", true));
            }
            for (String h : heads) if (shared.contains(h)) out.add(new Issue(h, "body_enter", "循环外面的节点不能直接连进循环体（" + h + "）", true));
            for (String x : b) if (!shared.contains(x)) {
                String t = type(byId.getOrDefault(x, Map.of()));
                if (t.equals("end") || t.equals("start")) out.add(new Issue(x, "body_end", "循环体里不能放开始 / 结束节点", true));
            }
        }
    }

    /** 从开始节点出发能走到的节点。 */
    public static Set<String> reachable(Map<String, Object> wf) {
        Map<String, List<String>> adj = new HashMap<>();
        String start = null;
        for (Map<String, Object> n : nodes(wf)) if (type(n).equals("start")) start = id(n);
        for (Map<String, Object> e : edges(wf)) adj.computeIfAbsent(s(e.get("sourceNodeID")), k -> new ArrayList<>()).add(s(e.get("targetNodeID")));
        Set<String> seen = new HashSet<>();
        if (start == null) return seen;
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) { String u = q.poll(); if (seen.add(u)) q.addAll(adj.getOrDefault(u, List.of())); }
        return seen;
    }

    public static List<Map<String, Object>> issuesView(List<Issue> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Issue i : l) out.add(i.view());
        return out;
    }
}
