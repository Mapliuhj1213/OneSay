/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/ScreenPort.kt ScreenSnapshot, Accuracy.kt ElementLocator)
 * to Java 7; web / virtual-display fields removed (Android 8 single display). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一屏（端口编号）。changedDuringRead：读的过程中页面在动；hasWindow：屏上有 App 窗口；blind：App 窗口里有一大块
 * 无字区域（TreeAdequacy 用，读屏时一遍算出）；hash：界面指纹（动作前后比对）；locators 与 elements 一一对应。
 */
public final class ScreenSnapshot {
    public final String snapshotId;
    public final String packageName;
    public final List<UiElement> elements;
    public final List<ElementLocator> locators;
    public final int width, height;
    public boolean changedDuringRead, hasWindow = true, protectedPage, blind, truncated, ime;
    public long hash;
    public String activity = "";
    public int omittedByPrivacy;

    public ScreenSnapshot(String snapshotId, String packageName, List<UiElement> elements, List<ElementLocator> locators, int width, int height) {
        this.snapshotId = snapshotId;
        this.packageName = packageName;
        this.elements = elements == null ? Collections.<UiElement>emptyList() : Collections.unmodifiableList(new ArrayList<UiElement>(elements));
        List<ElementLocator> ls = locators;
        if (ls == null) {
            ls = new ArrayList<ElementLocator>();
            String last = null;
            for (UiElement e : this.elements) {
                ls.add(new ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, e.inList));
                if (e.name.trim().length() > 0) last = e.name;
            }
        }
        this.locators = Collections.unmodifiableList(new ArrayList<ElementLocator>(ls));
        this.width = width;
        this.height = height;
    }

    public UiElement find(String ref) {
        if (ref == null) return null;
        for (UiElement e : elements) if (e.ref.equals(ref)) return e;
        return null;
    }

    /** 复制一份，换 id 和元素（编号换成模型编号时用），其余事实照抄。 */
    public ScreenSnapshot copyWith(String id, List<UiElement> els) {
        ScreenSnapshot s = new ScreenSnapshot(id, packageName, els, locators.size() == els.size() ? locators : null, width, height);
        s.changedDuringRead = changedDuringRead;
        s.hasWindow = hasWindow;
        s.protectedPage = protectedPage;
        s.blind = blind;
        s.truncated = truncated;
        s.ime = ime;
        s.hash = hash;
        s.activity = activity;
        s.omittedByPrivacy = omittedByPrivacy;
        return s;
    }
}
