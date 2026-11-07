/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 运行期设置（每次用时现读，宿主改了立即生效）。总开关不在这里：总开关关 = 宿主根本不 start 插件。 */
public interface PlugConfig {
    /** 只接受 Tailscale 来源（默认 false：局域网 + Tailscale）。 */
    boolean tailscaleOnly();

    /** 接受回环来源（只给 JVM 单测 / 沙箱联调；生产必须 false）。 */
    boolean allowLoopback();

    /** 把白名单 App 的通知预览转给主控（默认 false）。 */
    boolean relayNotifications();

    /** 允许主控请求查看屏幕 / 接管（默认 false；开了每次还要本机同意）。 */
    boolean screenSharing();
}
