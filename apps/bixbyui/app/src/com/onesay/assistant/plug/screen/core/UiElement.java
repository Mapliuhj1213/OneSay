/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/ScreenPort.kt UiElement / UiStates / normalize)
 * to Java 7; added inList / src for the Android 8 backends. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 屏幕上的一个元素。ref 形如 "@e3"；center 为像素（端口内部用，交给模型前换成 0–999）；bounds 为 [l,t,r,b]，已归一到 0–999。
 * hint：输入框里显示的是提示语 / 预置词（name 就是它），不是已经输入的内容。secret：密码框。
 */
public final class UiElement {
    public static final String CHECKED = "checked", SELECTED = "selected", DISABLED = "disabled";

    public final String ref, role, name;
    public final int centerX, centerY;
    public final int[] bounds;
    public final String resourceId;
    public final boolean clickable, editable, scrollable, hint, focused, secret, searchField, inList;
    public final String placeholder;
    public final Set<String> states;
    /** 后端句柄（见 {@link UiNode#src}）。 */
    public final int src;

    public UiElement(String ref, String role, String name, int centerX, int centerY, int[] bounds, String resourceId,
                     boolean clickable, boolean editable, boolean scrollable, boolean hint, boolean focused, boolean secret,
                     String placeholder, boolean searchField, Set<String> states, boolean inList, int src) {
        this.ref = ref;
        this.role = role == null ? "View" : role;
        this.name = name == null ? "" : name;
        this.centerX = centerX;
        this.centerY = centerY;
        this.bounds = bounds == null ? new int[0] : bounds;
        this.resourceId = resourceId;
        this.clickable = clickable;
        this.editable = editable;
        this.scrollable = scrollable;
        this.hint = hint;
        this.focused = focused;
        this.secret = secret;
        this.placeholder = placeholder;
        this.searchField = searchField;
        this.states = states == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new TreeSet<String>(states));
        this.inList = inList;
        this.src = src;
    }

    /** 换编号（RefStabilizer 用）。 */
    public UiElement withRef(String newRef) {
        return new UiElement(newRef, role, name, centerX, centerY, bounds, resourceId, clickable, editable, scrollable, hint, focused,
                secret, placeholder, searchField, states, inList, src);
    }

    /** 无名字且不可交互的节点对模型没有信息量。 */
    public boolean meaningful() {
        return name.trim().length() > 0 || clickable || editable || scrollable;
    }

    public int refNo() {
        return refNo(ref);
    }

    public static int refNo(String ref) {
        if (ref == null) return -1;
        String s = ref.trim();
        if (s.startsWith("@e")) s = s.substring(2);
        else if (s.startsWith("e")) s = s.substring(1);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 与 tap 的换算互逆：n = round(p*999/(size-1))，夹在 0–999。 */
    public static int normalize(int p, int size) {
        if (size <= 1) return 0;
        long v = Math.round(p * 999.0 / (size - 1));
        return (int) Math.min(999, Math.max(0, v));
    }

    /** 0–999 → 像素（normalize 的逆）。 */
    public static int denormalize(int n, int size) {
        if (size <= 1) return 0;
        int v = (int) Math.round(Math.max(0, Math.min(999, n)) * (size - 1) / 999.0);
        return Math.max(0, Math.min(size - 1, v));
    }
}
