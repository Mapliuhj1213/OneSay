package com.onesay.assistant.v2.phone;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ★ fix/chat-vision-1124 第 1 条：下单时「送不了这个地址」（纯 Java，JVM 可测）。
 *
 * <p>以前：页面上出现「无法配送 / 超出配送范围」，模型自己去点一个别的地址接着下单 —— 换到哪个地址是用户的事，不能替人定。
 * 现在 {@link PhoneAgent} 在问模型前先看这一屏：
 * <ol>
 *   <li>页面上有别的地址可选 → 确认卡问「原地址送不了，换成 XX 吗？」列编号，用户同意后只让模型点用户选的那条；</li>
 *   <li>没有可选的、但有「切换地址」入口 → 只让模型打开地址列表看看，不选；</li>
 *   <li>都没有 → 停下，说清卡在哪（等用户自己换好再继续）。</li>
 * </ol>
 * 送不了的状态下，模型自己去点地址条目（不是用户选的那条）/ 新建地址，一律先拦。付款前停下的规矩不变（不在这里管）。
 *
 * <p>判断都是看字（页面文字），没有用到任何 App 的内部编号。正则只用普通字符（安卓 7/8 的 ICU 也认）。
 */
public final class DeliveryBlock {

    private DeliveryBlock() {}

    /** 「送不了」的说法。 */
    static final Pattern BLOCKED = Pattern.compile(
            "无法配送|不能配送|暂不配送|不支持配送|暂不支持配送|不在配送范围|超出配送范围|超出了配送范围|超出配送距离|超出配送区域|不在服务范围|超出服务范围"
            + "|地址不在[^，。,.]{0,6}范围|该地址[^，。,.]{0,6}(送不了|无法送达|不可配送|不配送)|无法送达|不可配送|配送不到|送不到");

    /** 地址里常见的字（够两个、或一个加门牌数字，才像地址）。 */
    private static final Pattern ADDR_MARK = Pattern.compile("省|市|区|县|镇|乡|村|路|街|道|巷|弄|号|栋|幢|座|单元|室|小区|大厦|花园|公寓|苑|广场|大院|校区|园区");
    private static final Pattern DIGIT = Pattern.compile("[0-9０-９]");
    /** 操作入口（不是地址本身）。 */
    private static final Pattern ACTION = Pattern.compile("^(新增|添加|新建|编辑|修改|删除|管理|切换|更换|选择|换个|使用|确认|保存|去|查看)|地址管理|收货地址$|^我的地址$");
    /** 换地址的入口。 */
    private static final Pattern SWITCH = Pattern.compile("切换地址|更换地址|修改地址|选择地址|选择收货地址|更换收货地址|切换收货地址|换个地址|换地址|地址管理|我的地址|收货地址");
    /** 新建地址（送不了时不让模型自己建）。 */
    private static final Pattern NEW_ADDR = Pattern.compile("(新增|添加|新建)[^，。]{0,4}地址");

    /** 最多列几条。 */
    static final int MAX_OPTIONS = 5;

    /** 这一屏看到的情况。 */
    public static final class Verdict {
        /** 页面上写着送不了。 */
        public boolean blocked;
        /** 送不了的那句话（截短）。 */
        public String notice = "";
        /** 可以换的地址（不含原地址、不含标着送不了的）。 */
        public final List<UiTree.El> options = new ArrayList<UiTree.El>();
        /** 换地址的入口（没有 = null）。 */
        public UiTree.El switchEntry;

        /** 同一种情况只问一次（送不了的话 + 可选地址）。 */
        public String signature() {
            StringBuilder sb = new StringBuilder(notice);
            for (UiTree.El e : options) sb.append('|').append(e.label);
            return sb.toString();
        }
    }

    public static boolean blockedText(String s) {
        return s != null && s.length() > 0 && BLOCKED.matcher(s).find();
    }

    /** 像一条收货地址（可点的条目）。 */
    public static boolean looksLikeAddress(UiTree.El e) {
        if (e == null || !e.click || e.edit || e.password) return false;
        return looksLikeAddress(e.label);
    }

    static boolean looksLikeAddress(String label) {
        if (label == null) return false;
        String s = label.trim();
        if (s.length() < 6 || s.length() > 160) return false;
        if (ACTION.matcher(s).find() && s.length() < 12) return false;   // 「切换地址」「新增收货地址」这种入口
        java.util.regex.Matcher m = ADDR_MARK.matcher(s);
        int marks = 0;
        while (m.find()) marks++;
        return marks >= 2 || (marks >= 1 && DIGIT.matcher(s).find());
    }

    static boolean isSwitchEntry(UiTree.El e) {
        if (e == null || !e.click || e.label == null) return false;
        String s = e.label.trim();
        return s.length() > 0 && s.length() <= 16 && SWITCH.matcher(s).find() && !NEW_ADDR.matcher(s).find();
    }

    static boolean isNewAddress(UiTree.El e) {
        return e != null && e.label != null && NEW_ADDR.matcher(e.label).find();
    }

    /**
     * 看这一屏。
     *
     * @param t         当前界面
     * @param listMode  上一轮让模型打开过地址列表（列表页本身多半不写「送不了」，照样挑地址给用户选）
     */
    public static Verdict check(UiTree t, boolean listMode) {
        Verdict v = new Verdict();
        if (t == null) return v;
        UiTree.El notice = null;
        for (UiTree.El e : t.els) {
            if (blockedText(e.label)) {
                notice = e;
                break;
            }
        }
        if (notice == null && !listMode) return v;
        v.blocked = notice != null || listMode;
        v.notice = notice == null ? "" : clip(notice.label, 40);
        List<UiTree.El> addrs = new ArrayList<UiTree.El>();
        for (UiTree.El e : t.els) {
            if (looksLikeAddress(e)) addrs.add(e);
            else if (v.switchEntry == null && isSwitchEntry(e)) v.switchEntry = e;
        }
        // 去掉标着送不了的；没有标记时，离「送不了」那句最近的一条当成原地址（下单页上只露一条地址 = 原地址，不是可换的）
        List<UiTree.El> clean = new ArrayList<UiTree.El>();
        for (UiTree.El e : addrs) if (!blockedText(e.label)) clean.add(e);
        if (clean.size() == addrs.size() && notice != null && !clean.isEmpty() && !listMode) {
            UiTree.El cur = null;
            int best = Integer.MAX_VALUE;
            for (UiTree.El e : clean) {
                int d = Math.abs(e.cy() - notice.cy());
                if (d < best) {
                    best = d;
                    cur = e;
                }
            }
            clean.remove(cur);
            if (v.switchEntry == null) v.switchEntry = cur;   // 点原地址那一条一般就是去换地址
        }
        for (int i = 0; i < clean.size() && v.options.size() < MAX_OPTIONS; i++) v.options.add(clean.get(i));
        return v;
    }

    /** 确认卡上的话：「原地址送不了，换成 XX 吗？」+ 编号列表。 */
    public static String askText(Verdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append("原地址送不了，换成「").append(clip(v.options.get(0).label, 40)).append("」吗？");
        if (v.options.size() > 1) {
            sb.append("\n能换的地址：");
            for (int i = 0; i < v.options.size(); i++) sb.append('\n').append(i + 1).append(". ").append(clip(v.options.get(i).label, 40));
            sb.append("\n要换别的，回一个编号就行。");
        }
        sb.append("\n只换地址，付款前我还是会停下。");
        return sb.toString();
    }

    /**
     * 用户的回答 → 选第几条（0 起）。没说话 / 说「好、可以、换」= 第 1 条；说了编号就按编号；编号不在列表里 = -1。
     */
    public static int pick(String answer, int n) {
        if (n <= 0) return -1;
        if (answer == null) return 0;
        String a = answer.trim();
        if (a.length() == 0) return 0;
        java.util.regex.Matcher m = Pattern.compile("[0-9]+").matcher(a);
        if (m.find()) {
            int k;
            try {
                k = Integer.parseInt(m.group());
            } catch (NumberFormatException e) {
                return -1;
            }
            return k >= 1 && k <= n ? k - 1 : -1;
        }
        String[] cn = {"一", "二", "三", "四", "五"};
        for (int i = 0; i < cn.length && i < n; i++) {
            if (a.contains("第" + cn[i])) return i;
        }
        return 0;
    }

    /** 用户选好了：这一轮只点那一条。 */
    public static String changeOnlyNote(UiTree.El chosen) {
        return "收货地址送不了。用户同意换成「" + clip(chosen.label, 40) + "」：这一轮只点这条地址 [" + chosen.id
                + "]，别的都不要动、不要自己挑别的地址、不要新建地址；换好后再接着原来的任务，付款前照旧停下。";
    }

    /** 没有可选的地址、但有换地址入口：只打开地址列表看。 */
    public static String listOnlyNote(UiTree.El entry) {
        return "收货地址送不了，这一页上没有别的地址可选。这一轮只点 [" + entry.id + "]「" + clip(entry.label, 16)
                + "」打开地址列表看看有哪些，不要选任何地址、不要新建地址。";
    }

    /** 哪都换不了时，停下来给用户的话。 */
    public static String stuckText(Verdict v) {
        return "原地址送不了" + (v.notice.length() > 0 ? "（页面上写着「" + v.notice + "」）" : "")
                + "，页面上也找不到能换的地址。你手动换好地址后点继续，我接着做；不想换就停下。";
    }

    /**
     * 送不了的状态下，这一步点的东西要不要拦。
     *
     * @param chosen 用户选的那条地址的文字（还没选 = null）
     * @param entry  允许点的换地址入口文字（没有 = null）
     * @return 拦下的原因（null = 放行）
     */
    public static String tapBlocked(UiTree.El e, String chosen, String entry) {
        if (e == null) return null;
        if (isNewAddress(e)) return "原地址送不了，换哪个地址要用户定，不能自己新建地址";
        if (!looksLikeAddress(e)) return null;
        if (chosen != null && chosen.equals(e.label)) return null;
        if (chosen == null && entry != null && entry.equals(e.label)) return null;   // 原地址那条就是换地址的入口
        return "原地址送不了，换哪个地址要用户定，不能自己点地址";
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
