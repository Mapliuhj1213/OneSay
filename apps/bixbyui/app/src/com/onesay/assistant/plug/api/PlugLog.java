/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

/** 日志。core 只写不含正文 / 屏幕文字 / 凭据的短句。签名与 phongent 路的 PlugLog 一致。 */
public interface PlugLog {
    void i(String msg);

    void w(String msg);

    PlugLog NONE = new PlugLog() {
        @Override public void i(String msg) {
        }

        @Override public void w(String msg) {
        }
    };
}
