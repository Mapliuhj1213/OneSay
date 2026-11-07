/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

/** 开关来源（宿主：SharedPreferences + kill 文件 + 调试覆盖）。def = 规格里的出厂默认。 */
public interface PlugSwitches {
    boolean on(String id, boolean def);

    /** 全按出厂默认（JVM 单测 / 无宿主）。 */
    PlugSwitches DEFAULTS = new PlugSwitches() {
        @Override public boolean on(String id, boolean def) {
            return def;
        }
    };
}
