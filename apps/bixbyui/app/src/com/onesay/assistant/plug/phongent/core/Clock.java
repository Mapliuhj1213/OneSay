/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 时钟：elapsed = 单调时间（超时 / 限速用），wall = 墙钟毫秒（显示用）。 */
public interface Clock {
    long elapsed();

    long wall();

    Clock SYSTEM = new Clock() {
        @Override public long elapsed() {
            return System.nanoTime() / 1000000L;
        }

        @Override public long wall() {
            return System.currentTimeMillis();
        }
    };
}
