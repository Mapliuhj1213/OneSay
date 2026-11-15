package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.fastscreen.FsTree;
import com.onesay.assistant.v2.phone.UiTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★ a11y-labels：给无障碍读出的控件树贴「精准标签」（纯 Java，JVM 全测）。
 *
 * <p>每次读树一个快照 ID；树里每个留给模型的元素贴上：
 * <ul>
 *   <li><b>节点稳定键</b> {@link #key}：类名 + 锚点 + 祖先路径哈希，8 位十六进制，同键重复时按阅读顺序加 {@code .2 .3}；</li>
 *   <li><b>角色</b> {@link #role}：按钮 / 输入框 / 开关 / 勾选框 / 单选 / 列表项 / 标签页 / 图片 / 文本 / 列表 / 滚动区 / 滑块 / 进度条 / 网页；</li>
 *   <li><b>名字来源</b>：文字 / 描述 / 子项 / 推断（无文字图标按钮按资源 id 猜的名字）；</li>
 *   <li><b>阅读序号</b>：只给启用的可点 / 可勾选 / 可输入元素，从上到下、同一行从左到右（「显示按钮」编号用）。</li>
 * </ul>
 *
 * <h3>稳定键规则（同一界面读两次键一定相同）</h3>
 * <ol>
 *   <li>锚点：有资源 id 用资源 id（列表里的行共用资源 id，再加文字）；没有资源 id 用内容描述；再没有用自己的文字；
 *       再没有用并进来的子项文字；都没有 = 弱键（用同级下标兜底，绑定时还要核对位置）。</li>
 *   <li>文字进键前先规整：去首尾空白、连续空白并成一个、<b>连续数字换成 #</b>（「3 条消息」和「4 条消息」同键，验证码 / 号码不进键）、
 *       截到 32 字。所以：有资源 id 的控件文字怎么变键都不变；没有资源 id 的控件只改数字键不变，改了别的字键就变（按新控件对待）；
 *       输入框里输入的内容永远不进键（用提示语 / 描述，都没有就弱键）。</li>
 *   <li>祖先路径：从根到父节点每层的「短类名 + 资源 id」，不含下标和文字 —— 前面插进一条横幅、父容器文字变了，键都不变。</li>
 *   <li>键只是哈希，日志里可以出现；原文不进键、不进日志。</li>
 * </ol>
 */
public final class A11yLabeler {

    private A11yLabeler() {
    }

    public static final String SRC_TEXT = "文字", SRC_HINT = "提示", SRC_DESC = "描述", SRC_CHILD = "子项", SRC_RID = "推断", SRC_NONE = "";

    public static final String R_BUTTON = "按钮", R_INPUT = "输入框", R_SWITCH = "开关", R_CHECK = "勾选框", R_RADIO = "单选",
            R_ITEM = "列表项", R_TAB = "标签页", R_IMAGE = "图片", R_TEXT = "文本", R_LIST = "列表", R_SCROLL = "滚动区",
            R_SLIDER = "滑块", R_PROGRESS = "进度条", R_WEB = "网页";

    /** 给快照 ID 用的计数器（一个进程一份；测试可以换）。 */
    public interface Ids {
        String next();
    }

    private static final Object LOCK = new Object();
    private static long sSeq;

    /** 默认快照 ID：S + 进程内递增序号（36 进制）。 */
    public static final Ids DEFAULT_IDS = new Ids() {
        @Override public String next() {
            synchronized (LOCK) {
                return "S" + Long.toString(++sSeq, 36);
            }
        }
    };

    /** 贴标签（原地改 u 的元素）。u.tree 为空时只给快照 ID 和角色，键退化为弱键。返回 u。 */
    public static UiTree attach(UiTree u, String snapshotId, long now) {
        if (u == null) return null;
        u.snapshotId = snapshotId == null || snapshotId.length() == 0 ? DEFAULT_IDS.next() : snapshotId;
        u.takenAt = now;
        FsTree t = u.tree;
        Map<String, Integer> seen = new HashMap<String, Integer>();
        // 键的重复序号要按阅读顺序给，先排好
        List<UiTree.El> order = readingOrder(u.els);
        for (UiTree.El e : order) {
            FsTree.Node x = node(t, e);
            boolean inList = inList(t, e);
            e.role = role(x, e, inList);
            String[] nm = nameOf(x, e);
            e.nameSrc = nm[1];
            if (SRC_RID.equals(nm[1]) && e.label.length() == 0) e.label = nm[0];   // 推断名字写回（渲染时标「推断」）
            String[] k = baseKey(t, x, e, inList);
            e.weakKey = "w".equals(k[1]);
            Integer c = seen.get(k[0]);
            int n = c == null ? 1 : c + 1;
            seen.put(k[0], n);
            e.nodeKey = n == 1 ? k[0] : k[0] + "." + n;
        }
        int ord = 0;
        for (UiTree.El e : order) e.ord = tappable(e) && e.enabled ? ++ord : 0;
        return u;
    }

    public static boolean tappable(UiTree.El e) {
        return e.click || e.check || e.edit;
    }

    // ------------------------------------------------------------------ 阅读顺序

    /** 从上到下、同一行从左到右。同一行 = 顶边差不超过本行第一个元素高度的一半（至少 16 像素）。 */
    public static List<UiTree.El> readingOrder(List<UiTree.El> els) {
        List<UiTree.El> byTop = new ArrayList<UiTree.El>(els);
        Collections.sort(byTop, new Comparator<UiTree.El>() {
            @Override public int compare(UiTree.El a, UiTree.El b) {
                if (a.t != b.t) return a.t < b.t ? -1 : 1;
                return a.l < b.l ? -1 : (a.l == b.l ? 0 : 1);
            }
        });
        List<UiTree.El> out = new ArrayList<UiTree.El>();
        int i = 0;
        while (i < byTop.size()) {
            UiTree.El first = byTop.get(i);
            int tol = Math.max(16, (first.b - first.t) / 2);
            List<UiTree.El> row = new ArrayList<UiTree.El>();
            int j = i;
            while (j < byTop.size() && byTop.get(j).t - first.t <= tol) row.add(byTop.get(j++));
            Collections.sort(row, new Comparator<UiTree.El>() {
                @Override public int compare(UiTree.El a, UiTree.El b) {
                    if (a.l != b.l) return a.l < b.l ? -1 : 1;
                    return a.t < b.t ? -1 : (a.t == b.t ? 0 : 1);
                }
            });
            out.addAll(row);
            i = j;
        }
        return out;
    }

    // ------------------------------------------------------------------ 角色

    /** 按类名 + 可点 / 可编辑 / 可勾选 / 可滚动判断。 */
    public static String role(FsTree.Node x, UiTree.El e, boolean inList) {
        String c = ((x != null ? x.cls : e.cls) == null ? "" : (x != null ? x.cls : e.cls)).toLowerCase(Locale.ROOT);
        if (e.edit || c.contains("edittext") || c.contains("autocompletetextview")) return R_INPUT;
        if (c.contains("switch") || c.contains("togglebutton")) return R_SWITCH;
        if (c.contains("radiobutton")) return R_RADIO;
        if (c.contains("checkbox") || c.contains("checkedtextview")) return R_CHECK;
        if (e.check) return R_SWITCH;
        if (c.contains("seekbar") || c.contains("ratingbar") || c.contains("slider")) return R_SLIDER;
        if (c.contains("progressbar")) return R_PROGRESS;
        if (c.contains("webview")) return R_WEB;
        if (e.scroll && !e.click) {
            return c.endsWith("listview") || c.endsWith("recyclerview") || c.endsWith("gridview") || (x != null && x.has(FsTree.F_LIST)) ? R_LIST : R_SCROLL;
        }
        if (e.click && (c.contains("tabview") || c.endsWith(".tab") || c.contains("tabitem") || c.contains("tablayout$"))) return R_TAB;
        if (e.click && inList) return R_ITEM;
        if (c.contains("button")) return R_BUTTON;
        if (c.contains("image")) return e.click ? R_BUTTON : R_IMAGE;
        if (e.click) return R_BUTTON;
        return R_TEXT;
    }

    // ------------------------------------------------------------------ 名字

    /** [名字, 来源]。名字只在来源 = 推断时由这里给（其余沿用元素原名，已按原口径打码）。 */
    static String[] nameOf(FsTree.Node x, UiTree.El e) {
        String text = x == null ? "" : nz(x.text).trim(), desc = x == null ? "" : nz(x.desc).trim();
        if (x == null) return new String[]{e.label, e.label.length() > 0 ? SRC_TEXT : SRC_NONE};
        String hint = nz(x.hint).trim();
        if (e.edit && hint.length() > 0 && (text.length() == 0 || text.equals(hint) || x.has(FsTree.F_HINT_SHOWING)) && e.label.length() > 0)
            return new String[]{e.label, SRC_HINT};   // 输入框里显示的是提示语，不是已输入的内容
        if (text.length() > 0 && !e.password) return new String[]{e.label, SRC_TEXT};
        if (desc.length() > 0) return new String[]{e.label, SRC_DESC};
        if (e.label.length() > 0) return new String[]{e.label, e.password ? SRC_TEXT : SRC_CHILD};
        String guess = inferFromRid(x.rid);
        if (guess != null) return new String[]{guess, SRC_RID};
        return new String[]{"", SRC_NONE};
    }

    private static final String[][] WORDS = {
            {"back", "返回"}, {"return", "返回"}, {"close", "关闭"}, {"dismiss", "关闭"}, {"search", "搜索"}, {"more", "更多"},
            {"menu", "菜单"}, {"overflow", "更多"}, {"share", "分享"}, {"add", "添加"}, {"plus", "添加"}, {"create", "新建"},
            {"settings", "设置"}, {"setting", "设置"}, {"send", "发送"}, {"like", "点赞"}, {"comment", "评论"}, {"camera", "相机"},
            {"voice", "语音"}, {"mic", "麦克风"}, {"emoji", "表情"}, {"home", "首页"}, {"profile", "我的"}, {"mine", "我的"},
            {"scan", "扫一扫"}, {"cart", "购物车"}, {"delete", "删除"}, {"remove", "删除"}, {"edit", "编辑"}, {"play", "播放"},
            {"pause", "暂停"}, {"next", "下一个"}, {"prev", "上一个"}, {"previous", "上一个"}, {"favorite", "收藏"},
            {"collect", "收藏"}, {"star", "收藏"}, {"download", "下载"}, {"refresh", "刷新"}, {"filter", "筛选"}, {"sort", "排序"},
            {"notification", "通知"}, {"notice", "通知"}, {"message", "消息"}, {"msg", "消息"}, {"chat", "聊天"},
            {"contact", "联系人"}, {"call", "电话"}, {"dial", "拨号"}, {"photo", "照片"}, {"gallery", "相册"}, {"album", "相册"},
            {"location", "位置"}, {"help", "帮助"}, {"info", "信息"}, {"confirm", "确定"}, {"ok", "确定"}, {"cancel", "取消"},
            {"clear", "清除"}, {"expand", "展开"}, {"collapse", "收起"}, {"keyboard", "键盘"}, {"attach", "附件"},
            {"switch", "切换"}, {"skip", "跳过"}, {"submit", "提交"}, {"save", "保存"}, {"copy", "复制"}, {"translate", "翻译"},
            {"user", "用户"}, {"avatar", "头像"}, {"logo", "标志"}, {"icon", "图标"}};

    /** 资源 id → 中文名（按 _ / 驼峰拆词，取第一个认得的词）；一个词都不认得但资源 id 可读，就原样给出资源 id 末段。 */
    public static String inferFromRid(String rid) {
        String s = shortRid(rid);
        if (s.length() == 0) return null;
        String spaced = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        String[] parts = spaced.split("[^a-z0-9]+");
        for (String p : parts) {
            for (String[] w : WORDS) if (w[0].equals(p)) return w[1];
        }
        if (!s.matches("[A-Za-z0-9_]{3,}") || !s.matches(".*[A-Za-z]{3}.*")) return null;   // 混淆过的短名认不出用途
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    // ------------------------------------------------------------------ 稳定键

    /** [基础键, 强弱]（"s" 强 / "w" 弱）。 */
    static String[] baseKey(FsTree t, FsTree.Node x, UiTree.El e, boolean inList) {
        String cls = x != null ? x.shortCls() : e.cls;
        String rid = x != null ? shortRid(x.rid) : e.rid;
        String text = x != null ? nz(x.text) : "", desc = x != null ? nz(x.desc) : "";
        String anchor;
        boolean weak = false;
        if (rid.length() > 0 && !inList) anchor = "r:" + rid;
        else if (rid.length() > 0) anchor = "r:" + rid + "|" + norm(text.trim().length() > 0 ? text : desc.trim().length() > 0 ? desc : e.label);
        else if (e.edit) {   // 输入框：输入的内容会变，不进键；用提示语 / 描述，都没有就弱键
            String hint = x != null ? nz(x.hint).trim() : "";
            if (hint.length() > 0) anchor = "h:" + norm(hint);
            else if (desc.trim().length() > 0) anchor = "d:" + norm(desc);
            else {
                anchor = "p:" + siblingPath(t, e.node);
                weak = true;
            }
        } else if (desc.trim().length() > 0) anchor = "d:" + norm(desc);
        else if (text.trim().length() > 0 && !e.password) anchor = "t:" + norm(text);
        else if (e.label.length() > 0 && !SRC_RID.equals(e.nameSrc)) anchor = "c:" + norm(e.label);
        else {
            anchor = "p:" + siblingPath(t, e.node);
            weak = true;
        }
        String kind = (e.edit ? "E" : "") + (e.scroll ? "S" : "") + (e.check ? "C" : "");
        long h = fnv(FNV, cls);
        h = fnv(h, anchor);
        h = fnv(h, kind);
        h = fnv(h, ancestorSig(t, e.node));
        return new String[]{"n" + String.format("%08x", (int) (h ^ (h >>> 32))), weak ? "w" : "s"};
    }

    /** 规整：去首尾空白、空白并一个、连续数字 → #、截 32 字。 */
    public static String norm(String s) {
        String v = nz(s).trim().replaceAll("\\s+", " ").replaceAll("[0-9０-９]+", "#");
        return v.length() > 32 ? v.substring(0, 32) : v;
    }

    /** 根 → 父节点每层「短类名#资源 id」（不含下标、文字）。 */
    static String ancestorSig(FsTree t, int i) {
        if (t == null || i < 0 || i >= t.nodes.size()) return "";
        List<String> parts = new ArrayList<String>();
        int p = t.nodes.get(i).parent, guard = 0;
        while (p >= 0 && p < t.nodes.size() && guard++ < 64) {
            FsTree.Node a = t.nodes.get(p);
            parts.add(a.shortCls() + "#" + shortRid(a.rid));
            p = a.parent;
        }
        StringBuilder b = new StringBuilder();
        for (int k = parts.size() - 1; k >= 0; k--) b.append(parts.get(k)).append('/');
        return b.toString();
    }

    /** 弱键兜底：根 → 自己每层的同级下标。 */
    static String siblingPath(FsTree t, int i) {
        if (t == null || i < 0 || i >= t.nodes.size()) return "?";
        StringBuilder b = new StringBuilder();
        int p = i, guard = 0;
        while (p >= 0 && p < t.nodes.size() && guard++ < 64) {
            b.insert(0, t.nodes.get(p).childIdx + "/");
            p = t.nodes.get(p).parent;
        }
        return b.toString();
    }

    static boolean inList(FsTree t, UiTree.El e) {
        if (t == null || e.node < 0 || e.node >= t.nodes.size()) return false;
        int p = t.nodes.get(e.node).parent, guard = 0;
        while (p >= 0 && p < t.nodes.size() && guard++ < 64) {
            FsTree.Node a = t.nodes.get(p);
            String c = a.cls == null ? "" : a.cls;
            if (a.has(FsTree.F_LIST) || c.endsWith("ListView") || c.endsWith("RecyclerView") || c.endsWith("GridView")) return true;
            p = a.parent;
        }
        return false;
    }

    static FsTree.Node node(FsTree t, UiTree.El e) {
        return t != null && e.node >= 0 && e.node < t.nodes.size() ? t.nodes.get(e.node) : null;
    }

    static String shortRid(String rid) {
        if (rid == null) return "";
        int k = rid.indexOf(":id/");
        return k >= 0 ? rid.substring(k + 4) : rid;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static final long FNV = 0xcbf29ce484222325L;

    static long fnv(long h, String s) {
        String v = s == null ? "" : s;
        for (int i = 0; i < v.length(); i++) {
            h ^= v.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= 0x1f;
        h *= 0x100000001b3L;
        return h;
    }
}
