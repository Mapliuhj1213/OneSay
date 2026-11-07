/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

/** 模型：一次无工具的文本补全。返回 null = 不可用 / 出错 / 超时（插件据此回落，不抛）。 */
public interface ModelPort {
    String complete(String system, String user, int maxTokens, int timeoutMs);
}
