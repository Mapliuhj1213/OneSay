package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.search.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ★ 11-04 午（调研 31 s / 比价 20 s → ≤20 s）：同一轮里模型一次要了多个 web.search / web.read，原来<b>逐个串行</b>执行
 * （摩托车句：两次搜索 4.0 + 2.2 s、两次读页 11.2 + 1.0 s）。这里把它们并行取回（限并发 {@link #MAX_CONCURRENCY}），
 * 每个调用有单独上限（搜索 {@link #SEARCH_CAP_MS} / 读页 {@link #READ_CAP_MS}）；同一批里已经有 ≥2 个不同站点的页面读成功了，
 * 剩下还没回来的最多再等 {@link #ENOUGH_GRACE_MS} 就不等了（「够 2 源即可开始总结」），观测里如实写「读取超时，未等」。
 *
 * <p>只取原始结果（搜索结果列表 / 页面）；观测值的拼装、读页字数预算、来源登记仍在循环线程按原顺序做（{@code AgentLoop.execWeb}），
 * 所以门禁、证据门槛（≥2 独立来源 + ≥1 次读页）、观测格式都不变。research.spawn（要调模型、扣预算）不并行。
 */
public final class ParallelWeb {

    private ParallelWeb() {}

    public static final int MAX_CONCURRENCY = 3;
    public static final long SEARCH_CAP_MS = 6000L;
    public static final long READ_CAP_MS = 8000L;
    public static final long ENOUGH_GRACE_MS = 1500L;
    /** 「够了」= 本批读成功的不同站点数。 */
    public static final int ENOUGH_SITES = 2;

    /** 一次取回的结果。value = List&lt;SearchResult&gt;（搜索）或 WebTools.Page（读页）。 */
    public static final class Done {
        public final Object value;
        public final boolean ok;
        public final long at;

        public Done(Object value, boolean ok, long at) {
            this.value = value;
            this.ok = ok;
            this.at = at;
        }
    }

    public static final class Entry {
        public final String key, sign, target;
        public final long cap;
        volatile Future<Done> f;
        volatile Done done;

        Entry(String key, String sign, String target, long cap) {
            this.key = key;
            this.sign = sign;
            this.target = target;
            this.cap = cap;
        }
    }

    /** 一轮的并行批次。 */
    public static final class Batch {
        public final long t0;
        final Map<String, Entry> byKey = new HashMap<String, Entry>();
        final List<Entry> entries = new ArrayList<Entry>();

        Batch(long t0) {
            this.t0 = t0;
        }

        public int size() {
            return entries.size();
        }

        /** 本批已读成功的不同站点数，以及第 ENOUGH_SITES 个站点成功的时刻（没够 → -1）。 */
        long enoughAt() {
            List<Long> ats = new ArrayList<Long>();
            java.util.Set<String> hosts = new java.util.HashSet<String>();
            List<Entry> reads = new ArrayList<Entry>();
            for (Entry e : entries) if (WebTools.SIGN_READ.equals(e.sign)) reads.add(e);
            java.util.Collections.sort(reads, new java.util.Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    long x = a.done == null ? Long.MAX_VALUE : a.done.at, y = b.done == null ? Long.MAX_VALUE : b.done.at;
                    return x < y ? -1 : x > y ? 1 : 0;
                }
            });
            for (Entry e : reads) {
                Done d = e.done;
                if (d == null || !d.ok) continue;
                if (hosts.add(host(e.target))) ats.add(d.at);
                if (hosts.size() >= ENOUGH_SITES) return ats.get(ats.size() - 1);
            }
            return -1L;
        }
    }

    /** 取原始结果的后端（AgentLoop 用 WebTools.Backend 实现；单测注入假的）。 */
    public interface Fetch {
        List<SearchResult> search(String query, int max);

        WebTools.Page read(String url);
    }

    private static volatile ExecutorService sPool;

    static ExecutorService pool() {
        ExecutorService p = sPool;
        if (p != null) return p;
        synchronized (ParallelWeb.class) {
            if (sPool == null) {
                ThreadPoolExecutor t = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY, 30L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(), com.onesay.assistant.v2.util.Threads.namedFactory("web-par"));
                t.allowCoreThreadTimeOut(true);
                sPool = t;
            }
            return sPool;
        }
    }

    public static String key(String sign, String target) {
        return sign + "|" + (target == null ? "" : target.trim());
    }

    /** 调用 → 目标（搜索词 / 网址）；不是可并行的 web 调用 → null。 */
    public static String targetOf(String sign, org.json.JSONObject args) {
        if (args == null) return null;
        if (WebTools.SIGN_SEARCH.equals(sign)) {
            String q = args.optString("query", "").trim();
            return q.length() == 0 ? null : q;
        }
        if (WebTools.SIGN_READ.equals(sign)) {
            if (args.optInt("offset", 0) > 0) return null;   // 翻页续读：一次只读一段，不并行
            return WebTools.normalizeUrl(args.optString("url", ""));
        }
        return null;
    }

    /**
     * 开一批：calls = {sign, target, max(搜索条数)}；可并行的调用 &lt; 2 个 → null（照旧串行，不开线程）。
     */
    public static Batch start(List<String[]> calls, final Fetch fetch, long now) {
        if (calls == null || fetch == null) return null;
        Batch b = new Batch(now);
        for (String[] c : calls) {
            final String sign = c[0], target = c[1];
            final int max = c.length > 2 ? parseInt(c[2], LoopConfig.DEF_SEARCH_RESULTS) : LoopConfig.DEF_SEARCH_RESULTS;
            String k = key(sign, target);
            if (target == null || b.byKey.containsKey(k)) continue;
            b.byKey.put(k, null);
        }
        if (b.byKey.size() < 2) return null;
        b.byKey.clear();
        for (String[] c : calls) {
            final String sign = c[0], target = c[1];
            final int max = c.length > 2 ? parseInt(c[2], LoopConfig.DEF_SEARCH_RESULTS) : LoopConfig.DEF_SEARCH_RESULTS;
            String k = key(sign, target);
            if (target == null || b.byKey.containsKey(k)) continue;
            final Entry e = new Entry(k, sign, target, WebTools.SIGN_SEARCH.equals(sign) ? SEARCH_CAP_MS : READ_CAP_MS);
            e.f = pool().submit(new Callable<Done>() {
                @Override public Done call() {
                    Done d;
                    try {
                        if (WebTools.SIGN_SEARCH.equals(sign)) {
                            List<SearchResult> r = fetch.search(target, max);
                            d = new Done(r, r != null && !r.isEmpty(), System.currentTimeMillis());
                        } else {
                            WebTools.Page p = fetch.read(target);
                            d = new Done(p, p != null && p.ok(), System.currentTimeMillis());
                        }
                    } catch (Throwable t) {
                        d = new Done(null, false, System.currentTimeMillis());
                    }
                    e.done = d;
                    return d;
                }
            });
            b.byKey.put(k, e);
            b.entries.add(e);
        }
        return b;
    }

    /** 现在最多还该等这个调用多久（ms）：自身上限剩余；本批已够 2 个站点 → 不超过「够了之后 1.5 s」。纯函数（时间外部给）。 */
    public static long waitBudget(Batch b, Entry e, long now) {
        long left = b.t0 + e.cap - now;
        long enough = b.enoughAt();
        if (enough > 0 && WebTools.SIGN_READ.equals(e.sign)) left = Math.min(left, enough + ENOUGH_GRACE_MS - now);
        return Math.max(0L, left);
    }

    /**
     * 取这个调用的结果（循环线程）：批里没有 → null（照旧自己执行）；有 → 按 {@link #waitBudget} 等，等不到 → 超时 Done（value=null, ok=false）。
     */
    public static Done take(Batch b, String sign, String target) {
        if (b == null || target == null) return null;
        Entry e = b.byKey.get(key(sign, target));
        if (e == null) return null;
        b.byKey.remove(e.key);   // 每个调用只取一次（同参数重复调用走原路径）
        while (true) {
            if (e.done != null) return e.done;
            if (e.f.isDone()) {
                try {
                    Done d = e.f.get();
                    e.done = d;
                    return d;
                } catch (Throwable t) {
                    return new Done(null, false, System.currentTimeMillis());
                }
            }
            long left = waitBudget(b, e, System.currentTimeMillis());
            if (left <= 0) {
                e.f.cancel(true);
                return new Done(null, false, -1L);
            }
            try {
                Done d = e.f.get(Math.min(left, 200L), TimeUnit.MILLISECONDS);
                e.done = d;
                return d;
            } catch (java.util.concurrent.TimeoutException te) {
                // 再看一次「够了没有」
            } catch (Throwable t) {
                return new Done(null, false, System.currentTimeMillis());
            }
        }
    }

    /** 超时 Done（at = -1）。 */
    public static boolean timedOut(Done d) {
        return d != null && d.at == -1L;
    }

    static String host(String url) {
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT).replaceFirst("^[a-z]+://", "");
        int s = u.indexOf('/');
        if (s >= 0) u = u.substring(0, s);
        u = u.replaceFirst("^(www|m|wap|mobile)\\.", "");
        String[] p = u.split("\\.");
        if (p.length >= 2) return p[p.length - 2] + "." + p[p.length - 1];
        return u;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }
}
