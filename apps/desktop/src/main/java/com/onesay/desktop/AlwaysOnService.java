package com.onesay.desktop;

import com.onesay.desktop.AlwaysOn.Action;
import com.onesay.desktop.AlwaysOn.Job;
import com.onesay.desktop.AlwaysOn.St;
import com.onesay.desktop.AlwaysOn.Where;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * [always-on-1120] 电脑端的常驻分工：本机计时、和常驻节点（中转）同步、离线补跑 / 过期、去重、通知。
 *
 * <p>三条路会报同一次「发生」：本机计时（电脑开着；或者启动时发现关机期间错过的）、节点转来的到点（aon_fire）、
 * 节点的排队（aon_queue，电脑离线期间到点的）。都走 {@link #occur}，按「任务 id@到点时刻」在 {@link AlwaysOn.Ledger} 里认领，只做一次；
 * 节点来的处理完回 aon_ack（结果取账本里的），节点据此清队列、告诉手机。
 *
 * <p>交给节点的只有时间和密文；配对密钥、Key 都不出电脑。需电脑的任务真正执行仍在电脑上，走和对话同一条审批链（{@link Runner}）。
 */
public final class AlwaysOnService {
    /** 到常驻节点的通道（实际是中转连接）。 */
    public interface Port {
        boolean up();
        void send(Map<String, Object> frame);
    }

    /** 真正执行一次：提醒 → 发通知；快捷指令 → 走审批链运行。返回 [St, 说明]。 */
    public interface Runner {
        Object[] run(Job j, long due, boolean late);
    }

    /** 发一条电脑通知（类别：提醒）。 */
    public interface Notifier {
        void post(String title, String text, String from);
    }

    public static final int MAX_ITEMS = 60;

    private final Path file;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final List<Job> jobs = new ArrayList<>();
    private final AlwaysOn.Ledger ledger = new AlwaysOn.Ledger(1000);
    private final Deque<Map<String, Object>> items = new ArrayDeque<>();
    private final Set<String> pendingAck = new HashSet<>();
    public volatile Port port;
    public volatile Runner runner;
    public volatile Notifier notifier = (t, x, f) -> { };
    public volatile Executor exec;
    /** 电脑 id 和配对密钥（密封用）；网络唤醒设置。由 App 从 Config 填。 */
    public volatile String pcId = "";
    public volatile byte[] pairKey = new byte[0];
    public volatile boolean nodeConfigured;
    public volatile boolean wolEnabled, wolAuto;
    public volatile String wolMac = "", wolBcast = "";
    private volatile Map<String, Object> nodeStatus = new TreeMap<>();
    private volatile String nodeErr = "";
    /** 中转是旧版本（不认识常驻分工的帧）：不再每分钟问状态，界面提示更新中转。 */
    private volatile boolean nodeOld;
    private volatile long lastSave, lastStatusReq;
    private ScheduledExecutorService sched;

    public AlwaysOnService(Path file, LongSupplier clock, Consumer<String> log) {
        this.file = file;
        this.clock = clock;
        this.log = log == null ? s -> { } : log;
        this.exec = null; // null = 就地执行（单测）；start() 时换成单独的执行线程
    }

    // ------------------------------------------------------------------ 存取

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        jobs.clear();
        items.clear();
        try {
            if (file != null && Files.exists(file)) {
                Map<String, Object> m = Json.obj(Files.readString(file, StandardCharsets.UTF_8));
                if (m.get("jobs") instanceof List) for (Object o : (List<Object>) m.get("jobs")) if (o instanceof Map) jobs.add(Job.from((Map<String, Object>) o));
                if (m.get("ledger") instanceof Map) ledger.load((Map<String, Object>) m.get("ledger"));
                if (m.get("items") instanceof List) for (Object o : (List<Object>) m.get("items")) if (o instanceof Map) items.addLast(new LinkedHashMap<>((Map<String, Object>) o));
            }
        } catch (Exception e) {
            log.accept("常驻分工：任务文件读不了，先当空的（" + e.getClass().getSimpleName() + "）");
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Object> js = new ArrayList<>();
            for (Job j : jobs) js.add(j.toMap());
            m.put("jobs", js);
            m.put("ledger", ledger.toMap());
            m.put("items", new ArrayList<Object>(items));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(m), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastSave = clock.getAsLong();
        } catch (Exception e) {
            log.accept("常驻分工：保存失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    // ------------------------------------------------------------------ 运行

    /** 启动：先补一轮（关机期间错过的），之后每 15 秒一轮。 */
    public void start() {
        if (exec == null) exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "onesay-aon-run"); t.setDaemon(true); return t; });
        sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "onesay-aon"); t.setDaemon(true); return t; });
        sched.scheduleWithFixedDelay(() -> { try { tick(); } catch (Throwable t) { log.accept("常驻分工：计时出错 " + t.getClass().getSimpleName()); } }, 0, 15, TimeUnit.SECONDS);
    }

    public void stop() { if (sched != null) sched.shutdownNow(); save(); }

    /** 一轮本机计时：每条任务 (seen, now] 里的发生按策略执行或过期。 */
    public void tick() {
        long now = clock.getAsLong();
        List<Object[]> todo = new ArrayList<>();
        boolean changed = false;
        synchronized (this) {
            for (Job j : jobs) {
                for (Object[] p : AlwaysOn.plan(j, now)) {
                    // 放在常驻节点的提醒：节点是它的钟，电脑关着时节点已经送到手机（没人在线时节点会在电脑上线后补送）。
                    // 本机只在准时的时候兜底（中转一时连不上），晚到的不认领，免得抢了节点补送、或者手机已经提醒过电脑再提醒一遍。
                    if (j.where == Where.NODE && j.action == Action.NOTIFY && now - (Long) p[0] > AlwaysOn.ON_TIME_MS) continue;
                    todo.add(new Object[] {j, p[0], p[1]});
                }
                if (j.seen != now) { j.seen = now; }
            }
            changed = !todo.isEmpty();
        }
        for (Object[] t : todo) occur((Job) t[0], (Long) t[1], (St) t[2], "本机", null, false);
        if (changed || now - lastSave > 60_000) save();
        Port p = port;
        if (p != null && p.up() && now - lastStatusReq > 60_000 && !nodeOld) { lastStatusReq = now; p.send(frame("aon_status_req")); }
    }

    /**
     * 处理一次发生。decision = 到 now 为止的判断（执行 / 过期）；via = 本机 / 常驻节点；nodeState = 节点那边的状态（排队 / 已唤醒…，可空）；
     * fromNode = 要回 aon_ack。
     */
    void occur(Job j, long due, St decision, String via, St nodeState, boolean fromNode) {
        String key = AlwaysOn.key(j.id, due);
        if (!ledger.claim(key, St.RUNNING.id)) {
            String done = ledger.get(key);
            if (fromNode) {
                if (St.RUNNING.id.equals(done)) synchronized (this) { pendingAck.add(key); }
                else ack(j.id, due, done);
            }
            return;
        }
        long now = clock.getAsLong();
        long late = now - due;
        boolean isLate = late > AlwaysOn.ON_TIME_MS;
        Map<String, Object> it = item(key, j, due, via);
        if (nodeState != null) note(it, nodeState, nodeState == St.WAKING ? "电脑离线时到点，常驻节点发了唤醒" : nodeState == St.WAKE_FAILED ? "电脑离线时到点，唤醒后没上线" : "电脑离线时到点，在常驻节点排队");
        if (decision == St.EXPIRED) {
            ledger.set(key, St.EXPIRED.id);
            note(it, St.EXPIRED, "到点时电脑没开（" + hm(due) + "），" + (j.missed == AlwaysOn.Missed.EXPIRE ? "按设置过期不跑" : "超过宽限没补跑"));
            notifier.post("已过期：" + j.title, "到点时电脑没开（" + hm(due) + "），" + (j.missed == AlwaysOn.Missed.EXPIRE ? "按设置不补跑。" : "超过宽限，没有补跑。"), via);
            if (fromNode) ack(j.id, due, St.EXPIRED.id);
            save();
            return;
        }
        if (fromNode) synchronized (this) { pendingAck.add(key); }
        note(it, St.RUNNING, isLate ? "晚了 " + mins(late) + "，补跑一次" : "到点执行");
        Runnable r = () -> {
            Object[] res;
            try {
                Runner rn = runner;
                res = rn == null ? new Object[] {St.FAILED, "没有执行器"} : rn.run(j, due, isLate);
            } catch (Throwable t) {
                res = new Object[] {St.FAILED, "出错：" + t.getClass().getSimpleName()};
            }
            St st = (St) res[0];
            String text = String.valueOf(res[1]);
            ledger.set(key, st.id);
            note(it, st, text);
            if (isLate && j.action != Action.NOTIFY) notifier.post("补跑" + (st == St.DONE ? "完成" : "：" + st.zh) + "：" + j.title, "电脑离线时到点（" + hm(due) + "），上线后补跑。" + text, via);
            else if (j.action != Action.NOTIFY && st != St.DONE) notifier.post(st.zh + "：" + j.title, text, via);
            boolean doAck;
            synchronized (this) { doAck = pendingAck.remove(key); }
            if (doAck) ack(j.id, due, st.id);
            save();
        };
        Executor ex = exec;
        if (ex == null) r.run(); else ex.execute(r);
    }

    private void ack(String id, long due, String result) {
        Port p = port;
        if (p == null || !p.up()) return;
        Map<String, Object> f = frame("aon_ack");
        f.put("id", id);
        f.put("due", due);
        f.put("result", St.RUNNING.id.equals(result) ? St.DONE.id : result);
        p.send(f);
    }

    private synchronized Map<String, Object> item(String key, Job j, long due, String via) {
        for (Map<String, Object> m : items) if (key.equals(m.get("key"))) return m;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("job", j.id);
        m.put("title", j.title);
        m.put("action", j.action.zh);
        m.put("due", due);
        m.put("via", via);
        m.put("state", St.QUEUED.id);
        m.put("history", new ArrayList<Object>());
        items.addFirst(m);
        while (items.size() > MAX_ITEMS) items.removeLast();
        return m;
    }

    @SuppressWarnings("unchecked")
    private synchronized void note(Map<String, Object> it, St st, String text) {
        it.put("state", st.id);
        it.put("note", text);
        it.put("at", clock.getAsLong());
        Object h = it.get("history");
        if (h instanceof List) ((List<Object>) h).add(st.id);
    }

    static String hm(long ms) {
        java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault());
        return String.format("%d月%d日 %02d:%02d", t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute());
    }

    static String mins(long ms) {
        long m = Math.max(1, ms / 60_000);
        return m < 60 ? m + " 分钟" : m < 1440 ? (m / 60) + " 小时" + (m % 60 > 0 ? " " + (m % 60) + " 分钟" : "") : (m / 1440) + " 天";
    }

    // ------------------------------------------------------------------ 和常驻节点

    static Map<String, Object> frame(String type) {
        Map<String, Object> f = new TreeMap<>();
        f.put("type", type);
        return f;
    }

    /** 中转连上：整份同步交给节点的任务和唤醒设置，再问一次状态。 */
    public void onNodeUp() {
        Port p = port;
        if (p == null || !p.up() || pairKey.length < 32 || pcId.isEmpty()) return;
        Map<String, Object> f = frame("aon_sync");
        List<Object> js = new ArrayList<>();
        synchronized (this) { for (Job j : jobs) if (j.where == Where.NODE && j.enabled) js.add(AlwaysOn.nodeJob(j, pairKey, pcId)); }
        f.put("jobs", js);
        p.send(f);
        Map<String, Object> w = frame("aon_wake_cfg");
        w.put("enabled", wolEnabled && !WakeOnLan.normMac(wolMac).isEmpty());
        w.put("mac", WakeOnLan.normMac(wolMac));
        w.put("bcast", WakeOnLan.ipv4(wolBcast) ? wolBcast : "255.255.255.255");
        w.put("port", 9L);
        w.put("auto", wolAuto);
        p.send(w);
        lastStatusReq = clock.getAsLong();
        p.send(frame("aon_status_req"));
    }

    /** 中转转来的 aon_* 帧。 */
    @SuppressWarnings("unchecked")
    public void onFrame(Map<String, Object> f) {
        String t = String.valueOf(f.get("type"));
        long now = clock.getAsLong();
        switch (t) {
            case "aon_queue": {
                if (!(f.get("items") instanceof List)) return;
                for (Object o : (List<Object>) f.get("items")) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    String id = AlwaysOn.str(m.get("id"));
                    long due = AlwaysOn.num(m.get("due"));
                    Job j = find(id);
                    if (j == null) { ack(id, due, St.DUP.id); continue; } // 电脑上已经删了的任务
                    St ns = St.of(m.get("state"));
                    St d = ns == St.EXPIRED ? St.EXPIRED : AlwaysOn.decide(j, due, now);
                    occur(j, due, d, "常驻节点", ns, true);
                }
                return;
            }
            case "aon_fire": {
                String id = AlwaysOn.str(f.get("id"));
                long due = AlwaysOn.num(f.get("due"));
                Job j = find(id);
                String kind = AlwaysOn.str(f.get("kind"));
                if ("watch".equals(kind)) { watchHit(j, id, due, f); return; }
                if (j == null) {
                    if ("pc".equals(kind)) ack(id, due, St.DUP.id);
                    return;
                }
                occur(j, due, AlwaysOn.decide(j, due, now), "常驻节点", null, "pc".equals(kind));
                return;
            }
            case "aon_status": nodeStatus = new TreeMap<>(f); nodeErr = ""; nodeOld = false; return;
            case "aon_err": nodeErr = errZh(AlwaysOn.str(f.get("code"))); nodeOld = "unknown".equals(f.get("code")); log.accept("常驻节点报错：" + f.get("code") + "（" + f.get("op") + "）"); return;
            default: return;
        }
    }

    private void watchHit(Job j, String id, long due, Map<String, Object> f) {
        String key = AlwaysOn.key(id, due);
        if (!ledger.claim(key, St.DONE.id)) return;
        String title = j != null ? j.title : "查网页";
        if (j == null) {
            Map<String, Object> p = AlwaysOn.open(pairKey, pcId, id, AlwaysOn.str(f.get("sealed")));
            if (p != null) title = AlwaysOn.str(p.get("title"));
        }
        String text = j != null && !j.contains.isEmpty() ? "页面上出现了「" + j.contains + "」" : "页面内容变了";
        Map<String, Object> it = item(key, j != null ? j : stub(id, title), due, "常驻节点");
        note(it, St.DONE, text);
        notifier.post("网页有变化：" + title, text + (j != null ? "\n" + j.url : ""), "常驻节点");
        save();
    }

    private static Job stub(String id, String title) { Job j = new Job(); j.id = id; j.title = title; j.action = Action.WATCH; return j; }

    static String errZh(String code) {
        switch (code) {
            case "pc_only": return "只有电脑能登记任务";
            case "too_many": return "任务太多（最多 200 条）";
            case "every_too_short": return "重复间隔太短";
            case "private_url": return "查网页不能查内网地址";
            case "bad_url": return "网址不对";
            case "bad_sealed": return "提醒内容太长";
            case "unknown": return "中转还是旧版本，不认识常驻分工（要更新中转）";
            default: return "常驻节点拒绝了（" + code + "）";
        }
    }

    private void resync() { Port p = port; if (p != null && p.up()) onNodeUp(); }

    // ------------------------------------------------------------------ 增删改

    public synchronized Job find(String id) { for (Job j : jobs) if (j.id.equals(id)) return j; return null; }

    public synchronized List<Job> jobs() { return new ArrayList<>(jobs); }

    /** 新增；返回 null = 成功，否则是给人看的原因。 */
    public String add(Job j) {
        String bad = AlwaysOn.validate(j, nodeConfigured);
        if (bad != null) return bad;
        synchronized (this) {
            if (jobs.size() >= AlwaysOn.MAX_JOBS) return "最多 200 条";
            if (j.id.isEmpty()) j.id = "j" + Protocol.newId().replaceAll("[^A-Za-z0-9]", "").substring(0, 12);
            j.seen = clock.getAsLong() - 1;
            jobs.add(j);
        }
        save();
        if (j.where == Where.NODE) resync();
        log.accept("常驻分工：加了「" + j.title + "」（" + j.action.zh + " · " + j.where.zh + "）");
        return null;
    }

    public String remove(String id) {
        Job j;
        synchronized (this) { j = find(id); if (j == null) return "没有这条"; jobs.remove(j); }
        save();
        Port p = port;
        if (j.where == Where.NODE && p != null && p.up()) { Map<String, Object> f = frame("aon_del"); f.put("id", id); p.send(f); }
        return null;
    }

    public String toggle(String id, boolean on) {
        Job j;
        synchronized (this) { j = find(id); if (j == null) return "没有这条"; j.enabled = on; if (on) j.seen = clock.getAsLong(); }
        save();
        if (j.where == Where.NODE) resync();
        return null;
    }

    public synchronized void clearItems() { items.clear(); save(); }

    /** 设置变了（网络唤醒开关 / 网卡）：重新告诉节点。 */
    public void wakeChanged() { resync(); }

    // ------------------------------------------------------------------ 界面

    public synchronized Map<String, Object> view() {
        long now = clock.getAsLong();
        Map<String, Object> v = new LinkedHashMap<>();
        Port p = port;
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("configured", nodeConfigured);
        node.put("up", p != null && p.up());
        Object ag = nodeStatus.get("agents");
        node.put("agents", ag instanceof List ? ag : List.of());
        node.put("queued", nodeStatus.getOrDefault("queued", 0L));
        node.put("jobs", nodeStatus.getOrDefault("jobs", 0L));
        node.put("wake", nodeStatus.getOrDefault("wake", Map.of()));
        node.put("err", nodeErr);
        v.put("node", node);
        List<Object> js = new ArrayList<>();
        int local = 0, onNode = 0;
        for (Job j : jobs) {
            Map<String, Object> m = j.toMap();
            long nx = AlwaysOn.nextDue(j, now);
            m.put("next", nx);
            m.put("needs_pc", j.needsPc());
            m.put("where_zh", j.where.zh);
            m.put("action_zh", j.action.zh);
            m.put("missed_zh", j.missed.zh);
            js.add(m);
            if (j.where == Where.NODE) onNode++; else local++;
        }
        v.put("jobs", js);
        v.put("count_local", (long) local);
        v.put("count_node", (long) onNode);
        List<Object> its = new ArrayList<>();
        for (Map<String, Object> m : items) {
            Map<String, Object> c = new LinkedHashMap<>(m);
            c.put("state_zh", St.of(m.get("state")).zh);
            its.add(c);
        }
        v.put("items", its);
        return v;
    }
}
