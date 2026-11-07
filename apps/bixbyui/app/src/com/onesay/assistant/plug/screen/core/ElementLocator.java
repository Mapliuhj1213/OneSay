/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/Accuracy.kt) to Java 7. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

/**
 * 用来在新界面里认出「同一个元素」的特征。context 是遍历顺序里它前面最近一个有名字的元素，
 * 防止滚动后别处的同名按钮顶上来被误认。hint：名字是输入框的提示语；inList：元素在列表容器里。
 */
public final class ElementLocator {
    public final String role, name, resourceId, context;
    public final int centerX, centerY;
    public final boolean hint, inList;

    public ElementLocator(String role, String name, String resourceId, int centerX, int centerY, String context, boolean hint, boolean inList) {
        this.role = role == null ? "" : role;
        this.name = name == null ? "" : name;
        this.resourceId = resourceId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.context = context;
        this.hint = hint;
        this.inList = inList;
    }

    boolean sameKey(ElementLocator o) {
        return role.equals(o.role) && name.equals(o.name) && eq(resourceId, o.resourceId);
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
