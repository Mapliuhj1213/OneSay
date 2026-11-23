package com.onesay.assistant.v2.ui.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [fix-1123-c] 控制台里「只让手填」的地方改成开关 / 档位 / 列表的选项计算（纯 Java，JVM 可测；界面在 {@link Pickers}）。
 * 2026-11-22 预跑报告第 5 节手机端六处：通知上岛白名单、提前提醒分钟数、免打扰时段、新建事件任务、朗读音色、模型名。
 */
public final class FormChoices {
    private FormChoices() {}

    // ------------------------------------------------------------------ 提前多少分钟：档位

    static final int[] LEAD_STEPS = {0, 5, 10, 15, 30, 60, 90, 120};

    /** lo..hi 范围内的档位（0 = 「准时」或「不提前」）；当前值不在档位里也列进去（选中它）。 */
    public static List<Integer> minuteSteps(int lo, int hi, int current) {
        List<Integer> out = new ArrayList<Integer>();
        for (int s : LEAD_STEPS) if (s >= lo && s <= hi) out.add(s);
        if (lo > 0 && !out.contains(lo)) out.add(0, lo);
        if (current >= lo && current <= hi && !out.contains(current)) out.add(current);
        Collections.sort(out);
        return out;
    }

    public static String minuteLabel(int m) {
        if (m <= 0) return "准时提醒（不提前）";
        if (m % 60 == 0) return "提前 " + (m / 60) + " 小时";
        return "提前 " + m + " 分钟";
    }

    // ------------------------------------------------------------------ 免打扰时段：HH:MM ↔ 时、分

    /** "23:00" → {23, 0}；认不出 → def 的。 */
    public static int[] hm(String s, String def) {
        int[] r = parseHm(s);
        return r != null ? r : parseHm(def) != null ? parseHm(def) : new int[] {0, 0};
    }

    static int[] parseHm(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,2})[:：](\\d{2})\\s*$").matcher(s);
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
        if (h > 23 || mi > 59) return null;
        return new int[] {h, mi};
    }

    public static String formatHm(int h, int m) {
        return String.format(Locale.ROOT, "%02d:%02d", Math.max(0, Math.min(23, h)), Math.max(0, Math.min(59, m)));
    }

    /** 免打扰时段的一句说明（跨夜写清楚）。 */
    public static String quietSpan(String start, String end) {
        int[] a = hm(start, "23:00"), b = hm(end, "07:00");
        int s = a[0] * 60 + a[1], e = b[0] * 60 + b[1];
        if (s == e) return "开始和结束一样：等于整天都免打扰";
        return formatHm(a[0], a[1]) + " 到 " + (e < s ? "第二天 " : "") + formatHm(b[0], b[1]);
    }

    // ------------------------------------------------------------------ 已装 App 列表（勾选 / 搜索）

    public static final class App {
        public final String label, pkg;

        public App(String label, String pkg) {
            this.label = label == null || label.trim().length() == 0 ? pkg : label.trim();
            this.pkg = pkg;
        }
    }

    /** 排序：勾着的在前，再按名字；同名按包名。去重（同一个包只留一条）。 */
    public static List<App> sortApps(List<App> all, final Set<String> checked) {
        List<App> out = new ArrayList<App>();
        Set<String> seen = new LinkedHashSet<String>();
        if (all != null) for (App a : all) if (a != null && a.pkg != null && seen.add(a.pkg)) out.add(a);
        Collections.sort(out, new Comparator<App>() {
            @Override public int compare(App x, App y) {
                boolean cx = checked != null && checked.contains(x.pkg), cy = checked != null && checked.contains(y.pkg);
                if (cx != cy) return cx ? -1 : 1;
                int c = x.label.compareToIgnoreCase(y.label);
                return c != 0 ? c : x.pkg.compareTo(y.pkg);
            }
        });
        return out;
    }

    /** 搜索：名字或包名里有这个词（不分大小写）。空词 = 全部。 */
    public static List<App> filter(List<App> all, String q) {
        String k = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (k.length() == 0) return all;
        List<App> out = new ArrayList<App>();
        for (App a : all) if (a.label.toLowerCase(Locale.ROOT).contains(k) || a.pkg.toLowerCase(Locale.ROOT).contains(k)) out.add(a);
        return out;
    }

    /** 已勾选的包名 → 存储用的「a,b,c」（按勾选顺序）。 */
    public static String joinPkgs(Set<String> pkgs) {
        StringBuilder b = new StringBuilder();
        if (pkgs != null) for (String p : pkgs) if (p != null && p.length() > 0) b.append(b.length() > 0 ? "," : "").append(p);
        return b.toString();
    }

    /** 列表上显示的「已选 N 个：微信、QQ…」。 */
    public static String pickedSummary(Set<String> pkgs, List<App> all) {
        if (pkgs == null || pkgs.isEmpty()) return "没有";
        List<String> names = new ArrayList<String>();
        for (String p : pkgs) {
            String n = p;
            if (all != null) for (App a : all) if (a.pkg.equals(p)) { n = a.label; break; }
            if (names.size() < 3) names.add(n);
        }
        return pkgs.size() + " 个：" + join(names, "、") + (pkgs.size() > 3 ? "…" : "");
    }

    // ------------------------------------------------------------------ 关键词小标签

    public static final int MAX_TAGS = 20;

    /** 加一个（可一次粘多个，逗号 / 顿号 / 空格分开）；去空、去重、最多 {@link #MAX_TAGS}。 */
    public static List<String> addTags(List<String> tags, String input) {
        List<String> out = new ArrayList<String>(tags == null ? Collections.<String>emptyList() : tags);
        if (input == null) return out;
        for (String x : input.split("[,，、;；\\s]+")) {
            String v = x.trim();
            if (v.length() > 0 && v.length() <= 30 && !out.contains(v) && out.size() < MAX_TAGS) out.add(v);
        }
        return out;
    }

    public static List<String> removeTag(List<String> tags, String t) {
        List<String> out = new ArrayList<String>(tags == null ? Collections.<String>emptyList() : tags);
        out.remove(t);
        return out;
    }

    // ------------------------------------------------------------------ 模型名：列表为主，自定义放最后

    public static final String CUSTOM = "自己填一个…";

    /** 当前值 + 预置 + 服务商拉回来的，去重，最后一项「自己填一个…」。 */
    public static List<String> modelChoices(String current, String preset, List<String> fetched) {
        List<String> out = new ArrayList<String>();
        addIf(out, current);
        addIf(out, preset);
        if (fetched != null) for (String f : fetched) if (out.size() < 200) addIf(out, f);
        out.add(CUSTOM);
        return out;
    }

    /** 已知的朗读模型（百炼语音合成文档 help.aliyun.com/zh/model-studio/tts-model 的 CosyVoice 列表，2026-11 查；以服务商为准，列表最后仍能自己填）。 */
    public static final String[] TTS_MODELS = {"cosyvoice-v3.5-flash", "cosyvoice-v3.5-plus", "cosyvoice-v3-flash", "cosyvoice-v3-plus", "cosyvoice-v2"};

    static void addIf(List<String> l, String v) {
        if (v == null) return;
        String t = v.trim();
        if (t.length() > 0 && !l.contains(t)) l.add(t);
    }

    /** 选中项的显示：当前值标「（在用）」。 */
    public static String[] labels(List<String> items, String current) {
        String[] a = new String[items.size()];
        for (int i = 0; i < a.length; i++) {
            String s = items.get(i);
            a[i] = current != null && s.equals(current.trim()) && !CUSTOM.equals(s) ? s + "（在用）" : s;
        }
        return a;
    }

    // ------------------------------------------------------------------ 事件任务：Wi‑Fi / 蓝牙 从列表选

    public static final String ANY = "任意（不限哪个）";
    public static final String MANUAL = "列表里没有，自己填…";

    /** 选项：任意 + 名字（去引号、去空、去重、排序）+ 自己填。 */
    public static List<String> targetChoices(List<String> names) {
        List<String> out = new ArrayList<String>();
        out.add(ANY);
        List<String> mid = new ArrayList<String>();
        if (names != null) for (String n : names) {
            String v = unquote(n);
            if (v.length() > 0 && !"<unknown ssid>".equalsIgnoreCase(v) && !mid.contains(v)) mid.add(v);
        }
        Collections.sort(mid, String.CASE_INSENSITIVE_ORDER);
        out.addAll(mid);
        out.add(MANUAL);
        return out;
    }

    /** Wi‑Fi 名系统给的是带引号的 "\"Home\""。 */
    public static String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.trim();
    }

    /** 选了哪项 → 存进任务的 target（任意 = ""；自己填 = null，界面再问）。 */
    public static String targetOf(String choice) {
        if (choice == null || ANY.equals(choice)) return "";
        if (MANUAL.equals(choice)) return null;
        return choice;
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? sep : "").append(s);
        return b.toString();
    }
}
