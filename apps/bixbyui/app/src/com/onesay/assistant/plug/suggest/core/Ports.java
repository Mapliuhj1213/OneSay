/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/**
 * 宿主额外要实现的两个小端口（AICY-RESEARCH §5.3 之外、实现时补的）：
 * <ul>
 *   <li>{@link Sched}：延时任务（推迟 ≤10 s 重试、建议展示期间每秒查文件是否被删、写完判定）。</li>
 *   <li>{@link FileProbe}：只读文件元数据（存在 / 大小），截图场景用；core 不碰 java.io 以便假实现单测。</li>
 * </ul>
 */
public final class Ports {
    private Ports() {
    }

    /** 延时执行（宿主：HandlerThread / ScheduledExecutor；单测：手动推进的假调度器）。 */
    public interface Sched {
        /** 返回可取消句柄。 */
        Cancel schedule(Runnable r, long delayMs);
    }

    public interface Cancel {
        void cancel();
    }

    public interface FileProbe {
        boolean exists(String path);

        /** 不存在 / 读不到 = -1。 */
        long size(String path);
    }

    /** 立即执行的调度器（只用于没有宿主的场合：延时一律当 0）。 */
    public static final Sched IMMEDIATE = new Sched() {
        @Override public Cancel schedule(Runnable r, long delayMs) {
            r.run();
            return NOOP;
        }
    };

    /** 什么都不做的调度器（没有宿主时的默认：延时任务一律不跑，避免立即执行造成的轮询死循环）。 */
    public static final Sched NEVER = new Sched() {
        @Override public Cancel schedule(Runnable r, long delayMs) {
            return NOOP;
        }
    };

    public static final Cancel NOOP = new Cancel() {
        @Override public void cancel() {
        }
    };
}
