package com.onesay.clock.core;

/**
 * 全部设置项的键与默认值（纯 Java，JVM 测「开关默认值」）。
 * 规则：三星基线行为默认开；基线之外的新增功能一律默认关。
 */
public final class Flags {
    private Flags() { }

    // ---- 基线（对齐三星）
    /**
     * 每天只播报一次：当天已有闹钟响过后，之后的播报闹钟只响铃。默认关（0.1.3 起）。
     * 0.1.0–0.1.2 把三星 AlarmItem.isFirstAlarm() 误读成「当天第一个闹钟」而默认开；
     * 三星 7.0.92 的 isFirstAlarm() 实为 mActivate==1（本次是正点首响、不是贪睡再响），所以三星每个播报闹钟正点都播。
     */
    public static final String FIRST_ONLY = "firstOnly";
    public static final boolean FIRST_ONLY_DEF = false;
    /** 播报完没人操作 → 闹钟流铃声（R1 优先）。默认开，且界面不提供关闭（永不静音）。 */
    public static final String RING_AFTER = "ringAfter";
    public static final boolean RING_AFTER_DEF = true;
    /** 振动。默认开。 */
    public static final String VIBRATE = "vibrate";
    public static final boolean VIBRATE_DEF = true;
    /** 最低音量保护：闹钟音量 < 30% 时临时提到 30%，结束还原。默认开。 */
    public static final String MIN_VOL = "minVol";
    public static final boolean MIN_VOL_DEF = true;
    /** 提前生成分钟数。 */
    public static final String PREGEN_MIN = "pregenMin";
    public static final int PREGEN_MIN_DEF = 10;
    public static final int[] PREGEN_CHOICES = {5, 10, 15, 30};
    /**
     * 响铃多久没人操作就自动停止（分钟）。0.1.5 起（用户 11-06 定）到时是「停止」不是贪睡：等同用户点停止，清贪睡、排下一次。
     * 键名沿用 autoSnoozeMin，老用户改过的值照样生效。
     */
    public static final String AUTO_SNOOZE_MIN = "autoSnoozeMin";
    public static final int AUTO_SNOOZE_MIN_DEF = 10;
    /** 音量键：0 无 / 1 贪睡 / 2 停止。电源键永不处理（P2-5）。 */
    public static final String VOLUME_KEY = "volumeKey";
    public static final int VOLUME_KEY_DEF = 0;
    /** 天气：0 自动定位（失败用城市）/ 1 只用所选城市。 */
    public static final String WX_MODE = "wxMode";
    public static final int WX_MODE_DEF = 0;
    public static final String CITY = "city";
    public static final String CITY_DEF = "";

    // ---- 新增功能（全部默认关）
    /** 新闻取不到时说一句「今天的新闻暂时没取到」。默认关（原版没有这句，跳过最自然）。 */
    public static final String NEWS_MISS_LINE = "newsMissLine";
    public static final boolean NEWS_MISS_LINE_DEF = false;
    /** 下雨概率 ≥ 50% 时提醒带伞。 */
    public static final String X_UMBRELLA = "xUmbrella";
    public static final boolean X_UMBRELLA_DEF = false;
    /**
     * 播报降雨提醒（0.1.6，用户 2026-11-07 定）：和风分钟级降水 + 逐小时预报，在天气句后多说一句（见 RainLine）。
     * 例外：默认开——但只有配了和风 API Key + API Host 才生效；没配 = 功能关，设置页写原因（第三方 key 由用户自己配）。
     */
    public static final String X_RAIN_LINE = "xRainLine";
    public static final boolean X_RAIN_LINE_DEF = true;
    /** 和风天气 API Key / API Host（App 私有 prefs，同百炼 key；日志只记长度 / 是否已配）。 */
    public static final String QW_KEY = "qweatherKey";
    public static final String QW_HOST = "qweatherHost";
    /** 生日当天加一句生日祝福。 */
    public static final String X_BIRTHDAY = "xBirthday";
    public static final boolean X_BIRTHDAY_DEF = false;
    /** 生日 MM-dd。 */
    public static final String BIRTHDAY = "birthday";
    public static final String BIRTHDAY_DEF = "";
    /**
     * 天气来源：手机自带的三星天气（三星适配层：ContentProvider，被拒再 root 读库，8 秒超时），读不到回落 open-meteo。
     * 私有版 0.1.1 起默认开；开源版默认关（三星 / root 专属适配，默认不启用，需要时在设置里打开）。
     */
    public static final String X_ROOT_WEATHER = "xRootWeather";
    public static final boolean X_ROOT_WEATHER_DEF = false;
    /**
     * 开源版：新闻源「腾讯新闻」是否参与。默认关（开源构建不默认依赖 / 播报腾讯新闻）；
     * 关时只用其余已启用的源，引导句也不会念「腾讯新闻」。
     */
    public static final String X_NEWS_TENCENT = "xNewsTencent";
    public static final boolean X_NEWS_TENCENT_DEF = false;
    /** 开源版：新闻源「今日头条热榜」（非官方公开接口），默认关，用户在设置里打开。 */
    public static final String X_NEWS_TOUTIAO = "xNewsToutiao";
    public static final boolean X_NEWS_TOUTIAO_DEF = false;
    /** 开源版：新闻源「B 站热搜」（非官方公开接口），默认关，用户在设置里打开。 */
    public static final String X_NEWS_BILIBILI = "xNewsBilibili";
    public static final boolean X_NEWS_BILIBILI_DEF = false;
    /** 开源版：云端语音（百炼 CosyVoice）音色 ID，由用户在设置里填写；空 = 不做云端合成。 */
    public static final String COSY_VOICE = "cosyVoice";
    public static final String COSY_VOICE_DEF = "";
    /** 开源版：云端语音服务地址（DashScope 兼容接口；用户可改成自己的服务，必须 https）。 */
    public static final String TTS_ENDPOINT = "ttsEndpoint";
    public static final String TTS_ENDPOINT_DEF = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
    /** 开源版：云端语音模型名（可改）。 */
    public static final String COSY_MODEL = "cosyModel";
    public static final String COSY_MODEL_DEF = "cosyvoice-v3.5-flash";
    /** LLM 改写标题（本版未接入，开关只占位，恒为关）。 */
    public static final String X_LLM = "xLlm";
    public static final boolean X_LLM_DEF = false;
    /** 云端 Edge 朗读（本版未接入，恒为关）。 */
    public static final String X_EDGE = "xEdge";
    public static final boolean X_EDGE_DEF = false;

    /** 设置版本：升级时清掉已作废的键、让改了默认值的键回到新默认（见 App.migrate）。 */
    public static final String CFG_VER = "cfgVer";
    public static final int CFG_VER_NOW = 3;
    /** 0.1.0 → 0.1.1：删掉「历史上的今天」；天气来源改为默认读三星天气（旧版这个键只可能是默认关，清掉即回到新默认）。 */
    public static final String[] DROP_KEYS_V2 = {"xHistory", X_ROOT_WEATHER};
    /** 0.1.2 → 0.1.3：「只在当天第一个闹钟播报」改默认关（旧值多半是旧默认开）；清掉被调试闹钟 990 污染的 lastFiredDay。 */
    public static final String[] DROP_KEYS_V3 = {FIRST_ONLY, "lastFiredDay"};

    /** 从 from 版本升到 CFG_VER_NOW 要删的键（纯函数，JVM 测）。 */
    public static java.util.List<String> dropKeys(int from) {
        java.util.List<String> r = new java.util.ArrayList<String>();
        if (from < 2) r.addAll(java.util.Arrays.asList(DROP_KEYS_V2));
        if (from < 3) r.addAll(java.util.Arrays.asList(DROP_KEYS_V3));
        return r;
    }

    /** 调试（只有 ControlReceiver 会写，TEST_CLEAR 清掉）：提前生成秒数、贪睡秒数覆盖。0 = 不覆盖。 */
    public static final String DBG_PREGEN_SEC = "dbgPregenSec";
    public static final String DBG_SNOOZE_SEC = "dbgSnoozeSec";
    /** 调试：响铃超时秒数覆盖（0.1.5，冒烟用；0 = 用设置的分钟数）。 */
    public static final String DBG_TIMEOUT_SEC = "dbgTimeoutSec";
    /** 设置页「自动停止」可选分钟。 */
    public static final int[] AUTO_STOP_CHOICES = {5, 10, 15, 20, 30};

    /** 布尔开关默认值查表（未知键 → false）。 */
    public static boolean boolDefault(String key) {
        if (FIRST_ONLY.equals(key)) return FIRST_ONLY_DEF;
        if (RING_AFTER.equals(key)) return RING_AFTER_DEF;
        if (VIBRATE.equals(key)) return VIBRATE_DEF;
        if (MIN_VOL.equals(key)) return MIN_VOL_DEF;
        if (X_ROOT_WEATHER.equals(key)) return X_ROOT_WEATHER_DEF;
        if (X_RAIN_LINE.equals(key)) return X_RAIN_LINE_DEF;
        return false;
    }

    /** 本版没实现的开关：界面置灰、读取恒为 false。 */
    public static boolean notImplemented(String key) {
        return X_LLM.equals(key) || X_EDGE.equals(key);
    }

    public static final String[] ALL_BOOL = {FIRST_ONLY, RING_AFTER, VIBRATE, MIN_VOL, NEWS_MISS_LINE, X_UMBRELLA,
            X_BIRTHDAY, X_ROOT_WEATHER, X_RAIN_LINE, X_LLM, X_EDGE, X_NEWS_TENCENT, X_NEWS_TOUTIAO, X_NEWS_BILIBILI};
}
