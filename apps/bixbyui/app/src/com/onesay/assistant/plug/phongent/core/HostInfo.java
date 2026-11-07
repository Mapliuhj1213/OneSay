/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 本机信息。 */
public interface HostInfo {
    String deviceName();

    /** 改名（主控 POST /device/name）。非法抛 IllegalArgumentException；返回生效的名字。 */
    String rename(String name);

    /** 宿主自己前台有任务在跑（不经本插件下发的）。 */
    boolean localBusy();

    /** 通知使用权有没有给。 */
    boolean notificationAccess();

    /** 通知监听此刻连着没有。 */
    boolean listenerConnected();
}
