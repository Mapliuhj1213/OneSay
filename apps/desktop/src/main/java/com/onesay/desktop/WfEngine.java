package com.onesay.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * [workflow-1120] 工作流执行器（Java 侧，窗口关着也能跑）：直接读 onesay.workflow/1 的 JSON。
 *
 * <ul>
 *   <li>按依赖顺序执行：一个节点等它所有进来的连线都「有结果」（走通 / 走不通）了才决定跑还是跳过；条件节点只走选中的出口，没走的分支整条跳过。</li>
 *   <li>循环：循环体（body 出口能走到的节点）对列表里每一项跑一遍；{{循环id.item}} / {{循环id.index}} 取当前项；跑完 {{循环id.results}} 是每一轮的结果。</li>
 *   <li>变量引用 {{节点id.字段}}，{{input.x}} 是开始参数。整段就是一个引用时保留原类型（列表、数字）。</li>
 *   <li>取消、单节点超时、整体超时、运行日志（抹掉 key）。</li>
 *   <li>审批：只读档直接跑；本机改动 / 外发 / 花钱档每次都走确认卡（{@link Approver}，没有「记住」）。</li>
 * </ul>
 */
public final class WfEngine {
    /** 审批：节点标题、详情、风险档（local / outbound / spend）→ 允许 / 拒绝。 */
    public interface Approver { boolean approve(String title, String detail, String risk); }

    /** 大模型：system、prompt、温度、连接器 id（空 = OneSay 设置里的模型）。 */
    public interface LlmClient { String complete(String system, String prompt, double temperature, String connector, int timeoutSec) throws Exception; }

    /** 能力调用（MCP 工具等）：能力 id、参数 → 结果对象。 */
    public interface Capabilities {
        String risk(String capId, Map<String, Object> args);
        String describe(String capId);
        Map<String, Object> call(String capId, Map<String, Object> args) throws Exception;
    }

    public static final class Hooks {
        public Approver approver = (t, d, r) -> false;
        public LlmClient llm;
        public Capabilities caps;
        public SkillRegistry skills;
        public ApiConnectors api;
        /** 技能声明了 net 时给的 GET（null = 不给）。 */
        public java.util.function.Function<String, String> httpGet;
        public Supplier<Collection<String>> secrets = List::of;
    }

    public static final long DEFAULT_NODE_MS = 60_000, DEFAULT_RUN_MS = 10 * 60_000;
    public static final int DEFAULT_LOOP_MAX = 100, HARD_LOOP_MAX = 1000;

    /** 一次运行。 */
    public static final class Run {
        public final String id, wfId;
        public final long startedAt = System.currentTimeMillis();
        public volatile long endedAt;
        /** running / done / failed / cancelled / denied / timeout */
        public volatile String status = "running";
        public volatile String error = "";
        public volatile Object result;
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile Future<?> current;
        final List<Map<String, Object>> log = new ArrayList<>();
        final Map<String, String> nodeStatus = new LinkedHashMap<>();
        final Map<String, Object> outputs = new LinkedHashMap<>();
        long deadline;

        Run(String id, String wfId) { this.id = id; this.wfId = wfId; }

        public void cancel() {
            cancel.set(true);
            Future<?> f = current;
            if (f != null) f.cancel(true);
        }

        public boolean finished() { return !status.equals("running"); }

        public synchronized Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("workflow", wfId);
            m.put("status", status);
            m.put("status_zh", statusZh(status));
            m.put("error", error);
            m.put("started_at", startedAt);
            m.put("ended_at", endedAt);
            m.put("log", new ArrayList<Object>(log));
            m.put("nodes", new TreeMap<String, Object>(nodeStatus));
            m.put("result", result);
            return m;
        }

        synchronized void node(String id, String st) { nodeStatus.put(id, st); }

        synchronized void add(Map<String, Object> e) { if (log.size() < 2000) log.add(e); }
    }

    static String statusZh(String s) {
        switch (s) {
            case "running": return "运行中";
            case "done": return "完成";
            case "failed": return "出错";
            case "cancelled": return "已取消";
            case "denied": return "没批准";
            case "timeout": return "超时";
            default: return s;
        }
    }

    private final Hooks hooks;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "onesay-wf"); t.setDaemon(true); return t; });
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public WfEngine(Hooks hooks) { this.hooks = hooks; }

    public synchronized Run run(String id) { return runs.get(id); }

    /** 后台开始跑（立刻返回）。 */
    public Run start(Map<String, Object> wf, Map<String, Object> input, long runTimeoutMs) {
        Run r = new Run("run-" + System.currentTimeMillis() + "-" + seq.incrementAndGet(), WfSchema.s(wf.get("id")));
        synchronized (this) {
            runs.put(r.id, r);
            while (runs.size() > 30) runs.remove(runs.keySet().iterator().next());
        }
        pool.submit(() -> execute(wf, input, r, runTimeoutMs));
        return r;
    }

    /** 同步跑（测试 / 快捷指令）。 */
    public Run runSync(Map<String, Object> wf, Map<String, Object> input, long runTimeoutMs) {
        Run r = new Run("run-" + System.currentTimeMillis() + "-" + seq.incrementAndGet(), WfSchema.s(wf.get("id")));
        synchronized (this) { runs.put(r.id, r); }
        execute(wf, input, r, runTimeoutMs);
        return r;
    }

    // ------------------------------------------------------------------ 执行

    static final class Stop extends RuntimeException {
        final String status;
        /** 节点自己已经在运行日志里写过这条出错 / 没批准（收尾时不再重复写一行）。 */
        final boolean logged;
        Stop(String status, String msg) { this(status, msg, false); }
        Stop(String status, String msg, boolean logged) { super(msg); this.status = status; this.logged = logged; }
    }

    private void execute(Map<String, Object> wf0, Map<String, Object> input, Run r, long runTimeoutMs) {
        r.deadline = System.currentTimeMillis() + (runTimeoutMs > 0 ? runTimeoutMs : DEFAULT_RUN_MS);
        Map<String, Object> wf = WfSchema.normalize(wf0);
        try {
            List<WfSchema.Issue> issues = WfSchema.validate(wf);
            if (!WfSchema.ok(issues)) {
                StringBuilder sb = new StringBuilder();
                for (WfSchema.Issue i : issues) if (i.fatal) { if (sb.length() > 0) sb.append("；"); sb.append(i.text); }
                throw new Stop("failed", "工作流有问题，没运行：" + sb);
            }
            G g = new G(wf);
            for (Map<String, Object> n : g.nodes.values()) r.node(WfSchema.id(n), "pending");
            Map<String, Object> vars = new HashMap<>();
            vars.put("input", input == null ? new TreeMap<>() : input);
            Set<String> scope = g.scope(new HashSet<>(g.nodes.keySet()));
            runScope(g, scope, List.of(g.start), vars, r);
            if (r.result == null) r.result = new TreeMap<>();
            r.status = "done";
            log(r, null, null, "done", "运行完成", 0);
        } catch (Stop s) {
            r.status = s.status;
            r.error = redact(s.getMessage());
            if (!s.logged) log(r, null, null, s.status, r.error, 0); // 出错的节点已经写过一行，不重复
        } catch (RuntimeException e) {
            r.status = "failed";
            r.error = redact("执行器出错：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
            log(r, null, null, "failed", r.error, 0);
        } finally {
            r.endedAt = System.currentTimeMillis();
            synchronized (r) {
                for (Map.Entry<String, String> e : r.nodeStatus.entrySet()) if (e.getValue().equals("pending")) e.setValue("skipped");
            }
        }
    }

    /** 图的索引。 */
    static final class G {
        final Map<String, Object> wf;
        final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> out = new HashMap<>(), in = new HashMap<>();
        final Map<String, Set<String>> bodies = new HashMap<>();
        String start;

        G(Map<String, Object> wf) {
            this.wf = wf;
            for (Map<String, Object> n : WfSchema.nodes(wf)) { nodes.put(WfSchema.id(n), n); if (WfSchema.type(n).equals("start")) start = WfSchema.id(n); }
            for (Map<String, Object> e : WfSchema.edges(wf)) {
                out.computeIfAbsent(WfSchema.s(e.get("sourceNodeID")), k -> new ArrayList<>()).add(e);
                in.computeIfAbsent(WfSchema.s(e.get("targetNodeID")), k -> new ArrayList<>()).add(e);
            }
            for (Map<String, Object> n : nodes.values()) if (WfSchema.type(n).equals("loop")) bodies.put(WfSchema.id(n), WfSchema.body(wf, WfSchema.id(n)));
        }

        /** 一个范围里「本层」的节点：去掉其中循环节点的循环体（循环体由循环节点自己跑）。 */
        Set<String> scope(Set<String> all) {
            Set<String> s = new HashSet<>(all);
            for (String x : all) if (bodies.containsKey(x)) s.removeAll(bodies.get(x));
            return s;
        }
    }

    /** 在一个范围里按依赖顺序跑。entries 是没有本层进线的入口。返回最后跑完的节点 id。 */
    private String runScope(G g, Set<String> scope, List<String> entries, Map<String, Object> vars, Run r) {
        Map<String, Integer> pending = new HashMap<>(), live = new HashMap<>();
        for (String id : scope) {
            int c = 0;
            for (Map<String, Object> e : g.in.getOrDefault(id, List.of())) if (scope.contains(WfSchema.s(e.get("sourceNodeID"))) && !isBodyEdge(g, e)) c++;
            pending.put(id, c);
        }
        ArrayDeque<String> ready = new ArrayDeque<>(entries);
        String last = null;
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            checkAlive(r);
            String id = ready.poll();
            if (!done.add(id)) continue;
            Map<String, Object> n = g.nodes.get(id);
            Set<String> ports = runNode(g, n, vars, r);
            last = id;
            for (Map<String, Object> e : g.out.getOrDefault(id, List.of())) {
                String t = WfSchema.s(e.get("targetNodeID"));
                if (!scope.contains(t) || isBodyEdge(g, e)) continue;
                String p = WfSchema.port(e);
                boolean ok = ports == null || ports.contains(p == null ? "" : p);
                if (ok) live.merge(t, 1, Integer::sum);
                settle(g, scope, t, pending, live, ready, r, done);
            }
        }
        return last;
    }

    /** 一条进线有结果了：进线都有结果时，有一条走通就排队跑，全没走通就跳过并把跳过往下传。 */
    private void settle(G g, Set<String> scope, String t, Map<String, Integer> pending, Map<String, Integer> live, ArrayDeque<String> ready, Run r, Set<String> done) {
        int left = pending.merge(t, -1, Integer::sum);
        if (left > 0) return;
        if (live.getOrDefault(t, 0) > 0) { ready.add(t); return; }
        done.add(t);
        r.node(t, "skipped");
        markBodySkipped(g, t, r);
        for (Map<String, Object> e : g.out.getOrDefault(t, List.of())) {
            String x = WfSchema.s(e.get("targetNodeID"));
            if (!scope.contains(x) || isBodyEdge(g, e)) continue;
            settle(g, scope, x, pending, live, ready, r, done);
        }
    }

    private void markBodySkipped(G g, String id, Run r) {
        Set<String> b = g.bodies.get(id);
        if (b != null) for (String x : b) r.node(x, "skipped");
    }

    static boolean isBodyEdge(G g, Map<String, Object> e) {
        Map<String, Object> s = g.nodes.get(WfSchema.s(e.get("sourceNodeID")));
        return s != null && WfSchema.type(s).equals("loop") && "body".equals(WfSchema.port(e));
    }

    private void checkAlive(Run r) {
        if (r.cancel.get() || Thread.currentThread().isInterrupted()) throw new Stop("cancelled", "已取消");
        if (System.currentTimeMillis() > r.deadline) throw new Stop("timeout", "整个工作流超时，已停下");
    }

    /** 跑一个节点。返回选中的出口（null = 全部默认出口）。 */
    private Set<String> runNode(G g, Map<String, Object> n, Map<String, Object> vars, Run r) {
        String id = WfSchema.id(n), type = WfSchema.type(n), title = WfSchema.title(n);
        Map<String, Object> d = WfSchema.data(n);
        long t0 = System.currentTimeMillis();
        r.node(id, "running");
        log(r, id, title, "start", "开始：" + title, 0);
        // 审批
        String risk = risk(n);
        if (!risk.equals("read")) {
            boolean yes;
            try { yes = hooks.approver != null && hooks.approver.approve(title, approvalDetail(n, vars), risk); } catch (RuntimeException e) { yes = false; }
            if (!yes) {
                r.node(id, "denied");
                log(r, id, title, "denied", "「" + title + "」要你批准（" + riskZh(risk) + "），没有批准，已停下", System.currentTimeMillis() - t0);
                throw new Stop("denied", "「" + title + "」没有批准，工作流停下了", true);
            }
        }
        try {
            Set<String> ports = null;
            Object outv;
            switch (type) {
                case "start": {
                    Object schema = d.get("inputs");
                    Object v = vars.get("input");
                    if (schema != null) {
                        Object[] res = JsonSchemaLite.validate(schema, v);
                        @SuppressWarnings("unchecked") List<String> errs = (List<String>) res[1];
                        if (!errs.isEmpty()) throw new IllegalArgumentException("开始参数不对：" + String.join("；", errs));
                        v = res[0];
                        vars.put("input", v);
                    }
                    outv = v;
                    break;
                }
                case "end": {
                    Object o = resolve(d.get("output"), vars);
                    outv = o == null ? new TreeMap<>() : o;
                    if (r.result == null) r.result = outv;
                    break;
                }
                case "condition": {
                    boolean b = cond(resolve(d.get("left"), vars), WfSchema.s(d.get("op")), resolve(d.get("right"), vars));
                    outv = Map.of("result", b);
                    ports = Set.of(b ? "true" : "false");
                    break;
                }
                case "loop":
                    outv = runLoop(g, n, vars, r);
                    ports = Set.of("", "next");
                    break;
                default:
                    outv = timed(r, n, () -> exec(n, type, d, vars));
            }
            vars.put(id, outv);
            synchronized (r) { r.outputs.put(id, outv); }
            r.node(id, "ok");
            log(r, id, title, "ok", "完成：" + title + " → " + clip(redact(Json.stringify(outv)), 300), System.currentTimeMillis() - t0);
            return ports;
        } catch (Stop s) {
            r.node(id, s.status.equals("cancelled") ? "cancelled" : "error");
            throw s;
        } catch (Exception e) {
            String msg = redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if ("continue".equals(d.get("onError"))) {
                Map<String, Object> o = new TreeMap<>();
                o.put("error", msg);
                vars.put(id, o);
                r.node(id, "error");
                log(r, id, title, "error", "「" + title + "」出错，按设置继续：" + msg, System.currentTimeMillis() - t0);
                return null;
            }
            r.node(id, "error");
            log(r, id, title, "error", "「" + title + "」出错：" + msg, System.currentTimeMillis() - t0);
            throw new Stop(e instanceof TimeoutException ? "timeout" : "failed", "「" + title + "」出错：" + msg, true);
        }
    }

    /** 单节点超时：放到别的线程跑，等不到就中断。 */
    private Object timed(Run r, Map<String, Object> n, Callable<Object> c) throws Exception {
        Map<String, Object> d = WfSchema.data(n);
        long ms = d.get("timeoutMs") instanceof Number ? ((Number) d.get("timeoutMs")).longValue() : DEFAULT_NODE_MS;
        ms = Math.max(100, Math.min(ms, 15 * 60_000));
        ms = Math.min(ms, Math.max(100, r.deadline - System.currentTimeMillis()));
        Future<Object> f = pool.submit(c);
        r.current = f;
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimeoutException("超时（超过 " + (ms >= 1000 ? ms / 1000 + " 秒" : ms + " 毫秒") + "），已中断");
        } catch (java.util.concurrent.CancellationException e) {
            throw new Stop("cancelled", "已取消");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c2 = e.getCause();
            if (c2 instanceof Exception) throw (Exception) c2;
            throw new IllegalStateException(String.valueOf(c2));
        } catch (InterruptedException e) {
            f.cancel(true);
            throw new Stop("cancelled", "已取消");
        } finally {
            r.current = null;
            if (r.cancel.get()) throw new Stop("cancelled", "已取消");
        }
    }

    @SuppressWarnings("unchecked")
    private Object exec(Map<String, Object> n, String type, Map<String, Object> d, Map<String, Object> vars) throws Exception {
        switch (type) {
            case "llm": {
                if (hooks.llm == null) throw new IllegalStateException("没配置大模型（设置 › 模型）");
                String sys = WfSchema.s(resolveStr(d.get("system"), vars));
                String prompt = WfSchema.s(resolveStr(d.get("prompt"), vars));
                double temp = d.get("temperature") instanceof Number ? ((Number) d.get("temperature")).doubleValue() : 0.3;
                String text = hooks.llm.complete(sys, prompt, temp, WfSchema.s(d.get("connector")), 120);
                Map<String, Object> o = new TreeMap<>();
                o.put("text", text == null ? "" : text);
                return o;
            }
            case "code": {
                Object in = resolve(d.get("inputs"), vars);
                int tmo = d.get("codeTimeoutMs") instanceof Number ? ((Number) d.get("codeTimeoutMs")).intValue() : WfCode.DEFAULT_TIMEOUT_MS;
                int mem = d.get("memoryMB") instanceof Number ? ((Number) d.get("memoryMB")).intValue() : WfCode.DEFAULT_MEM_MB;
                WfCode.Result res = WfCode.run(WfSchema.s(d.get("code")), in == null ? new TreeMap<>() : in, tmo, mem, null);
                if (!res.ok) throw new IllegalStateException(res.error);
                Map<String, Object> o = new TreeMap<>(WfCode.asOutput(res.value));
                return o;
            }
            case "skill": {
                if (hooks.skills == null) throw new IllegalStateException("技能没开");
                Object in = resolve(d.get("inputs"), vars);
                int tmo = d.get("codeTimeoutMs") instanceof Number ? ((Number) d.get("codeTimeoutMs")).intValue() : 10_000;
                return hooks.skills.run((Map<String, Object>) d.get("skill"), in instanceof Map ? (Map<String, Object>) in : new TreeMap<>(), tmo, hooks.httpGet);
            }
            case "api": {
                if (hooks.api == null) throw new IllegalStateException("API 连接器没开");
                ApiConnectors.Conn c = hooks.api.get(WfSchema.s(d.get("connector")));
                if (c == null) throw new IllegalStateException("缺少连接器「" + WfSchema.s(d.get("connector")) + "」，请先在「连接」里添加");
                if (!hooks.api.usable(c)) throw new IllegalStateException("连接器「" + c.name + "」还没测试通过（或改过地址 / key），请先点「测试」");
                Object q = resolve(d.get("query"), vars), h = resolve(d.get("headers"), vars), b = resolve(d.get("body"), vars);
                for (Object hv : h instanceof Map ? ((Map<String, Object>) h).keySet() : List.of()) if (WfSchema.SECRETISH.matcher(String.valueOf(hv)).matches()) throw new IllegalStateException("请求头里不能自己写 key，用连接器的 key");
                ApiConnectors.Resp resp = hooks.api.send(c, WfSchema.s(d.getOrDefault("method", "GET")), WfSchema.s(resolveStr(d.get("path"), vars)),
                        q instanceof Map ? (Map<String, Object>) q : null, h instanceof Map ? (Map<String, Object>) h : null, b, 60);
                if (resp.status == 0) throw new IllegalStateException("请求失败：" + resp.error);
                Map<String, Object> o = resp.output();
                if (resp.status >= 400 && !"continue".equals(d.get("onError"))) throw new IllegalStateException("接口回了 HTTP " + resp.status);
                return o;
            }
            case "tool": {
                if (hooks.caps == null) throw new IllegalStateException("能力没开");
                Object a = resolve(d.get("args"), vars);
                return hooks.caps.call(WfSchema.s(d.get("capability")), a instanceof Map ? (Map<String, Object>) a : new TreeMap<>());
            }
            default:
                throw new IllegalStateException("不认识的节点类型：" + type);
        }
    }

    @SuppressWarnings("unchecked")
    private Object runLoop(G g, Map<String, Object> n, Map<String, Object> vars, Run r) {
        String id = WfSchema.id(n);
        Map<String, Object> d = WfSchema.data(n);
        Object items = resolve(d.get("items"), vars);
        if (items instanceof String) { try { items = Json.parse((String) items); } catch (RuntimeException ignored) { } }
        if (items instanceof Number) { List<Object> l = new ArrayList<>(); for (long i = 0; i < ((Number) items).longValue() && i < HARD_LOOP_MAX; i++) l.add(i); items = l; }
        if (!(items instanceof List)) throw new IllegalArgumentException("循环要一个列表，拿到的是：" + clip(String.valueOf(items), 80));
        List<Object> list = (List<Object>) items;
        int max = d.get("max") instanceof Number ? ((Number) d.get("max")).intValue() : DEFAULT_LOOP_MAX;
        max = Math.max(1, Math.min(max, HARD_LOOP_MAX));
        if (list.size() > max) throw new IllegalArgumentException("列表有 " + list.size() + " 项，超过最多循环次数 " + max);
        Set<String> body = g.bodies.getOrDefault(id, Set.of());
        Set<String> scope = g.scope(body);
        List<String> heads = WfSchema.bodyHeads(g.wf, id);
        String collect = WfSchema.s(d.get("collect"));
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            checkAlive(r);
            Map<String, Object> cur = new TreeMap<>();
            cur.put("item", list.get(i));
            cur.put("index", (long) i);
            cur.put("results", new ArrayList<>(results));
            vars.put(id, cur);
            for (String b : body) vars.remove(b);
            log(r, id, WfSchema.title(n), "iter", "第 " + (i + 1) + " / " + list.size() + " 轮", 0);
            String last = body.isEmpty() ? null : runScope(g, scope, heads, vars, r);
            String pick = !collect.isEmpty() && vars.containsKey(collect) ? collect : last;
            results.add(pick == null ? list.get(i) : vars.get(pick));
        }
        Map<String, Object> o = new TreeMap<>();
        o.put("results", results);
        o.put("count", (long) results.size());
        return o;
    }

    // ------------------------------------------------------------------ 风险

    /** 节点的风险档：read / local / outbound / spend。 */
    @SuppressWarnings("unchecked")
    public String risk(Map<String, Object> n) {
        Map<String, Object> d = WfSchema.data(n);
        switch (WfSchema.type(n)) {
            case "api": {
                ApiConnectors.Conn c = hooks.api == null ? null : hooks.api.get(WfSchema.s(d.get("connector")));
                return ApiConnectors.risk(c, WfSchema.s(d.getOrDefault("method", "GET")));
            }
            case "skill": {
                Object ref = d.get("skill");
                SkillRegistry.Skill k = hooks.skills == null || !(ref instanceof Map) ? null : hooks.skills.get(WfSchema.s(((Map<String, Object>) ref).get("id")));
                return k == null ? "read" : k.risk();
            }
            case "tool": {
                if (hooks.caps == null) return "local";
                String rk = hooks.caps.risk(WfSchema.s(d.get("capability")), d.get("args") instanceof Map ? (Map<String, Object>) d.get("args") : Map.of());
                return rk == null ? "local" : rk;
            }
            default:
                return "read";
        }
    }

    public static String riskZh(String r) {
        switch (r) {
            case "read": return "只读";
            case "local": return "本机改动";
            case "outbound": return "外发";
            case "spend": return "花钱";
            default: return r;
        }
    }

    @SuppressWarnings("unchecked")
    private String approvalDetail(Map<String, Object> n, Map<String, Object> vars) {
        Map<String, Object> d = WfSchema.data(n);
        StringBuilder sb = new StringBuilder("工作流里的「" + WfSchema.title(n) + "」要你批准（" + riskZh(risk(n)) + "）\n");
        switch (WfSchema.type(n)) {
            case "api": {
                ApiConnectors.Conn c = hooks.api == null ? null : hooks.api.get(WfSchema.s(d.get("connector")));
                sb.append(WfSchema.s(d.getOrDefault("method", "GET")).toUpperCase()).append(" ").append(c == null ? "?" : c.name).append(" ").append(WfSchema.s(resolveStr(d.get("path"), vars)));
                Object b = resolve(d.get("body"), vars);
                if (b != null) sb.append("\n发送内容：").append(clip(redact(b instanceof String ? (String) b : Json.stringify(b)), 300));
                break;
            }
            case "tool":
                sb.append(hooks.caps == null ? WfSchema.s(d.get("capability")) : hooks.caps.describe(WfSchema.s(d.get("capability"))));
                sb.append("\n参数：").append(clip(redact(Json.stringify(resolve(d.get("args"), vars))), 300));
                break;
            case "skill": {
                Object ref = d.get("skill");
                SkillRegistry.Skill k = hooks.skills == null || !(ref instanceof Map) ? null : hooks.skills.get(WfSchema.s(((Map<String, Object>) ref).get("id")));
                if (k != null) sb.append("技能「").append(k.name).append("」权限：").append(String.join("、", k.permLines()));
                break;
            }
            default:
                break;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 变量与条件

    /** 解析引用：整段就是一个引用 → 原值；否则字符串里替换；对象 / 列表递归。 */
    @SuppressWarnings("unchecked")
    public static Object resolve(Object v, Map<String, Object> vars) {
        if (v instanceof String) {
            String s = (String) v;
            Matcher m = WfSchema.REF.matcher(s);
            if (m.matches()) return lookup(m.group(1), m.group(2), vars);
            StringBuilder sb = new StringBuilder();
            m.reset();
            int last = 0;
            boolean any = false;
            while (m.find()) {
                any = true;
                sb.append(s, last, m.start());
                Object x = lookup(m.group(1), m.group(2), vars);
                sb.append(x == null ? "" : x instanceof String ? (String) x : Json.stringify(x));
                last = m.end();
            }
            if (!any) return s;
            sb.append(s.substring(last));
            return sb.toString();
        }
        if (v instanceof Map) { Map<String, Object> o = new TreeMap<>(); for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) o.put(e.getKey(), resolve(e.getValue(), vars)); return o; }
        if (v instanceof List) { List<Object> o = new ArrayList<>(); for (Object x : (List<Object>) v) o.add(resolve(x, vars)); return o; }
        return v;
    }

    static Object resolveStr(Object v, Map<String, Object> vars) {
        Object o = resolve(v, vars);
        return o == null ? "" : o instanceof String ? o : Json.stringify(o);
    }

    @SuppressWarnings("unchecked")
    static Object lookup(String id, String path, Map<String, Object> vars) {
        Object cur = vars.get(id);
        if (path == null || path.isEmpty()) return cur;
        Matcher m = java.util.regex.Pattern.compile("\\.([^.\\[]+)|\\[(\\d+)\\]").matcher(path);
        while (m.find() && cur != null) {
            if (m.group(1) != null) {
                if (cur instanceof Map) cur = ((Map<String, Object>) cur).get(m.group(1));
                else if (cur instanceof List && m.group(1).equals("length")) cur = (long) ((List<Object>) cur).size();
                else cur = null;
            } else {
                int i = Integer.parseInt(m.group(2));
                cur = cur instanceof List && i < ((List<Object>) cur).size() ? ((List<Object>) cur).get(i) : null;
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    static boolean cond(Object l, String op, Object r) {
        switch (op) {
            case "empty": return isEmpty(l);
            case "not_empty": return !isEmpty(l);
            case "true": return truthy(l);
            case "false": return !truthy(l);
            case "contains": return l instanceof List ? ((List<Object>) l).stream().anyMatch(x -> String.valueOf(x).equals(String.valueOf(r))) : String.valueOf(l).contains(String.valueOf(r));
            case "not_contains": return !cond(l, "contains", r);
            default: break;
        }
        Double a = num(l), b = num(r);
        if (a != null && b != null) {
            switch (op) {
                case "==": return a.doubleValue() == b.doubleValue();
                case "!=": return a.doubleValue() != b.doubleValue();
                case ">": return a > b;
                case ">=": return a >= b;
                case "<": return a < b;
                case "<=": return a <= b;
                default: return false;
            }
        }
        String x = l == null ? "" : l instanceof String ? (String) l : Json.stringify(l), y = r == null ? "" : r instanceof String ? (String) r : Json.stringify(r);
        switch (op) {
            case "==": return x.equals(y);
            case "!=": return !x.equals(y);
            case ">": return x.compareTo(y) > 0;
            case ">=": return x.compareTo(y) >= 0;
            case "<": return x.compareTo(y) < 0;
            case "<=": return x.compareTo(y) <= 0;
            default: return false;
        }
    }

    static Double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) { try { String s = ((String) o).trim(); if (!s.isEmpty()) return Double.parseDouble(s); } catch (NumberFormatException ignored) { } }
        return null;
    }

    static boolean isEmpty(Object o) {
        return o == null || o instanceof String && ((String) o).isEmpty() || o instanceof List && ((List<?>) o).isEmpty() || o instanceof Map && ((Map<?, ?>) o).isEmpty();
    }

    static boolean truthy(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        if (o instanceof String) return !((String) o).isEmpty() && !o.equals("false") && !o.equals("0");
        return o != null && !isEmpty(o);
    }

    // ------------------------------------------------------------------ 日志

    private void log(Run r, String node, String title, String st, String text, long ms) {
        Map<String, Object> e = new TreeMap<>();
        e.put("at", System.currentTimeMillis());
        if (node != null) e.put("node", node);
        if (title != null) e.put("title", title);
        e.put("status", st);
        e.put("text", clip(redact(text), 600));
        if (ms > 0) e.put("ms", ms);
        r.add(e);
    }

    String redact(String s) {
        if (s == null) return "";
        String t = s;
        try {
            for (String k : hooks.secrets.get()) if (k != null && k.length() >= 4) t = t.replace(k, "••••");
        } catch (RuntimeException ignored) { }
        if (hooks.api != null) t = hooks.api.redact(t);
        return t;
    }

    static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }

    public void shutdown() { pool.shutdownNow(); }
}
