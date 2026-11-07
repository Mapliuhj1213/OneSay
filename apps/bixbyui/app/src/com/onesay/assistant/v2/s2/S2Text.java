package com.onesay.assistant.v2.s2;

import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * [s2] 文本工具（纯 Java）：轻量归一化、中文 bigram 切词、日期标签、金额格式化。
 *
 * <p><b>为什么不用 TriageDecider.normalize</b>：它会把 {@code -} 等 ASCII 标点一起去掉（"B2-17" → "b217"），
 * 用来判意图没问题，但会毁掉要存下来的内容。这里分两种形态：
 * {@link #key}（判意图/打分用：去空白与中文标点、小写，保留字母数字与 {@code -}）和 {@link #clean}（存储/展示用：只修剪首尾）。
 */
public final class S2Text {

    private S2Text() {}

    /** OneSay：不剥离厂商唤醒词残留（原 嗨比西比 / Bixby 表已删除）。 */
    private static final String[] WAKE = {};

    /** 判意图用：去空白和常见中文/英文句读（保留 - 与 字母数字），小写，剥唤醒词残留。 */
    public static String key(String s) {
        if (s == null) return "";
        String q = s.toLowerCase(Locale.ROOT).replaceAll("(?<!\\d)\\.|\\.(?!\\d)", "")
                .replaceAll("[\\s，,。！!？?、：:；;“”\"‘’'《》（）()【】…·～~]", "");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String w : WAKE) {
                if (q.startsWith(w) && q.length() > w.length()) {
                    q = q.substring(w.length());
                    changed = true;
                }
            }
        }
        return q;
    }

    /** 存储/展示用：剥唤醒词、修剪首尾空白与句末标点，内部空白压成一个。 */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        String low = t.toLowerCase(Locale.ROOT);
        for (String w : WAKE) {
            if (low.startsWith(w)) {
                t = t.substring(w.length()).replaceFirst("^[\\s，,、:：]+", "");
                break;
            }
        }
        t = t.replaceFirst("^[\\s，,、:：；;。.!！]+", "").replaceFirst("[\\s，,、。.!！~～…；;]+$", "");
        return t;
    }

    /** 无空白紧凑形（槽位解析用，保留大小写与 -）。 */
    public static String compact(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 打分时忽略的 bigram（功能词）。 */
    private static final Set<String> STOP = new LinkedHashSet<String>();
    static {
        String[] w = {"我的", "一下", "在哪", "哪了", "哪儿", "哪里", "什么", "记的", "上次", "之前", "那条", "这条", "了吗",
                "是什么", "我把", "把我", "帮我", "记一", "记下", "多少", "是多", "我记", "一个", "那个", "这个"};
        for (String x : w) STOP.add(x);
    }

    /** 中文 bigram + 连续字母数字整词（"B2-17" 作为一个 token），用于关键词打分。 */
    public static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<String>();
        String k = key(s);
        StringBuilder alnum = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (isCjk(c)) {
                flush(alnum, out);
                if (prevCjk != 0) {
                    String bg = "" + prevCjk + c;
                    if (!STOP.contains(bg)) out.add(bg);
                }
                prevCjk = c;
            } else if (Character.isLetterOrDigit(c) || c == '-') {
                alnum.append(c);
                prevCjk = 0;
            } else {
                flush(alnum, out);
                prevCjk = 0;
            }
        }
        flush(alnum, out);
        return out;
    }

    /** 单字集合（bigram 对不上时的兜底，如「停车」对「车停在」）。 */
    public static Set<Character> chars(String s) {
        Set<Character> out = new LinkedHashSet<Character>();
        String k = key(s);
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (isCjk(c) && "的了我在哪是把吗呢一下那这条个记".indexOf(c) < 0) out.add(Character.valueOf(c));
        }
        return out;
    }

    private static void flush(StringBuilder sb, Set<String> out) {
        if (sb.length() > 0) {
            String t = sb.toString().replaceAll("^-+|-+$", "");
            if (t.length() > 0) out.add(t);
            sb.setLength(0);
        }
    }

    public static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    // ------------------------------------------------------------------ 日期

    public static Calendar cal(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz != null ? tz : TimeZone.getDefault(), Locale.CHINA);
        c.setTimeInMillis(ms);
        return c;
    }

    /** yyyy-MM-dd */
    public static String day(long ms, TimeZone tz) {
        Calendar c = cal(ms, tz);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 某天 0 点的毫秒。 */
    public static long dayStart(long ms, TimeZone tz) {
        Calendar c = cal(ms, tz);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 两个时刻相差几个自然日（b 相对 a，b 在后为正）。 */
    public static int dayDiff(long a, long b, TimeZone tz) {
        long da = dayStart(a, tz);
        long db = dayStart(b, tz);
        return (int) Math.round((db - da) / 86400000.0);
    }

    /** "10月7日"。 */
    public static String md(long ms, TimeZone tz) {
        Calendar c = cal(ms, tz);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
    }

    /** yyyy-MM-dd → "10月7日"。 */
    public static String mdOfDay(String day) {
        if (day == null || day.length() < 10) return day == null ? "" : day;
        try {
            int m = Integer.parseInt(day.substring(5, 7));
            int d = Integer.parseInt(day.substring(8, 10));
            return m + "月" + d + "日";
        } catch (NumberFormatException e) {
            return day;
        }
    }

    /** 相对标签：今天 / 昨天 / 前天 / null（更早）。 */
    public static String relative(long then, long now, TimeZone tz) {
        int d = dayDiff(then, now, tz);
        if (d == 0) return "今天";
        if (d == 1) return "昨天";
        if (d == 2) return "前天";
        return null;
    }

    /** "10月7日（昨天）" / "10月8日（今天 20:15）" / "9月30日"。 */
    public static String whenLabel(long then, long now, TimeZone tz) {
        String rel = relative(then, now, tz);
        String md = md(then, tz);
        if ("今天".equals(rel)) {
            Calendar c = cal(then, tz);
            return md + "（今天 " + String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + "）";
        }
        return rel != null ? md + "（" + rel + "）" : md;
    }

    // ------------------------------------------------------------------ 金额

    /** 分 → "35"、"62.5"、"0.5"、"1234.56"。 */
    public static String yuan(long fen) {
        boolean neg = fen < 0;
        long a = Math.abs(fen);
        long y = a / 100;
        long f = a % 100;
        String s;
        if (f == 0) s = String.valueOf(y);
        else if (f % 10 == 0) s = y + "." + (f / 10);
        else s = y + "." + (f < 10 ? "0" + f : String.valueOf(f));
        return (neg ? "-" : "") + s;
    }

    /** "¥35"。 */
    public static String rmb(long fen) {
        return "¥" + yuan(fen);
    }

    /** 截断展示。 */
    public static String brief(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
