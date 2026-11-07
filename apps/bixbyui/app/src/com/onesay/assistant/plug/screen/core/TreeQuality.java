/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/TreeQuality.kt) to Java 7; OCR branch removed —
 * an "uncertain" verdict counts as adequate, and only partial / unusable ask the host for its screencap
 * fallback; the blind-area walk is done once by ScreenReader. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

/** 这一屏的无障碍树够不够用。 */
public final class TreeQuality {
    private TreeQuality() {
    }

    public static final String ADEQUATE = "adequate", PARTIAL = "partial", UNUSABLE = "unusable", PROTECTED = "protected";
    static final int ENOUGH_NAMED = 6;

    /** {quality, reason}；reason 可为 null。 */
    public static String[] of(ScreenSnapshot s) {
        if (s.protectedPage) return new String[]{PROTECTED, null};
        if (!s.hasWindow) return s.elements.isEmpty() ? new String[]{UNUSABLE, "unreadable_window"} : new String[]{ADEQUATE, null};
        if (s.elements.isEmpty()) return new String[]{UNUSABLE, "empty_elements"};
        int named = 0, controls = 0, namedControls = 0;
        for (UiElement e : s.elements) {
            boolean hasName = e.name.trim().length() > 0;
            if (hasName) named++;
            if (e.clickable || e.editable) {
                controls++;
                if (hasName || (e.placeholder != null && e.placeholder.trim().length() > 0)) namedControls++;
            }
        }
        if (controls > 0 && (namedControls == 0 || (controls >= 4 && namedControls * 3 <= controls)))
            return new String[]{named == 0 ? UNUSABLE : PARTIAL, "unlabeled_controls"};
        if (named == 0) return new String[]{UNUSABLE, "no_text"};
        // Mika 在这里拿不准（named < 6 或有盲区）时读 OCR；OneSay 不做 OCR：有盲区按 partial（交给宿主截图兜底），少字页面按够用
        if (s.blind) return new String[]{PARTIAL, "blind_area"};
        return new String[]{ADEQUATE, null};
    }

    public static boolean poor(String q) {
        return PARTIAL.equals(q) || UNUSABLE.equals(q);
    }
}
