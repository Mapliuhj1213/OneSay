package com.onesay.desktop;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * [always-on-1120] 常驻分工的模型和纯逻辑（不碰网络、不碰界面，单测直接测）。
 *
 * <p>个人电脑不会 7×24 开机。一直要在线的只有「定时、触发器、查网页、发通知」这几样，放到常驻节点（中转兼任，见 onesay-relay 的 src/aon.js）；
 * 必须用电脑的任务到点时电脑不在 → 进队列，电脑上线后按策略补跑或标过期（可选：经局域网唤醒代理发魔术包把电脑叫起来）。
 *
 * <ul>
 *   <li>执行位置 {@link Where}：电脑本地（只在电脑开着时计时；关机期间错过的，下次启动按策略补跑 / 过期）/
 *   常驻节点（节点计时：提醒电脑关着也照样送到手机；需电脑的任务电脑离线时进队列、可唤醒）。</li>
 *   <li>每次到点叫一次「发生」，键 = 任务 id + "@" + 到点毫秒。本机计时、节点转来的到点、节点的排队三条路可能同时报同一次发生，
 *   一律按这个键去重（{@link Ledger}），只做一次。</li>
 *   <li>错过了怎么办 {@link Missed}：补跑一次（默认，宽限内；重复任务错过多次也只补最近一次，像 anacron）/ 过期不跑。</li>
 *   <li>状态 {@link St}：排队 → 已唤醒 → 执行中 → 已完成 / 已过期 / 没做成 / 需要你操作。</li>
 *   <li>交给节点的提醒内容用配对密钥派生的钥匙 AES-256-GCM 密封（{@link #seal}）：节点只存密文、原样转发，手机用同一把配对密钥解开。</li>
 * </ul>
 */
public final class AlwaysOn {
    private AlwaysOn() {}

    /** 到点后这么久以内算「准时」，不提示补跑。 */
    public static final long ON_TIME_MS = 90_000;
    public static final long DEF_GRACE_MS = 2 * 3600_000L;
    public static final long MAX_GRACE_MS = 7 * 24 * 3600_000L;
    public static final long MIN_EVERY_MS = 60_000;
    public static final long MIN_WATCH_MS = 15 * 60_000L;
    public static final int MAX_JOBS = 200;

    public enum Where {
        LOCAL("local", "电脑本地"), NODE("node", "常驻节点");
        public final String id, zh;
        Where(String id, String zh) { this.id = id; this.zh = zh; }
        public static Where of(Object s) { return "node".equals(String.valueOf(s)) ? NODE : LOCAL; }
    }

    public enum Missed {
        RUN("run", "补跑一次"), EXPIRE("expire", "过期不跑");
        public final String id, zh;
        Missed(String id, String zh) { this.id = id; this.zh = zh; }
        public static Missed of(Object s) { return "expire".equals(String.valueOf(s)) ? EXPIRE : RUN; }
    }

    /** 一次「发生」的状态（界面标签和节点的 state 同名）。 */
    public enum St {
        SCHEDULED("scheduled", "已排程"), QUEUED("queued", "排队"), WAKING("waking", "已唤醒"), WAKE_FAILED("wake_failed", "唤醒失败"),
        RUNNING("running", "执行中"), DONE("done", "已完成"), EXPIRED("expired", "已过期"), FAILED("failed", "没做成"),
        NEEDS_USER("needs_user", "需要你操作"), DUP("dup", "重复（已做过）");
        public final String id, zh;
        St(String id, String zh) { this.id = id; this.zh = zh; }
        public static St of(Object s) { for (St x : values()) if (x.id.equals(String.valueOf(s))) return x; return QUEUED; }
        public boolean finished() { return this == DONE || this == EXPIRED || this == FAILED || this == NEEDS_USER || this == DUP; }
    }

    /** 做什么：提醒（哪都能送）/ 运行快捷指令（需电脑）/ 查网页（只能在常驻节点）。 */
    public enum Action {
        NOTIFY("notify", "提醒", false), SHORTCUT("shortcut", "运行快捷指令", true), WATCH("watch", "查网页", false);
        public final String id, zh;
        public final boolean needsPc;
        Action(String id, String zh, boolean needsPc) { this.id = id; this.zh = zh; this.needsPc = needsPc; }
        public static Action of(Object s) { for (Action a : values()) if (a.id.equals(String.valueOf(s))) return a; return null; }
        /** 节点那边的 kind。 */
        public String nodeKind() { return this == NOTIFY ? "notify" : this == WATCH ? "watch" : "pc"; }
    }

    /** 一条定时 / 提醒 / 触发任务（存在电脑上；交给节点的只有时间和密文）。 */
    public static final class Job {
        public String id = "", title = "", text = "", target = "", url = "", contains = "";
        public Action action = Action.NOTIFY;
        public Where where = Where.LOCAL;
        public Missed missed = Missed.RUN;
        public long at, every, grace = DEF_GRACE_MS;
        public boolean wake, enabled = true;
        /** 本机已经处理到哪个时刻（含）。 */
        public long seen;

        public boolean needsPc() { return action.needsPc; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("title", title); m.put("text", text); m.put("action", action.id); m.put("target", target);
            m.put("url", url); m.put("contains", contains); m.put("where", where.id); m.put("missed", missed.id);
            m.put("at", at); m.put("every", every); m.put("grace", grace); m.put("wake", wake); m.put("enabled", enabled); m.put("seen", seen);
            return m;
        }

        public static Job from(Map<String, Object> m) {
            Job j = new Job();
            j.id = str(m.get("id")); j.title = str(m.get("title")); j.text = str(m.get("text")); j.target = str(m.get("target"));
            j.url = str(m.get("url")); j.contains = str(m.get("contains"));
            Action a = Action.of(m.get("action"));
            j.action = a == null ? Action.NOTIFY : a;
            j.where = Where.of(m.get("where")); j.missed = Missed.of(m.get("missed"));
            j.at = num(m.get("at")); j.every = num(m.get("every"));
            j.grace = m.get("grace") == null ? DEF_GRACE_MS : num(m.get("grace"));
            j.wake = Boolean.TRUE.equals(m.get("wake")) || "true".equals(String.valueOf(m.get("wake")));
            j.enabled = !Boolean.FALSE.equals(m.get("enabled")) && !"false".equals(String.valueOf(m.get("enabled")));
            j.seen = num(m.get("seen"));
            return j;
        }
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return o == null ? 0 : (long) Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** 新建 / 修改时的校验：返回中文问题，null = 没问题。顺带规整（查网页强制常驻节点、宽限封顶）。 */
    public static String validate(Job j, boolean nodeAvailable) {
        if (j.title.isBlank()) return "写个名字";
        if (j.title.length() > 60) return "名字太长（60 字以内）";
        if (j.text.length() > 500) return "提醒内容太长（500 字以内）";
        if (j.at <= 0) return "选个时间";
        if (j.every != 0 && j.every < MIN_EVERY_MS) return "重复间隔至少 1 分钟";
        if (j.grace < 0) j.grace = 0;
        j.grace = Math.min(j.grace, MAX_GRACE_MS);
        if (j.action == Action.SHORTCUT && !j.target.matches("[^\\\\/:*?\"<>|]{1,80}")) return "选一个快捷指令";
        if (j.action == Action.WATCH) {
            if (!j.url.matches("(?i)https?://[^\\s/@]+(/\\S*)?") || j.url.length() > 500) return "网址要以 http:// 或 https:// 开头（不能带账号密码）";
            if (j.contains.length() > 200) return "要出现的文字太长（200 字以内）";
            j.where = Where.NODE; // 电脑关着也要查：只能放常驻节点
            if (j.every == 0) j.every = MIN_WATCH_MS;
            if (j.every < MIN_WATCH_MS) return "查网页最短 15 分钟一次";
        }
        if (j.where == Where.NODE && !nodeAvailable) return "常驻节点要先在「连接」里配好中转";
        if (!j.needsPc() || j.where == Where.LOCAL) j.wake = false; // 只有「需电脑、放在常驻节点」的任务才谈得上唤醒
        return null;
    }

    /** after 之后（不含）第一次到点；没有了返回 -1。 */
    public static long nextDue(Job j, long after) {
        if (j.at > after) return j.at;
        if (j.every <= 0) return -1;
        long n = (after - j.at) / j.every + 1;
        return j.at + n * j.every;
    }

    /** (from, to] 之间的到点时刻（最多 limit 个，取最晚的那些）。 */
    public static List<Long> occurrences(Job j, long from, long to, int limit) {
        List<Long> out = new ArrayList<>();
        if (to <= from) return out;
        if (j.every <= 0) { if (j.at > from && j.at <= to) out.add(j.at); return out; }
        long first = nextDue(j, from);
        if (first < 0 || first > to) return out;
        long count = (to - first) / j.every + 1;
        long skip = Math.max(0, count - limit);
        for (long k = skip; k < count; k++) out.add(first + k * j.every);
        return out;
    }

    public static String key(String jobId, long due) { return jobId + "@" + due; }

    /** 一次发生到了 now 该怎么办：准时 / 宽限内补跑 → 跑；否则过期。 */
    public static St decide(Job j, long due, long now) {
        long late = now - due;
        if (late <= ON_TIME_MS) return St.RUNNING;
        if (j.missed == Missed.RUN && late <= j.grace) return St.RUNNING;
        return St.EXPIRED;
    }

    /**
     * 本机计时一轮：给出 (seen, now] 里每次发生的决定。重复任务错过多次时只有最晚一次可能补跑，其余记过期（合并成一句通知）。
     * 返回 [到点时刻, 决定] 列表；不改 job.seen（调用方处理完再改）。
     */
    public static List<Object[]> plan(Job j, long now) {
        List<Object[]> out = new ArrayList<>();
        if (!j.enabled || j.action == Action.WATCH) return out;
        long from = j.seen > 0 ? j.seen : now - 1; // 新任务：从现在算，不回头补以前的
        List<Long> occ = occurrences(j, from, now, 50);
        for (int i = 0; i < occ.size(); i++) {
            long due = occ.get(i);
            St d = decide(j, due, now);
            if (i < occ.size() - 1 && now - due > ON_TIME_MS) d = St.EXPIRED; // 不是最近一次：合并掉
            out.add(new Object[] {due, d});
        }
        return out;
    }

    /** 处理过的发生：键 → 结果，最多留 max 条（按插入顺序丢最老的）。 */
    public static final class Ledger {
        private final LinkedHashMap<String, String> m = new LinkedHashMap<>();
        private final int max;
        public Ledger(int max) { this.max = max; }
        public synchronized boolean has(String k) { return m.containsKey(k); }
        public synchronized String get(String k) { return m.get(k); }
        /** 第一次记 → true；已有 → false（不覆盖）。 */
        public synchronized boolean claim(String k, String result) {
            if (m.containsKey(k)) return false;
            m.put(k, result);
            while (m.size() > max) m.remove(m.keySet().iterator().next());
            return true;
        }
        public synchronized void set(String k, String result) { m.put(k, result); while (m.size() > max) m.remove(m.keySet().iterator().next()); }
        public synchronized Map<String, Object> toMap() { return new LinkedHashMap<String, Object>(m); }
        public synchronized void load(Map<String, Object> src) { m.clear(); if (src != null) for (Map.Entry<String, Object> e : src.entrySet()) m.put(e.getKey(), String.valueOf(e.getValue())); }
        public synchronized int size() { return m.size(); }
    }

    // ------------------------------------------------------------------ 密封（给常驻节点的内容）

    static final String SEAL_LABEL = "onesay.aon.v1";

    /** 从配对密钥派生的密封钥匙：HMAC-SHA256(配对密钥, "onesay.aon.v1")。和签名用的不是同一把。 */
    static byte[] sealKey(byte[] pairKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pairKey, "HmacSHA256"));
            return mac.doFinal(SEAL_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] aad(String pcId, String jobId) { return (SEAL_LABEL + "\n" + pcId + "\n" + jobId).getBytes(StandardCharsets.UTF_8); }

    private static final SecureRandom RNG = new SecureRandom();

    /** base64url(12 字节随机数 ‖ AES-256-GCM 密文+16 字节标签)，附加数据绑定电脑 id 和任务 id（换到别的任务上解不开）。 */
    public static String seal(byte[] pairKey, String pcId, String jobId, Map<String, Object> payload) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sealKey(pairKey), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad(pcId, jobId));
            byte[] ct = c.doFinal(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 解开；钥匙不对 / 被改过 / 换了任务 → null。 */
    public static Map<String, Object> open(byte[] pairKey, String pcId, String jobId, String sealed) {
        try {
            byte[] b = Base64.getUrlDecoder().decode(sealed);
            if (b.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sealKey(pairKey), "AES"), new GCMParameterSpec(128, b, 0, 12));
            c.updateAAD(aad(pcId, jobId));
            byte[] pt = c.doFinal(b, 12, b.length - 12);
            return Json.obj(new String(pt, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 交给节点的那一份：只有时间、种类和密文（查网页另带网址和关键字：节点要自己去查，所以对节点可见）。 */
    public static Map<String, Object> nodeJob(Job j, byte[] pairKey, String pcId) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", j.id);
        m.put("kind", j.action.nodeKind());
        m.put("at", j.at);
        m.put("every", j.every);
        m.put("grace", j.missed == Missed.EXPIRE ? Math.min(j.grace, ON_TIME_MS) : j.grace);
        m.put("wake", j.wake);
        Map<String, Object> p = new TreeMap<>();
        p.put("title", j.title);
        p.put("text", j.action == Action.SHORTCUT ? "到点要在电脑上运行快捷指令「" + j.target + "」" : j.text);
        p.put("action", j.action.zh);
        m.put("sealed", seal(pairKey, pcId, j.id, p));
        if (j.action == Action.WATCH) { m.put("url", j.url); m.put("contains", j.contains); }
        return m;
    }
}
