package com.onesay.clock.core;

/** 天气 → 模板天气键（Bank.WX）。来源可以是 open-meteo 的 WMO 码，也可以是三星天气库的中文描述。认不出 → null（不念天气句）。 */
public final class WeatherCode {
    private WeatherCode() { }

    public static String fromWmo(int c) {
        switch (c) {
            case 0: case 1: return "qinglang";
            case 2: case 3: return c == 2 ? "duoyun" : "yin";
            case 45: case 48: return "youwu";
            case 51: case 53: case 55: case 56: case 57: case 61: return "xiaoyu";
            case 63: case 66: return "zhongyu";
            case 65: case 67: return "dayu";
            case 71: case 77: case 85: return "xiaoxue";
            case 73: return "xiaxue";
            case 75: case 86: return "daxue";
            case 80: case 81: return "zhenyu";
            case 82: return "baoyu";
            case 95: case 96: case 99: return "leizhenyu";
            default: return null;
        }
    }

    /** 中文描述（「小雨转阴」只看「转」前）。强条件在前。 */
    public static String fromDesc(String desc) {
        String d = desc == null ? "" : desc.trim();
        int z = d.indexOf('转');
        if (z > 0) d = d.substring(0, z);
        if (d.length() == 0) return null;
        if (d.contains("雨夹雪")) return "yujiaxue";
        if (d.contains("雷")) return "leizhenyu";
        if (d.contains("暴雨")) return "baoyu";
        if (d.contains("大雨")) return "dayu";
        if (d.contains("中雨")) return "zhongyu";
        if (d.contains("阵雨")) return "zhenyu";
        if (d.contains("小雨") || d.contains("毛毛雨")) return "xiaoyu";
        if (d.contains("雨")) return "xiayu";
        if (d.contains("大雪") || d.contains("暴雪")) return "daxue";
        if (d.contains("小雪")) return "xiaoxue";
        if (d.contains("雪")) return "xiaxue";
        if (d.contains("霾")) return "wumai";
        if (d.contains("雾")) return "youwu";
        if (d.contains("沙") || d.contains("尘")) return "shachen";
        if (d.contains("云")) return "duoyun";
        if (d.contains("阴")) return "yin";
        if (d.contains("晴")) return "qinglang";
        if (d.contains("风")) return "dafeng";
        return null;
    }

    /** 三星天气（TWC 数据源）的 COL_WEATHER_ICON_NUM → 天气键；中文描述认不出时兜底。认不出 → null。 */
    public static String fromTwc(int n) {
        switch (n) {
            case 3: case 4: case 37: case 38: case 47: return "leizhenyu";
            case 5: case 6: case 7: case 18: case 35: return "yujiaxue";
            case 8: case 9: case 10: return "xiaoyu";
            case 11: case 39: case 45: return "zhenyu";
            case 12: return "zhongyu";
            case 40: return "dayu";
            case 13: case 14: case 41: case 46: return "xiaoxue";
            case 15: case 16: case 17: return "xiaxue";
            case 42: case 43: return "daxue";
            case 19: return "shachen";
            case 20: return "youwu";
            case 21: case 22: return "wumai";
            case 23: case 24: case 0: case 1: case 2: return "dafeng";
            case 26: return "yin";
            case 27: case 28: case 29: case 30: return "duoyun";
            case 31: case 32: case 33: case 34: case 36: case 25: return "qinglang";
            default: return null;
        }
    }

    /** 温度合理性：摄氏 -60..60 原样；超出视为华氏换算（设置成 °F 的机器）；NaN 原样。 */
    public static double celsius(double t, int tempScale) {
        if (Double.isNaN(t)) return t;
        if (tempScale == 0 && t > 60) return Math.round((t - 32) * 5 / 9.0);
        return t;
    }

    /** 天气键 → 响铃页图标键（res/drawable-nodpi/alert_wx_*.png）；夜里晴换月亮。 */
    public static String icon(String wxKey, int hour) {
        if (wxKey == null) return null;
        boolean day = hour >= 6 && hour < 18;
        if (wxKey.equals("leizhenyu")) return "thunderstorm";
        if (wxKey.equals("youwu") || wxKey.equals("wumai") || wxKey.equals("shachen")) return "fog";
        if (wxKey.contains("xue")) return "snow";
        // 0.1.1 修：「duoyun」里也含 "yu"，原来先判 contains("yu") 把多云画成了雨（真机 12:21 响铃页截图发现）——云/阴必须先判
        if (wxKey.equals("duoyun") || wxKey.equals("yin")) return "cloudy";
        if (wxKey.equals("dafeng")) return "windy";
        if (wxKey.contains("yu")) return "rain";
        if (wxKey.equals("qinglang")) return day ? "sunny" : "clear";
        return null;
    }
}
