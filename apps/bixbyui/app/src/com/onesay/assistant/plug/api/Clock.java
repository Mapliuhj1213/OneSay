/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

/** 时钟：elapsed = 单调毫秒（超时 / 间隔），wall = 墙钟毫秒（显示 / 落盘）。签名与 phongent 路的 Clock 一致。 */
public interface Clock {
    long elapsed();

    long wall();

    /** 阻塞睡眠（测试实现可以只推进假时钟）。 */
    void sleep(long ms);

    Clock SYSTEM = new Clock() {
        @Override public long elapsed() {
            return System.nanoTime() / 1000000L;
        }

        @Override public long wall() {
            return System.currentTimeMillis();
        }

        @Override public void sleep(long ms) {
            if (ms <= 0) return;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };
}
