package com.onesay.assistant.v2.errand;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ 11-04 D（用户：「我想买一张1000以内的人体工学椅，有没有推荐」→ 搜索词成了「张一千块以内的人体工学椅 有没有推荐」）：
 * 购物 / 调研搜索词抽取成「品类 + 关键约束」，价格约束单独成筛选条件（纯函数，JVM 可测）。
 *
 * <p>根因：{@link ErrandIntent#clean} 只用一串 STRIP 正则剥指令词——量词表里没有「张 / 把 / 套 / 辆」，「有没有推荐」不在剥离表，
 * 价格短语原样留在关键词里；于是整句进了什么值得买 / 京东 / 天猫的站内搜索，结果都是「有没有推荐」的帖子或空页。
 *
 * <ul>
 *   <li>{@link #keyword}：品类 / 型号（「人体工学椅」），不含价格、不含口语；</li>
 *   <li>{@link #priceMax} / {@link #priceMin}：元，0 = 没说；「1000以内 / 一千块以下 / 不超过1k / 预算1000」→ max=1000，「1000左右」→ max=1100；</li>
 *   <li>{@link #recommend}：没有具体型号的推荐类（「有没有推荐 / 哪款好 / 推荐一下」或只说品类）→ 走「推荐 3 款代表款」而不是单品比价。</li>
 * </ul>
 * {@link #valid} 校验抽取结果（长度 2–20、不含原句、没有口语填充词），不过关就回退规则重抽。
 */
public final class ShopQuery {

    public final String keyword;
    public final int priceMax;
    public final int priceMin;
    public final boolean recommend;

    ShopQuery(String keyword, int priceMax, int priceMin, boolean recommend) {
        this.keyword = keyword;
        this.priceMax = priceMax;
        this.priceMin = priceMin;
        this.recommend = recommend;
    }

    /** 站内 / 网页搜索词：品类（推荐类加「推荐」）；价格不进关键词。 */
    public String searchQuery() {
        return recommend ? keyword + " 推荐" : keyword;
    }

    /** 给模型 / 日志看的约束说明。 */
    public String constraint() {
        if (priceMax > 0 && priceMin > 0) return priceMin + "–" + priceMax + " 元";
        if (priceMax > 0) return "≤" + priceMax + " 元";
        if (priceMin > 0) return "≥" + priceMin + " 元";
        return "";
    }

    @Override public String toString() {
        return "ShopQuery{" + keyword + (constraint().length() > 0 ? " " + constraint() : "") + (recommend ? " 推荐" : "") + "}";
    }

    static final String NUM = "([0-9]+(?:\\.[0-9]+)?\\s*[kKwW千万]?|[零一二两三四五六七八九十百千万]+)";
    static final String UNIT = "(?:块钱|块|元|rmb|RMB|¥)?";
    static final Pattern MAX = Pattern.compile(
            "(?:预算|价格|价位|价钱)?(?:在|是)?" + NUM + "\\s*" + UNIT + "\\s*(?:以内|以下|之内|内|之下|封顶|顶天)"
                    + "|(?:不超过|不到|低于|少于|别超过|不要超过|最多|顶多|预算(?:在|是)?)\\s*" + NUM + "\\s*" + UNIT);
    static final Pattern AROUND = Pattern.compile(NUM + "\\s*" + UNIT + "\\s*(?:左右|上下|出头)");
    static final Pattern RANGE = Pattern.compile(NUM + "\\s*" + UNIT + "\\s*(?:到|至|-|~|～)\\s*" + NUM + "\\s*" + UNIT);
    static final Pattern MIN = Pattern.compile("(?:不低于|高于|至少|起码|最少)\\s*" + NUM + "\\s*" + UNIT + "|" + NUM + "\\s*" + UNIT + "\\s*(?:以上|起步)");

    /** 口语填充词（先长后短）。 */
    static final Pattern FILLER = Pattern.compile(
            "^(?:请|麻烦|嗨)|有没有(?:什么|啥)?(?:好的|好用的|值得买的|推荐的?)?|有什么(?:好的|好用的|值得买的|推荐的?)?|有啥(?:好的|推荐的?)?"
                    + "|(?:给我|帮我|麻烦)?推荐(?:一下|几款|一款|个|下)?|哪款(?:好|比较好|值得买|性价比高)?|哪个(?:好|牌子好|比较好)?|什么牌子(?:好|的好)?"
                    + "|性价比(?:高的?|最高的?)?|值得买的?|好用的|好一点的?|靠谱的?|比较好的?|不错的?"
                    + "|我想(?:要|买|入手|换)?|我要(?:买|入手)?|想要|想买|想入手|打算买|准备买|帮我(?:买|找|挑|选|看看|搜|查)?|给我(?:买|找|挑|选)?|替我|帮忙(?:买|找)?"
                    + "|比比价|比一下价|比价|哪家便宜|哪里买便宜|最便宜的?|网购|网上买|帮我淘|买|购买|入手|下单"
                    + "|吗|呢|吧|呀|啊|嘛|哈|了|的话");
    static final Pattern QUANT = Pattern.compile("^(?:一|两|几)?(?:张|把|台|部|双|件|只|款|个|套|辆|块|支|瓶|盒|箱|条|本|副|顶|床|架|组|对)");
    static final Pattern RECOMMEND = Pattern.compile("推荐|哪款|哪个好|哪个牌子|什么牌子|有没有|有什么|有啥|怎么选|选哪|值得买|性价比");
    /** 具体型号：带字母数字型号（V3、M18、iPhone 15、RTX4060）或「XX Pro / Max / Ultra」。 */
    static final Pattern MODEL = Pattern.compile("[A-Za-z]+\\s*-?\\d+|\\d+\\s*[A-Za-z]+|(?i:pro|max|ultra|plus|mini)\\b");

    public static ShopQuery parse(String utterance) {
        String raw = utterance == null ? "" : utterance.trim();
        int max = 0, min = 0;
        String s = raw;
        Matcher m = RANGE.matcher(s);
        if (m.find()) {
            min = num(m.group(1));
            max = num(m.group(2));
            s = s.substring(0, m.start()) + " " + s.substring(m.end());
        } else {
            m = MAX.matcher(s);
            if (m.find()) {
                max = num(m.group(1) != null ? m.group(1) : m.group(2));
                s = s.substring(0, m.start()) + " " + s.substring(m.end());
            } else {
                m = AROUND.matcher(s);
                if (m.find()) {
                    int v = num(m.group(1));
                    max = v > 0 ? (int) Math.round(v * 1.1) : 0;
                    s = s.substring(0, m.start()) + " " + s.substring(m.end());
                }
            }
            m = MIN.matcher(s);
            if (m.find()) {
                min = num(m.group(1) != null ? m.group(1) : m.group(2));
                s = s.substring(0, m.start()) + " " + s.substring(m.end());
            }
        }
        if (min > 0 && max > 0 && min > max) { int t = min; min = max; max = t; }
        boolean rec = RECOMMEND.matcher(raw).find();
        String kw = core(s);
        boolean model = MODEL.matcher(kw).find();
        if (!model && !rec) rec = true;   // 只说品类、没型号：也当推荐（给代表款，不做单品比价）
        if (model) rec = false;
        return new ShopQuery(kw, max, min, rec);
    }

    /** 去口语、去价格残留、去量词、去标点 → 品类。超长时取最后一个分句（品类一般在后面）再截。 */
    static String core(String s) {
        String t = s.replaceAll("[，,。.！!？?、~～…:：\"“”'‘’()（）]+", " ").trim();
        for (int i = 0; i < 4; i++) {
            String before = t;
            t = FILLER.matcher(t).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
            t = t.replaceAll("(?:^|\\s)(?:的|个|一|一下|点|些)(?=\\s|$)", " ").trim();
            t = QUANT.matcher(t).replaceAll("").trim();
            t = t.replaceAll("^(?:的|个|一|把|将|在)+", "").replaceAll("的$", "").trim();
            if (t.equals(before)) break;
        }
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (t.length() > 20) {
            String[] parts = t.split("\\s+");
            String best = parts[parts.length - 1];
            for (String p : parts) if (p.length() > best.length() && p.length() <= 20) best = p;
            t = best.length() > 20 ? best.substring(best.length() - 20) : best;
        }
        return t;
    }

    /** 抽取结果校验：2–20 字、不含原句（也不是原句的 70% 以上）、没有口语填充词。 */
    public static boolean valid(String keyword, String raw) {
        if (keyword == null) return false;
        String k = keyword.trim();
        if (k.length() < 2 || k.length() > 20) return false;
        String r = raw == null ? "" : raw.replaceAll("[，,。.！!？?\\s]+", "");
        String kk = k.replaceAll("\\s+", "");
        if (r.length() >= 8 && (kk.equals(r) || kk.length() >= r.length() * 0.7)) return false;
        return !Pattern.compile("有没有|推荐一下|我想|帮我|以内|以下|块钱|吗$|呢$").matcher(k).find();
    }

    /** 模型给的搜索词：过校验就用（价格词剥掉），否则规则重抽。 */
    public static String accept(String modelKeyword, String raw) {
        if (modelKeyword != null) {
            String k = modelKeyword.trim();
            Matcher m = MAX.matcher(k);
            if (m.find()) k = (k.substring(0, m.start()) + " " + k.substring(m.end())).replaceAll("\\s{2,}", " ").trim();
            if (valid(k, raw)) return k;
        }
        return parse(raw).searchQuery();
    }

    /** 「1000 / 1k / 一千 / 两千五 / 1.5千 / 3w」→ 元。 */
    static int num(String s) {
        if (s == null) return 0;
        String t = s.replaceAll("\\s+", "");
        try {
            double mul = 1;
            if (t.endsWith("k") || t.endsWith("K") || t.endsWith("千")) { mul = 1000; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("w") || t.endsWith("W") || t.endsWith("万")) { mul = 10000; t = t.substring(0, t.length() - 1); }
            if (t.matches("[0-9]+(?:\\.[0-9]+)?")) return (int) Math.round(Double.parseDouble(t) * mul);
            Long v = cn(t + (mul == 1000 ? "千" : mul == 10000 ? "万" : ""));
            return v == null ? 0 : v.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 中文数字（一千 / 两千五 / 一千二百 / 三百 / 五万）。 */
    static Long cn(String s) {
        if (s == null || s.length() == 0) return null;
        String d = "零一二两三四五六七八九";
        long total = 0, section = 0, cur = 0;
        int lastUnit = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int dv = d.indexOf(c);
            if (dv >= 0) {
                cur = dv == 3 ? 2 : (dv > 3 ? dv - 1 : dv);
                if (c == '两') cur = 2;
                continue;
            }
            int unit = c == '十' ? 10 : c == '百' ? 100 : c == '千' ? 1000 : c == '万' ? 10000 : 0;
            if (unit == 0) return null;
            if (unit == 10000) {
                section = (section + cur) * 10000;
                total += section;
                section = 0;
            } else {
                if (cur == 0 && unit == 10) cur = 1;
                section += cur * unit;
            }
            cur = 0;
            lastUnit = unit;
        }
        // 「两千五」= 2500：末位数字跟在单位后，按上一单位的十分之一
        if (cur > 0) section += lastUnit >= 10 && s.length() >= 2 ? cur * (lastUnit / 10) : cur;
        return total + section;
    }
}
