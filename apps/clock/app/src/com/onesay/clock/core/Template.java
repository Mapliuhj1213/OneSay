package com.onesay.clock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 晨间简报模板 → 件序列（纯函数）。件的文本全部来自朗读表（Bank / assets/phrases/zh-CN.json，见 docs/phrase-table.md）。
 *
 *   「嘿，你好，」「现在是本地时间」{H点}{M分，}「室外温度为」{T摄氏度，}「最低温度为」{lo度，}「最高温度为」{hi度，}{今天是X天气。}
 *   [降雨提醒句（0.1.6，和风分钟级 + 逐小时，见 RainLine；云端合成的变量件，没有就不说）]
 *   [新增，默认关：带伞提醒 / 生日句]
 *   「我们来看看由{来源}提供的消息吧。」{5 条原标题} — 新闻没有就整段跳过（可选说一句「今天的新闻暂时没取到。」）
 *   「目前就是这样了，」「一天好心情。」
 *
 * 除新闻标题和降雨句外全部是朗读表固定件（可在密钥面板「预合成朗读表」一次合成进本地缓存）；标题与降雨句是现合成的变量件。
 */
public final class Template {
    private Template() { }

    public static final int NEWS_N = 5, NEWS_MIN = 3;

    public static final class Piece {
        public final String bank;    // 预合成件键；null = 新闻变量件或停顿
        public final String text;    // 朗读文本
        public final String section; // intro / extra / news1..5 / closing
        public final int gapMs;      // >0 = 停顿件

        Piece(String bank, String text, String section, int gapMs) {
            this.bank = bank; this.text = text; this.section = section; this.gapMs = gapMs;
        }

        public boolean isGap() { return gapMs > 0; }

        public boolean isBank() { return bank != null; }

        public boolean isNews() { return bank == null && gapMs == 0; }

        public String toString() { return isGap() ? "[" + gapMs + "]" : (isBank() ? "<" + bank + ">" : "{" + text + "}"); }
    }

    static Piece b(String key, String sec) { return new Piece(key, Bank.text(key), sec, 0); }

    static Piece gap(int ms, String sec) { return new Piece(null, "", sec, ms); }

    /** 天气输入（任一项缺失用 NaN / null）。 */
    public static final class Wx {
        public double temp = Double.NaN, tmin = Double.NaN, tmax = Double.NaN;
        public String key;          // Bank.WX 键
        public int rainProb = -1;
        public boolean ok;
    }

    public static final class Opts {
        public boolean newsMissLine;   // Flags.NEWS_MISS_LINE
        public boolean umbrella;       // Flags.X_UMBRELLA
        public boolean birthdayToday;  // Flags.X_BIRTHDAY 且今天是生日
        /** 降雨提醒句（RainLine 的结果，且已确认有音频）；null = 不说。有它时旧的「降水概率 ≥ 50% 带伞」句不再说（不重复提带伞）。 */
        public String rainLine;
    }

    static int round(double v) { return (int) Math.round(v); }

    /**
     * @param news      已清洗的标题（已确认有音频的那些；少于 NEWS_MIN 条视为没有新闻）
     * @param sourceKey Bank.SRC 键
     */
    public static List<Piece> pieces(int h, int m, Wx w, List<String> news, String sourceKey, Opts o) {
        if (o == null) o = new Opts();
        List<Piece> p = new ArrayList<Piece>();
        p.add(b("greet", "intro"));
        p.add(gap(Pauses.GREET, "intro"));
        p.add(b("timel", "intro"));
        p.add(gap(Pauses.TIGHT, "intro"));
        p.add(b(Bank.hourKey(h), "intro"));
        p.add(gap(Pauses.HM, "intro"));
        p.add(b(Bank.minuteKey(m), "intro"));
        boolean hasT = w != null && w.ok && !Double.isNaN(w.temp) && Bank.tempInRange(round(w.temp));
        if (hasT) {
            p.add(gap(Pauses.COMMA, "intro"));
            p.add(b("tempNow", "intro"));
            p.add(gap(Pauses.TIGHT, "intro"));
            p.add(b(Bank.tempCKey(round(w.temp)), "intro"));
            boolean hasLH = !Double.isNaN(w.tmin) && !Double.isNaN(w.tmax)
                    && Bank.tempInRange(round(w.tmin)) && Bank.tempInRange(round(w.tmax));
            if (hasLH) {
                p.add(gap(Pauses.COMMA, "intro"));
                p.add(b("tempLo", "intro"));
                p.add(gap(Pauses.TIGHT, "intro"));
                p.add(b(Bank.tempDKey(round(w.tmin)), "intro"));
                p.add(gap(Pauses.LOW_HIGH, "intro"));
                p.add(b("tempHi", "intro"));
                p.add(gap(Pauses.TIGHT, "intro"));
                p.add(b(Bank.tempDKey(round(w.tmax)), "intro"));
            }
        }
        if (w != null && w.ok && w.key != null && Bank.wxWord(w.key) != null) {
            p.add(gap(Pauses.COMMA, "intro"));
            p.add(b("wx_" + w.key, "intro"));
        }
        boolean hasRain = o.rainLine != null && o.rainLine.length() > 0;
        if (hasRain) {
            p.add(gap(Pauses.INTRO_END, "extra"));
            p.add(new Piece(null, o.rainLine, "extra", 0));
        }
        if (!hasRain && o.umbrella && w != null && w.ok && w.rainProb >= 50) {
            p.add(gap(Pauses.INTRO_END, "extra"));
            p.add(b("x_umbrella", "extra"));
        }
        if (o.birthdayToday) {
            p.add(gap(Pauses.INTRO_END, "extra"));
            p.add(b("x_birthday", "extra"));
        }
        boolean hasNews = news != null && news.size() >= NEWS_MIN;
        if (hasNews) {
            p.add(gap(Pauses.INTRO_END, "lead"));
            p.add(b("lead_" + (isSrc(sourceKey) ? sourceKey : "net"), "lead"));
            int n = Math.min(NEWS_N, news.size());
            for (int i = 0; i < n; i++) {
                p.add(gap(i == 0 ? Pauses.LEAD_NEWS : Pauses.ITEM, "news" + (i + 1)));
                p.add(new Piece(null, news.get(i) + "。", "news" + (i + 1), 0));
            }
            p.add(gap(Pauses.TO_CLOSER, "closing"));
        } else if (o.newsMissLine) {
            p.add(gap(Pauses.INTRO_END, "lead"));
            p.add(b("nonews", "lead"));
            p.add(gap(Pauses.TO_CLOSER, "closing"));
        } else {
            p.add(gap(Pauses.TO_CLOSER, "closing"));    // 开场后直接接收尾
        }
        p.add(b("closer", "closing"));
        p.add(gap(Pauses.BLESS, "closing"));
        p.add(b("bless", "closing"));
        return p;
    }

    static boolean isSrc(String k) {
        return Bank.sourceKnown(k);
    }

    /** 整稿文本（展示/存档/OneSay）。新闻每条一行。 */
    public static String text(List<Piece> ps) {
        StringBuilder sb = new StringBuilder();
        String last = "";
        for (Piece p : ps) {
            if (p.isGap()) continue;
            if (!p.section.equals(last) && sb.length() > 0 && !(last.equals("intro") && p.section.equals("extra")))
                sb.append('\n');
            sb.append(p.text);
            last = p.section;
        }
        return sb.toString();
    }
}
