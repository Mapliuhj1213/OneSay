/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (app/cap/phone/a11y/ScreenReader.kt) to Java 7; reads backend-neutral
 * UiNode windows; multi-display removed; added own-package window filter, canvas / text-less subtree pruning,
 * ScreenPrivacy masking, and blind-area detection in the same pass (no second walk). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 从窗口里读出元素表（@eN、0–999）、界面指纹、诊断文字。纯函数。 */
public final class ScreenReader {
    private ScreenReader() {
    }

    static final int MIN_SIZE = 5;
    static final int MAX_DEPTH = 80;
    static final int AGGREGATE_MAX = 60;
    /** 无字子树超过这么多节点就剪成一个画布占位（地图 / 视频流）。 */
    static final int PRUNE_NODES = 300;
    /** App 窗口里一个字也没有、占屏这么大（0–1）的子树就是盲区（Mika TreeAdequacy.BLIND_AREA）。 */
    static final double BLIND_AREA = 0.2;
    static final int NAMED_INSIDE = 3;

    private static final Pattern RID_OK = Pattern.compile("^[A-Za-z0-9_]{4,}$");
    private static final Pattern RID_LETTERS = Pattern.compile("[A-Za-z]{3}");

    /** 读屏选项：跳过哪些包的窗口（自家面板 / 岛 / 护罩）。 */
    public static final class Options {
        public final List<String> skipPackagePrefixes = new ArrayList<String>();
        public boolean prune = true;

        public Options skip(String prefix) {
            if (prefix != null && prefix.length() > 0) skipPackagePrefixes.add(prefix);
            return this;
        }
    }

    /** 从上到下、属于界面内容的窗口（去掉输入法 / 无障碍浮层 / 自家包）。 */
    public static List<UiWindow> contentWindows(List<UiWindow> windows, Options o) {
        List<UiWindow> out = new ArrayList<UiWindow>();
        if (windows == null) return out;
        for (UiWindow w : windows) {
            if (w == null || UiWindow.IME.equals(w.type) || UiWindow.OVERLAY.equals(w.type)) continue;
            if (o != null && skipped(w.packageName, o)) continue;
            out.add(w);
        }
        Collections.sort(out, new Comparator<UiWindow>() {
            @Override public int compare(UiWindow a, UiWindow b) {
                return b.layer < a.layer ? -1 : (b.layer == a.layer ? 0 : 1);
            }
        });
        return out;
    }

    static boolean skipped(String pkg, Options o) {
        if (pkg == null) return false;
        for (String p : o.skipPackagePrefixes) if (pkg.startsWith(p)) return true;
        return false;
    }

    /** 最上面一个根节点读得到的应用窗口的包名。 */
    public static String topPackage(List<UiWindow> content) {
        for (UiWindow w : content) if (UiWindow.APPLICATION.equals(w.type) && w.root != null && w.packageName.length() > 0) return w.packageName;
        return null;
    }

    public static ScreenSnapshot read(String snapshotId, List<UiWindow> windows, int width, int height, Options o) {
        final List<UiWindow> content = contentWindows(windows, o == null ? new Options() : o);
        final Walk wk = new Walk(width, height, o == null || o.prune);
        boolean hasApp = false;
        for (UiWindow w : content) {
            if (w.root == null) continue;
            if (UiWindow.APPLICATION.equals(w.type)) hasApp = true;
            wk.app = UiWindow.APPLICATION.equals(w.type);
            wk.visit(w.root, 0, false);
        }
        wk.finishBlind();
        ScreenSnapshot s = new ScreenSnapshot(snapshotId, topPackage(content), wk.elements, wk.locators, width, height);
        s.hash = wk.hash;
        s.hasWindow = hasApp;
        s.blind = wk.blind;
        s.omittedByPrivacy = wk.protectedFields;
        return s;
    }

    private static final class Walk {
        final int w, h;
        final boolean prune;
        final List<UiElement> elements = new ArrayList<UiElement>();
        final List<ElementLocator> locators = new ArrayList<ElementLocator>();
        final Set<String> seen = new HashSet<String>();
        long hash = FNV_OFFSET;
        String lastLabel;
        boolean app, blind;
        int protectedFields;
        /** 盲区判定时要数「落在里面的有名字元素」：先记下无字大块，最后统一数（元素此时都已收齐）。 */
        final List<int[]> blindCandidates = new ArrayList<int[]>();

        Walk(int w, int h, boolean prune) {
            this.w = w;
            this.h = h;
            this.prune = prune;
        }

        /** 返回这棵子树里有没有字。 */
        boolean visit(UiNode n, int depth, boolean inList) {
            if (depth > MAX_DEPTH || n == null) return false;
            boolean visible = n.visible;
            boolean text = false;
            if (visible) {
                hash = mix(hash, n.cls, n.password ? "" : n.text, n.desc, n.l + "," + n.t + "," + n.r + "," + n.b, states(n));
                boolean prot = ScreenPrivacy.protectedField(n);
                String own = prot ? (nz(n.hint).trim().length() > 0 ? n.hint.trim() : null) : ownLabel(n);
                if (own != null) text = true;
                int[] rc = clip(n.l, n.t, n.r, n.b, w, h);
                boolean usable = rc[2] - rc[0] >= MIN_SIZE && rc[3] - rc[1] >= MIN_SIZE;
                boolean interactive = n.clickable || n.editable || n.scrollable;
                // 剪枝：画布类（地图 / 视频 / GL）或巨大的无字子树 → 一个占位元素，不往下走
                if (prune && usable && canvas(n)) {
                    addElement(n, rc, own != null ? own : "[画布]", null, inList, false);
                    return own != null;
                }
                if (usable && (own != null || interactive || prot)) {
                    String label = prot && own == null ? ScreenPrivacy.PROTECTED_NAME : labelOf(n, own);
                    if (prot && n.editable && !n.password) protectedFields++;
                    if (seen.add(n.role() + "|" + label + "|" + rc[0] + "," + rc[1] + "," + rc[2] + "," + rc[3])) {
                        addElement(n, rc, label, null, inList, prot);
                    }
                }
            }
            boolean childrenInList = inList || n.isList();
            if (prune && visible && n.children.size() > 0 && bigTextless(n)) {
                int[] rc = clip(n.l, n.t, n.r, n.b, w, h);
                if (rc[2] - rc[0] >= MIN_SIZE && rc[3] - rc[1] >= MIN_SIZE) addElement(n, rc, "[无字区域]", null, inList, false);
                noteBlind(rc, false);
                return text;
            }
            for (UiNode c : n.children) if (visit(c, depth + 1, childrenInList)) text = true;
            if (visible) {
                if (app) noteBlind(clip(n.l, n.t, n.r, n.b, w, h), text);
            }
            return text;
        }

        void noteBlind(int[] rc, boolean hasText) {
            if (hasText || !app) return;
            double area = (double) Math.max(0, rc[2] - rc[0]) * Math.max(0, rc[3] - rc[1]) / ((double) Math.max(1, w) * Math.max(1, h));
            if (area >= BLIND_AREA) blindCandidates.add(rc);
        }

        /** 全部元素收齐后再数「落在无字大块里的有名字元素」：不到 NAMED_INSIDE 个就是盲区（底板不算）。 */
        void finishBlind() {
            for (int[] rc : blindCandidates) {
                int named = 0;
                for (UiElement e : elements) if (e.name.trim().length() > 0 && inside(e.centerX, e.centerY, rc)) named++;
                if (named < NAMED_INSIDE) {
                    blind = true;
                    return;
                }
            }
        }

        void addElement(UiNode n, int[] rc, String label, String roleOverride, boolean inList, boolean prot) {
            String id = "@e" + (elements.size() + 1);
            String role = roleOverride != null ? roleOverride : n.role();
            String rid = n.rid;
            Set<String> st = new TreeSet<String>();
            if (n.checked) st.add(UiElement.CHECKED);
            if (n.selected) st.add(UiElement.SELECTED);
            if (!n.enabled) st.add(UiElement.DISABLED);
            boolean hint = !prot && ownLabel(n) != null && showingHint(n);
            String placeholder = n.editable && nz(n.hint).trim().length() > 0 ? n.hint.trim() : null;
            String name = prot ? label : ScreenPrivacy.mask(label);
            UiElement e = new UiElement(id, role, name, (rc[0] + rc[2]) / 2, (rc[1] + rc[3]) / 2,
                    new int[]{UiElement.normalize(rc[0], w), UiElement.normalize(rc[1], h), UiElement.normalize(rc[2], w), UiElement.normalize(rc[3], h)},
                    label.length() == 0 || n.editable ? readableResourceId(rid) : null,
                    n.clickable || n.longClickable, n.editable, n.scrollable, hint, n.focused, n.password || prot, placeholder,
                    n.editable && nz(n.cls).toLowerCase().contains("search"), st, inList || n.inList(), n.src);
            elements.add(e);
            locators.add(new ElementLocator(role, name, rid, e.centerX, e.centerY, lastLabel, hint, inList));
            if (name.length() > 0) lastLabel = name;
        }
    }

    static boolean canvas(UiNode n) {
        String c = nz(n.cls);
        return c.endsWith("SurfaceView") || c.endsWith("TextureView") || c.endsWith("GLSurfaceView") || c.endsWith("MapView");
    }

    /** 子树节点数超过 PRUNE_NODES 且一个字也没有。 */
    static boolean bigTextless(UiNode n) {
        int[] count = {0};
        return !anyText(n, count, 0) && count[0] > PRUNE_NODES;
    }

    private static boolean anyText(UiNode n, int[] count, int depth) {
        if (depth > MAX_DEPTH) return false;
        count[0]++;
        if (n.visible && (nz(n.text).trim().length() > 0 || nz(n.desc).trim().length() > 0 || nz(n.hint).trim().length() > 0)) return true;
        for (UiNode c : n.children) if (anyText(c, count, depth + 1)) return true;
        return false;
    }

    static boolean inside(int x, int y, int[] rc) {
        return x >= rc[0] && x <= rc[2] && y >= rc[1] && y <= rc[3];
    }

    static int[] clip(int l, int t, int r, int b, int w, int h) {
        int cl = Math.max(0, l), ct = Math.max(0, t), cr = Math.min(w, r), cb = Math.min(h, b);
        if (cr <= cl || cb <= ct) return new int[]{0, 0, 0, 0};
        return new int[]{cl, ct, cr, cb};
    }

    static String labelOf(UiNode n, String own) {
        if (own != null) return own;
        return n.clickable ? descendantLabel(n) : "";
    }

    /** 节点自己的名字：文字（提示语也算），没有就用内容描述。 */
    public static String ownLabel(UiNode n) {
        String text = nz(n.text).trim();
        if (text.length() > 0) return text;
        String desc = nz(n.desc).trim();
        if (desc.length() > 0) return desc;
        String hint = nz(n.hint).trim();
        return hint.length() > 0 ? hint : null;
    }

    /** 节点上显示的是提示语。 */
    public static boolean showingHint(UiNode n) {
        if (n.showingHint) return true;
        String hint = nz(n.hint).trim();
        if (hint.length() == 0) return false;
        String text = nz(n.text).trim();
        return text.length() == 0 ? nz(n.desc).trim().length() == 0 : text.equals(hint);
    }

    /**
     * 输入框里用户可见的实际文字；显示的是提示语时为空串，密码框为 null。placeholder 是输入前看到的提示语；
     * 要输入的 typing 本身等于提示语时，同样文字当作已输入。
     */
    public static String fieldText(UiNode n, String placeholder, String typing) {
        if (n.password) return null;
        if (n.showingHint) return "";
        String text = nz(n.text);
        String shown = text.trim();
        if (typing != null && shown.equals(typing.trim())) return text;
        if (showingHint(n) || (placeholder != null && shown.equals(placeholder))) return "";
        return text;
    }

    /** 没名字的可点容器用子节点的文字当名字（深度 ≤ 6，合计 ≤ 60 字）。 */
    static String descendantLabel(UiNode n) {
        List<String> parts = new ArrayList<String>();
        walkLabel(n, 0, parts);
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(' ');
            b.append(p);
        }
        String j = b.toString();
        return j.length() > AGGREGATE_MAX ? j.substring(0, AGGREGATE_MAX) + "…" : j;
    }

    private static void walkLabel(UiNode c, int depth, List<String> parts) {
        if (depth > 6 || joinedLen(parts) > AGGREGATE_MAX) return;
        for (UiNode ch : c.children) {
            if (!ch.visible) continue;
            String l = ch.password ? null : ownLabel(ch);
            if (l != null && !parts.contains(l)) parts.add(l);
            walkLabel(ch, depth + 1, parts);
        }
    }

    private static int joinedLen(List<String> parts) {
        int n = 0;
        for (String p : parts) n += p.length() + 1;
        return n;
    }

    /** com.x:id/cart_icon → cart_icon；混淆过的短名认不出用途，不给。 */
    public static String readableResourceId(String id) {
        if (id == null) return null;
        int k = id.lastIndexOf('/');
        String s = k >= 0 ? id.substring(k + 1) : id;
        return RID_OK.matcher(s).matches() && RID_LETTERS.matcher(s).find() ? s : null;
    }

    static String states(UiNode n) {
        return "" + n.checked + n.selected + n.enabled + n.focused + n.textSelStart + ":" + n.textSelEnd;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static final long FNV_OFFSET = 0xcbf29ce484222325L;
    static final long FNV_PRIME = 0x100000001b3L;

    static long mix(long h0, Object... parts) {
        long h = h0;
        for (Object p : parts) {
            String s = p == null ? "\u0000" : p.toString();
            for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i)) * FNV_PRIME;
            h = (h ^ 0x1f) * FNV_PRIME;
        }
        return h;
    }

    /** 只算指纹（不建元素表）：动作前后比对用。 */
    public static long hash(List<UiWindow> windows, Options o) {
        long[] h = {FNV_OFFSET};
        for (UiWindow w : contentWindows(windows, o == null ? new Options() : o)) if (w.root != null) hashNode(w.root, 0, h);
        return h[0];
    }

    private static void hashNode(UiNode n, int depth, long[] h) {
        if (depth > MAX_DEPTH) return;
        if (n.visible) h[0] = mix(h[0], n.cls, n.password ? "" : n.text, n.desc, n.l + "," + n.t + "," + n.r + "," + n.b, states(n));
        for (UiNode c : n.children) hashNode(c, depth + 1, h);
    }
}
