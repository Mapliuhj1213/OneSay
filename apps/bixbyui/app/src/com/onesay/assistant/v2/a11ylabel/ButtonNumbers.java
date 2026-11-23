package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.phone.UiTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ a11y-labels：「显示按钮」给可点控件标号、说「N号」点选（纯 Java，JVM 全测；悬浮层与读屏在 {@link ButtonNumbersHooks}）。
 *
 * <p>思路借鉴小空（https://github.com/MichealMin928/xiaokong）：标号只给<b>真实可点</b>的节点（可点 / 可勾选 / 可输入且启用），
 * 编号按阅读顺序（上 → 下、同一行左 → 右，即 {@link A11yLabeler} 给的序号）；页面上一个可点节点都没有就如实说没有，
 * <b>不画假格子</b>（不按网格瞎编号）。代码按思路自己写，没有复制其源码。
 *
 * <p>点选时绑定标号那一刻的快照：重读树，包名 / Activity 变了、节点键找不到、超过 {@link #TTL_MS} 都拒绝，
 * 让用户重新说「显示按钮」，不按旧位置点。付款 / 发送类的按钮编号照标，但最后一下要本人点（现有规则不变）。
 */
public final class ButtonNumbers {

    private ButtonNumbers() {
    }

    /** 编号层有效期（也是悬浮层自动收起的时间）。 */
    public static final long TTL_MS = 60000L;
    /** 一屏最多标多少个（再多就太密，提示用户滚动 / 放大）。 */
    public static final int MAX = 99;
    public static final String OWNER = "buttons";

    // ------------------------------------------------------------------ 说法

    private static final String POLITE = "^(?:请|麻烦)?(?:你)?(?:帮我|给我)?";
    private static final String TAIL = "(?:一下|吧|呢|啊|呀|好吗|好不好)*$";
    private static final Pattern SHOW = Pattern.compile(POLITE
            + "(?:显示|标出|标一下|标上|给|把)?(?:一下)?(?:屏幕上的|这页的|这个页面的|页面上的|所有的?|全部)?"
            + "(?:按钮编号|按钮|可点的?(?:按钮|地方|东西)?|编号|数字标号|标号)"
            + "(?:编号|标号|标出来|编上号|都标出来|显示出来|标上号)?" + TAIL);
    private static final Pattern SHOW_VERB = Pattern.compile("(显示|标出|标一下|标上|编号|编上号|标号)");
    private static final Pattern HIDE = Pattern.compile(POLITE
            + "(?:隐藏|关掉|关闭|取消|去掉|收起|不要|不用|别显示)(?:了)?(?:按钮编号|按钮|编号|标号|数字)(?:了)?" + TAIL);

    /** 「显示按钮」「给按钮编号」「标出可点的地方」…（整句就是这个意思才算）。 */
    public static boolean isShow(String q) {
        String u = clean(q);
        if (u.length() == 0 || u.length() > 16) return false;
        if (!SHOW.matcher(u).matches()) return false;
        return SHOW_VERB.matcher(u).find() || u.startsWith("给") || u.startsWith("把");
    }

    /** 「隐藏编号」「关掉按钮编号」「不用编号了」…。 */
    public static boolean isHide(String q) {
        String u = clean(q);
        return u.length() > 0 && u.length() <= 12 && HIDE.matcher(u).matches();
    }

    private static final String NUM = "([零一二两三四五六七八九十]{1,4}|\\d{1,2})";
    private static final Pattern PICK = Pattern.compile(POLITE
            + "(?:点|点击|点一下|按|按一下|选|选择|戳|打开)?"
            + "(?:" + NUM + "号(?:按钮|键|那个)?|第" + NUM + "(?:个|号)(?:按钮)?)"
            + TAIL);

    public static final int NONE = -1, OUT_OF_RANGE = -2;

    /** 整句只是在点编号 → 编号（1 起）；超出 1..size → {@link #OUT_OF_RANGE}；不是这种说法 → {@link #NONE}。「3号线」「明天3号」都不算。 */
    public static int pick(String q, int size) {
        String u = clean(q);
        if (u.length() == 0 || u.length() > 12) return NONE;
        Matcher m = PICK.matcher(u);
        if (!m.matches()) return NONE;
        int n = num(m.group(1) != null ? m.group(1) : m.group(2));
        if (n <= 0) return NONE;
        return n <= size ? n : OUT_OF_RANGE;
    }

    /** 1–99：阿拉伯数字或「三」「十二」「二十三」「两」。 */
    static int num(String s) {
        if (s == null || s.length() == 0) return -1;
        if (s.matches("\\d+")) return Integer.parseInt(s);
        String d = "零一二三四五六七八九";
        s = s.replace('两', '二');
        int k = s.indexOf('十');
        if (k < 0) return s.length() == 1 ? d.indexOf(s.charAt(0)) : -1;
        if (s.indexOf('十', k + 1) >= 0) return -1;
        int tens = k == 0 ? 1 : (k == 1 ? d.indexOf(s.charAt(0)) : -1);
        int ones = k == s.length() - 1 ? 0 : (k == s.length() - 2 ? d.indexOf(s.charAt(k + 1)) : -1);
        if (tens <= 0 || ones < 0) return -1;
        return tens * 10 + ones;
    }

    static String clean(String q) {
        return q == null ? "" : q.replaceAll("[\\s，,。.！!？?、~～…“”\"']+", "");
    }

    // ------------------------------------------------------------------ 编号层

    /** 一次「显示按钮」：标号那一刻的快照 + 按编号排好的元素。 */
    public static final class Session {
        public final UiTree snap;
        public final List<UiTree.El> numbered;
        public final long at;
        public final boolean truncated;

        Session(UiTree snap, List<UiTree.El> numbered, long at, boolean truncated) {
            this.snap = snap;
            this.numbered = Collections.unmodifiableList(numbered);
            this.at = at;
            this.truncated = truncated;
        }

        public int size() {
            return numbered.size();
        }

        /** 1 起；没有返回 null。 */
        public UiTree.El get(int n) {
            return n >= 1 && n <= numbered.size() ? numbered.get(n - 1) : null;
        }

        public boolean expired(long now) {
            return now - at > TTL_MS;
        }
    }

    /** 真实可点：可点 / 可勾选 / 可输入，启用，有面积。 */
    public static boolean realTarget(UiTree.El e) {
        return (e.click || e.check || e.edit) && e.enabled && e.r - e.l >= 8 && e.b - e.t >= 8;
    }

    /** 对一个已贴标签的快照开编号层（按阅读序号排）。没有可点节点 → size() == 0（如实说，不画格子）。 */
    public static Session open(UiTree labeled, long now) {
        List<UiTree.El> l = new ArrayList<UiTree.El>();
        if (labeled != null && labeled.labeled()) {
            for (UiTree.El e : A11yLabeler.readingOrder(labeled.els)) if (e.ord > 0 && realTarget(e)) l.add(e);
        }
        boolean cut = l.size() > MAX;
        if (cut) l = new ArrayList<UiTree.El>(l.subList(0, MAX));
        return new Session(labeled, l, now, cut);
    }

    /** 开编号层后给用户的一句话。 */
    public static String openSay(Session s) {
        if (s == null || s.snap == null) return "读不到当前界面，没法标号。";
        if (s.size() == 0) return "这个页面没有可点的按钮（读到的都是文字或图片），所以没有标号。";
        return "标好了，一共 " + s.size() + " 个，说「几号」我就点" + (s.truncated ? "（太多了，只标了前 " + MAX + " 个）" : "") + "。";
    }

    /** 点选的结果。 */
    public static final class Pick {
        public final UiTree.El target;
        public final String say;
        /** 要重新标号（界面变了 / 过期）。 */
        public final boolean reopen;
        /** 付款 / 发送类：照标不点，要本人点。 */
        public final boolean userMustTap;

        Pick(UiTree.El target, String say, boolean reopen, boolean userMustTap) {
            this.target = target;
            this.say = say;
            this.reopen = reopen;
            this.userMustTap = userMustTap;
        }

        public boolean ok() {
            return target != null && !userMustTap;
        }
    }

    /** 判别「这一下要本人点」（宿主接 SensitiveGuard；单测可换）。返回原因，null = 可以替他点。 */
    public interface Guard {
        String userMustTap(UiTree.El target, UiTree fresh);
    }

    /** 默认判别：付款 / 转账最后一下（SensitiveGuard.userMustTap），以及发送 / 提交 / 删除 / 登录这类本来就要确认的按钮 —— 编号点选一律交给本人点。 */
    public static final Guard DEFAULT_GUARD = new Guard() {
        @Override public String userMustTap(UiTree.El target, UiTree fresh) {
            if (target == null || fresh == null) return null;
            com.onesay.assistant.v2.phone.PhonePlan.Step st = new com.onesay.assistant.v2.phone.PhonePlan.Step();
            st.op = "tap";
            st.id = target.id;
            String must = com.onesay.assistant.v2.phone.SensitiveGuard.userMustTap(st, fresh);
            if (must != null) return "付款 / 转账的最后一下只能你自己点";
            String sens = com.onesay.assistant.v2.phone.SensitiveGuard.check(st, fresh);
            return sens == null ? null : sens;
        }
    };

    /**
     * 说了「N号」：fresh = 刚重读的树（已贴标签）。顺序：没开编号层 → 编号不存在 → 过期 / 页面变了 / 节点不在（要重标）→ 付款发送类（本人点）→ 通过。
     */
    public static Pick choose(Session s, int n, UiTree fresh, long now, Guard guard) {
        if (s == null) return new Pick(null, "先说「显示按钮」，我把能点的标上号。", false, false);
        UiTree.El p = s.get(n);
        if (p == null) return new Pick(null, s.size() == 0 ? "这个页面没有可点的按钮。" : "没有 " + n + " 号，这页只有 1 到 " + s.size() + " 号。", false, false);
        SnapshotBinding.Verdict v = SnapshotBinding.checkEl(s.snap, p, fresh, now, TTL_MS);
        if (v.kind == SnapshotBinding.Kind.EXPIRED) return new Pick(null, "编号过期了，我重新标一遍，再说几号。", true, false);
        if (v.refused()) return new Pick(null, "界面变了，" + n + " 号已经不是原来那个，我重新标一遍，再说几号。", true, false);
        if (!v.ok()) return new Pick(null, "读不准这个按钮，没有点。", false, false);
        String must = guard == null ? null : guard.userMustTap(v.target, fresh);
        if (must != null) return new Pick(v.target, n + " 号是「" + clip(v.target.label) + "」，" + must + "，这一下要你自己点。", false, true);
        return new Pick(v.target, "", false, false);
    }

    static String clip(String s) {
        return s == null ? "" : s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    /** 悬浮层上每个标号画在哪里（像素）：元素左上角往里一点；跟前面的标号挤在一起就往右挪。返回 [n][x, y]。 */
    public static int[][] badgePositions(Session s, int radius) {
        int[][] out = new int[s.size()][2];
        for (int i = 0; i < s.size(); i++) {
            UiTree.El e = s.numbered.get(i);
            int x = e.l + radius, y = e.t + radius;
            for (int guard = 0; guard < 8; guard++) {
                boolean clash = false;
                for (int j = 0; j < i; j++) {
                    if (Math.abs(out[j][0] - x) < radius * 2 && Math.abs(out[j][1] - y) < radius * 2) {
                        clash = true;
                        break;
                    }
                }
                if (!clash) break;
                x += radius * 2;
            }
            out[i][0] = x;
            out[i][1] = y;
        }
        return out;
    }
}
