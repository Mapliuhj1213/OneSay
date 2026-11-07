package com.onesay.assistant.v2;

/**
 * 本机 / 本账号的私有默认值（随仓库的空默认值）。
 *
 * <p>这些值<b>不是密钥</b>，但和某个具体账号绑定（百炼复刻音色 ID、业务空间 ID），不应写死在公开源码里。
 * 构建时 {@code build_linux.sh} 按下面顺序选一份编进 APK：
 * <ol>
 *   <li>{@code $LOCAL_CONFIG/com/onesay/assistant/v2/LocalDefaults.java}（环境变量指定的目录）；</li>
 *   <li>仓库根目录 {@code local-config/com/onesay/assistant/v2/LocalDefaults.java}（未跟踪，见 .gitignore）；</li>
 *   <li>{@code app/config/com/onesay/assistant/v2/LocalDefaults.java}（随仓库的空默认值：云端朗读没有默认音色，等于默认不可用）。</li>
 * </ol>
 * 模板见 {@code local-config.example/}。字段必须是编译期常量（{@code static final String}）。
 */
public final class LocalDefaults {

    private LocalDefaults() {}

    /** 云端朗读（百炼 CosyVoice）默认音色；空串 = 没有默认音色（云端朗读视为未配置，用户可在设置里填）。 */
    public static final String CLOUD_TTS_VOICE = "";

    /** 百炼业务空间 ID（拼专属域名用）；空串 = 直接走 dashscope.aliyuncs.com。 */
    public static final String CLOUD_TTS_WORKSPACE_ID = "";

    /**
     * ★ feat/rain-minutely：和风天气 API Host（控制台 → 项目管理里那个 xxx.re.qweatherapi.com，<b>本账号私有，不进 git</b>）；
     * 空串 = 分钟级降水不可用（开源构建默认），控制台置灰并写明原因。API Key 不在这里——Key 只存设备（Keystore 加密），在控制台填。
     */
    public static final String QWEATHER_HOST = "";

    /** ★ feat/rain-minutely：灵动岛下雨 / 停雨提醒出厂是否打开（开源构建 false；上机版 true）。Key / Host 不齐时无论如何都不跑。 */
    public static final boolean RAIN_ALERTS_DEFAULT = false;
}
