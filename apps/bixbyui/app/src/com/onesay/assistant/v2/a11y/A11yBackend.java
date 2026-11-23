/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: app/cap/phone/a11y/ScreenAccessibility.kt reduced to an Android 8 (API 26) single-display
 * backend in Java 7 — getWindows() instead of windowsOnAllDisplays, screenshots only through the API 30 takeScreenshot when the
 * service declares that capability, no displayId, events are only
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
        sConnects++;
        android.util.Log.i("A11yBackend", "[A11Y] 服务已连上（第 " + sConnects + " 次）");
        try {
            mKeyWake = new com.onesay.assistant.v2.entry.keywake.KeyWakeHost(this);
            mKeyWake.refresh();
        } catch (Throwable t) {
            android.util.Log.w("A11yBackend", "按键唤醒启动失败: " + t.getClass().getSimpleName());
        }
    }

    private volatile com.onesay.assistant.v2.entry.keywake.KeyWakeHost mKeyWake;

    /** 按键唤醒设置 / 入口开关改动后调用（服务没连上时什么都不做）。 */
    public static void refreshKeyWake() {
        A11yBackend b = sInstance;
        if (b != null && b.mKeyWake != null) b.mKeyWake.refresh();
    }

    @Override protected boolean onKeyEvent(android.view.KeyEvent event) {
        com.onesay.assistant.v2.entry.keywake.KeyWakeHost h = mKeyWake;
        if (h != null && h.onKeyEvent(event)) return true;
        return super.onKeyEvent(event);
    }

    /**
     * ★ a11y-1118：只清掉<b>自己</b>。Flyme 会周期性地解绑 / 重新绑定服务，新实例可能先 onServiceConnected、旧实例后 onUnbind；
     * 以前旧实例一律 sInstance = null，会把刚连上的新实例也抹掉，直到下一次重新绑定（约 30 s）都显示「没连上」。
     */
    @Override public boolean onUnbind(android.content.Intent intent) {
        clearSelf("unbind");
        if (mKeyWake != null) mKeyWake.stop();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        clearSelf("destroy");
        super.onDestroy();
    }

    private void clearSelf(String why) {
        boolean mine = sInstance == this;
        if (mine) sInstance = null;
        android.util.Log.i("A11yBackend", "[A11Y] 服务" + why + (mine ? "" : "（旧实例，当前实例不动）"));
    }

    static volatile int sConnects;

    /** ★ a11y-1118：系统设置里本服务是否打开（含 debug 包 applicationId）。 */
    public static boolean enabledInSettings(android.content.Context ctx) {
        try {
            String v = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
            return A11yState.enabledIn(v, ctx.getPackageName(), A11yState.SERVICE_CLASS);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ a11y-1118：连着就立刻 true；设置里开着但实例暂时没连上（重新绑定中）→ 最多等 waitMs。
     */
    public static boolean awaitConnected(android.content.Context ctx, long waitMs) {
        if (sInstance != null) return true;
        if (ctx == null || !enabledInSettings(ctx)) return false;
        long end = SystemClock.uptimeMillis() + Math.max(0L, waitMs);
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
            if (sInstance != null) return true;
        }
        return sInstance != null;
    }

    // ------------------------------------------------------------------ ★ plan-1118：跨重绑间隙

    static final RebindWait.Source<A11yBackend> SOURCE = new RebindWait.Source<A11yBackend>() {
        @Override public A11yBackend current() {
            return sInstance;
        }
    };

    static final RebindWait.Clock CLOCK = new RebindWait.Clock() {
        @Override public long now() {
            return SystemClock.uptimeMillis();
        }

        @Override public void sleep(long ms) {
            SystemClock.sleep(ms);
        }
    };

    /**
     * 当前实例；正在重新绑定（本进程连上过、此刻是 null）→ 最多等 maxMs。从没连上过就不等。
     */
    public static A11yBackend awaitInstance(long maxMs) {
        A11yBackend b = sInstance;
        if (b != null || sConnects == 0) return b;
        long t0 = SystemClock.uptimeMillis();
        b = RebindWait.await(SOURCE, maxMs, CLOCK);
        android.util.Log.i("A11yBackend", "[A11Y] 重绑间隙等了 " + (SystemClock.uptimeMillis() - t0) + "ms → " + (b != null ? "连上" : "仍没连上"));
        return b;
    }

    /** 在当前实例上做 op；跨重绑间隙等待并在新实例上重做一次（见 {@link RebindWait#call}）。 */
    public static <R> R call(RebindWait.Op<A11yBackend, R> op, long maxMs) {
        if (sInstance == null && sConnects == 0) return null;
        return RebindWait.call(SOURCE, op, maxMs, CLOCK);
    }

    /** 布尔动作的跨重绑版本（false = 失败）。 */
    public static boolean act(final RebindWait.Op<A11yBackend, Boolean> op, long maxMs) {
        Boolean r = call(new RebindWait.Op<A11yBackend, Boolean>() {
            @Override public Boolean run(A11yBackend b) {
                Boolean x = op.run(b);
                return x != null && x ? Boolean.TRUE : null;
            }
        }, maxMs);
        return r != null && r;
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
        return readTree(maxNodes, true);
    }

    /** ★ plan-1118：keep=false 只读不换节点句柄（点按后比对用，免得模型手里的编号对不上）。 */
    public FsTree readTree(int maxNodes, boolean keep) {
        if (sInstance != this) return null;   // 已被解绑的旧实例：getWindows 只会拿到空 / 抛异常
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
        if (keep) {
            synchronized (mLock) {
                mNodes = handles;
            }
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
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 60, 1000);
    }

    /** ★ a11y-1118：按住不动（长按）。 */
    public boolean gestureHold(int x, int y, int ms) {
        Path p = new Path();
        p.moveTo(x, y);
        int d = Math.max(400, Math.min(ms, 5000));
        return gesture(p, d, d + 1000);
    }

    /** ★ a11y-1118：滑动。 */
    public boolean gestureSwipe(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        int d = Math.max(80, Math.min(ms, 5000));
        return gesture(p, d, d + 1000);
    }

    private boolean gesture(Path p, int durationMs, int waitMs) {
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();   // approve-not-block：机器人注入手势，确认卡上这一会儿的触摸不算真人
        // [fix-1123-dexec B8/B40] 手势走触摸管线，会被执行护罩当误触吞掉 → 不管谁调的，注入前一律在路径外框开孔，注入完补上
        boolean holed = false;
        try {
            android.graphics.RectF rf = new android.graphics.RectF();
            p.computeBounds(rf, true);
            int[] h = com.onesay.assistant.v2.exec.ShieldHoles.around((int) rf.left, (int) rf.top, (int) rf.right, (int) rf.bottom, 40);
            holed = com.onesay.assistant.v2.exec.ShieldOverlay.holeForInject(h[0], h[1], h[2], h[3]);
        } catch (Throwable ignored) {
        }
        try {
            GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs)).build();
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
            l.await(waitMs, TimeUnit.MILLISECONDS);
            return ok[0];
        } catch (Throwable t) {
            return false;
        } finally {
            com.onesay.assistant.v2.exec.ShieldOverlay.unholeInject(holed);
        }
    }

    // ------------------------------------------------------------------ ★ a11y-1118：按坐标的动作（读屏助手不可用时的全套后端）

    /** 上一次读到的节点里，包含 (x,y) 的最深（面积最小）节点；没有返回 null。 */
    private AccessibilityNodeInfo deepestAt(int x, int y, boolean wantEditable, boolean wantScrollable) {
        List<AccessibilityNodeInfo> l;
        synchronized (mLock) {
            l = mNodes;
        }
        if (l.isEmpty()) {   // ★ plan-1118：刚重新绑定的新实例还没读过树 → 先读一次拿句柄
            readTree(1500, true);
            synchronized (mLock) {
                l = mNodes;
            }
        }
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : l) {
            if (n == null) continue;
            if (wantEditable && !n.isEditable()) continue;
            if (wantScrollable && !n.isScrollable()) continue;
            n.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            long a = (long) r.width() * r.height();
            if (a < bestArea) {
                bestArea = a;
                best = n;
            }
        }
        if (best != null && !best.refresh()) return null;
        return best;
    }

    /** (x,y) 处节点或 ≤ 8 层可点祖先 ACTION_CLICK（不做手势）。 */
    public boolean clickNodeAt(int x, int y) {
        AccessibilityNodeInfo n = deepestAt(x, y, false, false);
        int up = 0;
        while (n != null && up <= 8 && !(n.isClickable() && n.isEnabled())) {
            n = n.getParent();
            up++;
        }
        return n != null && up <= 8 && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** 节点点击不行就 dispatchGesture 点按。 */
    public boolean clickAt(int x, int y) {
        return clickNodeAt(x, y) || gestureTap(x, y);
    }

    public boolean longPressAt(int x, int y, int ms) {
        AccessibilityNodeInfo n = deepestAt(x, y, false, false);
        int up = 0;
        while (n != null && up <= 8 && !(n.isLongClickable() && n.isEnabled())) {
            n = n.getParent();
            up++;
        }
        if (n != null && up <= 8 && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
        return gestureHold(x, y, ms);
    }

    /** @return "scrolled" / "edge"；null = 没有可滚节点。 */
    public String scrollAt(int x, int y, int dir) {
        AccessibilityNodeInfo n = deepestAt(x, y, false, true);
        if (n == null) return null;
        return n.performAction(dir > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ? "scrolled" : "edge";
    }

    /** 当前有输入焦点的输入框（找不到返回 null）。 */
    private AccessibilityNodeInfo focusedInput() {
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return f != null && f.isEditable() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** (x,y) 处的输入框（没有就用有焦点的输入框）SET_TEXT；返回写入后读回的文字（"set:…"），null = 失败。密码框不写。 */
    public String setTextAt(int x, int y, String text) {
        AccessibilityNodeInfo n = deepestAt(x, y, true, false);
        if (n == null) n = focusedInput();
        if (n == null || n.isPassword()) return null;
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)) return null;
        n.refresh();
        CharSequence now = n.getText();
        String old = now == null ? "" : now.toString();
        if (com.onesay.assistant.v2.fastscreen.ReadBack.has(old, text)) return "set:" + old;
        // [fix-1123-c] 界面换了新输入框时旧节点读回的还是旧提示：重新取有输入焦点的框再读
        AccessibilityNodeInfo f = focusedInput();
        String fresh = null;
        if (f != null && !f.isPassword()) {
            f.refresh();
            CharSequence g = f.getText();
            fresh = g == null ? "" : g.toString();
        }
        return com.onesay.assistant.v2.fastscreen.ReadBack.pick(text, old, fresh);
    }

    /** 有焦点的输入框 ACTION_PASTE。 */
    public boolean pasteFocused() {
        AccessibilityNodeInfo n = focusedInput();
        return n != null && n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    /** android.R.id.accessibilityActionImeEnter（API 30）。 */
    static final int ACTION_IME_ENTER = 0x01020054;

    /** 有焦点的输入框发「回车 / 搜索」（API 30+ 的 IME 动作）。 */
    public boolean imeEnter() {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo n = focusedInput();
        return n != null && n.performAction(ACTION_IME_ENTER);
    }

    /** android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT（API 30）。 */
    static final int CAPABILITY_CAN_TAKE_SCREENSHOT = 0x80;

    /** 能不能用无障碍截图（API 30+，且服务配置声明了 canTakeScreenshot）。 */
    public boolean canTakeScreenshot() {
        try {
            AccessibilityServiceInfo i = getServiceInfo();
            return android.os.Build.VERSION.SDK_INT >= 30 && i != null && (i.getCapabilities() & CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ a11y-1118：无障碍截图（API 30 takeScreenshot，按反射调用，本工程按 API 28 编译）写成 PNG。
     * 没有截图能力 / 失败返回 false（调用方走别的截图路径）。最多等 timeoutMs。
     */
    public boolean screenshotTo(java.io.File out, long timeoutMs) {
        if (!canTakeScreenshot() || out == null) return false;
        try {
            final Class<?> cbCls = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
            final Object[] result = new Object[1];
            final CountDownLatch l = new CountDownLatch(1);
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, new java.lang.reflect.InvocationHandler() {
                @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                    if ("onSuccess".equals(m.getName()) && args != null && args.length == 1) result[0] = args[0];
                    if ("onSuccess".equals(m.getName()) || "onFailure".equals(m.getName())) l.countDown();
                    return null;
                }
            });
            java.util.concurrent.Executor ex = new java.util.concurrent.Executor() {
                @Override public void execute(Runnable r) {
                    r.run();
                }
            };
            AccessibilityService.class.getMethod("takeScreenshot", int.class, java.util.concurrent.Executor.class, cbCls).invoke(this, 0, ex, cb);
            if (!l.await(timeoutMs, TimeUnit.MILLISECONDS) || result[0] == null) return false;
            Object shot = result[0];
            Object hb = shot.getClass().getMethod("getHardwareBuffer").invoke(shot);
            Object cs = shot.getClass().getMethod("getColorSpace").invoke(shot);
            android.graphics.Bitmap hw = (android.graphics.Bitmap) android.graphics.Bitmap.class
                    .getMethod("wrapHardwareBuffer", hb.getClass(), android.graphics.ColorSpace.class).invoke(null, hb, cs);
            try {
                hb.getClass().getMethod("close").invoke(hb);
            } catch (Throwable ignored) {
            }
            if (hw == null) return false;
            android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            if (sw == null) return false;
            java.io.FileOutputStream o = new java.io.FileOutputStream(out);
            try {
                sw.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
                sw.recycle();
            }
            return out.length() > 1024;
        } catch (Throwable t) {
            android.util.Log.w("A11yBackend", "[A11Y] 无障碍截图失败: " + t.getClass().getSimpleName());
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
