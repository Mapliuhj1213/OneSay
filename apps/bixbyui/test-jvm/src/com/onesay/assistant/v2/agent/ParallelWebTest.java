package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.search.SearchResult;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** ★ 11-04 午：同轮多个 web.search / web.read 并行取回、单个上限、够 2 个站点不等慢的。 */
public class ParallelWebTest {

    static final class SlowFetch implements ParallelWeb.Fetch {
        final java.util.Map<String, Long> delay = new java.util.HashMap<String, Long>();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        volatile int maxInFlight;

        void enter() {
            int n = inFlight.incrementAndGet();
            synchronized (this) { if (n > maxInFlight) maxInFlight = n; }
        }

        void sleep(String k) {
            Long d = delay.get(k);
            try { Thread.sleep(d == null ? 200L : d); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        @Override public List<SearchResult> search(String q, int max) {
            enter();
            try {
                sleep(q);
                return new ArrayList<SearchResult>(Arrays.asList(new SearchResult("t " + q, "https://a.com/" + q.length(), "s")));
            } finally { inFlight.decrementAndGet(); }
        }

        @Override public WebTools.Page read(String url) {
            enter();
            try {
                sleep(url);
                if (Thread.currentThread().isInterrupted()) return new WebTools.Page(url, null, "被取消");
                return new WebTools.Page(url, "正文 " + url, null);
            } finally { inFlight.decrementAndGet(); }
        }
    }

    static List<String[]> calls(String[]... c) { return new ArrayList<String[]>(Arrays.asList(c)); }

    @Test public void twoSearchesRunInParallel() {
        SlowFetch f = new SlowFetch();
        f.delay.put("二手摩托车 价格", 400L);
        f.delay.put("二手摩托车 行情 2026", 400L);
        long t0 = System.currentTimeMillis();
        ParallelWeb.Batch b = ParallelWeb.start(calls(new String[]{WebTools.SIGN_SEARCH, "二手摩托车 价格", "5"},
                new String[]{WebTools.SIGN_SEARCH, "二手摩托车 行情 2026", "5"}), f, t0);
        assertNotNull(b);
        ParallelWeb.Done a = ParallelWeb.take(b, WebTools.SIGN_SEARCH, "二手摩托车 价格");
        ParallelWeb.Done c = ParallelWeb.take(b, WebTools.SIGN_SEARCH, "二手摩托车 行情 2026");
        long el = System.currentTimeMillis() - t0;
        assertTrue(a.ok && c.ok);
        assertTrue("并行：两次 400 ms 共 < 700 ms，实际 " + el, el < 700L);
        assertEquals(2, f.maxInFlight);
    }

    @Test public void concurrencyCappedAtThree() {
        SlowFetch f = new SlowFetch();
        List<String[]> cs = new ArrayList<String[]>();
        for (int i = 0; i < 5; i++) cs.add(new String[]{WebTools.SIGN_SEARCH, "q" + i, "5"});
        ParallelWeb.Batch b = ParallelWeb.start(cs, f, System.currentTimeMillis());
        for (int i = 0; i < 5; i++) assertTrue(ParallelWeb.take(b, WebTools.SIGN_SEARCH, "q" + i).ok);
        assertTrue("并发 ≤ 3，实际 " + f.maxInFlight, f.maxInFlight <= ParallelWeb.MAX_CONCURRENCY);
    }

    @Test public void enoughTwoSitesDoesNotWaitForSlowPage() {
        SlowFetch f = new SlowFetch();
        f.delay.put("https://slow.example.com/a", 6000L);
        f.delay.put("https://www.smzdm.com/p/1", 100L);
        f.delay.put("https://www.58moto.com/x", 150L);
        long t0 = System.currentTimeMillis();
        ParallelWeb.Batch b = ParallelWeb.start(calls(new String[]{WebTools.SIGN_READ, "https://slow.example.com/a"},
                new String[]{WebTools.SIGN_READ, "https://www.smzdm.com/p/1"},
                new String[]{WebTools.SIGN_READ, "https://www.58moto.com/x"}), f, t0);
        ParallelWeb.Done slow = ParallelWeb.take(b, WebTools.SIGN_READ, "https://slow.example.com/a");
        long el = System.currentTimeMillis() - t0;
        assertTrue("慢页如实标超时", ParallelWeb.timedOut(slow));
        assertFalse(slow.ok);
        assertTrue("够 2 个站点后最多再等 1.5 s：实际 " + el, el < 150L + ParallelWeb.ENOUGH_GRACE_MS + 600L);
        assertTrue(ParallelWeb.take(b, WebTools.SIGN_READ, "https://www.smzdm.com/p/1").ok);
        assertTrue(ParallelWeb.take(b, WebTools.SIGN_READ, "https://www.58moto.com/x").ok);
    }

    @Test public void singleSiteWaitsUpToOwnCap() {
        ParallelWeb.Batch b = new ParallelWeb.Batch(0L);
        ParallelWeb.Entry e = new ParallelWeb.Entry("k", WebTools.SIGN_READ, "https://a.com/1", ParallelWeb.READ_CAP_MS);
        b.entries.add(e);
        assertEquals("没够 2 个站点：等到自身上限", ParallelWeb.READ_CAP_MS - 1000L, ParallelWeb.waitBudget(b, e, 1000L));
        ParallelWeb.Entry s = new ParallelWeb.Entry("s", WebTools.SIGN_SEARCH, "q", ParallelWeb.SEARCH_CAP_MS);
        assertEquals(0L, ParallelWeb.waitBudget(b, s, ParallelWeb.SEARCH_CAP_MS + 5L));
    }

    @Test public void lessThanTwoParallelizableCallsStaysSerial() throws Exception {
        SlowFetch f = new SlowFetch();
        assertNull(ParallelWeb.start(calls(new String[]{WebTools.SIGN_SEARCH, "只有一个", "5"}), f, 0L));
        assertNull("同参数重复不算两个", ParallelWeb.start(calls(new String[]{WebTools.SIGN_SEARCH, "a", "5"},
                new String[]{WebTools.SIGN_SEARCH, "a", "5"}), f, 0L));
        assertNull("翻页续读不并行", ParallelWeb.targetOf(WebTools.SIGN_READ, new JSONObject("{\"url\":\"https://a.com\",\"offset\":3000}")));
        assertNull("research.spawn 不并行", ParallelWeb.targetOf(WebTools.SIGN_RESEARCH, new JSONObject("{\"question\":\"x\"}")));
        assertNull("批里没有 → 走原路径", ParallelWeb.take(null, WebTools.SIGN_SEARCH, "x"));
    }

    @Test public void hostGroupsSubdomains() {
        assertEquals("smzdm.com", ParallelWeb.host("https://post.smzdm.com/p/al376d90"));
        assertEquals("smzdm.com", ParallelWeb.host("https://www.smzdm.com/"));
        assertEquals("58moto.com", ParallelWeb.host("https://m.58moto.com/x"));
    }
}
