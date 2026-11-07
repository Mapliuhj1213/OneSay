/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: the tap / scroll fallback order of app/cap/phone/a11y/AndroidScreen.kt (click L1316,
 * touch L1326, scroll L362) rewritten in Java 7 against a narrow ActionPort, so the UiAutomation helper (B1)
 * and an AccessibilityService (B2) share it. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.action.core;

/**
 * 动作降级链（纯逻辑）：
 * <ul>
 *   <li>点元素：① 节点或最近的「可点且可用」祖先（≤ 8 层）ACTION_CLICK → ② 节点范围内手势（B2 dispatchGesture；B1 不支持就跳过）
 *       → ③ 中心点注入触摸（宿主负责护罩开孔）；</li>
 *   <li>滚动：① ref 的可滚祖先 SCROLL_FORWARD/BACKWARD → ② 屏中线滑动；</li>
 *   <li>坐标点：① 手势 → ② 注入。</li>
 * </ul>
 * 每一级失败（false）才往下走；返回用了哪一级，供结果和统计。
 */
public final class ActionChain {
    private ActionChain() {
    }

    public static final int MAX_ANCESTOR = 8;

    /** 后端动作原语（B1 / B2 各实现一份）。所有方法阻塞；不支持的返回 false。bounds 为像素 [l,t,r,b]。 */
    public interface ActionPort {
        /** 节点自身或 ≤ maxAncestor 层内可点可用祖先 ACTION_CLICK。src = 读屏时的后端句柄，bounds/cls 用于核对节点没换。 */
        boolean clickNode(int src, int[] bounds, String cls, int maxAncestor);

        /** 在 (x,y) 发一次手势点按（B2 dispatchGesture）。 */
        boolean gestureTap(int x, int y);

        /** 注入触摸点按（B1 root 注入）。 */
        boolean injectTap(int x, int y);

        /** 节点或可滚祖先做 SCROLL_FORWARD(dir>0)/BACKWARD；返回 null = 没有可滚节点，"edge" = 到头，其余 = 滚了。 */
        String scrollNode(int src, int[] bounds, int dir);

        boolean swipe(int x1, int y1, int x2, int y2, int ms);
    }

    public static final String A11Y = "a11y_click", GESTURE = "gesture", INJECT = "inject", NONE = "none";
    public static final String SCROLLED = "scrolled", EDGE = "edge", SWIPED = "swiped";

    /** 点元素；返回用了哪一级（NONE = 全部失败）。 */
    public static String tap(ActionPort p, int src, int[] bounds, String cls) {
        if (bounds == null || bounds.length != 4) return NONE;
        int cx = (bounds[0] + bounds[2]) / 2, cy = (bounds[1] + bounds[3]) / 2;
        if (p.clickNode(src, bounds, cls, MAX_ANCESTOR)) return A11Y;
        if (p.gestureTap(cx, cy)) return GESTURE;
        if (p.injectTap(cx, cy)) return INJECT;
        return NONE;
    }

    public static String tapXY(ActionPort p, int x, int y) {
        if (p.gestureTap(x, y)) return GESTURE;
        if (p.injectTap(x, y)) return INJECT;
        return NONE;
    }

    /** 滚动；dir > 0 往下看更多。screenW/H 用于整屏滑。 */
    public static String scroll(ActionPort p, int src, int[] bounds, int dir, int screenW, int screenH) {
        if (bounds != null || src >= 0) {
            String r = p.scrollNode(src, bounds, dir);
            if (r != null) return EDGE.equals(r) ? EDGE : SCROLLED;
        }
        int x = bounds != null && bounds.length == 4 ? (bounds[0] + bounds[2]) / 2 : screenW / 2;
        boolean ok = dir > 0 ? p.swipe(x, screenH * 3 / 4, x, screenH / 4, 350) : p.swipe(x, screenH / 4, x, screenH * 3 / 4, 350);
        return ok ? SWIPED : NONE;
    }
}
