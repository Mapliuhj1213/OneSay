package com.onesay.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * [workflow-1120] 工作流 / 技能 / 连接器的总入口：界面接口（/api/wf）、给对话模型的工具（wf__*）、统一的「能力」列表。
 *
 * <p>能力 = 所有来源统一抽象：每个能力有 id、名字、参数格式、风险档（只读 / 本机改动 / 外发 / 花钱）、凭据来源。
 * 来源：MCP（已有）、API 连接器、技能、OAuth 账号。
 */
public final class WfApi {
    /** MCP 的接口（App 接 McpHub；测试里用假的）。 */
    public interface McpPort {
        List<Map<String, Object>> toolDefs();
        boolean readOnly(String fn);
        String describe(String fn);
        Actions.Result call(String fn, Map<String, Object> args);
    }

    public final WfStore store;
    public final SkillRegistry skills;
    public final ApiConnectors api;
    public final OAuthHub oauth;
    public final WfEngine engine;
    public final WfEngine.Hooks hooks = new WfEngine.Hooks();
    public volatile McpPort mcp;
    /** 「改图」用的模型（App 接 Llm；测试里假的）。 */
    public volatile WfEngine.LlmClient llm;
    private final Consumer<String> log;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public WfApi(Path dataDir, SecretStore secrets, Consumer<String> log) {
        this.log = log == null ? s -> { } : log;
        store = new WfStore(dataDir.resolve("workflows"), this.log);
        skills = new SkillRegistry(dataDir.resolve("skills"), this.log);
        api = new ApiConnectors(dataDir.resolve("connectors.json"), secrets, this.log);
        oauth = new OAuthHub(secrets, this.log);
        hooks.skills = skills;
        hooks.api = api;
        hooks.caps = new Caps();
        hooks.llm = (sys, prompt, temp, conn, tmo) -> {
            if (conn != null && !conn.isEmpty()) return llmViaConnector(conn, sys, prompt, temp, tmo);
            WfEngine.LlmClient l = llm;
            if (l == null) throw new IllegalStateException("没配置大模型（设置 › 模型）");
            return l.complete(sys, prompt, temp, "", tmo);
        };
        hooks.httpGet = this::skillHttpGet;
        hooks.secrets = api::knownSecrets;
        engine = new WfEngine(hooks);
    }

    /** 审批（节点）：App 接到确认卡上。 */
    public void approver(WfEngine.Approver a) { hooks.approver = a; }

    /** 审批（装技能）：App 接到确认卡上（只有允许 / 拒绝，不能记住）。 */
    public volatile SkillRegistry.Approver skillApprover = (t, d) -> false;

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    static Map<String, Object> ok(String t) { Map<String, Object> m = new TreeMap<>(); m.put("ok", true); m.put("text", t); return m; }

    static Map<String, Object> fail(String t) { Map<String, Object> m = ok(t); m.put("ok", false); return m; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : new TreeMap<>(); }

    // ------------------------------------------------------------------ 能力

    final class Caps implements WfEngine.Capabilities {
        @Override public String risk(String cap, Map<String, Object> args) {
            if (cap.startsWith("mcp:")) { McpPort m = mcp; return m != null && m.readOnly(cap.substring(4)) ? "read" : "local"; }
            if (cap.startsWith("api:")) return ApiConnectors.risk(api.get(cap.substring(4)), s(args.getOrDefault("method", "GET")));
            if (cap.startsWith("skill:")) { SkillRegistry.Skill k = skills.get(cap.substring(6)); return k == null ? "read" : k.risk(); }
            if (cap.startsWith("oauth:")) return "read";
            return "local";
        }

        @Override public String describe(String cap) {
            if (cap.startsWith("mcp:")) { McpPort m = mcp; return m == null ? cap : m.describe(cap.substring(4)); }
            if (cap.startsWith("api:")) { ApiConnectors.Conn c = api.get(cap.substring(4)); return "API 连接器「" + (c == null ? cap.substring(4) : c.name) + "」"; }
            if (cap.startsWith("skill:")) { SkillRegistry.Skill k = skills.get(cap.substring(6)); return "技能「" + (k == null ? cap.substring(6) : k.name) + "」"; }
            if (cap.startsWith("oauth:")) return "OAuth 账号（" + cap.substring(6) + "）";
            return cap;
        }

        @Override public Map<String, Object> call(String cap, Map<String, Object> args) throws Exception {
            if (cap.startsWith("mcp:")) {
                McpPort m = mcp;
                if (m == null) throw new IllegalStateException("MCP 连接器没开");
                Actions.Result r = m.call(cap.substring(4), args);
                if (!r.ok) throw new IllegalStateException(r.text);
                Map<String, Object> o = new TreeMap<>();
                o.put("text", r.text);
                if (r.data != null) o.put("data", r.data);
                return o;
            }
            if (cap.startsWith("api:")) {
                ApiConnectors.Conn c = api.get(cap.substring(4));
                if (c == null) throw new IllegalStateException("缺少连接器「" + cap.substring(4) + "」");
                if (!api.usable(c)) throw new IllegalStateException("连接器「" + c.name + "」还没测试通过");
                ApiConnectors.Resp r = api.send(c, s(args.getOrDefault("method", "GET")), s(args.get("path")), map(args.get("query")), null, args.get("body"), 60);
                if (r.status == 0) throw new IllegalStateException("请求失败：" + r.error);
                return r.output();
            }
            if (cap.startsWith("skill:")) {
                SkillRegistry.Skill k = skills.get(cap.substring(6));
                if (k == null) throw new IllegalStateException("缺少技能「" + cap.substring(6) + "」");
                return skills.run(k.ref(), args, 10_000, hooks.httpGet);
            }
            if (cap.startsWith("oauth:")) return oauthGet(cap.substring(6), s(args.get("path")));
            throw new IllegalStateException("不认识的能力：" + cap);
        }
    }

    /** 统一能力列表（不含任何 key）。 */
    public List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> out = new ArrayList<>();
        McpPort m = mcp;
        if (m != null) {
            try {
                for (Map<String, Object> t : m.toolDefs()) {
                    Map<String, Object> f = map(t.get("function"));
                    String fn = s(f.get("name"));
                    Map<String, Object> c = new TreeMap<>();
                    c.put("id", "mcp:" + fn);
                    c.put("name", m.describe(fn));
                    c.put("kind", "mcp");
                    c.put("kind_zh", "MCP");
                    c.put("description", WfCode.clip(s(f.get("description")), 200));
                    c.put("risk", m.readOnly(fn) ? "read" : "local");
                    c.put("credential", "由 MCP 服务器自己管");
                    c.put("ready", true);
                    c.put("schema", f.getOrDefault("parameters", new TreeMap<>()));
                    out.add(c);
                }
            } catch (RuntimeException ignored) { }
        }
        out.addAll(api.capabilities());
        out.addAll(skills.capabilities());
        out.addAll(oauth.capabilities());
        for (Map<String, Object> c : out) c.put("risk_zh", WfEngine.riskZh(s(c.get("risk"))));
        return out;
    }

    Map<String, Object> oauthGet(String provider, String path) throws Exception {
        OAuthHub.Provider p = oauth.provider(provider);
        if (p == null || p.apiBase.isEmpty()) throw new IllegalStateException("没有这个账号");
        String tok = oauth.token(provider);
        if (tok == null) throw new IllegalStateException(p.name + " 还没连接");
        String pth = path == null || path.isBlank() ? p.userPath : path.trim();
        if (!pth.startsWith("/") || pth.contains("..") || pth.contains("://")) throw new IllegalArgumentException("路径不合法");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(p.apiBase + pth)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + tok).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> o = new TreeMap<>();
        o.put("status", (long) r.statusCode());
        String b = r.body() == null ? "" : r.body().replace(tok, "••••");
        Object parsed = null;
        try { parsed = Json.parse(b); } catch (RuntimeException ignored) { }
        o.put("body", parsed == null ? WfCode.clip(b, 100_000) : parsed);
        return o;
    }

    /** 技能声明了 net 时才给的 GET：只认 http(s)，不跟跳转，1 MB、10 秒。不带任何 OneSay 的凭据。 */
    String skillHttpGet(String url) {
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || !u.getScheme().matches("(?i)https?")) return "{\"error\":\"只能访问 http / https\"}";
            if (u.getHost() == null || privateHost(u.getHost())) return "{\"error\":\"技能不能访问本机或局域网地址\"}";
            HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] b;
            try (java.io.InputStream in = r.body()) { b = in.readNBytes(1024 * 1024); }
            Map<String, Object> m = new TreeMap<>();
            m.put("status", (long) r.statusCode());
            m.put("body", new String(b, java.nio.charset.StandardCharsets.UTF_8));
            return Json.stringify(m);
        } catch (Exception e) {
            return "{\"error\":\"请求失败\"}";
        }
    }

    /** 本机 / 局域网 / 链路本地地址（技能联网不许碰：本机界面、路由器后台这类）。解析不了也算。 */
    static boolean privateHost(String host) {
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
                byte[] b = a.getAddress();
                if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) return true; // 100.64/10（CGNAT、Tailscale）
                if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return true; // fc00::/7
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 大模型节点选了某个「大模型」类型的连接器：按 OpenAI 兼容的 /chat/completions 调。 */
    String llmViaConnector(String id, String sys, String prompt, double temp, int tmo) throws Exception {
        ApiConnectors.Conn c = api.get(id);
        if (c == null) throw new IllegalStateException("缺少连接器「" + id + "」");
        if (!api.usable(c)) throw new IllegalStateException("连接器「" + c.name + "」还没测试通过");
        if (!"openai".equals(c.preset)) throw new IllegalStateException("大模型节点这一版只支持 OpenAI 兼容的连接器");
        List<Object> msgs = new ArrayList<>();
        if (sys != null && !sys.isBlank()) msgs.add(Map.of("role", "system", "content", sys));
        msgs.add(Map.of("role", "user", "content", prompt));
        Map<String, Object> body = new TreeMap<>();
        body.put("model", c.note.matches("model=[\\w./:-]+") ? c.note.substring(6) : "gpt-4o-mini");
        body.put("messages", msgs);
        body.put("temperature", temp);
        ApiConnectors.Resp r = api.send(c, "POST", "/chat/completions", null, null, body, tmo);
        if (r.status != 200) throw new IllegalStateException("模型接口回了 HTTP " + r.status + (r.error.isEmpty() ? "" : "：" + r.error));
        Object o = Json.parse(r.body);
        Object ch = map(o).get("choices");
        if (ch instanceof List && !((List<?>) ch).isEmpty()) return s(map(map(((List<?>) ch).get(0)).get("message")).get("content"));
        throw new IllegalStateException("模型接口返回的格式不认识");
    }

    // ------------------------------------------------------------------ 界面接口

    /** /api/wf：body.op 决定做什么。 */
    @SuppressWarnings("unchecked")
    public Object handle(Map<String, Object> b) {
        String op = s(b.get("op"));
        switch (op) {
            case "list": {
                Map<String, Object> m = ok("工作流");
                m.put("workflows", store.list());
                m.put("shortcuts", store.shortcuts());
                return m;
            }
            case "get": {
                Map<String, Object> wf = store.get(s(b.get("id")));
                if (wf == null) return fail("没有这个工作流");
                Map<String, Object> m = ok("");
                m.put("workflow", wf);
                m.put("status", nodeStatus(wf));
                m.put("proposals", store.pending(s(b.get("id"))));
                return m;
            }
            case "save": {
                Map<String, Object> r = store.save(map(b.get("workflow")));
                if (Boolean.TRUE.equals(r.get("ok"))) r.put("status", nodeStatus(map(r.get("workflow"))));
                return r;
            }
            case "delete": return store.delete(s(b.get("id")));
            case "validate": {
                Map<String, Object> wf = WfSchema.normalize(map(b.get("workflow")));
                List<WfSchema.Issue> is = WfSchema.validate(wf);
                Map<String, Object> m = WfSchema.ok(is) ? ok("格式没问题") : fail(WfStore.firstFatal(is));
                m.put("issues", WfSchema.issuesView(is));
                m.put("status", nodeStatus(wf));
                return m;
            }
            case "run": {
                Map<String, Object> wf = b.get("workflow") instanceof Map ? map(b.get("workflow")) : store.get(s(b.get("id")));
                if (wf == null) return fail("没有这个工作流");
                String bad = preflight(wf);
                if (bad != null) return fail(bad);
                WfEngine.Run r = engine.start(wf, map(b.get("input")), 0);
                Map<String, Object> m = ok("开始运行");
                m.put("run", r.view());
                return m;
            }
            case "run_status": {
                WfEngine.Run r = engine.run(s(b.get("run")));
                if (r == null) return fail("没有这次运行");
                Map<String, Object> m = ok(WfEngine.statusZh(r.status));
                m.put("run", r.view());
                return m;
            }
            case "cancel": {
                WfEngine.Run r = engine.run(s(b.get("run")));
                if (r == null) return fail("没有这次运行");
                r.cancel();
                return ok("正在取消");
            }
            case "export": {
                Map<String, Object> wf = b.get("workflow") instanceof Map ? map(b.get("workflow")) : store.get(s(b.get("id")));
                if (wf == null) return fail("没有这个工作流");
                Map<String, Object> m = ok("已导出（不含任何 key）");
                Map<String, Object> ex = WfStore.export(wf, api);
                List<Object> steps = new ArrayList<>(); // 清单：每一步是什么、风险档
                for (Map<String, Object> n : WfSchema.nodes(WfSchema.normalize(wf))) {
                    Map<String, Object> st = new TreeMap<>();
                    st.put("id", WfSchema.id(n));
                    st.put("type", WfSchema.type(n));
                    st.put("title", WfSchema.title(n));
                    st.put("risk", engine.risk(n));
                    steps.add(st);
                }
                map(ex.get("requires")).put("steps", steps);
                m.put("json", Json.stringify(ex));
                return m;
            }
            case "import_check": {
                Map<String, Object> wf;
                try { wf = b.get("json") != null ? Json.obj(s(b.get("json"))) : map(b.get("workflow")); } catch (RuntimeException e) { return fail("不是合法的 JSON"); }
                return WfStore.importCheck(wf, skills, api);
            }
            case "import": {
                Map<String, Object> wf;
                try { wf = b.get("json") != null ? Json.obj(s(b.get("json"))) : map(b.get("workflow")); } catch (RuntimeException e) { return fail("不是合法的 JSON"); }
                Map<String, Object> chk = WfStore.importCheck(wf, skills, api);
                if (!Boolean.TRUE.equals(chk.get("ok"))) return chk;
                Map<String, Object> clean = map(chk.get("workflow"));
                clean.remove("id");
                clean.remove("shortcut");
                clean.remove("exported_by");
                Map<String, Object> r = store.save(clean);
                r.put("missing_skills", chk.get("missing_skills"));
                r.put("missing_connectors", chk.get("missing_connectors"));
                if (Boolean.TRUE.equals(r.get("ok"))) r.put("text", "已导入。" + chk.get("text"));
                return r;
            }
            case "caps": { Map<String, Object> m = ok("能力"); m.put("capabilities", capabilities()); return m; }
            case "skills": { Map<String, Object> m = ok("技能"); m.put("skills", skills.list()); m.put("palette", skills.palette()); return m; }
            case "skill_inspect": {
                SkillRegistry.Skill k = skills.get(s(b.get("id")));
                if (k == null) return fail("没有这个技能");
                Map<String, Object> m = ok(k.name);
                m.put("skill", k.view());
                m.put("doc", WfCode.clip(k.body, 4000));
                m.put("node", SkillRegistry.nodeTemplate(k));
                return m;
            }
            case "skill_install": return skills.install(s(b.get("source")), skillApprover);
            case "skill_install_example": return installExample();
            case "skill_create": return skills.create(map(b.get("spec")), skillApprover);
            case "skill_uninstall": return skills.uninstall(s(b.get("id")));
            case "skill_relink": {
                SkillRegistry.Skill k = skills.get(s(b.get("id")));
                if (k == null) return fail("没有这个技能");
                Map<String, Object> m = ok("已换成已装版本 " + k.version);
                m.put("ref", k.ref());
                m.put("snapshot", k.snapshot());
                return m;
            }
            case "conn_list": { Map<String, Object> m = ok("连接器"); m.put("connectors", api.list()); m.put("oauth", oauth.list()); m.put("where", secretsWhere()); return m; }
            case "conn_detect": {
                String[] p = ApiConnectors.detect(s(b.get("url")), s(b.get("type")));
                Map<String, Object> m = ok(p == null ? "没认出来（冷门）：保存前要在备注里写清楚这是什么服务" : "认出来了：" + p[1]);
                m.put("cold", p == null);
                if (p != null) { m.put("preset", p[0]); m.put("preset_name", p[1]); m.put("key_mode", p[3]); m.put("key_name", p[4]); }
                return m;
            }
            case "conn_save": return api.save(b);
            case "conn_delete": return api.delete(s(b.get("id")));
            case "conn_test": return api.test(s(b.get("id")));
            case "oauth_client": return oauth.setClientId(s(b.get("id")), s(b.get("client_id")));
            case "oauth_start": return oauth.start(s(b.get("id")));
            case "oauth_poll": return oauth.poll(s(b.get("pending")));
            case "oauth_disconnect": return oauth.disconnect(s(b.get("id")));
            case "ai_edit": return aiEdit(s(b.get("id")), map(b.get("workflow")), s(b.get("instruction")));
            case "proposal_get": { Map<String, Object> p = store.proposal(s(b.get("proposal"))); if (p == null) return fail("没有这个修改"); Map<String, Object> m = ok(""); m.put("proposal", p); return m; }
            case "proposal_discard": { store.take(s(b.get("proposal"))); return ok("已放弃这次修改"); }
            case "proposal_apply": {
                Map<String, Object> p = store.take(s(b.get("proposal")));
                if (p == null) return fail("这个修改已经处理过了");
                Map<String, Object> m = ok("已采用修改（可以点「撤销」退回去）");
                m.put("workflow", p.get("after"));
                m.put("status", nodeStatus(map(p.get("after"))));
                return m;
            }
            case "shortcut_save": return store.setShortcut(s(b.get("id")), s(b.get("name")));
            case "shortcut_run": {
                Map<String, Object> wf = store.byShortcut(s(b.get("name")));
                if (wf == null) return fail("没有这个快捷指令");
                String bad = preflight(wf);
                if (bad != null) return fail(bad);
                WfEngine.Run r = engine.start(wf, map(b.get("input")), 0);
                Map<String, Object> m = ok("已开始运行「" + s(b.get("name")) + "」");
                m.put("run", r.view());
                return m;
            }
            case "draft_from_steps": {
                Map<String, Object> d = WfStore.draftFromSteps(s(b.get("name")), b.get("steps") instanceof List ? (List<Object>) b.get("steps") : List.of());
                Map<String, Object> m = ok("已生成草稿，改好再保存");
                m.put("workflow", d);
                return m;
            }
            default:
                return fail("不认识的操作");
        }
    }

    String secretsWhere() {
        return SafeExec.WINDOWS ? "Windows 凭据管理器" : "内存（只在这次运行有效）";
    }

    /** 跑之前查一遍：缺技能 / 连接器就不跑，直接说缺什么。 */
    @SuppressWarnings("unchecked")
    String preflight(Map<String, Object> wf) {
        List<WfSchema.Issue> is = WfSchema.validate(WfSchema.normalize(wf));
        if (!WfSchema.ok(is)) return "工作流有问题：" + WfStore.firstFatal(is);
        Map<String, Object> st = nodeStatus(wf);
        for (Map.Entry<String, Object> e : st.entrySet()) {
            Map<String, Object> x = (Map<String, Object>) e.getValue();
            if (Boolean.TRUE.equals(x.get("missing"))) return s(x.get("why"));
        }
        return null;
    }

    /** 每个节点的依赖状态（界面据此标红）：{节点id: {missing, why, risk}}。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> nodeStatus(Map<String, Object> wf) {
        Map<String, Object> out = new TreeMap<>();
        for (Map<String, Object> n : WfSchema.nodes(wf)) {
            Map<String, Object> d = WfSchema.data(n);
            String why = null;
            switch (WfSchema.type(n)) {
                case "skill": why = d.get("skill") instanceof Map ? skills.check((Map<String, Object>) d.get("skill")) : "没选技能"; break;
                case "api": {
                    ApiConnectors.Conn c = api.get(s(d.get("connector")));
                    if (c == null) why = "缺少连接器「" + s(d.get("connector")) + "」";
                    else if (!api.usable(c)) why = "连接器「" + c.name + "」还没测试通过";
                    break;
                }
                case "tool": {
                    String cap = s(d.get("capability"));
                    if (cap.startsWith("mcp:")) { McpPort m = mcp; boolean has = false; if (m != null) for (Map<String, Object> t : m.toolDefs()) if (cap.substring(4).equals(s(map(t.get("function")).get("name")))) has = true; if (!has) why = "缺少 MCP 工具（服务器没开或没装）"; }
                    else if (cap.startsWith("api:")) { ApiConnectors.Conn c = api.get(cap.substring(4)); if (c == null || !api.usable(c)) why = "缺少可用的连接器"; }
                    else if (cap.startsWith("skill:")) { if (skills.get(cap.substring(6)) == null) why = "缺少技能"; }
                    else if (cap.startsWith("oauth:")) { if (!oauth.connected(cap.substring(6))) why = "账号还没连接"; }
                    break;
                }
                default: break;
            }
            Map<String, Object> x = new TreeMap<>();
            x.put("missing", why != null);
            if (why != null) x.put("why", why);
            String rk = engine.risk(n);
            x.put("risk", rk);
            x.put("risk_zh", WfEngine.riskZh(rk));
            out.put(WfSchema.id(n), x);
        }
        return out;
    }

    /** 让模型按一句话改图：只生成修改预览，等用户确认。 */
    Map<String, Object> aiEdit(String id, Map<String, Object> current, String instruction) {
        if (instruction.isBlank()) return fail("说一下要怎么改");
        WfEngine.LlmClient l = llm;
        if (l == null) return fail("没配置大模型（设置 › 模型）");
        Map<String, Object> before = current.isEmpty() ? store.get(id) : current;
        if (before == null) return fail("没有这个工作流");
        String reply;
        try {
            reply = l.complete(editSystem(), "当前工作流：\n" + Json.stringify(WfStore.export(before, api)) + "\n\n要改的：" + WfCode.clip(instruction, 1000), 0.2, "", 120);
        } catch (Exception e) {
            return fail("模型没回：" + e.getClass().getSimpleName());
        }
        Map<String, Object> after = extractJson(reply);
        if (after == null) return fail("模型回的不是工作流 JSON，没改");
        return store.propose(id, before, after, "AI");
    }

    String editSystem() {
        StringBuilder sb = new StringBuilder();
        sb.append("你在帮用户改一个 OneSay 工作流。只输出改好后的完整工作流 JSON（不要解释）。格式 onesay.workflow/1：\n")
          .append("{schema, name, nodes:[{id, type, meta:{position:{x,y}}, data:{title,...}}], edges:[{sourceNodeID, targetNodeID, sourcePortID?}]}\n")
          .append("节点类型：start(data.inputs=JSON Schema) end(data.output={名字:值}) llm(data.prompt, data.system) condition(data.left, data.op, data.right；出口 true/false)\n")
          .append("loop(data.items=列表引用；出口 body=循环体、next=之后；循环体里用 {{循环id.item}}) code(data.code=JS，function main(input){return {...}}，data.inputs={名字:值})\n")
          .append("tool(data.capability, data.args) skill(data.skill={id,version,sha256}, data.inputs) api(data.connector, data.method, data.path, data.query, data.body)\n")
          .append("变量引用写 {{节点id.字段}}，开始参数写 {{input.字段}}。不要在节点里写任何 key / token / 口令。保留没要求改的节点和编号。\n");
        List<Map<String, Object>> pal = skills.palette();
        if (!pal.isEmpty()) {
            sb.append("已装的技能：\n");
            for (Map<String, Object> p : pal) sb.append("- ").append(p.get("id")).append(" ").append(p.get("name")).append(" v").append(p.get("version")).append(" sha256=").append(p.get("sha256")).append(" 输入 ").append(Json.stringify(p.get("inputs"))).append("\n");
        }
        return sb.toString();
    }

    static Map<String, Object> extractJson(String reply) {
        if (reply == null) return null;
        String t = reply.trim();
        int a = t.indexOf('{'), z = t.lastIndexOf('}');
        if (a < 0 || z <= a) return null;
        try { return Json.obj(t.substring(a, z + 1)); } catch (RuntimeException e) { return null; }
    }

    Map<String, Object> installExample() {
        try {
            Path tmp = java.nio.file.Files.createTempDirectory("onesay-skill-ex");
            Path d = tmp.resolve("text-stats");
            for (String f : new String[] {"SKILL.md", "skill.json", "scripts/main.js"}) {
                byte[] b = UiServer.resource("onesay/skills/text-stats/" + f);
                java.nio.file.Files.createDirectories(d.resolve(f).getParent());
                java.nio.file.Files.write(d.resolve(f), b);
            }
            try { return skills.install(d.toString(), skillApprover); } finally { SkillRegistry.deleteTree(tmp); }
        } catch (Exception e) {
            return fail("示例技能没找到");
        }
    }

    // ------------------------------------------------------------------ 给对话模型的工具（wf__*）

    public static List<Map<String, Object>> toolDefs() {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<String, Object> none = new TreeMap<>();
        none.put("description", ChatAgent.prop("string", ChatAgent.DESC));
        l.add(ChatAgent.fn("wf__list", "列出这台电脑上的工作流、快捷指令和已装技能（只读）。", new TreeMap<>(none), List.of("description")));
        Map<String, Object> g = new TreeMap<>(none);
        g.put("workflow_id", ChatAgent.prop("string", "工作流编号（先用 wf__list 查）"));
        l.add(ChatAgent.fn("wf__get", "读一个工作流的完整节点图 JSON（只读）。", g, List.of("description", "workflow_id")));
        Map<String, Object> p = new TreeMap<>(none);
        p.put("workflow_id", ChatAgent.prop("string", "要改的工作流编号；新建就留空"));
        p.put("workflow_json", ChatAgent.prop("string", "改好后的完整工作流 JSON（onesay.workflow/1）。不要写任何 key。"));
        l.add(ChatAgent.fn("wf__propose", "提交对工作流的修改：只生成「修改前后差异」给用户看，用户在工作流页点「采用」才生效（可撤销）。不会自动运行。", p, List.of("description", "workflow_json")));
        Map<String, Object> sk = new TreeMap<>(none);
        sk.put("skill_id", ChatAgent.prop("string", "技能编号"));
        l.add(ChatAgent.fn("wf__skill_inspect", "查看一个技能的说明、参数声明和权限（只读）。", sk, List.of("description", "skill_id")));
        Map<String, Object> si = new TreeMap<>(none);
        si.put("source", ChatAgent.prop("string", "技能来源：文件夹路径、.zip 文件路径或 https 的 zip 地址"));
        l.add(ChatAgent.fn("wf__skill_install", "安装一个技能。电脑上会弹确认卡列出它要的权限，用户点允许才装（每次都问）。", si, List.of("description", "source")));
        Map<String, Object> sc = new TreeMap<>(none);
        sc.put("spec_json", ChatAgent.prop("string", "新技能 JSON：{id, name, description, version, inputs(JSON Schema 子集), outputs, code(JS：function main(input){return {...}}), permissions:{net,files}}；权限默认全关，用不到就别开"));
        l.add(ChatAgent.fn("wf__skill_create", "新建一个 JS 技能（装进技能库后能当工作流节点）。同样弹确认卡。", sc, List.of("description", "spec_json")));
        Map<String, Object> an = new TreeMap<>(none);
        an.put("workflow_id", ChatAgent.prop("string", "工作流编号"));
        an.put("skill_id", ChatAgent.prop("string", "技能编号"));
        an.put("after_node", ChatAgent.prop("string", "可选：插在哪个节点后面（节点编号）"));
        l.add(ChatAgent.fn("wf__add_skill_node", "按技能的参数声明生成一个技能节点插进工作流（同样只生成差异预览，等用户确认）。", an, List.of("description", "workflow_id", "skill_id")));
        l.add(ChatAgent.fn("wf__capabilities", "列出 OneSay 能用的所有能力（MCP 工具、API 连接器、技能、OAuth 账号）及风险档（只读）。", new TreeMap<>(none), List.of("description")));
        return l;
    }

    /** wf__ 工具是否只读（不弹审批）：除了安装 / 新建技能（它们自己弹确认卡）都只读；propose 只生成预览。 */
    public static boolean readOnlyTool(String fn) {
        return !fn.equals("wf__skill_install") && !fn.equals("wf__skill_create");
    }

    /** 执行 wf__ 工具。 */
    @SuppressWarnings("unchecked")
    public Actions.Result callTool(String fn, Map<String, Object> a) {
        try {
            switch (fn) {
                case "wf__list": {
                    Map<String, Object> d = new TreeMap<>();
                    d.put("workflows", store.list());
                    d.put("shortcuts", store.shortcuts());
                    List<Object> sl = new ArrayList<>();
                    for (Map<String, Object> s : skills.list()) { Map<String, Object> x = new TreeMap<>(); for (String k : new String[] {"id", "name", "version", "runnable", "risk", "description"}) x.put(k, s.get(k)); sl.add(x); }
                    d.put("skills", sl);
                    return Actions.Result.ok("工作流 " + ((List<?>) d.get("workflows")).size() + " 个，技能 " + sl.size() + " 个", d);
                }
                case "wf__get": {
                    Map<String, Object> wf = store.get(s(a.get("workflow_id")));
                    if (wf == null) return Actions.Result.fail("没有这个工作流");
                    Map<String, Object> d = new TreeMap<>();
                    d.put("workflow", WfStore.export(wf, api));
                    return Actions.Result.ok("工作流「" + s(wf.get("name")) + "」", d);
                }
                case "wf__propose": {
                    Map<String, Object> after;
                    try { after = Json.obj(s(a.get("workflow_json"))); } catch (RuntimeException e) { return Actions.Result.fail("workflow_json 不是合法 JSON"); }
                    String id = s(a.get("workflow_id"));
                    Map<String, Object> before = id.isEmpty() ? null : store.get(id);
                    if (!id.isEmpty() && before == null) return Actions.Result.fail("没有这个工作流");
                    Map<String, Object> r = store.propose(id, before, after, "对话");
                    if (!Boolean.TRUE.equals(r.get("ok"))) return Actions.Result.fail(s(r.get("text")));
                    Map<String, Object> pr = map(r.get("proposal"));
                    Map<String, Object> d = new TreeMap<>();
                    d.put("proposal", pr.get("id"));
                    d.put("diff", pr.get("diff"));
                    return Actions.Result.ok(s(r.get("text")) + "。请用户到「工作流」页查看差异并点「采用」", d);
                }
                case "wf__skill_inspect": {
                    SkillRegistry.Skill k = skills.get(s(a.get("skill_id")));
                    if (k == null) return Actions.Result.fail("没有这个技能");
                    Map<String, Object> d = k.view();
                    d.put("doc", WfCode.clip(k.body, 3000));
                    return Actions.Result.ok("技能「" + k.name + "」", d);
                }
                case "wf__skill_install": {
                    Map<String, Object> r = skills.install(s(a.get("source")), skillApprover);
                    return Boolean.TRUE.equals(r.get("ok")) ? Actions.Result.ok(s(r.get("text"))) : Actions.Result.fail(s(r.get("text")));
                }
                case "wf__skill_create": {
                    Map<String, Object> spec;
                    try { spec = Json.obj(s(a.get("spec_json"))); } catch (RuntimeException e) { return Actions.Result.fail("spec_json 不是合法 JSON"); }
                    Map<String, Object> r = skills.create(spec, skillApprover);
                    return Boolean.TRUE.equals(r.get("ok")) ? Actions.Result.ok(s(r.get("text"))) : Actions.Result.fail(s(r.get("text")));
                }
                case "wf__add_skill_node": {
                    String id = s(a.get("workflow_id"));
                    Map<String, Object> wf = store.get(id);
                    if (wf == null) return Actions.Result.fail("没有这个工作流");
                    SkillRegistry.Skill k = skills.get(s(a.get("skill_id")));
                    if (k == null || !k.runnable) return Actions.Result.fail(k == null ? "没有这个技能" : "这个技能不能当节点：" + k.reason);
                    Map<String, Object> after = insertSkillNode(wf, k, s(a.get("after_node")));
                    Map<String, Object> r = store.propose(id, wf, after, "对话");
                    if (!Boolean.TRUE.equals(r.get("ok"))) return Actions.Result.fail(s(r.get("text")));
                    Map<String, Object> d = new TreeMap<>();
                    d.put("proposal", map(r.get("proposal")).get("id"));
                    return Actions.Result.ok(s(r.get("text")) + "。请用户到「工作流」页确认", d);
                }
                case "wf__capabilities": {
                    Map<String, Object> d = new TreeMap<>();
                    List<Object> l = new ArrayList<>();
                    for (Map<String, Object> c : capabilities()) { Map<String, Object> x = new TreeMap<>(c); x.remove("schema"); l.add(x); }
                    d.put("capabilities", l);
                    return Actions.Result.ok("能力 " + l.size() + " 个", d);
                }
                default:
                    return Actions.Result.fail("不认识的工作流工具");
            }
        } catch (RuntimeException e) {
            return Actions.Result.fail("出错：" + e.getClass().getSimpleName());
        }
    }

    /** 把技能节点插到 after 节点后面（after 原来连出去的线改从新节点连出）；没给就插在开始节点后面。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> insertSkillNode(Map<String, Object> wf, SkillRegistry.Skill k, String after) {
        Map<String, Object> out = WfSchema.normalize(wf);
        List<Object> nodes = (List<Object>) out.get("nodes");
        List<Object> edges = (List<Object>) out.get("edges");
        String anchor = after;
        if (anchor.isEmpty()) for (Map<String, Object> n : WfSchema.nodes(out)) if (WfSchema.type(n).equals("start")) anchor = WfSchema.id(n);
        String nid = "skill_" + k.id.replace('-', '_');
        int i = 2;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Map<String, Object> n : WfSchema.nodes(out)) ids.add(WfSchema.id(n));
        String base = nid;
        while (ids.contains(nid)) nid = base + "_" + i++;
        double x = 0, y = 0;
        for (Map<String, Object> n : WfSchema.nodes(out)) if (WfSchema.id(n).equals(anchor)) {
            Map<String, Object> pos = map(map(n.get("meta")).get("position"));
            x = pos.get("x") instanceof Number ? ((Number) pos.get("x")).doubleValue() + 160 : 0;
            y = pos.get("y") instanceof Number ? ((Number) pos.get("y")).doubleValue() + 140 : 0;
        }
        Map<String, Object> n = new TreeMap<>();
        n.put("id", nid);
        n.put("type", "skill");
        Map<String, Object> pos = new TreeMap<>();
        pos.put("x", (long) x);
        pos.put("y", (long) y);
        n.put("meta", Map.of("position", pos));
        n.put("data", SkillRegistry.nodeTemplate(k));
        nodes.add(n);
        String a0 = anchor;
        String fid = nid;
        for (Object o : edges) {
            Map<String, Object> e = (Map<String, Object>) o;
            if (s(e.get("sourceNodeID")).equals(a0) && WfSchema.port(e) == null) e.put("sourceNodeID", fid);
        }
        Map<String, Object> e = new TreeMap<>();
        e.put("sourceNodeID", a0);
        e.put("targetNodeID", fid);
        edges.add(e);
        return out;
    }

    public static boolean isTool(String fn) { return fn != null && fn.toLowerCase(Locale.ROOT).startsWith("wf__"); }
}
