package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.phone.UiTree;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * ★ a11y-labels：贴了标签的屏幕怎么给模型看 / 怎么记（纯 Java）。
 *
 * <p>给模型的紧凑格式（控制 token）：第一行「前台：包名 / Activity · 快照 S3」，之后一行一个元素：
 * {@code [12] 按钮「返回」推断 @60,140}。
 * <ul>
 *   <li>角色是精准角色（{@link A11yLabeler#role}）；按钮不再写「可点」；</li>
 *   <li>名字后面标「推断」= 屏幕上没有这几个字，是按内容描述 / 资源 id 得到的；标「提示」= 输入框里显示的是提示语、不是已输入的内容；</li>
 *   <li>资源 id 只在没名字或是输入框时写（省 token）；</li>
 *   <li>节点键不给模型（动作由代码按快照 + 键绑定，模型照常写编号）。</li>
 * </ul>
 * 日志只用 {@link #logLine}：键、角色、序号、状态、边界，不含任何文字。
 */
public final class LabelRender {

    private LabelRender() {
    }

    public static String render(UiTree u, int maxLines) {
        StringBuilder b = new StringBuilder();
        b.append("前台：").append(u.pkg.length() > 0 ? u.pkg : "?");
        if (u.activity.length() > 0) b.append(" / ").append(shortAct(u.activity));
        b.append(" · 快照 ").append(u.snapshotId);
        if (u.ime) b.append(" · 输入法已弹出");
        String q = u.quality();
        if (!UiTree.Q_OK.equals(q)) b.append(" · 控件树").append(UiTree.Q_PARTIAL.equals(q) ? "不全（有画布/图片区域或控件没名字）" : "读不到内容");
        if (u.truncated) b.append(" · 树太大已截断");
        b.append('\n');
        int shown = 0;
        for (UiTree.El e : u.els) {
            if (shown >= maxLines) {
                b.append("…（还有 ").append(u.els.size() - shown).append(" 个元素，可滚动查看）\n");
                break;
            }
            b.append(line(e)).append('\n');
            shown++;
        }
        return b.toString();
    }

    public static String line(UiTree.El e) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(e.id).append("] ").append(e.role.length() > 0 ? e.role : e.kind());
        if (e.label.length() > 0) {
            b.append("「").append(e.label).append("」");
            if (A11yLabeler.SRC_RID.equals(e.nameSrc) || A11yLabeler.SRC_DESC.equals(e.nameSrc)) b.append("推断");
            else if (A11yLabeler.SRC_HINT.equals(e.nameSrc)) b.append("提示");
        }
        if (e.rid.length() > 0 && (e.label.length() == 0 || e.edit)) b.append(" #").append(e.rid);
        if (A11yLabeler.R_CHECK.equals(e.role) || A11yLabeler.R_RADIO.equals(e.role)) b.append(e.checked ? " 已勾选" : " 未勾选");
        else if (e.check) b.append(e.checked ? " 已开" : " 已关");
        if (e.selected) b.append(" 选中");
        if (e.focused && e.edit) b.append(" 有焦点");
        if (e.password) b.append(" 密码框");
        if (!e.enabled) b.append(" 不可用");
        if (e.scroll && !A11yLabeler.R_LIST.equals(e.role) && !A11yLabeler.R_SCROLL.equals(e.role)) b.append(" 可滚动");
        b.append(" @").append(e.cx()).append(',').append(e.cy());
        return b.toString();
    }

    /** 日志用：没有任何文字（名字、资源 id 都不写），只有快照 / 键 / 角色 / 序号 / 状态 / 边界。 */
    public static String logLine(UiTree u, UiTree.El e) {
        return (u == null ? "?" : u.snapshotId) + " " + e.nodeKey + (e.weakKey ? "(弱)" : "") + " " + e.role + (e.ord > 0 ? " №" + e.ord : "")
                + " " + flags(e) + " [" + e.l + "," + e.t + "," + e.r + "," + e.b + "]";
    }

    static String flags(UiTree.El e) {
        return (e.click ? "c" : "-") + (e.edit ? "e" : "-") + (e.scroll ? "s" : "-") + (e.check ? (e.checked ? "+" : "o") : "-") + (e.enabled ? "" : "x");
    }

    /**
     * 一条完整的标签记录（报告 / 调试导出用）。名字原样取自元素（读屏时已按原口径打码；发给模型前另有脱敏），
     * 调用方要写日志时必须先过脱敏，或者直接用 {@link #logLine}。
     */
    public static JSONObject record(UiTree u, UiTree.El e) {
        JSONObject o = new JSONObject();
        try {
            o.put("snapshot", u.snapshotId);
            o.put("pkg", u.pkg);
            o.put("activity", u.activity);
            o.put("key", e.nodeKey);
            if (e.weakKey) o.put("weak", true);
            o.put("role", e.role);
            o.put("name", e.label);
            o.put("nameSrc", e.nameSrc);
            if (e.rid.length() > 0) o.put("rid", e.rid);
            o.put("cls", e.cls);
            o.put("clickable", e.click);
            o.put("editable", e.edit);
            o.put("scrollable", e.scroll);
            if (e.check) o.put("checked", e.checked);
            o.put("enabled", e.enabled);
            o.put("bounds", new JSONArray().put(e.l).put(e.t).put(e.r).put(e.b));
            if (e.ord > 0) o.put("ord", e.ord);
            o.put("id", e.id);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static String shortAct(String a) {
        int k = a.lastIndexOf('.');
        return k >= 0 ? a.substring(k + 1) : a;
    }
}
