package com.onesay.assistant.v2.s1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * ★ arch/island-progress（C 路）：ask_user 的等待 / 回填中转 —— 测试桩驱动「B 路等答案、岛 / 面板回填、60 s 超时 = no_answer」。
 */
public class AskUserBrokerTest {

    @Test public void answerWakesWaiter() throws Exception {
        final AskUserBroker b = new AskUserBroker();
        b.register("q1");
        assertTrue(b.isPending("q1"));
        final AtomicReference<String> got = new AtomicReference<String>("unset");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    got.set(b.await("q1", 5000L));
                } catch (InterruptedException e) {
                    got.set("interrupted");
                }
            }
        });
        t.start();
        Thread.sleep(50L);
        assertTrue("岛上点了芯片 → 回填成功", b.answer("q1", "西贝"));
        t.join(2000L);
        assertEquals("西贝", got.get());
        assertFalse("同一个问题只收一次", b.answer("q1", "海底捞"));
        assertEquals("取走后删除", 0, b.size());
    }

    @Test public void answerBeforeAwaitIsKept() throws Exception {
        AskUserBroker b = new AskUserBroker();
        b.register("q2");
        assertTrue(b.answer("q2", "个人"));
        assertEquals("先点后等不丢答案", "个人", b.await("q2", 10L));
    }

    @Test public void expireGivesNoAnswer() throws Exception {
        final AskUserBroker b = new AskUserBroker();
        b.register("q3");
        final AtomicReference<String> got = new AtomicReference<String>("unset");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    got.set(b.await("q3", 5000L));
                } catch (InterruptedException e) {
                    got.set("interrupted");
                }
            }
        });
        t.start();
        Thread.sleep(50L);
        long t0 = System.currentTimeMillis();
        assertTrue("岛上 60 s 超时收起 → 过期", b.expire("q3"));
        t.join(2000L);
        assertNull("no_answer", got.get());
        assertTrue("立刻放行，不等到兜底超时", System.currentTimeMillis() - t0 < 1500L);
        assertFalse("过期后回填无效", b.answer("q3", "x"));
    }

    @Test public void localTimeoutAndUnknownIdReturnNull() throws Exception {
        AskUserBroker b = new AskUserBroker();
        b.register("q4");
        assertNull("兜底超时 = no_answer", b.await("q4", 30L));
        assertFalse("超时后回填无效", b.answer("q4", "x"));
        assertNull("没登记", b.await("nope", 10L));
        assertFalse(b.answer(null, "x"));
        assertFalse(b.answer("nope", "x"));
        assertFalse(b.expire("nope"));
    }

    @Test public void reRegisterReleasesOldAndExpireAll() throws Exception {
        AskUserBroker b = new AskUserBroker();
        b.register("a");
        b.register("b");
        assertEquals(2, b.expireAll());
        assertEquals(0, b.size());
        assertFalse(b.isPending("a"));
        b.register("c");
        assertNull("还在等 → take 不取", b.take("c"));
        b.answer("c", "ok");
        assertEquals("ok", b.take("c"));
        assertEquals(0, b.size());
    }

    @Test public void hooksContractDefaults() {
        assertEquals("用户默认决策：60 秒", 60, S1Hooks.ASK_DEFAULT_TIMEOUT_SEC);
        // 服务未初始化（没有岛）：问题仍登记、返回 false（调用方改用面板 / 语音问），回填照样能到
        assertFalse(S1Hooks.onAskUser("hq", "?", java.util.Arrays.asList("a", "b")));
        assertTrue(S1Hooks.askBroker().isPending("hq"));
        assertTrue(S1Hooks.answerAskUser("hq", "b"));
        assertEquals("b", S1Hooks.awaitAskUser("hq", 10L));
        assertFalse(S1Hooks.onAskUser(null, "?", java.util.Arrays.asList("a", "b")));
        S1Hooks.onAskUser("hq2", "?", java.util.Arrays.asList("a", "b"), 0);
        S1Hooks.cancelAskUser("hq2");
        assertNull(S1Hooks.awaitAskUser("hq2", 10L));
        S1Hooks.onSubgoals(null);   // 没有岛：不抛
    }
}
