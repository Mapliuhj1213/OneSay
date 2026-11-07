package com.onesay.assistant.v2.capability;

import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置原子能力定义表。
 *
 * 迁移说明：
 * 1. 彻底淘汰 v1 mapping.json「直译 shell」模型，升维为「能力签名 + Schema 强校验」；
 * 2. 覆盖系统设置、网络连接、音频媒体、应用管理、时间时钟与高危安全控制六大域；
 * 3. 严格恪守安全铁律：高危破坏性动作（risk=2 且 confirm=true）不可旁路直接触发。
 *
 * 各领域能力已按领域机械拆分到 {@code capability/caps/*Caps}（内容逐字不变），
 * 本类只保留注册顺序（顺序影响 Top-K 同分时的先后，不可随意调整）。
 */
public class BuiltinCapabilities {

    public static void registerAll(CapabilityRegistry reg) {
        com.onesay.assistant.v2.capability.caps.SettingsCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.ConnectivityCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.VolumeCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.AppCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.ClockCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.SystemCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.VisionCaps.register(reg);
        com.onesay.assistant.v2.capability.caps.MailCaps.register(reg);
    }

}
