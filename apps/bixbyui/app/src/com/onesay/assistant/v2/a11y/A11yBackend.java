/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: app/cap/phone/a11y/ScreenAccessibility.kt reduced to an Android 8 (API 26) single-display
 * backend in Java 7 — getWindows() instead of windowsOnAllDisplays, no screenshots, no displayId, events are only
 * time-stamped (never copied or stored); the read produces the same FsTree as the UiAutomation helper so the
 * mika.screen core serves both backends. Upstream: ebb0e44.
 */
package com.onesay.assistant.v2.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ mika-plug（mika.a11y，B2 可选后端，默认不启用）：真正的 AccessibilityService。
 *
 * <ul>
 *   <li>只有用户在系统设置里手动打开才会绑定；mika.a11y 插件关着时什么都不做（读屏 / 动作仍走 UiAutomation 助手 B1）；</li>
 *   <li>读树：getWindows() 全部内容窗口（去输入法 / 无障碍浮层 / 分屏条 / 自家包），输出与助手同格式的 {@link FsTree}，
 *       并留住这一次读到的节点句柄（按下标）供点按 / 输入；</li>
 *   <li>动作：节点或 ≤ 8 层可点祖先 ACTION_CLICK、dispatchGesture 点按、performGlobalAction 返回 / 主页、ACTION_SET_TEXT；</li>
 *   <li>事件：onAccessibilityEvent 只记最后一次事件的时刻（ChangeWait 的「安静多久」），不复制、不缓存、不落盘。</li>
 * </ul>
 */
public class A11yBackend extends AccessibilityService {

    private static volatile A11yBackend sInstance;
    private static volatile long sLastEventAt;
    private final Object mLock = new Object();
    private List<AccessibilityNodeInfo> mNodes = new ArrayList<AccessibilityNodeInfo>();
    private String mSelf = "";

    public static A11yBackend instance() {
        return sInstance;
    }

    /** 服务连着。 */
    public static boolean connected() {
        return sInstance != null;
    }

    /** 距最近一次界面事件过了多久（没有事件记录时返回 Long.MAX_VALUE）。 */
    public static long quietForMs() {
        long t = sLastEventAt;
        return t == 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - t;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // 照 Mika：运行时再补一次 INCLUDE_NOT_IMPORTANT_VIEWS（不少 App 把结果文字标成对无障碍不重要，系统又可能沿用旧配置）
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable ignored) {
        }
        mSelf = getPackageName();
        sInstance = this;
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER || t == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) return;
        sLastEventAt = SystemClock.uptimeMillis();   // 只记时刻，不碰事件内容
    }

    @Override public void onInterrupt() {
    }

    // ------------------------------------------------------------------ 读树

    public FsTree readTree(int maxNodes) {
        long t0 = SystemClock.uptimeMillis();
        List<AccessibilityWindowInfo> ws;
        try {
            ws = new ArrayList<AccessibilityWindowInfo>(getWindows());
        } catch (Throwable e) {
            return null;
        }
        Collections.sort(ws, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        FsTree t = new FsTree();
        List<AccessibilityNodeInfo> handles = new ArrayList<AccessibilityNodeInfo>();
        for (AccessibilityWindowInfo w : ws) {
            int ty = w.getType();
            if (ty == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                t.ime = true;
                continue;
            }
            if (ty == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || ty == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) continue;
            AccessibilityNodeInfo r = w.getRoot();
            String pkg = r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
            if (pkg.equals(mSelf)) continue;
            FsTree.Win fw = new FsTree.Win();
            fw.id = w.getId();
            fw.layer = w.getLayer();
            fw.type = ty == AccessibilityWindowInfo.TYPE_APPLICATION ? "application" : ty == AccessibilityWindowInfo.TYPE_SYSTEM ? "system" : "other";
            fw.pkg = pkg;
            t.windows.add(fw);
            if (t.pkg.length() == 0 && ty == AccessibilityWindowInfo.TYPE_APPLICATION && pkg.length() > 0) t.pkg = pkg;
            if (r != null) walk(r, -1, 0, 0, t, handles, maxNodes, t0, t.windows.size() - 1);
        }
        synchronized (mLock) {
            mNodes = handles;
        }
        t.ms = SystemClock.uptimeMillis() - t0;
        return t.windows.isEmpty() ? null : t;
    }

    private static void walk(AccessibilityNodeInfo n, int parent, int childIdx, int depth, FsTree t, List<AccessibilityNodeInfo> handles,
                             int maxNodes, long t0, int win) {
        if (n == null) return;
        if (t.nodes.size() >= maxNodes || SystemClock.uptimeMillis() - t0 > 2500 || depth > 80) {
            t.truncated = true;
            return;
        }
        if (parent >= 0 && !n.isVisibleToUser()) return;
        FsTree.Node x = new FsTree.Node();
        x.parent = parent;
        x.childIdx = childIdx;
        CharSequence cn = n.getClassName(), tx = n.getText(), ds = n.getContentDescription(), ht = n.getHintText();
        x.cls = cn == null ? "" : cn.toString();
        x.text = n.isPassword() ? "" : tx == null ? "" : clip(tx.toString(), 300);
        x.desc = ds == null ? "" : clip(ds.toString(), 200);
        x.hint = ht == null ? "" : clip(ht.toString(), 120);
        String rid = n.getViewIdResourceName();
        x.rid = rid == null ? "" : rid;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        x.l = b.left;
        x.t = b.top;
        x.r = b.right;
        x.b = b.bottom;
        int f = 0;
        if (n.isClickable()) f |= FsTree.F_CLICK;
        if (n.isLongClickable()) f |= FsTree.F_LONG;
        if (n.isEditable()) f |= FsTree.F_EDIT;
        if (n.isScrollable()) f |= FsTree.F_SCROLL;
        if (n.isCheckable()) f |= FsTree.F_CHECKABLE;
        if (n.isChecked()) f |= FsTree.F_CHECKED;
        if (n.isSelected()) f |= FsTree.F_SELECTED;
        if (n.isFocused()) f |= FsTree.F_FOCUSED;
        if (n.isEnabled()) f |= FsTree.F_ENABLED;
        if (n.isPassword()) f |= FsTree.F_PASSWORD;
        if (n.isFocusable()) f |= FsTree.F_FOCUSABLE;
        if (n.isVisibleToUser()) f |= FsTree.F_VISIBLE;
        if (n.isShowingHintText()) f |= FsTree.F_HINT_SHOWING;
        if (n.getCollectionInfo() != null || x.cls.endsWith("RecyclerView") || x.cls.endsWith("ListView") || x.cls.endsWith("GridView")) f |= FsTree.F_LIST;
        x.flags = f;
        x.win = win;
        int me = t.nodes.size();
        t.nodes.add(x);
        handles.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), me, i, depth + 1, t, handles, maxNodes, t0, win);
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private AccessibilityNodeInfo node(int src, int[] bounds) {
        AccessibilityNodeInfo n;
        synchronized (mLock) {
            n = src >= 0 && src < mNodes.size() ? mNodes.get(src) : null;
        }
        if (n == null || !n.refresh()) return null;
        if (bounds != null && bounds.length == 4) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.left != bounds[0] || r.top != bounds[1] || r.right != bounds[2] || r.bottom != bounds[3]) return null;   // 节点已经换了位置 / 内容
        }
        return n;
    }

    // ------------------------------------------------------------------ 动作

    public boolean clickNode(int src, int[] bounds, int maxAncestor) {
        AccessibilityNodeInfo n = node(src, bounds);
        int up = 0;
        while (n != null && up <= maxAncestor && !(n.isClickable() && n.isEnabled())) {
            n = n.getParent();
            up++;
        }
        return n != null && up <= maxAncestor && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    public String scrollNode(int src, int[] bounds, int dir) {
        AccessibilityNodeInfo n = node(src, bounds);
        int up = 0;
        while (n != null && up <= 8 && !n.isScrollable()) {
            n = n.getParent();
            up++;
        }
        if (n == null || !n.isScrollable()) return null;
        return n.performAction(dir > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ? "scrolled" : "edge";
    }

    public boolean setText(int src, int[] bounds, String text) {
        AccessibilityNodeInfo n = node(src, bounds);
        if (n == null || !n.isEditable() || n.isPassword()) return false;
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
    }

    /** dispatchGesture 点按（API 24+），最多等 1 s 回调。 */
    public boolean gestureTap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
            final CountDownLatch l = new CountDownLatch(1);
            final boolean[] ok = {false};
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    l.countDown();
                }

                @Override public void onCancelled(GestureDescription d) {
                    l.countDown();
                }
            }, null);
            if (!sent) return false;
            l.await(1, TimeUnit.SECONDS);
            return ok[0];
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean globalBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean globalHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
