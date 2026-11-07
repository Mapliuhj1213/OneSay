/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/RefStabilizer.kt) to Java 7; window-tree ref rewriting
 * dropped (OneSay sends elements only). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨读屏的稳定元素编号。端口编号只在一次读屏内有效；这里让相邻两屏里没变的元素保持同一个模型编号，
 * 动作时再翻译回端口编号。沿用条件：键（角色 + 名字 + 资源 ID）在新旧两屏都唯一、前台 App 没换、relocate 判为同一个；
 * 列表里的元素还要页面没动、它还在原地。没名字也没资源 ID 的元素永远拿新编号。编号任务内单调递增，不复用。
 */
public final class RefStabilizer {
    private static final int KEEP_CENTERS = 20;

    /** 给模型看的一屏：elements 的 ref 已换成模型编号；toPort = 模型编号 → 端口编号。 */
    public static final class ModelSnapshot {
        public final String snapshotId;
        public final ScreenSnapshot port;
        public final ScreenSnapshot screen;
        public final Map<String, String> toPort;

        ModelSnapshot(String id, ScreenSnapshot port, List<UiElement> els, Map<String, String> toPort) {
            this.snapshotId = id;
            this.port = port;
            this.screen = port.copyWith(id, els);
            this.toPort = toPort;
        }
    }

    /** resolve 的结果：portRef 为 null = 过期。 */
    public static final class Resolved {
        public final String portRef, portSnapshotId;
        public final boolean relocated;

        Resolved(String portRef, String portSnapshotId, boolean relocated) {
            this.portRef = portRef;
            this.portSnapshotId = portSnapshotId;
            this.relocated = relocated;
        }

        public boolean stale() {
            return portRef == null;
        }
    }

    static final Resolved STALE = new Resolved(null, null, false);

    private final String prefix;
    private int nextRef = 1, count;
    private ModelSnapshot latest;
    private Map<String, Integer> born = new HashMap<String, Integer>();
    private final LinkedHashMap<Integer, Map<String, int[]>> centers = new LinkedHashMap<Integer, Map<String, int[]>>();

    public RefStabilizer(String prefix) {
        this.prefix = prefix == null || prefix.length() == 0 ? "s" : prefix;
    }

    public ModelSnapshot latest() {
        return latest;
    }

    public synchronized ModelSnapshot next(ScreenSnapshot port) {
        int n = ++count;
        ModelSnapshot prev = latest != null && eq(latest.port.packageName, port.packageName) ? latest : null;
        int shortSide = Math.min(port.width, port.height);
        List<ElementLocator> before = prev == null ? new ArrayList<ElementLocator>() : locators(prev.screen);
        List<ElementLocator> after = locators(port);
        boolean still = Accuracy.stationary(before, after, shortSide * Accuracy.SAME_SPOT_SHIFT);
        Map<String, List<Integer>> prevByKey = new HashMap<String, List<Integer>>();
        if (prev != null) {
            for (int j = 0; j < prev.screen.elements.size(); j++) {
                String k = key(prev.screen.elements.get(j));
                List<Integer> l = prevByKey.get(k);
                if (l == null) prevByKey.put(k, l = new ArrayList<Integer>());
                l.add(j);
            }
        }
        Map<String, Integer> newCount = new HashMap<String, Integer>();
        for (UiElement e : port.elements) {
            Integer c = newCount.get(key(e));
            newCount.put(key(e), c == null ? 1 : c + 1);
        }
        Map<String, String> toPort = new LinkedHashMap<String, String>();
        Map<String, Integer> bornNow = new HashMap<String, Integer>();
        List<UiElement> els = new ArrayList<UiElement>();
        for (int i = 0; i < port.elements.size(); i++) {
            UiElement e = port.elements.get(i);
            String k = key(e);
            String ref = null;
            List<Integer> olds = prevByKey.get(k);
            if (olds != null && olds.size() == 1 && identifiable(e) && newCount.get(k) == 1) {
                int j = olds.get(0);
                boolean same = Accuracy.relocate(before.get(j), before, after, shortSide) == i;
                boolean listOk = !(before.get(j).inList || after.get(i).inList)
                        || (still && Accuracy.shift(before.get(j), after.get(i)) <= shortSide * Accuracy.SAME_SPOT_SHIFT);
                if (same && listOk) ref = prev.screen.elements.get(j).ref;
            }
            if (ref == null) ref = "@e" + (nextRef++);
            Integer b = born.get(ref);
            bornNow.put(ref, b == null ? n : b);
            toPort.put(ref, e.ref);
            els.add(e.withRef(ref));
        }
        born = bornNow;
        Map<String, int[]> c = new HashMap<String, int[]>();
        for (UiElement e : els) c.put(e.ref, new int[]{e.centerX, e.centerY});
        centers.put(n, c);
        while (centers.size() > KEEP_CENTERS) {
            Iterator<Integer> it = centers.keySet().iterator();
            it.next();
            it.remove();
        }
        latest = new ModelSnapshot(prefix + n, port, els, toPort);
        return latest;
    }

    public synchronized Resolved resolve(String ref, String snapshotId) {
        ModelSnapshot cur = latest;
        if (cur == null || ref == null) return STALE;
        int n;
        if (snapshotId == null || snapshotId.length() == 0) n = count;   // 没给 snapshotId：按最新一屏
        else {
            if (!snapshotId.startsWith(prefix)) return STALE;
            try {
                n = Integer.parseInt(snapshotId.substring(prefix.length()));
            } catch (NumberFormatException e) {
                return STALE;
            }
        }
        if (n < 1 || n > count) return STALE;
        Integer from = born.get(ref);
        if (from == null || from > n) return STALE;
        String portRef = cur.toPort.get(ref);
        if (portRef == null) return STALE;
        if (n != count) {
            Map<String, int[]> thenM = centers.get(n), nowM = centers.get(count);
            int[] then = thenM == null ? null : thenM.get(ref), now = nowM == null ? null : nowM.get(ref);
            if (then == null || now == null) return STALE;
            double limit = Math.min(cur.port.width, cur.port.height) * Accuracy.RELOCATE_MAX_SHIFT;
            if (Math.hypot(then[0] - now[0], then[1] - now[1]) > limit) return STALE;
        }
        return new Resolved(portRef, cur.port.snapshotId, n != count);
    }

    private static boolean identifiable(UiElement e) {
        return e.name.trim().length() > 0 || (e.resourceId != null && e.resourceId.trim().length() > 0);
    }

    private static String key(UiElement e) {
        return e.role + "\u0000" + e.name + "\u0000" + (e.resourceId == null ? "" : e.resourceId);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 中心落在别的可滚动元素范围里的算在列表里（快照里没有父子关系，按范围推断）。 */
    static List<ElementLocator> locators(ScreenSnapshot s) {
        List<UiElement> panes = new ArrayList<UiElement>();
        for (UiElement e : s.elements) if (e.scrollable && e.bounds.length == 4) panes.add(e);
        List<ElementLocator> out = new ArrayList<ElementLocator>();
        String last = null;
        for (UiElement e : s.elements) {
            int x = UiElement.normalize(e.centerX, s.width), y = UiElement.normalize(e.centerY, s.height);
            boolean inList = e.inList;
            for (UiElement p : panes) {
                if (p != e && x >= p.bounds[0] && x <= p.bounds[2] && y >= p.bounds[1] && y <= p.bounds[3]) inList = true;
            }
            out.add(new ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList));
            if (e.name.trim().length() > 0) last = e.name;
        }
        return out;
    }
}
