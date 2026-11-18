package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.fastscreen.FsTree;

/**
 * ★ plan-1118（真机 Flyme 计算器 g02）：无障碍兜底下点按的「点了有没有反应」判定。纯 Java，JVM 可测。
 * <ul>
 *   <li>Flyme 计算器的按键 ACTION_CLICK 返回 true，界面却一点没变（自绘按键只认触摸）→ 以前就当点成功，连续 3 轮「界面没变化」卡死；</li>
 *   <li>现在：节点点击后等界面安静、读树比指纹，没变（再等一小会儿复核一次）→ 在按键中心补一次 dispatchGesture 坐标点按。</li>
 * </ul>
 * 指纹只看用户看得见的东西（类名、文字、描述、范围、勾选 / 选中 / 焦点），与 ScreenReader 的 hash 口径一致；
 * 自家窗口在读树时已经去掉。
 */
public final class TapVerify {
    private TapVerify() {}

    static final long FNV_OFFSET = 0xcbf29ce484222325L;
    static final long FNV_PRIME = 0x100000001b3L;

    /** 节点点击后最多等多久界面安静（ms）。 */
    public static final int SETTLE_MAX_MS = 700;
    /** 第一次没变化时再等这么久复核，免得慢一点的反应被补点成两下。 */
    public static final int RECHECK_MS = 250;

    /** 树指纹；null → 0（读不到时不判，调用方据此不补点）。 */
    public static long sig(FsTree t) {
        if (t == null) return 0L;
        long h = FNV_OFFSET;
        for (FsTree.Node n : t.nodes) {
            if (n == null) continue;
            h = mix(h, n.cls);
            h = mix(h, n.has(FsTree.F_PASSWORD) ? "" : n.text);
            h = mix(h, n.desc);
            h = mix(h, n.l + "," + n.t + "," + n.r + "," + n.b);
            h = mix(h, Integer.toString(n.flags & (FsTree.F_CHECKED | FsTree.F_SELECTED | FsTree.F_FOCUSED | FsTree.F_ENABLED)));
        }
        h = mix(h, t.pkg);
        h = mix(h, t.ime ? "ime" : "");
        return h == 0L ? 1L : h;
    }

    private static long mix(long h0, String s) {
        long h = h0;
        if (s != null) for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i)) * FNV_PRIME;
        return (h ^ 0x1f) * FNV_PRIME;
    }

    /**
     * 要不要补一次坐标点按：节点点击报成功（how = a11y_click），前后两次都读到了树，而且指纹没变。
     * 读不到（0）一律不补 —— 宁可让下一轮模型看，也不冒点两下的险。
     */
    public static boolean needGestureRetry(String how, long before, long after) {
        if (!com.onesay.assistant.plug.action.core.ActionChain.A11Y.equals(how)) return false;
        return before != 0L && after != 0L && before == after;
    }

    /** 点按时护罩开孔的范围：元素范围外扩 pad，最小 64×64，夹在屏幕内。返回 [l,t,r,b]。 */
    public static int[] holeFor(int l, int t, int r, int b, int screenW, int screenH, int pad) {
        int cx = (l + r) / 2, cy = (t + b) / 2;
        int hl = Math.min(l - pad, cx - 32), ht = Math.min(t - pad, cy - 32), hr = Math.max(r + pad, cx + 32), hb = Math.max(b + pad, cy + 32);
        if (screenW > 0) {
            hl = Math.max(0, hl);
            hr = Math.min(screenW, hr);
        }
        if (screenH > 0) {
            ht = Math.max(0, ht);
            hb = Math.min(screenH, hb);
        }
        return new int[]{hl, ht, hr, hb};
    }
}
