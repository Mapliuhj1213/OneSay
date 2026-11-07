package com.onesay.assistant.v2.router;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 19:45「今天全球股市行情」主要指数缺失）：股市行情类问题先走结构化行情源，
 * 固定取一组主要指数，数字原样（源里的字符串，不换算不四舍五入）进结论槽位；网页搜索只补板块与原因。
 * <ul>
 *   <li>主源：腾讯行情 qt.gtimg.cn（GBK）。A 股 / 港股 / 美股走标准 {@code ~} 格式（[1] 名称 [3] 最新 [30] 时间 [31] 涨跌 [32] 涨跌幅），
 *       日经 / DAX / 富时走全球指数 gz 格式（[2] 时间 [3] 最新 [4] 涨跌 [5] 涨跌幅）。</li>
 *   <li>备选：新浪 hq.sinajs.cn（GBK，必须带 Referer），只补腾讯没拿到的那几个。</li>
 *   <li>两个源都拿不到的写「未查到」，不编。</li>
 * </ul>
 * 时间：A 股 / 港股 / 日经 / 欧股 / 新浪美股为北京时间，腾讯美股为美东时间（源里就是这样标的，原样写出并注明时区）。
 * 纯 Java（JVM 可测；取数经 {@link Fetcher}，单测注入假源），Java 7 语法。
 */
public final class MarketQuotes {

    private MarketQuotes() {}

    public static final String SRC_TENCENT = "腾讯行情 qt.gtimg.cn";
    public static final String SRC_SINA = "新浪行情 hq.sinajs.cn";

    /** 固定的一组主要指数：{显示名, 地区, 腾讯代码, 新浪代码}。 */
    /** {显示全名, 地区, 腾讯代码, 新浪代码, 图上短名, 范围标记}；范围标记 "A+" = 只在问 A 股 / 点名时取（全球默认不取）。 */
    static final String[][] INDICES = {
            {"上证指数", "A股", "sh000001", "sh000001", "上证指数", ""},
            {"深证成指", "A股", "sz399001", "sz399001", "深证成指", ""},
            {"创业板指", "A股", "sz399006", "sz399006", "创业板指", ""},
            {"沪深300", "A股", "sh000300", "sh000300", "沪深300", ""},
            {"科创50", "A股", "sh000688", "sh000688", "科创50", "A+"},
            {"北证50", "A股", "bj899050", "bj899050", "北证50", "A+"},
            {"恒生指数", "港股", "hkHSI", "rt_hkHSI", "恒指", ""},
            {"恒生科技指数", "港股", "hkHSTECH", "rt_hkHSTECH", "恒生科技", ""},
            {"道琼斯工业指数", "美股", "usDJI", "gb_$dji", "道指", ""},
            {"标普500", "美股", "usINX", "gb_$inx", "标普500", ""},
            {"纳斯达克综合指数", "美股", "usIXIC", "gb_$ixic", "纳指", ""},
            {"日经225", "日欧", "gzN225", "znb_NKY", "日经225", ""},
            {"德国DAX", "日欧", "gzGDAXI", "znb_DAX", "德国DAX", ""},
            {"英国富时100", "日欧", "gzFTSE", "znb_UKX", "富时100", ""},
    };

    /** 全球默认（不问范围时）取的指数个数。 */
    public static int size() { return Scope.GLOBAL.indices().size(); }

    /** 全名 → 图上短名（不认识的原样返回）。 */
    public static String shortName(String name) {
        if (name == null) return "";
        for (String[] ix : INDICES) if (ix[0].equals(name)) return ix[4];
        return name;
    }

    // ------------------------------------------------------------------ 范围（★ 11-04 01:06 用户：「A股行情怎么样」不该回全球 12 个指数）

    /** 用户问的范围：地区集合或点名的单个指数；都没说 = 全球。 */
    public static final class Scope {
        /** 中文标签：全球 / A股 / 港股 / 美股 / 欧洲 / 日本，点名指数时为「创业板指」这类全名（多个用「、」连）。 */
        public final String label;
        final List<Integer> idx;
        public final boolean global;

        Scope(String label, List<Integer> idx, boolean global) { this.label = label; this.idx = idx; this.global = global; }

        public static final Scope GLOBAL;
        static {
            List<Integer> l = new ArrayList<Integer>();
            for (int i = 0; i < INDICES.length; i++) if (!"A+".equals(INDICES[i][5])) l.add(i);
            GLOBAL = new Scope("全球", l, true);
        }

        /** 范围内指数的全名（按 INDICES 顺序）。 */
        public List<String> indices() {
            List<String> n = new ArrayList<String>();
            for (int i : idx) n.add(INDICES[i][0]);
            return n;
        }
    }

    /** 点名单个指数：{正则, 全名}。顺序有讲究：「恒生科技」先于「恒生 / 恒指」，「沪深300」先于「沪深」。 */
    private static final String[][] NAMED = {
            {"沪深\\s*300|沪深三百", "沪深300"},
            {"创业板", "创业板指"},
            {"科创\\s*50|科创板|科创五十", "科创50"},
            {"北证|北交所", "北证50"},
            {"深证成指|深成指|深证指数|深指|深成", "深证成指"},
            {"上证|沪指|上指", "上证指数"},
            {"恒生科技|恒科指|恒科", "恒生科技指数"},
            {"恒生指数|恒指", "恒生指数"},
            {"道琼斯|道指", "道琼斯工业指数"},
            {"标普|标准普尔|S&P|SP500", "标普500"},
            {"纳斯达克|纳指", "纳斯达克综合指数"},
            {"日经", "日经225"},
            {"DAX|德国股市|德股", "德国DAX"},
            {"富时|英国股市|英股", "英国富时100"},
    };
    /** 地区：{正则, 地区标签, 图上地区}。 */
    private static final String[][] REGION_CUES = {
            {"A股|a股|大A|大a|沪深两市|两市|沪深股市|中国股市|国内股市|大盘", "A股"},
            {"港股|香港股市|恒生", "港股"},
            {"美股|美国股市|华尔街|美股三大|三大股指", "美股"},
            {"欧股|欧洲股市|欧洲市场|欧洲", "欧洲"},
            {"日股|日本股市|日本", "日本"},
    };
    private static final Pattern GLOBAL_CUE = Pattern.compile("全球|环球|全世界|世界各地|各国|海内外|内外盘|外围|主要市场|各大股市|各大市场");

    static String regionOfIndex(int i) {
        String r = INDICES[i][1];
        if ("日欧".equals(r)) return INDICES[i][0].contains("日经") ? "日本" : "欧洲";
        return r;
    }

    /** 行情问句 → 范围。没指明（或说「全球」）= {@link Scope#GLOBAL}；地区与点名可以并存（「A股和纳指」）。 */
    public static Scope scopeOf(String query) {
        if (query == null) return Scope.GLOBAL;
        String q = TaskContinuation.strip(query);
        if (GLOBAL_CUE.matcher(q).find()) return Scope.GLOBAL;
        java.util.TreeSet<Integer> set = new java.util.TreeSet<Integer>();
        List<String> labels = new ArrayList<String>();
        String rest = q;
        for (String[] n : NAMED) {
            Matcher m = Pattern.compile(n[0]).matcher(rest);
            if (m.find()) {
                for (int i = 0; i < INDICES.length; i++) if (INDICES[i][0].equals(n[1])) set.add(i);
                labels.add(n[1]);
                rest = m.replaceAll(" ");
            }
        }
        for (String[] r : REGION_CUES) {
            if (!Pattern.compile(r[0]).matcher(rest).find()) continue;
            if (!labels.contains(r[1])) labels.add(r[1]);
            for (int i = 0; i < INDICES.length; i++) if (regionOfIndex(i).equals(r[1])) set.add(i);
        }
        if (set.isEmpty()) return Scope.GLOBAL;
        StringBuilder lb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) lb.append(i > 0 ? "、" : "").append(labels.get(i));
        return new Scope(lb.toString(), new ArrayList<Integer>(set), false);
    }

    // ------------------------------------------------------------------ 识别

    private static final Pattern STOCK_MARKET = Pattern.compile(
            "股市|大盘|股指|指数|美股|港股|A股|a股|大A|欧股|日股|沪指|深指|恒指|道指|纳指|标普|收盘|开盘|三大股指|创业板|科创50|科创板|北证50|上证|深成|沪深300|恒生|道琼斯|纳斯达克|日经");
    private static final Pattern SINGLE_NAME = Pattern.compile("股价|个股|这只|那只|基金|债券|汇率|比特币|金价|油价");

    /** 问的是股市大盘行情（而不是单只股票 / 金价 / 二手价）。 */
    public static boolean isStockMarketQuery(String query) {
        if (query == null) return false;
        String q = TaskContinuation.strip(query);
        return STOCK_MARKET.matcher(q).find() && !SINGLE_NAME.matcher(q).find();
    }

    // ------------------------------------------------------------------ 数据

    public static final class Quote {
        public final String name;      // 固定显示名
        public final String region;
        public String price;           // 源里的原字符串
        public String change;
        public String pct;
        public String date;            // yyyy-MM-dd
        public String time;            // HH:mm
        public String zone;            // 北京时间 / 美东时间
        public String source;          // SRC_TENCENT / SRC_SINA；null = 未查到

        Quote(String name, String region) { this.name = name; this.region = region; }

        public boolean found() { return source != null; }
    }

    public static final class Snapshot {
        public final List<Quote> quotes = new ArrayList<Quote>();
        /** ★ 11-04：这次取数的范围（默认全球）。 */
        public Scope scope = Scope.GLOBAL;

        public int foundCount() {
            int n = 0;
            for (Quote q : quotes) if (q.found()) n++;
            return n;
        }

        public boolean any() { return foundCount() > 0; }

        public boolean usedSina() {
            for (Quote q : quotes) if (SRC_SINA.equals(q.source)) return true;
            return false;
        }

        public boolean usedTencent() {
            for (Quote q : quotes) if (SRC_TENCENT.equals(q.source)) return true;
            return false;
        }

        public Quote get(String name) {
            for (Quote q : quotes) if (q.name.equals(name)) return q;
            return null;
        }

        /** 结构化源算一次「读原文」+ 一个站点（收工证据门槛用）。 */
        public void addEvidence(ResearchGate.Evidence ev) {
            if (ev == null || !any()) return;
            ev.reads++;
            if (usedTencent()) ev.readHosts.add("gtimg.cn");
            if (usedSina()) ev.readHosts.add("sinajs.cn");
        }

        /** 日志用：拿到哪些 / 没拿到哪些（只有名字与来源，无数字）。 */
        public String describe() {
            StringBuilder ok = new StringBuilder(), miss = new StringBuilder();
            for (Quote q : quotes) {
                StringBuilder b = q.found() ? ok : miss;
                if (b.length() > 0) b.append(',');
                b.append(q.name);
                if (SRC_SINA.equals(q.source)) b.append("(新浪)");
            }
            return "found=" + foundCount() + "/" + quotes.size() + " [" + ok + "] missing=[" + miss + "]";
        }

        /**
         * 结论槽位：「主要指数」一节，数字逐字取自源。nowMillis 用来判断是不是今天的数据（北京时间）。
         */
        public String render(long nowMillis) {
            String today = ymd(nowMillis);
            StringBuilder sb = new StringBuilder("主要指数（结构化行情源：");
            List<String> srcs = new ArrayList<String>();
            if (usedTencent()) srcs.add(SRC_TENCENT);
            if (usedSina()) srcs.add(SRC_SINA);
            if (srcs.isEmpty()) srcs.add("腾讯 / 新浪行情均未取到");
            for (int i = 0; i < srcs.size(); i++) { if (i > 0) sb.append("、"); sb.append(srcs.get(i)); }
            sb.append("）：");
            String region = null;
            for (Quote q : quotes) {
                if (!q.region.equals(region)) {
                    region = q.region;
                    sb.append('\n').append(region).append("：");
                } else {
                    sb.append("；");
                }
                sb.append(q.name).append(' ');
                if (!q.found()) { sb.append("未查到"); continue; }
                sb.append(fmt2(q.price)).append("，").append(signed(fmt2(q.change))).append("（").append(signed(q.pct)).append("%）");
                if (q.date != null) {
                    sb.append("，").append(q.date.length() >= 10 ? q.date.substring(5) : q.date);
                    if (q.time != null) sb.append(' ').append(q.time);
                    if (q.zone != null) sb.append(' ').append(q.zone);
                    if (!q.date.equals(today) && !"美东时间".equals(q.zone)) sb.append("，最近一个交易日");
                }
            }
            return sb.toString();
        }
    }

    static String signed(String num) {
        if (num == null) return "";
        String s = num.trim();
        if (s.startsWith("-") || s.startsWith("+")) return s;
        try { if (Double.parseDouble(s) == 0d) return s; } catch (NumberFormatException e) { return s; }
        return "+" + s;
    }

    static String ymd(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.setTimeInMillis(millis);
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------ 解析

    private static final Pattern VAR = Pattern.compile("v_([A-Za-z0-9_$.]+)=\"([^\"]*)\"");
    private static final Pattern SINA_VAR = Pattern.compile("hq_str_([A-Za-z0-9_$.]+)=\"([^\"]*)\"");

    /** 腾讯响应 → 代码 → 原始字段串。 */
    static Map<String, String> splitVars(String body, Pattern p) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (body == null) return m;
        Matcher mm = p.matcher(body);
        while (mm.find()) m.put(mm.group(1), mm.group(2));
        return m;
    }

    static boolean num(String s) {
        if (s == null || s.trim().length() == 0) return false;
        try { Double.parseDouble(s.trim()); return true; } catch (NumberFormatException e) { return false; }
    }

    static boolean positive(String s) {
        try { return num(s) && Double.parseDouble(s.trim()) > 0; } catch (NumberFormatException e) { return false; }
    }

    /** 解析一条腾讯行情填进 q；成功返回 true。 */
    static boolean parseTencent(String code, String raw, Quote q) {
        if (raw == null || raw.length() == 0) return false;
        String[] f = raw.split("~", -1);
        if (code.startsWith("gz")) {
            // N225~日经225指数~2026-11-02 14:30:02~63081.64~-208.47~-0.33~AP~
            if (f.length < 6 || !positive(f[3]) || !num(f[4]) || !num(f[5])) return false;
            q.price = f[3].trim(); q.change = f[4].trim(); q.pct = f[5].trim();
            setDateTime(q, f[2], "北京时间");
        } else {
            if (f.length < 33 || !positive(f[3]) || !num(f[31]) || !num(f[32])) return false;
            q.price = f[3].trim(); q.change = f[31].trim(); q.pct = f[32].trim();
            setDateTime(q, f[30], code.startsWith("us") ? "美东时间" : "北京时间");
        }
        q.source = SRC_TENCENT;
        return true;
    }

    /** 解析一条新浪行情填进 q；成功返回 true。 */
    static boolean parseSina(String code, String raw, Quote q) {
        if (raw == null || raw.length() == 0) return false;
        String[] f = raw.split(",", -1);
        if (code.startsWith("rt_hk")) {
            // HSI,恒生指数,开,昨收,高,低,最新,涨跌,涨跌幅,…,日期(17),时间(18)
            if (f.length < 19 || !positive(f[6]) || !num(f[7]) || !num(f[8])) return false;
            q.price = f[6].trim(); q.change = f[7].trim(); q.pct = f[8].trim();
            setDateTime(q, f[17] + " " + f[18], "北京时间");
        } else if (code.startsWith("gb_")) {
            // 名称,最新,涨跌幅,时间(北京),涨跌,…
            if (f.length < 5 || !positive(f[1]) || !num(f[2]) || !num(f[4])) return false;
            q.price = f[1].trim(); q.change = f[4].trim(); q.pct = f[2].trim();
            setDateTime(q, f[3], "北京时间");
        } else if (code.startsWith("znb_")) {
            // 名称,最新,涨跌,涨跌幅,…,日期(6),时间(7)
            if (f.length < 8 || !positive(f[1]) || !num(f[2]) || !num(f[3])) return false;
            q.price = f[1].trim(); q.change = f[2].trim(); q.pct = f[3].trim();
            setDateTime(q, f[6] + " " + f[7], "北京时间");
        } else {
            return false;   // A 股走 parseSinaA（简版给数字、完整版给时间）
        }
        q.source = SRC_SINA;
        return true;
    }

    /**
     * 新浪 A 股：简版 s_xxx（名称,最新,涨跌,涨跌幅,…）给数字，完整版 xxx（…,日期(30),时间(31)）给行情时间；
     * 数字都取简版原字符串，不自己算。
     */
    static boolean parseSinaA(String shortRaw, String fullRaw, Quote q) {
        if (shortRaw == null || shortRaw.length() == 0) return false;
        String[] f = shortRaw.split(",", -1);
        if (f.length < 4 || !positive(f[1]) || !num(f[2]) || !num(f[3])) return false;
        q.price = f[1].trim(); q.change = f[2].trim(); q.pct = f[3].trim();
        if (fullRaw != null) {
            String[] g = fullRaw.split(",", -1);
            if (g.length >= 32) setDateTime(q, g[30] + " " + g[31], "北京时间");
        }
        q.source = SRC_SINA;
        return true;
    }

    private static final Pattern DT_COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})");
    private static final Pattern DT_SEP = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})");

    static void setDateTime(Quote q, String raw, String zone) {
        String s = raw == null ? "" : raw.trim();
        Matcher m = DT_COMPACT.matcher(s);
        if (!m.find()) { m = DT_SEP.matcher(s); if (!m.find()) return; }
        q.date = m.group(1) + "-" + pad(m.group(2)) + "-" + pad(m.group(3));
        q.time = pad(m.group(4)) + ":" + m.group(5);
        q.zone = zone;
    }

    private static String pad(String s) { return s.length() == 1 ? "0" + s : s; }

    // ------------------------------------------------------------------ 取数

    /** 取一个 URL 的正文（GBK）。单测注入假源。 */
    public interface Fetcher {
        String get(String url, String referer) throws Exception;
    }

    public static String tencentUrl() {
        return tencentUrl(Scope.GLOBAL.idx);
    }

    static String tencentUrl(List<Integer> ids) {
        StringBuilder sb = new StringBuilder("https://qt.gtimg.cn/q=");
        for (int k = 0; k < ids.size(); k++) { if (k > 0) sb.append(','); sb.append(INDICES[ids.get(k)][2]); }
        return sb.toString();
    }

    public static String sinaUrl(List<String> codes) {
        StringBuilder sb = new StringBuilder("https://hq.sinajs.cn/list=");
        for (int i = 0; i < codes.size(); i++) { if (i > 0) sb.append(','); sb.append(codes.get(i)); }
        return sb.toString();
    }

    static boolean isSinaA(String sinaCode) {
        return sinaCode.startsWith("sh") || sinaCode.startsWith("sz") || sinaCode.startsWith("bj");
    }

    public static final String SINA_REFERER = "https://finance.sina.com.cn/";

    /** 腾讯一次取全；没拿到的再用新浪补。任何异常都吞掉（拿不到 = 未查到）。 */
    public static Snapshot fetch(Fetcher f) {
        return fetch(f, Scope.GLOBAL);
    }

    /** ★ 11-04：只取范围内的指数（「A股行情」只取 A 股）。 */
    public static Snapshot fetch(Fetcher f, Scope scope) {
        if (scope == null) scope = Scope.GLOBAL;
        Snapshot s = new Snapshot();
        s.scope = scope;
        final List<Integer> ids = scope.idx;
        for (int i : ids) s.quotes.add(new Quote(INDICES[i][0], INDICES[i][1]));
        if (f == null) return s;
        Map<String, String> tv = null;
        try { tv = splitVars(f.get(tencentUrl(ids), null), VAR); } catch (Throwable ignored) {}
        if (tv != null) {
            for (int k = 0; k < ids.size(); k++) {
                String tc = INDICES[ids.get(k)][2];
                try { parseTencent(tc, tv.get(tc), s.quotes.get(k)); } catch (Throwable ignored) {}
            }
        }
        List<String> miss = new ArrayList<String>();
        for (int k = 0; k < ids.size(); k++) {
            if (s.quotes.get(k).found()) continue;
            String sc = INDICES[ids.get(k)][3];
            if (isSinaA(sc)) miss.add("s_" + sc);
            miss.add(sc);
        }
        if (miss.isEmpty()) return s;
        Map<String, String> sv = null;
        try { sv = splitVars(f.get(sinaUrl(miss), SINA_REFERER), SINA_VAR); } catch (Throwable ignored) {}
        if (sv != null) {
            for (int k = 0; k < ids.size(); k++) {
                Quote q = s.quotes.get(k);
                if (q.found()) continue;
                String sc = INDICES[ids.get(k)][3];
                try {
                    if (isSinaA(sc)) parseSinaA(sv.get("s_" + sc), sv.get(sc), q);
                    else parseSina(sc, sv.get(sc), q);
                } catch (Throwable ignored) {}
            }
        }
        return s;
    }

    /** 真实取数：HttpURLConnection，连接 3 s / 读 4 s，按 GBK 解码，正文上限 64 KB。 */
    public static final Fetcher HTTP = new Fetcher() {
        @Override public String get(String url, String referer) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(3000);
                c.setReadTimeout(4000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) OneSay");
                if (referer != null) c.setRequestProperty("Referer", referer);
                if (c.getResponseCode() != 200) return null;
                InputStream is = c.getInputStream();
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
                    return new String(bo.toByteArray(), "GBK");
                } finally {
                    try { is.close(); } catch (Exception ignored) {}
                }
            } finally {
                c.disconnect();
            }
        }
    };

    // ------------------------------------------------------------------ 结论槽位 / 提示

    /** 最终回答 = 「主要指数」槽位（结构化源原样）+ 空行 + 模型写的板块与原因。 */
    public static String withSlots(Snapshot s, String answer, long nowMillis) {
        if (s == null) return answer;
        String slots = s.render(nowMillis);
        if (answer == null || answer.trim().length() == 0) return slots;
        return slots + "\n\n" + answer.trim();
    }

    /** 开局提示（追加在用户消息后）：结构化数据已经有了，网页搜索只补板块与原因。 */
    public static String promptBlock(Snapshot s, long nowMillis) {
        Scope sc = s.scope == null ? Scope.GLOBAL : s.scope;
        String scopeLine = sc.global ? ""
                : "用户只问了「" + sc.label + "」：回答只谈" + sc.label + "的走势、板块与原因，不要扩展到其他市场。";
        return "（系统数据：下面「主要指数」是本机从结构化行情源取到的原始数据，系统会把它画成分地区涨跌幅柱状图放在回答卡片里。"
                + "你的回答不要再列这些指数的点位和涨跌幅（图里已有；非提不可就必须与下面一致），标「未查到」的不要自己编数字；"
                + "不要提「见上图」「结构化行情源」这类说法；"
                + scopeLine
                + "各地区的涨跌定性必须与下面「地区涨跌事实」一致：全部上涨才能说走强 / 上涨，全部下跌才能说走弱 / 下跌，涨跌互现就说分化；"
                + "盘中的只能说「盘中」「截至几点」，不能说收盘；"
                + "回答只写强势与逆势板块、代表个股和涨跌原因的简短说明（约 300 字内），标注日期与来源；"
                + "web.search / web.read 只用来补充这些。）\n" + s.render(nowMillis) + "\n" + regionFacts(s, nowMillis);
    }

    // ------------------------------------------------------------------ ★ 11-04：数字格式 / 地区涨跌事实 / 文字与数据一致性

    /** 点位统一保留 2 位小数（四舍五入）；解析不了原样返回。 */
    public static String fmt2(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 0) return t;
        try {
            return new java.math.BigDecimal(t).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return t;
        }
    }

    static double pctOf(Quote q) {
        if (q == null || !q.found() || q.pct == null) return Double.NaN;
        try { return Double.parseDouble(q.pct.trim().replace("%", "").replace("+", "")); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** 地区方向：1 = 全涨，-1 = 全跌，0 = 涨跌互现 / 有平盘，NaN 无数据用 null。 */
    static Integer directionOf(List<Quote> qs) {
        int up = 0, down = 0, n = 0;
        for (Quote q : qs) {
            double p = pctOf(q);
            if (Double.isNaN(p)) continue;
            n++;
            if (p > 0) up++; else if (p < 0) down++;
        }
        if (n == 0) return null;
        if (up == n) return 1;
        if (down == n) return -1;
        return 0;
    }

    /** 图上地区（「日欧」拆成日本 / 欧洲）→ 该地区的报价（按原顺序）。 */
    static Map<String, List<Quote>> byRegion(Snapshot s) {
        Map<String, List<Quote>> m = new LinkedHashMap<String, List<Quote>>();
        for (Quote q : s.quotes) {
            String r = "日欧".equals(q.region) ? (q.name.contains("日经") ? "日本" : "欧洲") : q.region;
            List<Quote> l = m.get(r);
            if (l == null) { l = new ArrayList<Quote>(); m.put(r, l); }
            l.add(q);
        }
        return m;
    }

    private static int hm(String t) {
        if (t == null || t.length() < 4) return -1;
        try { String[] a = t.split(":"); return Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]); } catch (Exception e) { return -1; }
    }

    /** 盘中 / 收盘（按行情时间与各市场收盘时刻；欧洲时区换算麻烦，只写「截至」）。 */
    static String sessionOf(String region, Quote q, long nowMillis) {
        if (q == null || q.date == null) return "时间未知";
        String at = "截至 " + (q.date.length() >= 10 ? q.date.substring(5) : q.date) + (q.time != null ? " " + q.time : "") + (q.zone != null ? " " + q.zone : "");
        String today;
        if ("美东时间".equals(q.zone)) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
            c.setTimeInMillis(nowMillis);
            today = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        } else {
            today = ymd(nowMillis);
        }
        int close;
        if ("A股".equals(region)) close = 15 * 60;
        else if ("港股".equals(region)) close = 16 * 60;
        else if ("美股".equals(region) && "美东时间".equals(q.zone)) close = 16 * 60;
        else if ("日本".equals(region)) close = 14 * 60 + 30;   // 东京 15:30 = 北京 14:30
        else return at;
        boolean closed = !q.date.equals(today) || hm(q.time) >= close;
        return (closed ? "已收盘，" : "盘中，") + at;
    }

    /** 给模型的「地区涨跌事实」：每个地区的状态、各指数涨跌幅与定性（全部上涨 / 全部下跌 / 涨跌互现）。 */
    public static String regionFacts(Snapshot s, long nowMillis) {
        StringBuilder sb = new StringBuilder("地区涨跌事实（按上面数据，定性必须一致）：");
        for (Map.Entry<String, List<Quote>> e : byRegion(s).entrySet()) {
            Integer d = directionOf(e.getValue());
            if (d == null) continue;
            Quote first = null;
            for (Quote q : e.getValue()) if (q.found()) { first = q; break; }
            sb.append("\n").append(e.getKey()).append("（").append(sessionOf(e.getKey(), first, nowMillis)).append("）：");
            int k = 0;
            for (Quote q : e.getValue()) {
                if (!q.found()) continue;
                sb.append(k++ > 0 ? "、" : "").append(shortName(q.name)).append(' ').append(signed(q.pct)).append('%');
            }
            sb.append(" → ").append(d == 1 ? "全部上涨" : d == -1 ? "全部下跌" : "涨跌互现（写「分化」，不要说走强或走弱）");
        }
        return sb.toString();
    }

    private static final String[][] REGION_TEXT = {
            {"A股", "A股|a股|A 股|大A|沪深两市|两市|沪深股市"},
            {"港股", "港股|香港股市|港股市场"},
            {"美股", "美股|美国股市|华尔街"},
            {"欧洲", "欧股|欧洲股市|欧洲市场|欧洲主要股指|欧洲三大"},
            {"日本", "日股|日本股市"},
    };
    private static final Pattern INDEX_ALIAS = Pattern.compile(
            "上证|沪指|深成|深证|创业板|沪深300|科创|北证|恒指|恒生|道指|道琼斯|标普|纳指|纳斯达克|日经|DAX|富时|个股|板块");
    private static final Pattern UP_WORD = Pattern.compile("上涨|收涨|走强|普涨|齐涨|大涨|飘红|走高|反弹|拉升|收高|攀升|全线上扬|上扬|强势");
    private static final Pattern DOWN_WORD = Pattern.compile("下跌|收跌|走弱|普跌|齐跌|大跌|飘绿|走低|回落|下挫|重挫|收低|跳水|承压|疲软|弱势");
    private static final Pattern MIXED_OK = Pattern.compile("分化|涨跌互现|互有涨跌|有涨有跌|涨跌不一|涨跌各异");

    private static boolean has(Pattern p, String clause) {
        Matcher m = p.matcher(clause);
        while (m.find()) {
            int i = m.start();
            String before = clause.substring(Math.max(0, i - 2), i);
            if (before.contains("未") || before.contains("没") || before.contains("不") || before.contains("非")) continue;
            return true;
        }
        return false;
    }

    /**
     * ★ 11-04（用户：说「美股走强」但道指 -0.34%）：地区定性与结构化数据是否矛盾。
     * 按「，。；！？换行」切分句，含地区词、但没点名具体指数 / 板块的分句才核：
     * 全涨却只说跌、全跌却只说涨、涨跌互现却说涨或跌（没写分化）→ 返回内部说明（给模型重写用，不回显用户）；不矛盾返回 null。
     */
    public static String consistencyIssue(Snapshot s, String answer) {
        if (s == null || answer == null || !s.any()) return null;
        Map<String, List<Quote>> by = byRegion(s);
        String[] clauses = answer.replace("涨跌幅", "").replace("涨幅", "").replace("跌幅", "").split("[，,。；;！!？?\n]");
        for (String c : clauses) {
            if (c.trim().length() == 0 || INDEX_ALIAS.matcher(c).find()) continue;
            for (String[] rt : REGION_TEXT) {
                if (!Pattern.compile(rt[1]).matcher(c).find()) continue;
                List<Quote> qs = by.get(rt[0]);
                Integer d = qs == null ? null : directionOf(qs);
                if (d == null) continue;
                boolean up = has(UP_WORD, c), down = has(DOWN_WORD, c), mixedOk = MIXED_OK.matcher(c).find();
                String bad = null;
                if (d == 1 && down && !up) bad = "全部上涨却写成下跌 / 走弱";
                else if (d == -1 && up && !down) bad = "全部下跌却写成上涨 / 走强";
                else if (d == 0 && (up || down) && !mixedOk) bad = "涨跌互现却写成单边" + (up ? "上涨 / 走强" : "下跌 / 走弱");
                if (bad == null) continue;
                StringBuilder f = new StringBuilder();
                for (Quote q : qs) if (q.found()) f.append(f.length() > 0 ? "、" : "").append(shortName(q.name)).append(' ').append(signed(q.pct)).append('%');
                return rt[0] + "：数据为 " + f + "，回答里「" + c.trim() + "」" + bad;
            }
        }
        return null;
    }

    /** 打回重写的内部指令（不回显用户）。 */
    public static String rewriteNudge(String issue, String query) {
        return "（内部核对，不要在回答里提及本条、不要道歉或解释）地区涨跌定性与行情数据矛盾：" + issue
                + "。请重写完整的最终回答：地区定性必须与数据一致（全部上涨才说上涨，全部下跌才说下跌，涨跌互现写「分化」），"
                + "其余要求不变，直接回答用户原问题：" + query;
    }
}
