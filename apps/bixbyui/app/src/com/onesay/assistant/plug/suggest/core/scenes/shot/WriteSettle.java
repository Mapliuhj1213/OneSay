/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import com.onesay.assistant.plug.suggest.core.Ports;

/**
 * 「写完再读」（§6.3-5，A 源）：size &gt; 0 且隔 250 ms 不变才算写完，最多等 3 s。三星滚动截图会多次改写，以最后一次稳定为准。
 * 用宿主的调度器轮询，单测用假调度器推进。
 */
public final class WriteSettle {
    public static final long STEP_MS = 250L;
    public static final long MAX_MS = 3000L;

    public interface Callback {
        void settled(long size);

        void gaveUp(String reason);
    }

    private WriteSettle() {
    }

    public static void await(final String path, final Ports.FileProbe files, final Ports.Sched sched, final Callback cb) {
        await(path, files, sched, cb, STEP_MS, 1, MAX_MS);
    }

    /** ★ fix/shot-1104 B 源（inotify 只报「新建 n」、不报写完）：每 150 ms 读一次大小，连续 2 次不变且 &gt; 0 才算写完。 */
    public static final long B_STEP_MS = 150L;
    public static final int B_STABLE = 2;

    public static void awaitFast(String path, Ports.FileProbe files, Ports.Sched sched, Callback cb) {
        await(path, files, sched, cb, B_STEP_MS, B_STABLE, MAX_MS);
    }

    /**
     * @param stepMs 两次读大小的间隔
     * @param stable 要连续多少次「大小 &gt; 0 且与上次相同」才算写完（1 = 原 A 源口径）
     * @param maxMs  最多等多久（超了 gaveUp：empty / unstable）
     */
    public static void await(String path, Ports.FileProbe files, Ports.Sched sched, Callback cb, long stepMs, int stable, long maxMs) {
        step(path, files, sched, cb, -1L, 0L, 0, Math.max(1L, stepMs), Math.max(1, stable), maxMs);
    }

    private static void step(final String path, final Ports.FileProbe files, final Ports.Sched sched, final Callback cb,
                             final long prev, final long waited, int same, final long stepMs, final int stable, final long maxMs) {
        long sz;
        try {
            sz = files.size(path);
        } catch (Throwable t) {
            sz = -1;
        }
        if (sz < 0 && prev >= 0) {   // 等的时候被删了
            cb.gaveUp("gone");
            return;
        }
        final int run = (sz > 0 && sz == prev) ? same + 1 : 0;
        if (run >= stable) {
            cb.settled(sz);
            return;
        }
        if (waited >= maxMs) {
            cb.gaveUp(sz <= 0 ? "empty" : "unstable");
            return;
        }
        final long cur = sz;
        sched.schedule(new Runnable() {
            @Override public void run() {
                step(path, files, sched, cb, cur, waited + stepMs, run, stepMs, stable, maxMs);
            }
        }, stepMs);
    }
}
