package com.onesay.clock.core;

/**
 * 天气 → 背景音乐（照搬 v1 main WeatherBgm.pick，逐条同序）。曲目是 v1 从原厂 ClockPackage res/raw 提取的 6 段 + default_sound，
 * 打包在 assets/bgm/。判定用中文天气文本 + 温度（三星天气给的就是中文短语）；网络天气只有天气键时先把键译回中文再判。
 * 读不到天气 → v1 的默认曲目 default_sound.ogg。
 */
public final class WeatherBgm {
    private WeatherBgm() { }

    public static final String DIR = "bgm/";
    public static final String FALLBACK = "default_sound.ogg";

    // v1 BriefingService / Cfg 的混音参数（默认值，原样）
    /** 配乐独奏时长：之后人声开口、配乐同时让位。 */
    public static final long LEAD_MS = 1500L;
    /** 配乐开场音量（v1 bgmStart）。 */
    public static final float START = 0.80f;
    /** 人声期间配乐垫底（v1 musicBed）。 */
    public static final float BED = 0.25f;
    /** 播完推上来的音量（v1 musicFull）。 */
    public static final float FULL = 0.55f;
    /** 让位斜坡（v1 duckMs）。 */
    public static final long DUCK_MS = 1200L;
    /** 人声渐强（v1 fadeInMs）。 */
    public static final long VOICE_FADE_IN_MS = 300L;
    /** 人声尾段轻收到的比例与窗口（v1 VOICE_TAIL_FLOOR / VOICE_TAIL_MS，窗口 = min(3000, max(800, dur/4))）。 */
    public static final float VOICE_TAIL_FLOOR = 0.35f;
    public static final long VOICE_TAIL_MAX_MS = 3000L;

    public static long tailMs(long durMs) { return Math.min(VOICE_TAIL_MAX_MS, Math.max(800L, durMs / 4)); }

    /** v1 WeatherBgm.pick（原样）。 */
    public static String pick(String desc, double temp) {
        String d = desc == null ? "" : desc.toLowerCase();
        if (any(d, "雷", "thunder", "t-storm", "tstorm", "storm")) return "thunder.ogg";
        if (any(d, "雪", "冰", "冻", "snow", "sleet", "ice", "flurr", "blizzard")) return "snow.ogg";
        if (any(d, "雨", "rain", "shower", "drizzle")) return "rain.ogg";
        if (any(d, "雾", "霾", "沙", "尘", "fog", "haze", "mist", "smoke", "dust")) return "fog_cloudy.ogg";
        if (any(d, "风", "wind", "breez", "gale")) return "windy_cold.ogg";
        if (any(d, "多云", "阴", "云", "cloud", "overcast")) return "fog_cloudy.ogg";
        if (any(d, "晴", "少云", "sunny", "clear", "fair")) return "sunny_clear_hot_warm.ogg";
        if (Double.isNaN(temp)) return FALLBACK;
        if (temp <= 8) return "windy_cold.ogg";
        if (temp >= 28) return "sunny_clear_hot_warm.ogg";
        return FALLBACK;
    }

    /** 模板天气键 → 中文词（网络天气没有中文描述时用）。 */
    public static String keyWord(String key) {
        if (key == null) return "";
        String[][] t = {{"yujiaxue", "雨夹雪"}, {"leizhenyu", "雷阵雨"}, {"baoyu", "暴雨"}, {"dayu", "大雨"}, {"zhongyu", "中雨"},
                {"zhenyu", "阵雨"}, {"xiaoyu", "小雨"}, {"xiayu", "下雨"}, {"daxue", "大雪"}, {"xiaoxue", "小雪"}, {"xiaxue", "下雪"},
                {"wumai", "雾霾"}, {"youwu", "有雾"}, {"shachen", "沙尘"}, {"duoyun", "多云"}, {"yin", "阴天"}, {"qinglang", "晴朗"}, {"dafeng", "大风"}};
        for (String[] r : t) if (r[0].equals(key)) return r[1];
        return "";
    }

    /** 实际用的选曲：有天气（ok）→ 中文描述（不是中文就用天气键译回）+ 温度；没天气 → 默认曲目。 */
    public static String route(boolean ok, String desc, String key, double temp) {
        if (!ok) return FALLBACK;
        String d = desc == null ? "" : desc;
        boolean zh = false;
        for (int i = 0; i < d.length(); i++) if (d.charAt(i) >= 0x4E00 && d.charAt(i) <= 0x9FFF) { zh = true; break; }
        if (!zh) d = keyWord(key);
        return pick(d, temp);
    }

    /** v1 WeatherBgm.label（日志/界面用）。 */
    public static String label(String asset) {
        if (asset == null) return "无";
        if (asset.startsWith("thunder")) return "雷雨 · 低沉";
        if (asset.startsWith("snow")) return "雪天 · 温暖";
        if (asset.startsWith("rain")) return "雨天 · 安静";
        if (asset.startsWith("fog_cloudy")) return "多云 · 舒缓";
        if (asset.startsWith("windy_cold")) return "风冷 · 空灵";
        if (asset.startsWith("sunny_clear_hot_warm")) return "晴暖 · 轻快";
        return "通用 · 晨间";
    }

    private static boolean any(String s, String... ks) {
        for (String k : ks) if (s.indexOf(k) >= 0) return true;
        return false;
    }
}
