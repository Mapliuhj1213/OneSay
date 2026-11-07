/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

/** 给用户的一句提示（岛 / 面板 / 通知，由宿主决定落在哪）。 */
public interface Notifier {
    void notice(String title, String text);

    Notifier NONE = new Notifier() {
        @Override public void notice(String title, String text) {
        }
    };
}
