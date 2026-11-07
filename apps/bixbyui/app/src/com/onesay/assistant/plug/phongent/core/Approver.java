/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/**
 * 本机确认（宿主弹通知 / 对话框）。用户点了之后宿主回调 {@link PhongentPlug#decidePair} / {@link PhongentPlug#decideScreen}。
 * 安全约定：确认只能在本机做，宿主不得提供远程放行的路径。
 */
public interface Approver {
    /** 现在能不能弹（通知被关 = false，配对申请直接回 409）。 */
    boolean canPrompt();

    void askPair(String requestId, String controllerName);

    void dismissPair(String requestId);

    void askScreen(String controllerName);

    void dismissScreen();
}
