package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.phone.UiTree;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ a11y-labels（社区借鉴 · 定时步骤按标签找控件）：快捷指令 / 定时步骤里「点哪个控件」的存法与运行时查找（纯 Java，JVM 全测）。
 *
 * <p>思路借鉴 DroidBridge（https://github.com/zephyr7030/DroidBridge ，Apache-2.0）：步骤里存「按文字 / 描述 / resource-id 找」，
 * 运行时对着当前屏幕重新找控件，<b>不存死坐标</b>。代码按思路自己写，没有复制其源码。
 *
 * <h3>存法（步骤 args）</h3>
 * <ul>
 *   <li>新格式：{@code {"target":"发送"}}（默认按文字，含描述 / 推断名）、{@code {"target":"发送","by":"desc"}}、
 *       {@code {"target":"btn_send","by":"rid"}}，可选 {@code "role":"按钮"}、{@code "exact":true}、{@code "app":"包名"}；</li>
 *   <li>旧格式兼容：{@code {"x":500,"y":900}}（0–999 坐标）。运行时<b>不按坐标盲点</b>：找包含这个点的最小可点控件，
 *       找到就点它并给出迁移后的新格式（{@link Match#migrated}），找不到就失败。</li>
 * </ul>
 *
 * <h3>匹配</h3>
 * 只在真实可点（可点 / 可勾选 / 可输入、启用）的元素里找；先完全相等，没有再包含；多个候选取阅读顺序第一个并在
 * {@link Match#candidates} 里给出个数（调用方可据此要求更精确）。
 */
public final class LabelSelector {

    public static final String BY_TEXT = "text", BY_DESC = "desc", BY_RID = "rid";

    public final String by, value, role, app;
    public final boolean exact;
    /** 旧格式坐标（0–999）；新格式为 -1。 */
    public final int x999, y999;

    private LabelSelector(String by, String value, String role, String app, boolean exact, int x, int y) {
        this.by = by;
        this.value = value == null ? "" : value.trim();
        this.role = role == null ? "" : role.trim();
        this.app = app == null ? "" : app.trim();
        this.exact = exact;
        this.x999 = x;
        this.y999 = y;
    }

    public static LabelSelector of(String by, String value) {
        return new LabelSelector(normBy(by), value, "", "", false, -1, -1);
    }

    public boolean legacy() {
        return x999 >= 0 && y999 >= 0 && value.length() == 0;
    }

    static String normBy(String by) {
        String b = by == null ? "" : by.trim().toLowerCase(java.util.Locale.ROOT);
        if (b.equals("desc") || b.equals("description") || b.equals("content-desc") || b.equals("描述")) return BY_DESC;
        if (b.equals("rid") || b.equals("id") || b.equals("resource-id") || b.equals("resourceid") || b.equals("资源id")) return BY_RID;
        return BY_TEXT;
    }

    /** 从步骤参数解析；什么都没有 → null。 */
    public static LabelSelector fromArgs(JSONObject a) {
        if (a == null) return null;
        String v = a.optString("target", "").trim();
        String by = a.optString("by", "");
        if (v.length() == 0) {   // 旧写法：text / desc / rid 直接当键
            if (a.optString("rid", "").trim().length() > 0) {
                v = a.optString("rid", "");
                by = BY_RID;
            } else if (a.optString("desc", "").trim().length() > 0) {
                v = a.optString("desc", "");
                by = BY_DESC;
            } else if (a.optString("text", "").trim().length() > 0) {
                v = a.optString("text", "");
                by = BY_TEXT;
            }
        }
        int x = a.has("x") ? a.optInt("x", -1) : -1, y = a.has("y") ? a.optInt("y", -1) : -1;
        if (v.length() == 0 && (x < 0 || y < 0 || x > 999 || y > 999)) return null;
        return new LabelSelector(normBy(by), v, a.optString("role", ""), a.optString("app", ""), a.optBoolean("exact", false),
                v.length() == 0 ? x : -1, v.length() == 0 ? y : -1);
    }

    public JSONObject toArgs() {
        JSONObject o = new JSONObject();
        try {
            if (legacy()) {
                o.put("x", x999);
                o.put("y", y999);
            } else {
                o.put("target", value);
                if (!BY_TEXT.equals(by)) o.put("by", by);
                if (role.length() > 0) o.put("role", role);
                if (exact) o.put("exact", true);
            }
            if (app.length() > 0) o.put("app", app);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override public String toString() {
        if (legacy()) return "旧坐标(" + x999 + "," + y999 + ")";
        return (BY_RID.equals(by) ? "#" : BY_DESC.equals(by) ? "描述「" : "「") + value + (BY_RID.equals(by) ? "" : "」") + (role.length() > 0 ? role : "");
    }

    /** 查找结果。 */
    public static final class Match {
        public final UiTree.El el;
        public final int candidates;
        public final String error;
        /** 旧坐标命中后建议改存的新格式（新格式本身为 null）。 */
        public final LabelSelector migrated;

        Match(UiTree.El el, int candidates, String error, LabelSelector migrated) {
            this.el = el;
            this.candidates = candidates;
            this.error = error;
            this.migrated = migrated;
        }

        public boolean ok() {
            return el != null;
        }
    }

    public Match find(UiTree u) {
        if (u == null) return new Match(null, 0, "读不到当前界面", null);
        if (app.length() > 0 && !app.equals(u.pkg)) return new Match(null, 0, "前台不是 " + app + "（现在是 " + u.pkg + "）", null);
        if (legacy()) return findLegacy(u);
        List<UiTree.El> exactL = new ArrayList<UiTree.El>(), partL = new ArrayList<UiTree.El>();
        for (UiTree.El e : A11yLabeler.readingOrder(u.els)) {
            if (!ButtonNumbers.realTarget(e)) continue;
            if (role.length() > 0 && !role.equals(e.role)) continue;
            String hay = hay(e);
            if (hay == null) continue;
            if (hay.equals(value)) exactL.add(e);
            else if (!exact && hay.contains(value)) partL.add(e);
        }
        List<UiTree.El> c = exactL.isEmpty() ? partL : exactL;
        if (c.isEmpty()) return new Match(null, 0, "屏幕上没找到 " + this, null);
        return new Match(c.get(0), c.size(), null, null);
    }

    private String hay(UiTree.El e) {
        if (BY_RID.equals(by)) {
            String v = value.indexOf(":id/") >= 0 ? value.substring(value.indexOf(":id/") + 4) : value;
            return e.rid.equals(v) ? value : null;
        }
        if (BY_DESC.equals(by)) return A11yLabeler.SRC_DESC.equals(e.nameSrc) ? e.label : null;
        return e.label;   // 文字：屏上文字、子项文字、描述、推断名都算（用户说的就是看到 / 听到的名字）
    }

    private Match findLegacy(UiTree u) {
        int px = com.onesay.assistant.plug.screen.core.UiElement.denormalize(x999, u.screenW), py = com.onesay.assistant.plug.screen.core.UiElement.denormalize(y999, u.screenH);
        UiTree.El best = null;
        long bestArea = Long.MAX_VALUE;
        for (UiTree.El e : u.els) {
            if (!ButtonNumbers.realTarget(e)) continue;
            if (px < e.l || px >= e.r || py < e.t || py >= e.b) continue;
            long area = (long) (e.r - e.l) * (e.b - e.t);
            if (area < bestArea) {
                best = e;
                bestArea = area;
            }
        }
        if (best == null) return new Match(null, 0, "旧坐标 (" + x999 + "," + y999 + ") 那里现在没有可点的控件，没有按坐标盲点", null);
        LabelSelector mig;
        if (best.rid.length() > 0) mig = new LabelSelector(BY_RID, best.rid, best.role, app, true, -1, -1);
        else if (best.label.length() > 0) mig = new LabelSelector(A11yLabeler.SRC_DESC.equals(best.nameSrc) ? BY_DESC : BY_TEXT, best.label, best.role, app, true, -1, -1);
        else mig = null;
        return new Match(best, 1, null, mig);
    }
}
