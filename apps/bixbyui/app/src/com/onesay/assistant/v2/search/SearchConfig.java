package com.onesay.assistant.v2.search;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 联网搜索配置（持久化于 SharedPreferences，文件名 {@code bixby_search_cfg}）。
 *
 * <p>与 ASR / LLM 的配置分开存，是为了让"哪把 Key 属于哪个服务"一目了然 ——
 * 这个工程里已经有过一次教训：把百炼的 Key 填进 DeepSeek 的槽位、
 * 或者反过来拿 NLS 的 AK 去打 DashScope，两边都会以"网络失败"的面目出现，
 * 排查起来非常费劲。一个服务一个配置文件，互相不串。
 */
public final class SearchConfig {

    private static final String PREF_NAME = "bixby_search_cfg";

    private static final String K_ENABLED = "enabled";
    private static final String K_PROVIDER = "provider";
    private static final String K_TAVILY_KEY = "tavily_key";
    private static final String K_BOCHA_KEY = "bocha_key";
    /** ★ feat/api-first：海纳开放平台（gw-openapi.zhidemai.com）商品检索的 key，与 Tavily 同一套 SecretPrefs 加密存。 */
    private static final String K_HAINA_KEY = "haina_key";
    /** R15：天气类问句没带地点时补的城市；空 = 不补（代码不写死城市）。 */
    private static final String K_HOME_CITY = "home_city";

    /** auto = 按优先级自动挑第一个就绪的（Tavily → 博查 → 必应网页） */
    public static final String PROVIDER_AUTO = "auto";
    public static final String PROVIDER_TAVILY = "tavily";
    public static final String PROVIDER_BOCHA = "bocha";
    public static final String PROVIDER_BING = "bing";

    private final Context mContext;

    public SearchConfig(Context context) {
        mContext = context.getApplicationContext();
    }

    private SharedPreferences sp() {
        return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 联网问答总开关。
     *
     * <p><b>默认 true</b> —— 与云端 ASR 不同，这里的默认值是开。
     * 理由：不开就等于"LLM 永远答不出实时问题"（这正是用户报的缺陷），
     * 而免 Key 的必应网页通道没有任何配置成本，开箱即用。
     */
    public boolean isEnabled() {
        return sp().getBoolean(K_ENABLED, true);
    }

    public void setEnabled(boolean v) {
        sp().edit().putBoolean(K_ENABLED, v).apply();
    }

    public String getProvider() {
        String v = sp().getString(K_PROVIDER, PROVIDER_AUTO);
        return (v == null || v.trim().length() == 0) ? PROVIDER_AUTO : v.trim();
    }

    public void setProvider(String v) {
        sp().edit().putString(K_PROVIDER, v == null ? "" : v.trim()).apply();
    }

    /** 常用城市（天气问句补地点用）；默认空串 = 不补。 */
    public String getHomeCity() {
        String v = sp().getString(K_HOME_CITY, "");
        return v == null ? "" : v.trim();
    }

    public void setHomeCity(String v) {
        sp().edit().putString(K_HOME_CITY, v == null ? "" : v.trim()).apply();
    }

    public String getTavilyKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_TAVILY_KEY);
    }

    public void setTavilyKey(String v) {
        { android.content.SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_TAVILY_KEY, v == null ? "" : v.trim()); e.apply(); }
    }

    public String getBochaKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_BOCHA_KEY);
    }

    public void setBochaKey(String v) {
        { android.content.SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_BOCHA_KEY, v == null ? "" : v.trim()); e.apply(); }
    }

    /** ★ feat/api-first：海纳商品检索 key（路由层第 ② 档；没填 = 跳过这一档）。 */
    public String getHainaKey() {
        return com.onesay.assistant.v2.security.SecretPrefs.get(sp(), K_HAINA_KEY);
    }

    public void setHainaKey(String v) {
        { android.content.SharedPreferences.Editor e = sp().edit(); com.onesay.assistant.v2.security.SecretPrefs.put(e, K_HAINA_KEY, v == null ? "" : v.trim()); e.apply(); }
    }

    /** 一行摘要（已脱敏），供日志与自测展示。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(isEnabled());
        sb.append(", provider=").append(getProvider());
        sb.append(", tavily=").append(mask(getTavilyKey()));
        sb.append(", bocha=").append(mask(getBochaKey()));
        sb.append(", haina=").append(mask(getHainaKey()));
        return sb.toString();
    }

    /** describe() 会进日志 —— 只说有没有，不露 Key 的前后缀与长度。 */
    private static String mask(String s) {
        return com.onesay.assistant.v2.util.TextUtil.secretState(s);
    }
}
