package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ r2/fast-screen：给模型看的精简控件树（纯 Java，JVM 可测）。部分移植自 Mika（Apache-2.0，Copyright 2026 wangzan101，
 * core/uistate：元素表 + TreeQuality）：mika.screen 插件打开时，元素表、编号、质量判定、给模型的 JSON 都由
 * com.onesay.assistant.plug.screen.core（照 Mika 翻译的 Java 版）产出，见 {@link #mika}；插件关着时仍是这里原来的 Java 实现。
 *
 * <ul>
 *   <li>只留「有字 / 有描述 / 可点 / 可输入 / 可滚动 / 可勾选」的节点；没字的可点容器取子孙前两段文字当名字；</li>
 *   <li><b>稳定编号</b>：同一任务里，同一个元素（资源 id + 名字 + 类名 + 同键序号）换屏后编号不变（{@link Ids}），
 *       模型下一步可以直接引用上一屏看到的编号；</li>
 *   <li>{@link #render} 每行一个元素：{@code [12] 按钮「WLAN」#title 可点 @540,300}，带边界中心；</li>
 *   <li>{@link #quality}：树够不够用（够用就不截图）；</li>
 *   <li>{@link #diff}：动作前后的界面差异（新出现 / 消失 / 前台变化），动作结果直接带回给模型，省一次「再看一眼」。</li>
 * </ul>
 */
public final class UiTree {

    public static final class El {
        public int id;
        public String label = "";
        public String rid = "";
        public String cls = "";
        public int l, t, r, b;
        public boolean click, edit, scroll, check, checked, selected, focused, password, enabled = true;
        /** 稳定键（不含编号）。 */
        String key = "";
        /** ★ mika-plug：后端句柄（助手树里的节点下标）；-1 = 没有。 */
        public int src = -1;

        public int cx() {
            return (l + r) / 2;
        }

        public int cy() {
            return (t + b) / 2;
        }

        public String kind() {
            if (edit) return "输入框";
            if (check) return "开关";
            String c = cls.toLowerCase();
            if (c.contains("button")) return "按钮";
            if (c.contains("image")) return "图片";
            if (c.contains("tab")) return "标签";
            if (scroll && !click) return "列表";
            if (click) return "可点";
            return "文字";
        }
    }

    /** 跨屏编号表：一个任务一份。 */
    public static final class Ids {
        /** ★ mika-plug：mika.screen 开时的稳定编号器（RefStabilizer，一个任务一份）。 */
        public com.onesay.assistant.plug.screen.core.RefStabilizer mikaRefs;

        private final Map<String, Integer> mIds = new HashMap<String, Integer>();
        private int mNext = 1;

        int idFor(String key) {
            Integer v = mIds.get(key);
            if (v == null) {
                v = mNext++;
                mIds.put(key, v);
            }
            return v;
        }

        public int size() {
            return mIds.size();
        }
    }

    public String pkg = "";
    public String activity = "";
    public boolean ime;
    public boolean truncated;
    public long readMs;
    public int screenW = 1080, screenH = 2220;
    public final List<El> els = new ArrayList<El>();
    /** 树里一个字也没有的大块区域（地图 / 画布 / 读不到的网页）占屏比例。 */
    public float blindArea;

    // ------------------------------------------------------------------ ★ mika-plug（mika.screen 开时才有）

    /** 模型编号下的快照（Mika 元素表）；null = 插件关，走原来的元素表。 */
    public com.onesay.assistant.plug.screen.core.ScreenSnapshot mika;
    /** 给模型的屏幕 JSON（Mika phone_observe 形状，≤ 6000 字）。 */
    public String mikaJson;
    /** Mika TreeQuality 的结论（adequate / partial / unusable / protected）。 */
    public String mikaQuality;
    /** 编号跨屏稳定（mika.refs 开）：结构化差异才有意义。 */
    public boolean mikaStable;

    public El find(int id) {
        for (El e : els) if (e.id == id) return e;
        return null;
    }

    /** 按文字找（完全相等优先，其次包含）。 */
    public El findText(String text) {
        if (text == null || text.length() == 0) return null;
        El part = null;
        for (El e : els) {
            if (e.label.equals(text)) return e;
            if (part == null && e.label.contains(text)) part = e;
        }
        return part;
    }

    /** ★ fast-screen-v2：有没有「新出现」的元素含这段文字（before 里没有同名元素）。 */
    public boolean hasNewText(String text, UiTree before) {
        if (text == null || text.length() == 0) return false;
        if (before == null) return hasText(text);
        java.util.Set<String> was = new java.util.HashSet<String>();
        for (El e : before.els) was.add(e.label);
        for (El e : els) if (e.label.contains(text) && !was.contains(e.label)) return true;
        return false;
    }

    public boolean hasText(String text) {
        if (text == null || text.length() == 0) return false;
        for (El e : els) if (e.label.contains(text)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 构建

    public static UiTree from(FsTree t, Ids ids, int screenW, int screenH) {
        UiTree u = new UiTree();
        u.pkg = t.pkg;
        u.activity = t.activity;
        u.truncated = t.truncated;
        u.ime = t.ime;
        u.readMs = t.ms;
        u.screenW = screenW > 0 ? screenW : 1080;
        u.screenH = screenH > 0 ? screenH : 2220;
        int n = t.nodes.size();
        // 子节点表
        List<List<Integer>> kids = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) kids.add(new ArrayList<Integer>());
        for (int i = 0; i < n; i++) {
            int p = t.nodes.get(i).parent;
            if (p >= 0 && p < n) kids.get(p).add(i);
        }
        // 每个节点子树里是否有字
        boolean[] hasText = new boolean[n];
        for (int i = n - 1; i >= 0; i--) {
            FsTree.Node x = t.nodes.get(i);
            boolean h = x.text.trim().length() > 0 || x.desc.trim().length() > 0;
            for (int c : kids.get(i)) h |= hasText[c];
            hasText[i] = h;
        }
        Set<Integer> usedAsLabel = new HashSet<Integer>();
        Map<String, Integer> dup = new HashMap<String, Integer>();
        float screenArea = (float) u.screenW * u.screenH;
        float blind = 0f;
        for (int i = 0; i < n; i++) {
            FsTree.Node x = t.nodes.get(i);
            if (x.r <= x.l || x.b <= x.t) continue;
            if (x.r <= 0 || x.b <= 0 || x.l >= u.screenW || x.t >= u.screenH) continue;   // 屏外
            float area = (float) (x.r - x.l) * (x.b - x.t);
            if (!hasText[i] && area / screenArea >= 0.2f && namedInside(t, kids, i) < 3) blind = Math.max(blind, area / screenArea);
            boolean click = x.has(FsTree.F_CLICK) || x.has(FsTree.F_LONG);
            boolean edit = x.has(FsTree.F_EDIT);
            boolean scroll = x.has(FsTree.F_SCROLL);
            boolean check = x.has(FsTree.F_CHECKABLE);
            String own = joinLabel(x.text, x.desc);
            if (own.length() == 0 && !click && !edit && !scroll && !check) continue;
            if (own.length() > 0 && !click && !edit && !check && usedAsLabel.contains(i)) continue;   // 已并进可点父节点的名字
            String label = own;
            if (label.length() == 0 && (click || check)) label = descendantText(t, kids, i, usedAsLabel, 2);
            El e = new El();
            e.label = clip(label.replace('\n', ' ').trim(), 60);
            e.rid = shortRid(x.rid);
            e.cls = x.shortCls();
            e.l = x.l;
            e.t = x.t;
            e.r = x.r;
            e.b = x.b;
            e.click = click;
            e.edit = edit;
            e.scroll = scroll;
            e.check = check;
            e.checked = x.has(FsTree.F_CHECKED);
            e.selected = x.has(FsTree.F_SELECTED);
            e.focused = x.has(FsTree.F_FOCUSED);
            e.password = x.has(FsTree.F_PASSWORD);
            e.enabled = x.has(FsTree.F_ENABLED) || x.flags == 0;
            String base = e.rid + "|" + e.label + "|" + e.cls + "|" + (edit ? "E" : "") + (scroll ? "S" : "");
            Integer k = dup.get(base);
            int ord = k == null ? 0 : k + 1;
            dup.put(base, ord);
            e.key = base + "#" + ord;
            e.id = ids == null ? u.els.size() + 1 : ids.idFor(e.key);
            u.els.add(e);
        }
        u.blindArea = blind;
        return u;
    }

    private static int namedInside(FsTree t, List<List<Integer>> kids, int i) {
        int c = 0;
        List<Integer> stack = new ArrayList<Integer>(kids.get(i));
        while (!stack.isEmpty() && c < 3) {
            int k = stack.remove(stack.size() - 1);
            FsTree.Node x = t.nodes.get(k);
            if (x.text.trim().length() > 0 || x.desc.trim().length() > 0) c++;
            stack.addAll(kids.get(k));
        }
        return c;
    }

    private static String descendantText(FsTree t, List<List<Integer>> kids, int i, Set<Integer> used, int max) {
        List<String> parts = new ArrayList<String>();
        List<Integer> queue = new ArrayList<Integer>(kids.get(i));
        while (!queue.isEmpty() && parts.size() < max) {
            int k = queue.remove(0);
            FsTree.Node x = t.nodes.get(k);
            if (x.has(FsTree.F_CLICK) || x.has(FsTree.F_EDIT)) continue;   // 子控件自己会单独列出
            String s = joinLabel(x.text, x.desc);
            if (s.length() > 0 && !parts.contains(s)) {
                parts.add(s);
                used.add(k);
            }
            queue.addAll(kids.get(k));
        }
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.toString();
    }

    static String joinLabel(String text, String desc) {
        String a = text == null ? "" : text.trim(), d = desc == null ? "" : desc.trim();
        if (a.length() == 0) return d;
        if (d.length() == 0 || d.equals(a) || a.contains(d)) return a;
        return a + "（" + d + "）";
    }

    static String shortRid(String rid) {
        if (rid == null) return "";
        int k = rid.indexOf(":id/");
        return k >= 0 ? rid.substring(k + 4) : rid;
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ------------------------------------------------------------------ 质量

    public static final String Q_OK = "ok", Q_PARTIAL = "partial", Q_UNUSABLE = "unusable";

    /** 借鉴 Mika TreeAdequacy 的判法：有名字的元素少、控件大多没名字、或有大块无字区域 → 不够用（要截图 / 视觉补充）。 */
    public String quality() {
        if (mikaQuality != null) {   // ★ mika-plug：TreeQuality（去 OCR）
            if (com.onesay.assistant.plug.screen.core.TreeQuality.UNUSABLE.equals(mikaQuality)) return Q_UNUSABLE;
            if (com.onesay.assistant.plug.screen.core.TreeQuality.ADEQUATE.equals(mikaQuality)) return Q_OK;
            return Q_PARTIAL;
        }
        if (els.isEmpty()) return Q_UNUSABLE;
        int named = 0, controls = 0, namedControls = 0;
        for (El e : els) {
            if (e.label.length() > 0) named++;
            if (e.click || e.edit) {
                controls++;
                if (e.label.length() > 0) namedControls++;
            }
        }
        if (named == 0) return Q_UNUSABLE;
        if (controls >= 4 && namedControls * 3 <= controls) return Q_PARTIAL;
        if (blindArea >= 0.35f) return Q_PARTIAL;
        return Q_OK;
    }

    // ------------------------------------------------------------------ 输出

    /** 给模型看的文本（maxLines 行以内，超出的列表项只留前后）。 */
    public String render(int maxLines) {
        if (mikaJson != null) return "前台：" + (pkg.length() > 0 ? pkg : "?") + (activity.length() > 0 ? " / " + shortAct(activity) : "") + "\n" + mikaJson + "\n";
        StringBuilder b = new StringBuilder();
        b.append("前台：").append(pkg.length() > 0 ? pkg : "?");
        if (activity.length() > 0) b.append(" / ").append(shortAct(activity));
        if (ime) b.append(" · 输入法已弹出");
        String q = quality();
        if (!Q_OK.equals(q)) b.append(" · 控件树").append(Q_PARTIAL.equals(q) ? "不全（有画布/图片区域或控件没名字）" : "读不到内容");
        if (truncated) b.append(" · 树太大已截断");
        b.append('\n');
        int shown = 0;
        for (El e : els) {
            if (shown >= maxLines) {
                b.append("…（还有 ").append(els.size() - shown).append(" 个元素，可滚动查看）\n");
                break;
            }
            b.append(line(e)).append('\n');
            shown++;
        }
        return b.toString();
    }

    public static String line(El e) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(e.id).append("] ").append(e.kind());
        if (e.label.length() > 0) b.append("「").append(e.label).append("」");
        if (e.rid.length() > 0) b.append(" #").append(e.rid);
        if (e.check) b.append(e.checked ? " 已开" : " 已关");
        if (e.selected) b.append(" 选中");
        if (e.focused && e.edit) b.append(" 有焦点");
        if (e.password) b.append(" 密码框");
        if (!e.enabled) b.append(" 不可用");
        if (e.scroll) b.append(" 可滚动");
        b.append(" @").append(e.cx()).append(',').append(e.cy());
        return b.toString();
    }

    static String shortAct(String a) {
        int k = a.lastIndexOf('.');
        return k >= 0 ? a.substring(k + 1) : a;
    }

    /** 界面签名（无进展检测用）：前台 + 元素名字 + 勾选状态。 */
    public String signature() {
        if (mika != null) return Long.toHexString(mika.hash) + ":" + els.size();   // ★ mika-plug：读屏时一遍算出的指纹（含焦点 / 光标）
        StringBuilder b = new StringBuilder(pkg).append('/').append(activity).append('|');
        for (El e : els) {
            b.append(e.key);
            if (e.check) b.append(e.checked ? "+" : "-");
            if (e.edit) b.append('=').append(e.label);
            b.append(';');
        }
        return Integer.toHexString(b.toString().hashCode()) + ":" + els.size();
    }

    /**
     * ★ stable-screen：画面稳定判定用的指纹（结构 + 文字 + 位置 + 状态，不含编号）。和 {@link #signature} 不同：
     * 这里带边界，转场 / 滑入动画里元素在动也算没稳定；编号不进指纹，同一画面重读两次指纹一定相同。
     */
    public String settleFingerprint() {
        long h = 0xcbf29ce484222325L;
        h = fnv(h, pkg);
        h = fnv(h, activity);
        h = fnv(h, ime ? "I" : "-");
        for (El e : els) {
            h = fnv(h, e.cls);
            h = fnv(h, e.rid);
            h = fnv(h, e.label);
            h = fnv(h, e.l + "," + e.t + "," + e.r + "," + e.b);
            h = fnv(h, (e.click ? "c" : "") + (e.edit ? "e" : "") + (e.scroll ? "s" : "") + (e.check ? (e.checked ? "+" : "-") : "")
                    + (e.selected ? "S" : "") + (e.focused ? "F" : "") + (e.enabled ? "" : "x"));
        }
        return Long.toHexString(h) + ":" + els.size();
    }

    /**
     * ★ smart-replay：回放指纹用的结构记号。和 {@link #settleFingerprint} 正好相反，这里<b>只看结构与资源 id</b>：
     * 有资源 id 的元素记「id + 类名」，没有的记「类名 + 种类」，去重（列表多几行 / 少几行不影响），
     * 不看文字（时间、角标数字、列表内容、输入框里的字都会变）、不看位置、不看勾选 / 焦点状态、不看元素个数。
     * 记号是 8 位哈希（存储里不出现界面文字）。
     */
    public static String replayToken(El e) {
        String raw = e.rid.length() > 0 ? "r:" + e.rid + "/" + e.cls
                : "c:" + e.cls + "/" + (e.click ? "k" : "") + (e.edit ? "e" : "") + (e.scroll ? "s" : "") + (e.check ? "c" : "");
        long h = fnv(0xcbf29ce484222325L, raw);
        return String.format("%08x", (int) (h ^ (h >>> 32)));
    }

    /** 一屏的结构记号集合（有资源 id 的优先，最多 max 个，按哈希排序后截断，同一屏每次结果相同）。 */
    public java.util.List<String> replayTokens(int max) {
        java.util.TreeSet<String> rid = new java.util.TreeSet<String>(), cls = new java.util.TreeSet<String>();
        for (El e : els) (e.rid.length() > 0 ? rid : cls).add(replayToken(e));
        java.util.List<String> out = new ArrayList<String>();
        for (String s : rid) if (out.size() < max) out.add(s);
        for (String s : cls) if (out.size() < max) out.add(s);
        return out;
    }

    /** ★ smart-replay：回放指纹（前台 + 页面 + 结构记号），诊断 / trace 用；回放时按记号集合的相似度比，不要求完全相同。 */
    public String replayFingerprint() {
        long h = 0xcbf29ce484222325L;
        h = fnv(h, pkg);
        h = fnv(h, activity);
        for (String s : replayTokens(48)) h = fnv(h, s);
        return Long.toHexString(h);
    }

    private static long fnv(long h, String s) {
        String v = s == null ? "" : s;
        for (int i = 0; i < v.length(); i++) {
            h ^= v.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= 0x1f;   // 字段分隔
        h *= 0x100000001b3L;
        return h;
    }

    /** 动作前后的差异（给模型，≤ maxItems 项）。 */
    public static String diff(UiTree before, UiTree after, int maxItems) {
        if (after == null) return "（动作后读不到界面）";
        if (before == null) return "（新界面）";
        if (before.mika != null && after.mika != null && after.mikaJson != null && after.mikaStable)   // ★ mika-plug：结构化差异（InterfaceUpdate）
            return com.onesay.assistant.plug.screen.core.InterfaceUpdate.between(before.mika, after.mika, true, 0, com.onesay.assistant.plug.screen.core.ScreenJson.DEFAULT_MAX_CHARS).toString();
        StringBuilder b = new StringBuilder();
        if (!before.pkg.equals(after.pkg)) b.append("前台从 ").append(before.pkg).append(" 变成 ").append(after.pkg).append("；");
        else if (!before.activity.equals(after.activity) && after.activity.length() > 0)
            b.append("换到页面 ").append(shortAct(after.activity)).append("；");
        if (before.ime != after.ime) b.append(after.ime ? "输入法弹出；" : "输入法收起；");
        Set<String> was = new LinkedHashSet<String>(), now = new LinkedHashSet<String>();
        for (El e : before.els) if (e.label.length() > 0) was.add(e.label + (e.check ? (e.checked ? "(开)" : "(关)") : ""));
        for (El e : after.els) if (e.label.length() > 0) now.add(e.label + (e.check ? (e.checked ? "(开)" : "(关)") : ""));
        List<String> added = new ArrayList<String>(), gone = new ArrayList<String>();
        for (String s : now) if (!was.contains(s)) added.add(s);
        for (String s : was) if (!now.contains(s)) gone.add(s);
        if (added.isEmpty() && gone.isEmpty() && b.length() == 0) return "界面没有变化";
        if (!added.isEmpty()) b.append("新出现：").append(joinN(added, maxItems)).append("；");
        if (!gone.isEmpty()) b.append("消失：").append(joinN(gone, maxItems)).append("；");
        return b.toString();
    }

    static String joinN(List<String> l, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < n; i++) {
            if (i > 0) b.append("、");
            b.append(clip(l.get(i), 24));
        }
        if (l.size() > n) b.append(" 等 ").append(l.size()).append(" 项");
        return b.toString();
    }
}
