package com.onesay.assistant.v2.fastscreen;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ r2/fast-screen：助手读出的控件树（纯 Java，JVM 可测）。
 *
 * <p>助手侧把 AccessibilityNodeInfo 前序遍历成 {@link Node} 列表（不可见子树整枝剪掉，节点数和总耗时有上限），
 * 线上格式是紧凑 JSON：{@code {"pkg":…,"ms":…,"n":[[parent,cls,text,desc,rid,l,t,r,b,flags,childIdx],…]}}。
 * App 侧再转成 uiautomator 兼容 XML（{@link #toUiautomatorXml}，给既有 UiDumpParser / ImageNodeSelector / 区域选择），
 * 或交给 phone/UiTree 编号给模型看。
 */
public final class FsTree {

    public static final int F_CLICK = 1, F_LONG = 2, F_EDIT = 4, F_SCROLL = 8, F_CHECKABLE = 16, F_CHECKED = 32,
            F_SELECTED = 64, F_FOCUSED = 128, F_ENABLED = 256, F_PASSWORD = 512, F_FOCUSABLE = 1024, F_VISIBLE = 2048,
            /** ★ mika-plug：显示的是提示语（isShowingHintText，API 26）/ 列表容器（collectionInfo 或 *ListView/RecyclerView/GridView）。 */
            F_HINT_SHOWING = 4096, F_LIST = 8192;

    public static final class Node {
        public int parent = -1;
        public int childIdx;
        public String cls = "";
        public String text = "";
        public String desc = "";
        public String rid = "";
        public int l, t, r, b;
        public int flags;
        /** ★ mika-plug：hintText（API 26）；所在窗口在 {@link #windows} 里的下标（旧助手不发 = 0）。 */
        public String hint = "";
        public int win;

        public boolean has(int f) {
            return (flags & f) != 0;
        }

        public int cx() {
            return (l + r) / 2;
        }

        public int cy() {
            return (t + b) / 2;
        }

        public boolean contains(int x, int y) {
            return x >= l && x < r && y >= t && y < b;
        }

        public String shortCls() {
            int i = cls.lastIndexOf('.');
            return i >= 0 ? cls.substring(i + 1) : cls;
        }
    }

    public String pkg = "";
    public String activity = "";
    /** 助手侧读树耗时（ms）。 */
    public long ms;
    /** 是否因节点数 / 时间上限被截断。 */
    public boolean truncated;
    /** 输入法窗口是否在屏上。 */
    public boolean ime;
    public final List<Node> nodes = new ArrayList<Node>();

    /** ★ mika-plug：多窗口读法时的窗口表（从上到下）。旧助手 / 单窗口读法为空。 */
    public static final class Win {
        public int id, layer;
        public String type = "application", pkg = "";
    }

    public final List<Win> windows = new ArrayList<Win>();

    public String toJson() {
        StringBuilder sb = new StringBuilder(nodes.size() * 64 + 64);
        sb.append("{\"pkg\":").append(JSONObjectQuote.q(pkg)).append(",\"act\":").append(JSONObjectQuote.q(activity))
                .append(",\"ms\":").append(ms).append(",\"tr\":").append(truncated ? 1 : 0).append(",\"ime\":").append(ime ? 1 : 0).append(",\"n\":[");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(n.parent).append(',').append(JSONObjectQuote.q(n.cls)).append(',').append(JSONObjectQuote.q(n.text))
                    .append(',').append(JSONObjectQuote.q(n.desc)).append(',').append(JSONObjectQuote.q(n.rid)).append(',')
                    .append(n.l).append(',').append(n.t).append(',').append(n.r).append(',').append(n.b).append(',')
                    .append(n.flags).append(',').append(n.childIdx);
            if (n.hint.length() > 0 || n.win != 0) sb.append(',').append(JSONObjectQuote.q(n.hint)).append(',').append(n.win);
            sb.append(']');
        }
        sb.append(']');
        if (!windows.isEmpty()) {
            sb.append(",\"w\":[");
            for (int i = 0; i < windows.size(); i++) {
                Win w = windows.get(i);
                if (i > 0) sb.append(',');
                sb.append('[').append(w.id).append(',').append(JSONObjectQuote.q(w.type)).append(',').append(w.layer).append(',').append(JSONObjectQuote.q(w.pkg)).append(']');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    public static FsTree fromJson(String s) throws JSONException {
        org.json.JSONObject o = new org.json.JSONObject(s);
        FsTree t = new FsTree();
        t.pkg = o.optString("pkg", "");
        t.activity = o.optString("act", "");
        t.ms = o.optLong("ms", 0);
        t.truncated = o.optInt("tr", 0) == 1;
        t.ime = o.optInt("ime", 0) == 1;
        JSONArray a = o.optJSONArray("n");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.getJSONArray(i);
                Node n = new Node();
                n.parent = r.getInt(0);
                n.cls = r.optString(1, "");
                n.text = r.optString(2, "");
                n.desc = r.optString(3, "");
                n.rid = r.optString(4, "");
                n.l = r.getInt(5);
                n.t = r.getInt(6);
                n.r = r.getInt(7);
                n.b = r.getInt(8);
                n.flags = r.getInt(9);
                n.childIdx = r.optInt(10, 0);
                n.hint = r.optString(11, "");
                n.win = r.optInt(12, 0);
                t.nodes.add(n);
            }
        }
        JSONArray ws = o.optJSONArray("w");
        if (ws != null) {
            for (int i = 0; i < ws.length(); i++) {
                JSONArray r = ws.optJSONArray(i);
                if (r == null) continue;
                Win w = new Win();
                w.id = r.optInt(0, i);
                w.type = r.optString(1, "application");
                w.layer = r.optInt(2, 0);
                w.pkg = r.optString(3, "");
                t.windows.add(w);
            }
        }
        return t;
    }

    /**
     * uiautomator dump 兼容 XML（属性名、bounds 写法与 uiautomator 一致）。
     * @param compressed true = 像 --compressed 一样去掉「无文字、无描述、无 id、不可交互」的纯布局节点（子节点上提到最近的保留祖先）
     */
    public String toUiautomatorXml(boolean compressed) {
        int n = nodes.size();
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            Node x = nodes.get(i);
            keep[i] = !compressed || x.parent < 0 || x.text.length() > 0 || x.desc.length() > 0 || x.rid.length() > 0
                    || (x.flags & (F_CLICK | F_LONG | F_EDIT | F_SCROLL | F_CHECKABLE)) != 0;
        }
        // 每个保留节点的保留父节点
        int[] kp = new int[n];
        for (int i = 0; i < n; i++) {
            int p = nodes.get(i).parent;
            while (p >= 0 && !keep[p]) p = nodes.get(p).parent;
            kp[i] = p;
        }
        List<List<Integer>> kids = new ArrayList<List<Integer>>(n);
        for (int i = 0; i < n; i++) kids.add(new ArrayList<Integer>());
        List<Integer> roots = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            if (kp[i] < 0) roots.add(i);
            else kids.get(kp[i]).add(i);
        }
        StringBuilder sb = new StringBuilder(n * 160 + 128);
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        for (int k = 0; k < roots.size(); k++) xml(sb, roots.get(k), k, kids);
        sb.append("</hierarchy>");
        return sb.toString();
    }

    private void xml(StringBuilder sb, int i, int index, List<List<Integer>> kids) {
        Node x = nodes.get(i);
        sb.append("<node index=\"").append(index).append("\" text=\"").append(esc(x.text)).append("\" resource-id=\"")
                .append(esc(x.rid)).append("\" class=\"").append(esc(x.cls)).append("\" package=\"").append(esc(pkg))
                .append("\" content-desc=\"").append(esc(x.desc)).append("\" checkable=\"").append(x.has(F_CHECKABLE))
                .append("\" checked=\"").append(x.has(F_CHECKED)).append("\" clickable=\"").append(x.has(F_CLICK))
                .append("\" enabled=\"").append(x.has(F_ENABLED)).append("\" focusable=\"").append(x.has(F_FOCUSABLE))
                .append("\" focused=\"").append(x.has(F_FOCUSED)).append("\" scrollable=\"").append(x.has(F_SCROLL))
                .append("\" long-clickable=\"").append(x.has(F_LONG)).append("\" password=\"").append(x.has(F_PASSWORD))
                .append("\" selected=\"").append(x.has(F_SELECTED)).append("\" bounds=\"[").append(x.l).append(',').append(x.t)
                .append("][").append(x.r).append(',').append(x.b).append("]\"");
        List<Integer> ch = kids.get(i);
        if (ch.isEmpty()) {
            sb.append(" />");
            return;
        }
        sb.append('>');
        for (int k = 0; k < ch.size(); k++) xml(sb, ch.get(k), k, kids);
        sb.append("</node>");
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = null;
            switch (c) {
                case '&': r = "&amp;"; break;
                case '<': r = "&lt;"; break;
                case '>': r = "&gt;"; break;
                case '"': r = "&quot;"; break;
                case '\'': r = "&apos;"; break;
                case '\n': r = "&#10;"; break;
                case '\r': r = "&#13;"; break;
                default:
                    if (c < 0x20 && c != '\t') r = " ";
            }
            if (r != null) {
                if (b == null) {
                    b = new StringBuilder(s.length() + 16);
                    b.append(s, 0, i);
                }
                b.append(r);
            } else if (b != null) b.append(c);
        }
        return b == null ? s : b.toString();
    }

    /** 最小 JSON 字符串转义（不依赖 org.json 的 quote，助手和 JVM 测试同一实现）。 */
    static final class JSONObjectQuote {
        static String q(String s) {
            if (s == null) return "\"\"";
            StringBuilder b = new StringBuilder(s.length() + 2);
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
            return b.toString();
        }
    }
}
