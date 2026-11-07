package com.onesay.assistant.v2.soul;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [soul] 文本工具（纯 Java）：归一化、二元切分（检索用）、去重键哈希、类别判定、约定到期日、隐私分级。
 *
 * <p>检索为什么用自己切的二元组：研究 §6.4 建议首版只做关键词检索，FTS5/trigram 在端上不确定。
 * 真机是 Android 8（SQLite 3.18 左右，trigram 分词器要 3.34+，Android 平台 SQLite 也未开启 FTS5），
 * 所以不用 FTS，表很小（几百到几千行），在 Java 里算 bigram 重叠即可。
 */
public final class MemoryText {

    private MemoryText() {}

    private static final Pattern PUNCT = Pattern.compile("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》…—·～]+");
    private static final Pattern FILLER = Pattern.compile("^(请你?|你|麻烦你?|帮我|给我|我想让你|其实|那个|就是)+");

    /**
     * ★ 综合包（soul × bike-chooser）：大模型回答末尾的应用选择尾巴（{@code <app_choice>{…store_query…}</app_choice>}）
     * 是给界面的机器指令，不是用户的事实，永远不进长期记忆 / 人设。先按规范剥掉成对尾巴与残缺开头，
     * 剩下的文字里如果还带 app_choice / store_query 字样（模型漏了标签直接吐 JSON）就整条不收（返回 null）。
     */
    public static String stripMachineTail(String s) {
        if (s == null) return null;
        String t = com.onesay.assistant.v2.roles.AppChoiceSpec.stripForDisplay(s);
        if (t == null) return null;
        int open = t.indexOf("<app_choice");
        if (open >= 0) t = t.substring(0, open);
        t = t.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("app_choice") || low.contains("store_query")) return null;
        return t;
    }

    /** 去标点空白、转小写。 */
    public static String norm(String s) {
        if (s == null) return "";
        return PUNCT.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 去重键：归一化后去掉口头前缀与句尾语气词。 */
    public static String key(String s) {
        String n = norm(s);
        n = FILLER.matcher(n).replaceAll("");
        n = n.replaceAll("(了|啊|呀|哦|吧|呢|的)+$", "");
        return n;
    }

    /** 键哈希（forgotten 表只存哈希，不存被忘的原文）。 */
    public static String keyHash(String s) {
        String k = key(s);
        if (k.length() == 0) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(k.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(k.hashCode());
        }
    }

    /** 二元组（中文按相邻两字；英文数字串整体当一个词）。 */
    public static List<String> grams(String s) {
        List<String> out = new ArrayList<String>();
        String n = norm(s);
        StringBuilder word = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean cjk = c >= 0x3400 && c <= 0x9FFF;
            if (cjk) {
                if (word.length() > 0) { out.add(word.toString()); word.setLength(0); }
                if (prevCjk != 0) out.add("" + prevCjk + c);
                prevCjk = c;
            } else {
                prevCjk = 0;
                if (Character.isLetterOrDigit(c)) word.append(c);
                else if (word.length() > 0) { out.add(word.toString()); word.setLength(0); }
            }
        }
        if (word.length() > 0) out.add(word.toString());
        // 单字中文句（「车」）也给一个单字 gram，免得完全检索不到
        if (out.isEmpty() && n.length() > 0) out.add(n);
        return out;
    }

    /** 停用二元组：太常见、不区分内容。 */
    private static final String[] STOP = {"我的", "一下", "什么", "怎么", "是不", "不是", "这个", "那个", "你说", "我们", "记得", "还记", "以前", "之前", "上次", "知道", "告诉", "帮我", "我想", "可以", "现在", "今天", "一个",
            // 推荐 / 请求类句子的功能词（「给我推荐一杯咖啡」只该按「咖啡」检索）
            "给我", "我推", "推荐", "荐一", "建议", "一杯", "一家", "一下", "一点", "我选", "选一", "挑一", "帮我选"};

    public static boolean isStop(String g) {
        for (String s : STOP) if (s.equals(g)) return true;
        return false;
    }

    // ------------------------------------------------------------ 类别判定

    private static final Pattern DIRECTIVE = Pattern.compile(
            "^(以后|今后|往后|下次|每次|从今往后|从现在起)|^(你)?(别|不要|不用|少|多)(再)?(叫我|喊我|说|讲|问|给我|提醒|打扰|用)|叫我|称呼我|喊我");
    private static final Pattern PREF = Pattern.compile(
            "喜欢|不喜欢|爱吃|爱喝|爱看|爱听|讨厌|不吃|不喝|吃不了|忌口|过敏|偏好|口味|习惯|常用|最爱|受不了|怕辣|不辣|素食|吃素");
    private static final Pattern COMMIT = Pattern.compile(
            "答应|约了|约好|要在.{0,8}(前|之前)|得在.{0,8}(前|之前)|截止|到期|deadline|记得(去|要|给|交|还|买|带|打)|要还|还给|待办|提醒我|之前(交|还|给|办)");

    /** 核心记忆类别：directive（画像指令）/ commit / pref / fact。 */
    public static String classify(String text) {
        String t = text == null ? "" : text.trim();
        if (DIRECTIVE.matcher(t).find()) return Claim.K_DIRECTIVE;
        if (COMMIT.matcher(t).find()) return Claim.K_COMMIT;
        if (PREF.matcher(t).find()) return Claim.K_PREF;
        return Claim.K_FACT;
    }

    private static final String[][] TOPICS = {
            {"称呼", "叫我|称呼我|喊我|称呼"},
            {"回答风格", "简短|简洁|啰嗦|详细|长一点|短一点|说重点|废话|语速|口语|书面"},
            {"打扰", "打扰|免打扰|安静|别吵|晚上|半夜|夜里"},
            {"语言", "普通话|粤语|英文|英语|中文"},
            {"确认", "确认|直接做|先问|问我"},
            {"打车", "打车|叫车"},
            {"导航", "导航|地图"},
            {"音乐", "音乐|听歌|放歌"},
    };

    /** 画像指令的主题（同主题新指令替代旧指令）；认不出返回 ""。 */
    public static String topic(String text) {
        if (text == null) return "";
        for (String[] t : TOPICS) if (Pattern.compile(t[1]).matcher(text).find()) return t[0];
        return "";
    }

    private static final Pattern HEALTH_FIN = Pattern.compile(
            "病|药|医院|体检|血压|血糖|怀孕|月经|抑郁|焦虑|手术|诊断|工资|收入|存款|欠款|贷款|房贷|负债|借钱|理财|股票|余额");

    public static String privacy(String text) {
        if (text != null && HEALTH_FIN.matcher(text).find()) return Claim.P_HEALTH_FINANCE;
        return Claim.P_NORMAL;
    }

    // ------------------------------------------------------------ 约定到期

    private static final Pattern MD = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]");
    private static final Pattern D = Pattern.compile("(?<![月\\d])(\\d{1,2})[号日](?!子)");
    private static final Pattern WEEK = Pattern.compile("(下下|下|这|本)?(周|星期|礼拜)([一二三四五六日天])");
    private static final String WD = "一二三四五六日天";

    /**
     * 约定的有效期截止（当天 23:59:59 的毫秒）；认不出返回 0（不过期，交给 90 天退役）。
     * 只认「今天 / 明天 / 后天 / 周X / 下周X / M月D日 / D号」，够语音场景用。
     */
    public static long validUntil(String text, long now, TimeZone tz) {
        if (text == null) return 0;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        Matcher m;
        if (text.contains("后天")) c.add(Calendar.DAY_OF_MONTH, 2);
        else if (text.contains("明天")) c.add(Calendar.DAY_OF_MONTH, 1);
        else if (text.contains("今天") || text.contains("今晚")) { /* 当天 */ }
        else if ((m = MD.matcher(text)).find()) {
            int mo = Integer.parseInt(m.group(1)) - 1, d = Integer.parseInt(m.group(2));
            if (mo > 11 || d < 1 || d > 31) return 0;
            int y = c.get(Calendar.YEAR);
            c.set(y, mo, d);
            if (c.getTimeInMillis() < now - 86400000L) c.set(y + 1, mo, d);
        } else if ((m = WEEK.matcher(text)).find()) {
            int idx = WD.indexOf(m.group(3).charAt(0));
            int target = idx >= 6 ? Calendar.SUNDAY : Calendar.MONDAY + idx;   // 周一..周六 / 周日
            int cur = c.get(Calendar.DAY_OF_WEEK);
            // 以周一为一周开始
            int curIdx = (cur + 5) % 7, tgtIdx = (target + 5) % 7;
            int delta = tgtIdx - curIdx;
            String pre = m.group(1);
            if ("下".equals(pre)) delta += 7;
            else if ("下下".equals(pre)) delta += 14;
            else if (pre == null && delta < 0) delta += 7;
            c.add(Calendar.DAY_OF_MONTH, delta);
        } else if ((m = D.matcher(text)).find()) {
            int d = Integer.parseInt(m.group(1));
            if (d < 1 || d > 31) return 0;
            if (d < c.get(Calendar.DAY_OF_MONTH)) c.add(Calendar.MONTH, 1);
            c.set(Calendar.DAY_OF_MONTH, Math.min(d, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        } else {
            return 0;
        }
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static String day(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 注入用的短日期「10-30」。 */
    public static String shortDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d-%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 按码点数截断（检索片段用；核心记忆不截断）。 */
    public static String clip(String s, int max) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= max) return s;
        int end = s.offsetByCodePoints(0, Math.max(0, max - 1));
        return s.substring(0, end) + "…";
    }

    public static int len(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** gram 频次表。 */
    static Map<String, Integer> bag(String s) {
        Map<String, Integer> m = new HashMap<String, Integer>();
        for (String g : grams(s)) {
            Integer v = m.get(g);
            m.put(g, v == null ? 1 : v + 1);
        }
        return m;
    }
}
