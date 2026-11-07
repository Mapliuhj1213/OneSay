/*
 * [mika.screen 插件] 部分移植自 Mika（UiWindowTree 的最小子集，Android 8 单屏；Apache-2.0，Copyright 2026 wangzan101，见 assets/LICENSE-APACHE-2.0.txt 与 NOTICE）。
 */
package com.onesay.assistant.plug.screen.core;

/** 一个窗口：type 用 Mika 的口径 application / system / other，外加 ime / overlay（读屏时跳过）。layer 大的在上。 */
public final class UiWindow {
    public static final String APPLICATION = "application", SYSTEM = "system", OTHER = "other", IME = "ime", OVERLAY = "overlay";

    public final int id;
    public final String type;
    public final int layer;
    public final String packageName;
    public final UiNode root;

    public UiWindow(int id, String type, int layer, String packageName, UiNode root) {
        this.id = id;
        this.type = type == null ? APPLICATION : type;
        this.layer = layer;
        this.packageName = packageName == null ? "" : packageName;
        this.root = root;
    }
}
