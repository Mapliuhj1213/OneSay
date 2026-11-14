package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 找茬 · 并发：同一张已批准令牌被 N 个线程同时拿去执行，只能有一个成功（一次性）；同一张确认卡（PendingConfirm）被
 * N 个线程同时点「允许」，只能有一个拿到令牌；一边批准一边拒绝，结局只能是「放行一次」或「拒绝」二选一。
 * 线程数 × 场景 × 轮次（轮次只换参数值，确定性，不用随机）。
 */
@RunWith(Parameterized.class)
public class AdvTokenRaceTest {

    @Parameterized.Parameters(name = "{index}: {0} n={1} round={2}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (String sc : new String[]{"authorize", "pendingAllow", "approveVsReject"})
            for (int n : new int[]{2, 3, 4, 8, 16})
                for (int round = 0; round < 4; round++) out.add(new Object[]{sc, n, round});
        return out;
    }

    private final String scenario;
    private final int n, round;

    public AdvTokenRaceTest(String scenario, int n, int round) {
        this.scenario = scenario;
        this.n = n;
        this.round = round;
    }

    private ExecutionGate.ConfirmToken issued;

    @After public void reset() {
        if (issued != null) ExecutionGate.reject(issued);   // 不在登记表里留令牌
        ExecutionGate.setStrictConfirm(true);
    }

    @Test public void race() throws Exception {
        final Capability cap = new Capability.Builder("adv.race." + scenario, "adv").risk(2, true).sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo").build();
        final JSONObject args = new JSONObject().put("round", round).put("n", n);
        final ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, false);
        assertTrue(d.needsConfirm());
        issued = d.token;
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger wins = new AtomicInteger(), rejects = new AtomicInteger();
        final PendingConfirm pc = new PendingConfirm();
        if (scenario.equals("authorize")) assertTrue(ExecutionGate.approve(d.token));
        if (scenario.equals("pendingAllow")) pc.hold(d, "q" + round);
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        go.await();
                        if (scenario.equals("authorize")) {
                            if (ExecutionGate.authorize(cap, new JSONObject(args.toString()), false, d.token) == null) wins.incrementAndGet();
                            ExecutionGate.takeConsumedReceipt();
                        } else if (scenario.equals("pendingAllow")) {
                            if (pc.allow().ok()) wins.incrementAndGet();
                        } else {
                            if (k % 2 == 0) {
                                if (ExecutionGate.approve(d.token)) wins.incrementAndGet();
                            } else {
                                ExecutionGate.reject(d.token);
                                rejects.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            ts.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join(10000);
        if (scenario.equals("authorize")) {
            assertEquals("一次性：只能执行一次", 1, wins.get());
            assertEquals(ExecutionGate.TokenState.CONSUMED, d.token.state());
        } else if (scenario.equals("pendingAllow")) {
            assertEquals("一张卡只能被允许一次", 1, wins.get());
            assertFalse(pc.isHolding());
        } else {
            assertTrue(wins.get() <= 1);
            assertTrue(rejects.get() >= 1);
            assertEquals("有拒绝就以拒绝收场", ExecutionGate.TokenState.REJECTED, d.token.state());
            assertNotNull("拒绝后执行不放行", ExecutionGate.authorize(cap, args, false, d.token));
        }
    }
}
