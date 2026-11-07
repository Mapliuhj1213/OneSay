package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 审计 B-1：分诊 5 秒复用缓存的句子与结果必须成对发布。 */
public class ReuseSlotTest {

    @Test public void hitSameKeyWithinTtl() {
        ReuseSlot<String> s = new ReuseSlot<String>(5000);
        s.put("打开微信", "DO_ACT", 1000);
        assertEquals("DO_ACT", s.get("打开微信", 1000));
        assertEquals("DO_ACT", s.get("打开微信", 5999));
    }

    @Test public void missOnOtherKeyExpiryOrClockBack() {
        ReuseSlot<String> s = new ReuseSlot<String>(5000);
        assertNull(s.get("x", 0));
        s.put("打开微信", "DO_ACT", 1000);
        assertNull(s.get("今天天气", 1001));
        assertNull("到期", s.get("打开微信", 6000));
        assertNull("时钟回拨不复用", s.get("打开微信", 999));
        assertNull(s.get(null, 1001));
        s.clear();
        assertNull(s.get("打开微信", 1001));
    }

    /** 两个线程交替分诊不同句子，读线程任何时刻拿到的结果都必须属于它问的那句话。 */
    @Test public void concurrentWritersNeverMixQueryAndResult() throws Exception {
        final ReuseSlot<String> s = new ReuseSlot<String>(60000);
        final int N = 20000;
        final AtomicInteger mismatches = new AtomicInteger();
        final AtomicInteger hits = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        Thread[] ts = new Thread[4];
        for (int w = 0; w < ts.length; w++) {
            final String key = "句子" + w;
            ts[w] = new Thread(new Runnable() {
                @Override public void run() {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    for (int i = 0; i < N; i++) {
                        s.put(key, "结果:" + key, 1);
                        for (int k = 0; k < 4; k++) {
                            String q = "句子" + k;
                            String v = s.get(q, 2);
                            if (v != null) {
                                hits.incrementAndGet();
                                if (!v.equals("结果:" + q)) mismatches.incrementAndGet();
                            }
                        }
                    }
                }
            });
            ts[w].start();
        }
        start.countDown();
        for (Thread t : ts) t.join();
        assertTrue("应有命中", hits.get() > 0);
        assertEquals("句子与结果错配次数", 0, mismatches.get());
    }

    /** 接线：UnifiedRouter 不再用两个独立 volatile 字段。 */
    @Test public void routerUsesSingleSlot() {
        String ur = com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertFalse(ur.contains("mLastTriageQuery"));
        assertTrue(ur.contains("ReuseSlot<Triage> mLastTriage"));
        assertTrue(ur.contains("mLastTriage.get(query"));
        assertTrue(ur.contains("mLastTriage.put(query"));
    }
}
