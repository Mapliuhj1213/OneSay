package com.onesay.assistant.v2.apifirst;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * ★ feat/api-first：开关。
 * <ul>
 *   <li>{@link #ROUTER}：路由工具 route.open 是否提供给 AgentLoop（默认开；kill 文件 apifirst.off）；</li>
 *   <li>{@link #KEYED}：第 ② 档「已配置接口」是否参与（个人版默认开，<b>开源版默认关</b>；关了或没有密钥时跳过 ②）。</li>
 * </ul>
 */
public final class ApiFirstSettings {

    private ApiFirstSettings() {}

    public static final String PREFS = "bixby_apifirst";
    public static final String KILL = "apifirst.off";

    /** 第 ② 档出厂默认：个人版 true（开源版 false）。 */
    public static final boolean KEYED_DEFAULT = false;   // 开源版默认关（个人版默认开）

    public static final FeatureSwitch ROUTER = new FeatureSwitch(PREFS, "enabled", true, KILL);
    public static final FeatureSwitch KEYED = new FeatureSwitch(PREFS, "keyed_apis", KEYED_DEFAULT, KILL, "apifirst_keyed.off");
}
