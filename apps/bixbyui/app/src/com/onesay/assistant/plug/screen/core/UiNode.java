/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin to Java 7; the AccessibilityNodeInfo view is replaced by this
 * backend-neutral snapshot node so the same reader serves the UiAutomation helper (B1) and an
 * AccessibilityService (B2) on Android 8 single-display devices. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 后端无关的一个控件节点（读屏后端把 AccessibilityNodeInfo 抄成它，核心逻辑只看它）。像素坐标。
 * {@link #src} 是后端自己的句柄（B1 = 助手树里的下标；B2 = 节点缓存键），核心不解释它，只原样带到元素上，
 * 动作层据此找回节点。
 */
public final class UiNode {
    public String cls = "";
    public String text = "";
    public String desc = "";
    /** hintText（API 26 起有）。 */
    public String hint = "";
    public String rid = "";
    public int l, t, r, b;
    public boolean visible = true, clickable, longClickable, editable, scrollable, checkable, checked, selected,
            enabled = true, focused, password, showingHint, collection;
    public int textSelStart = -1, textSelEnd = -1;
    public int src = -1;
    public UiNode parent;
    public final List<UiNode> children = new ArrayList<UiNode>();

    public UiNode add(UiNode c) {
        c.parent = this;
        children.add(c);
        return this;
    }

    public int width() {
        return r - l;
    }

    public int height() {
        return b - t;
    }

    public int cx() {
        return (l + r) / 2;
    }

    public int cy() {
        return (t + b) / 2;
    }

    public String role() {
        String c = cls == null ? "" : cls;
        int i = c.lastIndexOf('.');
        String s = i >= 0 ? c.substring(i + 1) : c;
        return s.length() == 0 ? "View" : s;
    }

    /** 列表类容器：子项会复用（Mika isList）。 */
    public boolean isList() {
        if (collection) return true;
        String c = cls == null ? "" : cls;
        return c.endsWith("RecyclerView") || c.endsWith("ListView") || c.endsWith("GridView");
    }

    public boolean inList() {
        UiNode p = parent;
        int d = 0;
        while (p != null && d++ < ScreenReader.MAX_DEPTH) {
            if (p.isList()) return true;
            p = p.parent;
        }
        return false;
    }

    /** 测试 / 适配器用的构造器。 */
    public static UiNode of(String cls, String text, int l, int t, int r, int b) {
        UiNode n = new UiNode();
        n.cls = cls == null ? "" : cls;
        n.text = text == null ? "" : text;
        n.l = l;
        n.t = t;
        n.r = r;
        n.b = b;
        return n;
    }
}
