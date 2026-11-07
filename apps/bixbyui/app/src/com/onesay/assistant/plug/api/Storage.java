/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

import java.util.Map;

/** 键值持久化（值是文本，core 存 JSON）。put / remove 返回 true = 已落盘。签名与 phongent 路的 Storage 一致。 */
public interface Storage {
    Map<String, String> all();

    boolean put(String key, String value);

    boolean remove(String key);
}
