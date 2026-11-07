package com.onesay.assistant.v2.s1;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ arch/island-progress（C 路）：ask_user 的<b>等待 / 回填</b>中转（纯 Java，线程安全，JVM 可测）。
 *
 * <p>B 路（AgentLoop 的 ask_user 工具）：{@code S1Hooks.onAskUser(id, …)} 登记并上岛 →
 * {@code S1Hooks.awaitAskUser(id, ms)} 阻塞等答案（null = no_answer）。<br>
 * 回填：岛上点芯片 / 面板 / 语音 → {@code S1Hooks.answerAskUser(id, choice)}；岛上 60 s 超时收起 → {@link #expire}。
 *
 * <p>同一个 id 只回填一次（先到先得）；回填 / 过期之后的再次回填返回 false。
 * 已回填但还没人来取的答案保留到 {@link #await} / {@link #take} 取走（避免「先点后等」丢答案）。
 */
public final class AskUserBroker {

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean done;
        volatile String choice;   // null = no_answer（过期 / 取消）
    }

    private final Map<String, Pending> mMap = new HashMap<String, Pending>();

    /** 登记一个待答问题（同 id 重复登记 → 覆盖旧的，旧的按 no_answer 放行）。 */
    public void register(String id) {
        if (id == null) return;
        Pending old;
        synchronized (mMap) {
            old = mMap.put(id, new Pending());
        }
        if (old != null && !old.done) {
            old.done = true;
            old.latch.countDown();
        }
    }

    public boolean isPending(String id) {
        synchronized (mMap) {
            Pending p = id == null ? null : mMap.get(id);
            return p != null && !p.done;
        }
    }

    /** 回填答案。true = 这个问题还在等、答案已收下。 */
    public boolean answer(String id, String choice) {
        if (id == null || choice == null) return false;
        Pending p;
        synchronized (mMap) {
            p = mMap.get(id);
            if (p == null || p.done) return false;
            p.choice = choice;
            p.done = true;
        }
        p.latch.countDown();
        return true;
    }

    /** 过期 / 取消：等待方拿到 null（no_answer）。true = 之前确实在等。 */
    public boolean expire(String id) {
        if (id == null) return false;
        Pending p;
        synchronized (mMap) {
            p = mMap.get(id);
            if (p == null || p.done) return false;
            p.choice = null;
            p.done = true;
        }
        p.latch.countDown();
        return true;
    }

    /** 全部按 no_answer 放行（服务销毁 / 任务结束）。返回放行了几个。 */
    public int expireAll() {
        java.util.List<Pending> l = new java.util.ArrayList<Pending>();
        synchronized (mMap) {
            for (Pending p : mMap.values()) {
                if (!p.done) {
                    p.choice = null;
                    p.done = true;
                    l.add(p);
                }
            }
            mMap.clear();
        }
        for (Pending p : l) p.latch.countDown();
        return l.size();
    }

    /**
     * 阻塞等答案（最多 timeoutMs）。返回选项文字；超时 / 过期 / 没登记 → null（no_answer）。取走后该 id 被删除。
     */
    public String await(String id, long timeoutMs) throws InterruptedException {
        Pending p;
        synchronized (mMap) {
            p = id == null ? null : mMap.get(id);
        }
        if (p == null) return null;
        boolean got = p.latch.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        synchronized (mMap) {
            if (mMap.get(id) == p) mMap.remove(id);
        }
        if (!got) {
            p.done = true;   // 本地超时：以后再回填一律不收
            return null;
        }
        return p.choice;
    }

    /** 不阻塞地取答案：还在等 → null 且保留；已结束 → 返回答案（或 null）并删除。 */
    public String take(String id) {
        synchronized (mMap) {
            Pending p = id == null ? null : mMap.get(id);
            if (p == null || !p.done) return null;
            mMap.remove(id);
            return p.choice;
        }
    }

    public int size() {
        synchronized (mMap) {
            return mMap.size();
        }
    }
}
