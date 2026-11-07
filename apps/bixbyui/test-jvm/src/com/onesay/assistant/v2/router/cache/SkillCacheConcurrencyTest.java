package com.onesay.assistant.v2.router.cache;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 审计 B-4：前台唤醒的 AgentLoop 与 S3 到点的无人值守 AgentLoop 共用同一个 UnifiedRouter 单例里的 L1 缓存。
 * 两个循环同时写缓存 / 查缓存 / 纠错时：不抛异常、不串条目（A 句不会命中 B 句的能力）、计数一致。
 * （JVM 用 InMemorySkillStore；真机的 SqliteSkillStore 由 SkillCache 自身的 synchronized 串行化，同一把锁。）
 */
public class SkillCacheConcurrencyTest {

    static final SkillCache.Outcome OK_VERIFIED = SkillCache.Outcome.success(true);

    private static SkillCache newCache() {
        SkillCacheTest.FakeOracle oracle = new SkillCacheTest.FakeOracle()
                .add("connectivity.bt.on", 1).add("connectivity.wifi.off", 1)
                .add("battery.saver.on", 1).add("settings.rotation.off", 1)
                .add("settings.brightness.set", 1);
        return new SkillCache(new InMemorySkillStore(), "e1", oracle, new SkillCacheTest.FakeClock());
    }

    private static JSONObject j(String s) {
        try { return new JSONObject(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Thread loop(final SkillCache c, final CountDownLatch go, final List<Throwable> errs,
                               final String utt, final String sign, final String other, final String otherSign) {
        return new Thread(new Runnable() {
            @Override public void run() {
                try {
                    go.await();
                    for (int i = 0; i < 3000; i++) {
                        c.recordSuccess(utt, "DO_ACT", sign, j("{}"), OK_VERIFIED, false);
                        SkillCache.Hit h = c.lookup(utt, "DO_ACT");
                        if (h != null && !sign.equals(h.sign)) throw new AssertionError(utt + " 命中了 " + h.sign);
                        SkillCache.Hit o = c.peekHit(other, "DO_ACT");
                        if (o != null && !otherSign.equals(o.sign)) throw new AssertionError(other + " 命中了 " + o.sign);
                        c.peek(utt, "DO_ACT");
                    }
                } catch (Throwable t) {
                    errs.add(t);
                }
            }
        });
    }

    @Test public void foregroundAndUnattendedLoopsWriteConcurrently() throws Exception {
        final SkillCache c = newCache();
        final CountDownLatch go = new CountDownLatch(1);
        final List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread fg = loop(c, go, errs, "开蓝牙", "connectivity.bt.on", "开省电", "battery.saver.on");
        Thread bg = loop(c, go, errs, "开省电", "battery.saver.on", "开蓝牙", "connectivity.bt.on");
        Thread bg2 = loop(c, go, errs, "关掉无线网", "connectivity.wifi.off", "开蓝牙", "connectivity.bt.on");
        fg.start(); bg.start(); bg2.start();
        go.countDown();
        fg.join(); bg.join(); bg2.join();
        if (!errs.isEmpty()) throw new AssertionError("并发出错：" + errs.get(0), errs.get(0));
        assertEquals("connectivity.bt.on", c.lookup("开蓝牙", "DO_ACT").sign);
        assertEquals("battery.saver.on", c.lookup("开省电", "DO_ACT").sign);
        assertEquals("connectivity.wifi.off", c.lookup("关掉无线网", "DO_ACT").sign);
    }

    /** 一边写一边纠错 / 清除：不抛异常，清除后不再命中。 */
    @Test public void concurrentWriteAndClear() throws Exception {
        final SkillCache c = newCache();
        final CountDownLatch go = new CountDownLatch(1);
        final List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread w = loop(c, go, errs, "开蓝牙", "connectivity.bt.on", "开省电", "battery.saver.on");
        Thread clr = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    go.await();
                    for (int i = 0; i < 3000; i++) {
                        c.clearPositive("开蓝牙");
                        c.recordRefusal("开省电", "DO_ACT", "测试", 1000);
                        c.clearNegative("开省电");
                    }
                } catch (Throwable t) {
                    errs.add(t);
                }
            }
        });
        w.start(); clr.start();
        go.countDown();
        w.join(); clr.join();
        if (!errs.isEmpty()) throw new AssertionError("并发出错：" + errs.get(0), errs.get(0));
        c.clearPositive("开蓝牙");
        assertNull(c.lookup("开蓝牙", "DO_ACT"));
    }
}
