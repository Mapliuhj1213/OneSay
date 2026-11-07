package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用设置导航（纯 Java，JVM 单测 SettingsNavTest）：不依赖任何厂商私有 settings 键或包名，靠<b>读设置页控件树</b>找开关。
 *
 * <h3>流程（{@link #plan}）</h3>
 * <ol>
 *   <li>用标准 {@code Settings.ACTION_*} 打开最可能的设置页，在控件树里找目标项（{@link #find}，可滚动几屏）；</li>
 *   <li>没找到：打开系统设置首页，用它自带的搜索框搜目标项（{@link Step.Kind#SETTINGS_SEARCH}）；</li>
 *   <li>还没找到：用用户自己配置的搜索 key（Tavily / 博查）联网查「这台机型上这个设置在哪」，从结果里抽出路径
 *       （{@link #parsePath}），回到设置首页按路径一级级点进去；没配 key 就如实说没法查（{@link #NO_KEY}）。</li>
 * </ol>
 * 找到后只<b>报告</b>当前状态；要不要改由用户点芯片确认，<b>每次</b>都要确认（{@link #needsConfirm} 恒为 true）。
 */
public final class SettingsNav {
    private SettingsNav() {}

    public static final String NO_KEY = "没有配置联网搜索的 key（Tavily / 博查），没法查这台手机上这个设置在哪。可以在「密钥」页填一个，或者告诉我它在设置里的路径。";
    public static final String NEED_A11Y = "要先在系统「无障碍」里打开「OneSay 读屏与按键唤醒」，我才能读设置页、帮你找开关。";

    /** 常见设置项的别名（中英，覆盖各家常见叫法；只用于在控件树里匹配文字，不涉及任何包名）。 */
    static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<String, List<String>>();

    static {
        syn("注视时保持亮屏", "智能亮屏", "注视不熄屏", "注视屏幕时保持亮屏", "Smart stay", "Screen attention", "Stay awake while looking");
        syn("防误触", "防误触模式", "口袋模式", "Accidental touch protection", "Pocket mode", "Prevent accidental touches");
        syn("息屏显示", "熄屏显示", "AOD", "Always On Display", "Always-on display", "Ambient display");
        syn("通知亮屏", "来通知时亮屏", "通知时点亮屏幕", "边缘闪光", "侧屏闪光", "Edge lighting", "Wake screen for notifications", "New notifications");
        syn("护眼模式", "护眼", "夜间模式", "夜灯", "蓝光过滤", "Eye comfort", "Night Light", "Night light", "Blue light filter", "Eye comfort shield");
        syn("拿起手机时振动提醒", "智能提醒", "拿起提醒", "Smart alert", "Lift to wake", "Pick up");
        syn("抬起唤醒", "抬手亮屏", "拿起亮屏", "Lift to wake", "Raise to wake");
    }

    private static void syn(String key, String... alts) {
        List<String> l = new ArrayList<String>();
        l.add(key);
        Collections.addAll(l, alts);
        SYNONYMS.put(key, Collections.unmodifiableList(l));
    }

    /** 目标项的全部可匹配名字（第一个是本身）。 */
    public static List<String> names(String label) {
        List<String> l = SYNONYMS.get(label);
        if (l != null) return l;
        List<String> one = new ArrayList<String>();
        if (label != null) one.add(label);
        return one;
    }

    // ------------------------------------------------------------------ 计划

    public static final class Step {
        public enum Kind { OPEN_ACTION, FIND, SETTINGS_SEARCH, WEB_LOOKUP, FOLLOW_PATH, REPORT_NO_KEY, REPORT_NOT_FOUND }

        public final Kind kind;
        public final String arg;
        public final List<String> path;

        Step(Kind kind, String arg, List<String> path) {
            this.kind = kind;
            this.arg = arg;
            this.path = path;
        }

        @Override public String toString() {
            return kind + (arg != null ? "(" + arg + ")" : "") + (path != null ? path : "");
        }
    }

    /**
     * @param settingsAction  标准 Settings.ACTION_*（可空 = 直接从设置首页开始）
     * @param knownPath       用户或上次查到的路径（可空）
     * @param hasSearchKey    是否配置了联网搜索 key
     */
    public static List<Step> plan(String label, String settingsAction, List<String> knownPath, boolean hasSearchKey) {
        List<Step> s = new ArrayList<Step>();
        s.add(new Step(Step.Kind.OPEN_ACTION, settingsAction != null && settingsAction.length() > 0 ? settingsAction : ACTION_SETTINGS, null));
        s.add(new Step(Step.Kind.FIND, label, null));
        if (knownPath != null && !knownPath.isEmpty()) {
            s.add(new Step(Step.Kind.OPEN_ACTION, ACTION_SETTINGS, null));
            s.add(new Step(Step.Kind.FOLLOW_PATH, label, knownPath));
            s.add(new Step(Step.Kind.FIND, label, null));
        }
        s.add(new Step(Step.Kind.OPEN_ACTION, ACTION_SETTINGS, null));
        s.add(new Step(Step.Kind.SETTINGS_SEARCH, label, null));
        s.add(new Step(Step.Kind.FIND, label, null));
        if (hasSearchKey) {
            s.add(new Step(Step.Kind.WEB_LOOKUP, label, null));
            s.add(new Step(Step.Kind.OPEN_ACTION, ACTION_SETTINGS, null));
            s.add(new Step(Step.Kind.FOLLOW_PATH, label, null));
            s.add(new Step(Step.Kind.FIND, label, null));
            s.add(new Step(Step.Kind.REPORT_NOT_FOUND, label, null));
        } else {
            s.add(new Step(Step.Kind.REPORT_NO_KEY, label, null));
        }
        return s;
    }

    public static final String ACTION_SETTINGS = "android.settings.SETTINGS";

    /** 每次改动都要用户确认（含特权 / 破坏性操作）。 */
    public static boolean needsConfirm(String label) {
        return true;
    }

    /** 联网查路径用的搜索词（机型 + 系统版本 + 设置项）。 */
    public static String webQuery(String manufacturer, String model, int sdk, String label) {
        StringBuilder sb = new StringBuilder();
        if (manufacturer != null && manufacturer.trim().length() > 0) sb.append(manufacturer.trim()).append(' ');
        if (model != null && model.trim().length() > 0) sb.append(model.trim()).append(' ');
        if (sdk > 0) sb.append("Android ").append(androidVersion(sdk)).append(' ');
        sb.append("设置 ").append(label).append(" 在哪里 打开路径");
        return sb.toString();
    }

    static String androidVersion(int sdk) {
        if (sdk >= 35) return "15";
        if (sdk >= 34) return "14";
        if (sdk >= 33) return "13";
        if (sdk >= 31) return "12";
        if (sdk >= 30) return "11";
        if (sdk >= 29) return "10";
        if (sdk >= 28) return "9";
        if (sdk >= 26) return "8";
        return "7";
    }

    // ------------------------------------------------------------------ 路径抽取

    private static final Pattern SEP = Pattern.compile("\\s*(?:>|＞|›|»|→|->|=>|/|＞|－>|—>)\\s*");
    private static final Pattern ROOT = Pattern.compile("^(?:打开|进入|前往|依次点击|依次进入|路径[:：]?|在)?\\s*(?:手机)?(?:系统)?(?:设置|settings)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从搜索结果文字里抽出设置路径：取含分隔符（{@code > › → /}）最多、且最后一段与目标名之一相关的那一串；
     * 去掉开头的「设置」、引号书名号；每段 ≤ 16 字，最多 6 段。抽不出返回空列表。
     */
    public static List<String> parsePath(String text, String label) {
        List<String> best = new ArrayList<String>();
        if (text == null) return best;
        List<String> ns = names(label);
        int bestScore = 0;
        for (String line : text.split("[\\n。；;！!？?]")) {
            if (!SEP.matcher(line).find()) continue;
            String[] raw = SEP.split(line);
            List<String> segs = new ArrayList<String>();
            for (int i = 0; i < raw.length; i++) {
                String t;
                if (i == 0) {
                    String[] w = raw[i].trim().split("[，,：:\\s]+");   // 句子里夹着的路径：开头只留最后一个词组
                    t = clean(w.length == 0 ? "" : w[w.length - 1]);
                } else {
                    t = clean(raw[i].split("[，,。]|即可|就可以|然后")[0]);   // 结尾只留第一个分句
                }
                if (t.length() == 0) continue;
                if (segs.isEmpty() && ROOT.matcher(t).matches()) continue;
                if (t.length() > 16) { segs.clear(); break; }
                segs.add(t);
            }
            if (segs.size() < 1 || segs.size() > 6) continue;
            int score = segs.size();
            String last = norm(segs.get(segs.size() - 1));
            for (String n : ns) if (last.contains(norm(n)) || norm(n).contains(last)) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = segs;
            }
        }
        return best;
    }

    static String clean(String s) {
        String t = s == null ? "" : s.trim();
        t = t.replaceAll("^[「『\"“'‘【\\[（(]+", "").replaceAll("[」』\"”'’】\\]）)]+$", "");
        t = t.replaceAll("^(?:点击|点|选择|进入|打开|找到)", "");
        t = t.replaceAll("[「」『』\"“”]", "").trim();
        return t;
    }

    // ------------------------------------------------------------------ 控件树匹配

    public static final class Match {
        /** 文字所在节点。 */
        public final int labelNode;
        /** 点它能进入 / 切换的节点（最近的可点击祖先，或自身）。 */
        public final int clickNode;
        /** 开关节点（可勾选）；-1 = 不是开关（是进入下一级的条目）。 */
        public final int toggleNode;
        /** 开关当前状态；null = 不是开关。 */
        public final Boolean checked;
        /** 同一行的说明 / 当前值文字（如「30 秒」「已关闭」）；可空。 */
        public final String summary;
        public final String matchedName;

        Match(int labelNode, int clickNode, int toggleNode, Boolean checked, String summary, String matchedName) {
            this.labelNode = labelNode;
            this.clickNode = clickNode;
            this.toggleNode = toggleNode;
            this.checked = checked;
            this.summary = summary;
            this.matchedName = matchedName;
        }

        public String describe(String label) {
            StringBuilder sb = new StringBuilder("「").append(matchedName).append("」");
            if (checked != null) sb.append("现在是").append(checked ? "开着的" : "关着的");
            else sb.append("在这一页");
            if (summary != null && summary.length() > 0) sb.append("（").append(summary).append("）");
            return sb.append("。").toString();
        }

        @Override public String toString() {
            return "Match{" + matchedName + " label=" + labelNode + " click=" + clickNode + " toggle=" + toggleNode + " checked=" + checked + "}";
        }
    }

    /** 在控件树里找目标项（任一别名）。完全相等优先，其次包含；本 App 自己的面板窗口之外的节点才算。找不到返回 null。 */
    public static Match find(FsTree t, String label) {
        return find(t, label, null);
    }

    /** 同上，跳过 {@code selfPkg}（本 App 的悬浮面板）窗口里的节点。 */
    public static Match find(FsTree t, String label, String selfPkg) {
        if (t == null) return null;
        List<String> ns = names(label);
        int best = -1, bestScore = 0;
        String bestName = null;
        for (int i = 0; i < t.nodes.size(); i++) {
            FsTree.Node n = t.nodes.get(i);
            if (selfPkg != null && selfPkg.equals(winPkg(t, n.win))) continue;
            String tx = norm(n.text.length() > 0 ? n.text : n.desc);
            if (tx.length() == 0 || tx.length() > 40) continue;
            for (String name : ns) {
                String nn = norm(name);
                if (nn.length() == 0) continue;
                int sc = tx.equals(nn) ? 3 : (tx.contains(nn) ? 2 : (nn.length() >= 4 && nn.contains(tx) && tx.length() >= 3 ? 1 : 0));
                if (sc > bestScore) {
                    bestScore = sc;
                    best = i;
                    bestName = n.text.length() > 0 ? n.text : n.desc;
                }
            }
        }
        if (best < 0) return null;
        int row = rowOf(t, best);
        int toggle = -1;
        FsTree.Node self = t.nodes.get(best);
        if (self.has(FsTree.F_CHECKABLE)) toggle = best;
        if (toggle < 0) toggle = firstCheckableUnder(t, row, best);
        Boolean checked = toggle >= 0 ? Boolean.valueOf(t.nodes.get(toggle).has(FsTree.F_CHECKED)) : null;
        String summary = summaryUnder(t, row, best);
        int click = row >= 0 && t.nodes.get(row).has(FsTree.F_CLICK) ? row : (self.has(FsTree.F_CLICK) ? best : (toggle >= 0 ? toggle : row));
        return new Match(best, click < 0 ? best : click, toggle, checked, summary, bestName);
    }

    static String winPkg(FsTree t, int win) {
        for (FsTree.Win w : t.windows) if (w.id == win) return w.pkg;
        return null;
    }

    /** 最近的可点击祖先（列表的一行）；没有返回父节点或自身。 */
    static int rowOf(FsTree t, int i) {
        int cur = i, hops = 0;
        while (cur >= 0 && hops < 5) {
            if (t.nodes.get(cur).has(FsTree.F_CLICK)) return cur;
            cur = t.nodes.get(cur).parent;
            hops++;
        }
        int p = t.nodes.get(i).parent;
        return p >= 0 ? p : i;
    }

    static boolean under(FsTree t, int node, int ancestor) {
        int cur = node, hops = 0;
        while (cur >= 0 && hops < 8) {
            if (cur == ancestor) return true;
            cur = t.nodes.get(cur).parent;
            hops++;
        }
        return false;
    }

    static int firstCheckableUnder(FsTree t, int row, int label) {
        if (row < 0) return -1;
        for (int j = 0; j < t.nodes.size(); j++) {
            if (j == label) continue;
            if (t.nodes.get(j).has(FsTree.F_CHECKABLE) && under(t, j, row)) return j;
        }
        // 开关在旁边的兄弟节点里（行本身不可点的布局）
        int p = t.nodes.get(label).parent;
        if (p >= 0) {
            for (int j = 0; j < t.nodes.size(); j++) {
                if (t.nodes.get(j).has(FsTree.F_CHECKABLE) && under(t, j, p)) return j;
            }
        }
        return -1;
    }

    static String summaryUnder(FsTree t, int row, int label) {
        if (row < 0) return null;
        for (int j = 0; j < t.nodes.size(); j++) {
            if (j == label) continue;
            FsTree.Node n = t.nodes.get(j);
            if (n.text.length() == 0 || n.has(FsTree.F_CHECKABLE)) continue;
            if (under(t, j, row)) return n.text.length() > 40 ? n.text.substring(0, 40) : n.text;
        }
        return null;
    }

    /** 设置首页的搜索入口：文字或描述含「搜索」/「Search」的可点击节点。 */
    public static int findSearchBox(FsTree t) {
        if (t == null) return -1;
        for (int i = 0; i < t.nodes.size(); i++) {
            FsTree.Node n = t.nodes.get(i);
            String s = (n.text + " " + n.desc + " " + n.rid).toLowerCase(Locale.ROOT);
            if ((s.contains("搜索") || s.contains("search")) && (n.has(FsTree.F_CLICK) || n.has(FsTree.F_EDIT))) return i;
        }
        return -1;
    }

    /** 可编辑的输入框（设置搜索页）。 */
    public static int findEditable(FsTree t) {
        if (t == null) return -1;
        for (int i = 0; i < t.nodes.size(); i++) if (t.nodes.get(i).has(FsTree.F_EDIT)) return i;
        return -1;
    }

    /** 可滚动的列表（翻页找目标）。 */
    public static int findScrollable(FsTree t) {
        if (t == null) return -1;
        for (int i = 0; i < t.nodes.size(); i++) if (t.nodes.get(i).has(FsTree.F_SCROLL)) return i;
        return -1;
    }

    /** 按名字找一级路径条目（用于 FOLLOW_PATH）。 */
    public static int findEntry(FsTree t, String segment) {
        Match m = find(t, segment, null);
        return m == null ? -1 : m.clickNode;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s「」『』\"“”'’‘（）()·・\\-_]", "");
    }

    /** 抽出的路径给用户看的写法。 */
    public static String pathText(List<String> path) {
        StringBuilder sb = new StringBuilder("设置");
        if (path != null) for (String p : path) sb.append(" › ").append(p);
        return sb.toString();
    }
}
