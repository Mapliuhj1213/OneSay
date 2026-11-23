package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * [workflow-1120] 工作流的存取（数据目录 workflows/*.json）、导出 / 导入（导出不带任何凭据，导入列出缺的技能和连接器）、
 * AI 改图的待确认修改（先给差异，确认后才生效）、快捷指令（工作流的一键入口）、从一次成功的任务生成工作流草稿。
 */
public final class WfStore {
    private final Path dir;
    private final Consumer<String> log;
    /** AI 提的修改：提案 id → {workflow, before, after, diff, by, at}。只在内存，确认 / 放弃后删掉。 */
    private final Map<String, Map<String, Object>> proposals = new LinkedHashMap<>();
    private long seq;

    public WfStore(Path dir, Consumer<String> log) {
        this.dir = dir;
        this.log = log == null ? s -> { } : log;
    }

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    static boolean safeId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,64}"); }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> st = Files.list(dir)) {
            for (Path p : (Iterable<Path>) st.sorted()::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".json")) continue;
                try {
                    Map<String, Object> wf = Json.obj(Files.readString(p, StandardCharsets.UTF_8));
                    Map<String, Object> m = new TreeMap<>();
                    m.put("id", s(wf.get("id")));
                    m.put("name", s(wf.get("name")));
                    m.put("description", s(wf.get("description")));
                    m.put("nodes", (long) WfSchema.nodes(wf).size());
                    m.put("updated_at", wf.get("updated_at"));
                    if (wf.get("shortcut") instanceof Map) m.put("shortcut", wf.get("shortcut"));
                    out.add(m);
                } catch (IOException | RuntimeException e) {
                    log.accept("工作流文件读不出来：" + fn);
                }
            }
        } catch (IOException e) {
            log.accept("工作流目录读不出来");
        }
        return out;
    }

    public synchronized Map<String, Object> get(String id) {
        if (!safeId(id)) return null;
        Path p = dir.resolve(id + ".json");
        try { return Files.exists(p) ? Json.obj(Files.readString(p, StandardCharsets.UTF_8)) : null; } catch (IOException | RuntimeException e) { return null; }
    }

    /** 保存（校验不过不存）。返回 {ok,text,workflow,issues}。 */
    public synchronized Map<String, Object> save(Map<String, Object> wf0) {
        Map<String, Object> wf = WfSchema.normalize(wf0);
        String id = s(wf.get("id"));
        if (id.isEmpty()) { id = "wf-" + Long.toString(System.currentTimeMillis(), 36); wf.put("id", id); }
        if (!safeId(id)) return fail("工作流编号不合法");
        if (s(wf.get("name")).isBlank()) wf.put("name", "未命名工作流");
        if (s(wf.get("name")).length() > 60) return fail("名字太长（最多 60 字）");
        List<WfSchema.Issue> issues = WfSchema.validate(wf);
        Map<String, Object> r;
        if (!WfSchema.ok(issues)) {
            r = fail("没保存：" + firstFatal(issues));
            r.put("issues", WfSchema.issuesView(issues));
            return r;
        }
        wf.put("requires", requires(wf));
        wf.put("updated_at", System.currentTimeMillis());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("." + id + ".tmp");
            Files.writeString(tmp, Json.stringify(wf), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return fail("写不进去：" + e.getClass().getSimpleName());
        }
        r = ok("已保存");
        r.put("workflow", wf);
        r.put("issues", WfSchema.issuesView(issues));
        return r;
    }

    static String firstFatal(List<WfSchema.Issue> issues) {
        for (WfSchema.Issue i : issues) if (i.fatal) return i.text;
        return "";
    }

    public synchronized Map<String, Object> delete(String id) {
        if (!safeId(id)) return fail("编号不合法");
        try { return Files.deleteIfExists(dir.resolve(id + ".json")) ? ok("已删除") : fail("没有这个工作流"); } catch (IOException e) { return fail("删不掉"); }
    }

    /** 依赖清单：用到的技能（id / 版本 / sha256）和连接器 / 能力。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requires(Map<String, Object> wf) {
        Map<String, Map<String, Object>> skills = new TreeMap<>();
        Set<String> conns = new LinkedHashSet<>(), caps = new LinkedHashSet<>();
        for (Map<String, Object> n : WfSchema.nodes(wf)) {
            Map<String, Object> d = WfSchema.data(n);
            switch (WfSchema.type(n)) {
                case "skill":
                    if (d.get("skill") instanceof Map) {
                        Map<String, Object> ref = (Map<String, Object>) d.get("skill");
                        Map<String, Object> e = new TreeMap<>();
                        e.put("id", s(ref.get("id")));
                        e.put("version", s(ref.get("version")));
                        e.put("sha256", s(ref.get("sha256")));
                        skills.put(s(ref.get("id")), e);
                    }
                    break;
                case "api":
                    conns.add(s(d.get("connector")));
                    break;
                case "llm":
                    if (!s(d.get("connector")).isEmpty()) conns.add(s(d.get("connector")));
                    break;
                case "tool":
                    caps.add(s(d.get("capability")));
                    break;
                default:
                    break;
            }
        }
        Map<String, Object> m = new TreeMap<>();
        m.put("skills", new ArrayList<Object>(skills.values()));
        List<Object> cl = new ArrayList<>();
        for (String c : conns) { Map<String, Object> e = new TreeMap<>(); e.put("id", c); e.put("kind", "api"); cl.add(e); }
        m.put("connectors", cl);
        m.put("capabilities", new ArrayList<Object>(caps));
        return m;
    }

    // ------------------------------------------------------------------ 导出 / 导入

    /** 导出：去掉所有像凭据的字段（就算有人手写进节点），连接器只留 id / 名字 / 类型 / 预设，不留地址里的查询串。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> export(Map<String, Object> wf, ApiConnectors api) {
        Map<String, Object> out = (Map<String, Object>) scrub(JsonSchemaLite.copy(wf));
        out.remove("updated_at");
        Map<String, Object> req = requires(out);
        List<Object> cl = new ArrayList<>();
        for (Object o : (List<Object>) req.get("connectors")) {
            Map<String, Object> e = new TreeMap<>((Map<String, Object>) o);
            ApiConnectors.Conn c = api == null ? null : api.get(s(e.get("id")));
            if (c != null) {
                e.put("name", c.name);
                e.put("type", c.type);
                e.put("preset", c.preset);
                e.put("key_mode", c.keyMode);
                if (!c.keyName.isEmpty()) e.put("key_name", c.keyName);
                String url = c.url;
                int q = url.indexOf('?');
                e.put("url", q >= 0 ? url.substring(0, q) : url);
                if (!c.note.isEmpty()) e.put("note", c.note);
            }
            cl.add(e);
        }
        req.put("connectors", cl);
        out.put("requires", req);
        out.put("exported_by", "OneSay " + Flavor.version());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object scrub(Object v) {
        if (v instanceof Map) {
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (WfSchema.SECRETISH.matcher(e.getKey()).matches() || e.getKey().equalsIgnoreCase("verified_fp") || e.getKey().equalsIgnoreCase("has_key")) continue;
                m.put(e.getKey(), scrub(e.getValue()));
            }
            return m;
        }
        if (v instanceof List) { List<Object> l = new ArrayList<>(); for (Object o : (List<Object>) v) l.add(scrub(o)); return l; }
        if (v instanceof String && ((String) v).matches("(?i)^(Bearer\\s+\\S{8,}|sk-[A-Za-z0-9_-]{12,}|ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|xox[bp]-\\S{10,})$")) return "";
        return v;
    }

    /** 导入前检查：校验 + 缺哪些技能 / 连接器。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> importCheck(Map<String, Object> wf, SkillRegistry skills, ApiConnectors api) {
        Map<String, Object> clean = (Map<String, Object>) scrub(WfSchema.normalize(wf));
        List<WfSchema.Issue> issues = WfSchema.validate(clean);
        List<Object> missingSkills = new ArrayList<>(), missingConns = new ArrayList<>();
        Map<String, Object> req = requires(clean);
        for (Object o : (List<Object>) req.get("skills")) {
            Map<String, Object> e = (Map<String, Object>) o;
            String bad = skills == null ? "技能没开" : skills.check(e);
            if (bad != null) { Map<String, Object> x = new TreeMap<>(e); x.put("why", bad); missingSkills.add(x); }
        }
        Map<String, Object> declared = new HashMap<>();
        if (wf.get("requires") instanceof Map && ((Map<String, Object>) wf.get("requires")).get("connectors") instanceof List)
            for (Object o : (List<Object>) ((Map<String, Object>) wf.get("requires")).get("connectors")) if (o instanceof Map) declared.put(s(((Map<String, Object>) o).get("id")), o);
        for (Object o : (List<Object>) req.get("connectors")) {
            String id = s(((Map<String, Object>) o).get("id"));
            ApiConnectors.Conn c = api == null ? null : api.get(id);
            if (c == null || !api.usable(c)) {
                Map<String, Object> x = new TreeMap<>();
                x.put("id", id);
                Object d = declared.get(id);
                if (d instanceof Map) { for (String k : new String[] {"name", "type", "url", "preset", "key_mode", "key_name", "note"}) if (((Map<String, Object>) d).get(k) != null) x.put(k, ((Map<String, Object>) d).get(k)); }
                x.put("why", c == null ? "缺这个连接，请在「连接」里添加并填 key" : "这个连接还没测试通过");
                missingConns.add(x);
            }
        }
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", WfSchema.ok(issues));
        m.put("issues", WfSchema.issuesView(issues));
        m.put("missing_skills", missingSkills);
        m.put("missing_connectors", missingConns);
        m.put("workflow", clean);
        StringBuilder t = new StringBuilder(WfSchema.ok(issues) ? "可以导入" : "格式有问题：" + firstFatal(issues));
        if (!missingSkills.isEmpty()) t.append("；缺 ").append(missingSkills.size()).append(" 个技能");
        if (!missingConns.isEmpty()) t.append("；缺 ").append(missingConns.size()).append(" 个连接");
        m.put("text", t.toString());
        return m;
    }

    // ------------------------------------------------------------------ AI 改图：差异 + 确认

    /** 两版之间的差异：加 / 删 / 改的节点（改了哪些字段），加 / 删的连线。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> diff(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Map<String, Object>> na = new LinkedHashMap<>(), nb = new LinkedHashMap<>();
        for (Map<String, Object> n : WfSchema.nodes(a)) na.put(WfSchema.id(n), n);
        for (Map<String, Object> n : WfSchema.nodes(b)) nb.put(WfSchema.id(n), n);
        List<Object> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : nb.entrySet()) {
            Map<String, Object> old = na.get(e.getKey());
            if (old == null) { added.add(brief(e.getValue())); continue; }
            List<Object> fields = new ArrayList<>();
            if (!WfSchema.type(old).equals(WfSchema.type(e.getValue()))) fields.add("type");
            Map<String, Object> d1 = WfSchema.data(old), d2 = WfSchema.data(e.getValue());
            Set<String> keys = new LinkedHashSet<>(d1.keySet());
            keys.addAll(d2.keySet());
            for (String k : keys) if (!Json.stringify(d1.get(k)).equals(Json.stringify(d2.get(k)))) fields.add(k);
            if (!fields.isEmpty()) { Map<String, Object> x = brief(e.getValue()); x.put("fields", fields); changed.add(x); }
        }
        for (Map.Entry<String, Map<String, Object>> e : na.entrySet()) if (!nb.containsKey(e.getKey())) removed.add(brief(e.getValue()));
        Set<String> ea = edgeKeys(a), eb = edgeKeys(b);
        List<Object> eAdd = new ArrayList<>(), eDel = new ArrayList<>();
        for (String k : eb) if (!ea.contains(k)) eAdd.add(k);
        for (String k : ea) if (!eb.contains(k)) eDel.add(k);
        Map<String, Object> m = new TreeMap<>();
        m.put("added", added);
        m.put("removed", removed);
        m.put("changed", changed);
        m.put("edges_added", eAdd);
        m.put("edges_removed", eDel);
        m.put("empty", added.isEmpty() && removed.isEmpty() && changed.isEmpty() && eAdd.isEmpty() && eDel.isEmpty());
        m.put("summary", "加 " + added.size() + " 个节点、删 " + removed.size() + " 个、改 " + changed.size() + " 个；连线加 " + eAdd.size() + " 条、删 " + eDel.size() + " 条");
        return m;
    }

    static Map<String, Object> brief(Map<String, Object> n) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", WfSchema.id(n));
        m.put("type", WfSchema.type(n));
        m.put("type_zh", WfSchema.zhType(WfSchema.type(n)));
        m.put("title", WfSchema.title(n));
        return m;
    }

    static Set<String> edgeKeys(Map<String, Object> wf) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> e : WfSchema.edges(wf)) {
            String p = WfSchema.port(e);
            out.add(s(e.get("sourceNodeID")) + (p == null ? "" : ":" + p) + " → " + s(e.get("targetNodeID")));
        }
        return out;
    }

    /** 记下一个待确认的修改（AI 提的）。不改文件。返回 {ok,text,proposal}。 */
    public synchronized Map<String, Object> propose(String wfId, Map<String, Object> before, Map<String, Object> after, String by) {
        Map<String, Object> next = WfSchema.normalize(after);
        if (before != null) {
            for (String k : new String[] {"id", "name", "description", "shortcut"}) if (next.get(k) == null && before.get(k) != null) next.put(k, before.get(k));
        }
        if (!s(wfId).isEmpty()) next.put("id", wfId);
        List<WfSchema.Issue> issues = WfSchema.validate(next);
        if (!WfSchema.ok(issues)) {
            Map<String, Object> r = fail("AI 改出来的图有问题，没采用：" + firstFatal(issues));
            r.put("issues", WfSchema.issuesView(issues));
            return r;
        }
        Map<String, Object> p = new TreeMap<>();
        String pid = "pr-" + (++seq) + "-" + Long.toString(System.currentTimeMillis() % 1_000_000, 36);
        p.put("id", pid);
        p.put("workflow", s(wfId));
        p.put("before", before == null ? new TreeMap<>() : before);
        p.put("after", next);
        p.put("diff", diff(before == null ? new TreeMap<>() : before, next));
        p.put("by", by == null ? "AI" : by);
        p.put("at", System.currentTimeMillis());
        proposals.put(pid, p);
        while (proposals.size() > 20) proposals.remove(proposals.keySet().iterator().next());
        Map<String, Object> r = ok("已生成修改预览，等你确认：" + ((Map<String, Object>) p.get("diff")).get("summary"));
        r.put("proposal", p);
        return r;
    }

    public synchronized Map<String, Object> proposal(String pid) { return proposals.get(pid); }

    public synchronized List<Map<String, Object>> pending(String wfId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : proposals.values()) if (wfId == null || wfId.equals(p.get("workflow"))) out.add(p);
        return out;
    }

    /** 放弃 / 确认后都从待确认里拿掉。确认时由界面把 after 应用到画布（可撤销）再保存。 */
    public synchronized Map<String, Object> take(String pid) { return proposals.remove(pid); }

    // ------------------------------------------------------------------ 快捷指令 / 草稿

    public synchronized Map<String, Object> setShortcut(String id, String name) {
        Map<String, Object> wf = get(id);
        if (wf == null) return fail("没有这个工作流");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) wf.remove("shortcut");
        else {
            if (n.length() > 20) return fail("快捷指令名字最多 20 字");
            for (Map<String, Object> o : list()) if (o.get("shortcut") instanceof Map && n.equals(s(((Map<?, ?>) o.get("shortcut")).get("name"))) && !id.equals(o.get("id"))) return fail("已经有一个叫「" + n + "」的快捷指令了");
            Map<String, Object> sc = new TreeMap<>();
            sc.put("name", n);
            wf.put("shortcut", sc);
        }
        Map<String, Object> r = save(wf);
        if (Boolean.TRUE.equals(r.get("ok"))) r.put("text", n.isEmpty() ? "已取消快捷指令" : "已存成快捷指令「" + n + "」，在工作流页顶上一键运行");
        return r;
    }

    public synchronized List<Map<String, Object>> shortcuts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : list()) if (o.get("shortcut") instanceof Map) {
            Map<String, Object> m = new TreeMap<>();
            m.put("name", ((Map<?, ?>) o.get("shortcut")).get("name"));
            m.put("workflow", o.get("id"));
            m.put("workflow_name", o.get("name"));
            out.add(m);
        }
        return out;
    }

    public synchronized Map<String, Object> byShortcut(String name) {
        for (Map<String, Object> o : list()) if (o.get("shortcut") instanceof Map && s(((Map<?, ?>) o.get("shortcut")).get("name")).equals(name)) return get(s(o.get("id")));
        return null;
    }

    /**
     * 从一次成功的任务生成工作流草稿：steps = [{tool, args, description}]（对话任务卡里的步骤）。
     * 每一步变成一个「能力」节点（MCP / 连接器）或一个说明用的大模型节点，按顺序连起来；草稿只是起点，要用户改好再保存。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> draftFromSteps(String name, List<Object> steps) {
        Map<String, Object> wf = new TreeMap<>();
        wf.put("schema", WfSchema.SCHEMA);
        wf.put("name", name == null || name.isBlank() ? "从任务生成的草稿" : WfCode.clip(name.trim(), 50));
        wf.put("draft", true);
        List<Object> nodes = new ArrayList<>(), edges = new ArrayList<>();
        nodes.add(node("start", "start", 0, Map.of("title", "开始")));
        String prev = "start";
        int i = 0;
        for (Object o : steps == null ? List.of() : steps) {
            if (!(o instanceof Map) || i >= 30) continue;
            Map<String, Object> st = (Map<String, Object>) o;
            String tool = s(st.get("tool")), desc = s(st.get("description"));
            String id = "step" + (++i);
            Map<String, Object> d = new TreeMap<>();
            d.put("title", desc.isEmpty() ? tool : WfCode.clip(desc, 30));
            String type;
            if (tool.startsWith("mcp__")) { type = "tool"; d.put("capability", "mcp:" + tool); d.put("args", st.getOrDefault("args", new TreeMap<>())); }
            else if (tool.startsWith("api:")) { type = "tool"; d.put("capability", tool); d.put("args", st.getOrDefault("args", new TreeMap<>())); }
            else { type = "llm"; d.put("prompt", "（草稿：原任务这一步用了「" + tool + "」：" + desc + "。请改成合适的节点）"); }
            Map<String, Object> scrubbed = (Map<String, Object>) scrub(d);
            nodes.add(node(id, type, i, scrubbed));
            edges.add(edge(prev, id));
            prev = id;
        }
        nodes.add(node("end", "end", i + 1, Map.of("title", "结束", "output", new TreeMap<>())));
        edges.add(edge(prev, "end"));
        wf.put("nodes", nodes);
        wf.put("edges", edges);
        return wf;
    }

    static Map<String, Object> node(String id, String type, int col, Map<String, Object> data) {
        Map<String, Object> n = new TreeMap<>();
        n.put("id", id);
        n.put("type", type);
        Map<String, Object> pos = new TreeMap<>();
        pos.put("x", (long) (col * 320));
        pos.put("y", 0L);
        n.put("meta", Map.of("position", pos));
        n.put("data", new TreeMap<>(data));
        return n;
    }

    static Map<String, Object> edge(String a, String b) {
        Map<String, Object> e = new TreeMap<>();
        e.put("sourceNodeID", a);
        e.put("targetNodeID", b);
        return e;
    }

    static Map<String, Object> ok(String t) { Map<String, Object> m = new TreeMap<>(); m.put("ok", true); m.put("text", t); return m; }

    static Map<String, Object> fail(String t) { Map<String, Object> m = ok(t); m.put("ok", false); return m; }
}
