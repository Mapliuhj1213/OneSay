package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.search.SearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（11-03 下午，RESEARCH-TEST-basilisk §7.6-2）：比价跑腿的「商品名关键词」。
 *
 * <ul>
 *   <li>把商品名切成关键 token：中文按「品牌 + 系列 / 型号」切（雷蛇 / Razer、巴塞利斯蛇 / Basilisk、V3），
 *       不认识的中文段按 2~6 字整段（更长按 2 字切），品类词（鼠标 / 手机…）和版本词（专业版 / 无线版…）不算关键 token；</li>
 *   <li>{@link #relevant}：免 key 的必应网页结果（{@link SearchResult#keyless()}）标题 / 摘要命中关键 token 不足就丢弃
 *       （例：「雷蛇巴塞利斯蛇v3」→ 只含「雷」字的迅雷 / 雷电模拟器 / 扫雷全丢）；</li>
 *   <li>{@link #englishName}：全被过滤时用英文名补搜（品牌型号映射表，覆盖常见外设 / 手机 / 数码品牌）；
 *       有不认识的中文段 → 返回 null（不做拼音，免得搜出更多噪声）。</li>
 * </ul>
 * 纯 Java、Java 7 语法，JVM 可测。
 */
public final class ProductQuery {

    public enum Type { BRAND, SERIES, MODEL, UNKNOWN, VARIANT, CATEGORY }

    /** 一个 token：中文别名、英文别名（第一个用于补搜）、在原句里的写法。 */
    public static final class Token {
        public final Type type;
        public final String text;
        final String[] zh;
        final String[] en;
        final Pattern model;

        Token(Type type, String text, String[] zh, String[] en, Pattern model) {
            this.type = type;
            this.text = text;
            this.zh = zh;
            this.en = en;
            this.model = model;
        }

        /** 算不算关键 token（参与相关性判断）。 */
        public boolean key() {
            return type == Type.BRAND || type == Type.SERIES || type == Type.MODEL || type == Type.UNKNOWN;
        }

        /** ★ 11-03 15:53 比价网适配器（errand.compare）也要用：low = 已转小写的文本。 */
        public boolean matches(String low) {
            if (model != null) return model.matcher(low).find();
            for (String z : zh) if (z.length() > 0 && low.contains(z)) return true;
            for (String e : en) if (e.length() > 0 && word(e).matcher(low).find()) return true;
            return false;
        }

        String english() {
            if (type == Type.MODEL) {
                if (text.matches("(?i)[a-z]{1,2}\\d+[a-z]?")) return text.toUpperCase(Locale.ROOT);   // v3 → V3、g502 → G502
                String low = text.toLowerCase(Locale.ROOT);
                if (low.startsWith("iphone")) return "iPhone" + text.substring(6);
                if (low.startsWith("ipad")) return "iPad" + text.substring(4);
                return cap(text);   // mate60 → Mate60
            }
            if (en.length == 0) return null;
            return hasHan(text) ? en[0] : cap(en[0]);   // 句子里写的英文版本词：pro → Pro
        }

        @Override public String toString() {
            return type + ":" + text;
        }
    }

    // ------------------------------------------------------------------ 映射表（中文别名用 | 分隔；英文第一个用于补搜）

    /** 品牌：常见外设 / 手机 / 电脑 / 影音 / 存储。 */
    static final String[][] BRANDS = {
            {"雷蛇", "Razer"}, {"罗技", "Logitech|Logi"}, {"赛睿", "SteelSeries"}, {"美商海盗船|海盗船", "Corsair"},
            {"卓威", "Zowie"}, {"樱桃", "Cherry"}, {"雷柏", "Rapoo"}, {"达尔优", "Dareu"}, {"黑峡谷", "Hyeku"},
            {"腹灵", "FL Esports|FLESPORTS"}, {"杜伽", "Durgod"}, {"阿米洛", "Varmilo"}, {"酷冷至尊", "Cooler Master|CoolerMaster"},
            {"狼蛛", "Aula"}, {"新贵", "Newmen"}, {"贼船", "Corsair"}, {"冰豹", "Roccat"}, {"雷神", "ThundeRobot"},
            {"玩家国度", "ROG"}, {"华硕", "ASUS"}, {"微星", "MSI"}, {"技嘉", "Gigabyte"}, {"拯救者", "Legion"}, {"联想", "Lenovo"},
            {"外星人", "Alienware"}, {"戴尔", "Dell"}, {"惠普", "HP"}, {"宏碁", "Acer"}, {"明基", "BenQ"}, {"机械革命", "Mechrevo"},
            {"漫步者", "Edifier"}, {"索尼", "Sony"}, {"森海塞尔", "Sennheiser"}, {"铁三角", "Audio-Technica"}, {"拜亚动力", "Beyerdynamic"},
            {"哈曼卡顿", "Harman Kardon"}, {"飞利浦", "Philips"}, {"苹果", "Apple"}, {"华为", "Huawei"}, {"荣耀", "Honor"},
            {"红米", "Redmi"}, {"小米", "Xiaomi"}, {"三星", "Samsung"}, {"一加", "OnePlus"}, {"真我", "realme"}, {"魅族", "Meizu"},
            {"努比亚", "nubia"}, {"红魔", "RedMagic"}, {"谷歌", "Google"}, {"摩托罗拉", "Motorola"}, {"欧珀", "OPPO"}, {"维沃", "vivo"},
            {"大疆", "DJI"}, {"任天堂", "Nintendo"}, {"金士顿", "Kingston"}, {"闪迪", "SanDisk"}, {"西部数据|西数", "WD|Western Digital"},
            {"希捷", "Seagate"}, {"飞智", "Flydigi"}, {"北通", "Betop"}, {"八位堂", "8BitDo"}, {"佳能", "Canon"}, {"尼康", "Nikon"},
            {"富士", "Fujifilm"}, {"松下", "Panasonic"}, {"戴森", "Dyson"}, {"影石", "Insta360"},
            // 只有英文写法的品牌（句子里直接写英文时认作品牌）
            {"", "iQOO"}, {"", "JBL"}, {"", "Bose"}, {"", "AKG"}, {"", "HyperX"}, {"", "Keychron"}, {"", "Anker"},
    };

    /** 系列（中文名 ↔ 英文名）。长的写前面（「炼狱蝰蛇」先于「蝰蛇」）。 */
    static final String[][] SERIES = {
            {"巴塞利斯蛇", "Basilisk"}, {"炼狱蝰蛇", "DeathAdder"}, {"毒蝰", "Viper"}, {"蝰蛇", "Viper"}, {"八岐大蛇", "Orochi"},
            {"那伽梵蛇", "Naga"}, {"眼镜蛇", "Cobra"}, {"黑寡妇蜘蛛", "BlackWidow"}, {"猎魂光蛛", "Huntsman"}, {"雨林狼蛛", "Ornata"},
            {"北海巨妖", "Kraken"}, {"旋风黑鲨", "BlackShark"}, {"战锤狂鲨", "Hammerhead"}, {"梭鱼", "Barracuda"}, {"幻影战蛛", "Cynosa"},
            {"狗屁王", "G Pro X Superlight"}, {"寒冰", "Sensei"},
            {"畅享", "Enjoy"}, {"折叠屏", "Fold"}, {"平板", "Pad"},
    };

    /** 版本词（不算关键 token；补搜时翻译，空串 = 补搜时去掉）。 */
    static final String[][] VARIANTS = {
            {"专业版", "Pro"}, {"超速版|极速版", "HyperSpeed"}, {"无线版", "Wireless"}, {"有线版", ""}, {"标准版", ""}, {"迷你", "Mini"},
            {"至尊版", "Ultimate"}, {"青春版", "Lite"}, {"轻量版", "Lite"}, {"典藏版", ""}, {"幻彩版", "Chroma"}, {"白色", "White"},
            {"黑色", "Black"}, {"粉色", "Pink"}, {"新款", ""}, {"最新款", ""}, {"国行", ""}, {"港版", ""},
    };

    /** 品类 / 虚词（去掉）。 */
    static final String[] CATEGORIES = {
            "无线鼠标", "游戏鼠标", "电竞鼠标", "鼠标", "机械键盘", "键盘", "头戴式耳机", "蓝牙耳机", "耳机", "耳麦", "手机", "笔记本电脑",
            "笔记本", "电脑", "显示器", "音箱", "音响", "手柄", "鼠标垫", "游戏本", "充电器", "手表", "手环", "相机", "无人机", "固态硬盘",
            "硬盘", "内存条", "显卡", "路由器", "电竞", "游戏", "无线", "有线", "蓝牙", "机械", "一个", "一款", "一台", "一副", "一只",
            "正品", "官方", "旗舰", "的",
    };

    /** 拉丁 / 数字片段：iphone15 → 「iphone15」，v3pro → 「v3」「pro」。 */
    private static final Pattern LATIN_RUN = Pattern.compile("[a-z]+\\d+(?:[a-z](?![a-z]))?|[a-z]+|\\d+(?:[a-z](?![a-z]))?");
    private static final Pattern NOISE_LATIN = Pattern.compile("pro|max|plus|ultra|mini|lite|se|x|s|v|the|and|new");

    private final List<Token> mTokens;
    public final String raw;

    private ProductQuery(String raw, List<Token> tokens) {
        this.raw = raw;
        this.mTokens = Collections.unmodifiableList(tokens);
    }

    public List<Token> tokens() {
        return mTokens;
    }

    public List<Token> keyTokens() {
        List<Token> l = new ArrayList<Token>();
        for (Token t : mTokens) if (t.key()) l.add(t);
        return l;
    }

    // ------------------------------------------------------------------ 切分

    public static ProductQuery parse(String query) {
        String q = query == null ? "" : query.trim();
        String low = q.toLowerCase(Locale.ROOT);
        List<Token> out = new ArrayList<Token>();
        StringBuilder unk = new StringBuilder();
        int i = 0;
        while (i < low.length()) {
            char c = low.charAt(i);
            if (isHan(c)) {
                int[] hit = matchHan(low, i);
                if (hit != null) {
                    flushUnknown(unk, out);
                    String word = q.substring(i, i + hit[1]);
                    Token t = hanToken(hit[0], hit[2], word);
                    if (t != null) out.add(t);
                    i += hit[1];
                } else {
                    unk.append(q.charAt(i));
                    i++;
                }
                continue;
            }
            flushUnknown(unk, out);
            if (Character.isLetterOrDigit(c)) {
                int j = i;
                while (j < low.length() && !isHan(low.charAt(j)) && (Character.isLetterOrDigit(low.charAt(j)) || low.charAt(j) == '-')) j++;
                latinTokens(q.substring(i, j), out);
                i = j;
                continue;
            }
            i++;
        }
        flushUnknown(unk, out);
        return new ProductQuery(q, mergeLatinBrands(out));
    }

    static boolean isHan(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /** 在 i 处最长匹配中文词：返回 {表(0 品牌 1 系列 2 版本 3 品类), 长度, 行号}；没有为 null。 */
    private static int[] matchHan(String low, int i) {
        int[] best = null;
        String[][][] tables = {BRANDS, SERIES, VARIANTS};
        for (int t = 0; t < tables.length; t++) {
            for (int r = 0; r < tables[t].length; r++) {
                for (String z : split(tables[t][r][0])) {
                    if (z.length() > 0 && low.startsWith(z, i) && (best == null || z.length() > best[1])) best = new int[]{t, z.length(), r};
                }
            }
        }
        for (int r = 0; r < CATEGORIES.length; r++) {
            String z = CATEGORIES[r];
            if (low.startsWith(z, i) && (best == null || z.length() > best[1])) best = new int[]{3, z.length(), r};
        }
        return best;
    }

    private static Token hanToken(int table, int row, String word) {
        switch (table) {
            case 0: return new Token(Type.BRAND, word, split(BRANDS[row][0]), split(BRANDS[row][1]), null);
            case 1: return new Token(Type.SERIES, word, split(SERIES[row][0]), split(SERIES[row][1]), null);
            case 2: return new Token(Type.VARIANT, word, split(VARIANTS[row][0]), split(VARIANTS[row][1]), null);
            default: return new Token(Type.CATEGORY, word, new String[]{word}, new String[0], null);
        }
    }

    private static void flushUnknown(StringBuilder unk, List<Token> out) {
        if (unk.length() == 0) return;
        String s = unk.toString();
        unk.setLength(0);
        if (s.length() < 2) return;
        if (s.length() <= 6) {
            out.add(new Token(Type.UNKNOWN, s, new String[]{s.toLowerCase(Locale.ROOT)}, new String[0], null));
            return;
        }
        for (int k = 0; k + 2 <= s.length(); k += 2) {
            String w = s.substring(k, k + 2);
            out.add(new Token(Type.UNKNOWN, w, new String[]{w}, new String[0], null));
        }
    }

    private static void latinTokens(String run, List<Token> out) {
        java.util.regex.Matcher m = LATIN_RUN.matcher(run.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String orig = run.substring(m.start(), m.end());
            String w = m.group();
            String[] brand = rowFor(BRANDS, w), series = rowFor(SERIES, w), variant = rowFor(VARIANTS, w);
            if (brand != null) out.add(new Token(Type.BRAND, orig, split(brand[0]), split(brand[1]), null));
            else if (series != null) out.add(new Token(Type.SERIES, orig, split(series[0]), split(series[1]), null));
            else if (variant != null) out.add(new Token(Type.VARIANT, orig, new String[0], split(variant[1]), null));
            else if (NOISE_LATIN.matcher(w).matches() || w.length() < 2)
                out.add(new Token(Type.VARIANT, orig, new String[0], new String[]{orig}, null));
            else out.add(new Token(Type.MODEL, orig, new String[0], new String[]{orig}, modelPattern(w)));
        }
    }

    /** 英文别名整词等于 w 的那一行。 */
    private static String[] rowFor(String[][] table, String w) {
        for (String[] row : table) for (String e : split(row[1])) if (e.toLowerCase(Locale.ROOT).replace(" ", "").equals(w)) return row;
        return null;
    }

    /** 「Western Digital」「Harman Kardon」这类两词品牌：切开后两个 MODEL 合回一个 BRAND（简单处理：只看相邻两词）。 */
    private static List<Token> mergeLatinBrands(List<Token> in) {
        List<Token> out = new ArrayList<Token>();
        for (int k = 0; k < in.size(); k++) {
            if (k + 1 < in.size()) {
                String two = (in.get(k).text + in.get(k + 1).text).toLowerCase(Locale.ROOT);
                String[] b = rowFor(BRANDS, two);
                if (b != null) {
                    out.add(new Token(Type.BRAND, in.get(k).text + " " + in.get(k + 1).text, split(b[0]), split(b[1]), null));
                    k++;
                    continue;
                }
            }
            out.add(in.get(k));
        }
        return out;
    }

    /** 型号词：v3 → 「v 3」「v-3」也算，但不算 v30 / iv3；g502 → g502。 */
    static Pattern modelPattern(String w) {
        java.util.regex.Matcher m = Pattern.compile("^([a-z]*)(\\d*)([a-z]*)$").matcher(w);
        boolean digitsFirst = m.matches() && m.group(1).length() == 0;
        StringBuilder re = new StringBuilder(digitsFirst ? "(?<![0-9])" : "(?<![a-z0-9])");   // 「15」也要能命中「iphone15」
        if (m.matches() && m.group(1).length() > 0 && m.group(2).length() > 0) {
            re.append(Pattern.quote(m.group(1))).append("[\\s\\-]?").append(Pattern.quote(m.group(2)));
            if (m.group(3).length() > 0) re.append("[\\s\\-]?").append(Pattern.quote(m.group(3)));
        } else {
            re.append(Pattern.quote(w));
        }
        re.append("(?![0-9])");
        if (!(m.matches() && m.group(2).length() > 0 && m.group(3).length() == 0)) re.append("(?![a-z])");
        return Pattern.compile(re.toString());
    }

    private static Pattern word(String e) {
        String low = e.toLowerCase(Locale.ROOT);
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(low).replace(" ", "\\E\\s*\\Q") + "(?![a-z0-9])");
    }

    static String[] split(String s) {
        if (s == null || s.length() == 0) return new String[0];
        return s.split("\\|");
    }

    // ------------------------------------------------------------------ 相关性

    /** 命中的关键 token 个数。 */
    public int hits(String text) {
        String low = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int n = 0;
        for (Token t : mTokens) if (t.key() && t.matches(low)) n++;
        return n;
    }

    /** 至少要命中几个关键 token：1 个关键 token → 1；2~4 个 → 2；更多 → 过半。 */
    public int need() {
        int n = keyTokens().size();
        if (n <= 1) return n;
        return Math.max(2, (n + 1) / 2);
    }

    /** 标题 + 摘要和商品名相关吗（命中够数，且多于一个关键 token 时至少命中一个非品牌 token）。 */
    public boolean relevant(String title, String snippet) {
        List<Token> keys = keyTokens();
        if (keys.isEmpty()) return true;   // 判断不了 → 不丢
        String low = ((title == null ? "" : title) + " " + (snippet == null ? "" : snippet)).toLowerCase(Locale.ROOT);
        int n = 0;
        boolean nonBrand = false;
        for (Token t : keys) {
            if (!t.matches(low)) continue;
            n++;
            if (t.type != Type.BRAND) nonBrand = true;
        }
        if (n < need()) return false;
        if (keys.size() > 1 && !nonBrand) {
            for (Token t : keys) if (t.type != Type.BRAND) return false;   // 有型号却只命中品牌 → 不算
        }
        return true;
    }

    public boolean relevant(SearchResult r) {
        return r != null && relevant(r.title, r.snippet);
    }

    // ------------------------------------------------------------------ 英文名补搜

    /**
     * 英文名（「雷蛇巴塞利斯蛇v3」→「Razer Basilisk V3」）。认不出品牌 / 系列、或者有不认识的中文段 → null（不做拼音）；
     * 原句本来就是英文 → null（补搜没意义）。
     */
    public String englishName() {
        boolean mapped = false;
        StringBuilder sb = new StringBuilder();
        for (Token t : mTokens) {
            if (t.type == Type.UNKNOWN) return null;
            if (t.type == Type.CATEGORY) continue;
            String e = t.english();
            if ((t.type == Type.BRAND || t.type == Type.SERIES) && hasHan(t.text) && e != null && e.length() > 0) mapped = true;
            if (e == null || e.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(e);
        }
        if (!mapped || sb.length() == 0) return null;
        return sb.toString();
    }

    private static String cap(String s) {
        if (s == null || s.length() == 0) return s;
        return Character.isLowerCase(s.charAt(0)) ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    private static boolean hasHan(String s) {
        for (int k = 0; k < s.length(); k++) if (isHan(s.charAt(k))) return true;
        return false;
    }

    /** 英文名补搜的查询（「价格」「京东」「比价」后缀）。 */
    public static List<String> fallbackQueries(String english) {
        List<String> q = new ArrayList<String>();
        if (english == null || english.trim().length() == 0) return q;
        q.add(english + " 价格");
        q.add(english + " 京东");
        q.add(english + " 比价");
        return q;
    }

    @Override public String toString() {
        return mTokens.toString();
    }
}
