package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 朗读表（OneSay 开源版）：所有会被念出来的固定句、时 / 分 / 温度读法、天气句、新闻引导句、降雨提醒句式，
 * 都来自数据文件 {@code assets/phrases/zh-CN.json}（说明见 docs/phrase-table.md）。代码里不再写死文案。
 *
 * <p>Android 侧由 {@code App.onCreate} 调 {@link #install(String)} 装入；JVM 测试按系统属性 {@code bc.assets}
 * 指向的目录懒加载。没装入 = 空表（模板件没有文本，不会念出任何东西）。
 */
public final class Bank {
    private Bank() { }

    public static final String ASSET = "phrases/zh-CN.json";
    public static final int T_MIN = -30, T_MAX = 50;

    /** 兼容旧常量名：语速档（实际值以朗读表 rates 为准）。 */
    public static double RI = 0.90, RN = 0.80;

    private static volatile Table table;

    static final class Table {
        final Map<String, Object[]> pieces = new LinkedHashMap<String, Object[]>();
        final List<String[]> wx = new ArrayList<String[]>();
        final List<String[]> src = new ArrayList<String[]>();
        final Map<String, String> rain = new LinkedHashMap<String, String>();
        String[] rainInt, snowInt, parts;
    }

    /** 装入朗读表 JSON（解析失败抛异常，原表不变）。 */
    public static synchronized void install(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Table t = new Table();
        JSONObject rates = o.getJSONObject("rates");
        double ri = rates.getDouble("normal"), rn = rates.getDouble("news");
        JSONArray ps = o.getJSONArray("pieces");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.getJSONObject(i);
            double r = "news".equals(p.optString("rate", "normal")) ? rn : ri;
            t.pieces.put(p.getString("key"), new Object[]{p.getString("text"), Double.valueOf(r)});
        }
        JSONArray wx = o.getJSONArray("weather");
        for (int i = 0; i < wx.length(); i++) t.wx.add(new String[]{wx.getJSONObject(i).getString("id"), wx.getJSONObject(i).getString("word")});
        JSONArray src = o.getJSONArray("newsSources");
        for (int i = 0; i < src.length(); i++) t.src.add(new String[]{src.getJSONObject(i).getString("id"), src.getJSONObject(i).getString("name")});
        JSONObject rain = o.getJSONObject("rain");
        for (String k : new String[]{"tail", "now", "soon", "later"}) t.rain.put(k, rain.getString(k));
        t.rain.put("what.rain", rain.getJSONObject("what").getString("rain"));
        t.rain.put("what.snow", rain.getJSONObject("what").getString("snow"));
        t.rainInt = strings(rain.getJSONObject("intensity").getJSONArray("rain"), 3);
        t.snowInt = strings(rain.getJSONObject("intensity").getJSONArray("snow"), 3);
        t.parts = strings(rain.getJSONArray("partOfDay"), 6);
        RI = ri;
        RN = rn;
        table = t;
    }

    private static String[] strings(JSONArray a, int n) throws Exception {
        if (a.length() != n) throw new Exception("phrase table: expected " + n + " entries");
        String[] s = new String[n];
        for (int i = 0; i < n; i++) s[i] = a.getString(i);
        return s;
    }

    static Table t() {
        Table t = table;
        if (t != null) return t;
        synchronized (Bank.class) {
            if (table != null) return table;
            String dir = System.getProperty("bc.assets");
            if (dir != null) {
                try {
                    InputStream in = new FileInputStream(dir + "/" + ASSET);
                    try {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        install(new String(bo.toByteArray(), "UTF-8"));
                    } finally { in.close(); }
                } catch (Exception ignored) { }
            }
            if (table == null) table = new Table();
            return table;
        }
    }

    public static String hourKey(int h) { return String.format("h%02d", h); }

    public static String minuteKey(int m) { return String.format("m%02d", m); }

    public static String tempCKey(int t) { return "tc" + t; }

    public static String tempDKey(int t) { return "td" + t; }

    public static boolean tempInRange(int t) { return t >= T_MIN && t <= T_MAX; }

    /** 天气键 → 天气词（表里 weather）。 */
    public static String wxWord(String key) {
        for (String[] w : t().wx) if (w[0].equals(key)) return w[1];
        return null;
    }

    /** 天气键 → 天气句（表里 wx_{键}）。 */
    public static String wxLine(String key) { return key == null ? null : text("wx_" + key); }

    public static boolean sourceKnown(String key) {
        if (key == null) return false;
        for (String[] s : t().src) if (s[0].equals(key)) return true;
        return false;
    }

    public static String sourceName(String key) {
        for (String[] s : t().src) if (s[0].equals(key)) return s[1];
        String net = null;
        for (String[] s : t().src) if ("net".equals(s[0])) net = s[1];
        return net == null ? "" : net;
    }

    /** 降雨句式与词（键：tail / now / soon / later / what.rain / what.snow）。 */
    public static String rain(String key) {
        String v = t().rain.get(key);
        return v == null ? "" : v;
    }

    /** 0 小 / 1 中 / 2 大。 */
    public static String rainIntensity(int level, boolean snow) {
        String[] a = snow ? t().snowInt : t().rainInt;
        return a == null ? "" : a[Math.max(0, Math.min(2, level))];
    }

    /** 0 凌晨 / 1 上午 / 2 中午 / 3 下午 / 4 傍晚 / 5 晚上。 */
    public static String partOfDay(int idx) {
        String[] a = t().parts;
        return a == null ? "" : a[Math.max(0, Math.min(5, idx))];
    }

    /** 按 {名字} 填占位符。 */
    public static String fill(String tpl, String... kv) {
        String s = tpl == null ? "" : tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    /** 全部件：key → {text, rate}（表里 pieces 的顺序）。 */
    public static Map<String, Object[]> all() {
        return Collections.unmodifiableMap(t().pieces);
    }

    public static String text(String key) {
        Object[] v = t().pieces.get(key);
        return v == null ? null : (String) v[0];
    }

    public static double rate(String key) {
        Object[] v = t().pieces.get(key);
        return v == null ? RI : (Double) v[1];
    }
}
