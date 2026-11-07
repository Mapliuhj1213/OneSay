package com.onesay.assistant.v2.city;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;

/**
 * [lane C 第 2 项] 常用城市的裁决（纯函数，JVM 可测）。
 *
 * <p>常用城市来自<b>手动设置</b>、<b>你的默认天气 App 屏幕上显示的城市</b>（无障碍读屏，你在控制台点一下才读）和<b>定位</b>。优先级（高 → 低）：
 * <ol>
 *   <li>用户手动设置的 {@code bixby_search_cfg/home_city}（R15 的设置项，非空就用它，别的都不看）；</li>
 *   <li>默认天气 App 的屏幕：你在控制台点「从天气 App 读城市」时，经无障碍读它首屏显示的城市（{@link #cityFromScreen}），
 *       结果缓存起来，不定期自动打开别的 App；</li>
 *   <li>定位：最后一次已知位置 → 经纬度反查城市（系统 Geocoder → BigDataCloud → Nominatim）；</li>
 *   <li>都拿不到（含<b>定位权限没给</b>）→ 空串 = 现在的行为（天气问句不补城市）。</li>
 * </ol>
 */
public final class HomeCityPolicy {

    private HomeCityPolicy() {}

    public enum Source { MANUAL, SYS_WEATHER, LOCATION, NONE }

    public static final class Result {
        public final String city;
        public final Source source;

        public Result(String city, Source source) {
            this.city = city == null ? "" : city;
            this.source = source;
        }

        @Override
        public String toString() {
            return source + ":" + (city.length() == 0 ? "(空)" : city);
        }
    }

    /** 天气 App 里的一行城市（多城市列表排序用）。 */
    public static final class SysCity {
        public final String key;
        public final String name;
        public final int order;

        public SysCity(String key, String name, int order) {
            this.key = key;
            this.name = name;
            this.order = order;
        }
    }

    /** 缓存有效期：天气 App 城市 / 定位反查的结果 6 小时内复用（手动城市不缓存，每次现读）。 */
    public static final long CACHE_TTL_MS = 6L * 3600L * 1000L;
    /** 什么都没拿到时，30 分钟内不再重试（root / 网络都要花时间）。 */
    public static final long NEGATIVE_TTL_MS = 30L * 60L * 1000L;

    /**
     * 最终裁决。
     *
     * @param manual        手动设置（可空）
     * @param sysWeather    系统天气选出的城市（可空 = 读不到）
     * @param locationCity  定位反查出的城市（可空）
     * @param locationGranted 定位权限是否已授予（没授予时 locationCity 一律不用）
     */
    public static Result decide(String manual, String sysWeather, String locationCity, boolean locationGranted) {
        String m = clean(manual);
        if (m.length() > 0) return new Result(m, Source.MANUAL);
        String s = clean(sysWeather);
        if (s.length() > 0) return new Result(s, Source.SYS_WEATHER);
        if (locationGranted) {
            String l = clean(locationCity);
            if (l.length() > 0) return new Result(l, Source.LOCATION);
        }
        return new Result("", Source.NONE);
    }

    /** 需要去读系统天气 / 定位吗（手动城市非空就不用；缓存没过期也不用）。 */
    public static boolean needResolve(String manual, long cachedAtMs, boolean cachedEmpty, long nowMs) {
        if (clean(manual).length() > 0) return false;
        if (cachedAtMs <= 0L || nowMs < cachedAtMs) return true;
        long ttl = cachedEmpty ? NEGATIVE_TTL_MS : CACHE_TTL_MS;
        return nowMs - cachedAtMs >= ttl;
    }

    /**
     * 系统天气城市列表 → 有序列表（选中的那座排第一，其余按 COL_WEATHER_ORDER）。空名 / 重名去掉。
     */
    public static List<String> orderSysCities(List<SysCity> rows, String selectedKey) {
        List<String> out = new ArrayList<String>();
        if (rows == null || rows.isEmpty()) return out;
        List<SysCity> sorted = new ArrayList<SysCity>(rows);
        Collections.sort(sorted, new java.util.Comparator<SysCity>() {
            @Override
            public int compare(SysCity a, SysCity b) {
                return a.order < b.order ? -1 : (a.order == b.order ? 0 : 1);
            }
        });
        if (selectedKey != null && selectedKey.length() > 0) {
            for (SysCity c : sorted) {
                if (selectedKey.equals(c.key)) {
                    String n = clean(c.name);
                    if (n.length() > 0) out.add(n);
                    break;
                }
            }
        }
        for (SysCity c : sorted) {
            String n = clean(c.name);
            if (n.length() > 0 && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** 列表里拿来补问句的那座（第一个）。 */
    public static String pickSysCity(List<SysCity> rows, String selectedKey) {
        List<String> l = orderSysCities(rows, selectedKey);
        return l.isEmpty() ? "" : l.get(0);
    }

    /**
     * 城市名清洗：去首尾空白；三星库里偶见「佛山, 广东省」这种带省的写法 → 只留逗号前；
     * 「我的位置 / Current location」这类占位名不算城市。
     */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        int k = indexOfAny(t, ",", "，");
        if (k > 0) t = t.substring(0, k).trim();
        if (t.length() == 0) return "";
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("current location") || low.equals("my location") || t.equals("我的位置") || t.equals("当前位置")) return "";
        if (t.length() > 24) return "";   // 不像城市名（防止把一段乱码 / 哈希当城市）
        return t;
    }

    private static int indexOfAny(String s, String... keys) {
        int best = -1;
        for (String k : keys) {
            int i = s.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------------ 反查结果解析

    /** BigDataCloud reverse-geocode-client 的返回 → 城市（city，没有时退 locality）。解析失败返回空串。 */
    public static String parseBigDataCloud(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String c = clean(o.optString("city", ""));
            if (c.length() > 0) return c;
            return clean(o.optString("locality", ""));
        } catch (Throwable t) {
            return "";
        }
    }

    /** Nominatim reverse（jsonv2）→ 城市：address.city → town → county → state_district。解析失败返回空串。 */
    public static String parseNominatim(String json) {
        try {
            JSONObject a = new JSONObject(json).optJSONObject("address");
            if (a == null) return "";
            String[] keys = {"city", "town", "county", "state_district"};
            for (String k : keys) {
                String c = clean(a.optString(k, ""));
                if (c.length() > 0) return c;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }


    // ------------------------------------------------------------------ 读默认天气 App 的屏幕（通用，不碰任何私有数据库）

    private static final java.util.regex.Pattern CITY_SUFFIX = java.util.regex.Pattern.compile("^([\\u4e00-\\u9fa5]{1,8})(市|区|县)$");
    private static final java.util.regex.Pattern NOT_CITY = java.util.regex.Pattern.compile(
            "天气|预报|空气|湿度|风|日出|日落|紫外线|体感|降水|小时|今天|明天|周[一二三四五六日天]|星期|设置|添加|管理|更多|分享|[0-9℃°%]");

    /**
     * 从天气 App 首屏的文字（按从上到下的顺序）里认出当前城市（纯函数，JVM 单测）：
     * 优先「XX市 / XX区 / XX县」（去掉「市」）；否则取靠前的、2–6 个汉字、不像天气字段的短文本，
     * 且它在 {@link com.onesay.assistant.v2.search.WeatherQuery} 的常见地名表里或紧挨着定位图标描述（「当前位置」「定位」）。认不出 → 空串。
     */
    public static String cityFromScreen(List<String> texts) {
        if (texts == null) return "";
        for (String t0 : texts) {
            String t = t0 == null ? "" : t0.trim();
            java.util.regex.Matcher m = CITY_SUFFIX.matcher(t);
            if (m.matches() && !NOT_CITY.matcher(t).find()) return "市".equals(m.group(2)) ? clean(m.group(1)) : clean(t);
        }
        boolean nearLocate = false;
        for (String t0 : texts) {
            String t = t0 == null ? "" : t0.trim();
            if (t.contains("当前位置") || t.contains("定位") || t.equalsIgnoreCase("location")) { nearLocate = true; continue; }
            if (!t.matches("[\\u4e00-\\u9fa5]{2,6}") || NOT_CITY.matcher(t).find()) { nearLocate = false; continue; }
            if (nearLocate || com.onesay.assistant.v2.search.WeatherQuery.hasPlace(t, null)) return clean(t);
        }
        return "";
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
