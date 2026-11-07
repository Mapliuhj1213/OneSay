package com.onesay.assistant.v2.search;

/**
 * 天气类问句补城市（R15，2026-10-16）。
 *
 * <p>R14 上机发现：「今天天气怎么样」原样交给检索，Tavily 返回的是美国城市的天气，
 * 模型据此答「没有你所在城市的天气」。根因是问句里没有地点，检索只能瞎猜。
 *
 * <p>规则（纯函数，可 JVM 单测）：
 * <ol>
 *   <li>没配置常用城市（空串）⇒ 原样返回 —— <b>代码里不写死任何城市</b>，城市由用户决定；</li>
 *   <li>不是天气类问句 ⇒ 原样返回；</li>
 *   <li>问句里已经有地点（含配置的城市、或常见地名后缀 / 主要城市名）⇒ 原样返回
 *       （「北京明天下雨吗」不能被改成「佛山 北京明天下雨吗」）；</li>
 *   <li>否则在问句前加「城市 」。</li>
 * </ol>
 */
public final class WeatherQuery {

    private WeatherQuery() { }

    static final String[] WEATHER_WORDS = {
            "天气", "下雨", "下雪", "气温", "温度", "几度", "冷不冷", "热不热", "刮风", "风大",
            "雾霾", "空气质量", "空气怎么样", "台风", "降温", "升温", "带伞", "穿什么", "紫外线", "湿度"
    };

    /** 有这些后缀/词就视为已经带了地点（宁可少补，不可补错）。 */
    static final String[] PLACE_MARKS = {
            "市", "省", "县", "区", "镇", "乡", "州",
            "北京", "上海", "广州", "深圳", "天津", "重庆", "杭州", "南京", "武汉", "成都", "西安",
            "长沙", "苏州", "厦门", "青岛", "大连", "沈阳", "哈尔滨", "长春", "郑州", "济南", "合肥",
            "福州", "南昌", "昆明", "贵阳", "南宁", "海口", "三亚", "拉萨", "兰州", "西宁", "银川",
            "乌鲁木齐", "呼和浩特", "石家庄", "太原", "香港", "澳门", "台北", "佛山", "东莞", "珠海",
            "中山", "惠州", "汕头", "湛江", "肇庆", "清远", "无锡", "宁波", "温州",
            "东京", "首尔", "纽约", "伦敦", "巴黎"
    };

    public static boolean isWeatherQuery(String q) {
        if (q == null) return false;
        for (String w : WEATHER_WORDS) if (q.contains(w)) return true;
        return false;
    }

    public static boolean hasPlace(String q, String city) {
        if (q == null) return false;
        if (city != null && city.length() > 0 && q.contains(city)) return true;
        for (String p : PLACE_MARKS) if (q.contains(p)) return true;
        return false;
    }

    /** @return 应当拿去检索的问句（可能就是原句）。永不返回 null（入参为 null 时返回 null）。 */
    public static String withHomeCity(String query, String city) {
        if (query == null) return null;
        String c = city == null ? "" : city.trim();
        if (c.length() == 0) return query;
        if (!isWeatherQuery(query)) return query;
        if (hasPlace(query, c)) return query;
        return c + " " + query;
    }
}
