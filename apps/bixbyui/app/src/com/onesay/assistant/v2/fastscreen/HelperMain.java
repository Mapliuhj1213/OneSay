package com.onesay.assistant.v2.fastscreen;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ r2/fast-screen：常驻 root 读屏助手（app_process 进程，不是 App 进程）。
 *
 * <p>由 exec 守护的固定模板 {@code bixby-fastscreen-start} 拉起：
 * {@code CLASSPATH=<本包 base.apk> app_process /system/bin --nice-name=bixbyui_fs:<pkg> com.onesay.assistant.v2.fastscreen.HelperMain <pkg> <uid>}。
 * 守护在拉起前已经核过请求方的 uid + 证书；助手对每个连接再核一次（LocalSocket 对端 uid → 包名 → 证书 SHA-256 等于固定值）。
 *
 * <p>只开放 {@link FsProtocol#OPS} 里的固定方法，没有任意 shell。
 *
 * <p>能耗取舍：UiAutomation 连着时系统把所有界面的无障碍事件推给本进程（还会让 accessibility_enabled=1），
 * 所以读树后 {@link #UA_IDLE_MS} 内没有新的读屏请求就断开（重连约 40 ms）；{@link #EXIT_IDLE_MS} 没有任何请求就整个退出
 * （下次由 App 经守护重新拉起，约 2–3 s）。
 */
public final class HelperMain {

    static final long UA_IDLE_MS = 30_000L;
    static final long EXIT_IDLE_MS = 10 * 60_000L;
    static final int TREE_MAX_NODES = 1500;
    static final long TREE_MAX_MS = 2500L;

    static String sPkg;
    static int sUid;
    static volatile long sLastReq = SystemClock.uptimeMillis();
    static volatile long sLastUa = 0L;
    static final Object OP_LOCK = new Object();

    static UiAutomation sUa;
    static HandlerThread sUaThread;
    static volatile String sLastWinPkg = "", sLastWinCls = "";
    static volatile long sLastEventAt = 0L;

    // 认证缓存
    static int sAuthUid = -1;
    static long sAuthAt = 0L;

    public static void main(String[] args) {
        if (args.length > 2 && "--client".equals(args[0])) {
            try {
                client(args);
            } catch (Throwable t) {
                System.out.println("client error " + t);
            }
            return;
        }
        try {
            sPkg = args.length > 0 ? args[0] : "";
            sUid = args.length > 1 ? Integer.parseInt(args[1]) : -1;
            if (!FsProtocol.isPkg(sPkg) || sUid < 10000 || FsProtocol.pinnedSig(sPkg) == null) {
                System.err.println("bad args");
                System.exit(2);
            }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            final LocalServerSocket ss;
            try {
                ss = new LocalServerSocket(FsProtocol.socketName(sPkg));
            } catch (Throwable t) {
                System.err.println("already running: " + t);
                System.exit(3);
                return;
            }
            log("start pkg=" + sPkg + " uid=" + sUid + " pid=" + android.os.Process.myPid() + " myUid=" + android.os.Process.myUid());
            Thread idle = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        SystemClock.sleep(5000);
                        long now = SystemClock.uptimeMillis();
                        synchronized (OP_LOCK) {
                            if (sUa != null && now - sLastUa > UA_IDLE_MS) releaseUa("idle");
                        }
                        if (now - sLastReq > EXIT_IDLE_MS) {
                            log("idle exit");
                            shutdown();
                        }
                    }
                }
            }, "fs-idle");
            idle.setDaemon(true);
            idle.start();
            while (true) {
                final LocalSocket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        serve(s);
                    }
                }, "fs-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            log("fatal " + t);
            shutdown();
        }
    }

    static void log(String s) {
        System.out.println("[FS] " + s);
    }

    static void shutdown() {
        synchronized (OP_LOCK) {
            releaseUa("exit");
            stopBackWatch();
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ 连接与认证

    static void serve(LocalSocket s) {
        try {
            Credentials c = s.getPeerCredentials();
            int peer = c.getUid();
            String deny = authorize(peer);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            if (deny != null) {
                log("DENY peer uid=" + peer + " " + deny);
                FsProtocol.writeResponse(out, FsProtocol.ST_DENY, "DENY " + deny);
                s.close();
                return;
            }
            while (true) {
                String line;
                try {
                    line = FsProtocol.readRequest(in);
                } catch (java.io.EOFException eof) {
                    break;
                }
                sLastReq = SystemClock.uptimeMillis();
                String[] p = FsProtocol.decode(line);
                String bad = FsProtocol.validate(p);
                if (bad != null) {
                    FsProtocol.writeResponse(out, FsProtocol.ST_DENY, "DENY " + bad);
                    continue;
                }
                if (FsProtocol.OP_QUIT.equals(p[0])) {
                    FsProtocol.writeResponse(out, FsProtocol.ST_OK, "bye");
                    shutdown();
                }
                Object r;
                try {
                    synchronized (OP_LOCK) {
                        r = handle(p);
                    }
                } catch (Throwable t) {
                    Throwable x = t.getCause() != null ? t.getCause() : t;
                    r = new Err(String.valueOf(x));
                }
                if (r instanceof byte[]) FsProtocol.writeResponse(out, FsProtocol.ST_OK, (byte[]) r);
                else if (r instanceof Err) FsProtocol.writeResponse(out, FsProtocol.ST_ERR, ((Err) r).msg);
                else FsProtocol.writeResponse(out, FsProtocol.ST_OK, String.valueOf(r));
            }
        } catch (Throwable t) {
            log("conn err " + t);
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static final class Err {
        final String msg;

        Err(String m) {
            msg = m;
        }
    }

    static String authorize(int peer) {
        if (peer == 0) return null;   // root（守护 / 真机测试脚本）本来就能做这些事
        long now = SystemClock.uptimeMillis();
        if (peer == sAuthUid && now - sAuthAt < 60_000L) return null;
        String[] pkgs = null;
        String[] shas = null;
        try {
            Object pm = ipm();
            pkgs = (String[]) pm.getClass().getMethod("getPackagesForUid", int.class).invoke(pm, peer);
            if (pkgs != null && pkgs.length == 1) {
                Object pi = pm.getClass().getMethod("getPackageInfo", String.class, int.class, int.class)
                        .invoke(pm, pkgs[0], 64 /* GET_SIGNATURES */, 0);
                android.content.pm.Signature[] sigs = pi == null ? null : ((android.content.pm.PackageInfo) pi).signatures;
                if (sigs != null) {
                    shas = new String[sigs.length];
                    for (int i = 0; i < sigs.length; i++) shas[i] = FsProtocol.sha256Hex(sigs[i].toByteArray());
                }
            }
        } catch (Throwable t) {
            return "PM(" + t + ")";
        }
        String r = FsProtocol.authorizePeer(peer, sUid, sPkg, pkgs, shas);
        if (r == null) {
            sAuthUid = peer;
            sAuthAt = now;
        }
        return r;
    }

    static Object sIpm;

    static Object ipm() throws Exception {
        if (sIpm != null) return sIpm;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "package");
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        sIpm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        return sIpm;
    }

    // ------------------------------------------------------------------ 方法

    static Object handle(String[] p) throws Exception {
        String op = p[0];
        if (op.equals(FsProtocol.OP_PING)) return "pong uid=" + android.os.Process.myUid() + " ua=" + (sUa != null);
        if (op.equals(FsProtocol.OP_TREE)) {
            int max = p.length > 1 ? Integer.parseInt(p[1]) : TREE_MAX_NODES;
            FsTree t = p.length > 2 && "w".equals(p[2]) ? readAllWindows(max) : readTree(max);   // ★ mika-plug：w = 全部内容窗口
            return t == null ? new Err("NO_WINDOW") : t.toJson().getBytes(FsProtocol.UTF8);
        }
        if (op.equals(FsProtocol.OP_XML)) {
            FsTree t = readTree(TREE_MAX_NODES);
            boolean comp = p.length > 1 && "1".equals(p[1]);
            return t == null ? new Err("NO_WINDOW") : t.toUiautomatorXml(comp).getBytes(FsProtocol.UTF8);
        }
        if (op.equals(FsProtocol.OP_SHOT)) {
            int q = p.length > 1 ? Integer.parseInt(p[1]) : 70;
            int mw = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return shotJpeg(q, mw);
        }
        if (op.equals(FsProtocol.OP_SHOT_RAW)) return shotRaw();
        if (op.equals(FsProtocol.OP_TAP)) return tap(i(p[1]), i(p[2]), 40);
        if (op.equals(FsProtocol.OP_LONG)) return tap(i(p[1]), i(p[2]), i(p[3]));
        if (op.equals(FsProtocol.OP_SWIPE)) return swipe(i(p[1]), i(p[2]), i(p[3]), i(p[4]), i(p[5]));
        if (op.equals(FsProtocol.OP_CLICK)) return click(i(p[1]), i(p[2]));
        if (op.equals(FsProtocol.OP_SCROLL)) return scroll(i(p[1]), i(p[2]), i(p[3]));
        if (op.equals(FsProtocol.OP_KEY)) return key(i(p[1]));
        if (op.equals(FsProtocol.OP_PASTE)) return key(FsProtocol.KEY_PASTE);
        if (op.equals(FsProtocol.OP_SETTEXT)) return setText(i(p[1]), i(p[2]), p[3]);
        if (op.equals(FsProtocol.OP_LAUNCH)) return launch(p[1]);
        if (op.equals(FsProtocol.OP_TOP)) return top();
        if (op.equals(FsProtocol.OP_CLICKB)) return clickBounds(i(p[1]), i(p[2]), i(p[3]), i(p[4]), p.length > 5 ? p[5] : "");
        if (op.equals(FsProtocol.OP_IDLE)) return idle(i(p[1]), i(p[2]));
        if (op.equals(FsProtocol.OP_BACKWATCH)) return backWatch(p);
        if (op.equals(FsProtocol.OP_BACKS)) return takeBacks();
        if (op.equals(FsProtocol.OP_BACKWATCH_OFF)) {
            stopBackWatch();
            return "off";
        }
        if (op.equals(FsProtocol.OP_RELEASE)) {
            releaseUa("client");
            return "released";
        }
        return new Err("UNKNOWN");
    }

    static int i(String s) {
        return Integer.parseInt(s.trim());
    }

    // ------------------------------------------------------------------ UiAutomation

    static UiAutomation ua() throws Exception {
        sLastUa = SystemClock.uptimeMillis();
        if (sUa != null) return sUa;
        if (sUaThread == null) {
            sUaThread = new HandlerThread("fs-ua");
            sUaThread.start();
        }
        Class<?> connCls = Class.forName("android.app.UiAutomationConnection");
        Object conn = connCls.getConstructor().newInstance();
        Class<?> iconn = Class.forName("android.app.IUiAutomationConnection");
        Constructor<UiAutomation> c = UiAutomation.class.getDeclaredConstructor(Looper.class, iconn);
        c.setAccessible(true);
        UiAutomation u = c.newInstance(sUaThread.getLooper(), conn);
        try {
            Method m = UiAutomation.class.getDeclaredMethod("connect", int.class);
            m.setAccessible(true);
            m.invoke(u, 1 /* FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES */);
        } catch (NoSuchMethodException e) {
            Method m = UiAutomation.class.getDeclaredMethod("connect");
            m.setAccessible(true);
            m.invoke(u);
        }
        AccessibilityServiceInfo info = u.getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            u.setServiceInfo(info);
        }
        u.setOnAccessibilityEventListener(new UiAutomation.OnAccessibilityEventListener() {
            @Override public void onAccessibilityEvent(AccessibilityEvent e) {
                sLastEventAt = SystemClock.uptimeMillis();
                if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    CharSequence pk = e.getPackageName(), cl = e.getClassName();
                    if (pk != null && !pk.toString().equals(sPkg)) {
                        sLastWinPkg = pk.toString();
                        sLastWinCls = cl == null ? "" : cl.toString();
                    }
                }
            }
        });
        sUa = u;
        log("ua connected");
        return u;
    }

    static void releaseUa(String why) {
        if (sUa == null) return;
        try {
            Method m = UiAutomation.class.getDeclaredMethod("disconnect");
            m.setAccessible(true);
            m.invoke(sUa);
        } catch (Throwable ignored) {
        }
        sUa = null;
        log("ua released (" + why + ")");
    }

    /** 被操作的窗口：活动窗口；活动窗口是自家（面板 / 岛）时取最上面的非自家应用窗口。 */
    static AccessibilityNodeInfo targetRoot(UiAutomation u) {
        AccessibilityNodeInfo r = u.getRootInActiveWindow();
        if (r != null && r.getPackageName() != null && !sPkg.contentEquals(r.getPackageName())) return r;
        try {
            List<AccessibilityWindowInfo> ws = u.getWindows();
            AccessibilityWindowInfo best = null;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr == null || wr.getPackageName() == null || sPkg.contentEquals(wr.getPackageName())) continue;
                if (best == null || w.getLayer() > best.getLayer()) best = w;
            }
            if (best != null) return best.getRoot();
        } catch (Throwable ignored) {
        }
        return r;
    }

    static FsTree readTree(int maxNodes) throws Exception {
        UiAutomation u = ua();
        long t0 = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = targetRoot(u);
        if (root == null) {
            SystemClock.sleep(60);   // 刚连上 / 窗口切换中：给一次机会
            root = targetRoot(u);
        }
        if (root == null) return null;
        FsTree t = new FsTree();
        t.pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        t.activity = topActivity(t.pkg);
        walk(root, -1, 0, 0, t, maxNodes, t0, 0);
        // ★ fast-screen-v2（真机：三星设置首页点搜索框、输入法弹出后，活动窗口是个空的弹出层，整棵树 0 个元素）：
        //   活动窗口几乎没内容时，把同一个 App 的其它应用窗口（按层级从上到下）也走一遍，作为并列的根
        if (t.nodes.size() <= 3) {
            try {
                List<AccessibilityWindowInfo> ws = new java.util.ArrayList<AccessibilityWindowInfo>(u.getWindows());
                java.util.Collections.sort(ws, new java.util.Comparator<AccessibilityWindowInfo>() {
                    @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                        return b.getLayer() - a.getLayer();
                    }
                });
                for (AccessibilityWindowInfo w : ws) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo wr = w.getRoot();
                    if (wr == null || wr.getPackageName() == null || !t.pkg.contentEquals(wr.getPackageName()) || wr.equals(root)) continue;
                    walk(wr, -1, 0, 0, t, maxNodes, t0, 0);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            for (AccessibilityWindowInfo w : u.getWindows()) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) t.ime = true;
        } catch (Throwable ignored) {
        }
        t.ms = SystemClock.uptimeMillis() - t0;
        return t;
    }

    static void walk(AccessibilityNodeInfo n, int parent, int childIdx, int depth, FsTree t, int maxNodes, long t0, int win) {
        if (n == null) return;
        if (t.nodes.size() >= maxNodes || SystemClock.uptimeMillis() - t0 > TREE_MAX_MS || depth > 80) {
            t.truncated = true;
            return;
        }
        if (parent >= 0 && !n.isVisibleToUser()) return;
        FsTree.Node x = new FsTree.Node();
        x.parent = parent;
        x.childIdx = childIdx;
        CharSequence cn = n.getClassName(), tx = n.getText(), ds = n.getContentDescription();
        x.cls = cn == null ? "" : cn.toString();
        x.text = n.isPassword() ? "" : tx == null ? "" : clip(tx.toString(), 300);
        x.desc = ds == null ? "" : clip(ds.toString(), 200);
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
        // ★ mika-plug：提示语 / 列表容器（Mika ScreenReader 的 hintText、showingHint、isList；API 26 都有）
        try {
            CharSequence ht = n.getHintText();
            x.hint = ht == null ? "" : clip(ht.toString(), 120);
            if (n.isShowingHintText()) f |= FsTree.F_HINT_SHOWING;
        } catch (Throwable ignored) {
        }
        if (n.getCollectionInfo() != null || x.cls.endsWith("RecyclerView") || x.cls.endsWith("ListView") || x.cls.endsWith("GridView")) f |= FsTree.F_LIST;
        x.win = win;
        x.flags = f;
        int me = t.nodes.size();
        t.nodes.add(x);
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch == null) continue;
            walk(ch, me, i, depth + 1, t, maxNodes, t0, win);
            ch.recycle();
        }
    }

    /**
     * ★ mika-plug（MIKA-EXEC §6.4）：读全部内容窗口（弹窗、系统窗口、他包权限框都在内），按 layer 从上到下，每个窗口一个根；
     * 去掉输入法、无障碍浮层、分屏条和自家包（面板 / 岛 / 护罩）。读不到任何窗口时退回单根读法。
     */
    static FsTree readAllWindows(int maxNodes) throws Exception {
        UiAutomation u = ua();
        long t0 = SystemClock.uptimeMillis();
        List<AccessibilityWindowInfo> ws;
        try {
            ws = new java.util.ArrayList<AccessibilityWindowInfo>(u.getWindows());
        } catch (Throwable e) {
            return readTree(maxNodes);
        }
        java.util.Collections.sort(ws, new java.util.Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        FsTree t = new FsTree();
        for (AccessibilityWindowInfo w : ws) {
            int ty = w.getType();
            if (ty == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                t.ime = true;
                continue;
            }
            if (ty == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || ty == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) continue;
            AccessibilityNodeInfo r = w.getRoot();
            String pkg = r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
            if (sPkg != null && pkg.equals(sPkg)) continue;
            FsTree.Win fw = new FsTree.Win();
            fw.id = w.getId();
            fw.layer = w.getLayer();
            fw.type = ty == AccessibilityWindowInfo.TYPE_APPLICATION ? "application" : ty == AccessibilityWindowInfo.TYPE_SYSTEM ? "system" : "other";
            fw.pkg = pkg;
            t.windows.add(fw);
            if (t.pkg.length() == 0 && ty == AccessibilityWindowInfo.TYPE_APPLICATION && r != null && pkg.length() > 0) t.pkg = pkg;
            if (r != null) walk(r, -1, 0, 0, t, maxNodes, t0, t.windows.size() - 1);
        }
        if (t.windows.isEmpty()) return readTree(maxNodes);
        t.activity = topActivity(t.pkg);
        t.ms = SystemClock.uptimeMillis() - t0;
        return t;
    }

    /** ★ mika-plug（Mika AndroidScreen.click）：范围（和类名）对得上的节点，对它或 ≤ 8 层内可点可用的祖先 ACTION_CLICK。 */
    static Object clickBounds(int l, int tp, int r, int b, String cls) throws Exception {
        UiAutomation u = ua();
        AccessibilityNodeInfo n = null;
        try {
            for (AccessibilityWindowInfo w : u.getWindows()) {
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr == null || (wr.getPackageName() != null && sPkg != null && sPkg.contentEquals(wr.getPackageName()))) continue;
                n = byBounds(wr, l, tp, r, b, cls, 0);
                if (n != null) break;
            }
        } catch (Throwable ignored) {
        }
        if (n == null) n = byBounds(targetRoot(u), l, tp, r, b, cls, 0);
        if (n == null) return new Err("NO_NODE");
        AccessibilityNodeInfo c = n;
        int up = 0;
        while (c != null && up <= 8 && !(c.isClickable() && c.isEnabled())) {
            c = c.getParent();
            up++;
        }
        if (c == null || up > 8) return new Err("NO_CLICKABLE");
        return c.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "clicked:" + up : new Err("CLICK_FAILED");
    }

    static AccessibilityNodeInfo byBounds(AccessibilityNodeInfo n, int l, int t, int r, int b, String cls, int depth) {
        if (n == null || depth > 80) return null;
        Rect x = new Rect();
        n.getBoundsInScreen(x);
        if (x.left == l && x.top == t && x.right == r && x.bottom == b && n.isVisibleToUser()) {
            CharSequence cn = n.getClassName();
            String c = cn == null ? "" : cn.toString();
            if (cls == null || cls.length() == 0 || c.endsWith(cls)) {
                // 同范围的子节点（文字盖在按钮上）：取最深的同范围节点，祖先链照样能找到可点的那个
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo d = byBounds(n.getChild(i), l, t, r, b, cls, depth + 1);
                    if (d != null) return d;
                }
                return n;
            }
        }
        if (!x.contains(l, t) && !x.intersects(l, t, r, b)) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo d = byBounds(n.getChild(i), l, t, r, b, cls, depth + 1);
            if (d != null) return d;
        }
        return null;
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** (x,y) 处最深的、满足条件的节点。 */
    static AccessibilityNodeInfo deepest(AccessibilityNodeInfo n, int x, int y, int need) {
        if (n == null || !n.isVisibleToUser() && n.getParent() != null) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(x, y)) return null;
        AccessibilityNodeInfo best = null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo d = deepest(ch, x, y, need);
            if (d != null) {
                best = d;
                break;
            }
        }
        if (best != null) return best;
        boolean ok = (need == FsTree.F_CLICK && n.isClickable()) || (need == FsTree.F_EDIT && n.isEditable())
                || (need == FsTree.F_SCROLL && n.isScrollable());
        return ok ? n : null;
    }

    static Object click(int x, int y) throws Exception {
        AccessibilityNodeInfo root = targetRoot(ua());
        AccessibilityNodeInfo n = deepest(root, x, y, FsTree.F_CLICK);
        if (n == null) return new Err("NO_CLICKABLE");
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return ok ? "clicked" : new Err("CLICK_FAILED");
    }

    static Object setText(int x, int y, String text) throws Exception {
        AccessibilityNodeInfo root = targetRoot(ua());
        AccessibilityNodeInfo n = null;
        if (x > 0 || y > 0) n = deepest(root, x, y, FsTree.F_EDIT);
        if (n == null && root != null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) return new Err("NO_EDITABLE");
        if (n.isPassword()) return new Err("PASSWORD_FIELD");
        if (!n.isFocused()) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
        if (!ok) return new Err("SET_TEXT_FAILED");
        // ★ fast-screen-v2：ACTION_SET_TEXT 后节点缓存不一定马上更新（真机三星设置搜索框：屏上已是「关于手机」，立即 refresh 读回的还是提示「搜索」）
        //   → 最多等 ~400 ms，直到读回包含要输入的字
        CharSequence got = null;
        for (int k = 0; k < 8; k++) {
            n.refresh();
            got = n.getText();
            if (got != null && got.toString().contains(text)) break;
            Thread.sleep(50);
        }
        return "set:" + (got == null ? "" : got.toString());
    }

    static Object scroll(int x, int y, int dir) throws Exception {
        AccessibilityNodeInfo root = targetRoot(ua());
        AccessibilityNodeInfo n = deepest(root, x, y, FsTree.F_SCROLL);
        if (n == null) {
            // 没有可滚动节点：退回注入滑动（屏幕中线，滑 45% 屏高）
            Point sz = screenSize();
            int h = sz.y, cx = x > 0 ? x : sz.x / 2;
            int y1 = dir > 0 ? h * 70 / 100 : h * 30 / 100, y2 = dir > 0 ? h * 25 / 100 : h * 75 / 100;
            return "swipe:" + swipe(cx, y1, cx, y2, 300);
        }
        boolean ok = n.performAction(dir > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        return ok ? "scrolled" : "edge";
    }

    static Object sIam;

    /** 前台任务的顶部 Activity（IActivityManager.getTasks，约 2 ms）；包名对不上（弹窗 / 自家窗口）时用最近的窗口事件兜底。 */
    static String topActivity(String pkg) {
        try {
            if (sIam == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity");
                sIam = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            }
            Object l = sIam.getClass().getMethod("getTasks", int.class, int.class).invoke(sIam, 1, 0);
            List<?> tasks = (List<?>) l;
            if (tasks != null && !tasks.isEmpty()) {
                android.content.ComponentName cn = ((android.app.ActivityManager.RunningTaskInfo) tasks.get(0)).topActivity;
                if (cn != null && (pkg == null || pkg.length() == 0 || pkg.equals(cn.getPackageName()))) return cn.getClassName();
            }
        } catch (Throwable ignored) {
        }
        return pkg != null && pkg.equals(sLastWinPkg) ? sLastWinCls : "";
    }

    static Object top() throws Exception {
        UiAutomation u = ua();
        AccessibilityNodeInfo r = targetRoot(u);
        String pkg = r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
        boolean ime = false;
        String focusPkg = "";
        try {
            for (AccessibilityWindowInfo w : u.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ime = true;
                if (w.isFocused()) {
                    AccessibilityNodeInfo wr = w.getRoot();
                    if (wr != null && wr.getPackageName() != null) focusPkg = wr.getPackageName().toString();
                }
            }
        } catch (Throwable ignored) {
        }
        AccessibilityNodeInfo inp = r == null ? null : r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        String inFocus = inp == null ? "" : (inp.getViewIdResourceName() == null ? String.valueOf(inp.getClassName()) : inp.getViewIdResourceName());
        return "{\"pkg\":" + FsTree.JSONObjectQuote.q(pkg) + ",\"act\":" + FsTree.JSONObjectQuote.q(topActivity(pkg))
                + ",\"focusWin\":" + FsTree.JSONObjectQuote.q(focusPkg) + ",\"ime\":" + ime + ",\"inputFocus\":" + FsTree.JSONObjectQuote.q(inFocus) + "}";
    }

    /** 等界面安静 idleMs（无障碍事件驱动），最多 timeoutMs；返回实际等了多久。 */
    static Object idle(int idleMs, int timeoutMs) throws Exception {
        UiAutomation u = ua();
        long t0 = SystemClock.uptimeMillis();
        try {
            u.waitForIdle(idleMs, timeoutMs);
            return "idle " + (SystemClock.uptimeMillis() - t0);
        } catch (java.util.concurrent.TimeoutException e) {
            return "busy " + (SystemClock.uptimeMillis() - t0);
        }
    }

    // ------------------------------------------------------------------ 注入

    static Object sIm;
    static Method sInject;

    static boolean inject(InputEvent e) throws Exception {
        if (sIm == null) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            sIm = c.getMethod("getInstance").invoke(null);
            sInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        return (Boolean) sInject.invoke(sIm, e, 1 /* WAIT_FOR_RESULT */);
    }

    static MotionEvent motion(long down, long t, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, t, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    static Object tap(int x, int y, int holdMs) throws Exception {
        long d = SystemClock.uptimeMillis();
        boolean a = inject(motion(d, d, MotionEvent.ACTION_DOWN, x, y));
        if (holdMs > 60) {
            long end = d + holdMs;
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(50);
                inject(motion(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y));
            }
        } else SystemClock.sleep(holdMs);
        boolean b = inject(motion(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y));
        return a && b ? "tapped" : new Err("INJECT_FAILED");
    }

    static Object swipe(int x1, int y1, int x2, int y2, int ms) throws Exception {
        long d = SystemClock.uptimeMillis();
        boolean ok = inject(motion(d, d, MotionEvent.ACTION_DOWN, x1, y1));
        int steps = Math.max(4, ms / 16);
        for (int k = 1; k <= steps; k++) {
            float f = k / (float) steps;
            float e = (float) (1 - Math.pow(1 - f, 2));   // 缓出
            SystemClock.sleep(Math.max(1, ms / steps));
            ok &= inject(motion(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * e, y1 + (y2 - y1) * e));
        }
        ok &= inject(motion(d, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2));
        return ok ? "swiped" : new Err("INJECT_FAILED");
    }

    static Object key(int code) throws Exception {
        long t = SystemClock.uptimeMillis();
        KeyEvent dn = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return inject(dn) && inject(up) ? "key " + code : new Err("INJECT_FAILED");
    }

    static Object launch(String pkg) throws Exception {
        Process pr = new ProcessBuilder("/system/bin/monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                .redirectErrorStream(true).start();
        String out = readAll(pr.getInputStream(), 4096);
        int rc = pr.waitFor();
        return rc == 0 && !out.contains("No activities found") ? "launched" : new Err("LAUNCH rc=" + rc);
    }

    static String readAll(java.io.InputStream in, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = in.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    // ------------------------------------------------------------------ 截图

    static Point screenSize() throws Exception {
        Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dmg = g.getMethod("getInstance").invoke(null);
        Object info = g.getMethod("getDisplayInfo", int.class).invoke(dmg, 0);
        int lw = info.getClass().getField("logicalWidth").getInt(info);
        int lh = info.getClass().getField("logicalHeight").getInt(info);
        return new Point(lw, lh);
    }

    static int rotation() {
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = g.getMethod("getInstance").invoke(null);
            Object info = g.getMethod("getDisplayInfo", int.class).invoke(dmg, 0);
            return info.getClass().getField("rotation").getInt(info);
        } catch (Throwable t) {
            return 0;
        }
    }

    static Bitmap grab() throws Exception {
        Point sz = screenSize();
        int rot = rotation();
        int w = sz.x, h = sz.y;
        boolean swap = rot == 1 || rot == 3;
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Bitmap b = (Bitmap) sc.getMethod("screenshot", int.class, int.class).invoke(null, swap ? h : w, swap ? w : h);
        if (b == null) return null;
        if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(-90f * rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
            b.recycle();
            b = r;
        }
        return b;
    }

    static Object shotJpeg(int q, int maxW) throws Exception {
        Bitmap b = grab();
        if (b == null) return new Err("SCREENSHOT_NULL");
        if (maxW > 0 && b.getWidth() > maxW) {
            Bitmap s = Bitmap.createScaledBitmap(b, maxW, Math.round(b.getHeight() * (maxW / (float) b.getWidth())), true);
            b.recycle();
            b = s;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(256 * 1024);
        b.compress(Bitmap.CompressFormat.JPEG, q, bo);
        b.recycle();
        return bo.toByteArray();
    }

    /** 与 Android 8 {@code screencap}（不带 -p）同格式：12 字节小端头 w,h,format(1=RGBA_8888) + RGBA 像素。 */
    static Object shotRaw() throws Exception {
        Bitmap b = grab();
        if (b == null) return new Err("SCREENSHOT_NULL");
        if (b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap c = b.copy(Bitmap.Config.ARGB_8888, false);
            b.recycle();
            b = c;
        }
        int w = b.getWidth(), h = b.getHeight(), rb = b.getRowBytes();
        ByteBuffer all = ByteBuffer.allocate(rb * h);
        b.copyPixelsToBuffer(all);   // ARGB_8888 在内存里就是 R,G,B,A 字节序
        b.recycle();
        ByteBuffer px;
        if (rb == w * 4) px = all;
        else {   // 行有对齐填充：逐行压紧成 w*4
            px = ByteBuffer.allocate(w * h * 4);
            byte[] a = all.array();
            for (int y = 0; y < h; y++) px.put(a, y * rb, w * 4);
        }
        ByteBuffer hd = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        hd.putInt(w).putInt(h).putInt(1);
        return FsProtocol.concat(hd.array(), px.array());
    }

    // ------------------------------------------------------------------ 导航键观察（执行模式的「返回键紧急接管」）

    static volatile Thread sWatch;
    static volatile int sBacks, sHomes;
    static volatile boolean sWatchOn;
    static Rect sBackR, sHomeR;

    /**
     * 读触摸屏原始事件（/dev/input，只有真人手指才会出现在这里；注入事件不经过它），落在导航栏「返回 / 主页」键区域的按下计数。
     * 参数（可选）：返回键 l t r b 屏宽 屏高；全为 0 时从无障碍窗口里找 systemui 的 back / home 节点。
     */
    static Object backWatch(String[] p) throws Exception {
        stopBackWatch();
        Rect back = new Rect(i(p[1]), i(p[2]), i(p[3]), i(p[4]));
        Rect home = p.length > 8 ? new Rect(i(p[5]), i(p[6]), i(p[7]), i(p[8])) : new Rect();
        sBackR = back.isEmpty() ? null : back;
        sHomeR = home.isEmpty() ? null : home;
        try {
            for (AccessibilityWindowInfo w : ua().getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null || !"com.android.systemui".contentEquals(r.getPackageName())) continue;
                for (String id : new String[]{"back", "home"}) {
                    List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId("com.android.systemui:id/" + id);
                    if (l != null && !l.isEmpty()) {
                        Rect b = new Rect();
                        l.get(0).getBoundsInScreen(b);
                        if (!b.isEmpty()) {
                            if (id.equals("back")) sBackR = b;   // 无障碍里找得到导航键就以它为准
                            if (id.equals("home")) sHomeR = b;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (sBackR == null) return new Err("NO_BACK_BUTTON");
        final String dev = touchDevice();
        if (dev == null) return new Err("NO_TOUCH_DEVICE");
        final int[] max = absMax(dev);
        final Point sz = screenSize();
        sBacks = 0;
        sHomes = 0;
        sWatchOn = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                readTouches(dev, max, sz);
            }
        }, "fs-backwatch");
        t.setDaemon(true);
        sWatch = t;
        t.start();
        return "watching back=" + sBackR.toShortString() + " home=" + (sHomeR == null ? "-" : sHomeR.toShortString()) + " dev=" + dev
                + " max=" + max[0] + "x" + max[1];
    }

    static void stopBackWatch() {
        sWatchOn = false;
        Thread t = sWatch;
        sWatch = null;
        if (t != null) t.interrupt();
    }

    static Object takeBacks() {
        int b = sBacks, h = sHomes;
        sBacks = 0;
        sHomes = 0;
        return "back=" + b + " home=" + h + " on=" + sWatchOn;
    }

    static String touchDevice() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/bus/input/devices"));
            String line, name = "", handlers;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) name = line.toLowerCase();
                if (line.startsWith("H: Handlers=") && (name.contains("touchscreen") || name.contains("sec_touch"))) {
                    handlers = line;
                    int k = handlers.indexOf("event");
                    if (k >= 0) {
                        int e = k + 5;
                        while (e < handlers.length() && Character.isDigit(handlers.charAt(e))) e++;
                        return "/dev/input/" + handlers.substring(k, e);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** ABS_MT_POSITION_X / Y 的最大值（getevent -p 解析）。 */
    static int[] absMax(String dev) {
        int[] m = {0, 0};
        try {
            Process p = new ProcessBuilder("/system/bin/getevent", "-p", dev).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                // "0035  : value 0, min 0, max 4095, ..."
                if (s.startsWith("0035") || s.startsWith("0036")) {
                    int k = s.indexOf("max ");
                    if (k > 0) {
                        int e = k + 4;
                        while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
                        int v = Integer.parseInt(s.substring(k + 4, e));
                        if (s.startsWith("0035")) m[0] = v;
                        else m[1] = v;
                    }
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return m;
    }

    static void readTouches(String dev, int[] max, Point sz) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(dev);
            byte[] ev = new byte[24];   // 64 位 input_event：timeval(16) type(2) code(2) value(4)
            int x = -1, y = -1;
            boolean down = false, pendingDown = false;
            while (sWatchOn) {
                int n = 0;
                while (n < 24) {
                    int k = in.read(ev, n, 24 - n);
                    if (k < 0) return;
                    n += k;
                }
                ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
                int type = bb.getShort(16) & 0xffff, code = bb.getShort(18) & 0xffff, val = bb.getInt(20);
                if (type == 3 && code == 0x35) x = val;
                else if (type == 3 && code == 0x36) y = val;
                else if (type == 3 && code == 0x39) {   // ABS_MT_TRACKING_ID：-1 = 抬起
                    if (val >= 0 && !down) pendingDown = true;
                    if (val < 0) down = false;
                } else if (type == 1 && code == 0x14a) {   // BTN_TOUCH
                    if (val == 1 && !down) pendingDown = true;
                    if (val == 0) down = false;
                } else if (type == 0 && code == 0 && pendingDown && x >= 0 && y >= 0) {   // SYN_REPORT
                    pendingDown = false;
                    down = true;
                    int sx = max[0] > 0 ? Math.round(x * (sz.x / (float) (max[0] + 1))) : x;
                    int sy = max[1] > 0 ? Math.round(y * (sz.y / (float) (max[1] + 1))) : y;
                    if (sBackR != null && sBackR.contains(sx, sy)) {
                        sBacks++;
                        log("nav back touch " + sx + "," + sy);
                    } else if (sHomeR != null && sHomeR.contains(sx, sy)) {
                        sHomes++;
                        log("nav home touch " + sx + "," + sy);
                    }
                }
            }
        } catch (Throwable t) {
            log("backwatch end " + t);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 真机测试用的 root 命令行客户端：{@code HelperMain --client <pkg> [--n N] <op> [args…]}（只有 root 对端会被放行）。
     * 截图类把字节数和耗时打印出来；--n N 重复 N 次并给 p50/p95。
     */
    static void client(String[] a) throws Exception {
        String pkg = a[1];
        int k = 2, n = 1;
        if (a.length > 3 && a[2].equals("--n")) {
            n = Integer.parseInt(a[3]);
            k = 4;
        }
        String[] rest = new String[a.length - k - 1];
        System.arraycopy(a, k + 1, rest, 0, rest.length);
        String line = FsProtocol.encode(a[k], rest);
        LocalSocket s = new LocalSocket();
        long c0 = SystemClock.uptimeMillis();
        s.connect(new android.net.LocalSocketAddress(FsProtocol.socketName(pkg), android.net.LocalSocketAddress.Namespace.ABSTRACT));
        long c1 = SystemClock.uptimeMillis();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        long[] d = new long[n];
        FsProtocol.Resp last = null;
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            FsProtocol.writeRequest(out, line);
            last = FsProtocol.readResponse(in);
            d[i] = (System.nanoTime() - t) / 1000000L;
        }
        s.close();
        String body = last.status == 0 && last.body.length > 0 && (a[k].startsWith("shot")) ? "<" + last.body.length + " bytes>" : last.text();
        if (n == 1) System.out.println("st=" + last.status + " " + d[0] + "ms connect=" + (c1 - c0) + "ms " + body);
        else {
            long[] q = d.clone();
            java.util.Arrays.sort(q);
            System.out.println(a[k] + " n=" + n + " p50=" + q[n / 2] + "ms p95=" + q[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)] + "ms min="
                    + q[0] + " max=" + q[n - 1] + " st=" + last.status + " " + (body.length() > 300 ? body.substring(0, 300) + "…" : body));
        }
    }
}
